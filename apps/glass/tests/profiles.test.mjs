import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { cssColor, parseProfileApps, parseProfiles } from "../.test-dist/services/profiles.js";
import { createProfilesBar } from "../.test-dist/pages/profilesBar.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

installMiniDom();
const mounted = [];
test.afterEach(() => {
  while (mounted.length) mounted.pop().destroy();
});

const B = "Cyclone_0123456789abcdef";
const LIST = { profiles: [
  { id: "main", label: "Profile A", emoji: null, color: null, ready: true, current: true, inTrash: false },
  { id: B, label: "Brand B", emoji: "🛍", color: "#FF7C4DFF", ready: true, current: false, inTrash: false },
  { id: "Cyclone_fedcba9876543210", label: "Old", emoji: null, color: null, ready: true, current: false, inTrash: true }], current: "main" };
const APPS = { apps: [{ package: "com.instagram.android", label: "Instagram" }], available: [{ package: "com.whatsapp", label: "WhatsApp" }], truncated: false };
const ctx = (fetch, devices = [{ id: "pixel8-abc", name: "Pixel 8", sessionReady: true }]) => ({
  client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.46", devices, device: devices[0], devicesError: null,
  navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));

test("profiles and their apps parse defensively", () => {
  const parsed = parseProfiles({ ...LIST, profiles: [...LIST.profiles, { id: "../x", label: "bad" }] });
  assert.deepEqual(parsed.profiles.map((p) => p.id), ["main", B, "Cyclone_fedcba9876543210"]);
  assert.equal(parsed.current, "main");
  assert.equal(cssColor("#FF7C4DFF"), "#7C4DFF");
  assert.equal(cssColor("red"), null);
  assert.equal(parseProfileApps(APPS).available[0].packageName, "com.whatsapp");
});

test("the profile strip switches the phone from the PC and manages a profile's apps", async () => {
  const posts = [];
  let current = "main";
  const gw = fakeGateway({
    "GET /v1/devices/pixel8-abc/profiles": () => ({ ...LIST, profiles: LIST.profiles.map((p) => ({ ...p, current: p.id === current })), current }),
    [`GET /v1/devices/pixel8-abc/profiles/${B}/apps`]: () => APPS,
    [`POST /v1/devices/pixel8-abc/profiles/${B}/switch`]: () => { posts.push("switch"); current = B; return { switched: true, current: B }; },
    [`POST /v1/devices/pixel8-abc/profiles/${B}/apps`]: ({ body }) => { posts.push(`${body.action}:${body.package}`); return { done: true }; },
  });
  const selected = [];
  const notes = [];
  const bar = createProfilesBar(ctx(gw.fetch), (t) => notes.push(t), (id, packages) => selected.push([id, packages ? [...packages] : null]));
  mounted.push(bar);
  await bar.load("pixel8-abc");
  assert.equal(bar.element.hidden, false);
  assert.match(bar.element.textContent, /Profile A/);
  assert.match(bar.element.textContent, /Brand B/);
  assert.ok(!bar.element.textContent.includes("Old"), "a profile in Recently deleted stays out");
  assert.match(bar.element.textContent, /Profile A is in front/);
  bar.element.querySelectorAll("button").find((b) => b.dataset.profile === B).click();
  await flush();
  assert.deepEqual(selected.at(-1), [B, ["com.instagram.android"]]);
  button(bar.element, "Switch the phone to Brand B").click();
  await flush(20);
  assert.deepEqual(posts, ["switch"]);
  assert.match(bar.element.textContent, /Brand B is in front/);
  button(bar.element, "Manage Brand B").click();
  await flush();
  assert.match(bar.element.textContent, /Apps in Brand B/);
  globalThis.confirm = () => true;
  button(bar.element, "Remove").click();
  await flush(20);
  assert.ok(button(bar.element, "Done managing apps"), "the manager stays open");
  button(bar.element, "Add to profile").click();
  await flush(20);
  delete globalThis.confirm;
  assert.deepEqual(posts, ["switch", "remove:com.instagram.android", "install:com.whatsapp"]);
  assert.ok(notes.some((n) => /Added WhatsApp to Brand B/.test(n)));
});

test("Accounts lists a Cyclone profile's apps when that profile is chosen", async () => {
  const gw = fakeGateway({
    "GET /v1/cc/overview": () => ({}),
    "GET /v1/cc/accounts": () => ({ accounts: [] }),
    "GET /v1/cc/approvals": () => ({ approvals: [] }),
    "GET /v1/cc/tasks": () => ({ tasks: [] }),
    "GET /v1/cc/signup/maps": () => ({ deviceId: "pixel8-abc", maps: [], fresh: true }),
    "GET /v1/devices/pixel8-abc/apps": () => ({ apps: [
      { placeId: "package:com.instagram.android", kind: "package", label: "Instagram", packageName: "com.instagram.android", installed: true },
      { placeId: "package:com.whatsapp", kind: "package", label: "WhatsApp", packageName: "com.whatsapp", installed: true }], truncated: false }),
    "GET /v1/devices/pixel8-abc/profiles": () => LIST,
    [`GET /v1/devices/pixel8-abc/profiles/${B}/apps`]: () => ({ apps: [{ package: "com.instagram.android", label: "Instagram" },
      { package: "com.brand.shop", label: "Brand Shop" }], available: [], truncated: false }),
  });
  const page = createCommandPage(ctx(gw.fetch), "accounts");
  mounted.push(page);
  await flush(20);
  const list = () => page.element.querySelectorAll("button").filter((b) => b.dataset.package).map((b) => b.dataset.package);
  assert.deepEqual(list().sort(), ["com.instagram.android", "com.whatsapp"]);
  page.element.querySelectorAll("button").find((b) => b.dataset.profile === B).click();
  await flush(20);
  assert.deepEqual(list().sort(), ["com.brand.shop", "com.instagram.android"]);
});
