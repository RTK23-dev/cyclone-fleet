import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createPhonePage } from "../.test-dist/pages/phonePage.js";
import { mapPointerGesture } from "../.test-dist/core/coordinates.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";

const IDLE = { taskId: null, state: "idle", title: "", app: "", currentMilestone: null, milestones: [], supportingCopy: null, outcomeCopy: null };

function phone({ locked = false, askError = null } = {}) {
  const state = { owner: "AI", ask: { ...IDLE } };
  const gateway = fakeGateway({
    "POST /v1/devices/d1/control": ({ body }) => {
      if (locked && body.kind !== "yield_ai") return json({ detail: { code: "PHONE_LOCKED", message: "locked" } }, 409);
      if (body.kind === "take_human") state.owner = "HUMAN";
      if (body.kind === "yield_ai") state.owner = "AI";
      return { ok: true, status: "android-result", inputOwner: state.owner };
    },
    "POST /v1/devices/d1/ask/start": ({ body }) => {
      if (askError) return json({ detail: { code: askError, message: askError } }, 409);
      state.ask = { ...IDLE, taskId: "t1", state: "working", title: "Gmail → Facebook", app: "Facebook", milestones: [{ label: "Finding the dm of Louella", state: "active" }] };
      return { accepted: true, goal: body.goal };
    },
    "POST /v1/devices/d1/ask/status": () => state.ask,
    "GET /v1/devices/d1/runs": () => ({ runs: state.runs ?? [] }),
    "POST /v1/devices/d1/background/keep-on": () => ({ ok: true, state: "on", message: "Done. Turn on Start on boot in Shizuku once." }),
  });
  return { state, gateway };
}

function open(fake) {
  installMiniDom();
  const renderers = [];
  const timers = [];
  const client = new GatewayClient({ token: "tok", fetch: fake.gateway.fetch });
  const devices = [parseDevice(READY_DEVICE)];
  const ctx = { client, version: "1.0.0-alpha.1", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
  const page = createPhonePage(ctx, {
    origin: "http://127.0.0.1:8765",
    fetch: fake.gateway.fetch,
    rendererFactory: (input) => {
      const renderer = { input, stopped: false, start() {}, stop() { renderer.stopped = true; } };
      renderers.push(renderer);
      return renderer;
    },
    askTimer: { setTimer: (fn) => timers.push(fn), clearTimer: () => (timers.length = 0) },
    hereTimer: { setInterval: () => 1, clearInterval() {} },
  });
  return { page, renderers, timers, controls: () => fake.gateway.calls.filter((c) => c.path.endsWith("/control")).map((c) => c.body) };
}

test("watching uses the full-rate focus stream without taking control from Cyclone", async () => {
  const fake = phone();
  const { page, renderers, controls } = open(fake);
  await flush();
  assert.equal(renderers.length, 1);
  assert.equal(renderers[0].input.streamUrl, "ws://127.0.0.1:8765/v1/devices/d1/video?profile=focus");
  assert.deepEqual(renderers[0].input.streamProtocols, ["cyclone-v1", "cyclone-token.tok"]);
  assert.deepEqual(controls(), []);
  assert.match(page.element.textContent, /Cyclone has control/);
  assert.equal(page.element.querySelector(".live-view").classList.contains("interactive"), true);
  page.destroy();
  assert.equal(renderers[0].stopped, true);
});

test("Take control keeps the live stream running; taps reach the phone; Give back keeps watching", async () => {
  const fake = phone();
  const { page, renderers, controls } = open(fake);
  await flush();
  page.element.querySelector(".phone-controls .btn").click();
  await flush();
  assert.deepEqual(controls()[0], { kind: "take_human", sessionId: "default-foreground" });
  assert.match(page.element.textContent, /You have control/);
  assert.equal(renderers.at(-1).input.profile, "focus");
  assert.equal(renderers.length, 1, "input ownership does not restart the video connection");
  const view = page.element.querySelector(".live-view");
  renderers[0].input.callbacks.onState("LIVE");
  assert.equal(view.classList.contains("interactive"), true);

  const canvas = view.querySelector(".live-canvas");
  canvas.width = 1080;
  canvas.height = 2340;
  canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 108, height: 234 });
  view.dispatchEvent({ type: "pointerdown", button: 0, clientX: 54, clientY: 117, timeStamp: 0 });
  view.dispatchEvent({ type: "pointerup", button: 0, clientX: 54, clientY: 117, timeStamp: 50 });
  await flush();
  assert.deepEqual(controls()[1], { kind: "tap", x: 0.5, y: 0.5 });

  page.element.querySelector(".phone-controls .btn").click();
  await flush();
  assert.deepEqual(controls()[2], { kind: "yield_ai", sessionId: "default-foreground" });
  assert.equal(renderers.at(-1).input.profile, "focus");
  assert.equal(renderers.length, 1);
  assert.match(page.element.textContent, /Cyclone has control/);
  page.destroy();
});

test("first click on a live phone takes control and lands at that pixel", async () => {
  const fake = phone();
  const { page, renderers, controls } = open(fake);
  await flush();
  const view = page.element.querySelector(".live-view");
  const canvas = view.querySelector(".live-canvas");
  canvas.width = 1080;
  canvas.height = 2340;
  canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 108, height: 234 });
  view.dispatchEvent({ type: "pointerdown", button: 0, clientX: 27, clientY: 175.5, timeStamp: 0 });
  view.dispatchEvent({ type: "pointerup", button: 0, clientX: 27, clientY: 175.5, timeStamp: 50 });
  assert.deepEqual(controls(), [], "reconnecting video cannot receive a stale tap");
  renderers[0].input.callbacks.onState("LIVE");
  view.dispatchEvent({ type: "pointerdown", button: 0, clientX: 27, clientY: 175.5, timeStamp: 0 });
  view.dispatchEvent({ type: "pointerup", button: 0, clientX: 27, clientY: 175.5, timeStamp: 50 });
  await flush();
  await flush();
  assert.deepEqual(controls(), [
    { kind: "take_human", sessionId: "default-foreground" },
    { kind: "tap", x: 0.25, y: 0.75 },
  ]);
  assert.match(page.element.textContent, /You have control/);
  assert.equal(renderers.length, 1, "first click does not reopen the video stream");
  page.destroy();
});

test("a locked phone is explained and never stolen", async () => {
  const fake = phone({ locked: true });
  const { page } = open(fake);
  await flush();
  page.element.querySelector(".phone-controls .btn").click();
  await flush();
  assert.match(page.element.querySelector(".control-note").textContent, /locked/);
  assert.match(page.element.textContent, /Cyclone has control/);
  page.destroy();
});

test("Ask sends the sentence unchanged and mirrors the phone's run", async () => {
  const fake = phone();
  const { page, timers } = open(fake);
  await flush();
  const goal = "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella";
  page.element.querySelector(".ask-input").value = goal;
  page.element.querySelector(".ask-form").dispatchEvent({ type: "submit" });
  await flush();
  const start = fake.gateway.calls.find((c) => c.path.endsWith("/ask/start"));
  assert.equal(start.body.goal, goal);
  assert.equal(start.body.sessionId, "default-foreground");
  assert.match(page.element.querySelector(".ask-hud").textContent, /Finding the dm of Louella/);

  fake.state.ask = { ...fake.state.ask, state: "done", outcomeCopy: "Opened Louella's conversation.", milestones: [{ label: "Finding the dm of Louella", state: "done" }] };
  const run = (runId, startedAt) => ({ runId, goal, model: "m", status: "completed", startedAt, endedAt: startedAt + 1, durationMs: 1, decisions: 1, stepCount: 1, metrics: {}, cause: null });
  fake.state.runs = [run("ai-run-old", Date.now() - 3_600_000), run("ai-run-9", Date.now())];
  for (const fn of timers.splice(0)) fn();
  await flush();
  await flush();
  assert.match(page.element.querySelector(".ask-hud").textContent, /Opened Louella's conversation/);
  const runLink = page.element.querySelector(".ask-runs-link");
  assert.equal(runLink.textContent, "Open this run", "a finished Ask links to its own run");
  assert.equal(runLink.href ?? runLink.getAttribute("href"), "#/runs/ai-run-9");
  assert.equal(timers.length, 0, "polling stops when the run is done");
  page.destroy();
});

test("Ask refusals are named in plain words", async () => {
  const fake = phone({ askError: "ASK_BUSY" });
  const { page } = open(fake);
  await flush();
  page.element.querySelector(".ask-input").value = "open clock";
  page.element.querySelector(".ask-form").dispatchEvent({ type: "submit" });
  await flush();
  assert.match(page.element.querySelector(".ask-note").textContent, /already running a task/);
  page.destroy();
});

test("pointer mapping turns a drag into a swipe and ignores letterbox clicks", () => {
  const rect = { left: 0, top: 0, width: 100, height: 200 };
  assert.deepEqual(mapPointerGesture({ clientX: 50, clientY: 150, startedAtMs: 0 }, { clientX: 50, clientY: 50, endedAtMs: 300 }, rect, 1080, 2160, 0), {
    type: "swipe", x1: 0.5, y1: 0.75, x2: 0.5, y2: 0.25, durationMs: 300,
  });
  assert.equal(mapPointerGesture({ clientX: 5, clientY: 5, startedAtMs: 0 }, { clientX: 5, clientY: 5, endedAtMs: 1 }, { left: 0, top: 0, width: 300, height: 200 }, 1080, 2160, 0), null);
});

test("live view follows portrait and landscape frame dimensions", async () => {
  const fake = phone();
  const { page, renderers } = open(fake);
  const view = page.element.querySelector(".live-view");
  const canvas = view.querySelector(".live-canvas");
  canvas.width = 864;
  canvas.height = 1920;
  renderers[0].input.callbacks.onState("LIVE");
  assert.equal(view.style["--frame-ratio"], String(864 / 1920));
  assert.equal(view.classList.contains("landscape"), false);
  canvas.width = 1920;
  canvas.height = 864;
  renderers[0].input.callbacks.onState("LIVE");
  assert.equal(view.style["--frame-ratio"], String(1920 / 864));
  assert.equal(view.classList.contains("landscape"), true);
  const mapped = mapPointerGesture({ clientX: 250, clientY: 100, startedAtMs: 0 },
    { clientX: 250, clientY: 100, endedAtMs: 1 },
    { left: 0, top: 0, width: 500, height: 225 }, 1920, 864, 0);
  assert.equal(mapped.type, "tap");
  assert.equal(mapped.x, 0.5);
  assert.ok(Math.abs(mapped.y - 100 / 225) < 1e-12);
  page.destroy();
});

test("You are here: the app, version and room on the phone now, with a way to the map", async () => {
  installMiniDom();
  const here = { placeId: "package:com.google.android.gm", roomId: "screen:list:aaaaaaaaaaaaaaaa", appVersion: "2026.09.14", observedAt: 5 };
  const fake = phone();
  const routes = fake.gateway;
  const gateway = fakeGateway({ "GET /v1/devices/d1/atlas/here": () => here });
  const merged = { calls: [], fetch: async (input, init) => (String(input).includes("/atlas/here") ? gateway.fetch(input, init) : routes.fetch(input, init)) };
  const client = new GatewayClient({ token: "tok", fetch: merged.fetch });
  const devices = [parseDevice(READY_DEVICE)];
  const navigated = [];
  const ctx = { client, version: "x", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} };
  const page = createPhonePage(ctx, {
    origin: "http://127.0.0.1:8765",
    fetch: merged.fetch,
    rendererFactory: () => ({ start() {}, stop() {} }),
    askTimer: { setTimer() {}, clearTimer() {} },
    hereTimer: { setInterval: () => 1, clearInterval() {} },
  });
  await flush();
  const card = page.element.querySelector(".here-card");
  assert.equal(card.hidden, false);
  assert.match(card.textContent, /Gmail/);
  assert.match(card.textContent, /version 2026\.09\.14/);
  assert.match(card.textContent, /List screen · aaaa/);
  card.querySelector(".btn").click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: here.placeId, tab: "map", route: [here.roomId] });
  page.destroy();
});

test("typing and scrolling from the PC only while you have control; the text is not kept", async () => {
  const fake = phone();
  const { page, controls } = open(fake);
  await flush();
  const form = page.element.querySelector("form.phone-type");
  assert.equal(form.hidden, true, "hidden while Cyclone has control");
  page.element.querySelector(".phone-controls .btn").click();
  await flush();
  assert.equal(form.hidden, false);
  const input = form.querySelector("input");
  input.value = "hello from the PC";
  form.dispatchEvent({ type: "submit", preventDefault() {} });
  await flush();
  assert.deepEqual(controls().at(-1), { kind: "text", text: "hello from the PC" });
  assert.equal(input.value, "", "typed text is cleared once sent");
  [...page.element.querySelectorAll(".phone-keys .btn")].find((b) => /Scroll down/.test(b.textContent)).click();
  await flush();
  assert.deepEqual(controls().at(-1), { kind: "scroll_down" });
  page.destroy();
});

test("recent sentences fill the Ask box without sending", async () => {
  const fake = phone();
  const run = (runId, goal, startedAt, model = "m") => ({ runId, goal, model, status: "completed", startedAt, endedAt: startedAt + 1, durationMs: 1, decisions: 1, stepCount: 1, metrics: {}, cause: null });
  fake.state.runs = [run("ai-run-r1", "open clock", 3000), run("ai-run-r2", "Open  Clock", 2000), run("ai-map-r3", "Map Clock", 4000, "cyclone-mapper"), run("ai-run-r4", "open gmail", 1000)];
  const { page } = open(fake);
  await flush();
  await flush();
  const chips = [...page.element.querySelectorAll(".ask-recent-goal")];
  assert.deepEqual(chips.map((c) => c.textContent), ["open clock", "open gmail"]);
  chips[1].click();
  assert.equal(page.element.querySelector(".ask-input").value, "open gmail");
  assert.equal(fake.gateway.calls.filter((c) => c.path.endsWith("/ask/start")).length, 0, "choosing a sentence never sends it");
  page.destroy();
});

test("while an Ask is working, Glass links to its live run", async () => {
  const fake = phone();
  const { page } = open(fake);
  await flush();
  fake.state.runs = [{ runId: "ai-run-live", goal: "open clock", model: "m", status: "running", startedAt: Date.now() + 10, endedAt: null, durationMs: 1, decisions: 1, stepCount: 1, metrics: {}, cause: null }];
  page.element.querySelector(".ask-input").value = "open clock";
  page.element.querySelector(".ask-form").dispatchEvent({ type: "submit" });
  await flush();
  await flush();
  const live = page.element.querySelector(".ask-runs-link");
  assert.equal(live.textContent, "Watch it step by step");
  assert.equal(live.href ?? live.getAttribute("href"), "#/runs/ai-run-live");
  page.destroy();
});

import { createLiveView } from "../.test-dist/ui/liveView.js";

test("live view never gives up: an unavailable stream is reopened on its own and says why", () => {
  installMiniDom();
  const timers = [];
  const renderers = [];
  const view = createLiveView({
    client: new GatewayClient({ token: "t", fetch: async () => new Response("{}") }),
    deviceId: "d1",
    origin: "http://127.0.0.1:8765",
    onGesture() {},
    unavailableMessage: () => "This PC does not see the phone over USB.",
    rendererFactory: (input) => {
      const renderer = { input, start() {}, stop() {} };
      renderers.push(renderer);
      return renderer;
    },
    setTimer: (fn) => timers.push(fn),
    clearTimer: () => (timers.length = 0),
  });
  renderers[0].input.callbacks.onState("UNAVAILABLE");
  assert.match(view.element.textContent, /does not see the phone over USB\. Retrying on its own/);
  assert.equal(timers.length, 1);
  timers.shift()();
  assert.equal(renderers.length, 2, "a new stream was opened");
  renderers[1].input.callbacks.onState("LIVE");
  assert.equal(timers.length, 0);
  view.destroy();
});

test("Share over Wi-Fi asks the phone; the owner taps the notification; then the chip says it is on", async () => {
  const fake = phone();
  let sharing = false;
  const calls = [];
  const gateway = fakeGateway({
    "GET /v1/devices/d1/share/status": () => ({ sharing, port: sharing ? 47823 : null, addresses: sharing ? ["192.168.1.20"] : [], phoneId: "x", protocol: "cyclone-lan-share-v1" }),
    "POST /v1/devices/d1/share/request": () => {
      calls.push("request");
      return { prompted: true, sharing: false };
    },
    "POST /v1/devices/d1/ask/status": () => fake.state.ask,
  });
  fake.gateway = gateway;
  const opened = open(fake);
  await flush();
  const button = [...opened.page.element.querySelectorAll(".share-box .btn")][0];
  assert.match(button.textContent, /Share over Wi‑Fi/);
  button.click();
  await flush();
  assert.deepEqual(calls, ["request"]);
  assert.match(opened.page.element.querySelector(".share-box").textContent, /tap the notification on the phone/);
  opened.page.destroy();
});

test("Keep background work on asks the gateway's fixed step and shows its answer", async () => {
  const fake = phone();
  const { page } = open(fake);
  await flush();
  const button = [...page.element.querySelectorAll(".background-card .btn")].find((b) => /Keep background work on/.test(b.textContent));
  assert.ok(button);
  button.click();
  await flush();
  const call = fake.gateway.calls.find((c) => c.path.endsWith("/background/keep-on"));
  assert.ok(call);
  assert.deepEqual(call.body, {});
  assert.match(page.element.querySelector(".background-card").textContent, /Start on boot/);
  page.destroy();
});
