#!/usr/bin/env python3
"""Create the isolated Regtest chain and signed conference demo transactions."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


WALLET_NAME = "conference-demo"
FUNDING_OUTPUT_BTC = 0.01
SPEND_OUTPUT_BTC = 0.00999
PHONE_TRANSACTION_COUNT = 4
TOTAL_FUNDING_OUTPUTS = PHONE_TRANSACTION_COUNT + 1  # One extra for the RPC smoke test.


@dataclass(frozen=True)
class Paths:
    root: Path
    data: Path
    generated: Path
    config: Path
    controller: Path
    manifest: Path
    alpha: Path
    bravo: Path

    @classmethod
    def resolve(cls) -> "Paths":
        root = Path(__file__).resolve().parent
        generated = root / "generated"
        return cls(
            root=root,
            data=root / "data",
            generated=generated,
            config=root / "bitcoin.conf",
            controller=root / "regtestctl",
            manifest=generated / "manifest.json",
            alpha=generated / "alpha-dev-transactions.json",
            bravo=generated / "bravo-dev-transactions.json",
        )


class RpcError(RuntimeError):
    pass


class RegtestRpc:
    def __init__(self, paths: Paths) -> None:
        bitcoin_cli = shutil.which("bitcoin-cli")
        if bitcoin_cli is None:
            raise RuntimeError("bitcoin-cli is not installed or is not on PATH")
        self.base = [
            bitcoin_cli,
            f"-datadir={paths.data}",
            f"-conf={paths.config}",
            "-regtest",
        ]

    def call(self, method: str, *params: str, wallet: str | None = None) -> Any:
        command = list(self.base)
        if wallet is not None:
            command.append(f"-rpcwallet={wallet}")
        command.extend([method, *params])
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "unknown RPC error"
            raise RpcError(f"{method} failed: {detail}")
        output = result.stdout.strip()
        if output == "":
            return None
        try:
            return json.loads(output)
        except json.JSONDecodeError:
            return output


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def start_node(paths: Paths) -> None:
    result = subprocess.run([str(paths.controller), "start"], text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError("Could not start the dedicated Regtest node")


def ensure_fresh_or_verify(paths: Paths, rpc: RegtestRpc) -> bool:
    if not paths.manifest.exists():
        return False

    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    chain = rpc.call("getblockchaininfo")
    if chain.get("chain") != "regtest":
        raise RuntimeError(f"Expected regtest, connected to {chain.get('chain')!r}")
    for phone in ("alpha", "bravo"):
        for transaction in manifest["phoneTransactions"][phone]:
            prevout = transaction["input"]
            if rpc.call("gettxout", prevout["txid"], str(prevout["vout"]), "true") is None:
                raise RuntimeError(
                    f"Existing {transaction['id']} input is already spent; the demo bundle cannot be reused."
                )
    print_existing_summary(paths, manifest, rpc)
    return True


def ensure_wallet(rpc: RegtestRpc) -> None:
    loaded = rpc.call("listwallets")
    if WALLET_NAME in loaded:
        return
    available = {item["name"] for item in rpc.call("listwalletdir")["wallets"]}
    if WALLET_NAME in available:
        rpc.call("loadwallet", WALLET_NAME)
    else:
        rpc.call("createwallet", WALLET_NAME)


def create_signed_spend(
    rpc: RegtestRpc,
    funding_txid: str,
    vout: int,
    sink_address: str,
    identifier: str,
    sequence: int,
) -> dict[str, Any]:
    inputs = json.dumps([{"txid": funding_txid, "vout": vout}], separators=(",", ":"))
    outputs = json.dumps([{sink_address: SPEND_OUTPUT_BTC}], separators=(",", ":"))
    raw = rpc.call("createrawtransaction", inputs, outputs)
    signed = rpc.call("signrawtransactionwithwallet", raw, wallet=WALLET_NAME)
    if not signed.get("complete"):
        raise RuntimeError(f"Bitcoin Core did not fully sign {identifier}")
    raw_hex = signed["hex"]
    decoded = rpc.call("decoderawtransaction", raw_hex)
    return {
        "id": identifier,
        "sequence": sequence,
        "expectedTxid": decoded["txid"],
        "rawTxHex": raw_hex,
        "sizeBytes": len(bytes.fromhex(raw_hex)),
        "input": {"txid": funding_txid, "vout": vout},
        "outputBtc": f"{SPEND_OUTPUT_BTC:.8f}",
    }


def generate(paths: Paths, rpc: RegtestRpc) -> None:
    chain = rpc.call("getblockchaininfo")
    if chain.get("chain") != "regtest":
        raise RuntimeError(f"Expected regtest, connected to {chain.get('chain')!r}")
    if int(chain.get("blocks", 0)) != 0:
        raise RuntimeError("The dedicated Regtest chain is not empty; refusing to replace it")

    ensure_wallet(rpc)
    mining_address = rpc.call("getnewaddress", "conference-mining", "bech32", wallet=WALLET_NAME)
    rpc.call("generatetoaddress", "101", mining_address)

    funding_addresses = [
        rpc.call("getnewaddress", f"conference-funding-{index}", "bech32", wallet=WALLET_NAME)
        for index in range(TOTAL_FUNDING_OUTPUTS)
    ]
    funding_outputs = {address: FUNDING_OUTPUT_BTC for address in funding_addresses}
    funding_txid = rpc.call(
        "sendmany",
        "",
        json.dumps(funding_outputs, separators=(",", ":")),
        wallet=WALLET_NAME,
    )
    funding_block_hash = rpc.call("generatetoaddress", "1", mining_address)[0]

    funding_tx = rpc.call("getrawtransaction", funding_txid, "true")
    vout_by_address: dict[str, int] = {}
    for output in funding_tx["vout"]:
        address = output.get("scriptPubKey", {}).get("address")
        if address in funding_outputs:
            vout_by_address[address] = int(output["n"])
    if len(vout_by_address) != TOTAL_FUNDING_OUTPUTS:
        raise RuntimeError("Could not map every funding address to a transaction output")

    sink_address = rpc.call("getnewaddress", "conference-sink", "bech32", wallet=WALLET_NAME)
    ordered_vouts = [vout_by_address[address] for address in funding_addresses]
    smoke = create_signed_spend(
        rpc, funding_txid, ordered_vouts[0], sink_address, "rpc-smoke", 0
    )

    alpha = [
        create_signed_spend(
            rpc,
            funding_txid,
            ordered_vouts[index],
            sink_address,
            f"alpha-{index:03d}",
            index,
        )
        for index in (1, 2)
    ]
    bravo = [
        create_signed_spend(
            rpc,
            funding_txid,
            ordered_vouts[index],
            sink_address,
            f"bravo-{index - 2:03d}",
            index - 2,
        )
        for index in (3, 4)
    ]

    broadcast_txid = rpc.call("sendrawtransaction", smoke["rawTxHex"])
    if broadcast_txid != smoke["expectedTxid"]:
        raise RuntimeError("Smoke transaction TXID differed from its precomputed TXID")
    confirmation_block_hash = rpc.call("generatetoaddress", "1", mining_address)[0]
    confirmed = rpc.call("getrawtransaction", broadcast_txid, "true")
    if int(confirmed.get("confirmations", 0)) < 1:
        raise RuntimeError("Smoke transaction was not confirmed after mining")

    for transaction in alpha + bravo:
        prevout = transaction["input"]
        if rpc.call("gettxout", prevout["txid"], str(prevout["vout"]), "true") is None:
            raise RuntimeError(f"Development transaction input is unexpectedly spent: {transaction['id']}")

    alpha_asset = {"network": "regtest", "station": "Alpha", "transactions": alpha}
    bravo_asset = {"network": "regtest", "station": "Bravo", "transactions": bravo}
    manifest = {
        "version": 1,
        "network": "regtest",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "bitcoinCoreVersion": rpc.call("getnetworkinfo")["subversion"],
        "rpcPort": 18443,
        "wallet": WALLET_NAME,
        "funding": {
            "txid": funding_txid,
            "blockHash": funding_block_hash,
            "outputBtc": f"{FUNDING_OUTPUT_BTC:.8f}",
            "outputCount": TOTAL_FUNDING_OUTPUTS,
        },
        "smokeTest": {
            "txid": broadcast_txid,
            "blockHash": confirmation_block_hash,
            "confirmations": int(confirmed["confirmations"]),
        },
        "phoneTransactions": {"alpha": alpha, "bravo": bravo},
        "blockHeight": int(rpc.call("getblockcount")),
    }
    write_json(paths.alpha, alpha_asset)
    write_json(paths.bravo, bravo_asset)
    write_json(paths.manifest, manifest)
    print_existing_summary(paths, manifest, rpc)


def print_existing_summary(paths: Paths, manifest: dict[str, Any], rpc: RegtestRpc) -> None:
    print("Stage 1 Regtest bundle is ready")
    print(f"  Block height: {rpc.call('getblockcount')}")
    print(f"  Funding TXID: {manifest['funding']['txid']}")
    print(f"  Smoke TXID:   {manifest['smokeTest']['txid']}")
    print(f"  Alpha queue:  {len(manifest['phoneTransactions']['alpha'])} transactions")
    print(f"  Bravo queue:  {len(manifest['phoneTransactions']['bravo'])} transactions")
    print(f"  Manifest:     {paths.manifest}")


def main() -> int:
    paths = Paths.resolve()
    paths.data.mkdir(parents=True, exist_ok=True)
    paths.generated.mkdir(parents=True, exist_ok=True)
    start_node(paths)
    rpc = RegtestRpc(paths)
    if ensure_fresh_or_verify(paths, rpc):
        return 0
    generate(paths, rpc)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, RpcError) as error:
        print(f"Stage 1 bootstrap failed: {error}", file=sys.stderr)
        raise SystemExit(1)
