import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { appIssues } from "../.test-dist/services/issues.js";
import { parseScenarios, parseVersions } from "../.test-dist/services/knowledge.js";
import { parseRunSummary } from "../.test-dist/services/runs.js";
import { parseRoute } from "../.test-dist/core/router.js";
import { createAppKnowledgePage } from "../.test-dist/pages/appKnowledgePage.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";

const GM = "package:com.google.android.gm";
const HOME = "screen:home:aaaaaaaaaaaaaaaa";
const MENU = "screen:menu:bbbbbbbbbbbbbbbb";
const SET = "screen:settings:cccccccccccccccc";

const VERSIONS = {
  installedVersion: { versionName: "2", versionCode: 2 },
  needsRemap: true,
  versions: [{ versionName: "1", versionCode: 1, installed: false, doors: 3, rooms: 3, failingDoors: 0, lastSeenAt: 1 }],
  staleDoorCount: 1,
  staleDoors: [{ edgeId: "edge:1", fromScreenId: HOME, toScreenId: MENU, versionName: "1", versionCode: 1 }],
};
const SCENARIOS = {
  persona: "mapping",
  entryScreenId: HOME,
  scenarios: [
    { scenarioId: "sc_0123456789abcdef01", title: "Reach Settings", route: [HOME, MENU, SET], health: "critical", runs: [] },
    { scenarioId: "sc_0123456789abcdef02", title: "Reach Menu", route: [HOME, MENU], health: "passing", runs: [] },
  ],
};
const run = (runId, startedAt, kind, extra = {}) => ({
  runId, goal: "open gmail settings", model: "m", status: "failed", startedAt, endedAt: startedAt + 1, durationMs: 1, decisions: 1, stepCount: 1, metrics: {},
  cause: { kind, stepIndex: 1, headline: "h", detail: "", fix: "Remap this room." }, places: [{ placeId: GM, appVersion: "2", route: [HOME] }], ...extra,
});
const RUNS = [run("ai-run-1", 3000, "stale-door"), run("ai-run-2", 2000, "stale-door"), run("ai-run-3", 1000, "timeout", { expected: true }), run("ai-run-4", 500, "timeout", { places: [] })];

test("issues: critical first, then warnings, then notes; expected and other apps' runs are left out", () => {
  const issues = appIssues({
    placeId: GM,
    versions: parseVersions(VERSIONS, GM),
    scenarios: parseScenarios(SCENARIOS, GM),
    runs: RUNS.map(parseRunSummary),
    rooms: [{ screenId: HOME, confidence: 0.9 }, { screenId: SET, confidence: 0.3 }],
  });
  assert.deepEqual(issues.map((i) => i.id), ["scenario:sc_0123456789abcdef01", "runs:stale-door", "needs-remap", "stale-doors", "uncertain-rooms"]);
  assert.match(issues[1].title, /2 failed runs: /);
  assert.deepEqual(issues[1].action, { kind: "run", runId: "ai-run-1", label: "Open the latest" });
  assert.deepEqual(issues[3].action.rooms, [HOME, MENU]);
  assert.deepEqual(issues[4].action.rooms, [SET]);
  assert.deepEqual(appIssues({ placeId: GM, versions: null, scenarios: null, runs: [], rooms: [] }), []);
});

test("Issues tab: lists each issue with its fix and routes to it", async () => {
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/issues`).tab, "issues");
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: [], truncated: false }),
    "GET /v1/devices/d1/apps/versions": () => ({ ...VERSIONS, placeId: GM }),
    "GET /v1/devices/d1/apps/scenarios": () => ({ ...SCENARIOS, placeId: GM, scenarios: SCENARIOS.scenarios.map((s) => ({ ...s, startScreenId: s.route[0], endScreenId: s.route.at(-1), steps: s.route.length - 1, danger: false, lastVerifiedAt: null, appVersion: null })) }),
    "GET /v1/devices/d1/runs": () => ({ runs: RUNS }),
  });
  const navigated = [];
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.17.dev1" })];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "x", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} };
  const page = createAppKnowledgePage(ctx, { name: "app", placeId: GM, tab: "issues" }, { fetch: gateway.fetch });
  await flush();
  await flush();
  const text = page.element.textContent;
  assert.match(text, /Reach Settings is failing/);
  assert.match(text, /The app was updated since it was mapped/);
  assert.equal(page.element.querySelector(".tab.active").textContent, "Issues");
  page.element.querySelector('[data-issue-id="runs:stale-door"] .btn').click();
  assert.deepEqual(navigated.at(-1), { name: "run", runId: "ai-run-1" });
  page.element.querySelector('[data-issue-id="needs-remap"] .btn').click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: GM, tab: "versions" });
  page.destroy();
});
