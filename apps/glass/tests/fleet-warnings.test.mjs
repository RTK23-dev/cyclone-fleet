import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseCommandResult } from "../.test-dist/services/fleet.js";
import { createFleetView } from "../.test-dist/pages/fleetView.js";

installMiniDom();
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent.startsWith(label));
const plan = { assignments: [{ deviceId: "dev_a", label: "Work Phone", goal: "Open Maps" }], clarification: null, needsConfirmation: true, confidence: "high", kind: "single", notes: [] };

test("the parser keeps preflight warnings and drops junk", () => {
  assert.deepEqual(parseCommandResult({ warnings: ["Tablet battery 7%.", 5, null] }).warnings, ["Tablet battery 7%."]);
  assert.deepEqual(parseCommandResult({}).warnings, []);
});

test("the owner sees WHY a confirmation is asked, and the warning clears after cancel", async () => {
  const gw = fakeGateway({
    "GET /v1/fleet/overview": () => ({ phones: [], counts: { phones: 0, ready: 0, busy: 0, needYou: 0 } }),
    "GET /v1/fleet/missions": () => ({ missions: [] }),
    "POST /v1/fleet/command": () => ({ dispatched: false, needsConfirmation: true, warnings: ["Work Phone battery 7%.", "Work Phone already has 2 open task(s); this waits behind them."], plan }),
  });
  const view = createFleetView({ client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, later: () => () => {}, newRequestId: () => "req-test-1", socket: () => ({ close() {}, onopen: null, onmessage: null, onclose: null, onerror: null }) });
  await flush();
  const input = view.element.querySelector("input");
  input.value = "open Maps on Work Phone";
  button(view.element, "Run").click();
  await flush();
  assert.match(view.element.textContent, /battery 7%/);
  assert.match(view.element.textContent, /waits behind them/);
  button(view.element, "Cancel").click();
  assert.doesNotMatch(view.element.textContent, /battery 7%/);
  view.destroy();
});
