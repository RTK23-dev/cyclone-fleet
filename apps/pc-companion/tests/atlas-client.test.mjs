import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  ATLAS_DOCUMENT_KEYS,
  createAtlasClient,
  DEMO_ATLAS_DISABLED_WHEN_PHONE_V5,
  parseAtlasDocument,
  parseSlotPresence,
  supportsGlassAtlas,
  AtlasClientError,
  SECRET_PAYLOAD_REJECTED,
  assertNoSecretValues,
  SecretPayloadRejectedError,
} from "../.test-dist/services/atlasClient.js";

const EMPTY_ATLAS = JSON.parse(
  readFileSync(new URL("./fixtures/atlas-empty-valid.json", import.meta.url), "utf8"),
);

const PLACE = "package:com.example.app";
const GMAIL = "package:com.google.android.gm";
const CHROME = "chrome:https://example.com";

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function client(overrides = {}) {
  const calls = [];
  const fetchImpl = overrides.fetch ?? (async (url, init = {}) => {
    calls.push({ url: String(url), init });
    return jsonResponse(overrides.body ?? EMPTY_ATLAS);
  });
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    fetch: fetchImpl,
    ...overrides,
    fetch: fetchImpl,
  });
  return { atlas, calls };
}

test("fixture atlas document satisfies required keys", () => {
  for (const key of ATLAS_DOCUMENT_KEYS) {
    assert.ok(key in EMPTY_ATLAS, `missing ${key}`);
  }
  const document = parseAtlasDocument(EMPTY_ATLAS);
  assert.equal(document.place.placeId, PLACE);
  assert.equal(document.persona, "live");
  assert.equal(document.mapStatus, "unmapped");
  assert.deepEqual(document.screens, []);
  assert.deepEqual(document.edges, []);
  assert.deepEqual(document.capabilities, []);
  assert.equal(document.lastObservedAt, null);
  assert.equal(document.lastVerifiedAt, null);
});

test("get() on empty valid document succeeds", async () => {
  const { atlas, calls } = client({ body: EMPTY_ATLAS });
  const document = await atlas.get(PLACE, "live");
  assert.equal(document.mapStatus, "unmapped");
  assert.equal(document.screens.length, 0);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/v1\/devices\/phone-1\/atlas\?/);
  assert.match(calls[0].url, /placeId=package%3Acom\.example\.app/);
  assert.match(calls[0].url, /persona=live/);
  assert.match(calls[0].url, /session_id=workspace-mail/);
  assert.equal(calls[0].init.headers["X-Cyclone-Session-Id"], "workspace-mail");
  assert.equal(calls[0].init.headers.Authorization, "Bearer test-token");
});

test("session_id omitted → SESSION_REQUIRED and fetch is not called", async () => {
  let fetched = false;
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "  ",
    getDeviceId: () => "phone-1",
    fetch: async () => {
      fetched = true;
      return jsonResponse(EMPTY_ATLAS);
    },
  });
  await assert.rejects(() => atlas.get(PLACE, "live"), (error) => {
    assert.equal(error instanceof AtlasClientError, true);
    assert.equal(error.code, "SESSION_REQUIRED");
    assert.doesNotMatch(error.message, /display 0|default-foreground/);
    return true;
  });
  assert.equal(fetched, false);
});

test("named session_id is not rewritten to default-foreground or display 0", async () => {
  const { atlas, calls } = client();
  await atlas.get(PLACE, "live");
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /session_id=workspace-mail/);
  assert.doesNotMatch(calls[0].url, /default-foreground/);
  assert.doesNotMatch(calls[0].url, /display_id=0/);
  assert.equal(calls[0].init.headers["X-Cyclone-Session-Id"], "workspace-mail");
});

test("payload with password value is rejected and not logged", async () => {
  const secret = "hunter2";
  const lines = [];
  const original = {
    log: console.log,
    warn: console.warn,
    error: console.error,
    info: console.info,
  };
  console.log = (...args) => lines.push(args.map(String).join(" "));
  console.warn = (...args) => lines.push(args.map(String).join(" "));
  console.error = (...args) => lines.push(args.map(String).join(" "));
  console.info = (...args) => lines.push(args.map(String).join(" "));
  try {
    const poisoned = { ...EMPTY_ATLAS, password: secret };
    assert.throws(() => assertNoSecretValues(poisoned), SecretPayloadRejectedError);
    const { atlas } = client({ body: poisoned });
    await assert.rejects(() => atlas.get(PLACE, "live"), (error) => {
      assert.equal(error instanceof SecretPayloadRejectedError || error.code === SECRET_PAYLOAD_REJECTED, true);
      assert.doesNotMatch(String(error.message), new RegExp(secret));
      return true;
    });
    assert.ok(!lines.some((line) => line.includes(secret)), "secret value must not be logged");
    if (typeof localStorage !== "undefined") {
      assert.equal(localStorage.getItem("password"), null);
    }
  } finally {
    Object.assign(console, original);
  }
});

test("secrets.slots { password: true } is allowed boolean presence", async () => {
  const payload = {
    placeId: PLACE,
    persona: "live",
    slots: { password: true, otp: false },
  };
  const parsed = parseSlotPresence(payload);
  assert.deepEqual(parsed.slots, { password: true, otp: false });
  const { atlas, calls } = client({ body: payload });
  const result = await atlas.secretsSlots(PLACE, "live");
  assert.equal(result.slots.password, true);
  assert.equal(result.slots.otp, false);
  assert.match(calls[0].url, /\/v1\/devices\/phone-1\/secrets\/slots/);
});

test("secrets.slots rejects a string value behind a slot name", () => {
  assert.throws(
    () => parseSlotPresence({
      placeId: PLACE,
      persona: "live",
      slots: { password: "hunter2" },
    }),
    SecretPayloadRejectedError,
  );
});

test("supportsGlassAtlas version gate", () => {
  assert.equal(supportsGlassAtlas("4.8.0"), false);
  assert.equal(supportsGlassAtlas("4.9.9"), false);
  assert.equal(supportsGlassAtlas("5.0.0-alpha.1"), true);
  assert.equal(supportsGlassAtlas("5.0.0"), true);
  assert.equal(supportsGlassAtlas("v5.1.0"), true);
  assert.equal(supportsGlassAtlas(""), false);
});

test("Mobile < 5 fails closed instead of calling atlas ops or substituting demo", async () => {
  let fetched = false;
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    getPhoneVersion: () => "4.8.0",
    fetch: async () => {
      fetched = true;
      return jsonResponse(EMPTY_ATLAS);
    },
  });
  await assert.rejects(() => atlas.get(PLACE, "live"), (error) => {
    assert.equal(error.code, "PHONE_VERSION_UNSUPPORTED");
    assert.match(error.message, /Update the phone/);
    return true;
  });
  assert.equal(fetched, false);
  assert.equal(atlas.usingDemoGraph, false);
});

test("demo graph is explicit, default off, and labeled demo", async () => {
  assert.equal(DEMO_ATLAS_DISABLED_WHEN_PHONE_V5, true);
  let fetched = false;
  const live = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    fetch: async () => {
      fetched = true;
      return jsonResponse(EMPTY_ATLAS);
    },
  });
  assert.equal(live.usingDemoGraph, false);
  await live.get(PLACE, "live");
  assert.equal(fetched, true);

  const demo = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    useDemoGraph: true,
    fetch: async () => {
      throw new Error("demo path must not hit the gateway");
    },
  });
  assert.equal(demo.usingDemoGraph, true);
  const catalog = await demo.places();
  assert.equal(catalog.places[0].place.label.includes("(demo)"), true);
  const document = await demo.get(GMAIL, "live");
  assert.equal(document.place.label, "Gmail (demo)");
  assert.equal(document.capabilities.includes("DemoGraph"), true);
});

test("chrome-origin placeId is accepted", async () => {
  const body = {
    place: {
      placeId: CHROME,
      kind: "chrome-origin",
      label: "example.com",
      origin: "https://example.com",
    },
    persona: "mapping",
    mapStatus: "unmapped",
    screens: [],
    edges: [],
    capabilities: [],
    confidence: 0,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
  const { atlas } = client({ body });
  const document = await atlas.get(CHROME, "mapping");
  assert.equal(document.place.kind, "chrome-origin");
  assert.equal(document.persona, "mapping");
});

test("4xx is mapped honestly and HUMAN_HAS_CONTROL stays named", async () => {
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    fetch: async () => jsonResponse(
      { detail: { code: "HUMAN_HAS_CONTROL", message: "Companion currently owns input." } },
      409,
    ),
  });
  await assert.rejects(() => atlas.get(PLACE, "live"), (error) => {
    assert.equal(error.code, "HUMAN_HAS_CONTROL");
    assert.equal(error.status, 409);
    assert.match(error.message, /owns input/);
    return true;
  });
});

test("secrets.request maps needs-secret to waiting and does not put session_id in the JSON body", async () => {
  const calls = [];
  const atlas = createAtlasClient({
    baseUrl: "http://127.0.0.1:8765",
    getBearer: () => "test-token",
    getSessionId: () => "workspace-mail",
    getDeviceId: () => "phone-1",
    fetch: async (url, init = {}) => {
      calls.push({ url: String(url), init });
      return jsonResponse({
        state: "needs-secret",
        request: { placeId: PLACE, persona: "live", slot: "password", reason: "Login required" },
      });
    },
  });
  const result = await atlas.secretsRequest(PLACE, "live", "password", "Login required");
  assert.deepEqual(result, { status: "waiting" });
  assert.equal(calls[0].init.method, "POST");
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(body, { placeId: PLACE, persona: "live", slot: "password", reason: "Login required" });
  assert.equal("session_id" in body, false);
  assert.equal("sessionId" in body, false);
  assert.equal("password" in body && body.password !== "password", false);
  assert.match(calls[0].url, /session_id=workspace-mail/);
  assert.match(calls[0].url, /\/v1\/devices\/phone-1\/secrets\/request/);
});

const JOB = {
  mappingJobId: "map-0123456789abcdef",
  placeId: PLACE,
  persona: "mapping",
  state: "running",
  sessionId: "default-foreground",
  displayId: 0,
  plane: { kind: "foreground", sessionId: "default-foreground", displayId: 0 },
  controlRevision: 1,
  executionGeneration: null,
  budget: { maxNewScreens: 12, maxElapsedMs: 180000, maxConsecutiveNonProgress: 6, maxAttemptsPerDoor: 2 },
  currentAtlasNodeId: "screen:settings:0123456789abcdef",
  progress: { newScreens: 3, verifiedMutations: 4, consecutiveNonProgress: 0, attemptedDoors: 4, remainingDarkRegions: 0 },
  atlasStatus: null,
  danger: null,
  boundary: null,
  startedAtEpochMs: 1,
  updatedAtEpochMs: 2,
  failureCode: null,
};

function foregroundClient(body) {
  return client({ getSessionId: () => "default-foreground", body });
}

test("mapping.start commands a mapping-persona pass on the foreground plane", async () => {
  const { atlas, calls } = foregroundClient(JOB);
  const job = await atlas.mappingStart(PLACE);
  assert.equal(job.state, "running");
  assert.equal(job.currentAtlasNodeId, "screen:settings:0123456789abcdef");
  assert.equal(job.newScreens, 3);
  assert.match(calls[0].url, /\/v1\/devices\/phone-1\/mapping\/start/);
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(body, {
    placeId: PLACE,
    persona: "mapping",
    budget: { maxNewScreens: 12, maxElapsedMs: 180000, maxConsecutiveNonProgress: 6, maxAttemptsPerDoor: 2 },
    sessionId: "default-foreground",
    displayId: 0,
  });
});

test("mapping resume sends only the job id and plane identity", async () => {
  const { atlas, calls } = foregroundClient(JOB);
  await atlas.mappingResume(JOB.mappingJobId);
  assert.deepEqual(JSON.parse(calls[0].init.body), {
    resumeJobId: JOB.mappingJobId,
    sessionId: "default-foreground",
    displayId: 0,
  });
});

test("Glass never guesses a named workspace display for mapping", async () => {
  const { atlas, calls } = client({ body: JOB });
  await assert.rejects(() => atlas.mappingStart(PLACE), (error) => error.code === "SESSION_DISPLAY_MISMATCH");
  assert.equal(calls.length, 0);
});

test("websites and demo mode cannot start mapping", async () => {
  const { atlas } = foregroundClient(JOB);
  await assert.rejects(() => atlas.mappingStart(CHROME), (error) => error.code === "PLACE_NOT_LAUNCHABLE");
  const demo = client({ getSessionId: () => "default-foreground", useDemoGraph: true }).atlas;
  await assert.rejects(() => demo.mappingStart(PLACE), (error) => error.code === "DEMO_MODE");
});

test("mapping status rejects malformed jobs and secret-shaped payloads", async () => {
  await assert.rejects(
    () => foregroundClient({ ...JOB, state: "walking" }).atlas.mappingStatus(JOB.mappingJobId),
    (error) => error.code === "PROTOCOL_MISMATCH",
  );
  await assert.rejects(
    () => foregroundClient({ ...JOB, currentAtlasNodeId: "Louella's inbox" }).atlas.mappingStatus(JOB.mappingJobId),
    (error) => error.code === "PROTOCOL_MISMATCH",
  );
  await assert.rejects(
    () => foregroundClient({ ...JOB, password: "hunter2" }).atlas.mappingStatus(JOB.mappingJobId),
    (error) => error.code === SECRET_PAYLOAD_REJECTED,
  );
});

test("atlas.diff passes the phone cursor and parses structural changes", async () => {
  const { atlas, calls } = foregroundClient({
    placeId: PLACE,
    persona: "mapping",
    since: "c1:0123456789abcdef0123:1",
    cursor: "c1:0123456789abcdef0123:2",
    resyncRequired: false,
    changes: [{ cursor: "c1:0123456789abcdef0123:2", entity: "screen", change: "upsert", id: "screen:list:0123456789abcdef", layout: { x: 0, y: 0 } }],
  });
  const diff = await atlas.atlasDiff(PLACE, "mapping", "c1:0123456789abcdef0123:1");
  assert.match(calls[0].url, /atlas\/diff\?placeId=package%3Acom\.example\.app&persona=mapping&since=c1%3A/);
  assert.equal(diff.cursor, "c1:0123456789abcdef0123:2");
  assert.equal(diff.changes.length, 1);
  assert.equal(diff.changes[0].entity, "screen");
});
