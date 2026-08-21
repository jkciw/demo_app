"""Meshtastic chunk reassembly and Bitcoin Regtest broadcasting."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Protocol


FRAME_PREFIX = "BTC_TX"
MAX_CHUNKS = 512
MAX_TRANSACTION_BYTES = 100_000
SESSION_TTL_SECONDS = 10 * 60
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
    """Consumes BTC_TX frames and sends application-level results to the phone."""

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
        if not text.strip().startswith(f"{FRAME_PREFIX}|"):
            return False
        session_hint = self._session_hint(text)
        try:
            frame = parse_chunk_frame(text)
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
            self._expire_sessions()
            completed = self._completed.get(key)
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
                    self._reject(incoming, "chunk-total-changed")
                    return True
                existing = incoming.chunks.get(frame.index)
                if existing is not None and existing != frame.payload:
                    self._reject(incoming, "chunk-conflict")
                    return True
                incoming.chunks[frame.index] = frame.payload
                incoming.updated_at = self.clock()
                received = len(incoming.chunks)
                self.sink.upsert_transaction(
                    self._view(incoming, status="receiving", chunks_received=received)
                )
                if received == incoming.total:
                    raw_hex = "".join(incoming.chunks[index] for index in range(1, incoming.total + 1))
                    if len(raw_hex) // 2 > MAX_TRANSACTION_BYTES:
                        self._reject(incoming, "too-large")
                        return True
                    del self._incoming[key]

        self._safe_reply(f"BTC_CHUNK_ACK|{frame.session}|{frame.index}", source_id, channel)
        if completed is not None:
            self._send_completed(frame.session, completed, source_id, channel)
        elif raw_hex is not None:
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
            self._safe_reply(f"BTC_ACK|{session}|{txid}", source_id, channel)
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
                }
            )
            self._safe_reply(f"BTC_CONF|{session}|{block_height}", source_id, channel)
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

    def _send_completed(
        self,
        session: str,
        completed: CompletedTransaction,
        source_id: str,
        channel: int,
    ) -> None:
        self._safe_reply(f"BTC_ACK|{session}|{completed.txid}", source_id, channel)
        self._safe_reply(f"BTC_CONF|{session}|{completed.block_height}", source_id, channel)

    def _reject(self, incoming: IncomingTransaction, reason: str) -> None:
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

    def _expire_sessions(self) -> None:
        cutoff = self.clock() - SESSION_TTL_SECONDS
        expired = [key for key, value in self._incoming.items() if value.updated_at < cutoff]
        for key in expired:
            incoming = self._incoming.pop(key)
            self.sink.upsert_transaction(
                {
                    **self._view(incoming, status="error", chunks_received=len(incoming.chunks)),
                    "error": "transfer-timeout",
                }
            )

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
