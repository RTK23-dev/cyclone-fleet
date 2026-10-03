import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { pcStateLabel, searchListings } from "../.test-dist/services/market.js";
import { createMarketPage } from "../.test-dist/pages/marketPage.js";

const listing = (id, extra = {}) => ({
  id, kind: "recipe", version: "1.0.0", name: id.replace("cyclone.", ""), publisher: { id: "cyclone", name: "Cyclone", verified: true },
  summary: `About ${id}`, category: "Daily", glyph: "★", goal: "Do it", inputs: [], apps: [], does: ["Reads your notifications"],
  asksFirst: [], suggestFor: [], featured: false, added: false, savedInputs: null, runs: 0, lastRunAt: null, ...extra,
});
const TIMER = listing("cyclone.focus-timer", {
  featured: true, goal: "Set a timer for {minutes} minutes and turn on Do Not Disturb.",
  inputs: [{ name: "minutes", label: "Minutes", kind: "number", default: "25", choices: [], required: true }],
});
const SEND = listing("cyclone.send-whatsapp", { asksFirst: ["Sending the message"], added: true, savedInputs: { person: "Sam", message: "hi" },
  inputs: [{ name: "person", label: "To", kind: "text", default: "", choices: [], required: true }, { name: "message", label: "Message", kind: "text", default: "", choices: [], required: true }] });
const CATALOG = {
  listings: [TIMER, SEND, listing("cyclone.inbox-brief", { featured: true })],
  suggestions: [{ id: "cyclone.inbox-brief", reason: "Because you use Gmail" }],
  installedCount: 1,
  connections: [{ id: "openrouter", name: "OpenRouter", glyph: "✦", state: "connected", detail: "Thinking with x/y", where: "phone" }],
};
const PC = { available: true, reason: null, connections: [
  { id: "codex", name: "Codex", description: "Coding agent", state: "connected", detected: true, configured: true },
  { id: "grok", name: "Grok", description: "Grok on this PC", state: "detected", detected: true, configured: false },
] };

function ctx(fetch) {
  const devices = [parseDevice(READY_DEVICE)];
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.26", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}

test("the marketplace route belongs to its own section; helpers read the gateway's data", () => {
  assert.deepEqual(parseRoute("#/market"), { name: "market" });
  assert.equal(routeHref({ name: "market" }), "#/market");
  assert.equal(sectionOf({ name: "market" }), "market");
  assert.deepEqual(searchListings(CATALOG.listings, "notifications").length, 3);
  assert.deepEqual(searchListings(CATALOG.listings, "timer").map((l) => l.id), ["cyclone.focus-timer"]);
  assert.equal(pcStateLabel("detected").label, "Found on this PC");
});

test("the store shows featured, for you with its reason, the phone's and this PC's connections", async () => {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/market": () => CATALOG, "GET /v1/pc/connections": () => PC });
  const page = createMarketPage(ctx(gateway.fetch));
  await flush();
  const text = page.element.textContent;
  assert.equal(page.element.querySelectorAll(".market-feature").length, 2);
  assert.match(text, /Because you use Gmail/);
  assert.match(text, /1 installed ›/);
  assert.match(text, /Thinking with x\/y/);
  assert.match(text, /Codex/);
  assert.match(text, /Found on this PC/);
  assert.equal(page.element.querySelectorAll("button").filter((b) => b.textContent === "Connect").length, 1, "only agents found but not connected offer Connect");
  page.destroy();
});

test("adding shows every disclosure first and sends the inputs to the phone; running refusals are shown", async () => {
  installMiniDom();
  let runs = 0;
  const gateway = fakeGateway({
    "GET /v1/devices/d1/market": () => CATALOG,
    "GET /v1/pc/connections": () => PC,
    "POST /v1/devices/d1/market/cyclone.focus-timer/install": ({ body }) => ({ id: "cyclone.focus-timer", added: true, inputs: body.inputs }),
    "POST /v1/devices/d1/market/cyclone.send-whatsapp/install": ({ body }) => ({ id: "cyclone.send-whatsapp", added: true, inputs: body.inputs }),
    "POST /v1/devices/d1/market/cyclone.send-whatsapp/run": () => {
      runs += 1;
      return new Response(JSON.stringify({ detail: { code: "ASK_BUSY", message: "Cyclone is busy with another task." } }), { status: 409 });
    },
  });
  const page = createMarketPage(ctx(gateway.fetch));
  await flush();
  page.element.querySelector(".market-feature").click();
  const sheet = page.element.querySelector(".market-sheet");
  assert.match(sheet.textContent, /What it does/);
  assert.match(sheet.textContent, /Asks you first/);
  const minutes = sheet.querySelector("[data-input=minutes]");
  assert.equal(minutes.value, "25");
  minutes.value = "15";
  minutes.dispatchEvent({ type: "input" });
  sheet.querySelectorAll("button").find((b) => b.textContent === "Add to phone").click();
  await flush();
  assert.deepEqual(gateway.calls.find((c) => c.path.endsWith("focus-timer/install")).body, { inputs: { minutes: "15" } });
  assert.match(page.element.textContent, /Focus-timer was added|focus-timer was added/);

  page.element.querySelectorAll(".market-row").find((r) => r.dataset.id === "cyclone.send-whatsapp").querySelector("button").click();
  const sendSheet = page.element.querySelector(".market-sheet");
  assert.match(sendSheet.textContent, /Sending the message/);
  sendSheet.querySelectorAll("button").find((b) => b.textContent === "Run on phone").click();
  await flush();
  assert.equal(runs, 1);
  assert.match(page.element.querySelector(".market-message").textContent, /busy/);
  page.destroy();
});
