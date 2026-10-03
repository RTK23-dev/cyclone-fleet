import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { LOCAL_CARD, parseConnection } from "../.test-dist/services/command.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8" }];
function ctx(fetch) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.32", devices: DEVICES, device: DEVICES[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}
const buttons = (page, label) => page.element.querySelectorAll("button").filter((b) => b.textContent === label);
const inputs = (page) => page.element.querySelectorAll("input");
const byLabel = (page, label) => [...inputs(page), ...page.element.querySelectorAll("select"), ...page.element.querySelectorAll("textarea")].find((i) => i.getAttribute("aria-label") === label);
const OVERVIEW = { accounts: 0, openTasks: 0, running: 0, routines: 0, routinesPaused: 0, approvals: 0, succeeded24h: 0, failed24h: 0 };
const BASE = { auth: "none", kind: "remote", transport: "http", detail: "", signedIn: false, grantKept: true, keyHeader: null, manualClient: false,
  tools: [], allowed: [], dailyCap: 10, approval: "always", usedToday: 0, probe: [], launch: null, envSet: [], running: false };
const tool = (name, cls, extra = {}) => ({ name, title: "", description: `${name} does things.`, readOnly: cls === "read", class: cls, allowed: false,
  rule: cls === "read" ? "cap" : "always", changed: false, previous: null, pollTool: null, fields: [], ...extra });

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

async function open(connections, extra) {
  installMiniDom();
  const gateway = fakeGateway(routes(connections, extra));
  const page = createCommandPage(ctx(gateway.fetch), "connections");
  await flush();
  return { page, gateway };
}

test("a pasted server config is sent as data; nothing runs yet", async () => {
  const { page, gateway } = await open([], { "POST /v1/cc/connections": ({ body }) => ({ ...BASE, id: "con_l1", name: "files", status: "needs_approval", body }) });
  try {
    buttons(page, "Program on this PC")[0].click();
    await flush();
    const area = byLabel(page, "Server config");
    area.value = '{"mcpServers":{"files":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem@2025.8.21","C:\\\\Notes"]}}}';
    buttons(page, "Add connection")[0].click();
    await flush();
    const call = gateway.calls.find((c) => c.method === "POST" && c.path === "/v1/cc/connections");
    assert.equal(call.body.config.mcpServers.files.command, "npx");
    area.value = "npx server";
    buttons(page, "Add connection")[0].click();
    await flush();
    assert.match(page.element.textContent, /That is not JSON/);
  } finally {
    page.destroy();
  }
});

test("the setup card says plainly what a local program can do, and approves that exact command", async () => {
  const local = { ...BASE, id: "con_l2", name: "weather", kind: "local", transport: "stdio", status: "needs_approval",
    launch: { name: "weather", launcher: "npx", args: ["-y", "weather-mcp@1.2.3"], envKeys: ["WEATHER_KEY"], pinned: "weather-mcp@1.2.3",
      display: "npx -y weather-mcp@1.2.3", hash: "h".repeat(64), approvedHash: null, previous: "npx -y weather-mcp@1.2.2" } };
  const posted = [];
  const { page } = await open([local], {
    "POST /v1/cc/connections/con_l2/env": ({ body }) => { posted.push(["env", body]); return local; },
    "POST /v1/cc/connections/con_l2/approve": ({ body }) => { posted.push(["approve", body]); return { ...local, status: "ready" }; },
  });
  try {
    const text = page.element.textContent;
    assert.match(text, /Run this program on your PC\?/);
    assert.ok(text.includes(LOCAL_CARD.body) && text.includes(LOCAL_CARD.trust));
    assert.match(LOCAL_CARD.body, /read and change files/);
    assert.match(LOCAL_CARD.body, /use the internet/);
    assert.match(text, /npx -y weather-mcp@1\.2\.3/);
    assert.match(text, /Before it was: npx -y weather-mcp@1\.2\.2/);
    const key = byLabel(page, "WEATHER_KEY");
    assert.equal(key.type, "password");
    key.value = "wk-1";
    buttons(page, "Run it")[0].click();
    await flush();
    assert.deepEqual(posted, [["env", { values: { WEATHER_KEY: "wk-1" } }], ["approve", { hash: "h".repeat(64) }]]);
    assert.equal(key.value, "", "Glass does not keep the key");
  } finally {
    page.destroy();
  }
});

test("a server that wants a key gets a masked form; Bearer is added when ticked", async () => {
  const keyed = { ...BASE, id: "con_k", name: "Keyed", url: "https://api.example.com/mcp", auth: "header", status: "needs_key",
    probe: [{ step: "Reached it", ok: true, detail: "" }, { step: "It wants a key (API key or token)", ok: false, detail: "" }] };
  const sent = [];
  const { page } = await open([keyed], { "POST /v1/cc/connections/con_k/key": ({ body }) => { sent.push(body); return { ...keyed, status: "ready" }; } });
  try {
    assert.match(page.element.textContent, /It wants a key/);
    const value = byLabel(page, "Key");
    assert.equal(value.type, "password");
    value.value = "sk-123";
    buttons(page, "Save the key")[0].click();
    await flush();
    assert.deepEqual(sent[0], { header: "Authorization", value: "Bearer sk-123" });
  } finally {
    page.destroy();
  }
});

test("a service without self-registration shows the redirect address and takes a client ID", async () => {
  const noreg = { ...BASE, id: "con_c", name: "NoReg", url: "https://x.example/mcp", auth: "oauth", status: "needs_client" };
  const sent = [];
  const { page } = await open([noreg], { "POST /v1/cc/connections/con_c/client": ({ body }) => { sent.push(body); return { ...noreg, status: "needs_sign_in" }; } });
  try {
    assert.match(page.element.textContent, /\/v1\/cc\/connections\/oauth\/callback/);
    assert.equal(buttons(page, "Sign in").length, 0);
    byLabel(page, "Client ID").value = "app-42";
    buttons(page, "Save and sign in")[0].click();
    await flush();
    assert.deepEqual(sent[0], { clientId: "app-42" });
  } finally {
    page.destroy();
  }
});

test("tools come in reads, changes and sensitive; reads can be allowed at once; a changed tool says what changed", async () => {
  const c = { ...BASE, id: "con_t", name: "Shop", url: "https://shop.example/mcp", status: "ready", signedIn: true, auth: "header",
    tools: [tool("list_orders", "read"), tool("get_order", "read", { allowed: true }), tool("update_order", "change", { changed: true, previous: { description: "Updates an order." }, description: "Updates an order and emails the customer." }),
      tool("refund_order", "sensitive")] };
  const saved = [];
  const { page } = await open([c], { "POST /v1/cc/connections/con_t/settings": ({ body }) => { saved.push(body); return c; } });
  try {
    const text = page.element.textContent;
    assert.match(text, /Reads/);
    assert.match(text, /Changes things/);
    assert.match(text, /always asks you/);
    assert.match(text, /Before: Updates an order\./);
    assert.match(text, /Now: Updates an order and emails the customer\./);
    assert.equal(byLabel(page, "When to ask for refund_order"), undefined, "a sensitive tool has no lower rule");
    assert.ok(byLabel(page, "When to ask for update_order"));
    buttons(page, "Allow all reads (2)")[0].click();
    await flush();
    assert.deepEqual(saved[0], { allowReads: true });
  } finally {
    page.destroy();
  }
});

test("Try it runs an allowed read and shows what came back", async () => {
  const c = { ...BASE, id: "con_w", name: "Weather", kind: "local", transport: "stdio", status: "ready", running: true,
    tools: [tool("get_weather", "read", { allowed: true, fields: [{ name: "city", type: "string", enum: [], required: true, description: "", default: null }] })] };
  let polls = 0;
  const { page, gateway } = await open([c], {
    "POST /v1/cc/connections/con_w/call": () => ({ id: "cal_1", connectionId: "con_w", tool: "get_weather", state: "running", summary: "", artifacts: [], arguments: {}, createdAt: 1 }),
    "GET /v1/cc/calls/cal_1": () => { polls += 1; return { id: "cal_1", connectionId: "con_w", tool: "get_weather", state: "done", summary: "Done.", artifacts: [], arguments: {}, createdAt: 1, result: { place: "Utrecht", temp: 21 } }; },
  });
  try {
    byLabel(page, "get_weather city").value = "Utrecht";
    buttons(page, "Try it")[0].click();
    await new Promise((r) => setTimeout(r, 1_200));
    await flush();
    assert.deepEqual(gateway.calls.find((x) => x.path === "/v1/cc/connections/con_w/call").body, { tool: "get_weather", arguments: { city: "Utrecht" } });
    assert.ok(polls >= 1);
    assert.match(page.element.textContent, /"temp": 21/);
  } finally {
    page.destroy();
  }
});

test("parsing keeps local launch details but never env values", () => {
  const c = parseConnection({ ...BASE, kind: "local", launch: { envKeys: ["A"], display: "npx x@1.0.0", hash: "h", env: { A: "secret" } } });
  assert.deepEqual(Object.keys(c.launch).sort(), ["approvedHash", "args", "display", "envKeys", "hash", "launcher", "name", "pinned", "previous"]);
});
