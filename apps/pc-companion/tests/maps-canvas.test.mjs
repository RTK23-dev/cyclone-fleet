import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import {
  applyBoardFilters,
  coverageOf,
  inspectorState,
  toViewModel,
  validateAtlasDocument,
  validatePlaceSummary,
} from "../.test-dist/maps/atlasViewModel.js";
import {
  FACEBOOK_PLACE_ID,
  GMAIL_LIVE_SCREEN_COUNT,
  GMAIL_PLACE_ID,
  GMAIL_REQUIRED_ROOMS,
  UNMAPPED_PLACE_ID,
  getMockDocument,
  listMockDocuments,
  listMockSummaries,
} from "../.test-dist/maps/mockAtlas.js";
import {
  clampScale,
  computeFitTransform,
  EMPTY_START_LABEL,
  isFiniteTransform,
  panBy,
  zoomToward,
} from "../.test-dist/ui/appMapCanvas.js";
import { createMapsPage } from "../.test-dist/pages/mapsPage.js";

const here = dirname(fileURLToPath(import.meta.url));
const schema = JSON.parse(readFileSync(resolve(here, "../../../protocol/cyclone-atlas-v1.schema.json"), "utf8"));
const SECRET_PATTERN = /password|passcode|passwd|\botp\b|api[_-]?key|authorization|cookie|cvv|credential|typed_text|typed_value/i;
const EMAIL_PATTERN = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;

function collectStrings(value, out = []) {
  if (typeof value === "string") out.push(value);
  else if (Array.isArray(value)) value.forEach((item) => collectStrings(item, out));
  else if (value && typeof value === "object") Object.values(value).forEach((item) => collectStrings(item, out));
  return out;
}

test("mock atlas documents match cyclone-atlas-v1 required fields", () => {
  const required = schema.$defs.atlasDocument.required;
  assert.deepEqual(required, ["place", "persona", "mapStatus", "screens", "edges", "capabilities", "confidence", "lastObservedAt", "lastVerifiedAt"]);
  const documents = listMockDocuments();
  assert.ok(documents.length >= 6);
  for (const document of documents) {
    const issues = validateAtlasDocument(document);
    assert.deepEqual(issues, [], `${document.place.placeId} ${document.persona}: ${issues.map((item) => item.path).join(", ")}`);
    for (const key of required) assert.ok(key in document, `missing ${key}`);
  }
  for (const summary of [...listMockSummaries("live"), ...listMockSummaries("mapping")]) {
    assert.deepEqual(validatePlaceSummary(summary), []);
  }
});

test("mock Gmail house has the operator rooms and expected node count", () => {
  const gmail = getMockDocument(GMAIL_PLACE_ID, "live");
  assert.equal(gmail.screens.length, GMAIL_LIVE_SCREEN_COUNT);
  const labels = new Set(gmail.screens.map((screen) => screen.label));
  for (const room of GMAIL_REQUIRED_ROOMS) assert.ok(labels.has(room), `missing room ${room}`);
  const account = gmail.screens.find((screen) => screen.screenId === "gmail.account-switcher");
  assert.ok(account.factSlots.some((slot) => slot.name === "signed-in-email"));
  assert.equal(account.label, "Account");
  assert.ok(!EMAIL_PATTERN.test(JSON.stringify(account)));
  const model = toViewModel(gmail);
  assert.ok(model.screens.some((screen) => screen.region === "Account"));
  assert.ok(model.screens.some((screen) => screen.region === "Inbox"));
  assert.ok(model.screens.some((screen) => screen.region === "Compose"));
  assert.ok(model.edges.some((edge) => edge.actionHint === "open avatar"));
  assert.ok(model.edges.some((edge) => edge.actionHint === "compose"));
});

test("fixtures contain no password or secret plaintext", () => {
  for (const document of listMockDocuments()) {
    for (const text of collectStrings(document)) {
      assert.equal(SECRET_PATTERN.test(text), false, text);
      assert.equal(EMAIL_PATTERN.test(text), false, text);
    }
  }
});

test("toViewModel coverage math counts screens, doors, and dark rooms", () => {
  const gmail = getMockDocument(GMAIL_PLACE_ID, "live");
  const coverage = coverageOf(gmail);
  assert.equal(coverage.screens, gmail.screens.length);
  assert.equal(coverage.doors, gmail.edges.length);
  assert.equal(coverage.dark, gmail.screens.filter((screen) => screen.confidence < 0.5).length);
  assert.ok(coverage.dark >= 1);
  const model = toViewModel(gmail);
  assert.deepEqual(model.coverage, coverage);
  const dummy = toViewModel(getMockDocument(GMAIL_PLACE_ID, "mapping"));
  assert.equal(dummy.coverage.dark, 0);
  assert.equal(dummy.persona, "mapping");
});

test("AtlasViewModel can be supplied independently of the mock fixture", () => {
  const document = {
    place: { placeId: "package:com.example.mail", kind: "package", label: "Example Mail", packageName: "com.example.mail" },
    persona: "live",
    mapStatus: "mapped",
    screens: [
      {
        screenId: "ex.inbox",
        label: "Inbox",
        purpose: "inbox",
        factSlots: [{ name: "unread-badge", factType: "boolean", required: false, description: "Unread mark on the header." }],
        risk: { danger: false, classes: [] },
        confidence: 0.9,
        lastObservedAt: "2026-09-01T00:00:00Z",
        lastVerifiedAt: "2026-09-01T00:00:00Z",
        layout: { x: 40, y: 20 },
      },
      {
        screenId: "ex.compose",
        label: "Compose",
        purpose: "compose",
        factSlots: [],
        risk: { danger: false, classes: [] },
        confidence: 0.8,
        lastObservedAt: "2026-09-01T00:00:00Z",
        lastVerifiedAt: "2026-09-01T00:00:00Z",
        layout: { x: 320, y: 20 },
      },
    ],
    edges: [
      {
        edgeId: "ex.inbox.compose",
        fromScreenId: "ex.inbox",
        toScreenId: "ex.compose",
        actionHint: "compose",
        risk: { danger: false, classes: [] },
        confidence: 0.85,
        lastVerifiedAt: "2026-09-01T00:00:00Z",
      },
    ],
    capabilities: ["OPEN_COMPOSE"],
    confidence: 0.88,
    lastObservedAt: "2026-09-01T00:00:00Z",
    lastVerifiedAt: "2026-09-01T00:00:00Z",
  };
  assert.deepEqual(validateAtlasDocument(document), []);
  const model = toViewModel(document);
  assert.equal(model.screens.length, 2);
  assert.equal(model.coverage.doors, 1);
  assert.equal(model.place.label, "Example Mail");
  const inspector = inspectorState(model, "ex.inbox");
  assert.equal(inspector.kind, "screen");
  assert.equal(inspector.title, "Inbox");
  assert.equal(inspector.doors.length, 1);
  assert.equal(inspector.doors[0].actionHint, "compose");
  assert.equal(inspector.factSlots[0].maskedValue, "Masked");
});

test("selecting a card opens inspector state with masked slots and doors", () => {
  const model = toViewModel(getMockDocument(GMAIL_PLACE_ID, "live"));
  assert.equal(inspectorState(model, null).kind, "empty");
  const selected = inspectorState(model, "gmail.account-switcher");
  assert.equal(selected.kind, "screen");
  assert.equal(selected.title, "Account");
  assert.equal(selected.purpose, "account-switcher");
  assert.ok(selected.factSlots.some((slot) => slot.name === "signed-in-email" && slot.maskedValue === "Masked"));
  assert.ok(selected.doors.some((door) => door.actionHint === "add another account"));
  assert.equal(SECRET_PATTERN.test(JSON.stringify(selected)), false);
});

test("empty unmapped place produces an intentional Start mapping state", () => {
  const youtube = getMockDocument(UNMAPPED_PLACE_ID, "live");
  assert.equal(youtube.mapStatus, "unmapped");
  assert.equal(youtube.screens.length, 0);
  const model = toViewModel(youtube);
  assert.equal(model.coverage.screens, 0);
  assert.equal(EMPTY_START_LABEL, "Start mapping");
});

test("facebook live place is blocked without a login credential payload", () => {
  const facebook = getMockDocument(FACEBOOK_PLACE_ID, "live");
  assert.equal(facebook.mapStatus, "blocked");
  assert.ok(facebook.screens.some((screen) => screen.purpose === "login"));
  assert.ok(facebook.screens.some((screen) => screen.purpose === "feed"));
  assert.ok(facebook.screens.some((screen) => screen.purpose === "dm-list"));
  assert.equal(SECRET_PATTERN.test(JSON.stringify(facebook)), false);
});

test("fit and zoom helpers never produce NaN or Infinity", () => {
  const gmail = toViewModel(getMockDocument(GMAIL_PLACE_ID, "live"));
  const fitted = computeFitTransform(gmail.screens, { width: 1200, height: 800 });
  assert.equal(isFiniteTransform(fitted), true);
  const empty = computeFitTransform([], { width: 0, height: NaN });
  assert.equal(isFiniteTransform(empty), true);
  const broken = computeFitTransform([{ layout: { x: Number.NaN, y: Number.POSITIVE_INFINITY } }], { width: -4, height: 0 });
  assert.equal(isFiniteTransform(broken), true);
  const zoomed = zoomToward(fitted, Number.POSITIVE_INFINITY, { x: Number.NaN, y: 40 });
  assert.equal(isFiniteTransform(zoomed), true);
  const panned = panBy(fitted, Number.NaN, Number.NEGATIVE_INFINITY);
  assert.equal(isFiniteTransform(panned), true);
  assert.equal(Number.isFinite(clampScale(Number.NaN)), true);
  assert.equal(clampScale(99) <= 2.4, true);
  const filtered = applyBoardFilters(gmail, { stale: true, blocked: false, danger: true });
  const refit = computeFitTransform(filtered.screens, { width: 800, height: 600 });
  assert.equal(isFiniteTransform(refit), true);
  assert.ok(filtered.screens.every((screen) => Number.isFinite(screen.layout.x) && Number.isFinite(screen.layout.y)));
});

test("maps page smoke: rail, empty place, inspector select", () => {
  installMiniDom();
  const page = createMapsPage();
  assert.ok(page.element.className.includes("maps-page"));
  const coverage = page.element.querySelector(".maps-coverage");
  assert.ok(coverage.textContent.includes("screens"));
  assert.ok(page.element.querySelector(".map-card"));
  const youtube = [...page.element.querySelectorAll(".maps-place")].find((row) => row.textContent.includes("YouTube"));
  assert.ok(youtube);
  youtube.click();
  assert.equal(page.element.querySelector(".map-empty-title")?.textContent, "Start mapping");
  assert.equal(page.element.querySelector(".map-empty-action")?.disabled, true);
  assert.equal(page.element.querySelector(".map-empty-action")?.title, "mapper is phone alpha.3");
  const gmail = [...page.element.querySelectorAll(".maps-place")].find((row) => row.textContent.includes("Gmail"));
  gmail.click();
  const account = page.element.querySelector('[data-screen-id="gmail.account-switcher"]');
  assert.ok(account);
  account.click();
  const inspector = page.element.querySelector(".maps-inspector-body");
  assert.ok(inspector.textContent.includes("account-switcher"));
  assert.ok(inspector.textContent.includes("Masked"));
  assert.ok(inspector.textContent.includes("open avatar") === false || inspector.textContent.includes("add another account"));
  const start = page.element.querySelector(".maps-start");
  assert.equal(start.disabled, true);
  assert.equal(start.title, "Connect a Mobile 5 phone to map");
  page.destroy();
});
