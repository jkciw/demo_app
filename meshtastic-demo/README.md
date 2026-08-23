# Meshtastic conference demo

This standalone Android app is the Meshtastic half of the resilient communications demo. It uses
the official Meshtastic SDK `0.1.0` over BLE and presents three visitor-facing destinations:
**Alice**, **Bob**, and the laptop **Gateway**. Alice and Bob are sent node-addressed messages;
**Everyone** remains an explicit public broadcast on primary channel `0`. Real inbound packets are
grouped by contact, while broadcast delivery is labelled **relayed by mesh** rather than claiming
that every node received it.

## Build and configure a station

Build the one universal APK:

```shell
./gradlew :app:assembleDebug
```

Install the same APK on every conference phone. On first launch choose **ALICE** or **BOB**. The
choice controls the visible station identity and selects an independent
signed-transaction queue; it is saved locally.

Alice and Bob are app identities, not radio identities. Each connected phone broadcasts a small
`DEMO_PRESENCE` control frame immediately after connecting and once per minute. The laptop does
the same for **Gateway**. The packet's Meshtastic source node is then mapped to Alice, Bob, or
Gateway for three minutes. This means the two LilyGo radios can be exchanged between the phones
without swapping the visitor identities. Presence request and announcement frames are hidden from
chat and from the laptop message wall. Radio long names are only a compatibility fallback.

Before the conference, pair each phone with its physically attached Meshtastic radio in Android
Bluetooth settings and remove any stale Meshtastic pairings. On first launch the app checks the
phone's bonded devices for Meshtastic radios. It automatically binds when exactly one is found; if
several are paired, it asks the operator to select one. Android 12+ can also use BLE service
discovery as a fallback. Android 8–11 deliberately avoid active discovery so system Location can
remain off. The selection is stored only on the phone.

The normal visitor flow does not show radio or identity controls. Long-press **RADIO READY** (or
**RADIO OFFLINE**) in the top-right corner of the landing screen to open the operator console.
That console can refresh presence, reconnect, change the paired radio, change Alice/Bob identity,
detect duplicate identity claims, and reset the signed-transaction queue. **Change radio** always
stops at the operator selection screen, even when only one Meshtastic radio is currently paired;
only radios already paired in Android are listed. Pair a replacement in Android Bluetooth settings
beforehand, then return to the app, press **Scan again**, and select it.

Operator actions use consistent subpages. **Change phone identity** returns to the Operator Console
when Back is pressed or Alice/Bob is selected. **Reconnect attached radio** opens a dedicated
progress screen and reports success or failure before returning to the Operator Console.

After permission is granted, a foreground connected-device service owns the app's single
Meshtastic client. The persistent **radio link** notification confirms that the service is alive.
Leaving the screen, locking the phone, or Android recreating the activity no longer closes BLE;
**Change radio** in the operator console is the controlled disconnect. If the SDK declares an otherwise healthy
idle BLE session stale, the service rebuilds the client with bounded backoff and completes a fresh
handshake without reopening the app. For the conference, also exempt the app from the phone's
battery optimisation and keep the official Meshtastic app closed so only one phone app owns each
radio.

## Hardware proof

Install the universal APK on M1 and M2. Select Alice on M1 and Bob on M2, then pair each phone
with its attached radio. Confirm that each app shows the selected station and actual radio name.
With Wi-Fi and cellular
disabled, verify M1 → LT1 → LoRa → LT2 → M2, then repeat in reverse. The app cannot complete this
test without the two phones and radios.

This project links to GPL-3.0-or-later Meshtastic SDK code and is therefore maintained and
distributed under GPL-3.0-or-later terms.

## Bitcoin Regtest relay

The universal APK packages independent Alice and Bob Regtest transaction queues under
`assets/regtest_transactions_alpha.txt` and `assets/regtest_transactions_bravo.txt`. The selected
runtime station role chooses the queue. The current Stage 3 development build contains two
transactions per station. Each line is one complete signed raw transaction hex.

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

After replacing the development queues with the final 250 Alice and 250 Bob transactions,
rebuild the universal APK with the same Gradle command shown above.
