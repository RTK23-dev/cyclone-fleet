import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { formatNumber, originLabel, parseOverview } from "../.test-dist/services/numbers.js";
import { createNumbersView } from "../.test-dist/pages/numbersView.js";

installMiniDom();
const NOW = 1_800_000_000_000;
const DAY = 86_400_000;
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

const overview = (over = {}) => ({
  summary: { total: 3, byOrigin: { phone: 1, plugin: 0, rental: 2, other: 0 }, ready: 2, attention: 1, expiringSoon: 1, unassigned: 2 },
  numbers: [
    { id: "num_a", number: "+31612345678", label: "Main", origin: "phone", source: { kind: "phone", ref: "pixel", name: "Pixel 8", provider: null, slot: 1 },
      state: "ready", why: "", paused: false, expiresAt: null, expiresSoon: false, account: { id: "acc_food", service: "Instagram", handle: "brand.food" }, seenAt: NOW - 60_000, notes: "" },
    { id: "num_b", number: "+447700900123", label: "Fitness", origin: "rental", source: { kind: "rental", ref: "sms-forwarder", name: "sms-forwarder", provider: "Acme Numbers", slot: null },
      state: "ready", why: "", paused: false, expiresAt: NOW + 3 * DAY, expiresSoon: true, account: null, seenAt: null, notes: "" },
    { id: "num_c", number: "+447700900999", label: "", origin: "rental", source: { kind: "rental", ref: null, name: null, provider: "Acme Numbers", slot: null },
      state: "no_source", why: "Pick the plugin that forwards this number's texts.", paused: false, expiresAt: null, expiresSoon: false, account: null, seenAt: null, notes: "" },
  ],
  phones: [
    { deviceId: "pixel", name: "Pixel 8", state: "ready", hint: "", numbers: 1 },
    { deviceId: "galaxy", name: "Galaxy A54", state: "no_permission", hint: "On the phone: Settings → Permissions → Codes → allow Read texts.", numbers: 0 },
  ],
  plugins: [{ name: "sms-forwarder", status: "active", title: "SMS forwarder" }],
  accounts: [{ id: "acc_food", service: "Instagram", handle: "brand.food" }, { id: "acc_fit", service: "Instagram", handle: "brand.fit" }],
  at: NOW,
  ...over,
});

const deps = { now: () => NOW, later: () => () => {} };
const ctx = (gw) => ({ client: new GatewayClient({ token: "t", fetch: gw.fetch }) });

test("the overview parses defensively and labels each number's origin", () => {
  const parsed = parseOverview({ ...overview(), numbers: [{ id: "x", number: "+1555000111", origin: "weird", state: "nope" }, "junk", { id: "" }] });
  assert.equal(parsed.numbers.length, 1);
  assert.equal(parsed.numbers[0].origin, "other");
  assert.equal(parsed.numbers[0].state, "manual");
  const good = parseOverview(overview());
  assert.equal(originLabel(good.numbers[0]), "Pixel 8 · SIM 1");
  assert.equal(originLabel(good.numbers[1]), "Rented · Acme Numbers · via sms-forwarder");
  assert.equal(formatNumber("+31612345678"), "+31 612 345 678");
  assert.equal(formatNumber("+447700900123"), "+44 770 090 0123");
  assert.equal(formatNumber("0612345678"), "061 234 5678");
});

test("the page shows the totals, the phones to set up and every number with where its texts arrive", async () => {
  const gw = fakeGateway({ "GET /v1/numbers": () => overview() });
  const view = createNumbersView(ctx(gw), () => {}, deps);
  await flush();
  const text = view.element.textContent;
  assert.match(text, /Ready for codes/);
  assert.match(text, /Rentals ending in 7 days/);
  assert.match(text, /Phones to set up/);
  assert.match(text, /Galaxy A54/);
  assert.match(text, /allow Read texts/);
  assert.match(text, /\+31 612 345 678/);
  assert.match(text, /Pixel 8 · SIM 1/);
  assert.match(text, /Rented · Acme Numbers/);
  assert.match(text, /Rented until/);
  assert.match(text, /No source/);
  assert.equal(gw.calls[0].query.refresh, "true", "the first look asks the phones now");
  view.destroy();
});

test("filters narrow the list to one origin or to what needs the owner", async () => {
  const gw = fakeGateway({ "GET /v1/numbers": () => overview() });
  const view = createNumbersView(ctx(gw), () => {}, deps);
  await flush();
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Needs you")).click();
  const rows = view.element.querySelectorAll("tr").filter((r) => r.className.includes("nm-row"));
  assert.equal(rows.length, 1);
  assert.match(rows[0].textContent, /No source/);
  view.element.querySelectorAll("button").find((b) => b.textContent.startsWith("Phones")).click();
  const phones = view.element.querySelectorAll("tr").filter((r) => r.className.includes("nm-row"));
  assert.equal(phones.length, 1);
  assert.match(phones[0].textContent, /Pixel 8/);
  view.destroy();
});

test("assigning, pausing and removing go to the gateway; a phone's own number can't be removed here", async () => {
  const gw = fakeGateway({
    "GET /v1/numbers": () => overview(),
    "POST /v1/numbers/num_b": ({ body }) => ({ id: "num_b", ...body }),
    "POST /v1/numbers/num_a": ({ body }) => ({ id: "num_a", ...body }),
    "POST /v1/numbers/num_b/delete": () => ({ id: "num_b", removed: true }),
  });
  const said = [];
  const view = createNumbersView(ctx(gw), (text) => said.push(text), deps);
  await flush();
  const row = (id) => view.element.querySelectorAll("tr").find((r) => r.dataset.id === id);
  const select = row("num_b").querySelector("select");
  const options = select.querySelectorAll("option");
  assert.ok(options.find((o) => o.textContent.includes("brand.food")).disabled, "an account that has a number can't take a second");
  select.value = "acc_fit";
  select.dispatchEvent({ type: "change" });
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path === "/v1/numbers/num_b").body, { accountId: "acc_fit" });
  button(row("num_a"), "Pause").click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path === "/v1/numbers/num_a").body, { paused: true });
  button(row("num_a"), "Edit").click();
  assert.equal(button(view.element, "Remove"), undefined);
  assert.match(view.element.textContent, /managed on the phone/);
  button(row("num_a"), "Close").click();
  button(row("num_b"), "Edit").click();
  const remove = button(view.element, "Remove");
  remove.click();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 0, "removing asks first");
  button(view.element, "Remove: are you sure").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path === "/v1/numbers/num_b/delete").length, 1);
  view.destroy();
});

test("adding a rented number sends the provider, end date, plugin and account", async () => {
  const gw = fakeGateway({ "GET /v1/numbers": () => overview(), "POST /v1/numbers": ({ body }) => ({ id: "num_new", ...body }) });
  const view = createNumbersView(ctx(gw), () => {}, deps);
  await flush();
  button(view.element, "Add a number").click();
  const inputs = view.element.querySelectorAll("input");
  const byLabel = (label) => inputs.find((i) => i.getAttribute("aria-label") === label);
  byLabel("Number").value = "+44 7700 900555";
  byLabel("Label").value = "Travel niche";
  byLabel("Provider").value = "Acme Numbers";
  byLabel("Rented until").value = "2027-01-31";
  const selects = view.element.querySelector(".nm-add").querySelectorAll("select");
  selects.find((s) => s.getAttribute("aria-label") === "Plugin").value = "sms-forwarder";
  selects.find((s) => s.getAttribute("aria-label") === "Account").value = "acc_fit";
  button(view.element, "Add number").click();
  await flush();
  const sent = gw.calls.find((c) => c.method === "POST" && c.path === "/v1/numbers").body;
  assert.equal(sent.origin, "rental");
  assert.equal(sent.number, "+44 7700 900555");
  assert.equal(sent.provider, "Acme Numbers");
  assert.equal(sent.source, "sms-forwarder");
  assert.equal(sent.accountId, "acc_fit");
  assert.equal(typeof sent.expiresAt, "number");
  view.destroy();
});

test("an empty fleet explains how numbers get here, and a failure can be retried", async () => {
  let fail = true;
  const gw = fakeGateway({
    "GET /v1/numbers": () => fail
      ? new Response(JSON.stringify({ detail: { code: "UNAVAILABLE", message: "down" } }), { status: 503, headers: { "Content-Type": "application/json" } })
      : overview({ numbers: [], phones: [], summary: { total: 0, byOrigin: { phone: 0, plugin: 0, rental: 0, other: 0 }, ready: 0, attention: 0, expiringSoon: 0, unassigned: 0 } }),
  });
  const view = createNumbersView(ctx(gw), () => {}, deps);
  await flush();
  assert.match(view.element.textContent, /couldn't be read/);
  fail = false;
  await view.refresh(true);
  assert.match(view.element.textContent, /No numbers yet/);
  assert.match(view.element.textContent, /Settings → Permissions → Codes/);
  view.destroy();
});
