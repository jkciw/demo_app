"""Meshtastic chunk reassembly and Bitcoin Regtest broadcasting."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Protocol


FRAME_PREFIX = "BTC_TX"
BEGIN_PREFIX = "BTC_BEGIN"
RESULT_REQUEST_PREFIX = "BTC_RESULT_REQUEST"
RESULT_ACK_PREFIX = "BTC_RESULT_ACK"
CANCEL_PREFIX = "BTC_CANCEL"
MAX_CHUNKS = 512
MAX_TRANSACTION_BYTES = 100_000
SESSION_TTL_SECONDS = 10 * 60
ACTIVE_SLOT_IDLE_SECONDS = 75
SESSION_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,24}$")
POSITION_PATTERN = re.compile(r"^(\d+)/(\d+)$")
HEX_PATTERN = re.compile(r"^[0-9A-Fa-f]+$")


class BitcoinRpcError(RuntimeError):
    """Raised when Bitcoin Core rejects or cannot process an RPC call."""


class BitcoinRpc(Protocol):
    def check_ready(self) -> dict[str, Any]: ...

    def broadcast(self, raw_hex: str) -> str: ...

    def mine_confirmation(self, txid: str) -> int: ...


class UnavailableBitcoinRpc:
    def __init__(self, detail: str) -> None:
        self.detail = detail

    def check_ready(self) -> dict[str, Any]:
        raise BitcoinRpcError(self.detail)

    def broadcast(self, raw_hex: str) -> str:
        raise BitcoinRpcError(self.detail)

    def mine_confirmation(self, txid: str) -> int:
        raise BitcoinRpcError(self.detail)


class TransactionSink(Protocol):
    def update_bitcoin(self, **values: Any) -> None: ...

    def upsert_transaction(self, transaction: dict[str, Any]) -> None: ...


@dataclass(frozen=True)
class ChunkFrame:
    session: str
    index: int
    total: int
    payload: str


@dataclass(frozen=True)
class BeginFrame:
    session: str
    total: int


@dataclass
class Admission:
    source_id: str
    sender: str
    session: str
    total: int
    channel: int
    updated_at: float = field(default_factory=time.monotonic)

    @property
    def key(self) -> tuple[str, str]:
        return (self.source_id, self.session)


@dataclass
class IncomingTransaction:
    source_id: str
    sender: str
    session: str
    total: int
    channel: int
    chunks: dict[int, str] = field(default_factory=dict)
    updated_at: float = field(default_factory=time.monotonic)


@dataclass(frozen=True)
class CompletedTransaction:
    txid: str
    block_height: int
    chunk_total: int


def parse_chunk_frame(text: str) -> ChunkFrame:
    parts = text.strip().split("|", 3)
    if len(parts) != 4 or parts[0] != FRAME_PREFIX:
        raise ValueError("bad-frame")
    session = parts[1]
    if not SESSION_PATTERN.fullmatch(session):
        raise ValueError("bad-session")
    match = POSITION_PATTERN.fullmatch(parts[2])
    if match is None:
        raise ValueError("bad-position")
    index, total = (int(value) for value in match.groups())
    if total < 1 or total > MAX_CHUNKS or index < 1 or index > total:
        raise ValueError("bad-position")
    payload = parts[3]
    if not payload or len(payload) % 2 or HEX_PATTERN.fullmatch(payload) is None:
        raise ValueError("bad-hex")
    if len(payload) // 2 > MAX_TRANSACTION_BYTES:
        raise ValueError("too-large")
    return ChunkFrame(session=session, index=index, total=total, payload=payload.lower())


def parse_begin_frame(text: str) -> BeginFrame:
    parts = text.strip().split("|")
    if len(parts) != 3 or parts[0] != BEGIN_PREFIX:
        raise ValueError("bad-frame")
    session = parts[1]
    if not SESSION_PATTERN.fullmatch(session):
        raise ValueError("bad-session")
    try:
        total = int(parts[2])
    except ValueError as error:
        raise ValueError("bad-total") from error
    if total < 1 or total > MAX_CHUNKS:
        raise ValueError("bad-total")
    return BeginFrame(session=session, total=total)


class BitcoinRegtest:
    """Small bitcoin-cli wrapper pinned to this dashboard's isolated Regtest data."""

    def __init__(self, project_root: Path, wallet: str = "conference-demo") -> None:
        bitcoin_cli = shutil.which("bitcoin-cli")
        if bitcoin_cli is None:
            raise BitcoinRpcError("bitcoin-cli is not installed or is not on PATH")
        regtest_dir = project_root / "regtest"
        self._base = [
            bitcoin_cli,
            f"-datadir={regtest_dir / 'data'}",
            f"-conf={regtest_dir / 'bitcoin.conf'}",
            "-regtest",
        ]
        self.wallet = wallet
        self._mining_address: str | None = None
        self._lock = threading.Lock()

    def _call(self, method: str, *params: str, wallet: bool = False) -> Any:
        command = list(self._base)
        if wallet:
            command.append(f"-rpcwallet={self.wallet}")
        command.extend([method, *params])
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "unknown RPC error"
            raise BitcoinRpcError(detail)
        output = result.stdout.strip()
        if not output:
            return None
        try:
            return json.loads(output)
        except json.JSONDecodeError:
            return output

    def check_ready(self) -> dict[str, Any]:
        chain = self._call("getblockchaininfo")
        if chain.get("chain") != "regtest":
            raise BitcoinRpcError(f"expected regtest, connected to {chain.get('chain')!r}")
        return {"chain": "regtest", "height": int(chain["blocks"])}

    def broadcast(self, raw_hex: str) -> str:
        expected_txid = str(self._call("decoderawtransaction", raw_hex)["txid"])
        try:
            return str(self._call("sendrawtransaction", raw_hex))
        except BitcoinRpcError as error:
            detail = str(error).lower()
            already_known = (
                "already in block chain" in detail
                or "txn-already-known" in detail
                or "txn-already-in-mempool" in detail
                or "outputs already in utxo set" in detail
            )
            if not already_known:
                raise
            # A phone may retry after the collector restarts. Treat a transaction
            # already present in this Regtest node as the same successful relay.
            self._call("getrawtransaction", expected_txid, "true")
            return expected_txid

    def mine_confirmation(self, txid: str) -> int:
        with self._lock:
            transaction = self._call("getrawtransaction", txid, "true")
            if int(transaction.get("confirmations", 0)) >= 1:
                block = self._call("getblockheader", transaction["blockhash"])
                return int(block["height"])
            try:
                self._call("getwalletinfo", wallet=True)
            except BitcoinRpcError:
                # Bitcoin Core does not necessarily reload a previously created
                # wallet after daemon restart. Recover transparently before mining.
                self._call("loadwallet", self.wallet)
            if self._mining_address is None:
                self._mining_address = str(
                    self._call("getnewaddress", "conference-auto-mine", "bech32", wallet=True)
                )
            self._call("generatetoaddress", "1", self._mining_address)
            transaction = self._call("getrawtransaction", txid, "true")
            if int(transaction.get("confirmations", 0)) < 1:
                raise BitcoinRpcError("transaction was not confirmed after mining")
            block = self._call("getblockheader", transaction["blockhash"])
            return int(block["height"])


class BitcoinTransactionBridge:
    """Serializes phone uploads, reassembles BTC_TX frames, and relays them to Regtest."""

    def __init__(
        self,
        sink: TransactionSink,
        rpc: BitcoinRpc,
        send_reply: Callable[[str, str, int], None],
        *,
        executor: Any | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.sink = sink
        self.rpc = rpc
        self.send_reply = send_reply
        self.clock = clock
        self._executor = executor or ThreadPoolExecutor(max_workers=1, thread_name_prefix="bitcoin")
        self._owns_executor = executor is None
        self._lock = threading.RLock()
        self._incoming: dict[tuple[str, str], IncomingTransaction] = {}
        self._completed: dict[tuple[str, str], CompletedTransaction] = {}
        self._admissions: dict[tuple[str, str], Admission] = {}
        self._submitted: set[tuple[str, str]] = set()
        self._active: Admission | None = None
        self._queued: deque[Admission] = deque()

    @property
    def has_active_transfer(self) -> bool:
        with self._lock:
            # Presence traffic may be the only activity after a phone disappears.
            # Expire an abandoned slot here as well as when the next frame arrives.
            self._expire_sessions_locked()
            return self._active is not None

    def close(self) -> None:
        if self._owns_executor:
            self._executor.shutdown(wait=False, cancel_futures=True)

    def handle_text(
        self,
        text: str,
        source_id: str,
        sender: str,
        channel: int = 0,
    ) -> bool:
        stripped = text.strip()
        if stripped.startswith(f"{BEGIN_PREFIX}|"):
            return self._handle_begin(stripped, source_id, sender, channel)
        if stripped.startswith(f"{RESULT_REQUEST_PREFIX}|"):
            return self._handle_result_request(stripped, source_id, channel)
        if stripped.startswith(f"{RESULT_ACK_PREFIX}|"):
            return self._handle_result_ack(stripped, source_id)
        if stripped.startswith(f"{CANCEL_PREFIX}|"):
            return self._handle_cancel(stripped, source_id, sender, channel)
        if not stripped.startswith(f"{FRAME_PREFIX}|"):
            return False
        session_hint = self._session_hint(text)
        try:
            frame = parse_chunk_frame(stripped)
        except ValueError as error:
            self._safe_reply(f"BTC_NACK|{session_hint}|{error}", source_id, channel)
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{session_hint}",
                    "session": session_hint,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "error",
                    "error": str(error),
                }
            )
            return True

        key = (source_id, frame.session)
        completed: CompletedTransaction | None = None
        raw_hex: str | None = None
        with self._lock:
            self._expire_sessions_locked()
            completed = self._completed.get(key)
            if completed is not None:
                self._safe_reply(f"BTC_CHUNK_ACK|{frame.session}|{frame.index}", source_id, channel)
                self._send_completed(frame.session, completed, source_id, channel)
                return True

            admission = self._admissions.get(key)
            if admission is None:
                admission = Admission(
                    source_id, sender, frame.session, frame.total, channel, self.clock()
                )
                self._admissions[key] = admission
                if self._active is None:
                    self._activate_locked(admission)
                else:
                    self._queued.append(admission)
                    self._refresh_queue_positions_locked()
            else:
                admission.updated_at = self.clock()

            if self._active is None or self._active.key != key:
                self._send_queue_position_locked(admission)
                return True
            if admission.total != frame.total:
                self._fail_admission_locked(admission, "chunk-total-changed")
                return True

            if completed is None:
                incoming = self._incoming.get(key)
                if incoming is None:
                    incoming = IncomingTransaction(
                        source_id=source_id,
                        sender=sender,
                        session=frame.session,
                        total=frame.total,
                        channel=channel,
                    )
                    self._incoming[key] = incoming
                if incoming.total != frame.total:
                    self._reject_locked(incoming, "chunk-total-changed")
                    return True
                existing = incoming.chunks.get(frame.index)
                if existing is not None and existing != frame.payload:
                    self._reject_locked(incoming, "chunk-conflict")
                    return True
                incoming.chunks[frame.index] = frame.payload
                incoming.updated_at = self.clock()
                received = len(incoming.chunks)
                self.sink.upsert_transaction(
                    self._view(incoming, status="receiving", chunks_received=received)
                )
                if received == incoming.total:
                    raw_hex = "".join(
                        incoming.chunks[index] for index in range(1, incoming.total + 1)
                    )
                    if len(raw_hex) // 2 > MAX_TRANSACTION_BYTES:
                        self._reject_locked(incoming, "too-large")
                        return True
                    del self._incoming[key]
                    # Bitcoin Core may receive the transaction after this point,
                    # so a later cancel request cannot promise to undo the relay.
                    self._submitted.add(key)

        self._safe_reply(f"BTC_CHUNK_ACK|{frame.session}|{frame.index}", source_id, channel)
        if raw_hex is not None:
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{frame.session}",
                    "session": frame.session,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "broadcasting",
                    "chunksReceived": frame.total,
                    "chunksTotal": frame.total,
                    "sizeBytes": len(raw_hex) // 2,
                }
            )
            self._executor.submit(
                self._broadcast_and_mine,
                key,
                sender,
                frame.total,
                raw_hex,
                channel,
            )
        return True

    def _broadcast_and_mine(
        self,
        key: tuple[str, str],
        sender: str,
        chunk_total: int,
        raw_hex: str,
        channel: int,
    ) -> None:
        source_id, session = key
        try:
            txid = self.rpc.broadcast(raw_hex)
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{session}",
                    "session": session,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "mining",
                    "chunksReceived": chunk_total,
                    "chunksTotal": chunk_total,
                    "sizeBytes": len(raw_hex) // 2,
                    "txid": txid,
                }
            )
            block_height = self.rpc.mine_confirmation(txid)
            completed = CompletedTransaction(txid, block_height, chunk_total)
            with self._lock:
                self._completed[key] = completed
            self.sink.update_bitcoin(status="online", detail="Regtest ready", height=block_height)
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{session}",
                    "session": session,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "confirmed",
                    "chunksReceived": chunk_total,
                    "chunksTotal": chunk_total,
                    "sizeBytes": len(raw_hex) // 2,
                    "txid": txid,
                    "blockHeight": block_height,
                    "resultAcknowledged": False,
                }
            )
            # The phone requests this stored result after it has consumed the final
            # chunk acknowledgement. Keeping the slot active until BTC_RESULT_ACK
            # prevents the next upload from colliding with result delivery.
        except Exception as error:
            detail = self._error_code(error)
            print(
                f"Bitcoin relay failed session={session} source={source_id}: {error}",
                flush=True,
            )
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{session}",
                    "session": session,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "error",
                    "chunksReceived": chunk_total,
                    "chunksTotal": chunk_total,
                    "error": detail,
                }
            )
            self._safe_reply(f"BTC_NACK|{session}|{detail}", source_id, channel)
            with self._lock:
                self._finish_active_locked(key)

    def _send_completed(
        self,
        session: str,
        completed: CompletedTransaction,
        source_id: str,
        channel: int,
    ) -> None:
        frame = f"BTC_RESULT|{session}|{completed.txid}|{completed.block_height}"
        self._safe_reply(frame, source_id, channel)

    def _reject_locked(self, incoming: IncomingTransaction, reason: str) -> None:
        key = (incoming.source_id, incoming.session)
        self._incoming.pop(key, None)
        self.sink.upsert_transaction(
            {
                **self._view(incoming, status="error", chunks_received=len(incoming.chunks)),
                "error": reason,
            }
        )
        self._safe_reply(
            f"BTC_NACK|{incoming.session}|{reason}", incoming.source_id, incoming.channel
        )
        self._finish_active_locked(key)

    def _handle_begin(self, text: str, source_id: str, sender: str, channel: int) -> bool:
        session_hint = self._session_hint(text)
        try:
            frame = parse_begin_frame(text)
        except ValueError as error:
            self._safe_reply(f"BTC_NACK|{session_hint}|{error}", source_id, channel)
            return True

        key = (source_id, frame.session)
        with self._lock:
            self._expire_sessions_locked()
            completed = self._completed.get(key)
            if completed is not None:
                self._send_completed(frame.session, completed, source_id, channel)
                return True

            existing = self._admissions.get(key)
            if existing is not None:
                existing.updated_at = self.clock()
                if existing.total != frame.total:
                    self._fail_admission_locked(existing, "chunk-total-changed")
                elif self._active is not None and self._active.key == key:
                    self._safe_reply(f"BTC_READY|{frame.session}", source_id, channel)
                else:
                    self._send_queue_position_locked(existing)
                return True

            admission = Admission(
                source_id, sender, frame.session, frame.total, channel, self.clock()
            )
            self._admissions[key] = admission
            if self._active is None:
                self._activate_locked(admission)
            else:
                self._queued.append(admission)
                self._refresh_queue_positions_locked()
                self._send_queue_position_locked(admission)
            return True

    def _handle_result_ack(self, text: str, source_id: str) -> bool:
        parts = text.split("|")
        if len(parts) != 2 or not SESSION_PATTERN.fullmatch(parts[1]):
            return True
        key = (source_id, parts[1])
        with self._lock:
            completed = self._completed.get(key)
            if completed is None:
                return True
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{parts[1]}",
                    "session": parts[1],
                    "sourceId": source_id,
                    "status": "confirmed",
                    "resultAcknowledged": True,
                }
            )
            self._finish_active_locked(key)
        return True

    def _handle_result_request(self, text: str, source_id: str, channel: int) -> bool:
        parts = text.split("|")
        if len(parts) != 2 or not SESSION_PATTERN.fullmatch(parts[1]):
            return True
        key = (source_id, parts[1])
        with self._lock:
            self._expire_sessions_locked()
            admission = self._admissions.get(key)
            if admission is not None:
                admission.updated_at = self.clock()
            completed = self._completed.get(key)
        if completed is not None:
            self._send_completed(parts[1], completed, source_id, channel)
        return True

    def _handle_cancel(
        self,
        text: str,
        source_id: str,
        sender: str,
        channel: int,
    ) -> bool:
        parts = text.split("|")
        if len(parts) != 2 or not SESSION_PATTERN.fullmatch(parts[1]):
            return True
        session = parts[1]
        key = (source_id, session)
        with self._lock:
            if key in self._completed or key in self._submitted:
                self._safe_reply(f"BTC_CANCEL_TOO_LATE|{session}", source_id, channel)
                return True

            admission = self._admissions.get(key)
            if admission is None:
                # Idempotent cancellation lets a phone safely retry this control frame.
                self._safe_reply(f"BTC_CANCELLED|{session}", source_id, channel)
                return True

            incoming = self._incoming.get(key)
            self.sink.upsert_transaction(
                {
                    "id": f"{source_id}:{session}",
                    "session": session,
                    "sender": sender,
                    "sourceId": source_id,
                    "status": "cancelled",
                    "chunksReceived": len(incoming.chunks) if incoming is not None else 0,
                    "chunksTotal": admission.total,
                }
            )
            if self._active is not None and self._active.key == key:
                self._finish_active_locked(key)
            else:
                self._incoming.pop(key, None)
                self._admissions.pop(key, None)
                self._queued = deque(item for item in self._queued if item.key != key)
                self._refresh_queue_positions_locked()
            self._safe_reply(f"BTC_CANCELLED|{session}", source_id, channel)
        return True

    def _activate_locked(self, admission: Admission) -> None:
        self._active = admission
        admission.updated_at = self.clock()
        self.sink.upsert_transaction(
            {
                "id": f"{admission.source_id}:{admission.session}",
                "session": admission.session,
                "sender": admission.sender,
                "sourceId": admission.source_id,
                "status": "reserved",
                "chunksReceived": 0,
                "chunksTotal": admission.total,
                "queuePosition": 0,
            }
        )
        self._safe_reply(f"BTC_READY|{admission.session}", admission.source_id, admission.channel)

    def _send_queue_position_locked(self, admission: Admission) -> None:
        try:
            position = next(
                index
                for index, item in enumerate(self._queued, start=1)
                if item.key == admission.key
            )
        except StopIteration:
            position = 1
        self._safe_reply(
            f"BTC_QUEUED|{admission.session}|{position}",
            admission.source_id,
            admission.channel,
        )

    def _refresh_queue_positions_locked(self) -> None:
        for position, admission in enumerate(self._queued, start=1):
            self.sink.upsert_transaction(
                {
                    "id": f"{admission.source_id}:{admission.session}",
                    "session": admission.session,
                    "sender": admission.sender,
                    "sourceId": admission.source_id,
                    "status": "queued",
                    "chunksReceived": 0,
                    "chunksTotal": admission.total,
                    "queuePosition": position,
                }
            )

    def _finish_active_locked(self, key: tuple[str, str]) -> None:
        self._incoming.pop(key, None)
        self._admissions.pop(key, None)
        self._submitted.discard(key)
        if self._active is None or self._active.key != key:
            self._refresh_queue_positions_locked()
            return
        self._active = None
        while self._queued:
            candidate = self._queued.popleft()
            if candidate.key in self._admissions:
                self._activate_locked(candidate)
                break
        self._refresh_queue_positions_locked()

    def _fail_admission_locked(self, admission: Admission, reason: str) -> None:
        self.sink.upsert_transaction(
            {
                "id": f"{admission.source_id}:{admission.session}",
                "session": admission.session,
                "sender": admission.sender,
                "sourceId": admission.source_id,
                "status": "error",
                "error": reason,
            }
        )
        self._safe_reply(
            f"BTC_NACK|{admission.session}|{reason}",
            admission.source_id,
            admission.channel,
        )
        if self._active is not None and self._active.key == admission.key:
            self._finish_active_locked(admission.key)
        else:
            self._admissions.pop(admission.key, None)
            self._queued = deque(item for item in self._queued if item.key != admission.key)
            self._refresh_queue_positions_locked()

    def _view(
        self,
        incoming: IncomingTransaction,
        *,
        status: str,
        chunks_received: int,
    ) -> dict[str, Any]:
        return {
            "id": f"{incoming.source_id}:{incoming.session}",
            "session": incoming.session,
            "sender": incoming.sender,
            "sourceId": incoming.source_id,
            "status": status,
            "chunksReceived": chunks_received,
            "chunksTotal": incoming.total,
        }

    def _expire_sessions_locked(self) -> None:
        now = self.clock()
        if self._active is not None and self._active.updated_at < now - ACTIVE_SLOT_IDLE_SECONDS:
            self._fail_admission_locked(self._active, "slot-timeout")

        queue_cutoff = now - SESSION_TTL_SECONDS
        expired = [item for item in self._queued if item.updated_at < queue_cutoff]
        for admission in expired:
            self._fail_admission_locked(admission, "queue-timeout")

    def _safe_reply(self, text: str, destination: str, channel: int) -> None:
        try:
            self.send_reply(text, destination, channel)
        except Exception:
            # The dashboard state remains authoritative if the radio reply itself fails.
            pass

    @staticmethod
    def _session_hint(text: str) -> str:
        parts = text.strip().split("|", 2)
        if len(parts) >= 2 and SESSION_PATTERN.fullmatch(parts[1]):
            return parts[1]
        return "unknown"

    @staticmethod
    def _error_code(error: Exception) -> str:
        message = str(error).lower()
        if "missing inputs" in message or "bad-txns-inputs" in message:
            return "inputs-spent"
        if "already in block chain" in message or "txn-already-known" in message:
            return "already-known"
        if "decode" in message or "hex" in message:
            return "invalid-transaction"
        return "rpc-failed"
