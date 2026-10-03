import test from "node:test";
import assert from "node:assert/strict";
import { INFO, leaseId, placeLabel, sealForTask, secretsOf } from "../.test-dist/services/delivery.js";
import { AEAD_AES256GCM, fingerprint, schedule, unhex } from "../.test-dist/services/hpke.js";
import { fromB64, toB64 } from "../.test-dist/services/vault.js";

// The RFC 9180 A.3 recipient key plays the phone.
const skRm = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2";
const pkRm = unhex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0");
const b64url = (b) => Buffer.from(b).toString("base64url");

async function phoneOpens(envelope) {
  const jwk = { kty: "EC", crv: "P-256", d: b64url(unhex(skRm)), x: b64url(pkRm.slice(1, 33)), y: b64url(pkRm.slice(33)) };
  const sk = await crypto.subtle.importKey("jwk", jwk, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const enc = fromB64(envelope.enc);
  const pkE = await crypto.subtle.importKey("raw", enc, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pkE }, sk, 256));
  const ks = await schedule(dh, enc, pkRm, new TextEncoder().encode(INFO), AEAD_AES256GCM);
  const key = await crypto.subtle.importKey("raw", ks.key, { name: "AES-GCM" }, false, ["decrypt"]);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: ks.baseNonce, additionalData: new TextEncoder().encode(envelope.aad) }, key, fromB64(envelope.ct));
  return new TextDecoder().decode(plain);
}

const NOW = Date.UTC(2026, 8, 28, 9, 0);
async function pending(extra = {}) {
  return { taskId: "tsk_abcdefgh", title: "Sign in", deviceId: "phone-a", vaultItemId: "vi_shoplogin000000000", accountId: "acc_shop0001",
    handle: "@shop", place: "package:com.example.shop", dueAt: null,
    deviceKey: { publicKey: toB64(pkRm), fingerprint: await fingerprint(pkRm) }, ...extra };
}
const LOGIN = { id: "vi_shoplogin000000000", kind: "login", label: "Shop", username: "me", secret: "CANARY-deliver-pw", url: "", notes: "",
  totp: "JBSW Y3DP EHPK 3PXP", accountId: "acc_shop0001", version: 1, createdBy: "owner", updatedAt: 1, moved: false };

test("a login seals its password and seed, each bound to the task, phone, app, slot and time", async () => {
  const envelopes = await sealForTask(await pending(), LOGIN, NOW);
  assert.deepEqual(envelopes.map((e) => e.slot), ["password", "otp"]);
  for (const e of envelopes) {
    const bound = JSON.parse(e.aad);
    assert.deepEqual(Object.keys(bound).sort(), ["deviceKey", "expiresAt", "leaseId", "place", "slot", "taskId"]);
    assert.equal(bound.taskId, "tsk_abcdefgh");
    assert.equal(bound.place, "package:com.example.shop");
    assert.equal(bound.leaseId, e.leaseId);
    assert.equal(bound.expiresAt, NOW + 30 * 60_000);
    assert.match(e.leaseId, /^ls_[0-9a-f]{11}[A-Za-z0-9_-]{12}$/);
    assert.equal(e.aad.includes("CANARY"), false);
    assert.equal(e.ct.includes("CANARY"), false);
  }
  assert.equal(await phoneOpens(envelopes[0]), "CANARY-deliver-pw");
  assert.equal(await phoneOpens(envelopes[1]), "JBSWY3DPEHPK3PXP");
  // Tampered bound data does not open.
  await assert.rejects(phoneOpens({ ...envelopes[0], aad: envelopes[0].aad.replace("com.example.shop", "com.evil.phish") }));
});

test("nothing is sealed to a key that does not match its fingerprint, or for a moved item", async () => {
  await assert.rejects(sealForTask(await pending({ deviceKey: { publicKey: toB64(pkRm), fingerprint: "0000 0000 0000 0000 0000 0000 0000 0000" } }), LOGIN, NOW), /does not match/);
  await assert.rejects(sealForTask(await pending(), { ...LOGIN, moved: true }, NOW), /changed outside Glass/);
  await assert.rejects(sealForTask(await pending(), { ...LOGIN, id: "vi_other00000000000000" }, NOW), /not the vault item/);
  await assert.rejects(sealForTask(await pending({ deviceKey: null }), LOGIN, NOW), /Trust/);
});

test("a later task's lease lasts until 30 minutes after it is due, never more than a day", async () => {
  const later = await sealForTask(await pending({ dueAt: NOW + 2 * 60 * 60_000 }), { ...LOGIN, totp: "" }, NOW);
  assert.equal(JSON.parse(later[0].aad).expiresAt, NOW + 2 * 60 * 60_000 + 30 * 60_000);
  const far = await sealForTask(await pending({ dueAt: NOW + 3 * 24 * 60 * 60_000 }), { ...LOGIN, totp: "" }, NOW);
  assert.equal(JSON.parse(far[0].aad).expiresAt, NOW + 24 * 60 * 60_000);
});

test("secrets, lease ids and places are what the phone expects", () => {
  assert.deepEqual(secretsOf({ ...LOGIN, totp: "" }).map((s) => s.slot), ["password"]);
  assert.deepEqual(secretsOf({ ...LOGIN, kind: "totp", secret: "JBSWY3DPEHPK3PXP", totp: "" }).map((s) => s.slot), ["otp"]);
  assert.deepEqual(secretsOf({ ...LOGIN, kind: "note", secret: "x", totp: "" }), []);
  assert.notEqual(leaseId(NOW), leaseId(NOW));
  assert.equal(placeLabel("chrome:https://example.com"), "https://example.com");
  assert.equal(placeLabel("package:com.example.shop"), "the com.example.shop app");
});
