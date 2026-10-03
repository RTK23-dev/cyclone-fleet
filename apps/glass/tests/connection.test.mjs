import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { createDevicesPage } from "../.test-dist/pages/devicesPage.js";
import { deviceReadiness, parseConnection, parseDevice } from "../.test-dist/services/devices.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";

const verdict = (over) => ({ layer: "app", code: "APP_STOPPED", ok: false, title: "Cyclone stopped on the phone", message: "Starting it again…", action: { kind: "start_app", label: "Start Cyclone" }, working: true, ...over });
const paired = (connection) => ({ deviceId: "d1", name: "Pixel 8", state: "READY", paired: true, source: "USB", mobileVersion: "5.0.0-alpha.88.dev1", trust: { sessionReady: false }, connection });

function page(devices, routes = {}) {
  installMiniDom();
  const calls = [];
  const client = new GatewayClient({ token: "t", fetch: async (url, init) => {
    calls.push({ method: init?.method ?? "GET", url: String(url), body: init?.body ? JSON.parse(init.body) : undefined });
    const handler = routes[`${init?.method ?? "GET"} ${url}`];
    return handler ? new Response(JSON.stringify(handler()), { status: 200 }) : new Response(JSON.stringify({ detail: { code: "NOT_FOUND" } }), { status: 404 });
  } });
  let refreshed = 0;
  const ctx = { client, version: "1.0.0-alpha.49", devices: devices.map(parseDevice), device: null, devicesError: null,
    navigate() {}, selectDevice() {}, refreshDevices: async () => { refreshed += 1; } };
  const view = createDevicesPage(ctx, { now: () => 0, sleep: async () => {}, care: { now: () => 0, later: () => () => {} } });
  return { view, calls, refreshed: () => refreshed };
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

test("the connection verdict parses defensively and wins the not-ready message", () => {
  assert.equal(parseConnection({ title: "" }), null);
  assert.equal(parseConnection({ title: "x", action: { kind: "shell", label: "Run" } }).action, null);
  const device = parseDevice(paired(verdict({ code: "PHONE_LOCKED", title: "Unlock the phone", message: "Cyclone reconnects as soon as the phone is unlocked.", action: null })));
  assert.equal(device.connection.code, "PHONE_LOCKED");
  assert.match(deviceReadiness(device).message, /^Unlock the phone\. Cyclone reconnects/);
});

test("a broken link shows one plain line and its one fix runs on the phone", async () => {
  const { view, calls, refreshed } = page([paired(verdict({ working: false }))], { "POST /v1/devices/d1/connection/fix": () => ({ ok: true }) });
  const text = view.element.textContent;
  assert.match(text, /Cyclone stopped on the phone/);
  const start = view.element.querySelectorAll("button").find((b) => b.textContent === "Start Cyclone");
  start.click();
  for (let i = 0; i < 5; i++) await settle();
  const fix = calls.find((c) => c.method === "POST");
  assert.equal(fix.url, "/v1/devices/d1/connection/fix");
  assert.deepEqual(fix.body, { action: "start_app" });
  assert.ok(refreshed() >= 1);
  view.destroy();
});

test("when the PC already asked on the phone, Glass shows the code instead of a second Connect", () => {
  const asked = { deviceId: "d2", name: "Galaxy S24", state: "UNPAIRED", paired: false, source: "USB",
    connection: verdict({ layer: "trust", code: "TRUST_CONFIRM", title: "Tap Allow on the phone", message: "The phone asks “Connect this PC?”. Check it shows 123 456, then tap Allow.", action: null, working: true }) };
  const { view } = page([asked]);
  assert.match(view.element.textContent, /123 456/);
  assert.equal(view.element.querySelectorAll("button").find((b) => b.textContent === "Connect"), undefined);
  view.destroy();
  const fresh = { ...asked, connection: verdict({ layer: "trust", code: "TRUST_NEEDED", title: "Connect this phone", message: "", action: { kind: "connect", label: "Connect" }, working: false }) };
  const second = page([fresh]).view;
  assert.equal(second.element.querySelectorAll("button").filter((b) => b.textContent === "Connect").length, 1, "one Connect, not two");
  second.destroy();
});

test("a phone Windows can't see says so in plain words, and a healthy phone shows no line", () => {
  const gone = { deviceId: "d3", name: "Pixel 8", state: "DISCONNECTED", paired: true, source: "USB",
    connection: verdict({ layer: "usb", code: "USB_NOT_DETECTED", title: "Phone not detected", message: "Windows doesn't see the phone. Plug it in with a cable that carries data, not a charge-only one.", action: null, working: false }) };
  const { view } = page([gone]);
  assert.match(view.element.textContent, /charge-only/);
  view.destroy();
  const ok = page([{ ...paired(verdict({ ok: true, code: "READY", title: "Connected", message: "", action: null, working: false })), trust: { sessionReady: true } }]).view;
  assert.equal(ok.element.querySelectorAll(".conn").length, 0);
  ok.destroy();
});
