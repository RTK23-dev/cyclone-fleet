import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import {
  applyBoardFilters,
  DARK_CONFIDENCE,
  inspectorState,
  inspectorStateForEdge,
  purposeGlyph,
  toViewModel,
} from "../.test-dist/maps/atlasViewModel.js";
import {
  GMAIL_PLACE_ID,
  getMockDocument,
} from "../.test-dist/maps/mockAtlas.js";
import { createMapsPage } from "../.test-dist/pages/mapsPage.js";

const SECRET_PATTERN = /password|passcode|passwd|\botp\b|api[_-]?key|authorization|cookie|cvv|credential|typed_text|typed_value/i;
const EMAIL_PATTERN = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;
const XPATH_PATTERN = /\/\/|xpath|android\.widget|resource-id/i;

function chipByLabel(page, label) {
  return [...page.element.querySelectorAll(".maps-chip")].find((node) => node.textContent === label);
}

function cardTitles(page) {
  return [...page.element.querySelectorAll(".map-card-title")].map((node) => node.textContent);
}

test("sessionId vd-mail on Session Kernel VD is displayed and not rewritten to default-foreground", () => {
  installMiniDom();
  const page = createMapsPage({
    sessionId: "vd-mail",
    sessionPlane: "session_kernel_vd",
  });
  const plane = page.element.querySelector(".maps-session-plane");
  assert.ok(plane);
  assert.match(plane.textContent, /Session Kernel VD/);
  assert.match(plane.textContent, /session_id vd-mail/);
  assert.match(page.element.textContent, /vd-mail/);
  assert.equal(plane.textContent.includes("default-foreground"), false);
  assert.equal(page.element.textContent.includes("default-foreground"), false);
  page.destroy();
});

test("omitted sessionId shows default-foreground on the Foreground plane", () => {
  installMiniDom();
  const page = createMapsPage();
  const plane = page.element.querySelector(".maps-session-plane");
  assert.ok(plane);
  assert.match(plane.textContent, /Foreground/);
  assert.match(plane.textContent, /session_id default-foreground/);
  page.destroy();
});

test("empty sessionId still displays default-foreground", () => {
  installMiniDom();
  const page = createMapsPage({ sessionId: "", sessionPlane: "foreground" });
  const plane = page.element.querySelector(".maps-session-plane");
  assert.match(plane.textContent, /Foreground · session_id default-foreground/);
  page.destroy();
});

test("Take control is disabled without onOpenControl and enabled callback fires", () => {
  installMiniDom();
  const missing = createMapsPage();
  const idle = missing.element.querySelector(".maps-take-control");
  assert.ok(idle);
  assert.equal(idle.disabled, true);
  assert.match(idle.title, /Phone live handoff/i);
  assert.match(idle.title, /not mapping pause/i);
  missing.destroy();

  let hits = 0;
  const armed = createMapsPage({ onOpenControl: () => { hits += 1; } });
  const control = armed.element.querySelector(".maps-take-control");
  assert.equal(control.disabled, false);
  control.click();
  assert.equal(hits, 1);
  armed.destroy();
});

test("clicking open avatar renders English edge inspector, not xpath", () => {
  installMiniDom();
  const page = createMapsPage();
  const edge = page.element.querySelector('[data-edge-id="gmail.inbox.avatar"]');
  assert.ok(edge, "mock Gmail open-avatar door must be clickable");
  edge.click();
  const inspector = page.element.querySelector(".maps-inspector-body");
  assert.ok(inspector.textContent.includes("open avatar"));
  assert.ok(inspector.textContent.includes("Inbox"));
  assert.ok(inspector.textContent.includes("Account"));
  assert.match(inspector.textContent, /→/);
  assert.match(inspector.textContent, /confidence/i);
  assert.match(inspector.textContent, /last verified/i);
  assert.equal(XPATH_PATTERN.test(inspector.textContent), false);
  assert.equal(SECRET_PATTERN.test(inspector.textContent), false);
  assert.equal(EMAIL_PATTERN.test(inspector.textContent), false);
  const pin = [...inspector.querySelectorAll("button")].find((node) => node.textContent === "Pin");
  const remap = [...inspector.querySelectorAll("button")].find((node) => node.textContent === "Remap this room");
  const never = [...inspector.querySelectorAll("button")].find((node) => node.textContent === "Never");
  assert.equal(pin?.disabled, true);
  assert.equal(remap?.disabled, true);
  assert.equal(never?.disabled, true);
  page.destroy();
});

test("screen inspector still works after the edge inspector", () => {
  installMiniDom();
  const page = createMapsPage();
  page.element.querySelector('[data-edge-id="gmail.inbox.avatar"]').click();
  const account = page.element.querySelector('[data-screen-id="gmail.account-switcher"]');
  account.click();
  const inspector = page.element.querySelector(".maps-inspector-body");
  assert.ok(inspector.textContent.includes("account-switcher"));
  assert.ok(inspector.textContent.includes("Masked"));
  assert.equal(inspector.textContent.includes("xpath"), false);
  page.destroy();
});

test("Dark doors chip keeps rooms with a dark outgoing door and hides high-confidence-only rooms", () => {
  installMiniDom();
  const page = createMapsPage();
  const before = cardTitles(page);
  assert.ok(before.includes("Compose"));
  assert.ok(before.includes("Inbox"));
  const dark = chipByLabel(page, "Dark doors");
  assert.ok(dark);
  dark.click();
  const after = cardTitles(page);
  assert.equal(after.includes("Compose"), false, "high-confidence-only Compose must hide");
  assert.equal(after.includes("Account"), false);
  assert.ok(after.includes("Inbox"), "Inbox has the dark open-search door");
  assert.ok(after.includes("Search"), "dark-door destination stays so the door can render");
  const remainingEdges = [...page.element.querySelectorAll(".map-edge")].map((node) => node.getAttribute("data-edge-id"));
  assert.ok(remainingEdges.includes("gmail.inbox.search"));
  assert.equal(remainingEdges.includes("gmail.inbox.compose"), false);
  page.destroy();
});

test("Start mapping stays disabled without a connected phone", () => {
  installMiniDom();
  const page = createMapsPage();
  const start = page.element.querySelector(".maps-start");
  assert.equal(start.disabled, true);
  assert.equal(start.title, "Connect a Mobile 5 phone to map");
  const youtube = [...page.element.querySelectorAll(".maps-place")].find((row) => row.textContent.includes("YouTube"));
  youtube.click();
  assert.equal(page.element.querySelector(".map-empty-action")?.disabled, true);
  assert.equal(page.element.querySelector(".maps-start")?.disabled, true);
  page.destroy();
});

test("capability glyphs on cards are CSS initials, not Mini passed 8/8", () => {
  installMiniDom();
  const page = createMapsPage();
  const glyphs = [...page.element.querySelectorAll(".map-glyph")].map((node) => node.textContent);
  assert.ok(glyphs.length > 0);
  assert.ok(glyphs.includes(purposeGlyph("inbox")));
  assert.ok(glyphs.includes(purposeGlyph("compose")));
  assert.equal(page.element.textContent.includes("passed 8/8"), false);
  assert.equal(page.element.textContent.includes("8/8"), false);
  page.destroy();
});

test("applyBoardFilters unmappedDoors-only keeps dark doors; no-chip path is unchanged", () => {
  const model = toViewModel(getMockDocument(GMAIL_PLACE_ID, "live"));
  const untouched = applyBoardFilters(model, { stale: false, blocked: false, danger: false });
  assert.equal(untouched.screens.length, model.screens.length);
  const dark = applyBoardFilters(model, { stale: false, blocked: false, danger: false, unmappedDoors: true });
  assert.ok(dark.screens.length < model.screens.length);
  assert.ok(dark.screens.some((screen) => screen.label === "Inbox"));
  assert.equal(dark.screens.some((screen) => screen.label === "Compose"), false);
  assert.ok(dark.edges.every((edge) => edge.confidence < DARK_CONFIDENCE));
  assert.ok(dark.edges.some((edge) => edge.actionHint === "open search"));
  const mixed = applyBoardFilters(model, { stale: false, blocked: false, danger: true, unmappedDoors: true });
  assert.ok(mixed.screens.some((screen) => screen.label === "Storage offer"));
  assert.ok(mixed.screens.some((screen) => screen.label === "Inbox"));
});

test("inspectorStateForEdge uses English actionHint and never passwords", () => {
  const model = toViewModel(getMockDocument(GMAIL_PLACE_ID, "live"));
  const state = inspectorStateForEdge(model, "gmail.inbox.avatar");
  assert.equal(state.kind, "edge");
  assert.equal(state.title, "open avatar");
  assert.equal(state.fromLabel, "Inbox");
  assert.equal(state.toLabel, "Account");
  assert.equal(XPATH_PATTERN.test(JSON.stringify(state)), false);
  assert.equal(SECRET_PATTERN.test(JSON.stringify(state)), false);
  assert.equal(EMAIL_PATTERN.test(JSON.stringify(state)), false);
  const screen = inspectorState(model, "gmail.account-switcher");
  assert.equal(screen.kind, "screen");
  assert.equal(screen.title, "Account");
});
