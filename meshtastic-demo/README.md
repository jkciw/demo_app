# Meshtastic conference demo

This standalone Android app is the Meshtastic half of the resilient communications demo. It uses
the official Meshtastic SDK `0.1.0` over BLE, broadcasts text on primary channel `0`, displays real
inbound text packets, and labels a broadcast delivery signal as **relayed by mesh** rather than
claiming that a named phone received it.

## Pair the attached radios

Build the two station APKs without device-specific values:

```shell
./gradlew :app:assembleMeshADebug :app:assembleMeshBDebug
```

Before the conference, pair each phone with its physically attached Meshtastic radio in Android
Bluetooth settings and remove any stale Meshtastic pairings. On first launch the app checks the
phone's bonded devices for Meshtastic radios. It automatically binds when exactly one is found; if
several are paired, it asks the operator to select one. Android 12+ can also use BLE service
discovery as a fallback. Android 8–11 deliberately avoid active discovery so system Location can
remain off. The selection is stored only on the phone and can be cleared with **Change radio**.

After permission is granted, a foreground connected-device service owns the app's single
Meshtastic client. The persistent **radio link** notification confirms that the service is alive.
Leaving the screen, locking the phone, or Android recreating the activity no longer closes BLE;
**Change radio** is the operator-controlled disconnect. If the SDK declares an otherwise healthy
idle BLE session stale, the service rebuilds the client with bounded backoff and completes a fresh
handshake without reopening the app. For the conference, also exempt the app from the phone's
battery optimisation and keep the official Meshtastic app closed so only one phone app owns each
radio.

## Hardware proof

Install `meshA` on M1 (paired to LT1) and `meshB` on M2 (paired to LT2). Confirm that each app
shows its attached radio's name. With Wi-Fi and cellular
disabled, verify M1 → LT1 → LoRa → LT2 → M2, then repeat in reverse. The app cannot complete this
test without the two phones and radios.

This project links to GPL-3.0-or-later Meshtastic SDK code and is therefore maintained and
distributed under GPL-3.0-or-later terms.

## Bitcoin Regtest relay

The Alpha and Bravo flavors package independent signed Regtest transaction queues under their
flavor-specific `assets/regtest_transactions.txt` files. The current Stage 3 development build
contains two transactions per phone. Each line is one complete signed raw transaction hex.

On the home screen, **RELAY NEXT SIGNED TRANSACTION** broadcasts the current transaction on the
proven primary-channel path. Laptop Meshtastic node `!2303a141` consumes the protocol frames and
broadcasts session-specific acknowledgements on that same channel. Other participant phones hide
these frames from their chat inbox. The app splits the hex into conservative 100-character chunks and uses:

```text
BTC_TX|<session>|<chunk>/<total>|<hex>
```

It waits for `BTC_CHUNK_ACK` before sending the next chunk, retries an unacknowledged chunk up to
three times, then waits for `BTC_ACK` and `BTC_CONF`. The queue advances only after confirmation.
The current queue index is stored locally, so closing the app does not skip a transaction.

Start the laptop dashboard before relaying:

```shell
cd /Users/balajic/Documents/HongKong/demo_app/laptop-dashboard
./dashboardctl start
```

After replacing the development queues with the final 250 Alpha and 250 Bravo transactions,
rebuild the two APKs with the same Gradle command shown above.
