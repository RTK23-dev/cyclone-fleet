import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createAppKnowledgePage } from "../.test-dist/pages/appKnowledgePage.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { parseScenarios, parseVersions } from "../.test-dist/services/knowledge.js";

const GM = "package:com.google.android.gm";
const HOME = "screen:home:aaaaaaaaaaaaaaaa";
const MENU = "screen:menu:bbbbbbbbbbbbbbbb";
const SETTINGS = "screen:settings:cccccccccccccccc";
const SCENARIOS = {
  placeId: GM,
  persona: "mapping",
  entryScreenId: HOME,
  scenarios: [
    { scenarioId: "sc_0123456789abcdef01", title: "Reach Settings", startScreenId: HOME, endScreenId: SETTINGS, route: [HOME, MENU, SETTINGS], steps: 2, danger: true, health: "critical", lastVerifiedAt: Date.now() - 60_000, appVersion: "2026.09.14", runs: [{ runId: "ai-run-2", status: "failed", startedAt: Date.now() - 120_000 }] },
    { scenarioId: "sc_0123456789abcdef02", title: "Reach Menu", startScreenId: HOME, endScreenId: MENU, route: [HOME, MENU], steps: 1, danger: false, health: "passing", lastVerifiedAt: null, appVersion: null, runs: [] },
  ],
};
const VERSIONS = {
  placeId: GM,
  installedVersion: { versionName: "2026.10.01", versionCode: 130 },
  needsRemap: true,
  versions: [
    { versionName: "2026.09.14", versionCode: 120, installed: false, doors: 3, rooms: 4, failingDoors: 1, lastSeenAt: Date.now() - 86_400_000 },
  ],
  staleDoorCount: 3,
  staleDoors: [{ edgeId: "edge:abc", fromScreenId: HOME, toScreenId: MENU, versionName: "2026.09.14", versionCode: 120 }],
};

function open(tab, routes) {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [], truncated: false }), ...routes });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.11.dev1" })];
  const navigated = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "1.0.0-alpha.5", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} };
  const page = createAppKnowledgePage(ctx, { name: "app", placeId: GM, tab }, { fetch: gateway.fetch });
  return { page, gateway, navigated };
}

test("app tabs route: map, scenarios, versions", () => {
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/scenarios`).tab, "scenarios");
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/versions`).tab, "versions");
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/nonsense`).tab, "map");
  assert.equal(routeHref({ name: "app", placeId: GM, tab: "versions" }), `#/apps/${encodeURIComponent(GM)}/versions`);
});

test("Scenarios: health, routes and runs from the phone; a route opens on the map", async () => {
  const { page, gateway, navigated } = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => SCENARIOS });
  await flush();
  const call = gateway.calls.find((c) => c.path.endsWith("/apps/scenarios"));
  assert.deepEqual(call.query, { placeId: GM, persona: "mapping" });
  const text = page.element.textContent;
  assert.match(text, /Reach Settings/);
  assert.match(text, /Critical/);
  assert.match(text, /Passes a guarded door/);
  assert.match(text, /2 doors/);
  assert.match(text, /Runs that went here/);
  assert.equal(page.element.querySelectorAll(".scenario-card").length, 2);
  assert.equal(page.element.querySelector(".tab.active").textContent, "Scenarios");
  page.element.querySelector('[data-scenario-id="sc_0123456789abcdef01"] .btn').click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: GM, tab: "map", route: [HOME, MENU, SETTINGS] });
});

test("Versions: installed version, needs remap, per-version doors and stale doors", async () => {
  const { page, navigated } = open("versions", { "GET /v1/devices/d1/apps/versions": () => VERSIONS });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /2026\.10\.01 \(130\)/);
  assert.match(text, /Needs remap/);
  assert.match(text, /2026\.09\.14 \(120\)/);
  assert.match(text, /3 doors last confirmed on an older version/);
  assert.match(text, /Home screen · aaaa → Menu screen · bbbb/);
  page.element.querySelector(".stale-show").click();
  assert.deepEqual(navigated.at(-1).route, [HOME, MENU]);
});

test("older phones are told to update; empty knowledge explains itself; parsing is defensive", async () => {
  let s = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => json({ detail: { code: "PROTOCOL_MISMATCH", message: "x" } }, 426) });
  await flush();
  assert.match(s.page.element.textContent, /Update Cyclone on the phone/);
  s = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => ({ ...SCENARIOS, scenarios: [] }) });
  await flush();
  assert.match(s.page.element.textContent, /No scenarios yet/);
  const parsed = parseScenarios({ scenarios: [{ scenarioId: "x", route: [HOME] }, { scenarioId: "y", route: [HOME, "Inbox of alice", MENU], health: "great" }] }, GM);
  assert.equal(parsed.scenarios.length, 1);
  assert.deepEqual(parsed.scenarios[0].route, [HOME, MENU]);
  assert.equal(parsed.scenarios[0].health, "untested");
  assert.equal(parseVersions({ staleDoors: [{ edgeId: "e", fromScreenId: "bad", toScreenId: MENU }] }, GM).staleDoors.length, 0);
});

test("Scenarios board: entry on the left, then columns by doors away", async () => {
  const { page, navigated } = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => SCENARIOS });
  await flush();
  [...page.element.querySelectorAll(".segment")].find((b) => /Board/.test(b.textContent)).click();
  const columns = page.element.querySelectorAll(".scenario-column");
  assert.equal(columns.length, 3);
  assert.match(columns[0].textContent, /Home screen · aaaa/);
  assert.match(columns[1].textContent, /1 door away/);
  assert.match(columns[2].textContent, /Reach Settings/);
  page.element.querySelector('button.scenario-mini[data-scenario-id="sc_0123456789abcdef02"]').click();
  assert.deepEqual(navigated.at(-1).route, [HOME, MENU]);
});

test("Screens: every room with doors in and out; a row opens it on the map", async () => {
  const atlas = {
    place: { placeId: GM, kind: "package", label: "Gmail", packageName: "com.google.android.gm" },
    persona: "mapping",
    mapStatus: "partial",
    screens: [HOME, MENU].map((screenId, i) => ({ screenId, label: i ? "Menu" : "Home", purpose: i ? "menu" : "home", factSlots: [], risk: { danger: i === 1, classes: [] }, confidence: i ? 0.4 : 0.9, lastObservedAt: null, lastVerifiedAt: null, layout: { x: i * 260, y: 0 } })),
    edges: [{ edgeId: "edge:1", fromScreenId: HOME, toScreenId: MENU, actionHint: "Open menu", risk: { danger: false, classes: [] }, confidence: 0.8, lastVerifiedAt: null }],
    capabilities: [],
    confidence: 0.8,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
  const { page, navigated } = open("screens", { "GET /v1/devices/d1/atlas": () => atlas });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /2 screens · 1 doors/);
  assert.match(text, /Guarded/);
  assert.match(text, /40%/);
  const rows = page.element.querySelectorAll("button.screen-row");
  assert.equal(rows.length, 2);
  assert.equal(rows[0].dataset.screenId, HOME, "most doors out first");
  rows[1].click();
  assert.deepEqual(navigated.at(-1).route, [MENU]);
  const find = page.element.querySelector(".search-input");
  find.value = "menu";
  find.dispatchEvent({ type: "input" });
  assert.equal(rows[0].hidden, true, "Home is filtered out");
  assert.equal(rows[1].hidden, false);
});

test("Runs tab: only runs that entered this app, with the map share; old phones are explained", async () => {
  const base = { goal: "g", model: "m", startedAt: Date.now() - 1000, endedAt: null, durationMs: 1000, decisions: 1, stepCount: 2, metrics: {}, cause: null };
  const runs = [
    { ...base, runId: "ai-run-1", status: "completed", mapSteps: 3, modelSteps: 1, places: [{ placeId: GM, appVersion: "1", route: [] }] },
    { ...base, runId: "ai-run-2", status: "failed", mapSteps: 0, modelSteps: 2, places: [{ placeId: "package:com.other.app", appVersion: null, route: [] }] },
  ];
  let s = open("runs", { "GET /v1/devices/d1/runs": () => ({ runs }) });
  await flush();
  assert.equal(s.page.element.querySelectorAll(".run-row").length, 1);
  assert.match(s.page.element.textContent, /75%/);
  assert.match(s.page.element.textContent, /Gmail · 3 steps from the map/);
  s = open("runs", { "GET /v1/devices/d1/runs": () => ({ runs: [{ ...base, runId: "ai-old-1", status: "completed" }] }) });
  await flush();
  assert.match(s.page.element.textContent, /does not record which app/);
});

import { createKnowledgePage } from "../.test-dist/pages/knowledgePage.js";

test("Knowledge: vault slots as set / not set per app, skills, automations and Atlas totals", async () => {
  installMiniDom();
  const summary = {
    vault: { slotCount: 3, setCount: 1, slots: [
      { placeId: "package:com.facebook.katana", persona: "live", slot: "password", set: true, updatedAt: Date.now() - 60_000 },
      { placeId: "package:com.facebook.katana", persona: "live", slot: "otp", set: false, updatedAt: null },
      { placeId: "chrome:https://www.linkedin.com", persona: "live", slot: "password", set: false, updatedAt: null },
    ] },
    skills: [{ id: "skill-1", name: "Morning brief", steps: 3, enabled: true, version: 2 }],
    automations: [{ id: "auto-1", name: "Plug in", trigger: "app_opened", steps: 1, enabled: false }],
    atlas: { places: 4, rooms: 31, doors: 40 },
  };
  const gateway = fakeGateway({ "GET /v1/devices/d1/knowledge": () => summary });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.12.dev1" })];
  const page = createKnowledgePage({ client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /1 of 3/);
  assert.match(text, /Facebook/);
  assert.match(text, /password · set/);
  assert.match(text, /otp · not set/);
  assert.match(text, /www\.linkedin\.com/);
  assert.match(text, /Morning brief/);
  assert.match(text, /app opened · 1 step/);
  assert.match(text, /31/);
  assert.doesNotMatch(text, /hunter2/);
  assert.match(text, /Update Cyclone on the phone to see this list/);
  page.destroy();
});

test("Knowledge: the never-pay list shows guarded doors per app, with unknown dangers dropped", async () => {
  installMiniDom();
  const summary = {
    vault: { slotCount: 0, setCount: 0, slots: [] },
    skills: [],
    automations: [],
    atlas: { places: 1, rooms: 3, doors: 2 },
    guarded: [
      { placeId: "package:com.example.shop", label: "Shop", persona: "mapping", danger: "payment", doors: 2, rooms: 1, roomIds: ["screen:list:bbbbbbbbbbbbbbbb", "Buy now"] },
      { placeId: "package:com.example.shop", label: "Shop", persona: "mapping", danger: "delete-account", doors: 1, rooms: 0 },
      { placeId: "package:com.example.shop", label: "Shop", persona: "mapping", danger: "hack", doors: 9, rooms: 9 },
    ],
  };
  const navigated = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/knowledge": () => summary });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.15.dev1" })];
  const page = createKnowledgePage({ client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /Never pressed/);
  assert.match(text, /Pay or buy · 3/);
  assert.match(text, /Delete · 1/);
  assert.doesNotMatch(text, /hack|· 18/);
  [...page.element.querySelectorAll(".guarded-card .btn")].find((b) => /Show on the map/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: "package:com.example.shop", tab: "map", route: ["screen:list:bbbbbbbbbbbbbbbb"] });
  const link = page.element.querySelector(".guarded-app");
  assert.equal(link.getAttribute("href") ?? link.href, `#/apps/${encodeURIComponent("package:com.example.shop")}/screens`);
  page.destroy();
});

test("Scenarios: Sign in and Already signed in are marked; the password never comes to Glass", async () => {
  const LOGIN = "screen:login:eeeeeeeeeeeeeeee";
  const signed = {
    ...SCENARIOS,
    scenarios: [
      { ...SCENARIOS.scenarios[1], scenarioId: "sc_0123456789abcdef03", kind: "sign-in", title: "Sign in", route: [HOME, LOGIN, SETTINGS], endScreenId: SETTINGS, steps: 2 },
      { ...SCENARIOS.scenarios[1], scenarioId: "sc_0123456789abcdef04", kind: "signed-in", title: "Already signed in" },
      { ...SCENARIOS.scenarios[1], kind: "boss" },
    ],
  };
  const parsed = parseScenarios(signed, GM);
  assert.deepEqual(parsed.scenarios.map((s) => s.kind), ["sign-in", "signed-in", "reach"]);
  const { page } = open("scenarios", {
    "GET /v1/devices/d1/apps/scenarios": () => signed,
    "GET /v1/devices/d1/knowledge": () => ({
      vault: { slotCount: 2, setCount: 1, slots: [
        { placeId: GM, persona: "live", slot: "password", set: true, updatedAt: null },
        { placeId: "package:com.facebook.katana", persona: "live", slot: "otp", set: false, updatedAt: null },
      ] },
      skills: [], automations: [], atlas: { places: 1, rooms: 1, doors: 1 },
    }),
  });
  await flush();
  await flush();
  const text = page.element.textContent;
  assert.match(text, /password · set/);
  assert.doesNotMatch(text, /otp/, "only this app's slots");
  assert.match(text, /Sign in/);
  assert.match(text, /Already signed in/);
  assert.match(text, /No login/);
  assert.match(text, /fills the login from its Vault/);
});

test("Scenarios: switch between the mapping pass and your teaching", async () => {
  const { page, gateway } = open("scenarios", {
    "GET /v1/devices/d1/apps/scenarios": (req) => (req.query.persona === "live" ? { ...SCENARIOS, persona: "live", scenarios: [] } : SCENARIOS),
  });
  await flush();
  assert.equal(page.element.querySelectorAll(".scenario-card").length, 2);
  const teach = [...page.element.querySelectorAll(".persona-toggle button")].find((b) => /Your teaching/.test(b.textContent));
  teach.click();
  await flush();
  assert.deepEqual(gateway.calls.filter((c) => c.path.endsWith("/apps/scenarios")).map((c) => c.query.persona), ["mapping", "live"]);
  assert.match(page.element.textContent, /No scenarios yet/);
  assert.ok(page.element.querySelector(".persona-toggle"), "the switch stays so you can go back");
});

import { staleDays } from "../.test-dist/services/knowledge.js";

test("scenario freshness: routes not confirmed for over two weeks are flagged", async () => {
  const now = Date.now();
  assert.equal(staleDays(null, now), null);
  assert.equal(staleDays(now - 3 * 86_400_000, now), null);
  assert.equal(staleDays(now - 20 * 86_400_000, now), 20);
  const old = { ...SCENARIOS, scenarios: [{ ...SCENARIOS.scenarios[0], lastVerifiedAt: now - 30 * 86_400_000 }] };
  const { page } = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => old });
  await flush();
  assert.match(page.element.textContent, /Not checked for 30 days/);
});

test("app pages show the installed version, map state and scenario health in the header", async () => {
  const { page } = open("versions", {
    "GET /v1/devices/d1/apps": () => ({
      apps: [{ placeId: GM, kind: "package", label: "Gmail", packageName: "com.google.android.gm", installed: true, installedVersion: { versionName: "2026.10.01", versionCode: 130 }, mapStatus: "mapped", rooms: 4, doors: 3, lastVerifiedAt: null, needsRemap: true, personas: [], mappedVersions: [], scenarios: { passing: 2, warning: 0, critical: 1, untested: 0 } }],
      truncated: false,
    }),
    "GET /v1/devices/d1/apps/versions": () => VERSIONS,
  });
  await flush();
  await flush();
  const facts = page.element.querySelector(".app-facts").textContent;
  assert.match(facts, /Installed 2026\.10\.01/);
  assert.match(facts, /Needs remap/);
  assert.match(facts, /3 scenarios · 1 critical/);
});
