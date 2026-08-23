"use client";

import { useEffect, useMemo, useState } from "react";

type TransportState = {
  status: "standby" | "connecting" | "online" | "error";
  port: string | null;
  detail: string;
  knownNodes?: number;
  destinationHash?: string;
};

type Message = {
  id: string;
  transport: "meshtastic" | "reticulum";
  sender: string;
  sourceId?: string;
  title?: string;
  text: string;
  receivedAt: string;
  rssi?: number;
  snr?: number;
  hops?: number;
};

type BitcoinState = {
  status: "standby" | "online" | "error";
  network: "regtest";
  rpcPort: number;
  detail: string;
  height?: number;
};

type BitcoinTransaction = {
  id: string;
  session: string;
  sender: string;
  sourceId: string;
  status: "queued" | "reserved" | "receiving" | "broadcasting" | "mining" | "confirmed" | "error";
  queuePosition?: number;
  chunksReceived?: number;
  chunksTotal?: number;
  sizeBytes?: number;
  txid?: string;
  blockHeight?: number;
  error?: string;
  resultAcknowledged?: boolean;
  receivedAt: string;
  updatedAt: string;
};

type Snapshot = {
  generatedAt: string;
  transports: Record<"meshtastic" | "reticulum", TransportState>;
  bitcoin: BitcoinState;
  transactions: BitcoinTransaction[];
  messages: Message[];
};

const SERVICE_URL = "http://127.0.0.1:8765";
const emptySnapshot: Snapshot = {
  generatedAt: new Date().toISOString(),
  transports: {
    meshtastic: { status: "standby", port: null, detail: "Waiting for local service" },
    reticulum: { status: "standby", port: null, detail: "Waiting for local service" },
  },
  bitcoin: {
    status: "standby",
    network: "regtest",
    rpcPort: 18443,
    detail: "Waiting for local service",
  },
  transactions: [],
  messages: [],
};

function formatTime(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

function TransportCard({
  name,
  mark,
  state,
}: {
  name: string;
  mark: "M" | "R";
  state: TransportState;
}) {
  return (
    <article className={`transport-card ${state.status === "online" ? "active" : ""}`}>
      <div className={`transport-mark ${mark === "R" ? "reticulum" : ""}`}>{mark}</div>
      <div className="transport-copy">
        <h2>{name}</h2>
        <p>{state.port ?? state.detail}</p>
        {state.port && <small>{state.detail}</small>}
      </div>
      <span className={`status-badge ${state.status}`}>{state.status.toUpperCase()}</span>
    </article>
  );
}

export default function Home() {
  const [snapshot, setSnapshot] = useState<Snapshot>(emptySnapshot);
  const [serviceOnline, setServiceOnline] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let active = true;
    fetch(`${SERVICE_URL}/api/snapshot`)
      .then((response) => response.json())
      .then((data: Snapshot) => {
        if (active) setSnapshot(data);
      })
      .catch(() => setServiceOnline(false));

    const events = new EventSource(`${SERVICE_URL}/api/events`);
    events.onopen = () => setServiceOnline(true);
    events.onmessage = (event) => {
      if (!active) return;
      setSnapshot(JSON.parse(event.data) as Snapshot);
      setServiceOnline(true);
    };
    events.onerror = () => setServiceOnline(false);
    return () => {
      active = false;
      events.close();
    };
  }, []);

  const messages = useMemo(() => [...snapshot.messages].reverse(), [snapshot.messages]);
  const transactions = useMemo(
    () => [...(snapshot.transactions ?? [])].reverse(),
    [snapshot.transactions],
  );
  const meshtastic = snapshot.transports.meshtastic;
  const reticulum = snapshot.transports.reticulum;
  const bitcoin = snapshot.bitcoin ?? emptySnapshot.bitcoin;
  const activeTransaction = transactions.find((transaction) =>
    ["reserved", "receiving", "broadcasting", "mining"].includes(transaction.status),
  );
  const queuedTransactions = transactions.filter((transaction) => transaction.status === "queued");

  async function copyDestination() {
    if (!reticulum.destinationHash) return;
    await navigator.clipboard.writeText(reticulum.destinationHash);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  }

  return (
    <main className="dashboard-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">RESILIENT COMMS / FIELD MONITOR</p>
          <h1>Unified Message Console</h1>
        </div>
        <div className={`system-state ${serviceOnline ? "" : "disconnected"}`}>
          <span className="pulse" aria-hidden="true" />
          {serviceOnline ? "LISTENING" : "SERVICE OFFLINE"}
        </div>
      </header>

      <section className="dashboard-grid">
        <aside className="transport-panel">
          <p className="section-label">TRANSPORTS</p>
          <TransportCard name="Meshtastic" mark="M" state={meshtastic} />
          <TransportCard name="Reticulum / LXMF" mark="R" state={reticulum} />

          {reticulum.destinationHash && (
            <div className="destination-card">
              <p className="section-label">LAPTOP LXMF DESTINATION</p>
              <code>{reticulum.destinationHash}</code>
              <button type="button" onClick={copyDestination}>{copied ? "COPIED" : "COPY ADDRESS"}</button>
            </div>
          )}

          <div className="network-summary">
            <p className="section-label">MESHTASTIC MESH</p>
            <dl>
              <div><dt>Known nodes</dt><dd>{meshtastic.knownNodes ?? "—"}</dd></div>
              <div><dt>Channel</dt><dd>LongFast</dd></div>
              <div><dt>Region</dt><dd>TW</dd></div>
            </dl>
          </div>

          <div className="bitcoin-summary">
            <div className="bitcoin-heading">
              <p className="section-label">BITCOIN GATEWAY</p>
              <span className={`status-badge ${bitcoin.status}`}>{bitcoin.status.toUpperCase()}</span>
            </div>
            <dl>
              <div><dt>Network</dt><dd>REGTEST</dd></div>
              <div><dt>RPC port</dt><dd>{bitcoin.rpcPort}</dd></div>
              <div><dt>Block height</dt><dd>{bitcoin.height ?? "—"}</dd></div>
            </dl>
            <small>{bitcoin.detail}</small>
          </div>

          <p className="security-note">
            LXMF is end-to-end encrypted. Only messages addressed or copied to this laptop destination appear here.
          </p>
        </aside>

        <div className="content-stack">
        <section className="transaction-panel">
          <div className="transaction-heading">
            <div><p className="section-label">BITCOIN / REGTEST</p><h2>Transaction relay</h2></div>
            <div className="gateway-slot-summary">
              <span className={activeTransaction ? "active" : "available"}>
                {activeTransaction ? `ACTIVE · ${activeTransaction.sender}` : "SLOT AVAILABLE"}
              </span>
              <small>{queuedTransactions.length} queued</small>
            </div>
          </div>
          <div className="transaction-stream" aria-live="polite">
            {transactions.length === 0 && (
              <div className="transaction-empty">
                Waiting for a signed transaction from Alice or Bob.
              </div>
            )}
            {transactions.map((transaction) => {
              const received = transaction.chunksReceived ?? 0;
              const total = transaction.chunksTotal ?? 0;
              const progress = total ? Math.round((received / total) * 100) : 0;
              return (
                <article className={`transaction-row ${transaction.status}`} key={transaction.id}>
                  <div className="transaction-topline">
                    <strong>{transaction.sender}</strong>
                    <code>{transaction.session}</code>
                    <span className={`transaction-status ${transaction.status}`}>{transaction.status.toUpperCase()}</span>
                  </div>
                  <div className="transaction-progress" aria-label={`${received} of ${total} chunks received`}>
                    <span style={{ width: `${progress}%` }} />
                  </div>
                  <div className="transaction-detail">
                    <span>{received}/{total} CHUNKS</span>
                    {transaction.queuePosition != null && transaction.queuePosition > 0 && (
                      <span>QUEUE POSITION {transaction.queuePosition}</span>
                    )}
                    {transaction.sizeBytes != null && <span>{transaction.sizeBytes} BYTES</span>}
                    {transaction.blockHeight != null && <span>BLOCK {transaction.blockHeight}</span>}
                    {transaction.status === "confirmed" && (
                      <span>{transaction.resultAcknowledged ? "PHONE RECEIVED RESULT" : "AWAITING PHONE ACK"}</span>
                    )}
                    {transaction.error && <span className="transaction-error">{transaction.error}</span>}
                  </div>
                  {transaction.txid && <code className="transaction-txid">TXID {transaction.txid}</code>}
                </article>
              );
            })}
          </div>
        </section>

        <section className="message-panel">
          <div className="message-heading">
            <div><p className="section-label">LIVE TRAFFIC</p><h2>All messages</h2></div>
            <p className="message-count">{messages.length} {messages.length === 1 ? "packet" : "packets"} received</p>
          </div>

          <div className="message-stream" aria-live="polite">
            {messages.length === 0 && (
              <div className="empty-state">
                <div className="radar" aria-hidden="true"><span /></div>
                <h3>Listening for field traffic</h3>
                <p>Messages received by either connected radio will appear here automatically.</p>
              </div>
            )}
            {messages.map((message) => (
              <article className="message-row" key={message.id}>
                <div className={`avatar ${message.transport} ${message.sender.toLowerCase()}`}>{message.sender[0]?.toUpperCase() ?? "?"}</div>
                <div className="message-content">
                  <div className="message-meta">
                    <strong>{message.sender}</strong>
                    <span className={message.transport}>{message.transport.toUpperCase()}</span>
                    <time dateTime={message.receivedAt}>{formatTime(message.receivedAt)}</time>
                  </div>
                  {message.title && <h3>{message.title}</h3>}
                  <p>{message.text}</p>
                  {(message.rssi != null || message.snr != null || message.hops != null) && (
                    <div className="radio-meta">
                      {message.rssi != null && <span>RSSI {message.rssi} dBm</span>}
                      {message.snr != null && <span>SNR {message.snr.toFixed(2)} dB</span>}
                      {message.hops != null && <span>{message.hops === 0 ? "DIRECT" : `${message.hops} HOPS`}</span>}
                    </div>
                  )}
                </div>
              </article>
            ))}
          </div>
        </section>
        </div>
      </section>
    </main>
  );
}
