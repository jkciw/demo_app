# Unified Message Console

A local, full-screen dashboard that receives Meshtastic and Reticulum/LXMF
messages from two independent USB serial radios.

## Start and stop

```sh
cd /Users/balajic/Documents/HongKong/demo_app/laptop-dashboard
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

## Add the Reticulum RNode

Connect the second USB radio, find its `/dev/cu.*` path, and start with:

```sh
RETICULUM_PORT=/dev/cu.usbmodem2101 ./dashboardctl start
```

The Reticulum defaults are 925.875 MHz, 250 kHz, SF9, CR5, and 17 dBm. They
must exactly match the two phone RNode configurations. Override any value with
`RETICULUM_FREQUENCY`, `RETICULUM_BANDWIDTH`, `RETICULUM_SF`,
`RETICULUM_CR`, and `RETICULUM_TXPOWER`.

The dashboard creates a persistent LXMF identity and displays its destination
hash. Add that destination as a contact on both phones. LXMF is end-to-end
encrypted, so the laptop can display only messages that are addressed or
explicitly copied to its destination; it cannot passively decrypt a private
phone-to-phone conversation.

## Data flow

```text
Meshtastic USB radio ─┐
                     ├─ local collector ─ live event stream ─ dashboard
Reticulum USB RNode ─┘
```

Messages are held in memory for the current run. The Reticulum identity is
stored under `state/` so its destination address remains stable.

## Bitcoin Regtest transaction relay

`./dashboardctl start` also starts the isolated Regtest node created in Stage
1. Bitcoin transaction traffic appears in the separate **Transaction relay**
panel and does not flood the ordinary message list.

The phone sends the signed transaction hex in one-based chunks. Keep each hex
payload at or below 170 characters:

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
BTC_ACK|<session>|<txid>
BTC_CONF|<session>|<block-height>
BTC_NACK|<session>|<reason>
```

The laptop acknowledges every valid chunk, reassembles chunks received out of
order, submits the completed raw transaction with `sendrawtransaction`, mines
one block, and returns the transaction ID and confirmation height. Repeating a
chunk or a completed session is safe and does not broadcast the transaction a
second time.
