import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseApproval, parseConnection, parseTask, toolArguments } from "../.test-dist/services/command.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";
import { MAX_AHEAD_MS, sealForTask } from "../.test-dist/services/delivery.js";
import { fingerprint, unhex } from "../.test-dist/services/hpke.js";
import { toB64 } from "../.test-dist/services/vault.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8" }];
function ctx(fetch, navigate = () => {}) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.31", devices: DEVICES, device: DEVICES[0], devicesError: null, navigate, selectDevice() {}, refreshDevices: async () => {} };
}
const buttons = (page, label) => page.element.querySelectorAll("button").filter((b) => b.textContent === label);
const OVERVIEW = { accounts: 0, openTasks: 0, running: 0, routines: 0, routinesPaused: 0, approvals: 0, succeeded24h: 0, failed24h: 0 };
const TOOLS = [
  { name: "generate_video", title: "Generate video", description: "Make a short video.", readOnly: false, class: "change", allowed: true, rule: "always", changed: false, previous: null, pollTool: "get_result", fields: [
    { name: "prompt", type: "string", enum: [], required: true, description: "", default: null },
    { name: "duration", type: "integer", enum: [], required: false, description: "", default: 5 },
    { name: "aspect", type: "string", enum: ["9:16", "16:9"], required: false, description: "", default: "9:16" }] },
  { name: "get_result", title: "", description: "Check a generation.", readOnly: true, class: "read", allowed: true, rule: "cap", changed: false, previous: null, pollTool: null, fields: [{ name: "job_id", type: "string", enum: [], required: true, description: "", default: null }] },
  { name: "delete_account", title: "", description: "Deletes the account.", readOnly: false, class: "sensitive", allowed: false, rule: "always", changed: false, previous: null, pollTool: null, fields: [] },
];
const HIGGS = { id: "con_higgs0001", name: "Higgsfield", url: "https://mcp.higgsfield.ai/mcp", auth: "oauth", status: "ready", detail: "3 tool(s)", signedIn: true,
  grantKept: true, tools: TOOLS, allowed: ["generate_video", "get_result"], dailyCap: 5, approval: "always", usedToday: 1 };

function routes(extra = {}) {
  return {
    "GET /v1/cc/overview": () => OVERVIEW,
    "GET /v1/cc/accounts": () => ({ accounts: [] }),
    "GET /v1/cc/approvals": () => ({ approvals: [] }),
    "GET /v1/cc/connections": () => ({ connections: [HIGGS], higgsfield: "https://mcp.higgsfield.ai/mcp" }),
    "GET /v1/cc/calls": () => ({ calls: [] }),
    "GET /v1/cc/artifacts": () => ({ artifacts: [] }),
    ...extra,
  };
}

test("parsers are defensive about connections, make steps and spend approvals", () => {
  const c = parseConnection({ status: "weird", approval: "yolo", tools: [{ name: "x", fields: [{ name: "a", type: "object" }] }] });
  assert.equal(c.status, "error");
  assert.equal(c.approval, "always");
  assert.equal(c.tools[0].fields[0].type, "string");
  assert.equal(parseTask({ status: "making", make: { connectionId: "con_1", tool: "t", arguments: { a: 1, b: { x: 1 } } } }).make.arguments.b, undefined);
  const spend = parseApproval({ kind: "spend", send: { connection: "Higgsfield", tool: "generate_video", arguments: { prompt: "cat" } } });
  assert.equal(spend.send, null);
  assert.equal(spend.spend.arguments.prompt, "cat");
});

test("tool arguments are typed by the tool's fields and never carry a secret", () => {
  const tool = parseConnection(HIGGS).tools[0];
  assert.deepEqual(toolArguments(tool, { prompt: " A cat surfing ", duration: "8", aspect: "" }), { prompt: "A cat surfing", duration: 8 });
  assert.throws(() => toolArguments(tool, { prompt: "" }), /prompt is required/);
  assert.throws(() => toolArguments(tool, { prompt: "x", duration: "8.5" }), /number/);
  assert.throws(() => toolArguments(tool, { prompt: "api_key: sk-123" }), /Leave passwords/);
});

test("connections: sign in opens the server's page, and rules are saved as ticked", async () => {
  installMiniDom();
  const opened = [];
  globalThis.open = (url, target, features) => { opened.push({ url, target, features }); return {}; };
  const signingIn = { ...HIGGS, id: "con_new000001", name: "Other", status: "needs_sign_in", signedIn: false, tools: [], allowed: [] };
  const saved = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/connections": () => ({ connections: [HIGGS, signingIn] }),
    "POST /v1/cc/connections/con_new000001/sign-in": () => ({ authorizationUrl: "https://auth.example.com/authorize?state=abc" }),
    "POST /v1/cc/connections/con_higgs0001/settings": ({ body }) => { saved.push(body); return HIGGS; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "connections");
  try {
    await flush();
    assert.match(page.element.textContent, /Sign in needed/);
    assert.match(page.element.textContent, /1 used today/);
    buttons(page, "Sign in")[0].click();
    await flush();
    assert.equal(opened[0].url, "https://auth.example.com/authorize?state=abc");
    assert.match(opened[0].features, /noopener/);
    const boxes = page.element.querySelectorAll("input").filter((i) => i.type === "checkbox");
    const del = boxes.find((b) => b.value === "delete_account");
    assert.equal(del.checked, false, "nothing is allowed until ticked");
    const get = boxes.find((b) => b.value === "get_result");
    get.checked = false;
    get.dispatchEvent({ type: "change" });
    const rule = page.element.querySelectorAll("select").find((s) => s.getAttribute("aria-label") === "When to ask");
    rule.value = "cap";
    rule.dispatchEvent({ type: "change" });
    buttons(page, "Save rules")[0].click();
    await flush();
    assert.deepEqual(saved[0], { allowed: ["generate_video"], rules: { generate_video: "always" }, dailyCap: 5, approval: "cap" });
  } finally {
    page.destroy();
    delete globalThis.open;
  }
});

test("a spend approval shows the exact call and is approved by its id; a login request only points to the vault", async () => {
  installMiniDom();
  const spend = { id: "apv_spend", taskId: "tsk_1", title: "Daily video", deviceId: "", kind: "spend", text: "Use Higgsfield: generate_video? It may use your plan's credits.",
    gate: "spend", send: { connection: "Higgsfield", tool: "generate_video", arguments: { prompt: "Our new sneaker", aspect: "9:16" } },
    choices: [], fields: [], approvableHere: true, answerHere: true, state: "open", createdAt: Date.now() };
  const login = { ...spend, id: "apv_login", kind: "login", text: "Unlock the vault in Glass to send the password for @shop to the phone.", send: null, gate: null, answerHere: false, approvableHere: false };
  const answers = [];
  const went = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/approvals": () => ({ approvals: [spend, login] }),
    "POST /v1/cc/approvals/apv_spend/answer": ({ body }) => { answers.push(body); return { handled: true, detail: "Running it." }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch, (route) => went.push(route)), "approvals");
  try {
    await flush();
    assert.match(page.element.textContent, /Our new sneaker/);
    assert.match(page.element.textContent, /generate_video/);
    buttons(page, "Approve the call")[0].click();
    await flush();
    assert.deepEqual(answers, [{ action: "approve" }]);
    assert.equal(buttons(page, "Approve and send").length, 0);
    buttons(page, "Open the vault")[0].click();
    assert.deepEqual(went[0], { name: "command", tab: "vault" });
  } finally {
    page.destroy();
  }
});

test("a task can make a video first, then post it from the phone, with typed arguments", async () => {
  installMiniDom();
  const created = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    "POST /v1/cc/tasks": ({ body }) => { created.push(body); return { id: "tsk_9", title: "x", goal: body.goal ?? "", status: "scheduled" }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "tasks");
  try {
    await flush();
    const make = page.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "Use connections first");
    make.checked = true;
    make.dispatchEvent({ type: "change" });
    await flush();
    const selects = () => page.element.querySelectorAll("select");
    assert.deepEqual(selects().find((s) => s.getAttribute("aria-label") === "Tool").querySelectorAll("option").map((o) => o.value), ["generate_video", "get_result"],
      "only allowed tools");
    assert.equal(selects().find((s) => s.getAttribute("aria-label") === "Check the result with").value, "get_result");
    page.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "prompt").value = "Our new sneaker";
    page.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "duration").value = "10";
    page.element.querySelector("textarea").value = "Post the new video on Instagram with the caption: New drop";
    buttons(page, "Create task")[0].click();
    await flush();
    assert.deepEqual(created[0].make, { connectionId: "con_higgs0001", tool: "generate_video", arguments: { prompt: "Our new sneaker", duration: 10, aspect: "9:16" },
      pollTool: "get_result", then: "post" });
    assert.match(created[0].goal, /Post the new video/);
  } finally {
    page.destroy();
  }
});

test("a routine can seal its next runs' passwords ahead on one phone", async () => {
  installMiniDom();
  const saved = [];
  const account = { id: "acc_shop0001", service: "com.example.shop", handle: "@shop", ownerBasis: "mine", twofa: "none", allowedDevices: [], status: "active", notes: "", lastOutcome: null, locked: false, vaultItems: 1 };
  const gateway = fakeGateway(routes({
    "GET /v1/cc/accounts": () => ({ accounts: [account] }),
    "GET /v1/cc/routines": () => ({ routines: [] }),
    "GET /v1/cc/vault": () => ({ exists: true, items: [{ id: "vi_shoplogin000000000", accountId: account.id, kind: "login", updatedAt: 1 }] }),
    "POST /v1/cc/routines": ({ body }) => { saved.push(body); return { id: "rtn_1", ...body, scheduleLabel: "Weekdays at 09:00", prepared: [] }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "routines");
  try {
    await flush();
    page.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "Title").value = "Check orders";
    page.element.querySelector("textarea").value = "Check the orders";
    const selects = page.element.querySelectorAll("select");
    const acc = selects.find((s) => s.getAttribute("aria-label") === "Account");
    acc.value = account.id;
    acc.dispatchEvent({ type: "change" });
    await flush();
    selects.find((s) => s.getAttribute("aria-label") === "Sign in with").value = "vi_shoplogin000000000";
    selects.find((s) => s.getAttribute("aria-label") === "Prepare ahead").value = "3";
    buttons(page, "Create routine")[0].click();
    await flush();
    assert.equal(saved.length, 0, "a vault routine needs one chosen phone");
    page.element.querySelectorAll("input").find((i) => i.type === "checkbox" && i.value === "phone-a").checked = true;
    buttons(page, "Create routine")[0].click();
    await flush();
    assert.equal(saved[0].vaultItemId, "vi_shoplogin000000000");
    assert.equal(saved[0].preauth, 3);
    assert.deepEqual(saved[0].deviceIds, ["phone-a"]);
  } finally {
    page.destroy();
  }
});

test("a prepared lease ends 30 minutes after its own run, days ahead, and not beyond 8 days", async () => {
  const pk = unhex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0");
  const now = Date.UTC(2026, 8, 28, 9, 0);
  const pending = { taskId: "tsk_ahead0001", title: "Check", deviceId: "phone-a", vaultItemId: "vi_shoplogin000000000", accountId: "acc_shop0001", handle: "@shop",
    place: "package:com.example.shop", dueAt: now + 3 * 86_400_000, ahead: true, deviceKey: { publicKey: toB64(pk), fingerprint: await fingerprint(pk) } };
  const item = { id: "vi_shoplogin000000000", kind: "login", label: "Shop", username: "me", secret: "pw", url: "", notes: "", totp: "", accountId: "acc_shop0001",
    version: 1, createdBy: "owner", updatedAt: 1, moved: false };
  const [envelope] = await sealForTask(pending, item, now);
  assert.equal(JSON.parse(envelope.aad).expiresAt, pending.dueAt + 30 * 60_000);
  await assert.rejects(sealForTask({ ...pending, dueAt: now + MAX_AHEAD_MS + 1 }, item, now), /8 days/);
});
