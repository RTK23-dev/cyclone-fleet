import test from "node:test";
import assert from "node:assert/strict";
import { deviceReadiness, majorVersion, parseDevice, pickDevice } from "../.test-dist/services/devices.js";

const base = { deviceId: "d1", name: "Pixel 8", model: "Pixel 8", state: "READY", paired: true, planes: { aiTrust: "TRUSTED" }, mobileVersion: "5.0.0-alpha.7.dev1" };

test("parses the gateway device shape and ignores rows without an id", () => {
  assert.equal(parseDevice({ ...base, deviceId: "" }), null);
  assert.equal(parseDevice(null), null);
  const device = parseDevice(base);
  assert.equal(device.id, "d1");
  assert.equal(device.aiTrust, "TRUSTED");
  assert.equal(parseDevice({ id: "x" }).name, "Android phone");
  const connecting = parseDevice({ ...base, source: "LAN", trust: { sessionReady: true, matchCode: "173715" } });
  assert.equal(connecting.transport, "LAN");
  assert.equal(connecting.sessionReady, true);
  assert.equal(connecting.matchCode, "173715");
  assert.equal(parseDevice({ ...base, trust: { matchCode: "12ab" } }).matchCode, null);
});

test("readiness fails closed: disconnected, unpaired, unknown and old versions", () => {
  assert.deepEqual(deviceReadiness(parseDevice(base)), { ready: true });
  assert.equal(deviceReadiness(parseDevice({ ...base, state: "DISCONNECTED" })).reason, "disconnected");
  assert.equal(deviceReadiness(parseDevice({ ...base, paired: false })).reason, "unpaired");
  assert.equal(deviceReadiness(parseDevice({ ...base, mobileVersion: undefined, trust: { sessionReady: true } })).reason, "version-unknown");
  // Trusted before but the session is not open: say why, in the gateway's words (the "Waiting for Cyclone" bug).
  const locked = deviceReadiness(parseDevice({ ...base, mobileVersion: undefined, trust: { state: "TRUSTED", sessionReady: false, lastSafeError: "Unlock the phone to restore AI/Codex access." } }));
  assert.equal(locked.reason, "connecting");
  assert.match(locked.message, /Unlock the phone/);
  const reopening = deviceReadiness(parseDevice({ ...base, mobileVersion: undefined }));
  assert.equal(reopening.reason, "connecting");
  const gatewayOff = parseDevice({ ...base, mobileVersion: undefined, trust: { sessionReady: true }, health: { planes: { gateway: { ready: false, message: "Cyclone Mobile gateway is reconnecting." } } } });
  assert.match(deviceReadiness(gatewayOff).message, /gateway is reconnecting/);
  const old = deviceReadiness(parseDevice({ ...base, mobileVersion: "4.8.0" }));
  assert.equal(old.reason, "needs-update");
  assert.match(old.message, /4\.8\.0/);
  assert.equal(majorVersion("10.1"), 10);
  assert.equal(majorVersion("v5"), null);
});

test("device picking keeps the chosen phone, else prefers a ready one", () => {
  const ready = parseDevice({ ...base, deviceId: "ready" });
  const old = parseDevice({ ...base, deviceId: "old", mobileVersion: "4.8.0" });
  assert.equal(pickDevice([old, ready], "old").id, "old");
  assert.equal(pickDevice([old, ready], "gone").id, "ready");
  assert.equal(pickDevice([old], null).id, "old");
  assert.equal(pickDevice([], "x"), null);
});

test("a PC the phone logged out is told so and offered Connect", () => {
  const revoked = parseDevice({ deviceId: "d1", name: "Pixel", state: "READY", paired: true, planes: { aiTrust: "REVOKED" }, mobileVersion: "5.0.0-alpha.16.dev1" });
  const readiness = deviceReadiness(revoked);
  assert.equal(readiness.reason, "unpaired");
  assert.match(readiness.message, /logged this PC out/);
});

test("the name the phone shows for this PC is parsed and bounded", () => {
  const device = parseDevice({ deviceId: "d1", name: "Pixel", state: "READY", paired: true, planes: { aiTrust: "TRUSTED" }, trust: { pcLabel: "DESK-PC" } });
  assert.equal(device.pcLabel, "DESK-PC");
  assert.equal(parseDevice({ deviceId: "d1", name: "Pixel", state: "READY", paired: true, planes: {}, trust: { pcLabel: 5 } }).pcLabel, null);
});

import { liveViewProblem } from "../.test-dist/services/devices.js";

test("live view explains the real USB reason, not a generic one", () => {
  const withUsb = (reasonCode, source = "USB") =>
    parseDevice({ deviceId: "d1", name: "Pixel", state: "READY", paired: true, source, planes: { aiTrust: "TRUSTED" }, health: { planes: { usbAuthorization: { ready: reasonCode === "USB_AUTHORIZED", reasonCode } } } });
  assert.equal(withUsb("USB_ABSENT").usb, "USB_ABSENT");
  assert.match(liveViewProblem(withUsb("USB_UNAUTHORIZED")), /Allow USB debugging/);
  assert.match(liveViewProblem(withUsb("USB_OFFLINE")), /offline/);
  assert.match(liveViewProblem(withUsb("USB_ABSENT")), /data cable/);
  assert.match(liveViewProblem(withUsb("USB_ABSENT", "LAN")), /Wi‑Fi/);
  assert.match(liveViewProblem(withUsb("USB_AUTHORIZED")), /video did not start/);
  assert.equal(parseDevice({ deviceId: "d1", state: "READY" }).usb, null);
});
