# Meshtastic Field Console

A local, full-screen dashboard that receives Meshtastic messages from the laptop Gateway radio and
displays the Bitcoin Regtest transaction relay. The conference experience is intentionally
Meshtastic-only; Reticulum is deferred.

## Start and stop

```sh
cd demo_app/laptop-dashboard
./dashboardctl start
```

Stop the dashboard and release the serial port with:

```sh
./dashboardctl stop
```

Starting it twice is safe. Local radio mappings are stored in `.dashboard.env`.

Open `http://localhost:3000`. The current Meshtastic default is
`/dev/cu.usbmodem1101`. Override it when macOS assigns a different device:

```sh
MESHTASTIC_PORT=/dev/cu.usbmodem1201 ./dashboardctl start
```

Only one application can own a serial device at a time. Stop the Meshtastic
CLI listener before starting the dashboard.

While the collector is running, the serial node announces itself as **Gateway** using the same
lightweight `DEMO_PRESENCE` protocol as the Alice, Bob, Charlie, and Dana phone apps. It also responds to a phone's
presence refresh request. These control frames are consumed by the collector and never appear in
the public message list; incoming phone messages are labelled from the most recent app presence,
not from a hard-coded LilyGo node ID.

The serial connection intentionally skips the firmware's full NodeDB replay. The conference app
learns all four phone identities and the Gateway address from presence frames, while avoiding an ESP32-S3 serial
framing failure that can occur during a high-volume NodeDB synchronization.

For the four-station preview, configure every radio to the same primary channel,
`ShortFast` modem preset, and `TW` region before starting the dashboard. The
dashboard displays the preset and region reported by the connected Gateway; it
does not change radio configuration.

## Data flow

```text
Alice / Bob / Charlie / Dana phones ─ LoRa mesh ─ Gateway USB radio ─ local collector ─ dashboard
```

Messages and relay state are held in memory for the current run.

## Bitcoin Regtest transaction relay

`./dashboardctl start` also starts the isolated Regtest node created in Stage
1. Bitcoin transaction traffic appears in the separate **Transaction relay**
panel and does not flood the ordinary message list.

Each phone first requests the Gateway's single FIFO upload slot:

```text
BTC_BEGIN|<session>|<total-chunks>
BTC_READY|<session>
BTC_QUEUED|<session>|<position>
```

Only the active station sends signed transaction hex in one-based chunks. The conference app uses
200 hexadecimal characters per payload:

```text
BTC_TX|<session>|<chunk>/<total>|<hex>
```

For example:

```text
BTC_TX|a001|1/3|0200000001...
```

The laptop broadcasts the reply on the incoming channel. The session ID makes
the reply specific to the originating transfer, and participant apps hide
protocol traffic from their chat inbox:

```text
BTC_CHUNK_ACK|<session>|<chunk>
BTC_RESULT_REQUEST|<session>
BTC_RESULT|<session>|<txid>|<block-height>
BTC_RESULT_ACK|<session>
BTC_NACK|<session>|<reason>
```

The laptop acknowledges every valid chunk, reassembles chunks received out of order, submits the
completed raw transaction with `sendrawtransaction`, and mines one block. The result remains stored
while the phone polls for it, and the active reservation is released only after `BTC_RESULT_ACK`.
The dashboard shows one active reservation plus each queued station. Repeating a request, chunk, or
completed session is safe and does not broadcast the transaction a second time. A reservation that
receives no valid protocol frame for 75 seconds expires and advances the queue.
