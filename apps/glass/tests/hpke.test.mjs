import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { AEAD_AES128GCM, AEAD_AES256GCM, fingerprint, hex, schedule, seal, unhex } from "../.test-dist/services/hpke.js";

// RFC 9180 appendix A.3.1: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode.
const V = {
  info: "4f6465206f6e2061204772656369616e2055726e",
  pkEm: "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4",
  skEm: "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb",
  pkRm: "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0",
  sharedSecret: "c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8",
  key: "868c066ef58aae6dc589b6cfdd18f97e",
  baseNonce: "4e0bc5018beba4bf004cca59",
  pt: "4265617574792069732074727574682c20747275746820626561757479",
  aad0: "436f756e742d30",
  ct0: "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434",
};

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");

async function ephemeral(skHex, pkHex) {
  const pk = unhex(pkHex);
  const jwk = { kty: "EC", crv: "P-256", d: b64url(unhex(skHex)), x: b64url(pk.slice(1, 33)), y: b64url(pk.slice(33)), ext: true };
  const privateKey = await crypto.subtle.importKey("jwk", jwk, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const publicKey = await crypto.subtle.importKey("raw", pk, { name: "ECDH", namedCurve: "P-256" }, true, []);
  return { privateKey, publicKey };
}

test("HPKE matches RFC 9180 A.3.1 (base mode, sequence 0)", async () => {
  const sealed = await seal(unhex(V.pkRm), unhex(V.info), unhex(V.aad0), unhex(V.pt), { aead: AEAD_AES128GCM, ephemeral: await ephemeral(V.skEm, V.pkEm) });
  assert.equal(hex(sealed.enc), V.pkEm);
  assert.equal(hex(sealed.ct), V.ct0);
});

test("the key schedule matches the RFC's intermediate values", async () => {
  const pair = await ephemeral(V.skEm, V.pkEm);
  const pkR = await crypto.subtle.importKey("raw", unhex(V.pkRm), { name: "ECDH", namedCurve: "P-256" }, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pkR }, pair.privateKey, 256));
  const ks = await schedule(dh, unhex(V.pkEm), unhex(V.pkRm), unhex(V.info), AEAD_AES128GCM);
  assert.equal(hex(ks.sharedSecret), V.sharedSecret);
  assert.equal(hex(ks.key), V.key);
  assert.equal(hex(ks.baseNonce), V.baseNonce);
});

test("fresh envelopes differ and bad keys are refused", async () => {
  const a = await seal(unhex(V.pkRm), new Uint8Array(), new Uint8Array(), new Uint8Array([1, 2, 3]));
  const b = await seal(unhex(V.pkRm), new Uint8Array(), new Uint8Array(), new Uint8Array([1, 2, 3]));
  assert.notEqual(hex(a.enc), hex(b.enc));
  assert.equal(a.ct.length, 3 + 16);
  await assert.rejects(seal(new Uint8Array(33), new Uint8Array(), new Uint8Array(), new Uint8Array(1)));
  assert.match(await fingerprint(unhex(V.pkRm)), /^([0-9A-F]{4} ){7}[0-9A-F]{4}$/);
});

// The cross-implementation fixture: Glass seals with the production suite to the RFC's recipient key, with a fixed
// ephemeral key; the phone's HpkeTest opens the same bytes with the RFC's recipient private key.
test("the Cyclone suite fixture the phone opens is reproduced byte for byte", async () => {
  const path = new URL("../../mobile/app/src/test/resources/cyclone-hpke-fixture.json", import.meta.url);
  const fixture = JSON.parse(fs.readFileSync(path, "utf8"));
  const sealed = await seal(unhex(V.pkRm), new TextEncoder().encode(fixture.info), new TextEncoder().encode(fixture.aad),
    new TextEncoder().encode(fixture.plaintext), { aead: AEAD_AES256GCM, ephemeral: await ephemeral(V.skEm, V.pkEm) });
  assert.equal(hex(sealed.enc), fixture.enc);
  assert.equal(hex(sealed.ct), fixture.ct);
});
