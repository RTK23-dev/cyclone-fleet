import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseRoute } from "../.test-dist/core/router.js";
import { studioFrame } from "../.test-dist/services/idGenerator.js";
import { createIdGeneratorPage } from "../.test-dist/pages/idGeneratorPage.js";

const path = "/v1/ports/starters/id-generator";
const fixture = () => ({ state: "found", detail: "Studio found", apiBase: "http://127.0.0.1:8787", plugin: null,
  config: { version: 1, apiBase: "", agentEnabled: true, apps: [], routines: [], whenToUse: "Company employee IDs", instructions: "" },
  ports: ["file.out", "x.id-generator.generate", "value.in", "file.in"], skill: { description: "Company IDs", workflow: "Wait for complete" },
  panelUrl: "http://127.0.0.1:5173/plugins/id-generator?embed=1", settingsUrl: "http://127.0.0.1:5173/settings/id-generator?embed=1", health: { worker: true } });
const button = (page, label) => page.element.querySelectorAll("button").find(x => x.textContent.includes(label));

test("native starter route and frames are limited to local Studio paths", () => {
  assert.equal(parseRoute("#/command/ports/id-generator").view, "id-generator");
  assert.ok(studioFrame(fixture().panelUrl, "/plugins/id-generator", "http://127.0.0.1:8765"));
  for (const url of ["https://example.com/plugins/id-generator?embed=1", "http://127.0.0.1:5173/evil?embed=1", "http://user:password@127.0.0.1:5173/plugins/id-generator?embed=1"])
    assert.equal(studioFrame(url, "/plugins/id-generator"), null);
  assert.equal(studioFrame(fixture().panelUrl, "/plugins/id-generator", "http://127.0.0.1:5173"), null);
});

test("discovery never pairs; owner edits persist; consent and active status gate the manual panel", async () => {
  installMiniDom(); let data = fixture();
  const fake = fakeGateway({
    [`GET ${path}`]: () => data,
    [`POST ${path}/config`]: ({ body }) => { data.config = body; return data; },
    [`POST ${path}/connect`]: ({ body }) => { data.plugin = { status: "active", serves: body.allowed.map(port => ({ port, allowed: true })) }; return data; },
    [`GET ${path}/schema`]: () => ({ signature: { default: "paul-signature" } }),
  });
  const page = createIdGeneratorPage({ client: new GatewayClient({ token: "t", fetch: fake.fetch }) });
  try {
    await flush(); assert.equal(fake.calls.length, 1); assert.equal(page.element.querySelectorAll("iframe").length, 0);
    const input = page.element.querySelectorAll("input").find(x => x.getAttribute("aria-label") === "Allowed apps");
    input.value = "com.example.company";
    button(page, "Save usage").click(); await flush();
    assert.deepEqual(data.config.apps, ["com.example.company"]);
    for (const x of page.element.querySelectorAll("input")) if (x.type === "checkbox") x.checked = true;
    button(page, "Connect Studio").click(); await flush();
    const call = fake.calls.find(x => x.path === path + "/connect"); assert.deepEqual(call.body.allowed, data.ports);
    assert.equal(page.element.querySelectorAll("iframe").length, 1);
    button(page, "Studio defaults").click(); assert.match(page.element.querySelectorAll("iframe")[0].src, /settings\/id-generator/);
    button(page, "View request schema").click(); await flush(); assert.match(page.element.textContent, /paul-signature/);
    assert.ok(!fake.calls.some(x => JSON.stringify(x.body ?? {}).includes("secret")));
  } finally { page.destroy(); }
});
