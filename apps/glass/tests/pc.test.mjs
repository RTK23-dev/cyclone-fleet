import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseTunnel, syncFleet, RUN_STOP_CARD } from "../.test-dist/services/pc.js";
import { createRemotePage } from "../.test-dist/pages/remotePage.js";
import { createAttachPage } from "../.test-dist/pages/attachPage.js";
import { createWelcomeCard } from "../.test-dist/ui/welcomeCard.js";

function ctx(fetch) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.27", devices: [], device: null, devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}
const button = (page, label) => page.element.querySelectorAll("button").find((b) => b.textContent === label);

const RUNNING = { state: "running", mode: "readonly", mcpUrl: "https://abc.trycloudflare.com/mcp", tokenLast4: "9f2c", healthOk: true, message: "Running." };

test("the PC pages have their own routes", () => {
  assert.deepEqual(parseRoute("#/remote"), { name: "remote" });
  assert.deepEqual(parseRoute("#/attach"), { name: "attach" });
  assert.equal(routeHref({ name: "attach" }), "#/attach");
  assert.equal(sectionOf({ name: "remote" }), "remote");
});

test("tunnel status is parsed defensively and never carries the bearer", () => {
  const parsed = parseTunnel({ state: "weird", token: "secret", tokenLast4: "abcd1234" });
  assert.equal(parsed.state, "unknown");
  assert.equal(parsed.tokenLast4, "1234");
  assert.equal("token" in parsed, false);
  assert.equal(parseTunnel(RUNNING).mode, "readonly");
});

test("Remote MCP shows state and address, and starts with the chosen mode", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/pc/tunnel": () => ({ ...RUNNING, state: "stopped", mcpUrl: null }),
    "POST /v1/pc/tunnel/start": ({ body }) => ({ ...RUNNING, mode: body.mode }),
  });
  const page = createRemotePage(ctx(gateway.fetch));
  await flush();
  assert.match(page.element.textContent, /Off/);
  button(page, "Start").click();
  await flush();
  const start = gateway.calls.find((c) => c.path === "/v1/pc/tunnel/start");
  assert.deepEqual(start.body, { mode: "readonly" });
  assert.equal(start.auth, "Bearer t");
  assert.match(page.element.textContent, /abc\.trycloudflare\.com\/mcp/);
  assert.match(page.element.textContent, /…9f2c/);
  assert.equal(gateway.calls.some((c) => c.path === "/v1/pc/tunnel/token"), false, "the token is fetched only on Show");
  page.destroy();
});

const FLEET = { controlApiBase: "", defaultGoal: "Watch", hasVmosApiKey: true,
  pads: [{ id: "pad-1", label: "Pad 1", sshHost: "h", sshPort: 1824, sshUser: "s", localAdbPort: 63670, remoteAdbSpec: "localhost:1", hasConnectKey: true }] };

test("ChatGPT Attach saves without resending saved keys and sends a new key once", async () => {
  installMiniDom();
  const saves = [];
  const gateway = fakeGateway({
    "GET /v1/pc/attach/fleet": () => FLEET,
    "GET /v1/pc/attach/share": () => ({ ok: false, running: false, url: "", localBase: "" }),
    "PUT /v1/pc/attach/fleet": ({ body }) => { saves.push(body); return FLEET; },
  });
  const page = createAttachPage(ctx(gateway.fetch));
  await flush();
  assert.match(page.element.textContent, /Key saved/);
  button(page, "Save").click();
  await flush();
  assert.equal("vmosApiKey" in saves[0], false);
  assert.equal("connectKey" in saves[0].pads[0], false);
  assert.equal("hasConnectKey" in saves[0].pads[0], false);
  const passwords = page.element.querySelectorAll("input").filter((i) => i.type === "password");
  assert.equal(passwords.length, 2);
  for (const input of passwords) assert.equal(input.value, "", "saved keys are never filled in");
  passwords[0].value = "NEW-ACCESS-KEY";
  passwords[0].dispatchEvent({ type: "input" });
  button(page, "Save").click();
  await flush();
  assert.equal(saves[1].vmosApiKey, "NEW-ACCESS-KEY");
  button(page, "Save").click();
  await flush();
  assert.equal("vmosApiKey" in saves[2], false, "Glass forgets a key once the gateway has it");
  page.destroy();
});

test("sync mints a cloud session per ready pad; a not-ready pad gets a plain hint", async () => {
  const gateway = fakeGateway({
    "POST /v1/pc/attach/sync": () => ({ controlApi: "", generatedAt: "now", pads: [
      { id: "a", label: "A", ok: true, deviceId: "", serial: "localhost:63670", adb: "device", mobile: "running" },
      { id: "b", label: "B", ok: true, deviceId: "", serial: "localhost:63671", adb: "device", mobile: "running" },
    ] }),
    "POST /v1/fleet/scan": () => ({ devices: [] }),
    "POST /cloud/v1/sessions": ({ body }) => ({ sessionId: `s-${body.serial}`, sessionToken: "tok", deviceId: body.serial === "localhost:63670" ? "dev-a" : "dev-b" }),
    "GET /cloud/v1/devices/dev-a/status": () => ({ adb: "device", mobileInstalled: true, mobileRunning: true, gatewayReady: true, trustReady: true }),
    "GET /cloud/v1/devices/dev-b/status": () => ({ adb: "device", mobileInstalled: true, mobileRunning: false, gatewayReady: true, trustReady: true }),
    "GET /v1/pc/attach/share": () => ({ running: true, url: "https://x.trycloudflare.com/cloud" }),
  });
  const client = new GatewayClient({ token: "t", fetch: gateway.fetch, baseUrl: "http://127.0.0.1:8765" });
  const result = await syncFleet(client, "http://127.0.0.1:8765", async () => {});
  assert.equal(result.pads[0].sessionSource, "control-api");
  assert.equal(result.pads[1].ok, false);
  assert.match(result.pads[1].error, /not running/);
  assert.equal(result.pads[1].sessionToken, "");
  assert.equal(result.controlApi, "https://x.trycloudflare.com/cloud");
});

test("the run/stop card says how to stop, start again and update, and closes", () => {
  installMiniDom();
  let closed = 0;
  const card = createWelcomeCard(() => { closed += 1; });
  const text = card.textContent;
  assert.match(text, /Ctrl\+C/);
  assert.match(text, /type: cyclone/);
  assert.match(text, /cyclone update/);
  assert.equal(RUN_STOP_CARD.rows.length, 4);
  card.querySelectorAll("button").find((b) => b.getAttribute("aria-label") === "Close").click();
  card.querySelectorAll("button").find((b) => b.textContent === "Got it").click();
  assert.equal(closed, 2);
});

test("the shell shows the run/stop card once, and the sidebar button shows it again", async () => {
  installMiniDom();
  const { GlassApp } = await import("../.test-dist/app.js");
  let seen = false;
  const gateway = fakeGateway({
    "GET /v1/devices": () => ({ devices: [] }),
    "GET /v1/pc/welcome": () => ({ seen }),
    "POST /v1/pc/welcome/seen": () => { seen = true; return { seen }; },
  });
  const root = document.createElement("div");
  const app = new GlassApp({
    root, client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", location: { hash: "#/settings" },
    storage: null, setHash() {}, onHashChange: () => () => {}, setInterval: () => 1, clearInterval() {},
  });
  await app.start();
  await flush();
  assert.equal(root.querySelectorAll(".welcome-card").length, 1);
  root.querySelectorAll("button").find((b) => b.textContent === "Got it").click();
  await flush();
  assert.equal(root.querySelectorAll(".welcome-card").length, 0);
  assert.equal(seen, true);
  root.querySelectorAll("button").find((b) => b.textContent === "Start and stop").click();
  assert.equal(root.querySelectorAll(".welcome-card").length, 1);
  app.stop();
});
