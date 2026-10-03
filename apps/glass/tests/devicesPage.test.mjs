import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { createDevicesPage } from "../.test-dist/pages/devicesPage.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { formatMatchCode, parseTrust, waitForPhone } from "../.test-dist/services/pairing.js";

const connected = { deviceId: "d1", name: "Pixel 8", state: "READY", paired: true, source: "USB", mobileVersion: "5.0.0-alpha.8.dev1", trust: { sessionReady: true } };
const fresh = { deviceId: "d2", name: "Galaxy S24", state: "UNPAIRED", paired: false, source: "USB" };
const unauthorized = { deviceId: "d3", name: "Android phone", state: "UNAUTHORIZED", paired: false };

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

function harness(routes, devices = [connected, fresh, unauthorized]) {
  installMiniDom();
  const calls = [];
  const client = new GatewayClient({
    token: "t",
    fetch: async (url, init) => {
      calls.push(`${init?.method ?? "GET"} ${url}`);
      const handler = routes[url];
      if (!handler) return json({ detail: { code: "NOT_FOUND", message: url } }, 404);
      return handler();
    },
  });
  const state = { selected: null, navigated: null, refreshed: 0 };
  const ctx = {
    client,
    version: "1.0.0-alpha.3",
    devices: devices.map(parseDevice),
    device: null,
    devicesError: null,
    navigate: (route) => (state.navigated = route),
    selectDevice: (id) => (state.selected = id),
    refreshDevices: async () => {
      state.refreshed += 1;
    },
  };
  let clock = 0;
  const deps = { now: () => clock, sleep: async (ms) => { clock += ms; await Promise.resolve(); } };
  const page = createDevicesPage(ctx, deps);
  return { page, ctx, calls, state, advance: (ms) => (clock += ms) };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

test("devices page groups phones: connected, ready to connect, needs the phone", () => {
  const { page } = harness({});
  const text = page.element.textContent;
  assert.match(text, /Connected/);
  assert.match(text, /Pixel 8/);
  assert.match(text, /Ready to connect/);
  assert.match(text, /Galaxy S24/);
  assert.match(text, /Needs the phone/);
  assert.match(text, /Allow USB debugging/);
  assert.match(text, /Cyclone 5\.0\.0-alpha\.8\.dev1/);
});

test("connect shows the phone's six-digit code, waits for Allow, then selects the phone", async () => {
  let answers = 0;
  let whileWaiting = "";
  const h = harness({
    "/v1/devices/d2/trust/begin": () => json({ state: "CONFIRMATION_REQUIRED", confirmationRequired: true, matchCode: "173715" }),
    "/v1/devices/d2/trust/complete": () => {
      if (++answers === 1) whileWaiting = h.page.element.textContent;
      return answers < 3
        ? json({ state: "CONFIRMATION_REQUIRED", confirmationRequired: true, completed: false })
        : json({ state: "TRUSTED", trusted: true, sessionReady: true, completed: true });
    },
  });
  const { page, calls, state } = h;
  const card = page.element.querySelector('[data-device-id="d2"]');
  card.querySelector(".btn-primary").click();
  for (let i = 0; i < 10 && !whileWaiting; i++) await settle();
  assert.match(whileWaiting, /173 715/);
  assert.match(whileWaiting, /Tap Allow/);
  assert.match(whileWaiting, /Waiting for the phone/);
  for (let i = 0; i < 10 && !state.selected; i++) await settle();
  assert.equal(state.selected, "d2");
  assert.ok(state.refreshed >= 1);
  assert.match(page.element.textContent, /is connected/);
  assert.equal(calls.filter((c) => c.endsWith("/trust/complete")).length, 3);
  page.destroy();
});

test("a Not now on the phone and a locked phone are said plainly", async () => {
  const declined = harness({
    "/v1/devices/d2/trust/begin": () => json({ state: "CONFIRMATION_REQUIRED", confirmationRequired: true, matchCode: "000123" }),
    "/v1/devices/d2/trust/complete": () => json({ detail: { code: "TRUST_REJECTED", message: "The phone answered Not now; this PC was not connected." } }, 403),
  });
  declined.page.element.querySelector('[data-device-id="d2"] .btn-primary').click();
  for (let i = 0; i < 10 && !/Not now/.test(declined.page.element.textContent); i++) await settle();
  assert.match(declined.page.element.textContent, /Not now/);
  assert.match(declined.page.element.textContent, /Try again/);

  const locked = harness({
    "/v1/devices/d2/trust/begin": () => json({ detail: { code: "PHONE_LOCKED", message: "Unlock the phone to restore AI/Codex access." } }, 423),
  });
  locked.page.element.querySelector('[data-device-id="d2"] .btn-primary').click();
  for (let i = 0; i < 10 && !/Unlock the phone/.test(locked.page.element.textContent); i++) await settle();
  assert.match(locked.page.element.textContent, /Unlock the phone, then try again/);
});

test("disconnect asks first, then revokes and refreshes", async () => {
  const { page, calls, state } = harness({ "/v1/devices/d1/trust/revoke": () => json({ state: "UNPAIRED", revoked: true }) });
  const buttons = () => [...page.element.querySelectorAll('[data-device-id="d1"] .btn')];
  buttons().find((b) => /Disconnect/.test(b.textContent)).click();
  assert.match(page.element.textContent, /Disconnect Pixel 8\?/);
  // Phone care reads its status on its own (alpha 87); only commands count here.
  // Cloud phones (alpha 90) also read their status on their own.
  const commands = () => calls.filter((call) => !call.endsWith("/care") && !call.endsWith("/v1/cloud"));
  assert.equal(commands().length, 0, "nothing sent before the second click");
  buttons().find((b) => b.classList.contains("btn-danger")).click();
  for (let i = 0; i < 10 && !commands().length; i++) await settle();
  await settle();
  assert.deepEqual(commands(), ["POST /v1/devices/d1/trust/revoke"]);
  assert.ok(state.refreshed >= 1);
});

test("a ready phone can be picked for Glass; a reconnecting one offers Reconnect", () => {
  const reconnecting = { ...connected, deviceId: "d4", mobileVersion: undefined, trust: { sessionReady: false, lastSafeError: "Unlock the phone to restore AI/Codex access." } };
  const { page, state } = harness({}, [connected, reconnecting]);
  const use = [...page.element.querySelectorAll('[data-device-id="d1"] .btn')].find((b) => /Use in Glass/.test(b.textContent));
  use.click();
  assert.equal(state.selected, "d1");
  assert.deepEqual(state.navigated, { name: "apps" });
  const other = page.element.querySelector('[data-device-id="d4"]').textContent;
  assert.match(other, /Reconnecting/);
  assert.match(other, /Unlock the phone/);
  assert.match(other, /Reconnect/);
});

test("waiting gives up after the phone's time limit and parses trust answers defensively", async () => {
  let clock = 0;
  const client = new GatewayClient({ token: "t", fetch: async () => json({ state: "CONFIRMATION_REQUIRED", confirmationRequired: true }) });
  const outcome = await waitForPhone(client, "d2", 0, { now: () => clock, sleep: async (ms) => { clock += ms; }, isCancelled: () => false });
  assert.equal(outcome.kind, "expired");
  assert.equal(parseTrust({ matchCode: "12345x" }).matchCode, null);
  assert.equal(parseTrust(null).state, "UNKNOWN");
  assert.equal(formatMatchCode("173715"), "173 715");
});
