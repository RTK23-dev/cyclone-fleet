import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { activityText, checkLabel, monogram, parseOverview, parsePlugin, portAbout, portLabel, statusInfo } from "../.test-dist/services/ports.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { createPortsPage } from "../.test-dist/pages/portsPage.js";

const KEY = "k1.SECRET-KEY-VALUE-123";
const ITEMS_OK = [
  { name: "manifest is valid", ok: true, required: true, detail: "", hint: "", cause: "" },
  { name: "run.event: accepts a sample envelope (2xx)", ok: true, required: true, detail: "HTTP 202", hint: "", cause: "" },
];
const LOGGER = {
  name: "run-logger", title: "Run logger", description: "Keeps run events.", version: "0.1.0", endpoint: "http://127.0.0.1:8771",
  remote: false, status: "active", paused: false, kid: "k1", health: "ok", healthDetail: "", seenAt: Date.now(), checkedAt: Date.now(),
  features: [], pending: null, createdAt: Date.now(),
  serves: [
    { port: "run.event", way: "out", sensitivity: "public", summary: "", extension: false, allowed: true },
    { port: "screen.shot", way: "out", sensitivity: "personal", summary: "", extension: false, allowed: false },
  ],
  checks: { passed: true, total: 2, failed: 0, items: ITEMS_OK },
};
const WAITING = { ...LOGGER, name: "pc-images", title: "PC image picker", status: "waiting_key",
  serves: [{ port: "file.in", way: "in", sensitivity: "personal", summary: "", extension: false, allowed: true }],
  checks: { passed: false, total: 3, failed: 2, items: [
    { name: "file.in: accepts a cancel (2xx)", ok: false, required: true, detail: "HTTP 401", hint: "The plugin doesn't have its key yet.", cause: "key" },
    { name: "answers 404 for a port it does not serve", ok: false, required: true, detail: "run.event: HTTP 401", hint: "The plugin doesn't have its key yet.", cause: "key" },
    { name: "manifest is valid", ok: true, required: true, detail: "", hint: "", cause: "" }] } };
const CATALOG = [
  { port: "run.event", way: "out", sensitivity: "public", summary: "x", pluginServed: true, servedBy: ["run-logger"] },
  { port: "code.in", way: "in", sensitivity: "secret", summary: "x", pluginServed: true, servedBy: [] },
  { port: "secret.in", way: "in", sensitivity: "secret", summary: "x", pluginServed: false, servedBy: [] },
];
const overview = (plugins) => ({ contract: "cyclone.ports/1", keysPersistent: true, plugins, catalog: CATALOG, extensions: [],
  activity: [{ id: 1, at: Date.now(), plugin: "run-logger", kind: "test", port: "log.line", ok: true, latencyMs: 3, detail: "delivered" }],
  today: { messages: 4, failures: 1 } });

function ctx(fetch) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0", devices: [], device: null, devicesError: null,
    navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}
const texts = (root, sel) => root.querySelectorAll(sel).map((n) => n.textContent);
const byLabel = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.includes(label));

test("route, words and parsers", () => {
  assert.deepEqual(parseRoute("#/command/ports"), { name: "command", tab: "ports" });
  assert.equal(routeHref({ name: "command", tab: "ports" }), "#/command/ports");
  assert.equal(portLabel("code.in"), "Verification codes");
  assert.equal(portLabel("x.crm.next-handle"), "Next handle");
  assert.match(portAbout("account.fields"), /Never a password/);
  assert.equal(portAbout("x.crm.lead", "from the plugin"), "from the plugin");
  assert.equal(checkLabel("file.in: accepts a cancel (2xx)"), "Files for the phone: accepts a cancel");
  assert.equal(checkLabel("refuses a stale signature (401)"), "Refuses a stale signature");
  assert.equal(checkLabel("/health answers 200 with ok"), "/health answers 200 with ok");
  const m = monogram("run-logger", "Run logger");
  assert.equal(m.letters, "RL");
  assert.deepEqual(monogram("run-logger", "Run logger"), m);
  assert.ok(m.tile >= 1 && m.tile <= 6);
  for (const status of ["active", "waiting_key", "failing_checks", "unreachable", "paused", "needs_review", "key_lost"]) {
    const info = statusInfo({ status, healthDetail: "", checks: null });
    assert.ok(info.label && info.explain, status);
    assert.equal(info.action === null, status === "active");
  }
  const weird = parsePlugin({ name: "x", status: "exploded", serves: [{ port: "log.line", way: "sideways", sensitivity: "?" }, {}], checks: { items: [{}] } });
  assert.equal(weird.status, "waiting_key");
  assert.equal(weird.serves.length, 1);
  assert.equal(weird.serves[0].way, "out");
  assert.equal(weird.checks.items[0].cause, "");
  assert.deepEqual(parseOverview(null).plugins, []);
  assert.equal(activityText({ kind: "check", ok: false, detail: "4 checks failed", port: null }), "4 checks failed");
});

test("first visit: a welcome with the kit's examples and the whole catalog", async () => {
  installMiniDom();
  const gw = fakeGateway({ "GET /v1/ports/overview": () => overview([]) });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  assert.ok(page.element.querySelector(".pt-welcome"));
  assert.deepEqual(texts(page.element, ".pt-starter-card .pt-card-title"), ["Run logger", "PC images", "SMS codes"]);
  assert.equal(page.element.querySelectorAll(".pt-catalog-row").length, 3);
  assert.ok(texts(page.element, ".pt-vault").includes("Vault only"));
  page.destroy();
});

test("with plugins: numbers at a glance, what needs you, cards, catalog and activity", async () => {
  installMiniDom();
  const gw = fakeGateway({ "GET /v1/ports/overview": () => overview([LOGGER, WAITING]) });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  const values = texts(page.element, ".pt-stat-value");
  assert.deepEqual(values, ["1", "1", "4", "1"]);
  assert.match(page.element.querySelector(".pt-attention").textContent, /PC image picker: waiting for its key/);
  assert.deepEqual(texts(page.element, ".pt-card-title"), ["Run logger", "PC image picker"]);
  const offChip = page.element.querySelectorAll(".pt-chip.off");
  assert.equal(offChip.length, 1, "a switched-off port shows struck through");
  assert.match(page.element.querySelector(".pt-feed").textContent, /Run logger.*Test message delivered/);
  page.destroy();
});

test("add a plugin: address, review with switches, the key once, checks", async () => {
  installMiniDom();
  let added = null;
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview(added ? [added] : []),
    "POST /v1/ports/plugins/preview": ({ body }) => ({
      endpoint: body.endpoint, remote: false, problems: [], alreadyAdded: null,
      manifest: { name: "run-logger", title: "Run logger", version: "0.1.0", description: "Keeps run events." },
      serves: LOGGER.serves.map((s) => ({ ...s, allowed: true })), endpointDiffers: false }),
    "POST /v1/ports/plugins": ({ body }) => {
      added = { ...LOGGER, status: "waiting_key", checks: null, serves: LOGGER.serves.map((s) => ({ ...s, allowed: body.allowed.includes(s.port) })) };
      return { plugin: added, key: { value: KEY, powershell: `$env:CYCLONE_PLUGIN_SECRET = "${KEY}"`, bash: `export CYCLONE_PLUGIN_SECRET='${KEY}'`, cmd: `set CYCLONE_PLUGIN_SECRET=${KEY}` } };
    },
    "POST /v1/ports/plugins/run-logger/check": () => {
      added = { ...added, status: "active", checks: LOGGER.checks };
      return { plugin: added, activity: [] };
    },
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-starter-card button").click();
  const sheet = page.element.querySelector(".pt-sheet");
  assert.ok(sheet, "the Add sheet opens");
  assert.equal(sheet.querySelector(".pt-address").value, "http://127.0.0.1:8771");
  assert.equal(sheet.querySelector(".pt-step.now").textContent, "1Address");

  byLabel(sheet, "Look it up").click();
  await flush();
  assert.equal(sheet.querySelector(".pt-review-title").textContent, "Run logger");
  const switches = sheet.querySelectorAll(".pt-switch");
  assert.equal(switches.length, 2);
  switches[1].click(); // switch Screenshots off
  await flush();
  assert.equal(switches[1].getAttribute("aria-checked"), "false");

  byLabel(sheet, "Add plugin").click();
  await flush();
  const addCall = gw.calls.find((c) => c.method === "POST" && c.path === "/v1/ports/plugins");
  assert.deepEqual(addCall.body.allowed, ["run.event"]);
  assert.equal(sheet.querySelector(".pt-key-value .pt-key-code").textContent, KEY);
  assert.match(sheet.querySelector(".pt-key-line").textContent, /\$env:CYCLONE_PLUGIN_SECRET/);

  byLabel(sheet, "run the checks").click();
  await flush();
  assert.match(sheet.querySelector(".pt-result-title").textContent, /Run logger is live/);
  assert.equal(sheet.querySelector(".pt-key"), null, "the key is gone once the checks ran");
  assert.equal(gw.calls.filter((c) => JSON.stringify(c.body ?? "").includes(KEY)).length, 0, "Glass never sends the key anywhere");
  page.destroy();
});

test("a plugin that only lacks its key shows one key row, not one per check", async () => {
  installMiniDom();
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview([WAITING]),
    "GET /v1/ports/plugins/pc-images": () => ({ plugin: WAITING, activity: [] }),
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-card").click();
  await flush();
  const drawer = page.element.querySelector(".pt-sheet-drawer");
  assert.ok(drawer);
  const bad = drawer.querySelectorAll(".pt-check.bad");
  assert.equal(bad.length, 1);
  assert.match(bad[0].textContent, /2 checks need the plugin's key/);
  assert.match(drawer.querySelector(".pt-status").textContent, /Waiting for its key/);
  page.destroy();
});

test("the plugin drawer: switch a port, pause, and remove after a confirm", async () => {
  installMiniDom();
  let plugin = { ...LOGGER };
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview([plugin]),
    "GET /v1/ports/plugins/run-logger": () => ({ plugin, activity: [] }),
    "POST /v1/ports/plugins/run-logger/ports": ({ body }) => {
      plugin = { ...plugin, serves: plugin.serves.map((s) => (s.port === body.port ? { ...s, allowed: body.allowed } : s)) };
      return { plugin, activity: [] };
    },
    "POST /v1/ports/plugins/run-logger/pause": ({ body }) => {
      plugin = { ...plugin, paused: body.paused, status: body.paused ? "paused" : "active" };
      return { plugin, activity: [] };
    },
    "POST /v1/ports/plugins/run-logger/delete": () => ({ removed: "run-logger" }),
  });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  page.element.querySelector(".pt-card").click();
  await flush();
  let drawer = page.element.querySelector(".pt-sheet-drawer");
  drawer.querySelectorAll(".pt-switch")[1].click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path.endsWith("/ports")).body, { port: "screen.shot", allowed: true });

  byLabel(drawer, "Pause").click();
  await flush();
  assert.match(drawer.querySelector(".pt-status").textContent, /Paused/);

  byLabel(drawer, "Remove plugin").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 0, "remove asks first");
  byLabel(drawer, "Remove Run logger").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path.endsWith("/delete")).length, 1);
  page.destroy();
});

// ---- run 2: the Port map -------------------------------------------------------------------------------------------------

const CANDS = [{ name: "sms-codes", title: "SMS codes", live: true, status: "active" }, { name: "mail-codes", title: "Mail codes", live: true, status: "active" }];
function bindingsView(scope, codeChosen = null, extra = {}) {
  const codeIn = codeChosen
    ? { state: "ok", effective: codeChosen, chosen: codeChosen, source: scope, override: true }
    : { state: "conflict", effective: [], chosen: null, source: "automatic", override: false };
  return {
    scope, scopes: scope === "default" ? [] : [{ scope, choices: 1 }],
    plugins: [{ name: "run-logger", title: "Run logger", status: "active" }, ...CANDS.map(({ name, title, status }) => ({ name, title, status }))],
    ports: [
      { port: "run.event", way: "out", sensitivity: "public", pluginServed: true, extension: false,
        candidates: [{ name: "run-logger", title: "Run logger", live: true, status: "active" }],
        state: "ok", effective: ["run-logger"], chosen: null, source: "automatic", override: false, inherits: null },
      { port: "code.in", way: "in", sensitivity: "secret", pluginServed: true, extension: false, candidates: CANDS,
        inherits: scope === "default" ? null : { state: "conflict", effective: [], source: "automatic" }, ...codeIn, ...extra },
      { port: "secret.in", way: "in", sensitivity: "secret", pluginServed: false, extension: false, candidates: [],
        state: "vault", effective: [], chosen: null, source: "hub", override: false, inherits: null },
    ],
  };
}

test("words for bindings and scopes", async () => {
  const { bindingInfo, scopeLabel, parseBindings } = await import("../.test-dist/services/ports.js");
  assert.equal(bindingInfo({ state: "ok", way: "out", effective: ["a", "b"], candidates: [] }).label, "2 plugins");
  assert.equal(bindingInfo({ state: "conflict", way: "in", effective: [], candidates: CANDS }).label, "Choose one");
  assert.equal(scopeLabel("default"), "Everywhere");
  assert.equal(scopeLabel("routine:rtn_abc123", new Map([["rtn_abc123", "Daily post"]])), "Routine: Daily post");
  assert.equal(scopeLabel("app:com.instagram.android"), "App: com.instagram.android");
  const parsed = parseBindings({ ports: [{ port: "code.in", state: "weird", chosen: null, candidates: [{ name: "x", status: "?" }] }] });
  assert.equal(parsed.ports[0].state, "empty");
  assert.equal(parsed.ports[0].chosen, null);
  assert.equal(parsed.ports[0].candidates[0].status, "waiting_key");
});

test("Port map: a conflict is settled with one choice, and an app gets its own map", async () => {
  installMiniDom();
  assert.deepEqual(parseRoute("#/command/ports/map"), { name: "command", tab: "ports", view: "map" });
  assert.equal(routeHref({ name: "command", tab: "ports", view: "map" }), "#/command/ports/map");
  const gw = fakeGateway({
    "GET /v1/ports/bindings": ({ query }) => bindingsView(query.scope),
    "GET /v1/cc/routines": () => ({ routines: [] }),
    "POST /v1/ports/bindings": ({ body }) => bindingsView(body.scope, body.plugins),
  });
  const page = createPortsPage(ctx(gw.fetch), "map");
  await flush();
  const map = page.element.querySelector(".pm");
  assert.ok(map, "the map view renders");
  assert.equal(page.element.querySelector(".pt-tab.active").textContent, "Port map");
  const code = map.querySelector('.pm-port[data-port="code.in"]');
  assert.match(code.textContent, /Choose one/);
  assert.ok(map.querySelector(".pm-vault-note"), "vault-only ports are a note, not rows");
  assert.equal(map.querySelectorAll(".pm-plugin").length, 3);

  code.querySelector(".pm-port-head").click();
  const radios = map.querySelectorAll('.pm-port[data-port="code.in"] .pm-option');
  assert.deepEqual(radios.map((r) => r.getAttribute("role")), ["radio", "radio", "radio", "radio"], "an in port takes one answer");
  radios.find((r) => r.textContent.includes("SMS codes")).click();
  byLabel(map.querySelector('.pm-port[data-port="code.in"]'), "Save").click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.method === "POST").body, { scope: "default", port: "code.in", plugins: ["sms-codes"] });
  assert.match(map.querySelector('.pm-port[data-port="code.in"]').textContent, /Answered/);

  byLabel(map, "For a routine or app").click();
  map.querySelector(".pm-adder input").value = "com.instagram.android";
  byLabel(map.querySelector(".pm-adder"), "Show its map").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path === "/v1/ports/bindings" && c.method === "GET").at(-1).query.scope, "app:com.instagram.android");
  assert.match(map.querySelector(".pm-scope.active").textContent, /com\.instagram\.android/);
  map.querySelector('.pm-port[data-port="code.in"] .pm-port-head').click();
  assert.match(map.querySelector('.pm-port[data-port="code.in"] .pm-option').textContent, /Same as Everywhere/);
  page.destroy();
});

test("the Plugins view lists port conflicts under Needs you", async () => {
  installMiniDom();
  const gw = fakeGateway({ "GET /v1/ports/overview": () => ({ ...overview([LOGGER]), conflicts: ["code.in"] }) });
  const page = createPortsPage(ctx(gw.fetch));
  await flush();
  assert.match(page.element.querySelector(".pt-attention").textContent, /Verification codes: choose who serves it/);
  assert.ok(byLabel(page.element, "Open the port map"));
  page.destroy();
});

// ---- run 3: Activity, run lanes and test runs ------------------------------------------------------------------------

const TEST_RUN = (state, codeStep) => ({
  runId: "run_test_ab12cd34", scenario: "signup", title: "Sign-up with a code", state, routine: null, app: null, startedAt: Date.now(),
  steps: [
    { kind: "emit", port: "run.event", state: "ok", detail: "to Run logger", label: "Send “started”" },
    { kind: "await", port: "code.in", state: codeStep, detail: codeStep === "ok" ? "a 6-character code from my-phone; would be sealed to the phone" : "waiting on SMS codes", label: "Wait for a verification code" },
  ],
});

test("Activity: filters, run lanes, and a test run watched to the end", async () => {
  installMiniDom();
  assert.deepEqual(parseRoute("#/command/ports/activity"), { name: "command", tab: "ports", view: "activity" });
  let polls = 0;
  const gw = fakeGateway({
    "GET /v1/ports/overview": () => overview([LOGGER]),
    "GET /v1/ports/runs": () => ({ runs: [{ runId: "run_test_ab12cd34", firstAt: Date.now(), lastAt: Date.now(), messages: 3, failures: 1 }], testRuns: [] }),
    "GET /v1/ports/activity": ({ query }) => ({ activity: query.status === "failed" ? [] : [
      { id: 2, at: Date.now(), plugin: "run-logger", kind: "emit", port: "run.event", runId: "run_test_ab12cd34", ok: true, latencyMs: 2, detail: "delivered" }] }),
    "GET /v1/ports/runs/run_test_ab12cd34": () => ({ activity: [
      { id: 1, at: Date.now(), plugin: "sms-codes", kind: "deliver", port: "code.in", runId: "run_test_ab12cd34", ok: true, latencyMs: null, detail: "code received (6 characters), held for the phone" }], waits: [] }),
    "POST /v1/ports/test-runs": ({ body }) => ({ ...TEST_RUN("running", "now"), scenario: body.scenario }),
    "GET /v1/ports/test-runs/run_test_ab12cd34": () => (++polls > 1 ? TEST_RUN("done", "ok") : TEST_RUN("running", "now")),
  });
  const page = createPortsPage(ctx(gw.fetch), "activity");
  await flush();
  const view = page.element.querySelector(".pa");
  assert.ok(view);
  assert.match(view.querySelector(".pt-feed").textContent, /Run logger.*Delivered · Run events/);

  // filters ask the gateway, and an empty result says so
  byLabel(view, "Failed").click();
  await flush();
  assert.equal(gw.calls.filter((c) => c.path === "/v1/ports/activity").at(-1).query.status, "failed");
  assert.match(view.textContent, /Nothing matches these filters/);

  // a run opens its lane
  view.querySelector(".pa-run-head").click();
  await flush();
  assert.match(view.querySelector(".pa-lane").textContent, /Answered · Verification codes: code received/);

  // a test run, live
  byLabel(view, "Start a test run").click();
  const sheet = page.element.querySelector(".pt-sheet");
  view.ownerDocument;
  sheet.querySelectorAll(".pa-scenario").find((c) => c.textContent.includes("Sign-up with a code")).click();
  byLabel(sheet, "Start").click();
  await flush();
  assert.deepEqual(gw.calls.find((c) => c.path === "/v1/ports/test-runs").body, { scenario: "signup" });
  assert.match(sheet.textContent, /Wait for a verification code.*Send the code to the phone or inbox your plugin watches/);
  await new Promise((resolve) => setTimeout(resolve, 2300));
  await flush();
  assert.match(sheet.textContent, /Went through/);
  assert.match(sheet.textContent, /would be sealed to the phone/);
  assert.doesNotMatch(JSON.stringify(gw.calls), /482913/);
  sheet.querySelector(".pt-close").click();
  page.destroy();
});
