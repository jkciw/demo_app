#!/usr/bin/env python3
"""Exercise the Stage 2 bridge with a fresh, test-only Regtest transaction."""

from __future__ import annotations

import json
import sys
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "service"))
sys.path.insert(0, str(ROOT / "regtest"))

from bootstrap import Paths, RegtestRpc, WALLET_NAME  # noqa: E402
from monitor import MessageHub  # noqa: E402
from transaction_bridge import BitcoinRegtest, BitcoinTransactionBridge  # noqa: E402


class InlineExecutor:
    def submit(self, function, *args):
        function(*args)


def json_compact(value):
    return json.dumps(value, separators=(",", ":"))


def main() -> int:
    paths = Paths.resolve()
    rpc = RegtestRpc(paths)
    chain = rpc.call("getblockchaininfo")
    if chain.get("chain") != "regtest":
        raise RuntimeError("The dedicated Regtest node is not running")

    # Phone inputs are 0.01 BTC. Selecting a large mature coinbase explicitly
    # keeps this smoke test completely separate from the Alpha/Bravo queues.
    candidates = rpc.call("listunspent", "1", "9999999", "[]", "true", wallet=WALLET_NAME)
    coin = next((item for item in candidates if Decimal(str(item["amount"])) > Decimal("1")), None)
    if coin is None:
        raise RuntimeError("No mature coinbase is available for the bridge smoke test")

    funding_address = rpc.call("getnewaddress", "stage2-smoke-funding", "bech32", wallet=WALLET_NAME)
    change_address = rpc.call("getnewaddress", "stage2-smoke-change", "bech32", wallet=WALLET_NAME)
    parent_input = [{"txid": coin["txid"], "vout": coin["vout"]}]
    change = Decimal(str(coin["amount"])) - Decimal("0.00200000") - Decimal("0.00001000")
    parent_outputs = {funding_address: 0.002, change_address: float(change)}
    parent_raw = rpc.call(
        "createrawtransaction", json_compact(parent_input), json_compact(parent_outputs)
    )
    parent_signed = rpc.call("signrawtransactionwithwallet", parent_raw, wallet=WALLET_NAME)
    parent_txid = rpc.call("sendrawtransaction", parent_signed["hex"])
    mining_address = rpc.call("getnewaddress", "stage2-smoke-mining", "bech32", wallet=WALLET_NAME)
    rpc.call("generatetoaddress", "1", mining_address)

    parent = rpc.call("getrawtransaction", parent_txid, "true")
    funding_vout = next(
        int(output["n"])
        for output in parent["vout"]
        if output.get("scriptPubKey", {}).get("address") == funding_address
    )
    sink = rpc.call("getnewaddress", "stage2-smoke-sink", "bech32", wallet=WALLET_NAME)
    child_raw = rpc.call(
        "createrawtransaction",
        json_compact([{"txid": parent_txid, "vout": funding_vout}]),
        json_compact({sink: 0.00199}),
    )
    child_signed = rpc.call("signrawtransactionwithwallet", child_raw, wallet=WALLET_NAME)

    hub = MessageHub()
    replies: list[str] = []
    bridge = BitcoinTransactionBridge(
        hub,
        BitcoinRegtest(ROOT),
        lambda text, _destination, _channel: replies.append(text),
        executor=InlineExecutor(),
    )
    raw_hex = child_signed["hex"]
    chunks = [raw_hex[index : index + 170] for index in range(0, len(raw_hex), 170)]
    for index, chunk in enumerate(chunks, start=1):
        bridge.handle_text(
            f"BTC_TX|stage2smoke|{index}/{len(chunks)}|{chunk}",
            "!smoke",
            "Stage 2 smoke",
            0,
        )

    transaction = hub.snapshot()["transactions"][0]
    if transaction["status"] != "confirmed":
        raise RuntimeError(f"Bridge smoke failed: {transaction}")
    if not any(reply.startswith("BTC_ACK|stage2smoke|") for reply in replies):
        raise RuntimeError("Bridge did not return BTC_ACK")
    if not any(reply.startswith("BTC_CONF|stage2smoke|") for reply in replies):
        raise RuntimeError("Bridge did not return BTC_CONF")

    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    for station in ("alpha", "bravo"):
        for queued in manifest["phoneTransactions"][station]:
            previous = queued["input"]
            if rpc.call("gettxout", previous["txid"], str(previous["vout"]), "true") is None:
                raise RuntimeError(f"Smoke test touched reserved input {queued['id']}")

    print("Stage 2 bridge smoke passed")
    print(f"  TXID:         {transaction['txid']}")
    print(f"  Block height: {transaction['blockHeight']}")
    print(f"  Chunks:       {len(chunks)}")
    print("  Phone inputs: 4 still unspent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Stage 2 bridge smoke failed: {error}", file=sys.stderr)
        raise SystemExit(1)
