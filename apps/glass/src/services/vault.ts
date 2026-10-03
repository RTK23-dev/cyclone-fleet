/**
 * The Command Center vault (plan 33, C1), browser side. Everything secret is encrypted here, with WebCrypto only,
 * before it reaches the gateway; the gateway stores ciphertext and cannot decrypt it.
 *
 * - Vault key (VK): 32 random bytes, held in this tab as a non-extractable AES-256-GCM key while unlocked.
 * - Passphrase: PBKDF2-SHA256 (600,000 iterations or more, 16-byte salt) gives the key that wraps the VK.
 * - Recovery key: 32 random bytes, shown once as text; it wraps the VK a second time.
 * - Items: each version gets its own random key, wrapped with the VK; its fields are encrypted with that key.
 *   The item id, kind and version are bound as associated data, so a box moved to another item fails to open.
 *
 * Why not Argon2id / XChaCha20 as the plan first said: Glass has no runtime dependencies (the Glass guard) and
 * WebCrypto offers PBKDF2 and AES-GCM natively. The iteration count follows OWASP's PBKDF2-SHA256 guidance.
 */
import type { GatewayClient } from "./gateway.js";

export type ItemKind = "login" | "totp" | "recovery_codes" | "note";
export const ITEM_KINDS: ItemKind[] = ["login", "totp", "recovery_codes", "note"];
export const DEFAULT_ITERATIONS = 600_000;
export const MIN_PASSPHRASE = 12;

export interface Sealed { iv: string; ct: string }
export interface Kdf { name: "PBKDF2"; hash: "SHA-256"; iterations: number }
export interface VaultMeta {
  kdf: Kdf;
  salt: string;
  wrappedVk: Sealed;
  recoveryWrappedVk: Sealed;
  keyVersion: number;
  createdAt: number;
  updatedAt: number;
}
export interface VaultRecord {
  id: string;
  accountId: string | null;
  kind: ItemKind;
  iv: string;
  ct: string;
  wrappedKey: Sealed;
  version: number;
  createdBy?: string;
  createdAt?: number;
  updatedAt?: number;
}
export interface VaultState { exists: boolean; format: number; minIterations: number; meta?: VaultMeta; items: VaultRecord[] }

/** An item's fields. Only ever in this tab's memory, and only while the vault is unlocked. */
export interface ItemFields {
  label: string;
  username: string;
  secret: string;
  url: string;
  notes: string;
  totp: string;
  /** The account the item was saved for; checked against the record's plain accountId when it opens. */
  accountId: string | null;
}
export interface OpenItem extends ItemFields {
  id: string;
  kind: ItemKind;
  version: number;
  createdBy: string;
  updatedAt: number;
  /** The plain account link no longer matches what was encrypted: someone changed it outside Glass. */
  moved: boolean;
}

export class VaultLockedError extends Error {
  constructor(message = "That passphrase does not open this vault.") {
    super(message);
    this.name = "VaultLockedError";
  }
}

const AAD = {
  vkPassphrase: "cyclone-vault/v1/vk/passphrase",
  vkRecovery: "cyclone-vault/v1/vk/recovery",
  itemKey: (id: string, version: number) => `cyclone-vault/v1/itemkey/${id}/${version}`,
  item: (id: string, kind: ItemKind, version: number) => `cyclone-vault/v1/item/${id}/${kind}/${version}`,
};

const subtle = (): SubtleCrypto => globalThis.crypto.subtle;
const text = new TextEncoder();
const untext = new TextDecoder();

export function random(n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  globalThis.crypto.getRandomValues(bytes);
  return bytes;
}

export function toB64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromB64(value: string): Uint8Array {
  const s = atob(value);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i += 1) out[i] = s.charCodeAt(i);
  return out;
}

async function aesKey(raw: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey("raw", raw as BufferSource, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function seal(key: CryptoKey, plain: Uint8Array, aad: string): Promise<Sealed> {
  const iv = random(12);
  const ct = new Uint8Array(await subtle().encrypt({ name: "AES-GCM", iv: iv as BufferSource, additionalData: text.encode(aad) }, key, plain as BufferSource));
  return { iv: toB64(iv), ct: toB64(ct) };
}

async function open(key: CryptoKey, box: Sealed, aad: string): Promise<Uint8Array> {
  const plain = await subtle().decrypt(
    { name: "AES-GCM", iv: fromB64(box.iv) as BufferSource, additionalData: text.encode(aad) }, key, fromB64(box.ct) as BufferSource,
  );
  return new Uint8Array(plain);
}

export async function deriveKek(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const base = await subtle().importKey("raw", text.encode(passphrase.normalize("NFKC")) as BufferSource, "PBKDF2", false, ["deriveKey"]);
  return subtle().deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations }, base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"],
  );
}

// ------------------------------------------------------------------ recovery key text

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function formatRecovery(bytes: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out.match(/.{1,4}/g)!.join("-");
}

export function parseRecovery(value: string): Uint8Array {
  const clean = value.toUpperCase().replace(/[\s-]/g, "");
  if (!/^[A-Z2-7]{52}$/.test(clean)) throw new VaultLockedError("A recovery key is 52 letters and digits (dashes optional).");
  return base32(clean).slice(0, 32);
}

function base32(clean: string): Uint8Array {
  const out: number[] = [];
  let bits = 0;
  let value = 0;
  for (const char of clean) {
    value = (value << 5) | B32.indexOf(char);
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return new Uint8Array(out);
}

// ------------------------------------------------------------------ the vault key

export interface CreatedVault {
  init: { kdf: Kdf; salt: string; wrappedVk: Sealed; recoveryWrappedVk: Sealed };
  vk: CryptoKey;
  /** Shown to the owner once. Never sent anywhere, never stored by Glass. */
  recoveryKey: string;
}

export async function createVault(passphrase: string, iterations = DEFAULT_ITERATIONS): Promise<CreatedVault> {
  checkPassphrase(passphrase);
  const vkRaw = random(32);
  const recoveryRaw = random(32);
  const salt = random(16);
  try {
    const kek = await deriveKek(passphrase, salt, iterations);
    const wrappedVk = await seal(kek, vkRaw, AAD.vkPassphrase);
    const recoveryWrappedVk = await seal(await aesKey(recoveryRaw), vkRaw, AAD.vkRecovery);
    const vk = await aesKey(vkRaw);
    return {
      init: { kdf: { name: "PBKDF2", hash: "SHA-256", iterations }, salt: toB64(salt), wrappedVk, recoveryWrappedVk },
      vk,
      recoveryKey: formatRecovery(recoveryRaw),
    };
  } finally {
    vkRaw.fill(0);
    recoveryRaw.fill(0);
  }
}

export function checkPassphrase(passphrase: string): void {
  if (passphrase.length < MIN_PASSPHRASE) throw new Error(`Use a passphrase of at least ${MIN_PASSPHRASE} characters.`);
  if (strength(passphrase) === "weak") throw new Error("That passphrase is too easy to guess. Try four or five unrelated words.");
}

export type Unlocker = { passphrase: string } | { recoveryKey: string };

/** The VK's raw bytes, briefly, for re-wrapping. The caller must zero them. */
async function rawVk(meta: VaultMeta, unlocker: Unlocker): Promise<Uint8Array> {
  try {
    if ("passphrase" in unlocker) {
      const kek = await deriveKek(unlocker.passphrase, fromB64(meta.salt), meta.kdf.iterations);
      return await open(kek, meta.wrappedVk, AAD.vkPassphrase);
    }
    const raw = parseRecovery(unlocker.recoveryKey);
    try {
      return await open(await aesKey(raw), meta.recoveryWrappedVk, AAD.vkRecovery);
    } finally {
      raw.fill(0);
    }
  } catch (error) {
    if (error instanceof VaultLockedError) throw error;
    throw new VaultLockedError("passphrase" in unlocker ? undefined : "That recovery key does not open this vault.");
  }
}

export async function unlock(meta: VaultMeta, unlocker: Unlocker): Promise<CryptoKey> {
  const raw = await rawVk(meta, unlocker);
  try {
    return await aesKey(raw);
  } finally {
    raw.fill(0);
  }
}

/** A new passphrase for the same VK (after proving the current passphrase or the recovery key). */
export async function rewrapPassphrase(meta: VaultMeta, unlocker: Unlocker, passphrase: string, iterations = DEFAULT_ITERATIONS) {
  checkPassphrase(passphrase);
  const raw = await rawVk(meta, unlocker);
  try {
    const salt = random(16);
    const kek = await deriveKek(passphrase, salt, iterations);
    return {
      kdf: { name: "PBKDF2" as const, hash: "SHA-256" as const, iterations },
      salt: toB64(salt),
      wrappedVk: await seal(kek, raw, AAD.vkPassphrase),
      keyVersion: meta.keyVersion,
    };
  } finally {
    raw.fill(0);
  }
}

/** A new recovery key (the old one stops working). */
export async function newRecoveryKey(meta: VaultMeta, unlocker: Unlocker) {
  const raw = await rawVk(meta, unlocker);
  const recoveryRaw = random(32);
  try {
    return {
      body: { recoveryWrappedVk: await seal(await aesKey(recoveryRaw), raw, AAD.vkRecovery), keyVersion: meta.keyVersion },
      recoveryKey: formatRecovery(recoveryRaw),
    };
  } finally {
    raw.fill(0);
    recoveryRaw.fill(0);
  }
}

// ------------------------------------------------------------------ items

export function newItemId(): string {
  return "vi_" + toB64(random(18)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function emptyFields(): ItemFields {
  return { label: "", username: "", secret: "", url: "", notes: "", totp: "", accountId: null };
}

export async function encryptItem(
  vk: CryptoKey, head: { id: string; kind: ItemKind; version: number }, fields: ItemFields,
): Promise<VaultRecord> {
  const itemRaw = random(32);
  try {
    const itemKey = await aesKey(itemRaw);
    const clean: ItemFields = {
      label: fields.label.trim(), username: fields.username.trim(), secret: fields.secret, url: fields.url.trim(),
      notes: fields.notes, totp: fields.totp.replace(/\s/g, ""), accountId: fields.accountId,
    };
    const body = await seal(itemKey, text.encode(JSON.stringify(clean)), AAD.item(head.id, head.kind, head.version));
    return {
      id: head.id, accountId: fields.accountId, kind: head.kind, version: head.version, iv: body.iv, ct: body.ct,
      wrappedKey: await seal(vk, itemRaw, AAD.itemKey(head.id, head.version)),
    };
  } finally {
    itemRaw.fill(0);
  }
}

export async function decryptItem(vk: CryptoKey, record: VaultRecord): Promise<OpenItem> {
  const itemRaw = await open(vk, record.wrappedKey, AAD.itemKey(record.id, record.version));
  let fields: ItemFields;
  try {
    const itemKey = await aesKey(itemRaw);
    const plain = await open(itemKey, { iv: record.iv, ct: record.ct }, AAD.item(record.id, record.kind, record.version));
    fields = { ...emptyFields(), ...(JSON.parse(untext.decode(plain)) as Partial<ItemFields>) };
    plain.fill(0);
  } finally {
    itemRaw.fill(0);
  }
  return {
    ...fields, id: record.id, kind: record.kind, version: record.version, createdBy: record.createdBy ?? "owner",
    updatedAt: record.updatedAt ?? 0, moved: (fields.accountId ?? null) !== (record.accountId ?? null),
  };
}

// ------------------------------------------------------------------ generator and health

const SETS = {
  lower: "abcdefghijkmnopqrstuvwxyz",
  upper: "ABCDEFGHJKLMNPQRSTUVWXYZ",
  digits: "23456789",
  symbols: "!@#$%^&*-_=+?",
};

function pick(alphabet: string): string {
  // Rejection sampling: no modulo bias.
  const limit = 256 - (256 % alphabet.length);
  for (;;) {
    const [b] = random(1);
    if (b! < limit) return alphabet[b! % alphabet.length]!;
  }
}

export function generatePassword(length = 20, sets: Array<keyof typeof SETS> = ["lower", "upper", "digits", "symbols"]): string {
  const size = Math.max(12, Math.min(128, Math.floor(length)));
  const chosen = sets.length ? sets : (["lower", "upper", "digits"] as Array<keyof typeof SETS>);
  const all = chosen.map((s) => SETS[s]).join("");
  const chars = chosen.map((s) => pick(SETS[s]));
  while (chars.length < size) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i -= 1) {
    const limit = 256 - (256 % (i + 1));
    let b: number;
    do b = random(1)[0]!; while (b >= limit);
    const j = b % (i + 1);
    [chars[i], chars[j]] = [chars[j]!, chars[i]!];
  }
  return chars.join("");
}

const COMMON = /^(password|passw0rd|qwerty|letmein|welcome|admin|iloveyou|monkey|dragon|123456|12345678|abc123|111111)/i;

export function strength(secret: string): "weak" | "fair" | "strong" {
  if (!secret) return "weak";
  const classes = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((r) => r.test(secret)).length;
  const words = secret.trim().split(/[\s\-_.]+/).filter((w) => w.length >= 3).length;
  if (COMMON.test(secret) || /^(.+?)\1+$/.test(secret) || secret.length < 10) return "weak";
  if (secret.length >= 20 || (words >= 4 && secret.length >= 16) || (secret.length >= 14 && classes >= 3)) return "strong";
  return secret.length >= 12 && classes >= 2 ? "fair" : "weak";
}

export interface Health { weak: string[]; reused: string[]; old: string[] }

const YEAR_MS = 365 * 24 * 60 * 60_000;

export function health(items: OpenItem[], now = Date.now()): Health {
  const logins = items.filter((i) => i.kind === "login" && i.secret);
  const seen = new Map<string, string[]>();
  for (const item of logins) seen.set(item.secret, [...(seen.get(item.secret) ?? []), item.id]);
  return {
    weak: logins.filter((i) => strength(i.secret) === "weak").map((i) => i.id),
    reused: [...seen.values()].filter((ids) => ids.length > 1).flat(),
    old: logins.filter((i) => i.updatedAt && now - i.updatedAt > YEAR_MS).map((i) => i.id),
  };
}

// ------------------------------------------------------------------ TOTP (RFC 6238)

export async function totpCode(seed: string, now = Date.now(), digits = 6, period = 30): Promise<string> {
  const clean = seed.toUpperCase().replace(/[\s=-]/g, "");
  if (!/^[A-Z2-7]{16,}$/.test(clean)) throw new Error("That is not an authenticator seed.");
  const key = await subtle().importKey("raw", base32(clean) as BufferSource, { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const counter = new Uint8Array(8);
  let step = Math.floor(now / 1000 / period);
  for (let i = 7; i >= 0; i -= 1) {
    counter[i] = step & 255;
    step = Math.floor(step / 256);
  }
  const mac = new Uint8Array(await subtle().sign("HMAC", key, counter as BufferSource));
  const offset = mac[mac.length - 1]! & 15;
  const bin = ((mac[offset]! & 127) << 24) | (mac[offset + 1]! << 16) | (mac[offset + 2]! << 8) | mac[offset + 3]!;
  return String(bin % 10 ** digits).padStart(digits, "0");
}

// ------------------------------------------------------------------ gateway calls (ciphertext only)

export const vaultApi = {
  get: (client: GatewayClient) => client.get<VaultState>("/v1/cc/vault"),
  init: (client: GatewayClient, body: CreatedVault["init"]) => client.post<VaultState>("/v1/cc/vault/init", body),
  rewrap: (client: GatewayClient, body: Record<string, unknown>) => client.post<VaultState>("/v1/cc/vault/rewrap", body),
  put: (client: GatewayClient, record: VaultRecord) => client.post<VaultRecord>("/v1/cc/vault/items", record),
  importMany: (client: GatewayClient, records: VaultRecord[]) => client.post<{ items: VaultRecord[] }>("/v1/cc/vault/import", { items: records }),
  remove: (client: GatewayClient, id: string) => client.post(`/v1/cc/vault/items/${encodeURIComponent(id)}/delete`),
  restore: (client: GatewayClient, backup: unknown) => client.post<VaultState>("/v1/cc/vault/restore", { backup }),
  reset: (client: GatewayClient) => client.post<VaultState>("/v1/cc/vault/reset", { confirm: "DELETE VAULT" }),
  audit: (client: GatewayClient, action: string, itemId?: string) =>
    client.post("/v1/cc/vault/audit", itemId ? { action, itemId } : { action }).catch(() => undefined),
};

/** The encrypted backup file: the gateway's ciphertext as-is, with a marker. Opens only with the passphrase or recovery key. */
export function backupFile(state: VaultState): string {
  return JSON.stringify({ cyclone: "vault-backup", savedAt: new Date().toISOString(), ...state }, null, 1);
}
