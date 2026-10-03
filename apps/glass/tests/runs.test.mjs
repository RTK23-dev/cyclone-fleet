import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { causeLabel, formatDuration, parseRunDetail, parseRunSummary } from "../.test-dist/services/runs.js";
import { createRunsPage } from "../.test-dist/pages/runsPage.js";
import { createRunPage } from "../.test-dist/pages/runPage.js";

const FAILED = {
  runId: "ai-run-1",
  goal: "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella",
  model: "model-x",
  status: "suspended",
  startedAt: Date.now() - 10 * 60_000,
  endedAt: null,
  durationMs: 94_000,
  decisions: 3,
  stepCount: 3,
  metrics: { toolCalls: 2, toolFailures: 1, verificationFailures: 0, recoveries: 1, visionChecks: 0, verifiedActions: 1 },
  cause: { kind: "needs-secret", stepIndex: 2, headline: "Stopped at a login wall", detail: "Facebook wants a password", fix: "Set the password slot for this app on the phone, then ask again." },
};
const DONE = { ...FAILED, runId: "ai-run-2", goal: "open clock", status: "completed", cause: null, durationMs: 8_000, stepCount: 2, metrics: { ...FAILED.metrics, toolFailures: 0 } };
const step = (index, outcome, title, events) => ({ index, startedAt: FAILED.startedAt + index * 1000, endedAt: FAILED.startedAt + index * 1000 + 500, title, action: index ? `click:${title}` : null, pageId: index ? "bbbbbbbbbbbbbbbb" : null, outcome, verification: null, recovery: index === 1 ? "recover.back" : null, vision: false, eventsTruncated: false, events });
const DETAIL = {
  ...FAILED,
  result: "Waiting for a password",
  stepsTruncated: false,
  steps: [
    step(0, "info", "Starting the task", [{ at: 1, kind: "START", text: "Starting task", code: "task.start", ok: null, detail: null }]),
    step(1, "recovered", "Messages", [{ at: 2, kind: "ANDROID_EXECUTION", text: "Tapped Messages", code: "executor.failed", ok: false, detail: "action=click:Messages" }]),
    step(2, "failed", "Log in", [{ at: 3, kind: "GATE_SUSPEND", text: "Facebook wants a password", code: "gate.need_secret", ok: null, detail: null }]),
  ],
};

const CLAUSE = { id: "clause-1", text: "check which Gmail I am logged in with", place: "package:com.google.android.gm", status: "verified", proof: "Live email: j***@gmail.com" };
const FACT = { key: "signed-in-email", value: "j***@gmail.com", sourcePlace: "package:com.google.android.gm", sourceRoom: "screen:account:0123456789abcdef", persona: "live", readAtMs: 1000 };

test("navigation fields tolerate older phones but discard unmasked or malformed facts", () => {
  assert.deepEqual(parseRunSummary(FAILED).clauses, []);
  const run = parseRunDetail({ ...DETAIL, clauses: [CLAUSE, { ...CLAUSE, status: "invented" }],
    ledger: [FACT, { ...FACT, value: "jane@gmail.com" }, { ...FACT, persona: "mapping" }],
    steps: [{ ...DETAIL.steps[0], expectedRoomId: FACT.sourceRoom }] });
  assert.deepEqual(run.clauses, [CLAUSE]);
  assert.deepEqual(run.ledger, [FACT]);
  assert.equal(run.steps[0].expectedRoomId, FACT.sourceRoom);
  assert.equal(parseRunDetail({ ...DETAIL, steps: [{ ...DETAIL.steps[0], expectedRoomId: "private text" }] }).steps[0].expectedRoomId, null);
});

test("run inspector renders clauses and masked live facts without evaluating proof", async () => {
  installMiniDom();
  const detail = { ...DETAIL, clauses: [CLAUSE, { ...CLAUSE, id: "clause-2", text: "open sign-up", place: "chrome:https://instagram.com", status: "needs-approval", proof: "Approval before submit" }],
    ledger: [FACT], steps: [{ ...DETAIL.steps[0], expectedRoomId: FACT.sourceRoom }] };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => detail });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  assert.match(page.element.querySelector(".clauses-card").textContent, /Approval before submit/);
  assert.match(page.element.querySelector(".clauses-card").textContent, /Chrome · instagram.com/);
  assert.match(page.element.querySelector(".ledger-card").textContent, /j\*\*\*@gmail.com/);
  assert.doesNotMatch(page.element.textContent, /jane@gmail.com/);
  page.destroy();
});

function ctx(fetch, device = READY_DEVICE) {
  const devices = [parseDevice(device)];
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.2", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}

test("run routes round-trip and belong to the Runs section", () => {
  assert.deepEqual(parseRoute("#/runs"), { name: "runs" });
  assert.deepEqual(parseRoute(routeHref({ name: "run", runId: "ai-3f2a" })), { name: "run", runId: "ai-3f2a" });
  assert.deepEqual(parseRoute("#/runs/..%2Fetc"), { name: "runs" });
  assert.equal(sectionOf({ name: "run", runId: "ai-3f2a" }), "runs");
});

test("a phone-side 401/403 is not mistaken for an ended Glass session", async () => {
  let expired = 0;
  const client = new GatewayClient({
    token: "t",
    onSessionExpired: () => expired++,
    fetch: async (url) =>
      url.endsWith("/pairing")
        ? json({ detail: { code: "PAIRING_REQUIRED", message: "Pair the phone." } }, 401)
        : json({ detail: "Invalid bearer token" }, 403),
  });
  await assert.rejects(client.get("/pairing"), (e) => e.code === "PAIRING_REQUIRED" && !e.sessionExpired);
  assert.equal(expired, 0);
  await assert.rejects(client.get("/other"), (e) => e.sessionExpired);
  assert.equal(expired, 1);
});

test("run parsing is defensive and labels are plain", () => {
  assert.equal(parseRunSummary({ runId: "../x" }), null);
  const run = parseRunSummary({ ...FAILED, status: "exploded", metrics: null });
  assert.equal(run.status, "failed");
  assert.equal(run.metrics.toolCalls, 0);
  const detail = parseRunDetail({ ...DETAIL, steps: [...DETAIL.steps, { nope: true }] });
  assert.equal(detail.steps.length, 3);
  assert.equal(causeLabel("needs-secret"), "Login wall");
  assert.equal(causeLabel("something-new"), "something new");
  assert.equal(formatDuration(94_000), "1m 34s");
  assert.equal(formatDuration(0), "—");
});

test("Runs page lists runs with how they ended and why, and filters on the phone", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs": ({ query }) => ({ runs: query.filter === "failed" ? [FAILED] : [FAILED, DONE] }),
  });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const rows = page.element.querySelectorAll("a.run-row");
  assert.equal(rows.length, 2);
  assert.equal(rows[0].href, "#/runs/ai-run-1");
  assert.match(rows[0].textContent, /Waiting/);
  assert.match(rows[0].textContent, /Login wall/);
  assert.match(rows[0].textContent, /1 failed/);
  assert.match(rows[1].textContent, /Finished/);
  assert.equal(gateway.calls[0].query.limit, "100");

  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "failed").click();
  await flush();
  assert.equal(gateway.calls.at(-1).query.filter, "failed");
  assert.equal(page.element.querySelectorAll("a.run-row").length, 1);

  page.element.querySelector(".search-input").value = "clock";
  page.element.querySelector(".search-input").dispatchEvent({ type: "input" });
  assert.match(page.element.textContent, /No runs match/);
  page.destroy();
});

test("an older phone is told to update; an empty phone says how runs appear", async () => {
  installMiniDom();
  const old = fakeGateway({ "GET /v1/devices/d1/runs": () => json({ detail: { code: "PROTOCOL_MISMATCH", message: "Unsupported gateway operation: runs.list" } }, 502) });
  let page = createRunsPage(ctx(old.fetch));
  await flush();
  assert.match(page.element.textContent, /5\.0\.0-alpha\.8/);
  const empty = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: [] }) });
  page = createRunsPage(ctx(empty.fetch));
  await flush();
  assert.match(page.element.textContent, /No runs yet/);
});

test("run inspector opens on the step where the run broke, with cause and fix", async () => {
  installMiniDom();
  const saved = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => DETAIL });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" }, { saveFile: (name, text) => saved.push({ name, text }) });
  await flush();
  const text = page.element.textContent;
  assert.match(page.element.querySelector(".run-title").textContent, /Louella/);
  assert.match(text, /Cause of death/);
  assert.match(text, /Stopped at a login wall/);
  assert.match(text, /Set the password slot/);
  assert.equal(page.element.querySelectorAll(".timeline-item").length, 3);
  assert.equal(page.element.querySelector(".timeline-item.selected .timeline-button").dataset.step, "2");
  assert.match(page.element.querySelector(".step-detail").textContent, /Where it broke/);
  assert.match(page.element.querySelector(".step-detail").textContent, /GATE_SUSPEND/);

  page.element.querySelectorAll(".timeline-button")[1].click();
  const detail = page.element.querySelector(".step-detail").textContent;
  assert.match(detail, /Recovered/);
  assert.match(detail, /recover\.back/);
  assert.match(detail, /executor\.failed/);

  page.element.querySelectorAll(".cause-card .btn")[0].click();
  assert.equal(page.element.querySelector(".timeline-item.selected .timeline-button").dataset.step, "2");

  [...page.element.querySelectorAll(".run-header .btn")].find((b) => /Download report/.test(b.textContent)).click();
  assert.equal(saved[0].name, "cyclone-run-ai-run-1.json");
  assert.equal(JSON.parse(saved[0].text).cause.kind, "needs-secret");
  page.destroy();
});

test("a finished run shows its result; a missing run is named", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-2": () => ({ ...DETAIL, ...DONE, result: "Clock is open.", steps: DETAIL.steps.slice(0, 2) }),
    "GET /v1/devices/d1/runs/ai-gone": () => json({ detail: { code: "RUN_NOT_FOUND", message: "No run" } }, 404),
  });
  let page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-2" });
  await flush();
  assert.doesNotMatch(page.element.textContent, /Cause of death/);
  assert.match(page.element.textContent, /Clock is open\./);
  page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-gone" });
  await flush();
  assert.match(page.element.textContent, /Run not found/);
});

const FB = "package:com.facebook.katana";
const HOME = "screen:home:0123456789abcdef";
const LIST = "screen:list:aaaaaaaaaaaaaaaa";
const V2 = {
  ...DETAIL,
  mapSteps: 1,
  modelSteps: 1,
  places: [{ placeId: FB, appVersion: "512.0.0", route: [HOME, LIST] }],
  steps: [
    DETAIL.steps[0],
    { ...DETAIL.steps[1], roomId: HOME, roomAfter: LIST, placeId: FB, appVersion: "512.0.0", decisionSource: "map" },
    { ...DETAIL.steps[2], roomId: LIST, roomAfter: null, placeId: FB, appVersion: "512.0.0", decisionSource: "model" },
  ],
};

test("run record v2: rooms, app version and map vs model per step; the route opens on the app's map", async () => {
  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => V2 });
  const context = { ...ctx(gateway.fetch), navigate: (route) => navigated.push(route) };
  const page = createRunPage(context, { name: "run", runId: "ai-run-1" });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /From the map/);
  assert.match(text, /1 of 2/);
  assert.match(text, /Route on the map/);
  assert.match(text, /Facebook/);
  assert.match(text, /version 512\.0\.0/);
  assert.match(text, /Home screen · 0123/);
  assert.equal(page.element.querySelectorAll(".timeline-source").length, 1, "only the map-chosen step carries the map badge");

  page.element.querySelectorAll(".timeline-button")[1].click();
  const detail = page.element.querySelector(".step-detail").textContent;
  assert.match(detail, /A known route \(no model call\)/);
  assert.match(detail, /List screen · aaaa/);

  [...page.element.querySelectorAll(".route-card .btn")].find((b) => /Show on the map/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  [...page.element.querySelectorAll(".step-detail .btn")].find((b) => /Open this room/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1).route, [HOME, LIST]);
  page.destroy();
});

test("the route card lists the scenarios this run reached, with their health", async () => {
  installMiniDom();
  const scenarios = {
    placeId: FB,
    persona: "mapping",
    entryScreenId: HOME,
    scenarios: [
      { scenarioId: "sc_0123456789abcdef01", kind: "reach", title: "Reach List", startScreenId: HOME, endScreenId: LIST, route: [HOME, LIST], steps: 1, danger: false, health: "critical", lastVerifiedAt: null, appVersion: null, runs: [] },
      { scenarioId: "sc_0123456789abcdef02", kind: "reach", title: "Reach Settings", startScreenId: HOME, endScreenId: "screen:settings:bbbbbbbbbbbbbbbb", route: [HOME, "screen:settings:bbbbbbbbbbbbbbbb"], steps: 1, danger: false, health: "passing", lastVerifiedAt: null, appVersion: null, runs: [] },
    ],
  };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => V2, "GET /v1/devices/d1/apps/scenarios": () => scenarios });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  await flush();
  const text = page.element.querySelector(".route-card").textContent;
  assert.match(text, /Scenarios this run reached/);
  assert.match(text, /Reach List/);
  assert.match(text, /Critical/);
  assert.doesNotMatch(text, /Reach Settings/);
  assert.equal(page.element.querySelector(".route-scenario").href ?? page.element.querySelector(".route-scenario").getAttribute("href"), `#/apps/${encodeURIComponent(FB)}/scenarios`);
  page.destroy();
});

test("v2 parsing drops rooms and places that are not structural; app routes carry the route", () => {
  const parsed = parseRunDetail({ ...V2, places: [{ placeId: "https://evil", route: [] }, { placeId: FB, appVersion: "x y", route: [HOME, "Inbox of alice"] }] });
  assert.equal(parsed.places.length, 1);
  assert.deepEqual(parsed.places[0].route, [HOME]);
  assert.equal(parsed.places[0].appVersion, null);
  assert.equal(parseRunSummary(FAILED).mapSteps, null, "older phones have no map/model split");
  const href = routeHref({ name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  assert.deepEqual(parseRoute(href), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  assert.deepEqual(parseRoute(`#/apps/${encodeURIComponent(FB)}/map?route=bad,${HOME}`).route, [HOME]);
});

test("Runs can be narrowed to one reason and one app", async () => {
  installMiniDom();
  const place = (id) => [{ placeId: id, appVersion: null, route: [] }];
  const runs = [
    { ...FAILED, runId: "ai-run-a", places: place("package:com.facebook.katana"), mapSteps: 0, modelSteps: 2 },
    { ...FAILED, runId: "ai-run-b", cause: { ...FAILED.cause, kind: "timeout" }, places: place("package:com.google.android.gm"), mapSteps: 0, modelSteps: 2 },
    { ...DONE, places: place("package:com.google.android.gm"), mapSteps: 1, modelSteps: 0 },
  ];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs }) });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const [causeSelect, appSelect] = page.element.querySelectorAll("select.runs-select");
  assert.equal(page.element.querySelectorAll("a.run-row").length, 3);
  causeSelect.value = "timeout";
  causeSelect.dispatchEvent({ type: "change" });
  assert.deepEqual(page.element.querySelectorAll("a.run-row").map((r) => r.dataset.runId), ["ai-run-b"]);
  causeSelect.value = "";
  causeSelect.dispatchEvent({ type: "change" });
  appSelect.value = "package:com.google.android.gm";
  appSelect.dispatchEvent({ type: "change" });
  assert.deepEqual(page.element.querySelectorAll("a.run-row").map((r) => r.dataset.runId), ["ai-run-b", "ai-run-2"]);
  assert.match(causeSelect.textContent, /Login wall/);
  page.destroy();
});

test("mapping passes are listed as runs and marked as such", async () => {
  installMiniDom();
  const pass = { ...DONE, runId: "ai-map-1", goal: "Map Gmail", model: "cyclone-mapper", mapSteps: 0, modelSteps: 0, places: [{ placeId: "package:com.google.android.gm", appVersion: "1", route: [] }] };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: [pass] }) });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const row = page.element.querySelector("a.run-row").textContent;
  assert.match(row, /Mapping pass/);
  assert.doesNotMatch(row, /cyclone-mapper/);
  page.destroy();
});

test("a stopped run can be marked expected on the phone, and unmarked", async () => {
  installMiniDom();
  let expected = false;
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-1": () => ({ ...DETAIL, expected }),
    "POST /v1/devices/d1/runs/ai-run-1/mark": ({ body }) => {
      expected = body.expected;
      return { runId: "ai-run-1", expected };
    },
  });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  const button = () => [...page.element.querySelectorAll(".run-header .btn")].find((b) => /expected|Count it again/.test(b.textContent));
  assert.match(button().textContent, /Mark as expected/);
  button().click();
  await flush();
  assert.equal(expected, true);
  assert.match(page.element.textContent, /Marked expected/);
  assert.match(button().textContent, /Count it again/);
  button().click();
  await flush();
  assert.equal(expected, false);
  page.destroy();
});

test("a map-caused death offers the room on the map and, for a stale door, the app's versions", async () => {
  installMiniDom();
  const navigated = [];
  const stale = { ...V2, cause: { kind: "stale-door", stepIndex: 1, headline: "A mapped door stopped working on version 512.0.0", detail: "", fix: "Remap." } };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => stale });
  const page = createRunPage({ ...ctx(gateway.fetch), navigate: (r) => navigated.push(r) }, { name: "run", runId: "ai-run-1" });
  await flush();
  const buttons = [...page.element.querySelectorAll(".cause-card .btn")];
  buttons.find((b) => /Open the room on the map/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  buttons.find((b) => /See app versions/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "versions" });
  page.destroy();
});

import { lastGoodRun, routeSplits } from "../.test-dist/services/runs.js";

test("compare: the last good run of the same goal and where the routes split", async () => {
  const SET = "screen:settings:cccccccccccccccc";
  const MENU = "screen:menu:bbbbbbbbbbbbbbbb";
  const failed = { ...V2, goal: "Open  Facebook settings", status: "failed", startedAt: 5_000_000, places: [{ placeId: FB, appVersion: "1", route: [HOME, LIST] }] };
  const good = { ...DONE, runId: "ai-run-good", goal: "open facebook settings", startedAt: 4_000_000, places: [{ placeId: FB, appVersion: "1", route: [HOME, MENU, SET] }] };
  const older = { ...good, runId: "ai-run-older", startedAt: 1_000_000 };
  const other = { ...good, runId: "ai-run-other", goal: "open clock", startedAt: 4_500_000 };
  const later = { ...good, runId: "ai-run-later", startedAt: 6_000_000 };
  const summaries = [older, good, other, later].map(parseRunSummary);
  assert.equal(lastGoodRun(parseRunSummary(failed), summaries).runId, "ai-run-good");
  assert.deepEqual(routeSplits(parseRunSummary(failed), parseRunSummary(good)), [{ placeId: FB, shared: [HOME], goodNext: MENU, thisNext: LIST }]);

  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => failed, "GET /v1/devices/d1/runs": () => ({ runs: [older, good, other, later] }) });
  const page = createRunPage({ ...ctx(gateway.fetch), navigate: (r) => navigated.push(r) }, { name: "run", runId: "ai-run-1" });
  await flush();
  await flush();
  const card = page.element.querySelector(".compare-card");
  assert.match(card.textContent, /Compared with the last good run/);
  assert.match(card.textContent, /The good run went on to Menu screen · bbbb; this run went to List screen · aaaa instead/);
  assert.equal(gateway.calls.find((c) => c.path === "/v1/devices/d1/runs").query.filter, "completed");
  [...card.querySelectorAll(".btn")].find((b) => /Show the split/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "map", route: [HOME, MENU, LIST], runId: "ai-run-1" });
  page.destroy();
});

test("Ask again sends the same sentence to the phone and opens the Phone page; mapping passes have no Ask again", async () => {
  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-1": () => ({ ...V2, status: "failed" }),
    "POST /v1/devices/d1/ask/start": ({ body }) => ({ accepted: true, goal: body.goal }),
  });
  const page = createRunPage({ ...ctx(gateway.fetch), navigate: (r) => navigated.push(r) }, { name: "run", runId: "ai-run-1" }, { fetch: gateway.fetch });
  await flush();
  [...page.element.querySelectorAll(".run-header .btn")].find((b) => /Ask again/.test(b.textContent)).click();
  await flush();
  assert.equal(gateway.calls.find((c) => c.path.endsWith("/ask/start")).body.goal, V2.goal);
  assert.deepEqual(navigated.at(-1), { name: "phone" });
  page.destroy();

  installMiniDom();
  const mapping = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => ({ ...V2, model: "cyclone-mapper", goal: "Map Facebook" }) });
  const pass = createRunPage(ctx(mapping.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  assert.ok(![...pass.element.querySelectorAll(".run-header .btn")].some((b) => /Ask again/.test(b.textContent)));
  pass.destroy();
});

import { groupByGoal } from "../.test-dist/services/runs.js";

test("Goals view: runs grouped by sentence with how often they worked; mapping passes and expected runs are not counted", async () => {
  const at = Date.now();
  const list = [
    { ...DONE, runId: "ai-run-g1", goal: "open clock", startedAt: at - 1000 },
    { ...DONE, runId: "ai-run-g2", goal: "Open  Clock", status: "failed", cause: FAILED.cause, startedAt: at - 2000 },
    { ...DONE, runId: "ai-run-g3", goal: "open clock", status: "failed", cause: FAILED.cause, startedAt: at - 3000, expected: true },
    { ...DONE, runId: "ai-run-g4", goal: "open gmail", status: "failed", cause: FAILED.cause, startedAt: at - 500 },
    { ...DONE, runId: "ai-map-g5", goal: "Map Clock", model: "cyclone-mapper", startedAt: at },
  ];
  const groups = groupByGoal(list.map(parseRunSummary));
  assert.deepEqual(groups.map((g) => [g.goal, g.runs, g.finished, g.failed]), [["open gmail", 1, 0, 1], ["open clock", 3, 1, 1]]);
  assert.equal(groups[1].successRate, 0.5);
  assert.equal(groups[1].last.runId, "ai-run-g1");

  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: list }) });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  [...page.element.querySelectorAll(".toolbar button")].find((b) => b.textContent === "Goals").click();
  const rows = page.element.querySelectorAll("a.goal-row");
  assert.equal(rows.length, 2);
  assert.match(rows[1].textContent, /50% · 1 of 2/);
  assert.equal(rows[1].href ?? rows[1].getAttribute("href"), "#/runs/ai-run-g1");
  page.destroy();
});

test("a running run is live: re-read until it ends, keeping the step you picked", async () => {
  installMiniDom();
  const timers = [];
  let state = { ...V2, status: "running", cause: null, steps: V2.steps.slice(0, 2), stepCount: 2 };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => state });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" }, { setTimer: (fn) => timers.push(fn), clearTimer: () => (timers.length = 0) });
  await flush();
  assert.match(page.element.textContent, /Live · updating/);
  assert.equal(page.element.querySelectorAll(".timeline-item").length, 2);
  assert.equal(timers.length, 1, "a running run schedules the next read");
  page.element.querySelectorAll(".timeline-button")[0].click();

  state = { ...V2, status: "running", cause: null };
  timers.shift()();
  await flush();
  assert.equal(page.element.querySelectorAll(".timeline-item").length, 3, "new steps appear");
  assert.equal(page.element.querySelector(".timeline-item.selected .timeline-button").dataset.step, "0", "your pick stays");

  state = { ...V2, status: "completed", cause: null };
  timers.shift()();
  await flush();
  assert.doesNotMatch(page.element.textContent, /Live · updating/);
  assert.equal(timers.length, 0, "a finished run stops updating");
  page.destroy();
});

test("the Runs list refreshes itself while a run is going, quietly", async () => {
  installMiniDom();
  const timers = [];
  let list = [{ ...DONE, runId: "ai-run-live", status: "running", cause: null }];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: list }) });
  const page = createRunsPage(ctx(gateway.fetch), { setTimer: (fn) => timers.push(fn), clearTimer: () => (timers.length = 0) });
  await flush();
  assert.equal(timers.length, 1);
  list = [{ ...DONE, runId: "ai-run-live" }];
  timers.shift()();
  await flush();
  assert.equal(timers.length, 0, "nothing running, no more refreshes");
  assert.match(page.element.textContent, /Finished/);
  page.destroy();
});

import { csvCell, runsCsv } from "../.test-dist/services/runs.js";

test("Export CSV: the runs in view, quoted, formulas neutralised", async () => {
  assert.equal(csvCell('say "hi", then'), '"say ""hi"", then"');
  assert.equal(csvCell("=HYPERLINK(1)"), `"'=HYPERLINK(1)"`);
  assert.equal(csvCell(null), '""');
  const csv = runsCsv([parseRunSummary({ ...V2, goal: "open facebook, then messages", status: "failed" })]);
  const lines = csv.trim().split("\r\n");
  assert.equal(lines.length, 2);
  assert.match(lines[0], /^"runId","goal","status"/);
  assert.match(lines[1], /"open facebook, then messages","failed"/);
  assert.match(lines[1], /"com\.facebook\.katana"/);

  installMiniDom();
  const saved = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: [FAILED, DONE] }) });
  const page = createRunsPage(ctx(gateway.fetch), { saveFile: (name, text, type) => saved.push({ name, text, type }) });
  await flush();
  const search = page.element.querySelector(".search-input");
  search.value = "clock";
  search.dispatchEvent({ type: "input" });
  [...page.element.querySelectorAll(".btn")].find((b) => /Export CSV/.test(b.textContent)).click();
  assert.equal(saved[0].type, "text/csv");
  assert.equal(saved[0].text.trim().split("\r\n").length, 2, "only the runs in view");
  page.destroy();
});

test("Learn on a finished run asks the phone to learn it and shows the phone's sentence", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-1": () => DETAIL,
    "POST /v1/devices/d1/runs/ai-run-1/learn": () => ({
      runId: "ai-run-1", learned: true, alreadyLearned: false,
      sentence: "Learned 3 screens, 15 controls and 1 move in Calculator.", apps: [], refusal: null,
    }),
  });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  const learn = () => [...page.element.querySelectorAll(".run-header .btn")].find((b) => /^Learn/.test(b.textContent));
  learn().click();
  await flush();
  assert.ok(gateway.calls.some((c) => c.method === "POST" && c.path === "/v1/devices/d1/runs/ai-run-1/learn"));
  assert.match(page.element.textContent, /Learned 3 screens, 15 controls and 1 move in Calculator\./);
  assert.match(learn().textContent, /Learned/);
  page.destroy();
});

test("a run the phone cannot learn from says why", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-1": () => DETAIL,
    "POST /v1/devices/d1/runs/ai-run-1/learn": () => ({
      runId: "ai-run-1", learned: false, alreadyLearned: false, sentence: "", apps: [],
      refusal: { code: "NOTHING_TO_LEARN", message: "This run is from before Learn existed. Run it again and press Learn." },
    }),
  });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" });
  await flush();
  [...page.element.querySelectorAll(".run-header .btn")].find((b) => /^Learn/.test(b.textContent)).click();
  await flush();
  assert.match(page.element.textContent, /from before Learn existed/);
  page.destroy();
});

test("replay steps through a run: prev/next, filmstrip, arrow keys and expected vs observed", async () => {
  installMiniDom();
  const navigated = [];
  const run = { ...V2, steps: [V2.steps[0], { ...V2.steps[1], expectedRoomId: LIST }, { ...V2.steps[2], expectedRoomId: HOME, roomAfter: LIST, decisionSource: "map" }] };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => run });
  const page = createRunPage({ ...ctx(gateway.fetch), navigate: (route) => navigated.push(route) }, { name: "run", runId: "ai-run-1" });
  await flush();
  const replay = () => page.element.querySelector(".replay");
  const count = () => replay().querySelector(".replay-count").textContent;
  const start = count();
  assert.match(start, /^Step \d of 3$/);
  assert.equal(replay().querySelectorAll(".replay-frame").length, 3);

  replay().querySelectorAll(".replay-frame")[1].click();
  assert.equal(count(), "Step 2 of 3");
  assert.match(replay().textContent, /Expected.*List screen · aaaa/);
  assert.ok(replay().querySelector(".replay-cell.hit"), "arrived where the map said");
  assert.match(replay().textContent, /SourceMap/);

  replay().querySelector(".replay-next").click();
  assert.equal(count(), "Step 3 of 3");
  assert.ok(replay().querySelector(".replay-cell.miss"), "landed somewhere the map did not expect");
  assert.match(replay().textContent, /landed elsewhere/);
  assert.equal(replay().querySelector(".replay-next").disabled, true);

  page.element.onkeydown({ key: "ArrowLeft", target: null, preventDefault() {} });
  assert.equal(count(), "Step 2 of 3");
  page.element.onkeydown({ key: "ArrowLeft", target: null, preventDefault() {} });
  assert.equal(count(), "Step 1 of 3");
  assert.equal(replay().querySelector(".replay-prev").disabled, true);

  replay().querySelectorAll(".replay-frame")[2].click();
  replay().querySelector(".replay-map-link").click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  page.destroy();
});
