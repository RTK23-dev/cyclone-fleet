import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseCloud, settling } from "../.test-dist/services/cloud.js";
import { createCloudPhonesView, IDLE_POLL_MS, SETTLING_POLL_MS } from "../.test-dist/pages/cloudPhonesView.js";

installMiniDom();
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

const phone = (over = {}) => ({ provider: "vmos", remoteId: "AC1", name: "Shop 1", android: "15", power: "running", address: null,
  keep: false, state: "off", message: "Not kept connected.", serial: null, deviceId: null, expiresAtMs: null, ...over });
const account = (phones = [phone()], over = {}) => ({ id: "acc_1", provider: "vmos", providerLabel: "VMOS Cloud", label: "Shop",
  installCyclone: true, hasKey: true, error: null, phones, ...over });

function clock() {
  const timers = [];
  return {
    timers,
    deps: { later: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return () => { t.cancelled = true; }; } },
    last: () => timers.filter((t) => !t.cancelled).at(-1),
  };
}

test("cloud status parses defensively and knows when a link is still on its way", () => {
  const parsed = parseCloud({ ssh: false, security: "MEMORY_ONLY", accounts: [account([phone({ keep: true, state: "tunnel" }), { junk: 1 }]), { id: "x", provider: "aws" }] });
  assert.equal(parsed.ssh, false);
  assert.equal(parsed.memoryOnly, true);
  assert.equal(parsed.accounts.length, 1);
  assert.equal(parsed.accounts[0].phones.length, 1);
  assert.equal(settling(parsed), true);
  assert.equal(settling(parseCloud({ accounts: [account([phone({ keep: true, state: "connected" })])] })), false);
  assert.equal(settling(parseCloud({ accounts: [account([phone({ keep: true, state: "needs_you" })])] })), false);
});

test("adding a VMOS account sends the keys once and never shows them again", async () => {
  let accounts = [];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, security: "WINDOWS_DPAPI_CURRENT_USER", accounts }),
    "POST /v1/cloud/accounts": ({ body }) => { accounts = [account()]; return accounts[0]; },
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), c.deps);
  await flush();
  assert.equal(view.element.hidden, false);
  assert.match(view.element.textContent, /Connect VMOS Cloud or DuoPlus phones/);
  button(view.element, "Add cloud phones").click();
  const inputs = view.element.querySelectorAll("input").filter((i) => i.type !== "checkbox");
  inputs[0].value = "AKID";
  inputs[1].value = "SECRET-123";
  assert.equal(inputs[1].type, "password");
  button(view.element, "Add").click();
  await flush();
  const sent = gw.calls.find((call) => call.method === "POST" && call.path === "/v1/cloud/accounts");
  assert.deepEqual(sent.body.secrets, { accessKey: "AKID", secretKey: "SECRET-123" });
  assert.equal(sent.body.installCyclone, true);
  assert.match(view.element.textContent, /Shop/);
  assert.doesNotMatch(view.element.textContent, /SECRET-123/);
  assert.equal(c.last().ms, IDLE_POLL_MS);
  view.destroy();
});

test("keeping a phone connected shows its link in one line and looks often until it is connected", async () => {
  let state = phone();
  const changes = [];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [account([state])] }),
    "POST /v1/cloud/accounts/acc_1/phones/AC1": ({ body }) => {
      state = phone({ keep: body.keep, state: body.keep ? "tunnel" : "off", message: body.keep ? "Starting the secure tunnel…" : "Not kept connected." });
      return account([state]);
    },
  });
  const c = clock();
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), { ...c.deps, onChange: () => changes.push(1) });
  await flush();
  button(view.element, "Keep connected").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.method === "POST").body, { keep: true });
  assert.match(view.element.textContent, /Connecting.*Starting the secure tunnel/s);
  assert.equal(c.last().ms, SETTLING_POLL_MS);
  state = phone({ keep: true, state: "connected", message: "Connected · key renews in 5 days", deviceId: "dev_1" });
  c.last().fn();
  await flush();
  assert.match(view.element.textContent, /Connected · key renews in 5 days/);
  assert.equal(changes.length, 1, "the device list above looks again once the phone connects");
  assert.equal(c.last().ms, IDLE_POLL_MS);
  view.destroy();
});

test("a DuoPlus phone asks for its ADB address; problems read in plain words; an older runtime shows nothing", async () => {
  const duo = account([phone({ provider: "duoplus", remoteId: "img1", name: "DP one", keep: true, state: "needs_you",
    message: "Turn on ADB for this phone in DuoPlus, add this PC's IP to the ADB whitelist, then paste the phone's ADB address here." })],
    { id: "acc_2", provider: "duoplus", providerLabel: "DuoPlus", label: "DuoPlus" });
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: true, accounts: [duo] }),
    "POST /v1/cloud/accounts/acc_2/phones/img1": () => json({ detail: { code: "INVALID_REQUEST", message: "That isn't an ADB address." } }, 400),
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), clock().deps);
  await flush();
  assert.match(view.element.textContent, /Needs you/);
  const input = view.element.querySelectorAll("input").find((i) => /ADB address/.test(i.getAttribute("aria-label") ?? ""));
  input.value = "nope";
  button(view.element, "Save").click();
  await flush();
  assert.deepEqual(gw.calls.find((call) => call.method === "POST").body, { address: "nope" });
  assert.match(view.element.textContent, /That isn't an ADB address/);
  view.destroy();

  const old = createCloudPhonesView(new GatewayClient({ token: "t", fetch: fakeGateway({}).fetch }), clock().deps);
  await flush();
  assert.equal(old.element.hidden, true);
  old.destroy();
});

test("removing an account asks first", async () => {
  let accounts = [account()];
  const gw = fakeGateway({
    "GET /v1/cloud": () => ({ ssh: false, accounts }),
    "POST /v1/cloud/accounts/acc_1/remove": () => { accounts = []; return { ok: true }; },
  });
  const view = createCloudPhonesView(new GatewayClient({ token: "t", fetch: gw.fetch }), clock().deps);
  await flush();
  assert.match(view.element.textContent, /OpenSSH Client/);
  button(view.element, "Remove").click();
  assert.match(view.element.textContent, /Remove Shop\?/);
  assert.equal(gw.calls.filter((call) => call.method === "POST").length, 0);
  view.element.querySelectorAll("button").find((b) => b.classList.contains("btn-danger")).click();
  await flush();
  assert.equal(gw.calls.filter((call) => call.path.endsWith("/remove")).length, 1);
  assert.doesNotMatch(view.element.textContent, /Shop/);
  view.destroy();
});
