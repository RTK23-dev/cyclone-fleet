import test from "node:test";
import assert from "node:assert/strict";
import {
  ASK_NEEDS_SECRET_FIXTURE,
  GLASS_VAULT_FIXTURE_SLOTS,
  canPreserveFocusedPage,
  initialCompanionState,
  phoneSupportsGlassAtlas,
  reduceCompanionState,
} from "../.test-dist/core/fleet.js";
import { createMockDevices } from "../.test-dist/services/mockDesktopService.js";

test("companion opens on Home and exposes distinct Control, Automations and ChatGPT routes", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  assert.equal(s.route, "home");
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "navigate", route: "fleet" });
  assert.equal(s.route, "fleet");
  s = reduceCompanionState(s, { type: "navigate", route: "automations" });
  assert.equal(s.route, "automations");
  s = reduceCompanionState(s, { type: "navigate", route: "chatgpt" });
  assert.equal(s.route, "chatgpt");
});

test("Glass shell navigates to Ask, Maps and Vault without dropping ChatGPT", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  s = reduceCompanionState(s, { type: "navigate", route: "ask" });
  assert.equal(s.route, "ask");
  assert.equal(s.focusedDeviceId, null);
  s = reduceCompanionState(s, { type: "navigate", route: "maps" });
  assert.equal(s.route, "maps");
  s = reduceCompanionState(s, { type: "navigate", route: "vault" });
  assert.equal(s.route, "vault");
  s = reduceCompanionState(s, { type: "navigate", route: "chatgpt" });
  assert.equal(s.route, "chatgpt");
  s = reduceCompanionState(s, { type: "navigate", route: "fleet" });
  assert.equal(s.route, "fleet");
  s = reduceCompanionState(s, { type: "navigate", route: "settings" });
  assert.equal(s.route, "settings");
});

test("phoneSupportsGlassAtlas is true for 5.x and false for 4.x", () => {
  assert.equal(phoneSupportsGlassAtlas("5.0.0-alpha.1"), true);
  assert.equal(phoneSupportsGlassAtlas("5.0"), true);
  assert.equal(phoneSupportsGlassAtlas("v5"), true);
  assert.equal(phoneSupportsGlassAtlas("4.8.0"), false);
  assert.equal(phoneSupportsGlassAtlas("4.8"), false);
  assert.equal(phoneSupportsGlassAtlas(""), false);
});

test("fleet add/remove keeps focus only while selected phone exists", () => {
  const devices = createMockDevices(2);
  let s = initialCompanionState(devices);
  s = reduceCompanionState(s, { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  assert.equal(s.route, "focused");
  s = reduceCompanionState(s, { type: "devices_updated", devices: [...devices, { ...devices[0], id: "phone-3" }] });
  assert.equal(s.route, "focused");
  assert.equal(s.focusedSessionId, "vd-mail");
  s = reduceCompanionState(s, { type: "devices_updated", devices: [devices[1]] });
  assert.equal(s.route, "fleet");
  assert.equal(s.focusedDeviceId, null);
  assert.equal(s.focusedSessionId, null);
});

test("focused phone navigation has explicit back to fleet", () => {
  const devices = createMockDevices(1);
  let s = reduceCompanionState(initialCompanionState(devices), { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  s = reduceCompanionState(s, { type: "back_to_fleet" });
  assert.equal(s.route, "fleet");
  assert.equal(s.focusedDeviceId, null);
  assert.equal(s.focusedSessionId, null);
});

test("fleet refresh preserves a mounted focused stream while its device still exists", () => {
  const devices = createMockDevices(1);
  const s = reduceCompanionState(initialCompanionState(devices), { type: "focus_device", deviceId: devices[0].id });
  assert.equal(canPreserveFocusedPage(s, [{ ...devices[0], connectionLabel: "Heartbeat refreshed" }]), true);
  assert.equal(canPreserveFocusedPage(s, []), false);
});

test("focus device then navigate to maps keeps focusedDeviceId", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  s = reduceCompanionState(s, { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  s = reduceCompanionState(s, { type: "navigate", route: "maps" });
  assert.equal(s.route, "maps");
  assert.equal(s.focusedDeviceId, devices[0].id);
  assert.equal(s.focusedSessionId, "vd-mail");
  s = reduceCompanionState(s, { type: "navigate", route: "ask" });
  assert.equal(s.route, "ask");
  assert.equal(s.focusedDeviceId, devices[0].id);
  assert.equal(s.focusedSessionId, "vd-mail");
  s = reduceCompanionState(s, { type: "navigate", route: "vault" });
  assert.equal(s.route, "vault");
  assert.equal(s.focusedDeviceId, devices[0].id);
  assert.equal(s.focusedSessionId, "vd-mail");
});

test("navigate to home clears focusedSessionId", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  s = reduceCompanionState(s, { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  s = reduceCompanionState(s, { type: "navigate", route: "home" });
  assert.equal(s.route, "home");
  assert.equal(s.focusedDeviceId, null);
  assert.equal(s.focusedSessionId, null);
});

test("focus_session trims empty to null and never rewrites a named id", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "  vd-mail  " });
  assert.equal(s.focusedSessionId, "vd-mail");
  assert.notEqual(s.focusedSessionId, "default-foreground");
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "" });
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "   " });
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "focus_session", sessionId: null });
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "default-foreground" });
  assert.equal(s.focusedSessionId, "default-foreground");
});

test("leaving Glass pages for fleet/settings clears both focused ids", () => {
  const devices = createMockDevices(1);
  let s = initialCompanionState(devices);
  s = reduceCompanionState(s, { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  s = reduceCompanionState(s, { type: "navigate", route: "fleet" });
  assert.equal(s.focusedDeviceId, null);
  assert.equal(s.focusedSessionId, null);
  s = reduceCompanionState(s, { type: "focus_device", deviceId: devices[0].id });
  s = reduceCompanionState(s, { type: "focus_session", sessionId: "vd-mail" });
  s = reduceCompanionState(s, { type: "navigate", route: "settings" });
  assert.equal(s.focusedDeviceId, null);
  assert.equal(s.focusedSessionId, null);
});

test("Ask fixture title and vault slots stay unchanged", () => {
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.title, "Facebook needs a password");
  assert.equal(GLASS_VAULT_FIXTURE_SLOTS.length, 3);
  assert.equal(GLASS_VAULT_FIXTURE_SLOTS[0].set, true);
  assert.equal(typeof GLASS_VAULT_FIXTURE_SLOTS[0].set, "boolean");
});
