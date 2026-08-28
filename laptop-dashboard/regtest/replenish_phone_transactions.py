#!/usr/bin/env python3
"""Create fresh, independent Regtest transactions for the phone APK queues."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from bootstrap import (
    FUNDING_OUTPUT_BTC,
    WALLET_NAME,
    Paths,
    RegtestRpc,
    create_signed_spend,
    ensure_wallet,
    start_node,
    write_json,
)


DEFAULT_PER_PHONE = 120
PHONE_QUEUES = (
    ("alice", "regtest_transactions_alpha.txt"),
    ("bob", "regtest_transactions_bravo.txt"),
    ("charlie", "regtest_transactions_charlie.txt"),
    ("dana", "regtest_transactions_dana.txt"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fund and sign fresh, mutually independent Regtest transactions, then replace "
            "the Alice, Bob, Charlie, and Dana APK queue assets."
        ),
    )
    parser.add_argument(
        "--per-phone",
        type=int,
        default=DEFAULT_PER_PHONE,
        help=f"fresh transactions for each phone (default: {DEFAULT_PER_PHONE})",
    )
    return parser.parse_args()


def write_asset(path: Path, identity: str, transactions: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    lines = [
        f"# {identity} Regtest queue. One fresh signed raw transaction hex per line.",
        *(str(transaction["rawTxHex"]) for transaction in transactions),
    ]
    temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    args = parse_args()
    if args.per_phone < 1:
        raise RuntimeError("--per-phone must be at least 1")
    if args.per_phone > 200:
        raise RuntimeError("--per-phone cannot exceed 200 in one replenishment")

    paths = Paths.resolve()
    start_node(paths)
    rpc = RegtestRpc(paths)
    chain = rpc.call("getblockchaininfo")
    if chain.get("chain") != "regtest":
        raise RuntimeError(f"Expected regtest, connected to {chain.get('chain')!r}")
    ensure_wallet(rpc)

    total = args.per_phone * len(PHONE_QUEUES)
    generated_at = datetime.now(timezone.utc)
    batch_id = generated_at.strftime("%Y%m%d%H%M%S")
    mining_address = rpc.call(
        "getnewaddress",
        f"queue-{batch_id}-mining",
        "bech32",
        wallet=WALLET_NAME,
    )
    funding_addresses = [
        rpc.call(
            "getnewaddress",
            f"queue-{batch_id}-{index:03d}",
            "bech32",
            wallet=WALLET_NAME,
        )
        for index in range(total)
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
    if len(vout_by_address) != total:
        raise RuntimeError("Could not map every fresh funding output")

    sink_address = rpc.call(
        "getnewaddress",
        f"queue-{batch_id}-sink",
        "bech32",
        wallet=WALLET_NAME,
    )
    phone_transactions: dict[str, list[dict[str, object]]] = {}
    for phone_index, (identity, _) in enumerate(PHONE_QUEUES):
        start = phone_index * args.per_phone
        addresses = funding_addresses[start : start + args.per_phone]
        phone_transactions[identity] = [
            create_signed_spend(
                rpc=rpc,
                funding_txid=funding_txid,
                vout=vout_by_address[address],
                sink_address=sink_address,
                identifier=f"{identity}-{batch_id}-{sequence:03d}",
                sequence=sequence,
            )
            for sequence, address in enumerate(addresses, start=1)
        ]
    transactions = [
        transaction
        for identity, _ in PHONE_QUEUES
        for transaction in phone_transactions[identity]
    ]

    for transaction in transactions:
        prevout = transaction["input"]
        if not isinstance(prevout, dict):
            raise RuntimeError(f"Invalid input metadata for {transaction['id']}")
        if rpc.call("gettxout", str(prevout["txid"]), str(prevout["vout"]), "true") is None:
            raise RuntimeError(f"Fresh input is unexpectedly unavailable: {transaction['id']}")

    repository_root = paths.root.parent.parent
    assets = repository_root / "meshtastic-demo" / "app" / "src" / "main" / "assets"
    asset_paths: dict[str, Path] = {}
    for identity, asset_name in PHONE_QUEUES:
        asset_path = assets / asset_name
        asset_paths[identity] = asset_path
        write_asset(asset_path, identity.title(), phone_transactions[identity])

    manifest = {
        "version": 1,
        "network": "regtest",
        "generatedAt": generated_at.isoformat(timespec="seconds"),
        "fundingTxid": funding_txid,
        "fundingBlockHash": funding_block_hash,
        "blockHeight": int(rpc.call("getblockcount")),
        "perPhone": args.per_phone,
        "phoneTransactions": phone_transactions,
    }
    write_json(paths.generated / "phone-queue-latest.json", manifest)

    print("Fresh phone transaction queues are ready")
    print(f"  Block height: {manifest['blockHeight']}")
    print(f"  Funding TXID: {funding_txid}")
    for identity, _ in PHONE_QUEUES:
        print(f"  {identity.title()} queue: {len(phone_transactions[identity])} fresh transactions")
        print(f"  {identity.title()} asset: {asset_paths[identity]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
