import test from "node:test";
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { createMappingWatcher, mappingStatusLine } from "../.test-dist/maps/mappingWatcher.js";
import { createMapsPage } from "../.test-dist/pages/mapsPage.js";

const PLACE = "package:com.example.clock";
const ROOM_A = "screen:unknown:aaaaaaaaaaaaaaaa";
const ROOM_B = "screen:list:bbbbbbbbbbbbbbbb";
const CURSOR = (n) => `c1:0123456789abcdef0123:${n}`;

function flush() {
  return delay(0);
}

function screen(screenId, x) {
  return {
    screenId,
    label: "Screen",
    purpose: "Screen",
    factSlots: [],
    risk: { danger: false, classes: [] },
    confidence: 0.7,
    lastObservedAt: null,
    lastVerifiedAt: null,
    layout: { x, y: 0 },
  };
}

function document(persona, screens) {
  return {
    place: { placeId: PLACE, kind: "package", label: "Clock", packageName: "com.example.clock" },
    persona,
    mapStatus: screens.length ? "partial" : "unmapped",
    screens,
    edges: [],
    capabilities: [],
    confidence: 0.7,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

/** A phone whose mapping atlas grows as the fake crawl advances. */
function fakePhone() {
  const phone = {
    rooms: [],
    job: null,
    cursor: 0,
    calls: [],
    loads: 0,
    source() {
      phone.loads += 1;
      const docs = {
        live: document("live", [screen("page:live-home", 0)]),
        mapping: document("mapping", phone.rooms.map((id, index) => screen(id, index * 260))),
      };
      return {
        listSummaries(persona) {
          const doc = docs[persona];
          if (persona === "mapping" && doc.screens.length === 0) return [];
          return [{ place: doc.place, persona, mapStatus: doc.mapStatus, confidence: 0.7, lastObservedAt: null, lastVerifiedAt: null }];
        },
        getDocument(placeId, persona) {
          return placeId === PLACE ? structuredClone(docs[persona]) : null;
        },
      };
    },
    walk(roomId, state = "running") {
      phone.rooms.push(roomId);
      phone.cursor += 1;
      phone.job = { ...phone.job, state, currentAtlasNodeId: roomId, newScreens: phone.rooms.length };
    },
    ops: {
      async atlasDiff(placeId, persona, since) {
        phone.calls.push(["diff", since]);
        const from = since ? Number(since.split(":")[2]) : phone.cursor;
        const changes = phone.rooms.slice(from).map((id, index) => ({
          cursor: CURSOR(from + index + 1), entity: "screen", change: "upsert", id,
        }));
        return { cursor: CURSOR(phone.cursor), resyncRequired: false, changes };
      },
      async mappingStart(placeId) {
        phone.calls.push(["start", placeId]);
        phone.job = {
          mappingJobId: "map-0123456789abcdef", placeId, state: "running",
          currentAtlasNodeId: null, newScreens: 0, verifiedMutations: 0, failureCode: null, atlasStatus: null,
        };
        return { ...phone.job };
      },
      async mappingResume() {
        phone.job = { ...phone.job, state: "running" };
        return { ...phone.job };
      },
      async mappingPause() {
        phone.job = { ...phone.job, state: "paused" };
        return { ...phone.job };
      },
      async mappingStop() {
        phone.calls.push(["stop"]);
        phone.job = { ...phone.job, state: "stopped" };
        return { ...phone.job };
      },
      async mappingStatus(id) {
        phone.calls.push(["status", id ?? null]);
        if (!phone.job) {
          return { mappingJobId: null, placeId: null, state: "idle", currentAtlasNodeId: null, newScreens: 0, verifiedMutations: 0, failureCode: null, atlasStatus: null };
        }
        return { ...phone.job };
      },
    },
  };
  return phone;
}

function manualTimer() {
  const pending = [];
  return {
    set(fn) {
      pending.push(fn);
      return pending.length;
    },
    clear() {
      pending.length = 0;
    },
    async fire() {
      const fns = pending.splice(0);
      for (const fn of fns) fn();
      for (let i = 0; i < 6; i += 1) await flush();
    },
  };
}

async function openLivePage(phone, timer, extra = {}) {
  installMiniDom();
  const page = createMapsPage({
    phoneVersion: "5.0.0-alpha.3.dev1",
    loadSource: async () => phone.source(),
    sessionId: "default-foreground",
    sessionPlane: "foreground",
    mapping: phone.ops,
    mappingTimer: timer,
    ...extra,
  });
  for (let i = 0; i < 6; i += 1) await flush();
  return page;
}

test("Start mapping is enabled for a real V5 phone on the foreground plane", async () => {
  const phone = fakePhone();
  const page = await openLivePage(phone, manualTimer());
  const start = page.element.querySelector(".maps-start");
  assert.equal(start.disabled, false);
  assert.match(start.title, /Never pays, sends, deletes or grants/);
  page.destroy();
});

test("Start mapping stays disabled on a named workspace plane and says why", async () => {
  const phone = fakePhone();
  const page = await openLivePage(phone, manualTimer(), { sessionId: "vd-mail", sessionPlane: "session_kernel_vd" });
  const start = page.element.querySelector(".maps-start");
  assert.equal(start.disabled, true);
  assert.match(start.title, /main screen/);
  page.destroy();
});

test("start → cursor pulses on the walked room and new cards appear without re-fitting", async () => {
  const phone = fakePhone();
  const timer = manualTimer();
  const page = await openLivePage(phone, timer);

  page.element.querySelector(".maps-start").click();
  for (let i = 0; i < 6; i += 1) await flush();
  assert.deepEqual(phone.calls.find((call) => call[0] === "start"), ["start", PLACE]);
  // The cursor is taken before the phone can write.
  const startIndex = phone.calls.findIndex((call) => call[0] === "start");
  const firstDiff = phone.calls.findIndex((call) => call[0] === "diff");
  assert.ok(firstDiff >= 0 && firstDiff < startIndex);
  assert.match(page.element.querySelector(".maps-mapping-status").textContent, /Mapping on the phone/);
  assert.equal(page.element.querySelector(".maps-start").hidden, true);
  assert.equal(page.element.querySelector(".maps-stop").hidden, false);

  phone.walk(ROOM_A);
  const loadsBefore = phone.loads;
  await timer.fire();
  assert.ok(phone.loads > loadsBefore, "a diff with changes refreshes the board");
  assert.ok(page.element.querySelector(`[data-screen-id="${ROOM_A}"]`), "first room card appears");
  assert.ok(page.element.querySelector(`[data-screen-id="${ROOM_A}"]`).classList.contains("mapping-cursor"));

  phone.walk(ROOM_B);
  await timer.fire();
  assert.ok(page.element.querySelector(`[data-screen-id="${ROOM_B}"]`), "second room card appears");
  assert.ok(page.element.querySelector(`[data-screen-id="${ROOM_B}"]`).classList.contains("mapping-cursor"));
  assert.equal(page.element.querySelector(`[data-screen-id="${ROOM_A}"]`).classList.contains("mapping-cursor"), false);
  page.destroy();
});

test("needs-secret shows the phone waiting line, and Stop ends the pass", async () => {
  const phone = fakePhone();
  const timer = manualTimer();
  const page = await openLivePage(phone, timer);
  page.element.querySelector(".maps-start").click();
  for (let i = 0; i < 6; i += 1) await flush();

  phone.walk(ROOM_A, "needs-secret");
  await timer.fire();
  const status = page.element.querySelector(".maps-mapping-status");
  assert.equal(status.textContent, "Waiting for a password on the phone");
  assert.ok(status.classList.contains("needs-secret"));
  assert.doesNotMatch(page.element.textContent, /hunter2|password:/i);

  page.element.querySelector(".maps-stop").click();
  for (let i = 0; i < 6; i += 1) await flush();
  assert.ok(phone.calls.some((call) => call[0] === "stop"));
  assert.match(status.textContent, /Mapping stopped/);
  assert.equal(page.element.querySelector(".maps-start").hidden, false);
  page.destroy();
});

test("Glass attaches to a pass started on the phone", async () => {
  const phone = fakePhone();
  await phone.ops.mappingStart(PLACE);
  phone.walk(ROOM_A);
  const timer = manualTimer();
  const page = await openLivePage(phone, timer);
  assert.match(page.element.querySelector(".maps-mapping-status").textContent, /Mapping on the phone · 1 new room/);
  assert.equal(page.element.querySelector(".maps-stop").hidden, false);
  page.destroy();
});

test("watcher stops polling once the job is terminal", async () => {
  const phone = fakePhone();
  const timer = manualTimer();
  const jobs = [];
  const watcher = createMappingWatcher({
    ops: phone.ops,
    onJob: (job) => jobs.push(job.state),
    onAtlasChanged: () => undefined,
    onError: (error) => { throw error; },
    setTimer: timer.set,
    clearTimer: timer.clear,
  });
  await watcher.start(PLACE);
  phone.job = { ...phone.job, state: "completed", atlasStatus: "mapped" };
  await timer.fire();
  const polls = phone.calls.filter((call) => call[0] === "status").length;
  await timer.fire();
  assert.equal(phone.calls.filter((call) => call[0] === "status").length, polls);
  assert.equal(jobs.at(-1), "completed");
  assert.equal(mappingStatusLine(watcher.current()), "Mapping done · every reachable door walked");
  watcher.dispose();
});
