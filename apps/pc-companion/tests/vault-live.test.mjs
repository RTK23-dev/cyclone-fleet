import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import {
  ASK_NEEDS_SECRET_FIXTURE,
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  GLASS_VAULT_FIXTURE_SLOTS,
  slotsFromPresence,
  vaultSlotPresenceLabel,
} from "../.test-dist/core/fleet.js";
import { createVaultPage } from "../.test-dist/pages/vaultPage.js";

const vaultSource = fs.readFileSync(new URL("../src/pages/vaultPage.ts", import.meta.url), "utf8");
const secretsSource = fs.readFileSync(new URL("../src/ui/secretsCard.ts", import.meta.url), "utf8");
const fleetSource = fs.readFileSync(new URL("../src/core/fleet.ts", import.meta.url), "utf8");

installMiniDom();

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

test("slotsFromPresence keeps boolean password keys and drops non-booleans", () => {
  const mapped = slotsFromPresence("Facebook", {
    password: true,
    otp: "123456",
    cookie: { value: "sid=abc" },
    token: 1,
    backup: false,
  });
  assert.deepEqual(mapped, [
    { id: "facebook-password", placeLabel: "Facebook", slotLabel: "password", set: true },
    { id: "facebook-backup", placeLabel: "Facebook", slotLabel: "backup", set: false },
  ]);
  for (const slot of mapped) {
    assert.equal(typeof slot.set, "boolean");
    assert.equal("value" in slot, false);
    assert.doesNotMatch(JSON.stringify(slot), /"value"/);
    assert.equal(slot.otp, undefined);
    assert.equal(slot.cookie, undefined);
  }
});

test("4.8.0 without previewSlots shows the update banner and does not list fixtures as live", () => {
  const page = createVaultPage({ mobileVersion: "4.8.0" });
  const banner = page.element.querySelector(".glass-compat-banner");
  assert.equal(banner?.hidden, false);
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_TITLE));
  assert.match(page.element.textContent, new RegExp(GLASS_UPDATE_PHONE_COPY.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.equal(page.element.querySelectorAll(".vault-slot-row").length, 0);
  assert.doesNotMatch(page.element.textContent, /Facebook password: set/);
  assert.doesNotMatch(page.element.textContent, /Gmail password: missing/);
  assert.doesNotMatch(page.element.textContent, /Dummy Gmail/);
  assert.doesNotMatch(page.element.textContent, /Presence from the connected phone/);
  const note = page.element.querySelector(".vault-source-note");
  assert.equal(note?.dataset.vaultSource, "blocked");
  page.destroy();
});

test("previewSlots lists fixture rows as sample inventory, never live", () => {
  const page = createVaultPage({ previewSlots: true });
  assert.ok(page.element.querySelectorAll(".vault-slot-row").length >= 3);
  for (const slot of GLASS_VAULT_FIXTURE_SLOTS) {
    assert.match(page.element.textContent, new RegExp(vaultSlotPresenceLabel(slot).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.match(page.element.textContent, /Sample inventory — not live/);
  assert.doesNotMatch(page.element.textContent, /Presence from the connected phone/);
  const note = page.element.querySelector(".vault-source-note");
  assert.equal(note?.dataset.vaultSource, "sample");
  page.destroy();
});

test("atlas-ready loadSlots paints boolean presence and never a value field", async () => {
  const slot = { id: "facebook-password", placeLabel: "Facebook", slotLabel: "password", set: true };
  const page = createVaultPage({
    mobileVersion: "5.0.0-alpha.1",
    loadSlots: async () => [slot],
  });
  assert.match(page.element.textContent, /Loading vault slots/);
  await flush();
  const row = page.element.querySelector('[data-slot-id="facebook-password"]');
  assert.ok(row);
  assert.equal(row.dataset.slotSet, "true");
  assert.match(row.textContent, /Facebook password: set/);
  assert.ok(row.querySelector(".vault-slot-set"));
  assert.match(page.element.textContent, /Presence from the connected phone\. Values stay in Android Keystore/);
  assert.equal(page.element.querySelector(".glass-compat-banner")?.hidden, true);
  assert.doesNotMatch(JSON.stringify(slot), /"value"|otp|cookie/i);
  assert.doesNotMatch(page.element.textContent, /hunter2|"value"/);
  page.destroy();
});

test("empty live loadSlots is honest empty, not fixture Facebook/Gmail", async () => {
  const page = createVaultPage({
    mobileVersion: "5.0.0-alpha.1",
    loadSlots: async () => [],
  });
  await flush();
  assert.match(page.element.textContent, /No vault slots to show/);
  assert.doesNotMatch(page.element.textContent, /Facebook password: set/);
  assert.doesNotMatch(page.element.textContent, /Gmail password: missing/);
  assert.match(page.element.textContent, /Presence from the connected phone/);
  page.destroy();
});

test("clicking a missing slot with onRequestSlot fires the slot id and shows the phone wait card", () => {
  const fired = [];
  const page = createVaultPage({
    mobileVersion: "5.0.0-alpha.1",
    slots: [
      { id: "gmail-password", placeLabel: "Gmail", slotLabel: "password", set: false },
      { id: "facebook-password", placeLabel: "Facebook", slotLabel: "password", set: true },
    ],
    onRequestSlot: (slotId) => fired.push(slotId),
  });
  const missing = page.element.querySelector('[data-slot-id="gmail-password"]');
  const present = page.element.querySelector('[data-slot-id="facebook-password"]');
  assert.ok(missing);
  present.click();
  assert.deepEqual(fired, []);
  missing.click();
  assert.deepEqual(fired, ["gmail-password"]);
  assert.match(page.element.textContent, /Type it on the phone overlay/);
  assert.match(page.element.textContent, /WAITING ON PHONE/);
  assert.equal(page.element.querySelector(".secrets-card")?.dataset.state, "needs-secret");
  page.destroy();
});

test("Vault and secrets card still have no password input", () => {
  assert.doesNotMatch(vaultSource, /type\s*=\s*["']password["']/);
  assert.doesNotMatch(vaultSource, /createElement\(["']input["']\)/);
  assert.doesNotMatch(vaultSource, /<input/i);
  assert.doesNotMatch(vaultSource, /localStorage/);
  assert.doesNotMatch(secretsSource, /type\s*=\s*["']password["']/);
  assert.doesNotMatch(secretsSource, /createElement\(["']input["']\)/);
  assert.doesNotMatch(secretsSource, /localStorage/);
});

test("malformed slot string values are ignored and never rendered", () => {
  const page = createVaultPage({
    mobileVersion: "5.0.0-alpha.1",
    slots: [
      {
        id: "facebook-password",
        placeLabel: "Facebook",
        slotLabel: "password",
        set: true,
        value: "hunter2",
        otp: "123456",
        cookie: "sid=abc",
      },
      {
        id: "poison-slot",
        placeLabel: "Poison",
        slotLabel: "password",
        set: "hunter2-as-set",
        value: "should-not-appear",
      },
    ],
  });
  assert.match(page.element.textContent, /Facebook password: set/);
  assert.equal(page.element.querySelector('[data-slot-id="poison-slot"]'), null);
  assert.doesNotMatch(page.element.textContent, /hunter2/);
  assert.doesNotMatch(page.element.textContent, /123456/);
  assert.doesNotMatch(page.element.textContent, /sid=abc/);
  assert.doesNotMatch(page.element.textContent, /should-not-appear/);
  assert.doesNotMatch(page.element.textContent, /Poison password/);
  page.destroy();
});

test("omitted mobileVersion keeps today's sample fixture default (merge-safe before 009)", () => {
  const page = createVaultPage({ devices: [] });
  assert.match(page.element.textContent, /Facebook password: set/);
  assert.match(page.element.textContent, /Sample inventory — not live/);
  assert.doesNotMatch(page.element.textContent, /Presence from the connected phone/);
  page.destroy();
});

test("Agent 005 Ask fixture title is unchanged", () => {
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.title, "Facebook needs a password");
  assert.match(fleetSource, /title: "Facebook needs a password"/);
});
