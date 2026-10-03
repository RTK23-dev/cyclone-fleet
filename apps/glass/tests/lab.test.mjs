import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { categoryTone, cleanVariant, estimate, rateText } from "../.test-dist/services/lab.js";
import { createLabPage } from "../.test-dist/pages/labPage.js";

const MISSION = (id, suites, extra = {}) => ({ id, title: id, goal: `Do ${id}`, category: "settings", suites, apps: [], expect: "done", minutes: 6, notes: "", checks: [], setup: [], owner: {}, ...extra });
const CATALOG = {
  missions: [MISSION("settings.rotate.on", ["smoke", "core"]), MISSION("clock.timer.5", ["core"]), MISSION("boundary.delete.file", ["smoke", "core"], { expect: "boundary" })],
  suites: ["core", "smoke"],
  problems: [],
  customDir: "C:/lab/missions",
};
const EXP = { id: "exp-20260925-120000-abcd", name: "marks on vs off", deviceId: "d1", createdAt: Date.now(), finishedAt: null, missions: ["settings.rotate.on"],
  variants: [{ name: "A" }, { name: "B", marks: false }], repetitions: 2, status: "running", reason: null, total: 4, done: 1,
  current: { index: 1, missionId: "settings.rotate.on", variant: "B", phase: "working · 3 turns", turns: 3, costUsd: 0.004 }, appVersion: "5.0.0-alpha.32.dev1", gatewayVersion: "2.9.5" };
const ARM = (rate) => ({ runs: 2, scored: 2, passes: Math.round(rate * 2), rate, ci95: [0.1, 0.9], infra: 0, falseSuccess: rate < 1 ? 1 : 0, safetyFailures: 0,
  durationSec: { median: 42, mean: 42, total: 84 }, turns: { median: 6, mean: 6, total: 12 }, actions: { median: 4, mean: 4, total: 8 },
  errors: { median: 0, mean: 0, total: 0 }, costUsd: { median: 0.01, mean: 0.01, total: 0.02 }, ownerAsks: 0, categories: {}, causes: rate < 1 ? { "repeated the same action": 1 } : {}, tools: {} });
const DETAIL = {
  experiment: EXP,
  arms: { A: ARM(1), B: ARM(0.5) },
  comparisons: [{ a: "A", b: "B", rateA: 1, rateB: 0.5, delta: -0.5, pValue: 1, missionsBetterA: ["settings.rotate.on"], missionsBetterB: [], missionsSame: [], costRatio: 1, timeRatio: 1,
    conclusion: "No significant difference yet (-50 points, p = 1.00); about 100 runs per arm would show a 10-point difference." }],
  matrix: [{ missionId: "settings.rotate.on", cells: { A: { passes: 2, scored: 2, infra: 0, categories: {} }, B: { passes: 1, scored: 2, infra: 0, categories: { false_success: 1 } } } }],
  insights: ["Honesty: 1 of 4 runs said done while the phone disagreed."],
  trials: [
    { trialId: "t1", missionId: "settings.rotate.on", variant: "A", rep: 0, verdict: "pass", category: "pass", cause: "", signals: [], checks: [], durationMs: 1, owner: [], error: null, phone: null },
    { trialId: "t2", missionId: "settings.rotate.on", variant: "B", rep: 0, verdict: "fail", category: "false_success", cause: "repeated the same action", signals: ["loop"],
      checks: [{ check: "setting", ok: false, detail: "accelerometer_rotation = 0" }], durationMs: 1, owner: [], error: null,
      phone: { summary: "Auto-rotate is on", traceId: "ai-run-9", metrics: { errorTail: ["tap: not found"] } } },
  ],
};

function ctx(fetch, navigate = () => {}) {
  const devices = [parseDevice(READY_DEVICE)];
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.16", devices, device: devices[0], devicesError: null, navigate, selectDevice() {}, refreshDevices: async () => {} };
}

const noTimers = { setTimer: () => 1, clearTimer: () => {} };

test("lab routes round-trip, reject junk ids and belong to the Lab section", () => {
  assert.deepEqual(parseRoute("#/lab"), { name: "lab" });
  assert.deepEqual(parseRoute(routeHref({ name: "lab", experimentId: EXP.id })), { name: "lab", experimentId: EXP.id });
  assert.deepEqual(parseRoute("#/lab/..%2Fetc"), { name: "lab" });
  assert.equal(sectionOf({ name: "lab" }), "lab");
});

test("variants send only what was filled in; helpers read the gateway's numbers", () => {
  assert.deepEqual(cleanVariant({ name: " A ", modelId: " ", effort: null, workingMinutes: null, marks: true, freshMemory: true, promptAddendum: "" }), { name: "A" });
  assert.deepEqual(cleanVariant({ name: "B", modelId: "vendor/model", effort: "low", workingMinutes: 9.6, marks: false, freshMemory: false, promptAddendum: " Prefer links. " }),
    { name: "B", modelId: "vendor/model", effort: "low", workingMinutes: 10, marks: false, freshMemory: false, promptAddendum: "Prefer links." });
  assert.deepEqual(cleanVariant({ name: "map off", useMap: false }), { name: "map off", useMap: false });
  assert.deepEqual(cleanVariant({ name: "map on", useMap: true }), { name: "map on" });
  assert.deepEqual(cleanVariant({ name: "ws", context: "workspace" }), { name: "ws", context: "workspace" });
  assert.deepEqual(cleanVariant({ name: "odd", context: null }), { name: "odd" });
  assert.equal(rateText(0.8, [0.49, 0.94]), "80% (49–94)");
  assert.equal(rateText(null), "—");
  assert.equal(estimate(CATALOG.missions, 2, 3).runs, 18);
  assert.equal(categoryTone("missed_boundary"), "danger");
});

test("the builder starts from the smoke suite and starts an A/B experiment on the selected phone", async () => {
  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({
    "GET /v1/lab/missions": () => CATALOG,
    "GET /v1/lab/experiments": () => ({ experiments: [] }),
    "POST /v1/lab/experiments": ({ body }) => ({ ...EXP, ...body, id: EXP.id }),
  });
  const page = createLabPage(ctx(gateway.fetch, (route) => navigated.push(route)), { name: "lab" }, noTimers);
  await flush();
  const checked = page.element.querySelectorAll(".lab-check").filter((box) => box.checked).map((box) => box.dataset.mission);
  assert.deepEqual(checked, ["settings.rotate.on", "boundary.delete.file"]);
  assert.match(page.element.textContent, /2 missions × 1 variant × 1 = 2 runs/);
  assert.match(page.element.textContent, /No experiments yet/);

  page.element.querySelector(".lab-add-variant").click();
  const variants = page.element.querySelectorAll(".lab-variant");
  assert.equal(variants.length, 2);
  const toggles = variants[1].querySelectorAll("input").filter((input) => input.type === "checkbox");
  toggles[0].checked = false;
  toggles[0].dispatchEvent({ type: "change" });

  page.element.querySelectorAll("button").find((b) => b.textContent === "Start experiment").click();
  await flush();
  const post = gateway.calls.find((call) => call.method === "POST");
  assert.equal(post.body.deviceId, "d1");
  assert.deepEqual(post.body.missions, ["settings.rotate.on", "boundary.delete.file"]);
  assert.deepEqual(post.body.variants, [{ name: "A" }, { name: "B", marks: false }]);
  assert.deepEqual(navigated, [{ name: "lab", experimentId: EXP.id }]);
  page.destroy();
});

test("an experiment shows live progress, the A/B verdict, the mission matrix and the runs to look at", async () => {
  installMiniDom();
  const timers = [];
  const gateway = fakeGateway({
    [`GET /v1/lab/experiments/${EXP.id}`]: () => DETAIL,
    [`POST /v1/lab/experiments/${EXP.id}/stop`]: () => ({ ...EXP, status: "stopped" }),
  });
  const page = createLabPage(ctx(gateway.fetch), { name: "lab", experimentId: EXP.id }, { setTimer: (fn) => timers.push(fn), clearTimer: () => {} });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /Run 2 of 4: settings\.rotate\.on · variant B/);
  assert.match(text, /Honesty: 1 of 4/);
  assert.match(text, /No significant difference yet/);
  assert.match(text, /100%/);
  assert.match(text, /Success · 95%: 10%–90%/);
  assert.equal(page.element.querySelectorAll(".lab-failure-group").length, 1);
  assert.match(page.element.querySelector(".lab-failure-summary").textContent, /1 of 2 runs/);
  assert.equal(page.element.querySelector(".lab-cell-mixed").textContent, "1/2");
  assert.match(text, /Said done, wasn't/);
  assert.match(text, /accelerometer_rotation = 0/);
  assert.equal(page.element.querySelector(".lab-open-run").href, "#/runs/ai-run-9");
  assert.equal(timers.length, 1, "a running experiment keeps refreshing");

  page.element.querySelectorAll("button").find((b) => b.textContent === "Stop").click();
  await flush();
  assert.ok(gateway.calls.some((call) => call.method === "POST" && call.path.endsWith("/stop")));
  page.destroy();
});
