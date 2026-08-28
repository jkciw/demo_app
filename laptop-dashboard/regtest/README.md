# Conference Regtest bundle

This directory owns a dedicated Bitcoin Core Regtest chain for the conference
demo. It does not use the normal Bitcoin Core data directory.

Create the development bundle:

```sh
python3 bootstrap.py
```

The first run mines the funding chain, creates one RPC smoke transaction, and
leaves four independent signed transactions unspent under `generated/`: two
for Alpha and two for Bravo. Later runs verify and report the existing bundle
without replacing it.

Control or inspect the node:

```sh
./regtestctl start
./regtestctl status
./regtestctl cli getblockchaininfo
./regtestctl stop
```

The signed transactions depend on the exact chain under `data/`. Do not delete
that directory while using the generated transactions.

Verify the laptop transaction bridge with a fresh test-only transaction:

```sh
python3 bridge_smoke.py
```

This mines two additional blocks but does not spend any Alpha or Bravo input.

Replenish the APK with fresh, independent phone transactions without resetting
the existing Regtest chain:

```sh
python3 replenish_phone_transactions.py --per-phone 120
```

This replaces the Alice, Bob, Charlie, and Dana queue assets with 120 fresh transactions per
identity in `meshtastic-demo` and writes an
ignored audit manifest to `generated/phone-queue-latest.json`. Rebuild the APK
after replenishing. Existing confirmed transactions and dashboard history remain
valid.
