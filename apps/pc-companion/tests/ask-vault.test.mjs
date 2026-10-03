import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  ASK_NEEDS_SECRET_FIXTURE,
  ASK_SAMPLE_SNAPSHOTS,
  GLASS_SECRET_PRIVACY_COPY,
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  GLASS_VAULT_FIXTURE_SLOTS,
  describeAskHud,
  needsSecretWaitTitle,
  phoneSupportsGlassAtlas,
  vaultFixtureContainsSecretValues,
  vaultSlotPresenceLabel,
} from "../.test-dist/core/fleet.js";

const askSource = fs.readFileSync(new URL("../src/pages/askPage.ts", import.meta.url), "utf8");
const vaultSource = fs.readFileSync(new URL("../src/pages/vaultPage.ts", import.meta.url), "utf8");
const secretsSource = fs.readFileSync(new URL("../src/ui/secretsCard.ts", import.meta.url), "utf8");
const appSource = fs.readFileSync(new URL("../src/app.ts", import.meta.url), "utf8");

test("Ask needs-secret fixture is a wait state, not failed", () => {
  const hud = describeAskHud(ASK_NEEDS_SECRET_FIXTURE);
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.state, "needs-secret");
  assert.equal(ASK_NEEDS_SECRET_FIXTURE.title, "Facebook needs a password");
  assert.equal(hud.state, "needs-secret");
  assert.equal(hud.isFailure, false);
  assert.equal(hud.showsSecretsCard, true);
  assert.equal(hud.waitTitle, "Needs you — Facebook password");
  assert.equal(needsSecretWaitTitle("Facebook password"), "Needs you — Facebook password");
  assert.notEqual(ASK_SAMPLE_SNAPSHOTS.failed.state, "needs-secret");
  assert.equal(describeAskHud(ASK_SAMPLE_SNAPSHOTS.failed).isFailure, true);
  assert.equal(describeAskHud(ASK_SAMPLE_SNAPSHOTS.failed).showsSecretsCard, false);
});

test("Ask page renders the needs-secret wait card from the sample snapshot", () => {
  assert.match(askSource, /previewNeedsSecret/);
  assert.match(askSource, /ASK_NEEDS_SECRET_FIXTURE/);
  assert.match(askSource, /createSecretsCard/);
  assert.match(askSource, /dataset\.state = snapshot\.state/);
  assert.match(askSource, /needs-secret/);
  assert.match(askSource, /GLASS_UPDATE_PHONE_TITLE/);
  assert.match(secretsSource, /needsSecretWaitTitle/);
  assert.match(secretsSource, /WAITING ON PHONE/);
  assert.match(secretsSource, /Type it on the phone overlay/);
  assert.match(secretsSource, /GLASS_SECRET_PRIVACY_COPY/);
  assert.equal(GLASS_SECRET_PRIVACY_COPY, "Cyclone will not keep this in chat or logs.");
  assert.match(secretsSource, /dataset\.state = "needs-secret"/);
  assert.doesNotMatch(secretsSource, /type\s*=\s*["']password["']/);
  assert.doesNotMatch(askSource, /type\s*=\s*["']password["']/);
  assert.doesNotMatch(secretsSource, /createElement\(["']input["']\)/);
});

test("Vault lists slot booleans only and has no password input", () => {
  assert.equal(vaultFixtureContainsSecretValues(), false);
  for (const slot of GLASS_VAULT_FIXTURE_SLOTS) {
    assert.equal(typeof slot.set, "boolean");
    assert.match(vaultSlotPresenceLabel(slot), /: (set|missing)$/);
    assert.doesNotMatch(JSON.stringify(slot), /"value"|otp|cookie|token/i);
  }
  assert.match(vaultSource, /vaultSlotPresenceLabel/);
  assert.match(vaultSource, /GLASS_VAULT_FIXTURE_SLOTS/);
  assert.match(vaultSource, /data-slot-set|dataset\.slotSet/);
  assert.doesNotMatch(vaultSource, /type\s*=\s*["']password["']/);
  assert.doesNotMatch(vaultSource, /createElement\(["']input["']\)/);
  assert.doesNotMatch(vaultSource, /<input/i);
  assert.match(vaultSource, /No password, OTP, cookie, or token fields/);
});

test("Ask and Vault stay honest when Mobile is below 5.0", () => {
  assert.equal(phoneSupportsGlassAtlas("4.8.0"), false);
  assert.equal(GLASS_UPDATE_PHONE_TITLE, "Update Cyclone on the phone");
  assert.match(GLASS_UPDATE_PHONE_COPY, /Mobile to 5\.0/);
  assert.match(askSource, /phoneSupportsGlassAtlas/);
  assert.match(vaultSource, /phoneSupportsGlassAtlas/);
  assert.match(askSource, /GLASS_UPDATE_PHONE_COPY/);
  assert.match(vaultSource, /GLASS_UPDATE_PHONE_COPY/);
});

test("Glass shell brands Ask Maps Vault and keeps ChatGPT", () => {
  assert.match(appSource, /Cyclone Glass/);
  assert.match(appSource, /\["ask", .*, "Ask"\]/);
  assert.match(appSource, /\["maps", .*, "Maps"\]/);
  assert.match(appSource, /\["vault", .*, "Vault"\]/);
  assert.match(appSource, /\["chatgpt", .*, "ChatGPT"\]/);
  assert.match(appSource, /createAskPage/);
  assert.match(appSource, /createVaultPage/);
  assert.match(appSource, /createMapsPage/);
  assert.match(appSource, /from "\.\/pages\/mapsPage/);
  assert.doesNotMatch(appSource, /createMapsPlaceholderPage/);
  assert.doesNotMatch(appSource, /Maps board ships next/);
  assert.doesNotMatch(appSource, /atlasClient/);
});
