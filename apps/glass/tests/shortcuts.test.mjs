import test from "node:test";
import assert from "node:assert/strict";
import { shortcutFor } from "../.test-dist/core/shortcuts.js";

test("g then a letter goes to a page; / searches; typing and modifiers are left alone", () => {
  const state = { pendingG: null };
  assert.equal(shortcutFor({ key: "g" }, state, 1000), null);
  assert.deepEqual(shortcutFor({ key: "r" }, state, 1500), { kind: "go", route: { name: "runs" } });
  assert.equal(shortcutFor({ key: "r" }, state, 1600), null, "r alone does nothing");
  shortcutFor({ key: "g" }, state, 2000);
  assert.equal(shortcutFor({ key: "h" }, state, 4000), null, "the chord times out");
  assert.deepEqual(shortcutFor({ key: "/" }, state, 5000), { kind: "search" });
  shortcutFor({ key: "g" }, state, 6000);
  assert.equal(shortcutFor({ key: "h", target: { tagName: "input" } }, state, 6100), null, "never while typing");
  assert.equal(shortcutFor({ key: "/", target: { tagName: "TEXTAREA" } }, state, 6200), null);
  shortcutFor({ key: "g" }, state, 7000);
  assert.equal(shortcutFor({ key: "k", ctrlKey: true }, state, 7100), null, "browser shortcuts stay the browser's");
  shortcutFor({ key: "G" }, state, 8000);
  assert.deepEqual(shortcutFor({ key: "K" }, state, 8100), { kind: "go", route: { name: "knowledge" } });
});
