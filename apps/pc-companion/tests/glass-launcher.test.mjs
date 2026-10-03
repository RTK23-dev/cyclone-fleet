import test from "node:test";
import assert from "node:assert/strict";
import { openCycloneGlass } from "../.test-dist/services/glassLauncher.js";

const gateway = { httpBase: "http://127.0.0.1:51234/", getBearer: () => "secret-bearer" };

test("One asks its own gateway for a one-time code and opens only that link", async () => {
  const calls = [];
  const opened = [];
  const address = await openCycloneGlass(
    gateway,
    async (path) => {
      opened.push(path);
      return "http://127.0.0.1:51234/glass/";
    },
    async (url, init) => {
      calls.push({ url, auth: init.headers.Authorization, method: init.method });
      return new Response(JSON.stringify({ code: "abcdefghijklmnopqrstuvwx", path: "/glass/#code=abcdefghijklmnopqrstuvwx", bundle: true }), { status: 200 });
    },
  );
  assert.deepEqual(calls, [{ url: "http://127.0.0.1:51234/v1/glass/launch-code", auth: "Bearer secret-bearer", method: "POST" }]);
  assert.deepEqual(opened, ["/glass/#code=abcdefghijklmnopqrstuvwx"]);
  assert.equal(address, "http://127.0.0.1:51234/glass/");
  assert.ok(!opened.join("").includes("secret-bearer"), "the bearer never reaches the browser");
});

test("unexpected links, old runtimes and missing bundles are refused", async () => {
  const never = async () => assert.fail("must not open a browser");
  const reply = (body, status = 200) => async () => new Response(JSON.stringify(body), { status });
  await assert.rejects(openCycloneGlass(gateway, never, reply({ path: "https://evil.example/" })), /unexpected Glass link/);
  await assert.rejects(openCycloneGlass(gateway, never, reply({}, 404)), /predates Glass/);
  await assert.rejects(openCycloneGlass(gateway, never, reply({ path: "/glass/#code=abcdefghijklmnopqrstuvwx", bundle: false })), /not included/);
  await assert.rejects(openCycloneGlass(undefined, never), /not running/);
});
