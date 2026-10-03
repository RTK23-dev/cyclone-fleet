import test from "node:test";
import assert from "node:assert/strict";
import {
  applySessionEvent,
  bindSessionTile,
  DEFAULT_FOREGROUND_SESSION_ID,
  FOREGROUND_KIND,
  FOREGROUND_PLANE_COPY,
  FOREGROUND_PLANE_LABEL,
  isSessionFabricEvent,
  isSessionKernelVd,
  jpegFocusTarget,
  markSessionInventory,
  MAX_HOT_BACKGROUND_ASK,
  MCP_FOREGROUND_SESSION_COPY,
  normalizeInputOwner,
  parseFleetWsEvent,
  readExactSessionSnapshotHeaders,
  SESSION_KERNEL_VD_KIND,
  SESSION_TILES_COPY,
  SESSION_TILES_TITLE,
  sessionDisplayLabel,
  sessionPlaneLabel,
  tileForSessionScope,
  VD_PLANE_LABEL,
} from "../.test-dist/core/sessionTiles.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

test("session tiles bind to session_id and never invent display 0 for named Session Kernel VD", () => {
  const foreground = bindSessionTile("phone-1", {
    sessionId: "default-foreground",
    displayId: 0,
    state: "FOREGROUND",
    executable: true,
  });
  assert.equal(foreground.sessionId, "default-foreground");
  assert.equal(foreground.kind, "foreground");
  assert.equal(foreground.plane, "foreground");
  assert.equal(foreground.displayId, 0);
  assert.equal(isSessionKernelVd(foreground), false);
  assert.equal(sessionPlaneLabel(foreground), FOREGROUND_PLANE_LABEL);

  const named = bindSessionTile("phone-1", {
    sessionId: "workspace-mail",
    displayId: 2,
    state: "RUNNING",
    targetPackage: "com.google.android.gm",
    inputOwner: "AI",
  });
  assert.equal(named.sessionId, "workspace-mail");
  assert.equal(named.kind, "session_kernel_vd");
  assert.equal(named.kind, SESSION_KERNEL_VD_KIND);
  assert.equal(named.plane, "session_kernel_vd");
  assert.equal(named.displayId, 2);
  assert.equal(named.inputOwner, "AI");
  assert.equal(isSessionKernelVd(named), true);
  assert.equal(sessionPlaneLabel(named), VD_PLANE_LABEL);
  assert.notEqual(named.plane, "layer2");

  const missingDisplay = bindSessionTile("phone-1", {
    sessionId: "workspace-maps",
    state: "RUNNING",
  });
  assert.equal(missingDisplay.kind, "session_kernel_vd");
  assert.equal(missingDisplay.plane, "session_kernel_vd");
  assert.equal(missingDisplay.displayId, undefined);
  assert.notEqual(missingDisplay.displayId, 0);
  assert.equal(sessionDisplayLabel(missingDisplay), "unknown (not display 0)");

  const zeroRewrite = bindSessionTile("phone-1", {
    sessionId: "workspace-chat",
    displayId: 0,
    state: "RUNNING",
  });
  assert.equal(zeroRewrite.kind, "session_kernel_vd");
  assert.equal(zeroRewrite.displayId, undefined);
  assert.notEqual(zeroRewrite.displayId, 0);
  assert.match(sessionDisplayLabel(zeroRewrite), /not display 0/);
});

test("session.added includes owner HUMAN/AI, sessionId, displayId; session.removed removes that tile", () => {
  let tiles = [];
  const added = parseFleetWsEvent({
    event: "session.added",
    payload: { sessionId: "workspace-chrome", displayId: 2, deviceId: "phone-1", owner: "AI", state: "RUNNING" },
  });
  assert.ok(added);
  assert.equal(isSessionFabricEvent(added), true);
  assert.equal(added.sessionId, "workspace-chrome");
  assert.equal(added.displayId, 2);
  assert.equal(added.owner, "AI");
  assert.equal(added.state, "RUNNING");
  tiles = applySessionEvent(tiles, added);
  assert.equal(tiles.length, 1);
  assert.equal(tiles[0].sessionId, "workspace-chrome");
  assert.equal(tiles[0].displayId, 2);
  assert.equal(tiles[0].deviceId, "phone-1");
  assert.equal(tiles[0].kind, "session_kernel_vd");
  assert.equal(tiles[0].plane, "session_kernel_vd");
  assert.equal(tiles[0].inputOwner, "AI");

  const humanAdded = parseFleetWsEvent({
    event: "session.added",
    sessionId: "workspace-mail",
    displayId: 3,
    deviceId: "phone-1",
    inputOwner: "HUMAN",
  });
  assert.equal(humanAdded.sessionId, "workspace-mail");
  assert.equal(humanAdded.displayId, 3);
  assert.equal(humanAdded.inputOwner, "HUMAN");
  tiles = applySessionEvent(tiles, humanAdded);
  const mail = tiles.find((tile) => tile.sessionId === "workspace-mail");
  assert.ok(mail);
  assert.equal(mail.inputOwner, "HUMAN");
  assert.equal(mail.displayId, 3);

  const removed = parseFleetWsEvent({ event: "session.removed", sessionId: "workspace-chrome", deviceId: "phone-1", displayId: 2, owner: "AI" });
  assert.equal(removed.sessionId, "workspace-chrome");
  assert.equal(removed.displayId, 2);
  assert.equal(removed.owner, "AI");
  tiles = applySessionEvent(tiles, removed);
  assert.equal(tiles.some((tile) => tile.sessionId === "workspace-chrome"), false);
  assert.equal(tiles.length, 1);
  assert.equal(tiles[0].sessionId, "workspace-mail");
});

test("named display 0 / missing display never becomes 0", () => {
  const missing = bindSessionTile("phone-1", { sessionId: "workspace-maps", state: "RUNNING" });
  assert.equal(missing.displayId, undefined);
  assert.notEqual(missing.displayId, 0);
  assert.equal(sessionDisplayLabel(missing), "unknown (not display 0)");

  const zero = applySessionEvent([], parseFleetWsEvent({
    event: "session.added",
    sessionId: "workspace-chat",
    displayId: 0,
    deviceId: "phone-1",
    owner: "HUMAN",
  }));
  assert.equal(zero[0].kind, "session_kernel_vd");
  assert.equal(zero[0].displayId, undefined);
  assert.notEqual(zero[0].displayId, 0);
  assert.equal(sessionDisplayLabel(zero[0]), "unknown (not display 0)");
});

test("jpegFocusTarget refuses named rewrite to 0 and accepts display 2", () => {
  const named = bindSessionTile("phone-1", {
    sessionId: "workspace-mail",
    displayId: 2,
    state: "RUNNING",
  });
  assert.deepEqual(jpegFocusTarget(named), { sessionId: "workspace-mail", displayId: 2 });

  const missing = bindSessionTile("phone-1", { sessionId: "workspace-maps", state: "RUNNING" });
  assert.throws(() => jpegFocusTarget(missing), /display 0/);

  const layer2Like = bindSessionTile("phone-1", {
    sessionId: "workspace-chat",
    displayId: 0,
    state: "RUNNING",
  });
  assert.throws(() => jpegFocusTarget(layer2Like), /display 0/);

  const foreground = bindSessionTile("phone-1", {
    sessionId: DEFAULT_FOREGROUND_SESSION_ID,
    displayId: 0,
    state: "FOREGROUND",
  });
  assert.deepEqual(jpegFocusTarget(foreground), { sessionId: DEFAULT_FOREGROUND_SESSION_ID, displayId: 0 });
});

test("tileForSessionScope matches named VD by session_id+display_id and foreground by default-foreground", () => {
  const tiles = markSessionInventory([
    bindSessionTile("phone-1", { sessionId: "default-foreground", displayId: 0, state: "FOREGROUND" }),
    bindSessionTile("phone-1", { sessionId: "workspace-mail", displayId: 2, state: "RUNNING", inputOwner: "AI" }),
    bindSessionTile("phone-1", { sessionId: "workspace-maps", displayId: 3, state: "RUNNING" }),
  ]);
  const vd = tileForSessionScope(tiles, { session_id: "workspace-mail", display_id: 2 });
  assert.ok(vd);
  assert.equal(vd.sessionId, "workspace-mail");
  assert.equal(vd.displayId, 2);
  assert.equal(vd.kind, "session_kernel_vd");
  assert.notEqual(vd.kind, "foreground");
  assert.notEqual(vd.plane, "layer2");
  assert.notEqual(vd.displayId, 0);

  const foreground = tileForSessionScope(tiles, { sessionId: "default-foreground" });
  assert.ok(foreground);
  assert.equal(foreground.sessionId, "default-foreground");
  assert.equal(foreground.kind, FOREGROUND_KIND);
  assert.equal(foreground.plane, "foreground");
  assert.notEqual(foreground.kind, "session_kernel_vd");

  assert.equal(tileForSessionScope(tiles, { session_id: "workspace-mail", display_id: 0 }), null);
});

test("mock fleet emits session.added and session.removed without a gateway", async () => {
  const service = new MockDesktopService(1);
  const events = [];
  const unwatch = service.watchFleet((event) => { if (event) events.push(event); });
  const listed = await service.listDeviceSessions("phone-1");
  assert.ok(listed.sessions.some((session) => session.sessionId === "default-foreground"));
  assert.ok(listed.sessions.some((session) => session.sessionId === "workspace-phone-1" && session.displayId === 2));

  const started = await service.startDeviceSession("phone-1", "com.android.chrome");
  assert.equal(started.session.displayId > 0, true);
  assert.notEqual(started.session.displayId, 0);
  assert.equal(started.session.inputOwner, "AI");
  const added = events.find((event) => event.event === "session.added" && event.sessionId === started.session.sessionId);
  assert.ok(added);
  assert.equal(added.displayId, started.session.displayId);
  assert.equal(added.inputOwner, "AI");

  await service.stopDeviceSession("phone-1", started.session.sessionId);
  const removed = events.find((event) => event.event === "session.removed" && event.sessionId === started.session.sessionId);
  assert.ok(removed);
  assert.equal(removed.displayId, started.session.displayId);
  assert.equal(removed.inputOwner, "AI");
  unwatch();
});

test("per-session JPEG focus encodes session_id and displayId and never rewrites named VD to display 0", async () => {
  const service = new MockDesktopService(1);
  const frame = await service.snapshotDeviceSession("phone-1", "workspace-phone-1");
  assert.equal(frame.displayId, 2);
  assert.equal(frame.sessionId, "workspace-phone-1");
  const decoded = decodeURIComponent(frame.url);
  assert.match(decoded, /workspace-phone-1/);
  assert.match(decoded, /display 2/);
  assert.doesNotMatch(decoded, /display 0/);
  await assert.rejects(() => service.snapshotDeviceSession("phone-1", "default-foreground"), /unproven background preview/);
});

test("sendSessionControl yields and takes per tile and preserves PHONE_LOCKED", async () => {
  const service = new MockDesktopService(4);
  const take = await service.sendSessionControl("phone-1", "workspace-phone-1", "take_human");
  assert.equal(take.ok, true);
  assert.equal(take.verification, "HUMAN_HAS_CONTROL");
  assert.equal(take.inputOwner, "HUMAN");
  const listed = await service.listDeviceSessions("phone-1");
  const vd = listed.sessions.find((session) => session.sessionId === "workspace-phone-1");
  assert.equal(vd.inputOwner, "HUMAN");
  const yieldAi = await service.sendSessionControl("phone-1", "workspace-phone-1", "yield_ai");
  assert.equal(yieldAi.ok, true);
  assert.equal(yieldAi.verification, "AI_HAS_CONTROL");
  const locked = await service.sendSessionControl("phone-3", "workspace-phone-3", "yield_ai");
  assert.equal(locked.ok, false);
  assert.equal(locked.verification, "PHONE_LOCKED");
});

test("exact-session snapshot refuses foreground-substituted frames", () => {
  assert.throws(
    () => readExactSessionSnapshotHeaders({ get: () => null }),
    /unproven background preview/,
  );
  assert.throws(
    () => readExactSessionSnapshotHeaders({
      get(name) {
        if (name === "X-Cyclone-Foreground-Substitution") return "false";
        if (name === "X-Cyclone-Display-Id") return "0";
        return null;
      },
    }),
    /invalid display identity/,
  );
  const ok = readExactSessionSnapshotHeaders({
    get(name) {
      if (name === "X-Cyclone-Foreground-Substitution") return "false";
      if (name === "X-Cyclone-Display-Id") return "2";
      return null;
    },
  });
  assert.equal(ok.displayId, 2);
});

test("yield_ai and take_human control kinds are sent", async () => {
  const service = new MockDesktopService(4);
  const take = await service.sendControl("phone-1", { type: "take_human" });
  assert.equal(take.ok, true);
  assert.equal(take.verification, "HUMAN_HAS_CONTROL");
  assert.equal(take.inputOwner, "HUMAN");
  const yieldAi = await service.sendControl("phone-1", { type: "yield_ai" });
  assert.equal(yieldAi.ok, true);
  assert.equal(yieldAi.verification, "AI_HAS_CONTROL");
  assert.equal(yieldAi.inputOwner, "AI");
  const locked = await service.sendControl("phone-3", { type: "yield_ai" });
  assert.equal(locked.ok, false);
  assert.equal(locked.verification, "PHONE_LOCKED");
});

test("extra Session Kernel VD tiles are inventory, not extra hot Ask slots", () => {
  assert.equal(MAX_HOT_BACKGROUND_ASK, 1);
  const tiles = markSessionInventory([
    bindSessionTile("phone-1", { sessionId: "default-foreground", displayId: 0, state: "FOREGROUND" }),
    bindSessionTile("phone-1", { sessionId: "workspace-a", displayId: 2, state: "RUNNING" }),
    bindSessionTile("phone-1", { sessionId: "workspace-b", displayId: 3, state: "RUNNING" }),
  ]);
  assert.ok(tiles.length >= 2);
  assert.equal(tiles[0].inventory, false);
  assert.equal(tiles[1].hotAsk, true);
  assert.equal(tiles[1].inventory, false);
  assert.equal(tiles[1].kind, "session_kernel_vd");
  assert.equal(tiles[2].inventory, true);
  assert.equal(tiles[2].hotAsk, false);
  assert.equal(tiles[2].kind, "session_kernel_vd");
});

test("session tile copy names Session Kernel VD and not Layer 2", () => {
  assert.equal(SESSION_TILES_TITLE, "Session Kernel VD tiles");
  assert.match(SESSION_TILES_COPY, /session_id/);
  assert.match(SESSION_TILES_COPY, /displayId>0/);
  assert.match(SESSION_TILES_COPY, /HUMAN\/AI/);
  assert.match(SESSION_TILES_COPY, /not Layer 2/i);
  assert.match(SESSION_TILES_COPY, /display-0/);
  assert.doesNotMatch(SESSION_TILES_COPY, new RegExp(DEFAULT_FOREGROUND_SESSION_ID));
  assert.equal(FOREGROUND_PLANE_LABEL, "Foreground");
  assert.notEqual(FOREGROUND_PLANE_LABEL, VD_PLANE_LABEL);
  assert.match(FOREGROUND_PLANE_COPY, new RegExp(`session_id=${DEFAULT_FOREGROUND_SESSION_ID}`));
  assert.match(FOREGROUND_PLANE_COPY, /display 0/);
  assert.doesNotMatch(FOREGROUND_PLANE_COPY, /Layer 2/);
  assert.equal(
    MCP_FOREGROUND_SESSION_COPY,
    `MCP observe/act/locate require session_id=${DEFAULT_FOREGROUND_SESSION_ID} for the live human display (display 0).`,
  );
  assert.equal(normalizeInputOwner("human"), "HUMAN");
  assert.equal(normalizeInputOwner("AI"), "AI");
  assert.equal(normalizeInputOwner("other"), undefined);
  assert.equal(isSessionKernelVd({ plane: "layer2" }), false);
});
