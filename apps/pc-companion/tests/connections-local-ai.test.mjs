import test from "node:test";
import assert from "node:assert/strict";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";
import { overallAiState } from "../.test-dist/core/localAiHealth.js";

test("mock Local AI connectors stay independent of phone readiness", async () => {
  const service = new MockDesktopService(4);
  const connectors = await service.listConnectors();
  const ids = connectors.map((item) => item.id);
  assert.ok(ids.includes("codex"));
  assert.ok(ids.includes("grok"));
  assert.ok(ids.includes("cursor"));
  const codex = connectors.find((item) => item.id === "codex");
  assert.equal(codex.phoneState, "READY");
  assert.equal(codex.aiState, "DETECTED");
  assert.notEqual(codex.state, "NEEDS_ATTENTION");
});

test("connected Local AI is not collapsed when a phone later disconnects", () => {
  assert.equal(overallAiState(["CONNECTED", "UNKNOWN"]), "CONNECTED");
  assert.notEqual(overallAiState(["CONNECTED"]), "FAILED");
});
