import test from "node:test";
import assert from "node:assert/strict";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { establishSession, readLaunchCode, SESSION_STORAGE_KEY } from "../.test-dist/core/session.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";

function memoryStorage() {
  const map = new Map();
  return { map, getItem: (k) => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: (k) => map.delete(k) };
}

const CODE = "Abcdefghijklmnop_1234";

test("routes round-trip, including place ids with slashes and colons", () => {
  for (const route of [{ name: "home" }, { name: "apps" }, { name: "phone" }, { name: "settings" }, { name: "app", placeId: "package:com.google.android.gm", tab: "map" }]) {
    assert.deepEqual(parseRoute(routeHref(route)), route);
  }
  const web = { name: "app", placeId: "chrome-origin:https://www.facebook.com/", tab: "map" };
  assert.deepEqual(parseRoute(routeHref(web)), web);
  assert.deepEqual(parseRoute(""), { name: "home" }, "Glass opens on Home");
  assert.deepEqual(parseRoute("#/nope"), { name: "home" });
  assert.deepEqual(parseRoute("#/apps/%E0%A4%A"), { name: "apps" }, "bad escapes fall back to Apps");
});

test("launch code is read from the hash and validated", () => {
  assert.equal(readLaunchCode(`#code=${CODE}`), CODE);
  assert.equal(readLaunchCode(`#/apps?code=${CODE}`), CODE);
  assert.equal(readLaunchCode("#code=short"), null);
  assert.equal(readLaunchCode("#code=<script>aaaaaaaaaaaaaaa"), null);
  assert.equal(readLaunchCode("#/apps"), null);
});

test("code exchange stores the bearer for this tab only and drops the code from the address bar", async () => {
  const storage = memoryStorage();
  const calls = [];
  let hash = null;
  const result = await establishSession({
    hash: `#code=${CODE}`,
    storage,
    replaceHash: (h) => (hash = h),
    fetch: async (url, init) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ token: "bearer-1" }), { status: 200 });
    },
  });
  assert.deepEqual(result, { state: "ready", token: "bearer-1" });
  assert.equal(hash, "#/", "code removed before the exchange completes");
  assert.equal(calls[0].url, "/v1/glass/session");
  assert.deepEqual(JSON.parse(calls[0].init.body), { code: CODE });
  assert.equal(calls[0].init.credentials, "omit");
  assert.equal(storage.map.get(SESSION_STORAGE_KEY), "bearer-1");
});

test("rejected, unreachable and missing sessions ask for the launcher", async () => {
  const base = { storage: memoryStorage(), replaceHash() {} };
  assert.deepEqual(
    await establishSession({ ...base, hash: `#code=${CODE}`, fetch: async () => new Response("{}", { status: 403 }) }),
    { state: "needs-launch", reason: "code-rejected" },
  );
  const down = await establishSession({ ...base, hash: `#code=${CODE}`, fetch: async () => { throw new TypeError("offline"); } });
  assert.equal(down.reason, "gateway-unreachable");
  assert.deepEqual(await establishSession({ ...base, hash: "#/apps", fetch: async () => assert.fail("no fetch") }), {
    state: "needs-launch",
    reason: "no-session",
  });
  const stored = memoryStorage();
  stored.setItem(SESSION_STORAGE_KEY, "kept");
  assert.deepEqual(await establishSession({ ...base, storage: stored, hash: "#/phone", fetch: async () => assert.fail() }), {
    state: "ready",
    token: "kept",
  });
});

test("gateway client sends the bearer and normalises errors", async () => {
  const seen = [];
  let expired = 0;
  const client = new GatewayClient({
    token: "t0k",
    onSessionExpired: () => expired++,
    fetch: async (url, init) => {
      seen.push({ url, auth: new Headers(init.headers).get("Authorization"), cache: init.cache });
      if (url.endsWith("/ok")) return new Response(JSON.stringify({ ok: 1 }), { status: 200 });
      if (url.endsWith("/busy")) return new Response(JSON.stringify({ detail: { code: "ASK_BUSY", message: "Busy.", retryable: true } }), { status: 409 });
      if (url.endsWith("/plain")) return new Response(JSON.stringify({ detail: "Nope" }), { status: 400 });
      return new Response("{}", { status: 401 });
    },
  });
  assert.deepEqual(await client.get("/ok"), { ok: 1 });
  assert.equal(seen[0].auth, "Bearer t0k");
  assert.equal(seen[0].cache, "no-store");
  await assert.rejects(client.post("/busy", {}), (e) => e.code === "ASK_BUSY" && e.retryable && e.status === 409);
  await assert.rejects(client.get("/plain"), (e) => e.code === "HTTP_400" && e.message === "Nope");
  await assert.rejects(client.get("/gone"), (e) => e.sessionExpired);
  await assert.rejects(client.get("/gone"), (e) => e.sessionExpired);
  assert.equal(expired, 1, "expiry callback fires once");

  const down = new GatewayClient({ token: "x", fetch: async () => { throw new TypeError("refused"); } });
  await assert.rejects(down.get("/v1/fleet"), (e) => e.code === "GATEWAY_UNREACHABLE");

  const ws = client.socket("/v1/devices/d1/video?profile=focus", "http://127.0.0.1:8765");
  assert.equal(ws.url, "ws://127.0.0.1:8765/v1/devices/d1/video?profile=focus");
  assert.deepEqual(ws.protocols, ["cyclone-v1", "cyclone-token.t0k"]);
});
