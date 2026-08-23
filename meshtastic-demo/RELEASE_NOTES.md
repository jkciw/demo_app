# Meshtastic Conference Demo v0.3.0

This is the conference reliability build. The same universal APK is used on both participant
phones; choose **Alice** or **Bob** inside the app.

## What changed

- **Simultaneous Bitcoin requests are orderly:** Alice and Bob request a Gateway upload slot before
  transmitting transaction chunks. The first decoded request becomes active and the other phone
  displays its FIFO queue position, then starts automatically when the Gateway grants its turn.
- **Final results survive packet loss:** the Gateway returns one compact result containing both the
  TXID and Regtest block height, repeats it three times, and records the phone's result
  acknowledgement. A missed intermediate packet can no longer strand the phone at "Waiting for
  Bitcoin Core."
- **Transaction traffic gets priority:** phone and Gateway presence announcements pause while an
  upload slot is active. A stalled station releases its slot after 75 seconds without a valid
  chunk, so the next visitor cannot remain blocked indefinitely.
- **Change Radio is now safe:** opening the radio picker, scanning, or pressing Back keeps the
  current BLE connection alive. The app switches only after the operator selects another paired
  Meshtastic radio.
- **Pairing loops are contained:** automatic recovery runs only for radios Android still reports as
  bonded. Android's persistent GATT auto-connect is disabled, so it cannot continue reopening
  pairing behind the service's bond checks. If the bond is gone, the app stops retrying and gives
  the operator a clear one-shot pairing action. That action establishes GATT first and completes
  the Meshtastic PIN exchange inside the live connection, matching the original stable app flow.
- **Idle links stay connected:** the SDK's fixed-nonce BLE heartbeat is replaced by a 20-second
  incrementing-nonce keepalive, preventing its still-active 60-second liveness watchdog from
  tearing down an idle link. Real GATT disconnects still enter bounded recovery, while a runtime
  bond observer immediately stops recovery if the radio rejects Android's stored key.
- **Gateway checks are accurate:** messages and Bitcoin transaction acknowledgements follow the
  Gateway node discovered by presence, rather than relying on a fixed radio identity.
- **Bitcoin control packets stay out of chat:** transaction chunks and acknowledgements remain part
  of the relay workflow without appearing as visitor messages.
- **Presence traffic is quieter:** Alice, Bob, and Gateway advertise every five minutes, with a
  ten-minute presence window, reducing contention during long transaction relays.

## Install or update

Download **Meshtastic-Conference-Demo.apk** below. Android will offer **Install** on a new phone or
**Update** when an earlier conference build with the same signing identity is present. Updating
preserves the selected identity, paired-radio preference, and transaction queue position.

The `.sha256` file beside the APK can be used to verify the download.
