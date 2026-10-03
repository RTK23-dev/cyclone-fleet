import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  applyDeviceMobileVersion,
  createGlassRuntime,
  GLASS_OPERATOR_REQUEST_REASON,
  readDeviceMobileVersion,
  resolveGlassSessionId,
  sessionPlaneFromSessionId,
  slotsFromPresence,
} from "../.test-dist/services/glassRuntime.js";
import { AtlasClientError } from "../.test-dist/services/atlasClient.js";
import { phoneSupportsGlassAtlas } from "../.test-dist/core/fleet.js";
import { DEFAULT_FOREGROUND_SESSION_ID } from "../.test-dist/core/sessionTiles.js";

const PLACE = "package:com.example.app";
const EMPTY_ATLAS = JSON.parse(
  readFileSync(new URL("./fixtures/atlas-empty-valid.json", import.meta.url), "utf8"),
);
const appSource = readFileSync(new URL("../src/app.ts", import.meta.url), "utf8");
const settingsSource = readFileSync(new URL("../src/pages/settingsPage.ts", import.meta.url), "utf8");
const runtimeSource = readFileSync(new URL("../src/services/glassRuntime.ts", import.meta.url), "utf8");
const httpSource = readFileSync(new URL("../src/services/httpDesktopService.ts", import.meta.url), "utf8");
const mockSource = readFileSync(new URL("../src/services/mockDesktopService.ts", import.meta.url), "utf8");

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function runtime(overrides = {}) {
  const calls = [];
  const fetchImpl = overrides.fetch ?? (async (url, init = {}) => {
    calls.push({ url: String(url), init });
    const href = String(url);
    if (href.includes("/secrets/slots")) {
      return jsonResponse({
        placeId: PLACE,
        persona: "live",
        slots: { password: true },
      });
    }
    if (href.includes("/secrets/request")) {
      return jsonResponse({ status: "waiting" });
    }
    if (href.includes("/atlas/places")) {
      return jsonResponse({
        places: [{
          place: EMPTY_ATLAS.place,
          persona: "live",
          mapStatus: "unmapped",
          confidence: 0,
          lastObservedAt: null,
          lastVerifiedAt: null,
        }],
      });
    }
    return jsonResponse(overrides.body ?? EMPTY_ATLAS);
  });
  const glass = createGlassRuntime({
    httpBase: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getDeviceId: () => "phone-1",
    getSessionId: () => "workspace-mail",
    getPhoneVersion: () => "5.0.0-alpha.1",
    fetch: fetchImpl,
    ...overrides,
    fetch: fetchImpl,
  });
  return { glass, calls };
}

test("resolveGlassSessionId preserves a named id and never rewrites vd-mail", () => {
  assert.equal(resolveGlassSessionId("vd-mail"), "vd-mail");
  assert.equal(resolveGlassSessionId(" workspace-mail "), "workspace-mail");
  assert.equal(resolveGlassSessionId(DEFAULT_FOREGROUND_SESSION_ID), DEFAULT_FOREGROUND_SESSION_ID);
  assert.notEqual(resolveGlassSessionId("vd-mail"), DEFAULT_FOREGROUND_SESSION_ID);
  assert.doesNotMatch(resolveGlassSessionId("vd-mail"), /default-foreground|^0$/);
});

test("resolveGlassSessionId uses default-foreground when the focused id is blank", () => {
  assert.equal(resolveGlassSessionId(""), DEFAULT_FOREGROUND_SESSION_ID);
  assert.equal(resolveGlassSessionId("   "), DEFAULT_FOREGROUND_SESSION_ID);
  assert.equal(resolveGlassSessionId(null), DEFAULT_FOREGROUND_SESSION_ID);
  assert.equal(resolveGlassSessionId(undefined), DEFAULT_FOREGROUND_SESSION_ID);
  assert.equal(resolveGlassSessionId(), DEFAULT_FOREGROUND_SESSION_ID);
});

test("sessionPlaneFromSessionId is foreground only for default-foreground", () => {
  assert.equal(sessionPlaneFromSessionId("vd-mail"), "session_kernel_vd");
  assert.equal(sessionPlaneFromSessionId(" workspace-mail "), "session_kernel_vd");
  assert.equal(sessionPlaneFromSessionId(""), "foreground");
  assert.equal(sessionPlaneFromSessionId("   "), "foreground");
  assert.equal(sessionPlaneFromSessionId(null), "foreground");
  assert.equal(sessionPlaneFromSessionId(undefined), "foreground");
  assert.equal(sessionPlaneFromSessionId(DEFAULT_FOREGROUND_SESSION_ID), "foreground");
  assert.notEqual(sessionPlaneFromSessionId("vd-mail"), "foreground");
  assert.notEqual(sessionPlaneFromSessionId("vd-mail"), "layer2");
});

test("missing session → loadMapsSource does not fetch", async () => {
  let fetched = false;
  const glass = createGlassRuntime({
    httpBase: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getDeviceId: () => "phone-1",
    getSessionId: () => "",
    getPhoneVersion: () => "5.0.0-alpha.1",
    fetch: async () => {
      fetched = true;
      return jsonResponse(EMPTY_ATLAS);
    },
  });
  await assert.rejects(() => glass.loadMapsSource("live"), (error) => {
    assert.equal(error instanceof AtlasClientError, true);
    assert.equal(error.code, "SESSION_REQUIRED");
    assert.doesNotMatch(error.message, /display 0|default-foreground/);
    return true;
  });
  assert.equal(fetched, false);
});

test("slotsFromPresence allows boolean password and drops hunter2", () => {
  const allowed = slotsFromPresence(PLACE, "Example", { password: true });
  assert.equal(allowed.length, 1);
  assert.equal(allowed[0].id, `${PLACE}:password`);
  assert.equal(allowed[0].placeLabel, "Example");
  assert.equal(allowed[0].slotLabel, "password");
  assert.equal(allowed[0].set, true);
  assert.equal(typeof allowed[0].set, "boolean");
  assert.equal("value" in allowed[0], false);

  const dropped = slotsFromPresence(PLACE, "Example", { password: "hunter2" });
  assert.equal(dropped.length, 0);
  assert.doesNotMatch(JSON.stringify(dropped), /hunter2/);

  const mixed = slotsFromPresence(PLACE, "Example", { password: true, otp: "hunter2", cookie: 1 });
  assert.equal(mixed.length, 1);
  assert.equal(mixed[0].slotLabel, "password");
  assert.doesNotMatch(JSON.stringify(mixed), /hunter2/);
});

test("loadVaultSlots maps boolean presence and never returns a secret string", async () => {
  const { glass, calls } = runtime();
  const slots = await glass.loadVaultSlots(PLACE, "live", "Example");
  assert.equal(slots.length, 1);
  assert.equal(slots[0].set, true);
  assert.equal(slots[0].slotLabel, "password");
  assert.doesNotMatch(JSON.stringify(slots), /hunter2|"value"/);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/secrets\/slots/);
  assert.match(calls[0].url, /session_id=workspace-mail/);
});

test("useDemoGraph is false on the Glass runtime factory", () => {
  const { glass } = runtime();
  assert.equal(glass.atlas.usingDemoGraph, false);
  assert.match(runtimeSource, /useDemoGraph:\s*false/);
  assert.doesNotMatch(runtimeSource, /useDemoGraph:\s*true/);
});

test("requestSecret body omits session_id and never sends a secret value", async () => {
  const { glass, calls } = runtime();
  const result = await glass.requestSecret(PLACE, "live", "password", GLASS_OPERATOR_REQUEST_REASON);
  assert.equal(result.status, "waiting");
  assert.equal(calls.length, 1);
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(body, {
    placeId: PLACE,
    persona: "live",
    slot: "password",
    reason: GLASS_OPERATOR_REQUEST_REASON,
  });
  assert.equal("session_id" in body, false);
  assert.equal("sessionId" in body, false);
  assert.equal("value" in body, false);
  assert.doesNotMatch(JSON.stringify(body), /hunter2/);
});

test("4.8.0 is not atlas-ready", () => {
  assert.equal(phoneSupportsGlassAtlas("4.8.0"), false);
  assert.equal(phoneSupportsGlassAtlas("5.0.0-alpha.1"), true);
  assert.equal(phoneSupportsGlassAtlas(""), false);
});

test("readDeviceMobileVersion maps appVersion and fails closed when absent", () => {
  assert.equal(readDeviceMobileVersion({ mobileVersion: "5.0.0-alpha.1" }), "5.0.0-alpha.1");
  assert.equal(readDeviceMobileVersion({ appVersion: "4.8.0" }), "4.8.0");
  assert.equal(readDeviceMobileVersion({ version: "5.0" }), "5.0");
  assert.equal(readDeviceMobileVersion({ id: "phone-1" }), undefined);
  assert.equal(readDeviceMobileVersion(null), undefined);
  const normalized = applyDeviceMobileVersion({ id: "phone-1", appVersion: "4.8.0" });
  assert.equal(normalized.mobileVersion, "4.8.0");
});

test("app.ts source mounts loadSource, mobileVersion, and glassRuntime", () => {
  assert.match(appSource, /loadSource/);
  assert.match(appSource, /mobileVersion/);
  assert.match(appSource, /phoneVersion/);
  assert.match(appSource, /createGlassRuntime|glassRuntime/);
  assert.match(appSource, /previewSlots/);
  assert.match(appSource, /onRequestSlot/);
  assert.match(appSource, /GLASS_OPERATOR_REQUEST_REASON|operator-request/);
  assert.match(appSource, /sessionPlane/);
  assert.match(appSource, /onOpenControl/);
  assert.match(appSource, /previewSnapshots/);
  assert.match(appSource, /focusedSessionId/);
  assert.match(appSource, /session_kernel_vd/);
  assert.match(appSource, /focus_session/);
  assert.doesNotMatch(appSource, /atlasClient/);
  assert.doesNotMatch(appSource, /mapping\.start/);
  assert.doesNotMatch(appSource, /ask\.start/);
});

test("settingsPage opens Cyclone Glass in the browser and keeps the plane rules", () => {
  // Glass is the local browser dashboard (owner charter 2026-09-23); One links to it instead of hosting it.
  assert.match(settingsSource, /Open Cyclone Glass/);
  assert.match(settingsSource, /invoke<string>\("open_glass", \{ path \}\)/);
  assert.match(settingsSource, /needs Mobile 5\.0 on the phone/);
  assert.match(settingsSource, /Cyclone One/);
  assert.match(settingsSource, /createRemoteMcpCard/);
  assert.match(settingsSource, /Foreground \(default-foreground\)/);
  assert.match(settingsSource, /Session Kernel VD/);
  assert.match(settingsSource, /never rewritten to display 0/);
});

test("HttpDesktopService surfaces glassGateway; mock omits it and leaves version unset", () => {
  assert.match(httpSource, /glassGateway/);
  assert.match(httpSource, /getBearer:\s*\(\)\s*=>\s*this\.token/);
  assert.match(httpSource, /applyDeviceMobileVersion/);
  assert.doesNotMatch(httpSource, /console\.(log|info|debug).*token/);
  assert.doesNotMatch(mockSource, /glassGateway/);
  assert.doesNotMatch(mockSource, /mobileVersion:\s*"5/);
});

test("runtime module has no secret values", () => {
  assert.doesNotMatch(runtimeSource, /hunter2/);
  assert.doesNotMatch(runtimeSource, /type\s*=\s*["']password["']/);
  assert.match(runtimeSource, /useDemoGraph: false/);
});
