import test from "node:test";
import assert from "node:assert/strict";
import {
  armedGoalLabel,
  bindLayer2Status,
  canPauseLayer2,
  canReleaseLayer2,
  generationLabel,
  isLayer2Plane,
  LAYER2_COMPACT_COPY,
  LAYER2_DISPLAY_LABEL,
  LAYER2_EMPTY_COPY,
  LAYER2_PLANE,
  LAYER2_PROTOCOL,
  LAYER2_STRIP_COPY,
  lockOwnerLabel,
  normalizeLayer2Status,
} from "../.test-dist/core/layer2.js";
import { bindSessionTile } from "../.test-dist/core/sessionTiles.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

test("normalize keeps display 0 and rejects display>0 Layer 2 rows", () => {
  const status = normalizeLayer2Status({
    protocol: LAYER2_PROTOCOL,
    deviceId: "phone-1",
    sessionId: "workspace-mail",
    displayId: 7,
    plane: "layer2",
    workspaces: [
      { id: "profile-a", label: "Profile A", appPackage: "com.android.chrome", androidUserId: 0, displayId: 0, state: "running" },
      { id: "vd-session", label: "Named VD", appPackage: "com.android.settings", androidUserId: 0, displayId: 2, state: "running" },
    ],
    holder: "profile-a",
    workspaceGeneration: 3,
    armed: ["profile-a"],
    gated: false,
  }, "phone-1");
  assert.equal(status.displayId, 0);
  assert.equal(status.sessionId, "default-foreground");
  assert.equal(status.plane, "layer2");
  assert.equal(status.workspaces.length, 1);
  assert.equal(status.workspaces[0].id, "profile-a");
  assert.equal(status.workspaces[0].displayId, 0);
  assert.ok(!status.workspaces.some((row) => row.displayId > 0));

  const missingDisplay = normalizeLayer2Status({
    workspaces: [{ id: "profile-b", label: "Profile B", appPackage: "com.google.android.apps.maps", androidUserId: 10, state: "idle" }],
  }, "phone-1");
  assert.equal(missingDisplay.displayId, 0);
  assert.equal(missingDisplay.workspaces[0].displayId, 0);
});

test("mock pause/release/switch updates holder and generation", async () => {
  const service = new MockDesktopService(1);
  const listed = await service.listLayer2Workspaces("phone-1");
  assert.equal(listed.protocol, LAYER2_PROTOCOL);
  assert.equal(listed.plane, "layer2");
  assert.equal(listed.displayId, 0);
  assert.equal(listed.sessionId, "default-foreground");
  assert.equal(listed.workspaces.length, 2);
  assert.ok(listed.workspaces.every((row) => row.displayId === 0));
  assert.equal(listed.holder, "profile-a");
  assert.equal(listed.lockOwner, "profile-a");
  assert.equal(listed.workspaceGeneration, 1);
  assert.deepEqual(listed.armed, ["profile-a"]);
  assert.equal(canPauseLayer2(listed), true);
  assert.equal(canReleaseLayer2(listed), true);

  const paused = await service.layer2Workspace("phone-1", "pause");
  assert.equal(paused.holder, null);
  assert.equal(paused.lockOwner, null);
  assert.equal(paused.workspaceGeneration, 2);
  assert.equal(canPauseLayer2(paused), false);

  const switched = await service.layer2Workspace("phone-1", "switch", { id: "profile-b" });
  assert.equal(switched.holder, "profile-b");
  assert.equal(switched.lockOwner, "profile-b");
  assert.equal(switched.workspaceId, "profile-b");
  assert.equal(switched.workspaceGeneration, 3);
  assert.equal(switched.verified, true);
  assert.equal(switched.displayId, 0);
  assert.match(lockOwnerLabel(switched), /Profile B/);
  assert.equal(generationLabel(switched), "Generation: 3");

  const released = await service.layer2Workspace("phone-1", "release");
  assert.equal(released.holder, null);
  assert.equal(released.armed.length, 0);
  assert.equal(released.workspaceGeneration, 4);
  assert.equal(armedGoalLabel(released), "Armed goal: none");
});

test("Layer 2 status is not a session tile", async () => {
  const service = new MockDesktopService(1);
  const status = bindLayer2Status("phone-1", await service.listLayer2Workspaces("phone-1"));
  const tile = bindSessionTile("phone-1", {
    sessionId: "workspace-phone-1",
    displayId: 2,
    state: "RUNNING",
    targetPackage: "com.android.chrome",
  });
  assert.equal(isLayer2Plane(status), true);
  assert.equal(status.plane, LAYER2_PLANE);
  assert.equal("kind" in status, false);
  assert.equal(tile.kind, "session_kernel_vd");
  assert.notEqual(tile.kind, status.plane);
  assert.ok(tile.displayId > 0);
  assert.equal(status.displayId, 0);
  assert.equal(status.sessionId, "default-foreground");
  assert.notEqual(status.sessionId, tile.sessionId);
  assert.equal(isLayer2Plane(tile), false);
});

test("Layer 2 copy and plane are layer2, not workspace-VD", () => {
  assert.equal(LAYER2_DISPLAY_LABEL, "Layer 2 · display 0");
  assert.match(LAYER2_STRIP_COPY, /display-0 time-sliced/i);
  assert.match(LAYER2_STRIP_COPY, /not named/i);
  assert.match(LAYER2_STRIP_COPY, /VD|virtual-display/i);
  assert.match(LAYER2_COMPACT_COPY, /not a named VD session/i);
  assert.equal(LAYER2_EMPTY_COPY, "No Layer 2 profiles registered on the phone");
  assert.doesNotMatch(LAYER2_STRIP_COPY, /session_id/);
  assert.equal(LAYER2_PLANE, "layer2");
  assert.notEqual(LAYER2_PLANE, "workspace");
  assert.notEqual(LAYER2_PLANE, "session_kernel_vd");
});
