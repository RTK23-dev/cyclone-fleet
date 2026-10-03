import test from "node:test";
import assert from "node:assert/strict";
import { deriveZones, zoneSubModel, coverageReport, runsThroughDoor, laneOf, zoneGlyph, MAX_ZONES } from "../.test-dist/maps/zones.js";

const S = (id, label, purpose, confidence = 0.9, danger = false) => ({
  screenId: `screen:${purpose}:${id.padEnd(16, "0")}`, label, purpose, confidence, risk: { danger, classes: danger ? ["unknown"] : [] },
  factSlots: [], lastObservedAt: null, lastVerifiedAt: null, layout: { x: 0, y: 0 }, landmarks: [], tone: "mapped", region: "Other",
});
const home = S("a1", "Home", "home"), search = S("a2", "Search", "search"), reels = S("a3", "Reels", "feed"),
  dms = S("a4", "Direct messages", "inbox"), thread = S("a5", "Thread", "thread"), requests = S("a6", "Message requests", "list", 0.4),
  profile = S("a7", "Profile", "account"), settings = S("a8", "Settings", "settings"), privacy = S("a9", "Privacy", "settings", 0.8, true),
  login = S("b1", "Log in", "login"), welcome = S("b2", "Welcome", "onboarding"), orphan = S("c1", "Story viewer", "detail");
let n = 0;
const E = (from, to, confidence = 0.9, danger = false) => ({ edgeId: `edge:${++n}`, fromScreenId: from.screenId, toScreenId: to.screenId, actionHint: to.label, risk: { danger, classes: [] }, confidence, lastVerifiedAt: null });
const model = {
  screens: [home, search, reels, dms, thread, requests, profile, settings, privacy, login, welcome, orphan],
  edges: [E(home, search), E(home, reels), E(home, dms), E(home, profile), E(dms, thread), E(dms, requests, 0.3), E(profile, settings),
    E(settings, privacy, 0.9, true), E(search, profile), E(welcome, login), E(login, home), E(thread, home)],
};

test("places sort into scenario lanes, zones grow from the entry's base pages, and the rest is Elsewhere", () => {
  const map = deriveZones(model);
  assert.equal(map.entryScreenId, home.screenId);
  assert.deepEqual(map.lanes.map((l) => [l.id, l.screenIds.length]), [["signed-out", 1], ["sign-in", 1], ["signed-in", 10]]);
  assert.deepEqual(map.zones.map((z) => z.name), ["Home", "Direct messages", "Profile", "Reels", "Search", "Elsewhere"]);
  const zone = (name) => map.zones.find((z) => z.name === name);
  assert.deepEqual(zone("Direct messages").screenIds, [dms.screenId, requests.screenId, thread.screenId]);
  assert.deepEqual(zone("Profile").screenIds, [profile.screenId, privacy.screenId, settings.screenId]);
  assert.equal(zone("Profile").blocked, 2, "the dangerous place and door are counted, not hidden");
  assert.equal(zone("Direct messages").unconfirmed, 2, "a low-confidence place and door");
  assert.deepEqual(zone("Elsewhere").screenIds, [orphan.screenId]);
  assert.equal(zone("Home").glyph, "home");
  assert.equal(zone("Direct messages").glyph, "chat");
  assert.ok(map.links.some((l) => l.from === "zone:" + search.screenId && l.to === "zone:" + profile.screenId && l.doors === 1));
});

test("the scenarios' entry wins, and a map without a signed-in place has no zones but keeps its lanes", () => {
  assert.equal(deriveZones(model, dms.screenId).entryScreenId, dms.screenId);
  const authOnly = deriveZones({ screens: [welcome, login], edges: [E(welcome, login)] });
  assert.equal(authOnly.entryScreenId, null);
  assert.equal(authOnly.zones.length, 0);
  assert.equal(authOnly.lanes.filter((l) => l.screenIds.length).length, 2);
  assert.equal(laneOf({ purpose: "signup", label: "" }), "sign-in");
  assert.equal(laneOf({ purpose: "detail", label: "Get started" }), "signed-out");
  assert.equal(zoneGlyph("Notifications"), "bell");
});

test("a zone view holds only that zone's places and the doors between them", () => {
  const map = deriveZones(model);
  const sub = zoneSubModel(model, map, "zone:" + dms.screenId);
  assert.deepEqual(sub.screens.map((s) => s.label).sort(), ["Direct messages", "Message requests", "Thread"]);
  assert.equal(sub.edges.length, 2);
});

test("too many base pages fold the smallest into the entry's zone instead of hiding them", () => {
  const hub = S("d0", "Home", "home");
  const tabs = Array.from({ length: MAX_ZONES + 3 }, (_, i) => S(`d${i + 1}`, `Tab ${String.fromCharCode(65 + i)}`, "list"));
  const map = deriveZones({ screens: [hub, ...tabs], edges: tabs.map((t) => E(hub, t)) });
  assert.equal(map.zones.length, MAX_ZONES);
  assert.equal(map.zones.reduce((sum, z) => sum + z.screenIds.length, 0), tabs.length + 1);
});

test("coverage reports confidence, unconfirmed, blocked and version freshness, never a share of the app", () => {
  const report = coverageReport(model, deriveZones(model), { staleDoorCount: 3 });
  assert.equal(report.scenariosKnown, 3);
  assert.equal(report.places, 12);
  assert.equal(report.doors, 12);
  assert.equal(report.unconfirmed, 2);
  assert.equal(report.blocked, 2);
  assert.equal(report.staleDoors, 3);
  assert.equal(report.currentShare, 9 / 12);
  assert.ok(report.confidence > 0.8 && report.confidence < 0.9);
  assert.equal(coverageReport(model, deriveZones(model)).currentShare, null);
});

test("runs through a door are the ones whose route took it", () => {
  const runs = [{ runId: "a", places: [{ route: [home.screenId, dms.screenId, thread.screenId] }] }, { runId: "b", places: [{ route: [dms.screenId, home.screenId] }] }];
  assert.deepEqual(runsThroughDoor(runs, dms.screenId, thread.screenId).map((r) => r.runId), ["a"]);
  assert.deepEqual(runsThroughDoor(runs, thread.screenId, dms.screenId), []);
});

test("a zone view lays its places out in columns by distance from the base page", async () => {
  const { layeredLayout, layoutCollapsed } = await import("../.test-dist/maps/zones.js");
  const map = deriveZones(model);
  const sub = layeredLayout(zoneSubModel(model, map, "zone:" + dms.screenId), dms.screenId);
  const at = (s) => sub.screens.find((x) => x.screenId === s.screenId).layout;
  assert.equal(at(dms).x, 0);
  assert.equal(at(thread).x, 250);
  assert.equal(at(requests).x, 250);
  assert.notEqual(at(thread).y, at(requests).y);
  assert.equal(layoutCollapsed(model), true);
  assert.equal(layoutCollapsed(sub), false);
});

test("a settings page about passwords stays signed in; only auth purposes (or a vague purpose with an auth label) change lane", () => {
  assert.equal(laneOf({ purpose: "settings", label: "Password and security" }), "signed-in");
  assert.equal(laneOf({ purpose: "login", label: "Welcome back" }), "sign-in");
  assert.equal(laneOf({ purpose: "unknown", label: "Sign up for Instagram" }), "sign-in");
  assert.equal(laneOf({ purpose: "list", label: "Welcome to your feed" }), "signed-in");
});
