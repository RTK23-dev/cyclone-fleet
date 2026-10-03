import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { GlassApp } from "../.test-dist/app.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";

const READY = { deviceId: "d1", name: "Pixel 8", state: "READY", paired: true, planes: { aiTrust: "TRUSTED" }, mobileVersion: "5.0.0-alpha.7.dev1" };

function start({ devices = [READY], hash = "#/apps", fail = null } = {}) {
  installMiniDom();
  const root = document.createElement("div");
  const location = { hash };
  const listeners = [];
  const storage = new Map();
  const client = new GatewayClient({
    token: "t",
    fetch: async (url) => {
      if (fail) return new Response(JSON.stringify(fail.body ?? {}), { status: fail.status });
      if (url === "/v1/fleet") return new Response(JSON.stringify({ devices }), { status: 200 });
      if (/^\/v1\/devices\/d[12]\/apps$/.test(url)) return new Response(JSON.stringify({ apps: [], truncated: false }), { status: 200 });
      return new Response(JSON.stringify({ detail: { code: "NOT_FOUND", message: url } }), { status: 404 });
    },
  });
  const app = new GlassApp({
    root,
    client,
    version: "1.0.0-alpha.1",
    location,
    storage: { getItem: (k) => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v) },
    setHash: (h) => {
      location.hash = h;
      listeners.forEach((fn) => fn());
    },
    onHashChange: (fn) => {
      listeners.push(fn);
      return () => {};
    },
    setInterval: () => 1,
    clearInterval() {},
  });
  return { app, root, location, storage };
}

test("shell renders brand, phone picker and nav, and marks the active section", async () => {
  const { app, root } = start();
  await app.start();
  assert.match(root.textContent, /Cyclone Glass/);
  assert.match(root.textContent, /1\.0\.0-alpha\.1/);
  assert.match(root.querySelector(".device-picker").textContent, /Cyclone 5\.0\.0-alpha\.7\.dev1/);
  const active = root.querySelector(".nav-item.active");
  assert.equal(active.dataset.section, "apps");
  assert.match(root.querySelector(".page-title").textContent, /Apps/);
  app.stop();
});

test("navigation swaps pages; app routes keep Apps active", async () => {
  const { app, root, location } = start();
  await app.start();
  location.hash = "#/settings";
  app["onHashChange"]();
  assert.match(root.querySelector(".page-title").textContent, /Settings/);
  assert.match(root.textContent, /None in Glass/);
  location.hash = "#/apps/package%3Acom.google.android.gm/map";
  app["onHashChange"]();
  assert.equal(root.querySelector(".nav-item.active").dataset.section, "apps");
  app.stop();
});

test("honest states: gateway down, expired session, no phone, old Cyclone", async () => {
  let s = start({ fail: { status: 503, body: { detail: { code: "PROVIDER_UNAVAILABLE", message: "x" } } } });
  await s.app.start();
  assert.match(s.root.textContent, /gateway is not answering/);
  s = start({ fail: { status: 401 } });
  await s.app.start();
  assert.match(s.root.textContent, /session has ended/);
  s = start({ devices: [] });
  await s.app.start();
  assert.match(s.root.textContent, /No phone connected/);
  s = start({ devices: [{ ...READY, mobileVersion: "4.8.0" }] });
  await s.app.start();
  assert.match(s.root.textContent, /Update Cyclone on the phone/);
});

test("choosing a phone is remembered for the tab", async () => {
  const other = { ...READY, deviceId: "d2", name: "Pixel 7" };
  const { app, storage, root } = start({ devices: [READY, other] });
  await app.start();
  app["selectDevice"]("d2");
  assert.equal(storage.get("cyclone.glass.device.v1"), "d2");
  assert.match(root.querySelector(".device-picker").textContent, /Pixel 7/);
});

test("like WhatsApp Web: with no connected phone, Glass opens on Devices; with one, on Home", async () => {
  let s = start({ devices: [{ deviceId: "d2", name: "Galaxy", state: "UNPAIRED", paired: false }], hash: "#/" });
  await s.app.start();
  assert.equal(s.location.hash, "#/devices");
  assert.match(s.root.querySelector(".page-title").textContent, /Devices/);
  assert.equal(s.root.querySelector(".nav-item.active").dataset.section, "devices");
  s.app.stop();
  s = start({ hash: "#/" });
  await s.app.start();
  assert.match(s.root.querySelector(".page-title").textContent, /Home/);
  s.app.stop();
  // A phone that is not connected yet sends the user to Devices from any page.
  s = start({ devices: [{ deviceId: "d2", name: "Galaxy", state: "UNPAIRED", paired: false }], hash: "#/apps" });
  await s.app.start();
  assert.match(s.root.textContent, /not connected to Glass yet/);
  assert.match(s.root.textContent, /Connect this phone/);
  s.app.stop();
});
