import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { fillRecipe, looksSecret, parseApproval, parseTask } from "../.test-dist/services/command.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8" }, { id: "phone-b", name: "Galaxy S24" }];

function ctx(fetch, navigate = () => {}) {
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.28", devices: DEVICES, device: DEVICES[0], devicesError: null, navigate, selectDevice() {}, refreshDevices: async () => {} };
}
const buttons = (page, label) => page.element.querySelectorAll("button").filter((b) => b.textContent === label);
const OVERVIEW = { accounts: 1, openTasks: 1, running: 1, routines: 0, routinesPaused: 0, approvals: 1, succeeded24h: 2, failed24h: 0 };
const ACCOUNT = { id: "acc_abcdefgh", service: "com.instagram.android", handle: "@mybrand", ownerBasis: "mine", twofa: "totp", allowedDevices: [], status: "active", notes: "", lastOutcome: null, locked: false };
const SEND = { id: "apv_1", taskId: "tsk_1", title: "Reply to Anna", deviceId: "phone-a", kind: "approval", text: "Send this message?", gate: "send",
  send: { text: "See you at 6", recipient: "Anna", app: "WhatsApp" }, choices: [], fields: [], approvableHere: true, answerHere: true, state: "open", createdAt: Date.now() };

function routes(extra = {}) {
  return {
    "GET /v1/cc/overview": () => OVERVIEW,
    "GET /v1/cc/accounts": () => ({ accounts: [ACCOUNT] }),
    "GET /v1/cc/approvals": () => ({ approvals: [SEND] }),
    ...extra,
  };
}

test("the Command Center has its own routes and section", () => {
  assert.deepEqual(parseRoute("#/command"), { name: "command", tab: "home" });
  assert.deepEqual(parseRoute("#/command/routines"), { name: "command", tab: "routines" });
  assert.deepEqual(parseRoute("#/command/nonsense"), { name: "command", tab: "home" });
  assert.equal(routeHref({ name: "command", tab: "accounts" }), "#/command/accounts");
  assert.equal(sectionOf({ name: "command", tab: "tasks" }), "command");
});

test("parsers are defensive and recipes fill their inputs", () => {
  assert.equal(parseTask({ status: "weird" }).status, "scheduled");
  assert.equal(parseApproval({ kind: "shell" }).kind, "question");
  assert.equal(parseApproval({ approvableHere: "yes" }).approvableHere, false);
  assert.equal(fillRecipe("Post {caption} to {account}", { caption: "New drop" }), "Post New drop to {account}");
  assert.ok(looksSecret("password: hunter2"));
  assert.ok(!looksSecret("Post the weekly video"));
});

test("an approval shows the exact message and approves by its id", async () => {
  installMiniDom();
  const gateway = fakeGateway(routes({ "POST /v1/cc/approvals/apv_1/answer": () => ({ handled: true, detail: "Approved." }) }));
  const page = createCommandPage(ctx(gateway.fetch), "approvals");
  await flush();
  assert.match(page.element.textContent, /See you at 6/);
  assert.match(page.element.textContent, /Anna/);
  assert.match(page.element.textContent, /Pixel 8/);
  buttons(page, "Approve and send")[0].click();
  await flush();
  const call = gateway.calls.find((c) => c.path === "/v1/cc/approvals/apv_1/answer");
  assert.deepEqual(call.body, { action: "approve" });
  assert.equal(call.auth, "Bearer t");
  page.destroy();
});

test("a redacted approval and secure input are answered on the phone only", async () => {
  installMiniDom();
  const hidden = { ...SEND, id: "apv_2", approvableHere: false };
  const secret = { ...SEND, id: "apv_3", kind: "secret", send: null, gate: null, text: "Type your password", approvableHere: false, answerHere: false };
  const gateway = fakeGateway(routes({ "GET /v1/cc/approvals": () => ({ approvals: [hidden, secret] }) }));
  const page = createCommandPage(ctx(gateway.fetch), "approvals");
  await flush();
  assert.equal(buttons(page, "Approve and send").length, 0);
  assert.match(page.element.textContent, /Approve this one on the phone/);
  assert.match(page.element.textContent, /Secrets never go through the PC/);
  assert.equal(buttons(page, "Decline").length, 1, "only the redacted approval can be declined here");
  page.destroy();
});

test("a task is created with its phone, account and an idempotency key; secrets are refused first", async () => {
  installMiniDom();
  const created = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    "POST /v1/cc/tasks": ({ body }) => { created.push(body); return { id: "tsk_2", title: body.title ?? body.goal, goal: body.goal, status: "scheduled" }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "tasks");
  await flush();
  const goal = page.element.querySelector("textarea");
  const [phone, account] = page.element.querySelectorAll("select");
  goal.value = "Log in with password: hunter2";
  buttons(page, "Create task")[0].click();
  await flush();
  assert.equal(created.length, 0);
  assert.match(page.element.textContent, /Leave passwords and codes out/);
  goal.value = "Post today's video";
  phone.value = "phone-b";
  account.value = ACCOUNT.id;
  buttons(page, "Create task")[0].click();
  await flush();
  assert.equal(created.length, 1);
  assert.equal(created[0].goal, "Post today's video");
  assert.equal(created[0].deviceId, "phone-b");
  assert.equal(created[0].accountId, ACCOUNT.id);
  assert.match(created[0].requestId, /^[0-9a-f]{24}$/);
  page.destroy();
});

test("a routine is created with its phones and schedule", async () => {
  installMiniDom();
  const saved = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/routines": () => ({ routines: [] }),
    "POST /v1/cc/routines": ({ body }) => { saved.push(body); return { id: "rtn_1", ...body, scheduleLabel: "Weekdays at 09:00" }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "routines");
  await flush();
  const inputs = page.element.querySelectorAll("input");
  inputs.find((i) => i.getAttribute("aria-label") === "Title").value = "Morning check";
  page.element.querySelector("textarea").value = "Check the shop inbox";
  inputs.find((i) => i.type === "checkbox" && i.value === "phone-a").checked = true;
  inputs.find((i) => i.type === "checkbox" && i.value === "phone-b").checked = true;
  buttons(page, "Create routine")[0].click();
  await flush();
  assert.deepEqual(saved[0].deviceIds, ["phone-a", "phone-b"]);
  assert.deepEqual(saved[0].schedule, { kind: "daily", time: "09:00", days: [1, 2, 3, 4, 5] });
  page.destroy();
});

test("accounts list shows whose account it is and never asks for a password", async () => {
  installMiniDom();
  const gateway = fakeGateway(routes());
  const page = createCommandPage(ctx(gateway.fetch), "accounts");
  await flush();
  assert.match(page.element.textContent, /@mybrand/);
  assert.match(page.element.textContent, /Authenticator app/);
  assert.match(page.element.textContent, /never a password/);
  assert.equal(page.element.querySelectorAll("input").some((i) => i.type === "password"), false);
  page.destroy();
});

test("a task can sign in with a vault login of its account, on one chosen phone", async () => {
  installMiniDom();
  const created = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    "GET /v1/cc/vault": () => ({ exists: true, items: [{ id: "vi_shoplogin000000000", accountId: ACCOUNT.id, kind: "login", updatedAt: Date.now() },
      { id: "vi_othernote0000000000", accountId: ACCOUNT.id, kind: "note", updatedAt: Date.now() }] }),
    "POST /v1/cc/tasks": ({ body }) => { created.push(body); return { id: "tsk_3", title: "x", goal: body.goal, status: "scheduled" }; },
  }));
  const page = createCommandPage(ctx(gateway.fetch), "tasks");
  await flush();
  const [phone, account, signIn] = page.element.querySelectorAll("select");
  account.value = ACCOUNT.id;
  account.dispatchEvent({ type: "change" });
  await flush();
  const choices = signIn.querySelectorAll("option").map((o) => o.value);
  assert.deepEqual(choices, ["", "vi_shoplogin000000000"], "logins only, never notes");
  page.element.querySelector("textarea").value = "Check orders";
  signIn.value = "vi_shoplogin000000000";
  buttons(page, "Create task")[0].click();
  await flush();
  assert.equal(created.length, 0, "a vault sign-in needs one chosen phone");
  phone.value = "phone-a";
  buttons(page, "Create task")[0].click();
  await flush();
  assert.equal(created[0].vaultItemId, "vi_shoplogin000000000");
  assert.equal(JSON.stringify(created[0]).includes("password"), false);
  page.destroy();
});
