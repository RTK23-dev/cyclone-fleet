import test from "node:test";
import assert from "node:assert/strict";
import { needsTrustRepair, trustRepairMessage } from "../.test-dist/core/trustRecovery.js";
import { createMockDevices } from "../.test-dist/services/mockDesktopService.js";

test("stale identity remains repairable even when the fleet still marks the phone paired", () => {
  const device = {
    ...createMockDevices(1)[0],
    paired: true,
    state: "READY",
    connectionHealth: {
      bridgeReachable: false,
      lastHeartbeatEpochMs: null,
      reconnectAttempts: 3,
      nextRetryEpochMs: null,
      lastError: "Connected phone identity no longer matches the stored trust record.",
      errorClass: "TRUST_AUTH_FAILED",
    },
  };

  assert.equal(needsTrustRepair(device), true);
  assert.match(trustRepairMessage(device), /Forget the stale record/);
});

test("healthy trusted phones do not show trust repair", () => {
  const device = createMockDevices(1)[0];
  assert.equal(needsTrustRepair(device), false);
});
