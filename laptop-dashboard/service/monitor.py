#!/usr/bin/env python3
"""Local dual-transport message collector for the field dashboard."""

from __future__ import annotations

import argparse
import json
import os
import queue
import signal
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from transaction_bridge import (
    BitcoinRegtest,
    BitcoinRpcError,
    BitcoinTransactionBridge,
    UnavailableBitcoinRpc,
)


NODE_NAMES = {
    "!2303a141": "Gateway",
}
BITCOIN_REPLY_INTERVAL_SECONDS = 3.0
PRESENCE_PREFIX = "DEMO_PRESENCE"
PRESENCE_REQUEST = "DEMO_PRESENCE_REQUEST|1"
PRESENCE_INTERVAL_SECONDS = 60.0
PRESENCE_INITIAL_DELAYS_SECONDS = (0.0, 2.0, 3.0)


def presence_announcement(identity: str, session: str) -> str:
    if identity not in {"ALICE", "BOB", "GATEWAY"}:
        raise ValueError("Unknown conference identity")
    if not session or len(session) > 24 or not all(
        character.isalnum() or character in "_-" for character in session
    ):
        raise ValueError("Invalid presence session")
    return f"{PRESENCE_PREFIX}|1|{identity}|{session}"


def parse_presence_announcement(text: str) -> tuple[str, str] | None:
    parts = text.strip().split("|")
    if len(parts) != 4 or parts[0] != PRESENCE_PREFIX or parts[1] != "1":
        return None
    identity, session = parts[2], parts[3]
    try:
        presence_announcement(identity, session)
    except ValueError:
        return None
    return identity, session


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class MessageHub:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._messages: deque[dict[str, Any]] = deque(maxlen=500)
        self._subscribers: list[queue.Queue[dict[str, Any]]] = []
        self._transports: dict[str, dict[str, Any]] = {
            "meshtastic": {"status": "standby", "port": None, "detail": "Not started"},
            "reticulum": {"status": "standby", "port": None, "detail": "Not configured"},
        }
        self._bitcoin: dict[str, Any] = {
            "status": "standby",
            "network": "regtest",
            "rpcPort": 18443,
            "detail": "Waiting for Bitcoin Core",
        }
        self._transactions: deque[dict[str, Any]] = deque(maxlen=100)

    def update_transport(self, name: str, **values: Any) -> None:
        with self._lock:
            self._transports[name] = {**self._transports[name], **values}
        self._broadcast()

    def add_message(self, message: dict[str, Any]) -> None:
        with self._lock:
            message = {
                "id": message.get("id") or uuid.uuid4().hex,
                "receivedAt": message.get("receivedAt") or utc_now(),
                **message,
            }
            if any(existing["id"] == message["id"] for existing in self._messages):
                return
            self._messages.append(message)
        self._broadcast()

    def update_bitcoin(self, **values: Any) -> None:
        with self._lock:
            self._bitcoin = {**self._bitcoin, **values}
        self._broadcast()

    def upsert_transaction(self, transaction: dict[str, Any]) -> None:
        with self._lock:
            now = utc_now()
            transaction_id = transaction.get("id") or uuid.uuid4().hex
            existing_index = next(
                (
                    index
                    for index, existing in enumerate(self._transactions)
                    if existing["id"] == transaction_id
                ),
                None,
            )
            if existing_index is None:
                value = {
                    "id": transaction_id,
                    "receivedAt": transaction.get("receivedAt") or now,
                    "updatedAt": now,
                    **transaction,
                }
                self._transactions.append(value)
            else:
                existing = self._transactions[existing_index]
                self._transactions[existing_index] = {
                    **existing,
                    **transaction,
                    "id": transaction_id,
                    "updatedAt": now,
                }
        self._broadcast()

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "generatedAt": utc_now(),
                "transports": json.loads(json.dumps(self._transports)),
                "bitcoin": json.loads(json.dumps(self._bitcoin)),
                "transactions": list(self._transactions),
                "messages": list(self._messages),
            }

    def subscribe(self) -> queue.Queue[dict[str, Any]]:
        subscriber: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=4)
        with self._lock:
            self._subscribers.append(subscriber)
        return subscriber

    def unsubscribe(self, subscriber: queue.Queue[dict[str, Any]]) -> None:
        with self._lock:
            if subscriber in self._subscribers:
                self._subscribers.remove(subscriber)

    def _broadcast(self) -> None:
        snapshot = self.snapshot()
        with self._lock:
            for subscriber in list(self._subscribers):
                try:
                    subscriber.put_nowait(snapshot)
                except queue.Full:
                    try:
                        subscriber.get_nowait()
                        subscriber.put_nowait(snapshot)
                    except queue.Empty:
                        pass


class MeshtasticAdapter(threading.Thread):
    def __init__(
        self,
        hub: MessageHub,
        port: str,
        stop_event: threading.Event,
        bitcoin_rpc: BitcoinRegtest,
    ) -> None:
        super().__init__(name="meshtastic-adapter", daemon=True)
        self.hub = hub
        self.port = port
        self.stop_event = stop_event
        self.interface: Any = None
        self._send_lock = threading.Lock()
        self._presence_wakeup = threading.Event()
        self._presence_session = uuid.uuid4().hex[:12]
        self._presence_names: dict[str, str] = {}
        self._presence_source_by_identity: dict[str, str] = {}
        self._transaction_replies: queue.Queue[tuple[str, str, int]] = queue.Queue()
        self.transaction_bridge = BitcoinTransactionBridge(
            hub, bitcoin_rpc, self._send_transaction_reply
        )

    def run(self) -> None:
        self.hub.update_transport(
            "meshtastic", status="connecting", port=self.port, detail="Opening radio"
        )
        try:
            from pubsub import pub
            from meshtastic.serial_interface import SerialInterface

            pub.subscribe(self._on_text, "meshtastic.receive.text")
            self.interface = SerialInterface(devPath=self.port)
            threading.Thread(
                target=self._transaction_reply_loop,
                name="meshtastic-bitcoin-replies",
                daemon=True,
            ).start()
            threading.Thread(
                target=self._presence_loop,
                name="meshtastic-gateway-presence",
                daemon=True,
            ).start()
            node_count = len(getattr(self.interface, "nodes", {}) or {})
            self.hub.update_transport(
                "meshtastic",
                status="online",
                port=self.port,
                detail="LongFast / TW",
                knownNodes=node_count,
            )
            self.stop_event.wait()
        except Exception as exc:
            self.hub.update_transport(
                "meshtastic", status="error", port=self.port, detail=str(exc)
            )
        finally:
            self.transaction_bridge.close()
            if self.interface is not None:
                try:
                    self.interface.close()
                except Exception:
                    pass

    def _on_text(self, packet: dict[str, Any], interface: Any = None) -> None:
        decoded = packet.get("decoded", {})
        text = decoded.get("text")
        if text is None:
            payload = decoded.get("payload", b"")
            text = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else str(payload)

        source = packet.get("fromId") or f"!{packet.get('from', 0):08x}"
        text = str(text)
        if text == PRESENCE_REQUEST:
            self._presence_wakeup.set()
            return
        presence = parse_presence_announcement(text)
        if presence is not None:
            identity, _session = presence
            self._record_presence(source, identity)
            return

        sender = self._presence_names.get(source) or NODE_NAMES.get(source, source)
        channel = packet.get("channel", 0)
        if self.transaction_bridge.handle_text(
            text, source, sender, channel if isinstance(channel, int) else 0
        ):
            return
        hop_start = packet.get("hopStart")
        hop_limit = packet.get("hopLimit")
        hops = max(0, hop_start - hop_limit) if isinstance(hop_start, int) and isinstance(hop_limit, int) else None
        self.hub.add_message(
            {
                "id": f"meshtastic-{packet.get('id', uuid.uuid4().hex)}",
                "transport": "meshtastic",
                "sender": sender,
                "sourceId": source,
                "text": text,
                "rssi": packet.get("rxRssi"),
                "snr": packet.get("rxSnr"),
                "hops": hops,
            }
        )

    def _send_transaction_reply(self, text: str, destination: str, channel: int) -> None:
        self._transaction_replies.put((text, destination, channel))

    def _record_presence(self, source: str, identity: str) -> None:
        display_name = identity.title()
        previous_source = self._presence_source_by_identity.get(identity)
        if previous_source is not None and previous_source != source:
            self._presence_names.pop(previous_source, None)
        previous_identity = self._presence_names.get(source)
        if previous_identity is not None and previous_identity.upper() != identity:
            self._presence_source_by_identity.pop(previous_identity.upper(), None)
        self._presence_source_by_identity[identity] = source
        self._presence_names[source] = display_name

    def _send_gateway_presence(self) -> None:
        if self.interface is None:
            return
        frame = presence_announcement("GATEWAY", self._presence_session)
        with self._send_lock:
            self.interface.sendText(frame, channelIndex=0)
        print(f"Gateway presence: {frame}", flush=True)

    def _presence_loop(self) -> None:
        try:
            for delay_seconds in PRESENCE_INITIAL_DELAYS_SECONDS:
                if self.stop_event.wait(delay_seconds):
                    return
                self._send_gateway_presence()
            while not self.stop_event.is_set():
                self._presence_wakeup.wait(PRESENCE_INTERVAL_SECONDS)
                self._presence_wakeup.clear()
                if self.stop_event.is_set():
                    return
                self._send_gateway_presence()
        except Exception as error:
            print(f"Gateway presence failed: {error}", flush=True)

    def _transaction_reply_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                text, destination, channel = self._transaction_replies.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                if self.interface is None:
                    raise RuntimeError("Meshtastic interface is not connected")
                # Direct-addressed application messages are not reliable in this
                # three-node demo. Broadcast the session-specific reply on the
                # proven primary-channel path.
                with self._send_lock:
                    self.interface.sendText(text, channelIndex=channel)
                print(f"Bitcoin gateway reply for {destination}: {text}", flush=True)
            except Exception as error:
                print(
                    f"Bitcoin gateway reply failed for {destination}: {error}",
                    flush=True,
                )
            finally:
                self._transaction_replies.task_done()
            self.stop_event.wait(BITCOIN_REPLY_INTERVAL_SECONDS)

class ReticulumAdapter(threading.Thread):
    def __init__(
        self,
        hub: MessageHub,
        port: str,
        stop_event: threading.Event,
        state_dir: Path,
        frequency: int,
        bandwidth: int,
        txpower: int,
        spreading_factor: int,
        coding_rate: int,
    ) -> None:
        super().__init__(name="reticulum-adapter", daemon=True)
        self.hub = hub
        self.port = port
        self.stop_event = stop_event
        self.state_dir = state_dir
        self.frequency = frequency
        self.bandwidth = bandwidth
        self.txpower = txpower
        self.spreading_factor = spreading_factor
        self.coding_rate = coding_rate
        self.router: Any = None
        self.destination: Any = None

    def run(self) -> None:
        self.hub.update_transport(
            "reticulum", status="connecting", port=self.port, detail="Opening RNode"
        )
        try:
            import LXMF
            import RNS

            config_dir = self.state_dir / "reticulum"
            config_dir.mkdir(parents=True, exist_ok=True)
            (config_dir / "config").write_text(self._config_text(), encoding="utf-8")

            RNS.Reticulum(str(config_dir), loglevel=3)
            self.router = LXMF.LXMRouter(storagepath=str(self.state_dir / "lxmf"))
            identity_path = self.state_dir / "dashboard_identity"
            identity = RNS.Identity.from_file(str(identity_path)) if identity_path.exists() else None
            if identity is None:
                identity = RNS.Identity()
                identity.to_file(str(identity_path))

            self.destination = self.router.register_delivery_identity(
                identity, display_name="Unified Message Console", stamp_cost=None
            )
            if self.destination is None:
                raise RuntimeError("Could not register the laptop LXMF destination")
            self.router.register_delivery_callback(self._on_delivery)
            destination_hash = self.destination.hash.hex()
            self.router.announce(self.destination.hash)
            self.hub.update_transport(
                "reticulum",
                status="online",
                port=self.port,
                detail=f"{self.frequency / 1_000_000:.3f} MHz / SF{self.spreading_factor}",
                destinationHash=destination_hash,
            )

            while not self.stop_event.wait(120):
                self.router.announce(self.destination.hash)
        except Exception as exc:
            self.hub.update_transport(
                "reticulum", status="error", port=self.port, detail=str(exc)
            )

    def _config_text(self) -> str:
        return f"""[reticulum]
  enable_transport = No
  share_instance = No

[logging]
  loglevel = 3

[interfaces]
  [[Dashboard RNode]]
    type = RNodeInterface
    enabled = Yes
    port = {self.port}
    frequency = {self.frequency}
    bandwidth = {self.bandwidth}
    txpower = {self.txpower}
    spreadingfactor = {self.spreading_factor}
    codingrate = {self.coding_rate}
"""

    def _on_delivery(self, message: Any) -> None:
        content = getattr(message, "content", "")
        if isinstance(content, bytes):
            content = content.decode("utf-8", errors="replace")
        title = getattr(message, "title", "")
        if isinstance(title, bytes):
            title = title.decode("utf-8", errors="replace")
        source_hash = getattr(message, "source_hash", b"")
        source_id = source_hash.hex() if isinstance(source_hash, bytes) else str(source_hash)
        message_hash = getattr(message, "hash", b"")
        message_id = message_hash.hex() if isinstance(message_hash, bytes) else uuid.uuid4().hex
        self.hub.add_message(
            {
                "id": f"reticulum-{message_id}",
                "transport": "reticulum",
                "sender": source_id[:10] if source_id else "Reticulum peer",
                "sourceId": source_id,
                "title": str(title),
                "text": str(content),
            }
        )


def make_handler(hub: MessageHub) -> type[BaseHTTPRequestHandler]:
    class DashboardHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_OPTIONS(self) -> None:
            self.send_response(HTTPStatus.NO_CONTENT)
            self._cors_headers()
            self.end_headers()

        def do_GET(self) -> None:
            if self.path in ("/", "/health"):
                self._send_json({"ok": True})
            elif self.path == "/api/snapshot":
                self._send_json(hub.snapshot())
            elif self.path == "/api/events":
                self._send_events()
            else:
                self._send_json({"error": "not_found"}, HTTPStatus.NOT_FOUND)

        def _send_json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self._cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_events(self) -> None:
            subscriber = hub.subscribe()
            self.send_response(HTTPStatus.OK)
            self._cors_headers()
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            try:
                initial = json.dumps(hub.snapshot(), separators=(",", ":"))
                self.wfile.write(f"data:{initial}\n\n".encode("utf-8"))
                self.wfile.flush()
                while True:
                    try:
                        snapshot = subscriber.get(timeout=15)
                        payload = json.dumps(snapshot, separators=(",", ":"))
                        self.wfile.write(f"data:{payload}\n\n".encode("utf-8"))
                    except queue.Empty:
                        self.wfile.write(b":keepalive\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                hub.unsubscribe(subscriber)

        def _cors_headers(self) -> None:
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")

        def log_message(self, fmt: str, *args: Any) -> None:
            return

    return DashboardHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Meshtastic + Reticulum laptop monitor")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--http-port", type=int, default=8765)
    parser.add_argument("--meshtastic-port", default=os.getenv("MESHTASTIC_PORT", "/dev/cu.usbmodem1101"))
    parser.add_argument("--no-meshtastic", action="store_true")
    parser.add_argument("--reticulum-port", default=os.getenv("RETICULUM_PORT"))
    parser.add_argument("--reticulum-frequency", type=int, default=925_875_000)
    parser.add_argument("--reticulum-bandwidth", type=int, default=250_000)
    parser.add_argument("--reticulum-txpower", type=int, default=17)
    parser.add_argument("--reticulum-sf", type=int, default=9)
    parser.add_argument("--reticulum-cr", type=int, default=5)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    stop_event = threading.Event()
    state_dir = Path(__file__).resolve().parent.parent / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    hub = MessageHub()
    project_root = Path(__file__).resolve().parent.parent

    bitcoin_rpc: Any
    try:
        bitcoin_rpc = BitcoinRegtest(project_root)
        bitcoin_state = bitcoin_rpc.check_ready()
        hub.update_bitcoin(
            status="online",
            detail="Regtest ready",
            height=bitcoin_state["height"],
        )
    except BitcoinRpcError as exc:
        hub.update_bitcoin(status="error", detail=str(exc))
        bitcoin_rpc = UnavailableBitcoinRpc(str(exc))

    if not args.no_meshtastic:
        MeshtasticAdapter(hub, args.meshtastic_port, stop_event, bitcoin_rpc).start()
    if args.reticulum_port:
        ReticulumAdapter(
            hub,
            args.reticulum_port,
            stop_event,
            state_dir,
            args.reticulum_frequency,
            args.reticulum_bandwidth,
            args.reticulum_txpower,
            args.reticulum_sf,
            args.reticulum_cr,
        ).start()
    else:
        hub.update_transport(
            "reticulum", status="standby", port=None, detail="Connect RNode and supply its port"
        )

    server = ThreadingHTTPServer((args.host, args.http_port), make_handler(hub))

    def request_shutdown(*_: Any) -> None:
        # BaseServer.shutdown() must run on a different thread from
        # serve_forever(), otherwise Python deliberately waits on itself.
        stop_event.set()
        threading.Thread(target=server.shutdown, name="http-shutdown", daemon=True).start()

    signal.signal(signal.SIGINT, request_shutdown)
    signal.signal(signal.SIGTERM, request_shutdown)
    print(f"Dashboard service: http://{args.host}:{args.http_port}", flush=True)
    try:
        server.serve_forever()
    finally:
        stop_event.set()
        server.server_close()


if __name__ == "__main__":
    main()
