import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import {
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
} from "../.test-dist/core/fleet.js";
import { toViewModel } from "../.test-dist/maps/atlasViewModel.js";
import {
  GMAIL_PLACE_ID,
  GMAIL_REQUIRED_ROOMS,
  listMockDocuments,
  mockMapsDataSource,
} from "../.test-dist/maps/mockAtlas.js";
import {
  emptyMapsDataSource,
  loadMapsDataSourceFromClient,
  loadPhoneAtlasSource,
  MAPS_DEMO_LABEL,
  MAPS_EMPTY_ATLAS_TITLE,
  MAPS_LOADING_TITLE,
  namedMapsLoadError,
  toMapsDocument,
} from "../.test-dist/maps/phoneAtlasSource.js";
import { createMapsPage } from "../.test-dist/pages/mapsPage.js";

const here = dirname(fileURLToPath(import.meta.url));
const SECRET_PATTERN = /password|passcode|passwd|\botp\b|api[_-]?key|authorization|cookie|cvv|credential|typed_text|typed_value/i;
const EMAIL_PATTERN = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;
const PLAINTEXT = /hunter2|otp\s*[:=]|cookie\s*[:=]|token\s*[:=]/i;

function collectStrings(value, out = []) {
  if (typeof value === "string") out.push(value);
  else if (Array.isArray(value)) value.forEach((item) => collectStrings(item, out));
  else if (value && typeof value === "object") Object.values(value).forEach((item) => collectStrings(item, out));
  return out;
}

function flush() {
  return delay(0);
}

function sourceFromDocument(document) {
  return {
    listSummaries(persona) {
      if (persona !== document.persona) return [];
      return [{
        place: document.place,
        persona: document.persona,
        mapStatus: document.mapStatus,
        confidence: document.confidence,
        lastObservedAt: document.lastObservedAt,
        lastVerifiedAt: document.lastVerifiedAt,
      }];
    },
    getDocument(placeId, persona) {
      if (placeId !== document.place.placeId || persona !== document.persona) return null;
      return structuredClone(document);
    },
  };
}

function partialDocument() {
  return {
    place: {
      placeId: "package:com.example.mail",
      kind: "package",
      label: "Example Mail",
      packageName: "com.example.mail",
    },
    persona: "live",
    mapStatus: "partial",
    screens: [
      {
        screenId: "ex.inbox",
        label: "Inbox",
        purpose: "inbox",
        factSlots: [{ name: "unread-badge", factType: "boolean", required: false, description: "Unread mark on the header." }],
        risk: { danger: false, classes: [] },
        confidence: 0.86,
        lastObservedAt: "2026-09-01T00:00:00Z",
        lastVerifiedAt: "2026-09-01T00:00:00Z",
        layout: { x: 40, y: 20 },
      },
    ],
    edges: [],
    capabilities: ["OPEN_INBOX"],
    confidence: 0.6,
    lastObservedAt: "2026-09-01T00:00:00Z",
    lastVerifiedAt: "2026-09-01T00:00:00Z",
  };
}

function unmappedDocument() {
  return {
    place: {
      placeId: "package:com.example.app",
      kind: "package",
      label: "Example",
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
  };
}

test("demo: true shows mock Gmail rooms and a visible (demo) banner", () => {
  installMiniDom();
  const page = createMapsPage({ demo: true });
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Gmail");
  assert.ok(page.element.querySelector(".map-card"), "Mini house rooms must render in demo");
  const labels = [...page.element.querySelectorAll(".map-card-title")].map((node) => node.textContent);
  for (const room of GMAIL_REQUIRED_ROOMS) assert.ok(labels.includes(room), `missing demo room ${room}`);
  const banner = page.element.querySelector(".maps-demo-banner");
  assert.ok(banner);
  assert.equal(banner.hidden, false);
  assert.match(banner.textContent, /\(demo\)/);
  assert.match(page.element.textContent, new RegExp(MAPS_DEMO_LABEL.replace(/[()]/g, "\\$&")));
  assert.equal(page.element.querySelector(".maps-start")?.disabled, true);
  assert.equal(page.element.querySelector(".maps-start")?.title, "Connect a Mobile 5 phone to map");
  page.destroy();
});

test("phoneVersion 4.8.0 shows update-the-phone copy and not a live Gmail house", () => {
  installMiniDom();
  const page = createMapsPage({ phoneVersion: "4.8.0" });
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_TITLE));
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_COPY.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Maps");
  assert.equal(page.element.querySelector(".map-card"), null);
  const names = [...page.element.querySelectorAll(".maps-place-name")].map((node) => node.textContent);
  assert.equal(names.includes("Gmail"), false);
  assert.doesNotMatch(page.element.querySelector(".maps-place-title")?.textContent ?? "", /^Gmail$/);
  assert.equal(page.element.querySelector(".maps-demo-banner"), null);
  assert.equal(page.element.querySelector(".maps-start")?.disabled, true);
  page.destroy();
});

test("explicit phoneVersion null is update-the-phone, omitted version keeps mock default", () => {
  installMiniDom();
  const omitted = createMapsPage();
  assert.equal(omitted.element.querySelector(".maps-place-title")?.textContent, "Gmail");
  assert.ok(omitted.element.querySelector(".map-card"));
  assert.equal(omitted.element.querySelector(".maps-demo-banner"), null);
  assert.doesNotMatch(omitted.element.querySelector(".glass-compat-banner")?.textContent ?? "", /Update Cyclone on the phone/);
  omitted.destroy();

  const explicitNull = createMapsPage({ phoneVersion: null });
  assert.match(explicitNull.element.textContent, /Update Cyclone on the phone/);
  assert.equal(explicitNull.element.querySelector(".map-card"), null);
  assert.equal(explicitNull.element.querySelector(".maps-place-title")?.textContent, "Maps");
  explicitNull.destroy();
});

test("phone 5.0 empty loadSource is honest empty, not a crash or mock Gmail", async () => {
  installMiniDom();
  let resolveSource;
  const loadSource = () => new Promise((resolve) => {
    resolveSource = resolve;
  });
  const page = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource,
  });
  assert.match(page.element.textContent, new RegExp(MAPS_LOADING_TITLE));
  assert.equal(page.element.querySelector(".map-card"), null);
  resolveSource(emptyMapsDataSource());
  await flush();
  assert.match(page.element.textContent, new RegExp(MAPS_EMPTY_ATLAS_TITLE));
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Maps");
  assert.equal(page.element.querySelector(".map-card"), null);
  const names = [...page.element.querySelectorAll(".maps-place-name")].map((node) => node.textContent);
  assert.equal(names.includes("Gmail"), false);
  assert.equal(page.element.querySelector(".maps-start")?.disabled, true);
  assert.equal(page.element.querySelector(".maps-start")?.title, "Connect a Mobile 5 phone to map");
  page.destroy();
});

test("phone 5.0 unmapped place without mapping ops keeps Start mapping disabled and says why", async () => {
  installMiniDom();
  const page = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => sourceFromDocument(unmappedDocument()),
  });
  await flush();
  assert.equal(page.element.querySelector(".map-empty-title")?.textContent, "Start mapping");
  assert.equal(page.element.querySelector(".map-empty-action")?.disabled, true);
  assert.equal(page.element.querySelector(".map-empty-action")?.title, "mapper is phone alpha.3");
  assert.equal(page.element.querySelector(".maps-start")?.disabled, true);
  assert.equal(page.element.querySelector(".maps-start")?.title, "Connect a Mobile 5 phone to map");
  assert.equal(page.element.querySelector(".maps-status.unmapped")?.textContent, "Not mapped");
  page.destroy();
});

test("phone 5.0 without loadSource is honest empty, not mock", () => {
  installMiniDom();
  const page = createMapsPage({ phoneVersion: "5.0.0-alpha.1" });
  assert.match(page.element.textContent, new RegExp(MAPS_EMPTY_ATLAS_TITLE));
  assert.equal(page.element.querySelector(".map-card"), null);
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Maps");
  page.destroy();
});

test("loadSource document with mapStatus partial stays partial in UI and view-model", async () => {
  installMiniDom();
  const document = partialDocument();
  const page = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => sourceFromDocument(document),
  });
  await flush();
  const model = toViewModel(document);
  assert.equal(model.mapStatus, "partial");
  assert.ok(model.screens.length > 0);
  assert.notEqual(model.mapStatus, "mapped");
  const chip = page.element.querySelector(".maps-status.partial");
  assert.ok(chip);
  assert.equal(chip.textContent, "Partial");
  assert.equal(page.element.querySelector(".maps-status.mapped"), null);
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Example Mail");
  assert.ok(page.element.querySelector(".map-card"));
  page.destroy();
});

test("loadSource SESSION_REQUIRED is named English, not a blank crash", async () => {
  installMiniDom();
  const coded = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => {
      throw { code: "SESSION_REQUIRED" };
    },
  });
  await flush();
  assert.match(coded.element.textContent, /SESSION_REQUIRED/);
  assert.match(coded.element.textContent, /session_id is required/i);
  assert.equal(coded.element.querySelector(".map-card"), null);
  coded.destroy();

  const named = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => {
      const error = new Error("atlas.get refused");
      error.name = "SESSION_REQUIRED";
      throw error;
    },
  });
  await flush();
  assert.match(named.element.textContent, /SESSION_REQUIRED/);
  named.destroy();

  const human = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => {
      throw { code: "HUMAN_HAS_CONTROL", message: "Companion currently owns input." };
    },
  });
  await flush();
  assert.match(human.element.textContent, /HUMAN_HAS_CONTROL/);
  human.destroy();

  const network = createMapsPage({
    phoneVersion: "5.0.0-alpha.1",
    loadSource: async () => {
      const error = new Error("Failed to fetch");
      error.name = "TypeError";
      throw error;
    },
  });
  await flush();
  assert.match(network.element.textContent, /NETWORK/);
  network.destroy();
});

test("fixtures still contain no password plaintext", () => {
  for (const document of listMockDocuments()) {
    for (const text of collectStrings(document)) {
      assert.equal(SECRET_PATTERN.test(text), false, text);
      assert.equal(EMAIL_PATTERN.test(text), false, text);
      assert.equal(PLAINTEXT.test(text), false, text);
    }
  }
  const adapter = readFileSync(resolve(here, "../src/maps/atlasClientAdapter.ts"), "utf8");
  const sourceHelper = readFileSync(resolve(here, "../src/maps/phoneAtlasSource.ts"), "utf8");
  const pageSource = readFileSync(resolve(here, "../src/pages/mapsPage.ts"), "utf8");
  for (const blob of [adapter, sourceHelper, pageSource]) {
    assert.doesNotMatch(blob, /hunter2/);
    assert.doesNotMatch(blob, /otp\s*[:=]|cookie\s*[:=]/i);
  }
  for (const blob of [JSON.stringify(partialDocument()), JSON.stringify(unmappedDocument()), JSON.stringify(emptyMapsDataSource().listSummaries("live"))]) {
    assert.doesNotMatch(blob, PLAINTEXT);
    assert.doesNotMatch(blob, /hunter2/);
  }
  const mapped = toMapsDocument({
    place: { placeId: "package:com.example.app", kind: "package", label: "app", packageName: "com.example.app" },
    persona: "live",
    mapStatus: "partial",
    screens: [{
      screenId: "ex.login",
      label: "Login",
      purpose: "login",
      factSlots: [{ name: "session-status", factType: "boolean", required: true, description: "Whether a session is already open.", value: "hunter2" }],
      risk: { danger: false, classes: [] },
      confidence: 0.4,
      lastObservedAt: null,
      lastVerifiedAt: null,
      layout: { x: 0, y: 0 },
    }],
    edges: [],
    capabilities: [],
    confidence: 0.4,
    lastObservedAt: null,
    lastVerifiedAt: null,
  });
  assert.equal(mapped.mapStatus, "partial");
  assert.doesNotMatch(JSON.stringify(mapped), /hunter2/);
});

test("namedMapsLoadError and empty source helper stay honest", () => {
  assert.equal(namedMapsLoadError({ code: "SESSION_REQUIRED" }).code, "SESSION_REQUIRED");
  assert.equal(namedMapsLoadError({ code: "PHONE_VERSION_UNSUPPORTED" }).code, "PHONE_VERSION_UNSUPPORTED");
  assert.equal(namedMapsLoadError({ code: "HUMAN_HAS_CONTROL" }).code, "HUMAN_HAS_CONTROL");
  const empty = emptyMapsDataSource();
  assert.deepEqual(empty.listSummaries("live"), []);
  assert.equal(empty.getDocument(GMAIL_PLACE_ID, "live"), null);
  assert.equal(typeof loadPhoneAtlasSource, "function");
  assert.equal(typeof loadMapsDataSourceFromClient, "function");
});

test("default createMapsPage() still mounts the mock Gmail path", () => {
  installMiniDom();
  const page = createMapsPage();
  assert.equal(page.element.querySelector(".maps-place-title")?.textContent, "Gmail");
  assert.ok(page.element.querySelector(".map-card"));
  assert.equal(mockMapsDataSource.getDocument(GMAIL_PLACE_ID, "live")?.place.label, "Gmail");
  page.destroy();
});
