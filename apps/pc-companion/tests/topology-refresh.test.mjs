import test from "node:test";
import assert from "node:assert/strict";
import { TopologyRefreshGate } from "../.test-dist/core/topologyRefresh.js";

test("bursty topology events cause one bounded follow-up refresh", () => {
  const gate = new TopologyRefreshGate();
  assert.equal(gate.begin(false), true);
  assert.equal(gate.begin(false), false);
  assert.equal(gate.begin(true), false);
  assert.equal(gate.begin(false), false);
  assert.equal(gate.finish(), true);
  assert.equal(gate.begin(true), true);
  assert.equal(gate.finish(), null);
});
