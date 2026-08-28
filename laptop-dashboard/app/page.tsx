"use client";

import { useEffect, useMemo, useRef, useState } from "react";

type TransportState = {
  status: "standby" | "connecting" | "reconnecting" | "online" | "error";
  port: string | null;
  detail: string;
  knownNodes?: number;
  modemPreset?: string;
  region?: string;
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

type BlockPresentation = {
  transaction: BitcoinTransaction;
  phase: "building" | "confirmed";
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

function shortTxid(txid?: string) {
  return txid ? `${txid.slice(0, 8)}…${txid.slice(-6)}` : "awaiting txid";
}

function TransportCard({
  name,
  state,
}: {
  name: string;
  state: TransportState;
}) {
  return (
    <article className={`transport-card ${state.status === "online" ? "active" : ""}`}>
      <div className="transport-mark">M</div>
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
  const [blockPresentation, setBlockPresentation] = useState<BlockPresentation | null>(null);
  const transactionStatuses = useRef(new Map<string, BitcoinTransaction["status"]>());
  const presentationTimers = useRef<number[]>([]);
  const pageOpenedAt = useRef(0);
  const chainStage = useRef<HTMLDivElement>(null);

  useEffect(() => {
    pageOpenedAt.current = Date.now();
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

  const messages = useMemo(
    () => snapshot.messages.filter((message) => message.transport === "meshtastic").reverse(),
    [snapshot.messages],
  );
  const transactions = useMemo(
    () => [...(snapshot.transactions ?? [])].reverse(),
    [snapshot.transactions],
  );

  useEffect(() => {
    const newlyConfirmed = transactions.find((transaction) => {
      const previousStatus = transactionStatuses.current.get(transaction.id);
      const firstSeenAfterPageOpened = previousStatus == null
        && Date.parse(transaction.updatedAt) >= pageOpenedAt.current - 1000;
      return transaction.status === "confirmed"
        && ((previousStatus != null && previousStatus !== "confirmed") || firstSeenAfterPageOpened);
    });

    transactions.forEach((transaction) => {
      transactionStatuses.current.set(transaction.id, transaction.status);
    });

    if (!newlyConfirmed) return;
    presentationTimers.current.forEach((timer) => window.clearTimeout(timer));
    setBlockPresentation({ transaction: newlyConfirmed, phase: "building" });
    presentationTimers.current = [
      window.setTimeout(() => {
        setBlockPresentation({ transaction: newlyConfirmed, phase: "confirmed" });
      }, 2600),
      window.setTimeout(() => {
        setBlockPresentation(null);
        presentationTimers.current = [];
      }, 6200),
    ];
  }, [transactions]);

  useEffect(() => () => {
    presentationTimers.current.forEach((timer) => window.clearTimeout(timer));
  }, []);

  const meshtastic = snapshot.transports.meshtastic;
  const bitcoin = snapshot.bitcoin ?? emptySnapshot.bitcoin;
  const activeTransaction = transactions.find((transaction) =>
    ["reserved", "receiving", "broadcasting", "mining"].includes(transaction.status),
  );
  const queuedTransactions = transactions.filter((transaction) => transaction.status === "queued");
  const blockCandidate = transactions.find((transaction) =>
    ["receiving", "broadcasting", "mining"].includes(transaction.status),
  );
  const visualCandidate = blockCandidate
    ?? (blockPresentation?.phase === "building" ? blockPresentation.transaction : undefined);
  const displayedTipHeight = blockPresentation?.phase === "building"
    && blockPresentation.transaction.blockHeight != null
    ? blockPresentation.transaction.blockHeight - 1
    : bitcoin.height;
  const visibleConfirmedBlockCount = visualCandidate ? 4 : 5;

  useEffect(() => {
    const stage = chainStage.current;
    if (!stage) return;
    const frame = window.requestAnimationFrame(() => {
      stage.scrollTo({ left: stage.scrollWidth, behavior: "smooth" });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [blockPresentation?.phase, displayedTipHeight, visualCandidate?.id]);

  const recentBlocks = useMemo(() => {
    if (displayedTipHeight == null) return [];
    const tipHeight = displayedTipHeight;
    const firstHeight = Math.max(0, tipHeight - (visibleConfirmedBlockCount - 1));
    return Array.from({ length: tipHeight - firstHeight + 1 }, (_, index) => {
      const height = firstHeight + index;
      return {
        height,
        transactions: transactions.filter(
          (transaction) => transaction.status === "confirmed" && transaction.blockHeight === height,
        ),
      };
    });
  }, [displayedTipHeight, transactions, visibleConfirmedBlockCount]);
  const gatewayRadioOnline = serviceOnline && meshtastic.status === "online";
  const gatewayReady = gatewayRadioOnline && bitcoin.status === "online";
  const slotLabel = activeTransaction
    ? `ACTIVE · ${activeTransaction.sender}`
    : !gatewayRadioOnline
      ? "RADIO OFFLINE"
      : bitcoin.status !== "online"
        ? "BITCOIN CORE OFFLINE"
        : "SLOT AVAILABLE";

  return (
    <main className="dashboard-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">MESHTASTIC / FIELD MONITOR</p>
          <h1>Meshtastic Field Console</h1>
        </div>
        <div className={`system-state ${serviceOnline ? "" : "disconnected"}`}>
          <span className="pulse" aria-hidden="true" />
          {serviceOnline ? "LISTENING" : "SERVICE OFFLINE"}
        </div>
      </header>

      <section className="dashboard-grid">
        <aside className="transport-panel">
          <p className="section-label">MESHTASTIC LINK</p>
          <TransportCard name="Gateway radio" state={meshtastic} />

          <div className="network-summary">
            <p className="section-label">MESHTASTIC MESH</p>
            <dl>
              <div><dt>Known nodes</dt><dd>{meshtastic.knownNodes ?? "—"}</dd></div>
              <div><dt>Modem preset</dt><dd>{meshtastic.modemPreset ?? "—"}</dd></div>
              <div><dt>Region</dt><dd>{meshtastic.region ?? "—"}</dd></div>
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
        </aside>

        <div className="content-stack">
        <section className="transaction-panel">
          <div className="transaction-heading">
            <div><p className="section-label">BITCOIN / REGTEST</p><h2>Transaction relay</h2></div>
            <div className="gateway-slot-summary">
              <span className={activeTransaction ? "active" : gatewayReady ? "available" : "unavailable"}>
                {slotLabel}
              </span>
              <small>{queuedTransactions.length} queued</small>
            </div>
          </div>
          <div className="transaction-stream" aria-live="polite">
            {transactions.length === 0 && (
              <div className="transaction-empty">
                {gatewayReady
                  ? "Waiting for a signed transaction from a participant phone."
                  : "Transaction relay will open when both the Gateway radio and Bitcoin Core are online."}
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

        <section className="chain-panel" aria-live="polite">
          <div className="chain-heading">
            <div>
              <p className="section-label">REGTEST CHAIN</p>
              <h2>Blocks mined by the Gateway</h2>
            </div>
            <div className="chain-tip-summary">
              <span>TIP {displayedTipHeight ?? "—"}</span>
              <small>ONE BLOCK PER DEMO TRANSACTION</small>
            </div>
          </div>

          <div className="chain-stage" ref={chainStage}>
            {recentBlocks.length === 0 ? (
              <div className="chain-empty">Start Bitcoin Core to reveal the Regtest chain.</div>
            ) : (
              <div className="chain-track">
                {recentBlocks.map((block, index) => {
                  const phoneTransaction = block.transactions[0];
                  const isTip = index === recentBlocks.length - 1;
                  const isJustMined = blockPresentation?.phase === "confirmed"
                    && phoneTransaction?.id === blockPresentation.transaction.id;
                  return (
                    <article
                      className={`chain-block ${isTip ? "tip" : ""} ${phoneTransaction ? "contains-phone-tx" : ""} ${isJustMined ? "just-mined" : ""}`}
                      key={block.height}
                    >
                      <div className="block-number">
                        <span>BLOCK</span>
                        <strong>{block.height}</strong>
                      </div>
                      <div className="block-cube" aria-hidden="true"><span /></div>
                      <div className="block-payload">
                        {phoneTransaction ? (
                          <>
                            <strong>{phoneTransaction.sender} TX MINED</strong>
                            <code title={phoneTransaction.txid}>{shortTxid(phoneTransaction.txid)}</code>
                          </>
                        ) : (
                          <>
                            <strong>COINBASE BLOCK</strong>
                            <small>No demo transaction</small>
                          </>
                        )}
                      </div>
                      {isJustMined
                        ? <span className="tip-label mined-label">TX MINED · NEW TIP</span>
                        : isTip && <span className="tip-label">CHAIN TIP</span>}
                    </article>
                  );
                })}

                {visualCandidate && (
                  <article className={`chain-block candidate ${visualCandidate.status}`}>
                    <div className="block-number">
                      <span>NEXT BLOCK</span>
                      <strong>{visualCandidate.blockHeight ?? (displayedTipHeight != null ? displayedTipHeight + 1 : "—")}</strong>
                    </div>
                    <div className="block-cube" aria-hidden="true"><span /></div>
                    <div className="block-payload">
                      <strong>
                        {visualCandidate.sender} TX {visualCandidate.status === "receiving" ? "ARRIVING" : "MINING"}
                      </strong>
                      {visualCandidate.status === "receiving" ? (
                        <code>{visualCandidate.chunksReceived ?? 0}/{visualCandidate.chunksTotal ?? "?"} CHUNKS RECEIVED</code>
                      ) : (
                        <code title={visualCandidate.txid}>{shortTxid(visualCandidate.txid)}</code>
                      )}
                    </div>
                    <span className="mining-label">
                      <i /> {visualCandidate.status === "receiving" ? "RECEIVING" : "BUILDING"}
                    </span>
                  </article>
                )}
              </div>
            )}
          </div>

          <p className="chain-explainer">
            The Gateway broadcasts the signed transaction to Bitcoin Core, mines one Regtest block,
            and places the transaction inside the new chain tip.
          </p>
        </section>

        <section className="message-panel">
          <div className="message-heading">
            <div><p className="section-label">LIVE TRAFFIC</p><h2>Meshtastic messages</h2></div>
            <p className="message-count">{messages.length} {messages.length === 1 ? "packet" : "packets"} received</p>
          </div>

          <div className="message-stream" aria-live="polite">
            {messages.length === 0 && (
              <div className="empty-state">
                <div className="radar" aria-hidden="true"><span /></div>
                <h3>Listening to the mesh</h3>
                <p>Messages received by the Gateway radio will appear here automatically.</p>
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
