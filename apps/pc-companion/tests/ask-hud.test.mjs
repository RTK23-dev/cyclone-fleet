import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import {
  ASK_NEEDS_SECRET_FIXTURE,
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
} from "../.test-dist/core/fleet.js";
import { createAskPage } from "../.test-dist/pages/askPage.js";
import { formatAskHudLog } from "../.test-dist/pages/askHudLog.js";

const askSource = fs.readFileSync(new URL("../src/pages/askPage.ts", import.meta.url), "utf8");
const fleetSource = fs.readFileSync(new URL("../src/core/fleet.ts", import.meta.url), "utf8");
const SECRET_VALUE = /hunter2|\botp\s*[:=]|\bcookie\s*[:=]|\btoken\s*[:=]/i;

installMiniDom();

test("ASK_NEEDS_SECRET_FIXTURE.title remains Facebook needs a password", () => {
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.title, "Facebook needs a password");
  assert.match(fleetSource, /title:\s*"Facebook needs a password"/);
});

test("omitted version keeps today's sample dropdown (merge-safe default)", () => {
  const page = createAskPage();
  const select = page.element.querySelector(".ask-demo-select");
  assert.ok(select, "sample dropdown must exist when mobileVersion is omitted");
  assert.match(page.element.textContent, /Sample snapshot \(no phone required\)/);
  assert.equal(page.element.querySelector(".ask-send")?.disabled, true);
  page.destroy();
});

test("5.x without preview is an empty honest HUD, not a live sample run", () => {
  const page = createAskPage({ mobileVersion: "5.0.0-alpha.1" });
  assert.equal(page.element.querySelector(".ask-demo-select"), null);
  assert.equal(page.element.querySelector(".ask-demo-bar"), null);
  assert.doesNotMatch(page.element.textContent, /Sample snapshot \(no phone required\)/);
  assert.doesNotMatch(page.element.textContent, /Sample snapshot — not a live phone run/);
  assert.doesNotMatch(page.element.textContent, /Opening Facebook/);
  assert.doesNotMatch(page.element.textContent, /Facebook needs a password/);
  assert.match(page.element.textContent, /No Ask run yet/);
  assert.match(page.element.textContent, /Send stays off until the phone Ask transport is connected/);
  assert.equal(page.element.querySelector(".ask-hud-title"), null);
  assert.equal(page.element.querySelector(".ask-hud-download"), null);
  assert.equal(page.element.querySelector(".ask-send")?.disabled, true);
  assert.equal(page.element.querySelector(".glass-compat-banner")?.hidden, true);
  page.destroy();
});

test("4.8.0 without preview shows update-phone copy and does not present samples as live", () => {
  const page = createAskPage({ mobileVersion: "4.8.0" });
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_TITLE));
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_COPY.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.equal(page.element.querySelector(".glass-compat-banner")?.hidden, false);
  assert.equal(page.element.querySelector(".ask-demo-select"), null);
  assert.doesNotMatch(page.element.textContent, /Opening Facebook/);
  assert.doesNotMatch(page.element.textContent, /Sample snapshot \(no phone required\)/);
  assert.equal(page.element.querySelector(".ask-send")?.disabled, true);
  page.destroy();
});

test("previewSnapshots with 5.x shows samples labeled (sample)", () => {
  const page = createAskPage({
    mobileVersion: "5.0.0-alpha.1",
    previewSnapshots: true,
  });
  assert.ok(page.element.querySelector(".ask-demo-select"));
  assert.match(page.element.textContent, /\(sample\)/);
  assert.match(page.element.textContent, /Sample snapshot — not a live phone run/);
  assert.doesNotMatch(page.element.textContent, /Opening Facebook/);
  const select = page.element.querySelector(".ask-demo-select");
  select.value = "working";
  select.dispatchEvent({ type: "change" });
  assert.match(page.element.querySelector(".ask-hud-title")?.textContent ?? "", /Opening Facebook/);
  assert.match(page.element.textContent, /\(sample\)/);
  assert.ok(page.element.querySelector(".ask-hud-download"));
  assert.equal(page.element.querySelector(".ask-send")?.disabled, true);
  page.destroy();
});

test("sessionId vd-mail appears unchanged on the Ask plane", () => {
  const page = createAskPage({
    mobileVersion: "5.0.0-alpha.1",
    sessionId: "vd-mail",
    sessionPlane: "session_kernel_vd",
  });
  const plane = page.element.querySelector(".ask-session-plane");
  assert.ok(plane);
  assert.match(plane.textContent, /Session Kernel VD/);
  assert.match(plane.textContent, /vd-mail/);
  assert.doesNotMatch(plane.textContent, /default-foreground/);
  assert.equal(plane.dataset.sessionId, "vd-mail");
  assert.equal(page.element.textContent.includes("vd-mail"), true);
  page.destroy();
});

test("omitted sessionId displays default-foreground on Foreground", () => {
  const page = createAskPage();
  const plane = page.element.querySelector(".ask-session-plane");
  assert.match(plane.textContent, /Foreground/);
  assert.match(plane.textContent, /default-foreground/);
  assert.equal(plane.dataset.sessionId, "default-foreground");
  page.destroy();
});

test("formatAskHudLog on needs-secret fixture has title, state, session, and no secret values", () => {
  const log = formatAskHudLog(ASK_NEEDS_SECRET_FIXTURE);
  assert.match(log, /Facebook needs a password/);
  assert.match(log, /needs-secret/);
  assert.match(log, /default-foreground/);
  assert.match(log, /session_id/);
  assert.match(log, /Open Facebook/);
  assert.doesNotMatch(log, SECRET_VALUE);
  assert.doesNotMatch(log, /hunter2/);
  assert.doesNotMatch(log, /otp=/);
  assert.doesNotMatch(log, /cookie=/);
  const named = formatAskHudLog(ASK_NEEDS_SECRET_FIXTURE, "vd-mail");
  assert.match(named, /vd-mail/);
  assert.doesNotMatch(named, /default-foreground/);
  assert.doesNotMatch(named, SECRET_VALUE);
});

test("Send stays disabled and submit does not execute on the PC", () => {
  const page = createAskPage({ mobileVersion: "5.0.0-alpha.1" });
  const send = page.element.querySelector(".ask-send");
  assert.equal(send?.disabled, true);
  const form = page.element.querySelector("form");
  form.dispatchEvent({ type: "submit" });
  assert.match(page.element.textContent, /Glass does not execute this goal on the PC/);
  assert.doesNotMatch(askSource, /ask\.start/);
  assert.doesNotMatch(askSource, /type\s*=\s*["']password["']/);
  page.destroy();
});

test("previewNeedsSecret paints the wait HUD and a Download HUD log control", () => {
  const page = createAskPage({ previewNeedsSecret: true });
  assert.equal(page.element.querySelector(".ask-hud")?.dataset.state, "needs-secret");
  assert.match(page.element.querySelector(".ask-hud-title")?.textContent ?? "", /Facebook needs a password/);
  assert.match(page.element.textContent, /\(sample\)/);
  assert.match(page.element.textContent, /Sample snapshot — not a live phone run/);
  assert.ok(page.element.querySelector(".ask-hud-download"));
  assert.match(page.element.querySelector(".ask-hud-download")?.textContent ?? "", /Download HUD log/);
  assert.equal(page.element.querySelector(".ask-send")?.disabled, true);
  page.destroy();
});

test("Ask honesty uses hasOwnProperty / in for mobileVersion", () => {
  assert.match(askSource, /hasOwnProperty\.call\(options,\s*"mobileVersion"\)/);
  assert.match(askSource, /"mobileVersion" in options/);
  assert.match(askSource, /previewSnapshots/);
  assert.match(askSource, /sessionPlane/);
});
