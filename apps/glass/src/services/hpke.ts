/**
 * HPKE (RFC 9180), base mode, single shot, with WebCrypto only. Plan 33 C2: a vault secret is sealed in this tab to one
 * phone's device key; the gateway relays bytes it cannot open.
 *
 * Suite: DHKEM(P-256, HKDF-SHA256) + HKDF-SHA256 + AES-256-GCM (kem 0x0010, kdf 0x0001, aead 0x0002). The AEAD is a
 * parameter so the implementation can be checked against RFC 9180 appendix A.3 (the same KEM and KDF with AES-128-GCM).
 * The phone (apps/mobile, `Hpke.kt`) implements the same steps; a shared fixture pins both to the same bytes.
 */

const subtle = (): SubtleCrypto => globalThis.crypto.subtle;
const enc = new TextEncoder();

export const KEM_P256 = 0x0010;
export const KDF_SHA256 = 0x0001;
export const AEAD_AES128GCM = 0x0001;
export const AEAD_AES256GCM = 0x0002;

function i2osp(value: number, length: number): Uint8Array {
  const out = new Uint8Array(length);
  for (let i = length - 1, v = value; i >= 0; i -= 1, v = Math.floor(v / 256)) out[i] = v & 255;
  return out;
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

async function hmac(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const k = await subtle().importKey("raw", (key.length ? key : new Uint8Array(32)) as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await subtle().sign("HMAC", k, data as BufferSource));
}

async function extract(salt: Uint8Array, ikm: Uint8Array): Promise<Uint8Array> {
  return hmac(salt, ikm);
}

async function expand(prk: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  let t: Uint8Array = new Uint8Array(0);
  const out: Uint8Array[] = [];
  for (let i = 1; out.reduce((n, p) => n + p.length, 0) < length; i += 1) {
    t = await hmac(prk, concat(t, info, new Uint8Array([i])));
    out.push(t);
  }
  return concat(...out).slice(0, length);
}

const HPKE_V1 = enc.encode("HPKE-v1");

async function labeledExtract(suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array) {
  return extract(salt, concat(HPKE_V1, suite, enc.encode(label), ikm));
}

async function labeledExpand(suite: Uint8Array, prk: Uint8Array, label: string, info: Uint8Array, length: number) {
  return expand(prk, concat(i2osp(length, 2), HPKE_V1, suite, enc.encode(label), info), length);
}

export interface KeySchedule { sharedSecret: Uint8Array; key: Uint8Array; baseNonce: Uint8Array }

/** DHKEM ExtractAndExpand plus the base-mode key schedule. Shared by seal and (in tests) open. */
export async function schedule(dh: Uint8Array, encapsulated: Uint8Array, recipient: Uint8Array, info: Uint8Array, aead: number): Promise<KeySchedule> {
  const kemSuite = concat(enc.encode("KEM"), i2osp(KEM_P256, 2));
  const eaePrk = await labeledExtract(kemSuite, new Uint8Array(0), "eae_prk", dh);
  const sharedSecret = await labeledExpand(kemSuite, eaePrk, "shared_secret", concat(encapsulated, recipient), 32);
  const suite = concat(enc.encode("HPKE"), i2osp(KEM_P256, 2), i2osp(KDF_SHA256, 2), i2osp(aead, 2));
  const pskIdHash = await labeledExtract(suite, new Uint8Array(0), "psk_id_hash", new Uint8Array(0));
  const infoHash = await labeledExtract(suite, new Uint8Array(0), "info_hash", info);
  const context = concat(new Uint8Array([0]), pskIdHash, infoHash);
  const secret = await labeledExtract(suite, sharedSecret, "secret", new Uint8Array(0));
  const nk = aead === AEAD_AES128GCM ? 16 : 32;
  return {
    sharedSecret,
    key: await labeledExpand(suite, secret, "key", context, nk),
    baseNonce: await labeledExpand(suite, secret, "base_nonce", context, 12),
  };
}

export interface Sealed { enc: Uint8Array; ct: Uint8Array }

/**
 * Seal [plaintext] to the uncompressed P-256 public key [recipient] (65 bytes). [ephemeral] is for test vectors only;
 * in use a fresh key pair is made for every envelope.
 */
export async function seal(
  recipient: Uint8Array, info: Uint8Array, aad: Uint8Array, plaintext: Uint8Array,
  options: { aead?: number; ephemeral?: CryptoKeyPair } = {},
): Promise<Sealed> {
  if (recipient.length !== 65 || recipient[0] !== 4) throw new Error("The phone's key is not an uncompressed P-256 point.");
  const aead = options.aead ?? AEAD_AES256GCM;
  const pkR = await subtle().importKey("raw", recipient as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const pair = options.ephemeral ?? (await subtle().generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]) as CryptoKeyPair);
  const dh = new Uint8Array(await subtle().deriveBits({ name: "ECDH", public: pkR }, pair.privateKey, 256));
  const encapsulated = new Uint8Array(await subtle().exportKey("raw", pair.publicKey));
  const ks = await schedule(dh, encapsulated, recipient, info, aead);
  dh.fill(0);
  const key = await subtle().importKey("raw", ks.key as BufferSource, { name: "AES-GCM" }, false, ["encrypt"]);
  const ct = new Uint8Array(await subtle().encrypt({ name: "AES-GCM", iv: ks.baseNonce as BufferSource, additionalData: aad as BufferSource }, key, plaintext as BufferSource));
  ks.key.fill(0);
  ks.sharedSecret.fill(0);
  return { enc: encapsulated, ct };
}

export function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function unhex(value: string): Uint8Array {
  const clean = value.replace(/\s/g, "");
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** The fingerprint both screens show for a phone's device key: SHA-256 of the key, first 16 bytes, grouped. */
export async function fingerprint(publicKey: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await subtle().digest("SHA-256", publicKey as BufferSource));
  return hex(digest.slice(0, 16)).toUpperCase().match(/.{4}/g)!.join(" ");
}
