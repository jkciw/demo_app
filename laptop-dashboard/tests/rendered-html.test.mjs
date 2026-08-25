import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the conference dashboard and Bitcoin relay", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>Meshtastic Field Console<\/title>/i);
  assert.match(html, /MESHTASTIC \/ FIELD MONITOR/);
  assert.match(html, /MESHTASTIC LINK/);
  assert.doesNotMatch(html, /Reticulum|LXMF|RNode/i);
  assert.match(html, /Transaction relay/);
  assert.match(html, /BITCOIN \/ REGTEST/);
  assert.match(html, /RPC port/);
  assert.match(html, />18443</);
  assert.match(html, /Waiting for a signed transaction from Alice or Bob/);
  assert.match(html, /SLOT AVAILABLE/);
});

test("client subscribes to collector snapshots and renders relay states", async () => {
  const [page, css, bridge] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
    readFile(new URL("../service/transaction_bridge.py", import.meta.url), "utf8"),
  ]);

  assert.match(page, /new EventSource\(`\$\{SERVICE_URL\}\/api\/events`\)/);
  assert.match(page, /message\.transport === "meshtastic"/);
  assert.match(page, /transaction\.status/);
  assert.match(page, /transaction\.txid/);
  assert.match(page, /transaction\.blockHeight/);
  assert.match(css, /\.transaction-panel/);
  assert.match(css, /\.transaction-status\.confirmed/);
  assert.match(css, /\.transaction-status\.queued/);
  assert.match(bridge, /BTC_BEGIN/);
  assert.match(bridge, /BTC_READY/);
  assert.match(bridge, /BTC_QUEUED/);
  assert.match(bridge, /BTC_CHUNK_ACK/);
  assert.match(bridge, /BTC_RESULT/);
  assert.match(bridge, /BTC_RESULT_ACK/);
  assert.match(bridge, /sendrawtransaction/);
  assert.match(bridge, /generatetoaddress/);
});
