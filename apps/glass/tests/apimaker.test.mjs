import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { basicAuth, parseCall, parseConnection, parseMake, parseTask, stepRefs, toolArguments } from "../.test-dist/services/command.js";
import { CURATED } from "../.test-dist/services/cards.js";
import { checkGoalRefs } from "../.test-dist/pages/connectionsView.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8" }];
function ctx(fetch) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.33", devices: DEVICES, device: DEVICES[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}
const buttons = (page, label) => page.element.querySelectorAll("button").filter((b) => b.textContent === label || b.getAttribute("aria-label") === label);
const controls = (page) => [...page.element.querySelectorAll("input"), ...page.element.querySelectorAll("select"), ...page.element.querySelectorAll("textarea")];
const byLabel = (page, label) => controls(page).find((i) => i.getAttribute("aria-label") === label);
const OVERVIEW = { accounts: 0, openTasks: 0, running: 0, routines: 0, routinesPaused: 0, approvals: 0, succeeded24h: 0, failed24h: 0 };
const BASE = { auth: "none", kind: "api", transport: "http", detail: "", signedIn: false, grantKept: true, keyHeader: null, keyQuery: null, manualClient: false,
  tools: [], allowed: [], dailyCap: 50, approval: "always", usedToday: 0, probe: [], launch: null, envSet: [], running: false, fromCard: false, url: "https://shop.example/v1" };
const API = (scheme) => ({ title: "Corner Shop", version: "2.1", base: "https://shop.example/v1", sourceUrl: null, operations: 3, skipped: [], unsupportedSignIn: null, scheme });
const tool = (name, cls, extra = {}) => ({ name, title: "", description: `${name} does things.`, readOnly: cls === "read", class: cls, baseClass: cls, overridden: false,
  allowed: false, rule: cls === "read" ? "cap" : "always", changed: false, previous: null, pollTool: null, fields: [], ...extra });

function routes(connections, extra = {}) {
  return {
    "GET /v1/cc/overview": () => OVERVIEW,
    "GET /v1/cc/accounts": () => ({ accounts: [] }),
    "GET /v1/cc/approvals": () => ({ approvals: [] }),
    "GET /v1/cc/connections": () => ({ connections, higgsfield: "https://mcp.higgsfield.ai/mcp" }),
    "GET /v1/cc/calls": () => ({ calls: [] }),
    "GET /v1/cc/artifacts": () => ({ artifacts: [] }),
    ...extra,
  };
}

async function open(connections, extra, tab = "connections") {
  installMiniDom();
  const gateway = fakeGateway(routes(connections, extra));
  const page = createCommandPage(ctx(gateway.fetch), tab);
  await flush();
  return { page, gateway };
}

test("parsers read API connections, steps, and each step's calls defensively", () => {
  const c = parseConnection({ ...BASE, id: "con_a", auth: "query", keyQuery: "api_key", api: API({ type: "query", name: "api_key", scopes: 3 }), fromCard: true,
    tools: [tool("searchProducts", "read", { baseClass: "change", overridden: true })] });
  assert.equal(c.kind, "api");
  assert.equal(c.auth, "query");
  assert.equal(c.api.scheme.type, "query");
  assert.deepEqual(c.api.scheme.scopes, []);
  assert.equal(c.tools[0].baseClass, "change");
  assert.equal(c.tools[0].overridden, true);
  assert.equal(parseConnection({ kind: "weird" }).kind, "remote");
  const make = parseMake({ connectionId: "con_a", tool: "listOrders", arguments: { x: { nested: 1 }, y: "{step1.id}" }, then: "phone",
    steps: [{ connectionId: "con_a", tool: "listOrders", arguments: {} }, { connectionId: "con_a", tool: "replyToOrder", arguments: { id: "{step1.orders.0.id}" } }, { bad: 1 }] });
  assert.equal(make.then, "phone");
  assert.equal(make.steps.length, 2);
  assert.equal(make.arguments.x, undefined);
  assert.equal(parseMake({ connectionId: "c", tool: "t", then: "sideways" }).then, "post");
  assert.equal(parseMake({ connectionId: "c", tool: "t" }).steps.length, 1, "a C3 one-step make reads as one step");
  const task = parseTask({ status: "making", stepAt: 1, calls: [{ id: "cal_1", state: "done", step: 0 }, "junk"] });
  assert.equal(task.stepAt, 1);
  assert.equal(task.calls[0].step, 0);
  assert.equal(parseCall({}).step, 0);
});

test("step references are checked in fields and in the goal", () => {
  assert.deepEqual(stepRefs("Tell {step1.orders.0.customer} about {step2.id}"), [1, 2]);
  assert.throws(() => stepRefs("{step 1.x}"), /not a step reference/);
  assert.throws(() => stepRefs("{step0.x}"), /not a step reference/);
  const t = tool("replyToOrder", "sensitive", { fields: [{ name: "id", type: "string", enum: [], required: true, description: "", default: null },
    { name: "count", type: "integer", enum: [], required: false, description: "", default: null }] });
  assert.deepEqual(toolArguments(t, { id: "{step1.orders.0.id}", count: "{step1.total}" }, 2), { id: "{step1.orders.0.id}", count: "{step1.total}" });
  assert.throws(() => toolArguments(t, { id: "{step1.id}" }, 1), /earlier steps/);
  assert.throws(() => toolArguments(t, { id: "{step2.id}" }, 2), /earlier steps/);
  assert.throws(() => toolArguments(t, { id: "x", count: "many" }, 2), /number/);
  const one = { make: { connectionId: "c", tool: "t", arguments: {}, pollTool: null, then: "post" } };
  const two = { steps: [one.make, one.make], then: "phone" };
  checkGoalRefs("Say {step2.name}", two);
  checkGoalRefs("No references", null);
  assert.throws(() => checkGoalRefs("Say {step3.name}", two), /after the last one/);
  assert.throws(() => checkGoalRefs("Say {step1.name}", { steps: two.steps, then: "keep" }), /go to a phone/);
  assert.throws(() => checkGoalRefs("Say {step1.name}", null), /go to a phone/);
  assert.equal(basicAuth("sam", "pässword"), `Basic ${Buffer.from("sam:pässword").toString("base64")}`);
});

test("an API description is added by its address or pasted, never with a key in the address", async () => {
  const added = [];
  const { page, gateway } = await open([], { "POST /v1/cc/connections": ({ body }) => { added.push(body); return { ...BASE, id: "con_new0001", name: "Corner Shop", status: "needs_key", api: API({ type: "header", name: "X-Shop-Key" }) }; } });
  try {
    buttons(page, "API description")[0].click();
    await flush();
    byLabel(page, "Description address").value = "https://shop.example/openapi.json?api_key=abc";
    buttons(page, "Add connection")[0].click();
    await flush();
    assert.match(page.element.textContent, /Leave keys out of the address/);
    assert.equal(added.length, 0);
    byLabel(page, "Description address").value = "https://shop.example/openapi.json";
    byLabel(page, "API description").value = "openapi: 3.0.0";
    buttons(page, "Add connection")[0].click();
    await flush();
    assert.match(page.element.textContent, /not both/);
    byLabel(page, "API description").value = "";
    buttons(page, "Add connection")[0].click();
    await flush();
    assert.deepEqual(added[0], { specUrl: "https://shop.example/openapi.json" });
    byLabel(page, "API description").value = "openapi: 3.0.0\npaths: {}";
    byLabel(page, "API address").value = "https://shop.example/v1";
    byLabel(page, "Name").value = "Shop";
    buttons(page, "Add connection")[0].click();
    await flush();
    assert.deepEqual(added[1], { specText: "openapi: 3.0.0\npaths: {}", baseUrl: "https://shop.example/v1", name: "Shop" });
    assert.ok(gateway.calls.some((c) => c.path === "/v1/cc/connections" && c.method === "POST"));
  } finally {
    page.destroy();
  }
});

test("an API asks for its key the way its description says: in the address, or as a user name and password", async () => {
  const query = { ...BASE, id: "con_q0000001", name: "Query shop", status: "needs_key", auth: "query", keyQuery: "api_key", api: API({ type: "query", name: "api_key", scopes: [] }) };
  const basic = { ...BASE, id: "con_b0000001", name: "Basic shop", status: "needs_key", auth: "header", keyHeader: "Authorization", api: API({ type: "basic", name: "Authorization", scopes: [] }) };
  const keys = [];
  const { page } = await open([query, basic], {
    "POST /v1/cc/connections/con_q0000001/key": ({ body }) => { keys.push(body); return query; },
    "POST /v1/cc/connections/con_b0000001/key": ({ body }) => { keys.push(body); return basic; },
  });
  try {
    assert.match(page.element.textContent, /takes its key in the address \(api_key=…\)/);
    const key = controls(page).find((i) => i.getAttribute("aria-label") === "Key");
    key.value = "k-123";
    buttons(page, "Save the key")[0].click();
    await flush();
    assert.deepEqual(keys[0], { query: "api_key", value: "k-123" });
    assert.equal(key.value, "", "the typed key is cleared from the page");
    byLabel(page, "User name").value = "sam";
    byLabel(page, "Password for the API").value = "pw-1";
    buttons(page, "Save")[0].click();
    await flush();
    assert.deepEqual(keys[1], { header: "Authorization", value: basicAuth("sam", "pw-1") });
    assert.equal(byLabel(page, "Password for the API").value, "");
  } finally {
    page.destroy();
  }
});

test("the owner can say a tool counts as a read or a change; a sensitive one has no choice", async () => {
  const con = { ...BASE, id: "con_s0000001", name: "Shop", status: "ready", allowed: ["listOrders"],
    tools: [tool("listOrders", "read", { allowed: true }), tool("searchProducts", "change"), tool("cancelOrder", "sensitive")] };
  const saved = [];
  const { page } = await open([con], { "POST /v1/cc/connections/con_s0000001/settings": ({ body }) => { saved.push(body); return con; } });
  try {
    assert.ok(byLabel(page, "searchProducts counts as"));
    assert.equal(byLabel(page, "cancelOrder counts as"), undefined);
    const counts = byLabel(page, "searchProducts counts as");
    counts.value = "read";
    counts.dispatchEvent({ type: "change" });
    const box = page.element.querySelectorAll("input").find((i) => i.type === "checkbox" && i.value === "searchProducts");
    box.checked = true;
    box.dispatchEvent({ type: "change" });
    buttons(page, "Save rules")[0].click();
    await flush();
    assert.deepEqual(saved[0].classes, { searchProducts: "read" });
    assert.deepEqual(saved[0].allowed.sort(), ["listOrders", "searchProducts"]);
    assert.deepEqual(saved[0].rules, {}, "no change rule is sent for a tool now counted as a read");
  } finally {
    page.destroy();
  }
});

test("cards: export asks the gateway, import sends the card, and curated cards carry no keys", async () => {
  const con = { ...BASE, id: "con_e0000001", name: "Corner Shop", status: "ready", tools: [tool("listOrders", "read", { allowed: true })], allowed: ["listOrders"] };
  const imported = [];
  const { page, gateway } = await open([con], {
    "GET /v1/cc/connections/con_e0000001/card": () => ({ cyclone: "connector-card", version: 1, name: "Corner Shop", kind: "api", tools: [] }),
    "POST /v1/cc/connections/import": ({ body }) => { imported.push(body.card); return { ...con, id: "con_i0000001" }; },
  });
  try {
    buttons(page, "Export card")[0].click();
    await flush();
    assert.ok(gateway.calls.some((c) => c.method === "GET" && c.path === "/v1/cc/connections/con_e0000001/card"));
    const area = byLabel(page, "Connector card");
    area.value = '{"hello": 1}';
    buttons(page, "Import the card")[0].click();
    await flush();
    assert.match(page.element.textContent, /not a Cyclone connector card/);
    area.value = JSON.stringify({ cyclone: "connector-card", version: 1, kind: "remote", remote: { url: "https://x.example/mcp" } });
    buttons(page, "Import the card")[0].click();
    await flush();
    assert.equal(imported[0].remote.url, "https://x.example/mcp");
    buttons(page, "Add Weather (Open-Meteo)")[0].click();
    await flush();
    assert.equal(imported[1].name, "Weather");
  } finally {
    page.destroy();
  }
  for (const item of CURATED) {
    const text = JSON.stringify(item.card);
    assert.doesNotMatch(text, /"(value|token|secret|password|clientSecret)"\s*:/i, item.id);
    for (const t of item.card.tools) assert.equal(t.hash, undefined, "curated tools are named, never pre-approved by hash");
  }
  const github = CURATED.find((c) => c.id === "github").card;
  assert.deepEqual(github.tools.map((t) => t.name), ["whoAmI", "getRepo", "listIssues"], "opening an issue is never pre-selected");
});

test("the steps editor chains two calls and hands the results to a phone as {step…} references", async () => {
  const con = { ...BASE, id: "con_shop0001", name: "Shop", status: "ready", allowed: ["listOrders", "replyToOrder"], tools: [
    tool("listOrders", "read", { allowed: true, fields: [{ name: "status", type: "string", enum: ["open", "done"], required: false, description: "", default: null }] }),
    tool("replyToOrder", "sensitive", { allowed: true, fields: [{ name: "id", type: "string", enum: [], required: true, description: "", default: null },
      { name: "text", type: "string", enum: [], required: true, description: "", default: null }] })] };
  const created = [];
  const { page } = await open([con], {
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    "POST /v1/cc/tasks": ({ body }) => { created.push(body); return { id: "tsk_1", title: "x", goal: body.goal ?? "", status: "scheduled" }; },
  }, "tasks");
  try {
    const on = byLabel(page, "Use connections first");
    on.checked = true;
    on.dispatchEvent({ type: "change" });
    await flush();
    byLabel(page, "status").value = "open";
    buttons(page, "Add a step")[0].click();
    await flush();
    const tool2 = byLabel(page, "Step 2 tool");
    tool2.value = "replyToOrder";
    tool2.dispatchEvent({ type: "change" });
    byLabel(page, "Step 2 id").value = "{step1.orders.0.id}";
    byLabel(page, "Step 2 text").value = "Hi {step1.orders.0.customer}, shipped!";
    const then = byLabel(page, "Then");
    then.value = "phone";
    const goal = page.element.querySelector("textarea");
    goal.value = "Tell {step3.customer} in Messages";
    buttons(page, "Create task")[0].click();
    await flush();
    assert.match(page.element.textContent, /after the last one/);
    assert.equal(created.length, 0);
    goal.value = "Tell {step1.orders.0.customer} in Messages that it shipped.";
    buttons(page, "Create task")[0].click();
    await flush();
    assert.deepEqual(created[0].steps, [
      { connectionId: "con_shop0001", tool: "listOrders", arguments: { status: "open" }, pollTool: null },
      { connectionId: "con_shop0001", tool: "replyToOrder", arguments: { id: "{step1.orders.0.id}", text: "Hi {step1.orders.0.customer}, shipped!" }, pollTool: null }]);
    assert.equal(created[0].then, "phone");
    assert.equal(created[0].make, undefined);
    // Step 1 cannot name itself.
    buttons(page, "Remove this step")[0].click();
    await flush();
    byLabel(page, "Then").value = "keep";
    const status = byLabel(page, "status");
    assert.equal(status.tagName, "SELECT", "step 1 keeps its choices");
  } finally {
    page.destroy();
  }
});

test("a Try it result and its typed values survive the list being redrawn", async () => {
  const con = { ...BASE, id: "con_t0000001", name: "Shop", status: "ready", allowed: ["listOrders"], tools: [
    tool("listOrders", "read", { allowed: true, fields: [{ name: "status", type: "string", enum: [], required: false, description: "", default: null }] })] };
  const { page } = await open([con], {
    "POST /v1/cc/connections/con_t0000001/call": ({ body }) => ({ id: "cal_1", connectionId: con.id, tool: body.tool, state: "done", summary: "", artifacts: [], arguments: body.arguments, result: { orders: ["ord-1001"] }, step: 0 }),
    "POST /v1/cc/connections/con_t0000001/refresh": () => con,
  });
  try {
    const field = byLabel(page, "listOrders status");
    field.value = "open";
    field.dispatchEvent({ type: "input" });
    buttons(page, "Try it")[0].click();
    await flush();
    assert.match(page.element.textContent, /ord-1001/);
    buttons(page, "Check again")[0].click();
    await flush();
    assert.notEqual(byLabel(page, "listOrders status"), field, "the list was redrawn");
    assert.equal(byLabel(page, "listOrders status").value, "open");
    assert.match(page.element.textContent, /ord-1001/);
  } finally {
    page.destroy();
  }
});
