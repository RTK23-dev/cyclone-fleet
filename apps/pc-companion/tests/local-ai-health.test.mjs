import test from "node:test";
import assert from "node:assert/strict";
import {
  adapterRowLabel,
  aiStatusLabel,
  overallAiState,
  phoneDoesNotFailAi,
  phoneHealthState,
  repairActions,
} from "../.test-dist/core/localAiHealth.js";

test("AI connected plus phone disconnected is not an AI failure", () => {
  const ai = { state: "CONNECTED", adapters: [{ id: "codex", name: "Codex", state: "CONNECTED", detected: true, configured: true, detail: "" }] };
  const phone = { state: "DISCONNECTED", reachable: false, readyDeviceCount: 0 };
  assert.equal(overallAiState(["CONNECTED"]), "CONNECTED");
  assert.equal(phoneHealthState({ reachable: false, deviceCount: 0, readyDeviceCount: 0 }), "DISCONNECTED");
  assert.equal(phoneDoesNotFailAi(ai, phone), true);
  assert.equal(aiStatusLabel("CONNECTED"), "Connected");
  const actions = repairActions({ ai, phone, engineReady: true });
  assert.deepEqual(actions.map((item) => item.layer), ["phone"]);
  assert.equal(actions[0].label, "Connect phone");
});

test("missing local AI config routes to Configure, not a phone repair", () => {
  const ai = { state: "DETECTED", adapters: [{ id: "cursor", name: "Cursor", state: "DETECTED", detected: true, configured: false, detail: "" }] };
  const phone = { state: "READY", reachable: true, readyDeviceCount: 1 };
  const actions = repairActions({ ai, phone, engineReady: true });
  assert.deepEqual(actions.map((item) => [item.layer, item.label]), [["local_ai", "Configure"]]);
});

test("stopped engine routes to Restart independently", () => {
  const ai = { state: "CONFIGURED", adapters: [{ id: "codex", name: "Codex", state: "CONFIGURED", detected: true, configured: true, detail: "" }] };
  const phone = { state: "READY", reachable: true, readyDeviceCount: 1 };
  const actions = repairActions({ ai, phone, engineReady: false });
  assert.deepEqual(actions.map((item) => [item.layer, item.label]), [["engine", "Restart"]]);
});

test("provider rows stay provider-neutral", () => {
  assert.equal(adapterRowLabel({ id: "grok", name: "Grok", state: "CONNECTED", detected: true, configured: true, detail: "" }), "Grok ✓");
  assert.equal(adapterRowLabel({ id: "codex", name: "Codex", state: "CONNECTED", detected: true, configured: true, detail: "" }), "Codex ✓");
  assert.equal(adapterRowLabel({ id: "cursor", name: "Cursor", state: "DETECTED", detected: true, configured: false, detail: "" }), "Cursor available");
});
