import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { accountAppRows, mapSummary, parseSignupMaps, signupState, tableColumns } from "../.test-dist/services/signup.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const DEVICES = [{ id: "phone-a", name: "Pixel 8", sessionReady: true }, { id: "phone-b", name: "Galaxy S24", sessionReady: false, connectionLabel: "Offline" }];
const ctx = (fetch) => ({ client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.43", devices: DEVICES, device: DEVICES[0], devicesError: null,
  navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
const mounted = [];
test.afterEach(() => {
  while (mounted.length) mounted.pop().destroy();
});

const field = (key, label, kind, extra = {}) => ({ key, label, kind, required: true, hint: "", choices: [], ...extra });
const page = (index, title, fields, check = null) => ({ index, title, continue: "Next", check, fields });
const INSTAGRAM = {
  package: "com.instagram.android", app: "Instagram", appVersion: "350.0", mappedAt: Date.now() - 60_000, finalLabel: "Sign up", complete: true,
  fetchedAt: Date.now(), tableId: null,
  pages: [
    page(1, "Enter your email", [field("email", "Email", "email")]),
    page(2, "Confirm your email", [field("confirmation_code", "Confirmation code", "text")], "email_code"),
    page(3, "What's your name?", [field("full_name", "Full name", "full_name")]),
    page(4, "Create a password", [field("password", "Password", "password", { hint: "At least 6 characters" })]),
    page(5, "Gender", [field("gender", "Gender", "gender", { required: false, choices: ["Female", "Male", "Custom"] })]),
  ],
};
const APPS = [
  { placeId: "package:com.whatsapp", kind: "package", label: "WhatsApp", packageName: "com.whatsapp", installed: true },
  { placeId: "package:com.instagram.android", kind: "package", label: "Instagram", packageName: "com.instagram.android", installed: true },
  { placeId: "package:com.tiktok", kind: "package", label: "TikTok", packageName: "com.tiktok", installed: false },
  { placeId: "chrome:https://example.com", kind: "chrome-origin", label: "example.com", origin: "https://example.com" },
  { placeId: "package:com.zzz.notes", kind: "package", label: "Notes", packageName: "com.zzz.notes", installed: true },
];
const ACCOUNT = { id: "acc_abcdefgh", service: "com.whatsapp", handle: "+Shop line", ownerBasis: "company", twofa: "none", allowedDevices: ["phone-a"],
  status: "active", notes: "", lastOutcome: null, locked: false };

test("maps parse defensively and read as a template", () => {
  const parsed = parseSignupMaps({ deviceId: "phone-a", maps: [INSTAGRAM, { app: "no package" }], fresh: false, note: "kept" });
  assert.equal(parsed.maps.length, 1);
  assert.equal(parsed.fresh, false);
  const map = parsed.maps[0];
  assert.equal(map.pages[1].check, "email_code");
  assert.equal(mapSummary(map), "5 pages · 5 fields · Email code");
  // Codes, passwords and photos never become columns.
  assert.deepEqual(tableColumns(map), ["Email", "Full name", "Gender"]);
  assert.equal(signupState(map, null), "mapped");
  assert.equal(signupState({ ...map, complete: false }, null), "partial");
  assert.equal(signupState(undefined, { status: "running" }), "mapping");
});

test("a phone's installed apps list accounts and sign-ups first", () => {
  const maps = parseSignupMaps({ maps: [INSTAGRAM] }).maps;
  const tasks = [{ id: "tsk_1", recipe: "signup_map:com.zzz.notes", deviceId: "phone-a", run: null, status: "running" }];
  const rows = accountAppRows(APPS, maps, [ACCOUNT], tasks, "phone-a");
  assert.deepEqual(rows.map((r) => r.app.label), ["WhatsApp", "Instagram", "Notes"]);
  assert.deepEqual(rows.map((r) => r.state), ["none", "mapped", "mapping"]);
  assert.equal(rows[0].accounts.length, 1);
  // The account is limited to phone-a; phone-b sees no account and no running mapping.
  const other = accountAppRows(APPS, maps, [ACCOUNT], tasks, "phone-b");
  assert.deepEqual(other.map((r) => [r.app.label, r.accounts.length, r.state]), [["Instagram", 0, "mapped"], ["Notes", 0, "none"], ["WhatsApp", 0, "none"]]);
  assert.deepEqual(accountAppRows(APPS, maps, [], [], "phone-a", "insta").map((r) => r.packageName), ["com.instagram.android"]);
});

function routes(extra = {}) {
  return {
    "GET /v1/cc/overview": () => ({}),
    "GET /v1/cc/accounts": () => ({ accounts: [ACCOUNT] }),
    "GET /v1/cc/approvals": () => ({ approvals: [] }),
    "GET /v1/devices/phone-a/apps": () => ({ apps: APPS, truncated: false }),
    "GET /v1/cc/signup/maps": () => ({ deviceId: "phone-a", maps: [INSTAGRAM], fresh: true }),
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    ...extra,
  };
}

const appButton = (page, pkg) => page.element.querySelectorAll("button").find((b) => b.dataset.package === pkg);
const button = (page, label) => page.element.querySelectorAll("button").find((b) => b.textContent === label);

test("Accounts: phone → apps → an app's accounts and its mapped sign-up", async () => {
  installMiniDom();
  const made = [];
  const gateway = fakeGateway(routes({
    "POST /v1/cc/signup/table": ({ body }) => { made.push(body); return { id: "tb_signups00001", title: "Instagram sign-ups" }; },
  }));
  const view = createCommandPage(ctx(gateway.fetch), "accounts");
  mounted.push(view);
  await flush();
  const text = view.element.textContent;
  assert.match(text, /Pixel 8/);
  assert.match(text, /Galaxy S24/);
  assert.match(text, /WhatsApp/);
  assert.ok(!text.includes("TikTok"), "an uninstalled app stays out");
  assert.match(text, /All accounts/);
  appButton(view, "com.whatsapp").click();
  assert.match(view.element.textContent, /\+Shop line/);
  assert.match(view.element.textContent, /Map the sign-up/);
  appButton(view, "com.instagram.android").click();
  const detail = view.element.textContent;
  assert.match(detail, /5 pages · 5 fields · Email code/);
  assert.match(detail, /You: Email code/);
  assert.match(detail, /Table columns: Email, Full name, Gender/);
  assert.equal(view.element.querySelectorAll("input").some((i) => i.type === "password"), false);
  button(view, "Make the sign-up table").click();
  await flush();
  assert.deepEqual(made, [{ deviceId: "phone-a", package: "com.instagram.android" }]);
  assert.match(view.element.textContent, /Made “Instagram sign-ups”/);
  assert.ok(button(view, "Create accounts"), "the sign-up table has Create accounts");
});

test("Accounts: mapping a sign-up says whose account it is", async () => {
  installMiniDom();
  const started = [];
  let mapping = false;
  const gateway = fakeGateway(routes({
    "POST /v1/cc/signup/map": ({ body }) => { started.push(body); mapping = true; return { id: "tsk_2", status: "scheduled" }; },
    "GET /v1/cc/tasks": () => ({ tasks: mapping ? [{ id: "tsk_2", title: "Map the sign-up of WhatsApp", goal: "x", recipe: "signup_map:com.whatsapp",
      deviceId: "phone-a", status: "running" }] : [] }),
  }));
  const view = createCommandPage(ctx(gateway.fetch), "accounts");
  mounted.push(view);
  await flush();
  appButton(view, "com.whatsapp").click();
  const whose = view.element.querySelectorAll("select").find((s) => s.getAttribute("aria-label") === "Whose first account");
  whose.value = "client";
  whose.dispatchEvent({ type: "change" });
  button(view, "Map the sign-up").click();
  await flush();
  assert.deepEqual(started, [{ deviceId: "phone-a", package: "com.whatsapp", app: "WhatsApp", ownerBasis: "client" }]);
  assert.match(view.element.textContent, /Mapping the WhatsApp sign-up: The phone is walking the sign-up now/);
  assert.match(view.element.textContent, /Mapping…/);
});

test("Accounts: when the phone is away, the last maps show with a note", async () => {
  installMiniDom();
  const gateway = fakeGateway(routes({
    "GET /v1/cc/signup/maps": () => ({ deviceId: "phone-a", maps: [INSTAGRAM], fresh: false, note: "The phone couldn't be reached; these are the last maps it sent." }),
  }));
  const view = createCommandPage(ctx(gateway.fetch), "accounts");
  mounted.push(view);
  await flush();
  assert.match(view.element.textContent, /last maps it sent/);
  assert.match(view.element.textContent, /Sign-up mapped/);
});

test("Accounts: a stuck mapping says why, can be cancelled, and shows the last try", async () => {
  installMiniDom();
  let status = "waiting_device";
  const cancelled = [];
  const gateway = fakeGateway(routes({
    "GET /v1/cc/tasks": () => ({ tasks: [{ id: "tsk_9", title: "Map the sign-up of WhatsApp", goal: "x", recipe: "signup_map:com.whatsapp",
      deviceId: "phone-a", status, cause: status === "cancelled" ? "Cancelled from the Command Center." : "You have control of the phone.", createdAt: 5 }] }),
    "POST /v1/cc/tasks/tsk_9/cancel": () => { cancelled.push(true); status = "cancelled"; return { id: "tsk_9", status: "cancelled" }; },
  }));
  const view = createCommandPage(ctx(gateway.fetch), "accounts");
  mounted.push(view);
  await flush();
  appButton(view, "com.whatsapp").click();
  assert.match(view.element.textContent, /You have control of the phone/);
  button(view, "Cancel mapping").click();
  await flush();
  assert.equal(cancelled.length, 1);
  assert.match(view.element.textContent, /Last try was cancelled: Cancelled from the Command Center/);
  assert.ok(button(view, "Map the sign-up"), "it can be started again");
});
