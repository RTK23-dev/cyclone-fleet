import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseMrz } from "../.test-dist/services/command.js";
import { createConnectionsView } from "../.test-dist/pages/connectionsView.js";

const status = { state: "found", detail: "Studio found. Worker is offline.", apiBase: "http://127.0.0.1:8787", uiBase: "http://127.0.0.1:5173",
  checkedAt: Date.now(), ready: false, connectionId: null, settingsChanged: false,
  recipe: { mcpServers: { "employee-id": { command: "node", args: ["C:\\Studio\\server.js"] } } },
  health: { api: true, worker: false, photoshop: true, template: true, dryRun: false } };
const button = (view, text) => view.element.querySelectorAll("button").find(b => b.textContent === text);

function setup(extra = {}, mrz = status) {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/cc/connections": () => ({ connections: [], mrz }),
    "GET /v1/cc/calls": () => ({ calls: [] }),
    "GET /v1/cc/artifacts": () => ({ artifacts: [] }), ...extra,
  });
  const messages = [];
  const ctx = { client: new GatewayClient({ token: "test", fetch: gateway.fetch }), devices: [], device: null };
  const view = createConnectionsView(ctx, (text) => messages.push(text));
  return { view, messages };
}

test("MRZ parser rejects external or credential-bearing Studio links", () => {
  assert.equal(parseMrz(null), null);
  assert.equal(parseMrz({ ...status, uiBase: "javascript:alert(1)" }).uiBase, "http://127.0.0.1:5173");
  assert.equal(parseMrz({ ...status, apiBase: "https://external.example" }).apiBase, "http://127.0.0.1:8787");
  assert.equal(parseMrz({ ...status, ready: "yes" }).ready, false);
});

test("dedicated MRZ entry is useful without a phone and keeps API/worker readiness separate", async () => {
  let connected = 0;
  const { view } = setup({ "POST /v1/cc/integrations/mrz/connect": () => { connected++; return { id: "con_mrz", status: "needs_approval" }; } });
  try {
    await flush();
    assert.match(view.element.textContent, /MRZ Studio · Employee ID/);
    assert.match(view.element.textContent, /Worker: not ready/);
    assert.match(view.element.textContent, /API: ready/);
    assert.equal(connected, 0, "viewing discovery never connects or executes a program");
    button(view, "Connect MRZ Studio").click();
    await flush();
    assert.equal(connected, 1);
  } finally { view.destroy(); }
});

test("second path fills the existing paste form but does not submit or approve it", async () => {
  const { view } = setup();
  try {
    await flush();
    const field = view.element.querySelectorAll("textarea").find(e => e.getAttribute("aria-label") === "Server config");
    // The program form is detached in address mode. Switch once to expose its mini-DOM node.
    button(view, "Program on this PC").click();
    const config = view.element.querySelectorAll("textarea").find(e => e.getAttribute("aria-label") === "Server config");
    config.focus = () => {};
    button(view, "Use pasted Glass JSON").click();
    assert.deepEqual(JSON.parse(config.value), status.recipe);
    assert.match(view.element.textContent, /Nothing runs until you say so/);
  } finally { view.destroy(); }
});

test("changed recipes, offline Studio and older runtimes cannot silently connect", async () => {
  for (const state of [{ ...status, settingsChanged: true }, { ...status, state: "offline" }, null]) {
    const { view } = setup({}, state);
    try {
      await flush();
      assert.equal(button(view, "Connect MRZ Studio").disabled, true);
      assert.ok(button(view, "Use pasted Glass JSON"));
      if (state?.settingsChanged) assert.match(view.element.textContent, /never silently replaced/);
    } finally { view.destroy(); }
  }
});

test("checking Studio uses authenticated gateway route; no direct browser scan", async () => {
  let checks = 0;
  const { view } = setup({ "POST /v1/cc/integrations/mrz/check": () => { checks++; return status; } });
  try {
    await flush(); button(view, "Check for Studio").click(); await flush();
    assert.equal(checks, 1);
  } finally { view.destroy(); }
});
