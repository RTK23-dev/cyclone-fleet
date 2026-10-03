import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createAppPage } from "../.test-dist/pages/appPage.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";

const PLACE = "package:com.example.clock";
const ROOM = (n) => `screen:list:${String(n).padStart(16, "a")}`;
const CURSOR = (n) => `c1:0123456789abcdef0123:${n}`;

function screen(id, index) {
  return {
    screenId: id,
    label: `Room ${index + 1}`,
    purpose: index === 0 ? "home" : "settings",
    factSlots: [],
    risk: { danger: false, classes: [] },
    confidence: 0.8,
    lastObservedAt: null,
    lastVerifiedAt: null,
    layout: { x: index * 260, y: 0 },
  };
}

function atlasDoc(persona, rooms, placeId = PLACE) {
  const place = placeId.startsWith("chrome:")
    ? { placeId, kind: "chrome-origin", label: "example.com", origin: placeId.slice(7) }
    : { placeId, kind: "package", label: "Clock", packageName: "com.example.clock" };
  return {
    place,
    persona,
    mapStatus: rooms.length ? "partial" : "unmapped",
    screens: rooms.map(screen),
    edges: rooms.slice(1).map((id, i) => ({
      edgeId: `door:${i}`,
      fromScreenId: rooms[0],
      toScreenId: id,
      actionHint: `Open ${id.slice(-4)}`,
      risk: { danger: false, classes: [] },
      confidence: 0.9,
      lastVerifiedAt: null,
    })),
    capabilities: [],
    confidence: 0.8,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

const CLOCK_APP = {
  placeId: PLACE,
  kind: "package",
  label: "Clock",
  packageName: "com.example.clock",
  origin: null,
  installed: true,
  installedVersion: { versionName: "8.1", versionCode: 81 },
  mapStatus: "partial",
  rooms: 1,
  doors: 0,
  lastVerifiedAt: null,
  needsRemap: false,
  personas: [{ persona: "mapping", mapStatus: "partial", rooms: 1, doors: 0, lastVerifiedAt: null }],
  mappedVersions: [{ versionName: "8.1", versionCode: 81, doors: 0 }],
};

function fakePhone({ rooms = [ROOM(1)], app = CLOCK_APP } = {}) {
  const phone = { rooms: [...rooms], job: null, cursor: rooms.length };
  const job = (state) => ({
    mappingJobId: "job-12345678",
    placeId: PLACE,
    state,
    currentAtlasNodeId: phone.rooms.at(-1) ?? null,
    progress: { newScreens: phone.rooms.length, verifiedMutations: 0 },
    failureCode: null,
    atlasStatus: "partial",
  });
  phone.gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: app ? [app] : [], truncated: false }),
    "GET /v1/devices/d1/atlas": ({ query }) => atlasDoc(query.persona, query.persona === "mapping" ? phone.rooms : [], query.placeId),
    "GET /v1/devices/d1/atlas/diff": ({ query }) => {
      const from = query.since ? Number(query.since.split(":")[2]) : phone.cursor;
      return {
        cursor: CURSOR(phone.rooms.length),
        resyncRequired: false,
        changes: phone.rooms.slice(from).map((id, i) => ({ cursor: CURSOR(from + i + 1), entity: "screen", change: "upsert", id })),
      };
    },
    "POST /v1/devices/d1/mapping/start": ({ body }) => {
      phone.started = body;
      assert.equal(body.placeId, PLACE);
      assert.equal(body.persona, "mapping");
      assert.equal(body.sessionId, "default-foreground");
      phone.job = "running";
      return job("running");
    },
    "POST /v1/devices/d1/mapping/status": () => (phone.job ? job(phone.job) : { mappingJobId: null, placeId: null, state: "idle", currentAtlasNodeId: null, progress: {}, failureCode: null, atlasStatus: null }),
    "POST /v1/devices/d1/mapping/stop": () => {
      phone.job = "stopped";
      return job("stopped");
    },
  });
  phone.walk = (n, state = "running") => {
    phone.rooms.push(ROOM(n));
    phone.job = state;
  };
  return phone;
}

function manualTimer() {
  const pending = [];
  return {
    setTimer: (fn) => pending.push(fn),
    clearTimer: () => (pending.length = 0),
    async fire() {
      for (const fn of pending.splice(0)) fn();
      await flush();
    },
  };
}

function open(phone, timer = manualTimer(), placeId = PLACE, extra = {}) {
  installMiniDom();
  const client = new GatewayClient({ token: "t", fetch: phone.gateway.fetch });
  const devices = [parseDevice(READY_DEVICE)];
  const ctx = { client, version: "1.0.0-alpha.1", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
  const page = createAppPage(ctx, { name: "app", placeId, tab: "map", ...extra }, { fetch: phone.gateway.fetch, setTimer: timer.setTimer, clearTimer: timer.clearTimer });
  return { page, timer };
}

function showAll(page) {
  const all = page.element.querySelectorAll(".rail-item").find((b) => /All places/.test(b.textContent));
  all.click();
}

test("app header shows phone facts; the board shows the phone's rooms and doors", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2)] });
  const { page } = open(phone);
  await flush();
  const text = page.element.textContent;
  assert.match(page.element.querySelector(".page-title").textContent, /Clock/);
  assert.match(text, /Installed 8\.1/);
  assert.match(text, /Mapped on 8\.1/);
  assert.equal(page.element.querySelectorAll(".zo-zone").length, 2, "the overview shows the entry zone and one base page");
  assert.match(page.element.querySelector(".atlas-crumb").textContent, /Clock›Overview/);
  showAll(page);
  assert.equal(page.element.querySelectorAll(".map-card").length, 2);
  assert.match(page.element.querySelector(".board-coverage").textContent, /2 places · 1 door/);
  assert.match(page.element.querySelector(".inspector").textContent, /Click a room/);
  assert.equal(page.element.querySelector(".mapping-controls .btn").textContent.trim(), "Map again");
  page.destroy();
});

test("clicking a room opens the inspector with its doors", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2)] });
  const { page } = open(phone);
  await flush();
  showAll(page);
  page.element.querySelectorAll(".map-card")[0].click();
  const inspector = page.element.querySelector(".inspector").textContent;
  assert.match(inspector, /Room/);
  assert.match(inspector, /Doors from this place/);
  assert.match(inspector, /Open/);
  page.destroy();
});

test("Start mapping follows the phone: cursor, new rooms from atlas.diff, then Stop", async () => {
  const phone = fakePhone({ rooms: [] });
  const { page, timer } = open(phone);
  await flush();
  assert.match(page.element.textContent, /Not mapped yet/);
  const start = page.element.querySelector(".mapping-controls .btn");
  assert.match(start.textContent, /Start mapping/);
  phone.rooms.push(ROOM(1));
  start.click();
  page.element.querySelector(".mission-start").click();
  await flush();
  assert.equal(page.element.querySelector(".sheet-overlay"), null, "the sheet closes once the pass starts");
  assert.match(page.element.querySelector(".mission-panel").textContent, /Mapping Clock/);
  assert.match(page.element.querySelector(".mapping-status").textContent, /Mapping on the phone/);
  assert.match(page.element.querySelector(".mapping-controls").textContent, /Pause/);

  phone.walk(2);
  await timer.fire();
  await flush();
  assert.equal(page.element.querySelectorAll(".zo-zone.active").length, 1, "the zone the phone is in pulses on the overview");
  showAll(page);
  assert.equal(page.element.querySelectorAll(".map-card").length, 2);
  assert.equal(page.element.querySelectorAll(".map-card.mapping-cursor").length, 1);

  const stop = page.element.querySelectorAll(".mapping-controls .btn").find((b) => /Stop/.test(b.textContent));
  stop.click();
  await flush();
  assert.match(page.element.querySelector(".mapping-status").textContent, /Mapping stopped/);
  assert.match(page.element.querySelector(".mapping-controls").textContent, /Map again/);
  const report = page.element.querySelector(".mission-panel.report").textContent;
  assert.match(report, /Mapping report/);
  assert.match(report, /You stopped the pass/);
  assert.equal(page.element.querySelectorAll(".map-card.mapping-cursor").length, 0);
  page.destroy();
});

test("mapping errors are explained, not swallowed", async () => {
  const busy = fakePhone({ rooms: [] });
  const original = busy.gateway.fetch;
  busy.gateway.fetch = async (input, init = {}) =>
    String(input).includes("/mapping/start")
      ? json({ detail: { code: "HUMAN_HAS_CONTROL", message: "human" } }, 409)
      : original(input, init);
  const { page } = open(busy);
  await flush();
  page.element.querySelector(".mapping-controls .btn").click();
  page.element.querySelector(".mission-start").click();
  await flush();
  assert.match(page.element.querySelector(".mapping-status").textContent, /You have control of the phone/);
  page.destroy();
});

test("web places show the board but cannot start mapping yet", async () => {
  const web = { ...CLOCK_APP, placeId: "chrome:https://example.com", kind: "chrome-origin", label: "example.com", packageName: null, origin: "https://example.com", installed: null, installedVersion: null };
  const phone = fakePhone({ app: web });
  const { page } = open(phone, manualTimer(), "chrome:https://example.com");
  await flush();
  showAll(page);
  assert.equal(page.element.querySelectorAll(".map-card").length, 1, "the web place's rooms still render");
  const start = page.element.querySelector(".mapping-controls .btn");
  assert.equal(start.disabled, true);
  assert.match(start.title, /Websites come later/);
  page.destroy();
});

test("a run's route lights up its rooms in order, with the doors between them", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2), ROOM(3)] });
  const { page } = open(phone, manualTimer(), PLACE, { route: [ROOM(1), ROOM(3)], runId: "ai-run-1" });
  await flush();
  const onRoute = page.element.querySelectorAll(".map-card.on-route");
  assert.equal(onRoute.length, 2);
  assert.deepEqual(onRoute.map((card) => card.querySelector(".route-badge").textContent), ["1", "2"]);
  assert.equal(page.element.querySelectorAll(".map-edge.on-route").length, 1, "only the door ROOM(1) → ROOM(3) is on the route");
  const banner = page.element.querySelector(".route-banner").textContent;
  assert.match(banner, /2 rooms/);
  assert.match(banner, /Back to the run/);
  page.destroy();
});

test("Start mapping asks whose account and how long, and sends exactly that to the phone", async () => {
  const phone = fakePhone({ rooms: [] });
  const { page } = open(phone);
  await flush();
  page.element.querySelector(".mapping-controls .btn").click();
  const sheet = page.element.querySelector(".mission-sheet");
  assert.ok(sheet, "the start sheet opens");
  assert.match(sheet.textContent, /My account — look only/);
  assert.match(sheet.textContent, /Test account/);
  assert.match(sheet.textContent, /10 min.*30 min.*2 h/s);
  assert.match(sheet.textContent, /Never sends, posts, pays, deletes, or changes settings or security/);
  page.element.querySelectorAll(".choice-card").find((b) => b.dataset.identity === "test").click();
  page.element.querySelectorAll(".budget-chip").find((b) => b.dataset.budget === "30m").click();
  assert.match(page.element.querySelector(".mission-sheet").textContent, /Secrets Card/);
  page.element.querySelector(".mission-start").click();
  await flush();
  assert.equal(phone.started.identity, "test");
  assert.equal(phone.started.budget.maxElapsedMs, 1_800_000);
  assert.equal(phone.started.budget.maxNewScreens, 200);
  page.destroy();
});

test("cancelling the sheet starts nothing", async () => {
  const phone = fakePhone({ rooms: [] });
  const { page } = open(phone);
  await flush();
  page.element.querySelector(".mapping-controls .btn").click();
  page.element.querySelectorAll(".sheet-actions .btn").find((b) => /Cancel/.test(b.textContent)).click();
  await flush();
  assert.equal(page.element.querySelector(".sheet-overlay"), null);
  assert.equal(phone.started, undefined);
  page.destroy();
});

test("Teach on the phone starts Follow Me, Done stops it and shows Your teaching", async () => {
  const phone = fakePhone({ rooms: [ROOM(1)] });
  const calls = [];
  const base = phone.gateway.fetch;
  phone.gateway.fetch = async (input, init = {}) => {
    const path = new URL(String(input), "http://127.0.0.1:8765").pathname;
    if (path.endsWith("/agent/teach/start") || path.endsWith("/agent/teach/stop")) {
      calls.push({ path, body: JSON.parse(init.body) });
      return json(path.endsWith("stop") ? { teaching: { summary: "Clock → Alarms" } } : { teaching: { active: true } });
    }
    return base(input, init);
  };
  const { page } = open(phone);
  await flush();
  const teachButton = () => [...page.element.querySelectorAll(".mapping-controls .btn")].find((b) => /Teach on the phone|Done teaching/.test(b.textContent));
  teachButton().click();
  await flush();
  assert.match(calls[0].path, /teach\/start$/);
  assert.match(calls[0].body.goal, /Teach a route in/);
  assert.match(page.element.querySelector(".mapping-status").textContent, /Follow Me is on/);
  assert.match(teachButton().textContent, /Done teaching/);
  teachButton().click();
  await flush();
  assert.deepEqual(calls[1].body, { compile_for_review: true });
  assert.match(page.element.querySelector(".mapping-status").textContent, /Taught: Clock → Alarms/);
  assert.equal(page.element.querySelector(".segment.active").textContent, "Your teaching");
  page.destroy();
});

test("a mapping pass that could not read the screen says why in plain words", async () => {
  const { failureHint, reportReason } = await import("../.test-dist/ui/missionPanel.js");
  const job = { state: "failed", failureCode: "OBSERVATION_MISSING_SCREEN_KEPT_CHANGING" };
  const reason = reportReason(job);
  assert.match(reason.text, /\(OBSERVATION_MISSING_SCREEN_KEPT_CHANGING\)/);
  assert.match(reason.text, /screen kept moving/);
  assert.match(reason.text, /Nothing was changed on the phone\.$/);
  assert.match(failureHint("AFTER_OBSERVATION_MISSING_ACCESSIBILITY_OFF"), /accessibility service is off/);
  assert.match(failureHint("OBSERVATION_MISSING"), /could not read the phone's screen/);
  assert.equal(failureHint("DOOR_UNSAFE"), "");
  assert.equal(failureHint(null), "");
});
