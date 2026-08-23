# Meshtastic Conference Demo v0.2.0

This is the conference reliability build. The same universal APK is used on both participant
phones; choose **Alice** or **Bob** inside the app.

## What changed

- **Change Radio is now safe:** opening the radio picker, scanning, or pressing Back keeps the
  current BLE connection alive. The app switches only after the operator selects another paired
  Meshtastic radio.
- **Pairing loops are contained:** automatic recovery runs only for radios Android still reports as
  bonded. If the bond is gone, the app stops retrying and gives the operator a clear pairing action.
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
