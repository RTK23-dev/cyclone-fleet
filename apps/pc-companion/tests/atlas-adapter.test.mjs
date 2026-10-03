import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ASK_NEEDS_SECRET_FIXTURE } from "../.test-dist/core/fleet.js";
import { toMapsDocument, loadMapsDataSourceFromClient } from "../.test-dist/maps/atlasClientAdapter.js";

const EMPTY_ATLAS = JSON.parse(
  readFileSync(new URL("./fixtures/atlas-empty-valid.json", import.meta.url), "utf8"),
);

const PLACE = "package:com.example.app";
const SECRET_VALUE = "hunter2";

function emptyServicesDocument(overrides = {}) {
  return {
    place: {
      placeId: PLACE,
      kind: "package",
      label: "app",
      packageName: "com.example.app",
    },
    persona: "live",
    mapStatus: "unmapped",
    screens: [],
    edges: [],
    capabilities: [],
    confidence: 0,
    lastObservedAt: null,
    lastVerifiedAt: null,
    ...overrides,
  };
}

test("needs-secret fixture title is honest password-wall copy, not login-status", () => {
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.title, "Facebook needs a password");
  assert.doesNotMatch(ASK_NEEDS_SECRET_FIXTURE.title, /login status/i);
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.state, "needs-secret");
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.slotLabel, "Facebook password");
  const blob = JSON.stringify(ASK_NEEDS_SECRET_FIXTURE);
  assert.doesNotMatch(blob, /hunter2|otp\s*[:=]|cookie\s*[:=]|token\s*[:=]/i);
});

test("toMapsDocument maps a minimal empty-screens atlas document", () => {
  const mapped = toMapsDocument(EMPTY_ATLAS);
  assert.equal(mapped.place.placeId, PLACE);
  assert.equal(mapped.place.kind, "package");
  assert.equal(mapped.place.packageName, "com.example.app");
  assert.equal(mapped.persona, "live");
  assert.equal(mapped.mapStatus, "unmapped");
  assert.deepEqual(mapped.screens, []);
  assert.deepEqual(mapped.edges, []);
  assert.deepEqual(mapped.capabilities, []);
  assert.equal(mapped.confidence, 0);
  assert.equal(mapped.lastObservedAt, null);
  assert.equal(mapped.lastVerifiedAt, null);
  assert.equal("password" in mapped, false);
  assert.equal("value" in mapped, false);
});

test("toMapsDocument drops unknown extras and does not copy a password string value", () => {
  const poisoned = emptyServicesDocument({
    password: SECRET_VALUE,
    token: SECRET_VALUE,
    extraUnknown: "keep-me-off-the-board",
    screens: [
      {
        screenId: "example.login",
        label: "Login",
        purpose: "login",
        password: SECRET_VALUE,
        factSlots: [
          {
            name: "session-status",
            factType: "boolean",
            required: true,
            description: "Whether a session is already open.",
            value: SECRET_VALUE,
            password: SECRET_VALUE,
          },
        ],
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastObservedAt: null,
        lastVerifiedAt: null,
        layout: { x: 8, y: 16 },
      },
    ],
  });

  const mapped = toMapsDocument(poisoned);
  const blob = JSON.stringify(mapped);
  assert.doesNotMatch(blob, new RegExp(SECRET_VALUE));
  assert.equal("password" in mapped, false);
  assert.equal("token" in mapped, false);
  assert.equal("extraUnknown" in mapped, false);
  assert.equal(mapped.screens.length, 1);
  assert.equal(mapped.screens[0].screenId, "example.login");
  assert.equal(mapped.screens[0].purpose, "login");
  assert.equal("password" in mapped.screens[0], false);
  assert.equal(mapped.screens[0].factSlots.length, 1);
  const slot = mapped.screens[0].factSlots[0];
  assert.deepEqual(Object.keys(slot).sort(), ["description", "factType", "name", "required"]);
  assert.equal(slot.name, "session-status");
  assert.equal("value" in slot, false);
  assert.equal("password" in slot, false);
  assert.equal(slot.password, undefined);
});

test("loadMapsDataSourceFromClient builds a sync source from a fake client", async () => {
  const calls = [];
  const document = emptyServicesDocument();
  const fakeClient = {
    usingDemoGraph: false,
    async places() {
      calls.push("places");
      return {
        places: [
          {
            place: document.place,
            persona: "live",
            mapStatus: "unmapped",
            confidence: 0,
            lastObservedAt: null,
            lastVerifiedAt: null,
          },
        ],
      };
    },
    async get(placeId, persona) {
      calls.push(`get:${placeId}:${persona}`);
      return emptyServicesDocument({
        place: { ...document.place, placeId },
        persona,
      });
    },
    async secretsSlots() {
      calls.push("secretsSlots");
      return { slots: {} };
    },
    async secretsRequest() {
      calls.push("secretsRequest");
      return { status: "waiting" };
    },
  };

  const source = await loadMapsDataSourceFromClient(fakeClient, "live");
  assert.deepEqual(calls, ["places", `get:${PLACE}:live`]);
  assert.equal(typeof source.listSummaries, "function");
  assert.equal(typeof source.getDocument, "function");

  const summaries = source.listSummaries("live");
  assert.equal(summaries.then, undefined);
  assert.equal(summaries.length, 1);
  assert.equal(summaries[0].place.placeId, PLACE);
  assert.equal(summaries[0].persona, "live");
  assert.equal(summaries[0].mapStatus, "unmapped");

  const loaded = source.getDocument(PLACE, "live");
  assert.equal(loaded.then, undefined);
  assert.equal(loaded.place.placeId, PLACE);
  assert.deepEqual(loaded.screens, []);
  assert.deepEqual(loaded.edges, []);
  assert.equal(source.getDocument(PLACE, "mapping"), null);
  assert.equal(source.listSummaries("mapping").length, 0);
  assert.ok(!calls.includes("secretsSlots"));
  assert.ok(!calls.includes("secretsRequest"));
});
