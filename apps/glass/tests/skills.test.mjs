import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createAppKnowledgePage } from "../.test-dist/pages/appKnowledgePage.js";
import { createAppPage } from "../.test-dist/pages/appPage.js";
import { createAppsPage } from "../.test-dist/pages/appsPage.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { parseSkills, skillsByApp, skillsThrough } from "../.test-dist/services/skills.js";

const CLOCK = "package:com.google.android.deskclock";
const ALARM = "page:bWluZC1hbGFybQ";
const TIMER = "page:bWluZC10aW1lcg";
const TIMER_SKILL = {
  skillId: "you.0123456789ab", name: "Set a 10 minute timer", placeId: CLOCK, ground: "grounded",
  detail: "1 known move to “Timer”.", routeMoves: 1,
  route: [{ title: "Alarm", screenId: ALARM }, { title: "Timer", screenId: TIMER }], finishSteps: 4, savedAt: Date.now() - 3_600_000,
};
const RECHECK = { ...TIMER_SKILL, skillId: "you.aaaaaaaaaaaa", name: "Start the stopwatch", ground: "needs-recheck", detail: "“Stopwatch” is no longer on the map.", routeMoves: null, route: [{ title: "Stopwatch", screenId: null }] };
const LEGACY = { ...TIMER_SKILL, skillId: "you.bbbbbbbbbbbb", name: "Old skill", placeId: null, ground: "not-grounded", route: [], routeMoves: null, finishSteps: 0, savedAt: null };
const SKILLS = { skills: [TIMER_SKILL, RECHECK, LEGACY], truncated: false };

function context(fetch, navigated = []) {
  const devices = [parseDevice(READY_DEVICE)];
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.22", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} };
}

test("skills parse defensively; per-app counts and the skills through a place", () => {
  const list = parseSkills({ skills: [TIMER_SKILL, { ...TIMER_SKILL, skillId: "stock.x" }, { ...RECHECK, ground: "great", route: [{ title: "x", screenId: "../etc" }] }, null], truncated: false });
  assert.deepEqual(list.skills.map((s) => s.skillId), ["you.0123456789ab", "you.aaaaaaaaaaaa"]);
  assert.equal(list.skills[1].ground, "not-grounded", "an unknown state is never shown as healthy");
  assert.equal(list.skills[1].route[0].screenId, null);
  assert.deepEqual([...skillsByApp(parseSkills(SKILLS).skills)], [[CLOCK, { count: 2, recheck: 1 }]]);
  assert.deepEqual(skillsThrough(parseSkills(SKILLS).skills, TIMER).map((s) => s.name), ["Set a 10 minute timer"]);
});

test("a skill's way on the map round-trips through the route, learned screen ids included", () => {
  const href = routeHref({ name: "app", placeId: CLOCK, tab: "map", route: [ALARM, TIMER], skill: TIMER_SKILL.skillId });
  const back = parseRoute(href);
  assert.deepEqual(back.route, [ALARM, TIMER]);
  assert.equal(back.skill, TIMER_SKILL.skillId);
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(CLOCK)}/skills`).tab, "skills");
  assert.equal(parseRoute(`#/apps/x/map?skill=../x`).skill, undefined);
});

test("Skills tab: each skill with its health, its saved way and where it works; show it on the map or run it", async () => {
  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: [], truncated: false }),
    "GET /v1/devices/d1/skills": () => SKILLS,
    "POST /v1/devices/d1/market/you.0123456789ab/run": () => ({ ok: true }),
  });
  const page = createAppKnowledgePage(context(gateway.fetch, navigated), { name: "app", placeId: CLOCK, tab: "skills" }, { fetch: gateway.fetch });
  await flush();
  const cards = page.element.querySelectorAll(".skill-card");
  assert.equal(cards.length, 2, "only this app's skills");
  assert.match(page.element.querySelector(".stats").textContent, /Needs re-check/);
  const timer = cards.find((c) => c.dataset.skillId === TIMER_SKILL.skillId);
  assert.match(timer.textContent, /Route known/);
  assert.match(timer.textContent, /Alarm.*Timer/);
  assert.match(timer.querySelector(".skill-stop.destination").textContent, /Timer/);
  assert.match(timer.textContent, /About 4 steps of work at the destination/);
  timer.querySelectorAll(".btn").find((b) => /Show on the map/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: CLOCK, tab: "map", route: [ALARM, TIMER], skill: TIMER_SKILL.skillId });
  timer.querySelectorAll(".btn").find((b) => /Run on the phone/.test(b.textContent)).click();
  await flush();
  assert.ok(gateway.calls.some((c) => c.method === "POST" && c.path === "/v1/devices/d1/market/you.0123456789ab/run"));
  assert.match(timer.textContent, /Started on the phone/);
  const recheck = cards.find((c) => c.dataset.skillId === RECHECK.skillId);
  assert.equal(recheck.querySelectorAll(".btn").filter((b) => /Show on the map/.test(b.textContent)).length, 0, "no known screens, nothing to draw");
  page.destroy();
});

test("the map draws a skill's way on the Taught map and a place lists the skills through it", async () => {
  installMiniDom();
  const screen = (id, label) => ({ screenId: id, label, purpose: "screen", factSlots: [], risk: { danger: false, classes: [] }, confidence: 0.8, lastObservedAt: null, lastVerifiedAt: null, layout: { x: 0, y: 0 } });
  const gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: [], truncated: false }),
    "GET /v1/devices/d1/atlas": ({ query }) => ({
      place: { placeId: CLOCK, kind: "package", label: "Clock", packageName: "com.google.android.deskclock" }, persona: query.persona,
      mapStatus: "partial", screens: query.persona === "live" ? [screen(ALARM, "Alarm"), screen(TIMER, "Timer")] : [],
      edges: query.persona === "live" ? [{ edgeId: "door:1", fromScreenId: ALARM, toScreenId: TIMER, actionHint: "Timer", risk: { danger: false, classes: [] }, confidence: 0.9, lastVerifiedAt: null }] : [],
      capabilities: [], confidence: 0.8, lastObservedAt: null, lastVerifiedAt: null,
    }),
    "GET /v1/devices/d1/atlas/diff": () => ({ cursor: "c1:0123456789abcdef0123:0", resyncRequired: false, changes: [] }),
    "POST /v1/devices/d1/mapping/status": () => ({ mappingJobId: null, placeId: null, state: "idle", currentAtlasNodeId: null, progress: {}, failureCode: null, atlasStatus: null }),
    "GET /v1/devices/d1/skills": () => SKILLS,
    "GET /v1/devices/d1/apps/scenarios": () => ({ placeId: CLOCK, persona: "mapping", entryScreenId: null, scenarios: [] }),
  });
  const page = createAppPage(context(gateway.fetch), { name: "app", placeId: CLOCK, tab: "map", route: [ALARM, TIMER], skill: TIMER_SKILL.skillId },
    { fetch: gateway.fetch, setTimer: () => 0, clearTimer: () => {} });
  await flush();
  await flush();
  assert.ok(gateway.calls.some((c) => c.path === "/v1/devices/d1/atlas" && c.query.persona === "live"), "a skill's way is on the Taught map");
  assert.match(page.element.querySelector(".route-banner").textContent, /skill's saved way/);
  assert.match(page.element.querySelector(".route-banner").textContent, /Back to Skills/);
  const timerCard = page.element.querySelectorAll(".map-card").find((c) => c.querySelector(".map-card-title").textContent === "Timer");
  timerCard.click();
  const inspector = page.element.querySelector(".inspector").textContent;
  assert.match(inspector, /Skills through here \(1\)/);
  assert.match(inspector, /Set a 10 minute timer/);
  assert.match(inspector, /Works here/);
  page.destroy();
});

test("the fleet counts each app's skills and flags the ones to re-check", async () => {
  installMiniDom();
  const app = { placeId: CLOCK, kind: "package", label: "Clock", packageName: "com.google.android.deskclock", origin: null, installed: true, installedVersion: null, mapStatus: "mapped", rooms: 2, doors: 1, lastVerifiedAt: null, needsRemap: false, personas: [], mappedVersions: [] };
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [app], truncated: false }), "GET /v1/devices/d1/skills": () => SKILLS });
  const page = createAppsPage(context(gateway.fetch), { name: "apps" });
  await flush();
  await flush();
  assert.match(page.element.querySelector(".fleet-skills").textContent, /1 skill to re-check/);
  page.destroy();
});
