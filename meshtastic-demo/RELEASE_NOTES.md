# Meshtastic Conference Demo v0.4.0-beta.6

This is the four-station software preview built from `four-station-mesh`. The same universal APK is
used on every participant phone; choose **Alice**, **Bob**, **Charlie**, or **Dana** inside the app.
The hardware-proven v0.3.3 release on `main` remains the conference fallback until the five-radio
acceptance gate passes.

## What changed

- **Incoming DMs are noticeable:** a real participant-to-participant direct message now produces a
  high-priority Android notification with the sender and message preview. Tapping it opens that
  participant conversation when the contact is available. Broadcasts, presence announcements,
  Gateway envelopes, Bitcoin frames, duplicate packets, and the phone's own echoes stay silent.
- **Unread DMs are visible inside the app:** the **Choose how to use the mesh** screen shows the
  total unread direct-message count and a per-contact **NEW** badge. Opening that conversation
  clears its badge; messages already visible in the open conversation are not counted as unread.
- **The landing action carries the signal:** **Send a mesh message** also shows the total unread
  count, while the redundant **Recently received** feed beneath Broadcast has been removed. Message
  history remains inside the relevant conversation and the Everyone broadcast screen.
- **Gateway messages no longer depend on PKI contact caches:** participant apps wrap Gateway
  requests in a reserved application-addressed frame and carry it over the proven shared primary
  channel. The Gateway console displays only the human message, while the other participant apps
  hide the protocol frame. Alice–Bob–Charlie–Dana conversations remain Meshtastic direct messages,
  and Everyone remains the explicit public broadcast experience.
- **Gateway requests identify their station in-band:** the request carries Alice, Bob, Charlie, or
  Dana inside the application envelope, so the big screen labels the visitor correctly even after
  radios are reset or exchanged and presence is still refreshing.
- **Bitcoin confirmation is visibly active:** once all chunks reach the Gateway node, an animated
  hourglass appears while the phone polls the Gateway for the confirmed transaction result. It
  disappears automatically on confirmation or failure.
- **Gateway language is consistent:** attendee-facing routes, status messages, confirmation text,
  and errors describe the service as the **Gateway node**, without exposing its current host device.
- **Four visitor identities are ready:** every phone shows the other three participants as direct
  contacts, plus Gateway and the explicit Everyone broadcast route.
- **Presence is staggered:** Alice, Bob, Charlie, and Dana use distinct announcement offsets to
  reduce collisions while retaining runtime identity discovery when radios are swapped.
- **The single mesh is faster:** current signed transactions use two 200-character chunks instead
  of four 100-character chunks, and Gateway replies are paced at one-second intervals.
- **Radio configuration is visible:** the dashboard reports the modem preset and region read from
  the connected Gateway rather than displaying a hard-coded LongFast label.
- **Fresh transactions are ready:** all four identities receive 20 independently funded, presigned
  Regtest transactions, with a repeatable replenishment tool for future testing.
- **Conference scope is focused:** the attendee-facing app, Gateway console, and operating guide now
  present only the tested Meshtastic experience; unfinished Reticulum placeholders are hidden.
- **Simultaneous Bitcoin requests are orderly:** participant phones request a Gateway upload slot
  before transmitting transaction chunks. The first decoded request becomes active and the others
  display FIFO queue positions, then start automatically as the Gateway grants each turn.
- **Final results survive packet loss:** after its final chunk is acknowledged, the phone requests
  the stored TXID and Regtest block height until the Gateway answers. The Gateway keeps that phone's
  slot until the result is acknowledged, so the next queued upload cannot collide with result
  delivery and strand the first phone at "Waiting for Bitcoin Core."
- **Installed build is visible:** the Operator Console shows the app version and build number.
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
- **Presence traffic is quieter:** all four phones and Gateway advertise every five minutes, with a
  ten-minute presence window, reducing contention during long transaction relays.

## Install or update

Download **Meshtastic-Conference-Demo.apk** below. Android will offer **Install** on a new phone or
**Update** when an earlier conference build with the same signing identity is present. Updating
preserves the selected identity, paired-radio preference, and transaction queue position.

The `.sha256` file beside the APK can be used to verify the download.
