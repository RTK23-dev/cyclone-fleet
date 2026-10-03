/**
 * Fail closed if a gateway payload carries secret VALUES.
 * Slot NAMES like "password" are allowed only as boolean keys on secrets.slots.
 * Never strip-and-forward. Never write to localStorage.
 */

export const SECRET_PAYLOAD_REJECTED = "SECRET_PAYLOAD_REJECTED";

const FORBIDDEN_SECRET_KEYS = new Set([
  "password",
  "passcode",
  "passwd",
  "pin",
  "otp",
  "token",
  "secret",
  "api_key",
  "authorization",
  "cookie",
  "cvv",
  "credential",
  "typed_text",
  "typed_value",
  "bearer",
  "access_token",
  "refresh_token",
  "id_token",
  "set_cookie",
]);

const FORBIDDEN_SECRET_TOKENS = new Set([
  "password",
  "passcode",
  "passwd",
  "pin",
  "otp",
  "token",
  "secret",
  "apikey",
  "authorization",
  "cookie",
  "cvv",
  "credential",
  "credentials",
  "typedtext",
  "typedvalue",
  "bearer",
  "accesstoken",
  "refreshtoken",
  "idtoken",
  "setcookie",
]);

const INLINE_SECRET = /(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value)|bearer)\s*[:=]/i;
const BEARER_VALUE = /(?:^|[\s"'])Bearer\s+[A-Za-z0-9._\-+=/]{8,}/i;
const JWT_VALUE = /^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/;

export class SecretPayloadRejectedError extends Error {
  readonly code = SECRET_PAYLOAD_REJECTED;

  constructor() {
    super("Secret-bearing payload rejected.");
    this.name = "SecretPayloadRejectedError";
  }
}

export interface SecretGuardOptions {
  /** When true, object values under `slots` may use secret-looking keys iff the values are boolean. */
  slotPresence?: boolean;
}

function normalizedKey(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase()
    .replace(/[-\s]/g, "_");
}

export function isSecretLookingKey(value: string): boolean {
  const normalized = normalizedKey(value);
  const compact = normalized.replace(/_/g, "");
  if (FORBIDDEN_SECRET_KEYS.has(normalized) || FORBIDDEN_SECRET_TOKENS.has(compact)) return true;
  return normalized.split("_").some((part) => FORBIDDEN_SECRET_TOKENS.has(part));
}

export function looksLikeSecretValue(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed) return false;
  if (INLINE_SECRET.test(trimmed)) return true;
  if (BEARER_VALUE.test(trimmed)) return true;
  if (JWT_VALUE.test(trimmed)) return true;
  return false;
}

function reject(): never {
  throw new SecretPayloadRejectedError();
}

function walk(value: unknown, inSlotsMap: boolean): void {
  if (value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) walk(item, false);
    return;
  }
  if (typeof value === "string") {
    if (looksLikeSecretValue(value)) reject();
    return;
  }
  if (typeof value !== "object") return;

  const record = value as Record<string, unknown>;
  for (const [key, nested] of Object.entries(record)) {
    if (inSlotsMap) {
      if (typeof nested !== "boolean") reject();
      continue;
    }
    const childIsSlotsMap = key === "slots" && nested !== null && typeof nested === "object" && !Array.isArray(nested);
    if (childIsSlotsMap) {
      walk(nested, true);
      continue;
    }
    if (isSecretLookingKey(key)) reject();
    walk(nested, false);
  }
}

/**
 * Throw if `payload` contains secret-looking keys or values.
 * `secrets.slots` may pass `{ slotPresence: true }` so `{ password: true }` is allowed.
 * Does not strip fields. Does not write localStorage.
 */
export function assertNoSecretValues(payload: unknown, options: SecretGuardOptions = {}): void {
  if (options.slotPresence) {
    if (payload !== null && typeof payload === "object" && !Array.isArray(payload)) {
      const record = payload as Record<string, unknown>;
      if ("slots" in record) {
        walk(payload, false);
        return;
      }
      walk(payload, true);
      return;
    }
  }
  walk(payload, false);
}
