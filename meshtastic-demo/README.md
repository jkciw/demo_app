# Meshtastic conference demo

This standalone Android app is the Meshtastic half of the resilient communications demo. It uses
the official Meshtastic SDK `0.1.0` over BLE and presents the other conference participants plus
the **Gateway** as visitor-facing destinations. Alice, Bob, Charlie, and Dana receive node-addressed messages;
**Everyone** remains an explicit public broadcast on primary channel `0`. Real inbound packets are
grouped by contact, while broadcast delivery is labelled **relayed by mesh** rather than claiming
that every node received it.

## Build and configure a station

Build the one universal APK:

```shell
./gradlew :app:assembleDebug
```

Install the same APK on every conference phone. On first launch choose **ALICE**, **BOB**,
**CHARLIE**, or **DANA**. The
choice controls the visible station identity and selects an independent
signed-transaction queue; it is saved locally.

Alice, Bob, Charlie, and Dana are app identities, not radio identities. Each connected phone broadcasts a small
`DEMO_PRESENCE` control frame after connecting and every five minutes. The Gateway does the same.
The packet's Meshtastic source node is then mapped to a phone identity or Gateway for
ten minutes. This means LilyGo radios can be exchanged between phones
without swapping the visitor identities. Presence request and announcement frames are hidden from
chat and from the Gateway message wall. Radio long names are only a compatibility fallback.

Before the conference, pair each phone with its physically attached Meshtastic radio in Android
Bluetooth settings and remove any stale Meshtastic pairings. On first launch the app checks the
phone's bonded devices for Meshtastic radios. It automatically binds when exactly one is found; if
several are paired, it asks the operator to select one. Android 12+ can also use BLE service
discovery as a fallback. Android 8–11 deliberately avoid active discovery so system Location can
remain off. The selection is stored only on the phone.

The normal visitor flow does not show radio or identity controls. Long-press **RADIO READY** (or
**RADIO OFFLINE**) in the top-right corner of the landing screen to open the operator console.
That console can refresh presence, reconnect, change the paired radio, change the phone identity,
detect duplicate identity claims, and reset the signed-transaction queue. **Change radio** always
stops at the operator selection screen, even when only one Meshtastic radio is currently paired;
only radios already paired in Android are listed. Pair a replacement in Android Bluetooth settings
beforehand, then return to the app, press **Scan again**, and select it. Opening this page, scanning,
or pressing Back preserves the working radio connection. The app disconnects only after the
operator selects a different bonded radio.

Operator actions use consistent subpages. **Change phone identity** returns to the Operator Console
when Back is pressed or an identity is selected. **Reconnect attached radio** opens a dedicated
progress screen and reports success or failure before returning to the Operator Console.

After permission is granted, a foreground connected-device service owns the app's single
Meshtastic client. The persistent **radio link** notification confirms that the service is alive.
Leaving the screen, locking the phone, or Android recreating the activity no longer closes BLE;
the operator must explicitly select a different paired radio before the existing link is closed. If
the SDK declares an otherwise healthy idle BLE session stale, the service rebuilds the client with
bounded backoff and completes a fresh handshake without reopening the app. If Android has removed
the bond, retries stop and the app asks the operator to pair the radio again instead of repeatedly
triggering pairing-code prompts. The BLE transport deliberately disables Android's persistent GATT
auto-connect so it cannot bypass these bond checks. SDK 0.1.0's default fixed-nonce BLE heartbeat is
disabled and replaced by a 20-second incrementing-nonce keepalive. This produces the firmware queue
response needed by the SDK's liveness watchdog; disabling the SDK sender alone is insufficient
because its watchdog continues running. A runtime bond observer stops recovery immediately if a
radio rejects a stored bond. Only the operator's explicit **Reconnect attached radio** action may
open one unbonded GATT session; this lets the Meshtastic PIN exchange finish inside the connection,
as required by radios that cannot establish a durable bond from Android Settings alone. For the
conference, also exempt the app from the phone's battery optimisation and keep the official
Meshtastic app closed so only one phone app owns each radio.

## Hardware proof

Install the universal APK on four phones. Select Alice, Bob, Charlie, and Dana respectively, then
pair each phone with its physically attached radio. Confirm that every app shows its selected
identity and actual radio name. With Wi-Fi and cellular disabled, verify the direct-message ring
Alice → Bob → Charlie → Dana → Alice, then send one broadcast from each phone. Full acceptance of
this preview requires all four phones and radios; the proven two-phone build remains on `main`.

This project links to GPL-3.0-or-later Meshtastic SDK code and is therefore maintained and
distributed under GPL-3.0-or-later terms.

## Bitcoin Regtest relay

The universal APK packages independent Alice, Bob, Charlie, and Dana Regtest transaction queues
under `assets/regtest_transactions_*.txt`. The selected runtime station role chooses the queue.
Replenish all four queues from the live demo chain with
`laptop-dashboard/regtest/replenish_phone_transactions.py`. Each line is one complete signed raw
transaction hex.

On the home screen, **RELAY NEXT SIGNED TRANSACTION** first requests the Gateway's single upload
slot. If several phones request it together, the first decoded request becomes active and the others
show their FIFO queue positions. Only the active phone transmits chunks; each queued phone starts
automatically after the Gateway grants its turn. Other participant phones hide these frames from
their chat inbox.

```text
BTC_BEGIN|<session>|<total-chunks>
BTC_READY|<session>
BTC_QUEUED|<session>|<position>
```

The app splits the current 191-byte Regtest transactions into two 200-character hex chunks and uses:

```text
BTC_TX|<session>|<chunk>/<total>|<hex>
```

It waits for `BTC_CHUNK_ACK` before sending the next chunk and retries an unacknowledged chunk up to
three times. After the last chunk, the phone polls with `BTC_RESULT_REQUEST|<session>` until the
Gateway returns the stored `BTC_RESULT|<session>|<txid>|<block-height>`. The phone replies with
`BTC_RESULT_ACK|<session>` and advances its local queue only after receiving the result. The Gateway
holds the active slot through this handshake, preventing the next queued upload from colliding with
result delivery. Presence announcements pause during an active relay, and a stalled upload releases
its slot after 75 seconds.

Start the laptop dashboard before relaying:

```shell
cd demo_app/laptop-dashboard
./dashboardctl start
```

After replacing the development queues with the final transaction sets for all four identities,
rebuild the universal APK with the same Gradle command shown above.
