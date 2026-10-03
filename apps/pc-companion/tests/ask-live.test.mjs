import test from "node:test";
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { createAskPage, askSnapshotFromStatus } from "../.test-dist/pages/askPage.js";
import { createAtlasClient, parseAskStatus } from "../.test-dist/services/atlasClient.js";

const LOUELLA = "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella";

function status(state, extra = {}) {
  return {
    taskId: state === "idle" ? null : "task-1",
    state,
    title: state === "idle" ? "" : "Gmail → Facebook",
    app: "Facebook",
    currentMilestone: "Finding the dm of Louella",
    milestones: state === "idle" ? [] : [
      { label: "Finding the signed-in email address", state: "done" },
      { label: "Finding the dm of Louella", state: state === "needs-secret" ? "action-needed" : "active" },
    ],
    supportingCopy: state === "needs-secret" ? "Secure input is required to continue." : null,
    outcomeCopy: state === "done" ? "Opened Louella's conversation." : null,
    ...extra,
  };
}

async function flush() {
  for (let i = 0; i < 6; i += 1) await delay(0);
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
      for (const fn of pending.splice(0)) fn();
      await flush();
    },
  };
}

function fakePhone() {
  const phone = {
    goals: [],
    current: status("idle"),
    startError: null,
    ops: {
      async askStart(goal) {
        if (phone.startError) throw phone.startError;
        phone.goals.push(goal);
        phone.current = status("working");
      },
      async askStatus() {
        return structuredClone(phone.current);
      },
    },
  };
  return phone;
}

async function openAsk(phone, timer, extra = {}) {
  installMiniDom();
  const page = createAskPage({
    mobileVersion: "5.0.0-alpha.4.dev1",
    sessionId: "default-foreground",
    sessionPlane: "foreground",
    ask: phone.ops,
    askTimer: timer,
    ...extra,
  });
  await flush();
  return page;
}

function submit(page, text) {
  page.element.querySelector(".ask-composer-input").value = text;
  const form = page.element.querySelector(".ask-composer-form");
  form.dispatchEvent({ type: "submit" });
}

test("Send is live for a V5 phone and sends the sentence unchanged", async () => {
  const phone = fakePhone();
  const timer = manualTimer();
  const page = await openAsk(phone, timer);
  assert.equal(page.element.querySelector(".ask-send").disabled, false);

  submit(page, LOUELLA);
  await flush();

  assert.deepEqual(phone.goals, [LOUELLA]);
  const hud = page.element.querySelector(".ask-hud");
  assert.ok(hud, "live HUD renders");
  assert.equal(hud.dataset.state, "working");
  assert.match(hud.textContent, /Gmail → Facebook/);
  assert.match(hud.textContent, /Finding the dm of Louella/);
  assert.doesNotMatch(page.element.textContent, /\(sample\)/);
  page.destroy();
});

test("HUD mirrors needs-secret on the phone, then the outcome", async () => {
  const phone = fakePhone();
  const timer = manualTimer();
  const page = await openAsk(phone, timer);
  submit(page, LOUELLA);
  await flush();

  phone.current = status("needs-secret");
  await timer.fire();
  const hud = page.element.querySelector(".ask-hud");
  assert.equal(hud.dataset.state, "needs-secret");
  assert.ok(page.element.querySelector(".ask-hud-host").textContent.length > 0);

  phone.current = status("done");
  await timer.fire();
  assert.equal(page.element.querySelector(".ask-hud").dataset.state, "done");
  assert.match(page.element.querySelector(".ask-hud").textContent, /Opened Louella's conversation/);

  // Terminal: polling stops.
  await timer.fire();
  phone.current = status("working");
  await timer.fire();
  assert.equal(page.element.querySelector(".ask-hud").dataset.state, "done");
  page.destroy();
});

test("Glass follows a run started on the phone", async () => {
  const phone = fakePhone();
  phone.current = status("working");
  const page = await openAsk(phone, manualTimer());
  assert.equal(page.element.querySelector(".ask-hud").dataset.state, "working");
  page.destroy();
});

test("busy phone and secret-looking goals show plain copy and nothing runs", async () => {
  const phone = fakePhone();
  phone.startError = Object.assign(new Error("busy"), { code: "ASK_BUSY" });
  const page = await openAsk(phone, manualTimer());
  submit(page, "open clock");
  await flush();
  assert.match(page.element.querySelector(".ask-form-status").textContent, /already running a task/);

  phone.startError = Object.assign(new Error("secret"), { code: "SECRET_PAYLOAD_REJECTED" });
  submit(page, "log in with password: hunter2");
  await flush();
  assert.match(page.element.querySelector(".ask-form-status").textContent, /Keep passwords out of the goal/);
  assert.deepEqual(phone.goals, []);
  page.destroy();
});

test("Send stays off on a named workspace plane and without a phone transport", async () => {
  const phone = fakePhone();
  const vd = await openAsk(phone, manualTimer(), { sessionId: "vd-mail", sessionPlane: "session_kernel_vd" });
  assert.equal(vd.element.querySelector(".ask-send").disabled, true);
  vd.destroy();

  const none = await openAsk(phone, manualTimer(), { ask: undefined });
  assert.equal(none.element.querySelector(".ask-send").disabled, true);
  none.destroy();
});

test("client sends goal text with the foreground plane and parses the phone snapshot", async () => {
  const calls = [];
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "default-foreground",
    getDeviceId: () => "phone-1",
    fetch: async (url, init = {}) => {
      calls.push({ url: String(url), init });
      const body = String(url).includes("/ask/start")
        ? { accepted: true, sessionId: "default-foreground", displayId: 0 }
        : status("needs-secret");
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    },
  });
  await atlas.askStart(LOUELLA);
  assert.deepEqual(JSON.parse(calls[0].init.body), { goal: LOUELLA, sessionId: "default-foreground", displayId: 0 });
  const snapshot = await atlas.askStatus();
  assert.equal(snapshot.state, "needs-secret");
  assert.equal(askSnapshotFromStatus(snapshot, "default-foreground").slotLabel, "password");
  assert.equal(askSnapshotFromStatus(status("idle"), "default-foreground"), null);

  await assert.rejects(() => atlas.askStart("login password=hunter2"), (error) => error.code === "SECRET_PAYLOAD_REJECTED");
  assert.throws(() => parseAskStatus({ ...status("working"), state: "walking" }), (error) => error.code === "PROTOCOL_MISMATCH");
});
