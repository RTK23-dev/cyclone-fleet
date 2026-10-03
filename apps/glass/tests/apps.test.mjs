import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { catalogStats, currentShare, filterApps, freshnessOf, knowledgeOf, parseAppCatalog, sortApps, statusLabel, versionLabel } from "../.test-dist/services/apps.js";
import { createAppsPage } from "../.test-dist/pages/appsPage.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";

export const GMAIL = {
  placeId: "package:com.google.android.gm",
  kind: "package",
  label: "Gmail",
  packageName: "com.google.android.gm",
  origin: null,
  installed: true,
  installedVersion: { versionName: "2026.09.01", versionCode: 900 },
  mapStatus: "mapped",
  rooms: 6,
  doors: 11,
  lastVerifiedAt: Date.now() - 5 * 60_000,
  needsRemap: true,
  personas: [{ persona: "mapping", mapStatus: "mapped", rooms: 6, doors: 11, lastVerifiedAt: null }],
  mappedVersions: [{ versionName: "2026.08.01", versionCode: 880, doors: 9 }],
};
const CLOCK = { ...GMAIL, placeId: "package:com.example.clock", label: "Clock", packageName: "com.example.clock", mapStatus: "unmapped", rooms: 0, doors: 0, needsRemap: false, personas: [], mappedVersions: [], installedVersion: { versionName: "8.1", versionCode: 81 } };
const FACEBOOK = { ...CLOCK, placeId: "chrome:https://www.facebook.com", kind: "chrome-origin", label: "www.facebook.com", packageName: null, origin: "https://www.facebook.com", installed: null, installedVersion: null, mapStatus: "partial", rooms: 3, doors: 2 };

test("catalog parsing drops rows it cannot trust and keeps versions", () => {
  const catalog = parseAppCatalog({ apps: [GMAIL, { placeId: "file:///x" }, null, CLOCK], truncated: false });
  assert.deepEqual(catalog.apps.map((a) => a.label), ["Gmail", "Clock"]);
  assert.equal(versionLabel(catalog.apps[0].installedVersion), "2026.09.01");
  assert.equal(versionLabel({ versionName: null, versionCode: 42 }), "build 42");
  assert.equal(statusLabel(catalog.apps[0]), "Needs remap");
  assert.equal(statusLabel(catalog.apps[1]), "Not mapped");
});

test("filters, sort and stats describe the phone honestly", () => {
  const apps = parseAppCatalog({ apps: [CLOCK, FACEBOOK, GMAIL], truncated: false }).apps;
  assert.deepEqual(sortApps(apps).map((a) => a.label), ["Gmail", "www.facebook.com", "Clock"], "mapped first, most rooms first");
  assert.deepEqual(filterApps(apps, "needs-remap", "").map((a) => a.label), ["Gmail"]);
  assert.deepEqual(filterApps(apps, "unmapped", "").map((a) => a.label), ["Clock"]);
  assert.deepEqual(filterApps(apps, "web", "").map((a) => a.label), ["www.facebook.com"]);
  assert.deepEqual(filterApps(apps, "all", "google").map((a) => a.label), ["Gmail"]);
  assert.deepEqual(catalogStats(apps), { total: 3, mapped: 2, needsRemap: 1, rooms: 9 });
});

function context(fetch, device = READY_DEVICE) {
  const client = new GatewayClient({ token: "t", fetch });
  const devices = [parseDevice(device)];
  return { client, version: "1.0.0-alpha.1", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}

test("Apps page lists the phone's apps with status, versions and links into each map", async () => {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [CLOCK, GMAIL, FACEBOOK], truncated: false }) });
  const page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  const rows = page.element.querySelectorAll("a.app-row");
  assert.equal(rows.length, 3);
  assert.equal(rows[0].dataset.placeId, "package:com.google.android.gm");
  assert.equal(rows[0].getAttribute("href") ?? rows[0].href, "#/apps/package%3Acom.google.android.gm/map");
  assert.match(rows[0].textContent, /Needs attention/);
  assert.match(rows[0].textContent, /Map from 2026\.08\.01/);
  assert.match(rows[0].textContent, /6 places · 11 doors/);
  assert.match(rows[0].textContent, /Open/);
  assert.match(rows[1].textContent, /Web · https:\/\/www\.facebook\.com/);
  assert.match(page.element.querySelector(".stats").textContent, /Needs attention/);
  assert.match(page.element.querySelector('a.app-row[data-place-id="package:com.example.clock"]').textContent, /Start mapping/);
  assert.equal(gateway.calls[0].auth, "Bearer t");

  const segments = page.element.querySelectorAll(".segment");
  segments.find((s) => s.dataset.id === "unmapped").click();
  assert.equal(page.element.querySelectorAll("a.app-row").length, 1);
  assert.match(page.element.querySelector("a.app-row").textContent, /Clock/);
  page.destroy();
});

test("an older phone without apps.list is told to update, other errors can retry", async () => {
  installMiniDom();
  let fail = true;
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () =>
      fail ? json({ detail: { code: "PROTOCOL_MISMATCH", message: "Unsupported gateway operation: apps.list" } }, 502) : { apps: [CLOCK], truncated: false },
  });
  let page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  assert.match(page.element.textContent, /Update Cyclone on the phone/);
  assert.match(page.element.textContent, /5\.0\.0-alpha\.7/);

  const other = fakeGateway({ "GET /v1/devices/d1/apps": () => (fail ? json({ detail: { code: "DEVICE_DISCONNECTED", message: "Phone went away." } }, 503) : { apps: [CLOCK], truncated: false }) });
  page = createAppsPage(context(other.fetch), { name: "apps" });
  await flush();
  assert.match(page.element.textContent, /Phone went away/);
  fail = false;
  page.element.querySelector(".empty-state .btn").click();
  await flush();
  assert.equal(page.element.querySelectorAll("a.app-row").length, 1);
});

test("Apps page does not call the phone when the device gate says no", async () => {
  installMiniDom();
  const gateway = fakeGateway({});
  const page = createAppsPage(context(gateway.fetch, { ...READY_DEVICE, mobileVersion: "4.8.0" }), { name: "apps" });
  await flush();
  assert.match(page.element.textContent, /Update Cyclone on the phone/);
  assert.equal(gateway.calls.length, 0);
});

test("Apps page shows each app's last run and filters apps whose last run failed", async () => {
  installMiniDom();
  const base = { goal: "g", model: "m", endedAt: null, durationMs: 1, decisions: 1, stepCount: 1, metrics: {}, cause: null, mapSteps: 1, modelSteps: 0 };
  const runs = [
    { ...base, runId: "ai-run-2", status: "failed", startedAt: 2000, places: [{ placeId: GMAIL.placeId, appVersion: null, route: [] }] },
    { ...base, runId: "ai-run-1", status: "completed", startedAt: 1000, places: [{ placeId: GMAIL.placeId, appVersion: null, route: [] }, { placeId: CLOCK.placeId, appVersion: null, route: [] }] },
  ];
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: [CLOCK, GMAIL, FACEBOOK], truncated: false }),
    "GET /v1/devices/d1/runs": () => ({ runs }),
  });
  const page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  const row = (id) => page.element.querySelector(`a.app-row[data-place-id="${id}"]`);
  assert.match(row(GMAIL.placeId).textContent, /Last run failed/);
  assert.match(row(CLOCK.placeId).textContent, /Last run finished/);
  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "failing").click();
  assert.equal(page.element.querySelectorAll("a.app-row").length, 1);
  assert.equal(page.element.querySelector("a.app-row").dataset.placeId, GMAIL.placeId);
  page.destroy();
});

test("apps show scenario health counts when the phone sends them", async () => {
  installMiniDom();
  const withScenarios = { ...GMAIL, scenarios: { passing: 6, warning: 1, critical: 2, untested: 0 } };
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [withScenarios, CLOCK], truncated: false }) });
  const page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  assert.match(page.element.querySelector(`a.app-row[data-place-id="${GMAIL.placeId}"]`).textContent, /9 scenarios · 2 critical/);
  assert.doesNotMatch(page.element.querySelector(`a.app-row[data-place-id="${CLOCK.placeId}"]`).textContent, /scenario/);
  page.destroy();
});

test("fleet knowledge, freshness and confidence come from the phone's facts only", () => {
  const [gmail, clock, facebook] = parseAppCatalog({ apps: [GMAIL, CLOCK, FACEBOOK], truncated: false }).apps;
  assert.equal(knowledgeOf(gmail), "attention", "needs remap");
  assert.equal(knowledgeOf(clock), "unmapped");
  assert.equal(knowledgeOf(facebook), "partial");
  const fresh = { ...gmail, needsRemap: false, mappedVersions: [{ versionName: "2026.09.01", versionCode: 900, doors: 11 }, { versionName: "2026.08.01", versionCode: 880, doors: 9 }] };
  assert.equal(knowledgeOf(fresh), "ready");
  assert.equal(knowledgeOf(fresh, true), "attention", "a failing last run needs attention");
  assert.equal(knowledgeOf({ ...fresh, scenarios: { passing: 1, warning: 0, critical: 1, untested: 0 } }), "attention");
  assert.deepEqual(freshnessOf(fresh), { text: "Current", tone: "success" });
  assert.deepEqual(freshnessOf(gmail), { text: "Map from 2026.08.01", tone: "warning" });
  assert.equal(currentShare(fresh), 1);
  assert.equal(currentShare(gmail), 0, "no door seen on the installed version");
  assert.equal(currentShare(facebook), null, "web places are not versioned");
});

test("fleet shows the app being mapped live, sorts it first and sorts by least mapped", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: [CLOCK, GMAIL, FACEBOOK], truncated: false }),
    "POST /v1/devices/d1/mapping/status": () => ({
      mappingJobId: "job-12345678", placeId: FACEBOOK.placeId, persona: "mapping", sessionId: "default-foreground", displayId: 0,
      state: "running", currentAtlasNodeId: null, newScreens: 2, verifiedMutations: 1, failureCode: null, atlasStatus: "partial",
    }),
  });
  const page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  await flush();
  const rows = () => page.element.querySelectorAll("a.app-row");
  assert.equal(rows()[0].dataset.placeId, FACEBOOK.placeId, "the live app leads the Activity sort");
  assert.match(rows()[0].textContent, /Mapping…/);
  assert.match(rows()[0].textContent, /Watch/);
  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "least").click();
  assert.deepEqual(rows().map((r) => r.dataset.placeId), [FACEBOOK.placeId, GMAIL.placeId, CLOCK.placeId]);
  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "name").click();
  assert.deepEqual(rows().map((r) => r.dataset.placeId), [CLOCK.placeId, GMAIL.placeId, FACEBOOK.placeId]);
  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "partial").click();
  assert.equal(rows().length, 1);
  page.destroy();
});
