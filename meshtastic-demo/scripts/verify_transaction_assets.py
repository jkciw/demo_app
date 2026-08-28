#!/usr/bin/env python3
"""Validate the four presigned Regtest queues packaged in the conference APK."""

from __future__ import annotations

from pathlib import Path


EXPECTED_PER_IDENTITY = 120
ASSETS = {
    "Alice": "regtest_transactions_alpha.txt",
    "Bob": "regtest_transactions_bravo.txt",
    "Charlie": "regtest_transactions_charlie.txt",
    "Dana": "regtest_transactions_dana.txt",
}


def transaction_lines(path: Path) -> list[str]:
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def main() -> int:
    project = Path(__file__).resolve().parent.parent
    assets = project / "app" / "src" / "main" / "assets"
    all_transactions: list[str] = []

    for identity, filename in ASSETS.items():
        path = assets / filename
        if not path.is_file():
            raise RuntimeError(f"Missing {identity} transaction queue: {path}")
        transactions = transaction_lines(path)
        if len(transactions) != EXPECTED_PER_IDENTITY:
            raise RuntimeError(
                f"{identity} queue contains {len(transactions)} transactions; "
                f"expected {EXPECTED_PER_IDENTITY}",
            )
        for index, raw_hex in enumerate(transactions, start=1):
            try:
                raw_bytes = bytes.fromhex(raw_hex)
            except ValueError as error:
                raise RuntimeError(f"{identity} transaction {index} is not valid hex") from error
            if len(raw_bytes) < 60 or len(raw_bytes) > 500:
                raise RuntimeError(
                    f"{identity} transaction {index} has unexpected size {len(raw_bytes)} bytes",
                )
        if len(set(transactions)) != len(transactions):
            raise RuntimeError(f"{identity} queue contains duplicate raw transactions")
        all_transactions.extend(transactions)
        print(f"{identity}: {len(transactions)} valid transactions")

    if len(set(all_transactions)) != len(all_transactions):
        raise RuntimeError("Raw transactions are duplicated across phone identities")

    print(f"All queues valid: {len(all_transactions)} unique presigned Regtest transactions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
