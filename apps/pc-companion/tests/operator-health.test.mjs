import test from "node:test";
import assert from "node:assert/strict";
import { deviceOperatorHealth, operatorRecovery } from "../.test-dist/core/operatorHealth.js";
import { createMockDevices } from "../.test-dist/services/mockDesktopService.js";

test("legacy Gateway devices keep unreported planes distinct from failures", () => {
  const device = createMockDevices(1)[0];
  const health = deviceOperatorHealth(device);
  assert.equal(health.length, 6);
  assert.equal(health.find((item) => item.id === "usb").state, "UNKNOWN");
  assert.equal(health.find((item) => item.id === "accessibility").state, "UNKNOWN");
  assert.equal(health.find((item) => item.id === "semantic").detail, "Not reported by this Gateway");
});

test("independent planes distinguish USB approval from stale phone session", () => {
  const device = createMockDevices(1)[0];
  device.state = "UNAUTHORIZED";
  device.planes = { discovery: "UNAUTHORIZED", bridge: "CONNECTED", aiTrust: "TRUSTED", media: "LIVE" };
  assert.equal(operatorRecovery(device)?.label, "Approve USB debugging");

  device.state = "READY";
  device.planes = { discovery: "ADB_READY", bridge: "AUTH_FAILED", aiTrust: "EXPIRED", media: "LIVE" };
  const health = deviceOperatorHealth(device);
  assert.equal(health.find((item) => item.id === "media").state, "READY");
  assert.equal(health.find((item) => item.id === "session").state, "ACTION_REQUIRED");
  assert.equal(operatorRecovery(device)?.needsPairing, true);
});

test("newer Gateway plane details override local legacy inference", () => {
  const device = createMockDevices(1)[0];
  device.operatorHealth = {
    accessibility: { state: "ACTION_REQUIRED", message: "Enable Cyclone Accessibility" },
    semantic: { state: "RECOVERING", message: "Refreshing semantic observer" },
  };
  const health = deviceOperatorHealth(device);
  assert.deepEqual(health.find((item) => item.id === "accessibility"), {
    id: "accessibility", label: "Accessibility", state: "ACTION_REQUIRED", detail: "Enable Cyclone Accessibility",
  });
  assert.equal(health.find((item) => item.id === "semantic").state, "RECOVERING");
});
