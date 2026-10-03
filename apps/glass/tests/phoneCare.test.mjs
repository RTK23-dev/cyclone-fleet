import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { exitKindLabel, parsePhoneCare } from "../.test-dist/services/phoneCare.js";
import { createPhoneCareView, IDLE_POLL_MS, WORKING_POLL_MS } from "../.test-dist/pages/phoneCareView.js";

installMiniDom();
const NOW = 1_790_000_000_000;
const PATH = "/v1/devices/d1/care";
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

const answer = (over = {}) => ({
  deviceId: "d1", status: "good", headline: "Up to date", detail: "Cyclone 5.0.0-alpha.87.dev1 on the phone and this PC.",
  hint: null, atMs: null, action: null,
  details: {
    versions: { phone: "5.0.0-alpha.87.dev1", phoneCode: 232, pc: "5.0.0-alpha.87.dev1", compare: "same" },
    update: null, lastStop: null, freezes: { count: 0, longestMs: 0 }, exits: [], stalls: [],
    healthCollectedAtMs: NOW - 30_000, diagnosticsPath: "C:\\Users\\me\\AppData\\Local\\Cyclone One\\runtime\\diagnostics\\live-d1",
  },
  ...over,
});

function clock() {
  const timers = [];
  return {
    timers,
    deps: { now: () => NOW, later: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return () => { t.cancelled = true; }; } },
    async fire() {
      const next = timers.filter((t) => !t.cancelled).pop();
      next.cancelled = true;
      next.fn();
      await flush();
      return next.ms;
    },
  };
}

test("phone care parses defensively and names Android's exit kinds", () => {
  const parsed = parsePhoneCare({ ...answer(), status: "weird", action: { kind: "delete", label: "x" }, details: { exits: [{ atMs: 5, kind: "anr", mainThread: ["a.B.c:1", 3] }, "junk"] } });
  assert.equal(parsed.status, "offline");
  assert.equal(parsed.action, null);
  assert.deepEqual(parsed.details.exits[0].mainThread, ["a.B.c:1"]);
  assert.equal(exitKindLabel("anr"), "Stopped responding");
  assert.equal(exitKindLabel("nope"), "Unknown reason");
});

test("a healthy phone is one quiet line; the evidence is behind Details", async () => {
  const gw = fakeGateway({ [`GET ${PATH}`]: () => answer() });
  const c = clock();
  const view = createPhoneCareView(new GatewayClient({ token: "t", fetch: gw.fetch }), "d1", c.deps);
  await flush();
  assert.equal(view.element.hidden, false);
  assert.match(view.element.textContent, /Up to date/);
  assert.equal(button(view.element, "Update"), undefined);
  assert.doesNotMatch(view.element.textContent, /Diagnostics/);
  button(view.element, "Details").click();
  assert.match(view.element.textContent, /Diagnostics/);
  assert.match(view.element.textContent, /live-d1/);
  assert.equal(c.timers.at(-1).ms, IDLE_POLL_MS);
  view.destroy();
});

test("an old phone app gets one Update button that installs, shows progress and ends up to date", async () => {
  let state = "older";
  const gw = fakeGateway({
    [`GET ${PATH}`]: () => state === "older"
      ? answer({ status: "attention", headline: "Update available", detail: "The phone has Cyclone 5.0.0-alpha.84.dev1; this PC has 5.0.0-alpha.87.dev1.", action: { kind: "update", label: "Update phone" } })
      : state === "installing"
        ? answer({ status: "working", headline: "Updating the phone", detail: "Installing on the phone. Keep it plugged in." })
        : answer({ headline: "Updated", detail: "Cyclone 5.0.0-alpha.87.dev1 is on the phone." }),
    [`POST ${PATH}/update`]: () => { state = "installing"; return answer({ status: "working", headline: "Updating the phone", detail: "Getting the update…" }); },
  });
  const c = clock();
  const view = createPhoneCareView(new GatewayClient({ token: "t", fetch: gw.fetch }), "d1", c.deps);
  await flush();
  assert.match(view.element.textContent, /Update available/);
  button(view.element, "Update phone").click();
  await flush();
  assert.equal(gw.calls.filter((call) => call.method === "POST").length, 1);
  assert.match(view.element.textContent, /Updating the phone/);
  assert.equal(button(view.element, "Update phone"), undefined, "no second button while it runs");
  assert.equal(c.timers.filter((t) => !t.cancelled).at(-1).ms, WORKING_POLL_MS);
  assert.equal(await c.fire(), WORKING_POLL_MS);
  assert.match(view.element.textContent, /Keep it plugged in/);
  state = "done";
  await c.fire();
  assert.match(view.element.textContent, /Updated/);
  view.destroy();
});

test("a stop names what happened and when, with the frozen code in the details", async () => {
  const gw = fakeGateway({ [`GET ${PATH}`]: () => answer({
    status: "attention", headline: "Cyclone stopped unexpectedly", detail: "It stopped because it stopped responding and Android closed it.", atMs: NOW - 3_600_000,
    details: { ...answer().details, exits: [{ atMs: NOW - 3_600_000, kind: "anr", unexpected: true, description: "Input dispatching timed out", mainThread: ["com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490"] }],
      stalls: [{ startedAtMs: NOW - 60_000, durationMs: 23_703, suspect: "com.cyclone.mobile.X.y:1", frames: ["com.cyclone.mobile.X.y:1"] }],
      freezes: { count: 1, longestMs: 23_703 } },
  }) });
  const view = createPhoneCareView(new GatewayClient({ token: "t", fetch: gw.fetch }), "d1", clock().deps);
  await flush();
  assert.match(view.element.textContent, /1 h ago · It stopped because it stopped responding/);
  assert.ok(view.element.className.includes("care-attention"));
  button(view.element, "Details").click();
  assert.match(view.element.textContent, /Stopped responding/);
  assert.match(view.element.textContent, /preferredForegroundRoot:490/);
  assert.match(view.element.textContent, /23\.7 s/);
  view.destroy();
});

test("a failed update explains itself with one next step; an offline phone or an older runtime shows nothing", async () => {
  const gw = fakeGateway({ [`GET ${PATH}`]: () => answer({ status: "problem", headline: "A different build is on the phone", detail: "The Cyclone on this phone was signed by someone else.", hint: "Remove Cyclone from the phone, then press Update again.", action: null,
    details: { ...answer().details, update: { state: "failed", target: "5.0.0-alpha.87.dev1", from: "5.0.0-alpha.84.dev1", error: { code: "INSTALL_FAILED_UPDATE_INCOMPATIBLE", detail: "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]" } } } }) });
  const view = createPhoneCareView(new GatewayClient({ token: "t", fetch: gw.fetch }), "d1", clock().deps);
  await flush();
  assert.match(view.element.textContent, /Remove Cyclone from the phone/);
  assert.equal(button(view.element, "Try again"), undefined);
  button(view.element, "Details").click();
  assert.match(view.element.textContent, /INSTALL_FAILED_UPDATE_INCOMPATIBLE/);
  view.destroy();

  const offline = createPhoneCareView(new GatewayClient({ token: "t", fetch: fakeGateway({ [`GET ${PATH}`]: () => answer({ status: "offline", headline: "" }) }).fetch }), "d1", clock().deps);
  await flush();
  assert.equal(offline.element.hidden, true);
  offline.destroy();
  const old = createPhoneCareView(new GatewayClient({ token: "t", fetch: fakeGateway({ [`GET ${PATH}`]: () => json({ detail: "Not Found" }, 404) }).fetch }), "d1", clock().deps);
  await flush();
  assert.equal(old.element.hidden, true);
  old.destroy();
});

test("the phone's decision numbers show under Details: who decides, how fast, what the phone model earned", async () => {
  const gw = fakeGateway({ [`GET ${PATH}`]: () => answer({ details: { ...answer().details, decisions: {
    provider: "JEV (TypeSafe)", speed: "auto", phoneModel: "earned", lessons: 120, onPhoneShare: 0.42,
    decisionsMs: { p50: 420, p95: 1100 }, instantVerified: 0.97, instantChecked: 60, shadowAgreement: 0.99, shadowChecked: 70,
    earned: ["volume_up", "scroll_down", "Bad"] } } }) });
  const view = createPhoneCareView(new GatewayClient({ token: "t", fetch: gw.fetch }), "d1", clock().deps);
  await flush();
  button(view.element, "Details").click();
  const text = view.element.textContent;
  assert.match(text, /Instant decisions/);
  assert.match(text, /42% of 120 requests/);
  assert.match(text, /420 ms median · 1\.1 s slowest 5%/);
  assert.match(text, /99% of 70 checked with JEV/);
  assert.match(text, /Volume up, Scroll down/);
  assert.doesNotMatch(text, /Bad/);
  view.destroy();
});
