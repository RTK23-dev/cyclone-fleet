import test from "node:test";
import assert from "node:assert/strict";
import { MockDesktopService, createMockDevices } from "../.test-dist/services/mockDesktopService.js";

test("mock disconnected devices expose bounded reconnect health", () => {
  const devices = createMockDevices(6);
  const disconnected = devices.find((device) => device.state === "DISCONNECTED");
  assert.ok(disconnected);
  assert.equal(disconnected.connectionHealth.bridgeReachable, false);
  assert.ok(disconnected.connectionHealth.reconnectAttempts >= 1);
  assert.equal(disconnected.connectionHealth.maxReconnectAttempts, 5);
  assert.ok(disconnected.connectionHealth.nextRetryEpochMs > Date.now());
  assert.ok(disconnected.connectionHealth.lastError);
});

test("mock ready devices expose a healthy bridge heartbeat", () => {
  const device = createMockDevices(1)[0];
  assert.equal(device.state, "READY");
  assert.equal(device.connectionHealth.bridgeReachable, true);
  assert.equal(device.connectionHealth.reconnectAttempts, 0);
  assert.equal(device.connectionHealth.lastError, null);
});

test("mock reconnect action resets connection health", async () => {
  const service = new MockDesktopService(6);
  const disconnected = (await service.listDevices()).find((device) => device.state === "DISCONNECTED");
  assert.ok(disconnected);
  await service.sendControl(disconnected.id, { type: "reconnect" });
  const refreshed = (await service.listDevices()).find((device) => device.id === disconnected.id);
  assert.equal(refreshed.state, "READY");
  assert.equal(refreshed.connectionHealth.bridgeReachable, true);
  assert.equal(refreshed.connectionHealth.reconnectAttempts, 0);
});

test("mock pairing exposes healthy phone preflight", async () => {
  const service = new MockDesktopService(4);
  const device = (await service.listDevices()).find((candidate) => !candidate.paired);
  assert.ok(device);
  const begin = await service.pairBegin(device.id);
  assert.deepEqual(begin.preflight, {
    appRunning: true,
    accessibilityEnabled: true,
    accessibilityServiceConfigured: true,
  });
});
