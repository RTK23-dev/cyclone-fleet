import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { attentionList, createHomePage } from "../.test-dist/pages/homePage.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { parseAppCatalog } from "../.test-dist/services/apps.js";
import { parseRunSummary } from "../.test-dist/services/runs.js";

const GM = "package:com.google.android.gm";
const CLOCK = "package:com.google.android.deskclock";
const SHOP = "package:com.example.shop";

function app(placeId, label, extra = {}) {
  return { placeId, kind: "package", label, packageName: placeId.slice(8), installed: true, installedVersion: { versionName: "1", versionCode: 1 }, mapStatus: "mapped", rooms: 3, doors: 2, lastVerifiedAt: null, needsRemap: false, personas: [], mappedVersions: [], ...extra };
}

function run(runId, status, placeId, startedAt, extra = {}) {
  return { runId, goal: `goal ${runId}`, model: "m", status, startedAt, endedAt: startedAt + 1, durationMs: 1, decisions: 1, stepCount: 1, metrics: {}, cause: status === "failed" ? { kind: "stale-door", stepIndex: 1, headline: "h", detail: "", fix: "" } : null, places: [{ placeId, appVersion: null, route: [] }], ...extra };
}

const APPS = {
  apps: [
    app(GM, "Gmail", { needsRemap: true }),
    app(CLOCK, "Clock"),
    app(SHOP, "Shop", { scenarios: { passing: 1, warning: 0, critical: 2, untested: 0 } }),
    app("package:com.example.calm", "Calm", { mapStatus: "unmapped" }),
  ],
  truncated: false,
};
const now = Date.now();
const RUNS = { runs: [run("ai-run-1", "failed", CLOCK, now - 1000), run("ai-run-0", "completed", CLOCK, now - 5000), run("ai-run-2", "failed", GM, now - 2000, { expected: true })] };

test("attention: critical scenarios, then failed last runs, then needs remap; expected runs do not count", () => {
  const rows = attentionList(parseAppCatalog(APPS).apps, RUNS.runs.map(parseRunSummary));
  assert.deepEqual(rows.map((r) => r.label), ["Shop", "Clock", "Gmail"]);
  assert.match(rows[0].reason, /2 critical scenarios/);
  assert.match(rows[1].reason, /Last run failed/);
  assert.equal(rows[1].href, "#/runs/ai-run-1");
  assert.match(rows[2].reason, /updated since it was mapped/);
  assert.match(rows[2].href, /\/versions$/);
});

test("Home: stats, attention, latest runs and knowledge from the phone", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => APPS,
    "GET /v1/devices/d1/runs": () => RUNS,
    "GET /v1/devices/d1/knowledge": () => ({
      vault: { slotCount: 2, setCount: 1, slots: [] },
      skills: [],
      automations: [],
      atlas: { places: 3, rooms: 9, doors: 7 },
      guarded: [{ placeId: SHOP, label: "Shop", persona: "mapping", danger: "payment", doors: 2, rooms: 1 }],
    }),
  });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.15.dev1" })];
  const page = createHomePage({ client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /3 of 4/);
  assert.match(text, /Needs attention/);
  assert.match(text, /goal ai-run-1/);
  assert.match(text, /Expected/);
  assert.match(text, /9 rooms and 7 doors in 3 places/);
  assert.match(text, /1 of 2 secrets set/);
  assert.match(text, /3 guarded doors in 1 app, never pressed/);
  page.destroy();
});

test("Home: an older phone without runs or knowledge still shows its apps", async () => {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [app(CLOCK, "Clock")], truncated: false }) });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.8.dev1" })];
  const page = createHomePage({ client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /Nothing needs you/);
  assert.match(text, /Runs are not available/);
  assert.match(text, /Update Cyclone on the phone/);
  page.destroy();
});

import { runsPerDay } from "../.test-dist/pages/homePage.js";

test("runs per day: seven days oldest first, expected failures are not failures", () => {
  const now = new Date(2026, 8, 24, 12, 0, 0).getTime();
  const day = 86_400_000;
  const runs = [
    run("ai-run-a", "completed", CLOCK, now - 1000),
    run("ai-run-b", "failed", CLOCK, now - 2000),
    run("ai-run-c", "failed", CLOCK, now - 3000, { expected: true }),
    run("ai-run-d", "completed", CLOCK, now - 2 * day),
    run("ai-run-e", "completed", CLOCK, now - 30 * day),
  ].map(parseRunSummary);
  const bars = runsPerDay(runs, now);
  assert.equal(bars.length, 7);
  assert.equal(bars[6].label, "Today");
  assert.deepEqual([bars[6].finished, bars[6].failed, bars[6].other], [1, 1, 1]);
  assert.equal(bars[4].finished, 1);
  assert.equal(bars.reduce((sum, b) => sum + b.finished + b.failed + b.other, 0), 4, "runs older than a week are left out");
});

test("Home report: counts and outcomes, never slot names or values", async () => {
  installMiniDom();
  const saved = [];
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => APPS,
    "GET /v1/devices/d1/runs": () => RUNS,
    "GET /v1/devices/d1/knowledge": () => ({
      vault: { slotCount: 1, setCount: 1, slots: [{ placeId: GM, persona: "live", slot: "password", set: true, updatedAt: null }] },
      skills: [], automations: [], atlas: { places: 1, rooms: 3, doors: 2 },
    }),
  });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.16.dev1" })];
  const page = createHomePage({ client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "1.0.0-alpha.9", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} }, { saveFile: (name, text) => saved.push({ name, text }) });
  await flush();
  [...page.element.querySelectorAll(".btn")].find((b) => /Download report/.test(b.textContent)).click();
  assert.equal(saved.length, 1);
  assert.match(saved[0].name, /^cyclone-home-\d{4}-\d{2}-\d{2}\.json$/);
  const report = JSON.parse(saved[0].text);
  assert.equal(report.kind, "cyclone-glass-home-report");
  assert.equal(report.phone.mobileVersion, "5.0.0-alpha.16.dev1");
  assert.equal(report.apps.length, 4);
  assert.equal(report.runs.length, 3);
  assert.equal(report.knowledge.secretsSet, 1);
  assert.doesNotMatch(saved[0].text, /password/);
  page.destroy();
});
