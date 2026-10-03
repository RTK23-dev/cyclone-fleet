/**
 * Numbers (alpha.102): every phone number Cyclone can receive codes on, in one place: the fleet's SIMs, numbers
 * forwarded by a plugin, and numbers the owner rents for a longer term. The gateway decides each number's state;
 * Glass shows it. Numbers only: never a text, a sender or a code.
 */
import type { GatewayClient } from "./gateway.js";

export type NumberOrigin = "phone" | "plugin" | "rental" | "other";
export type NumberState = "ready" | "paused" | "expired" | "offline" | "missing" | "codes_off" | "no_source" | "source_off" | "manual";
export type PhoneNumbersState = "ready" | "offline" | "unreadable" | "no_permission" | "off" | "no_number";

export interface NumberSource {
  kind: NumberOrigin;
  ref: string | null;
  name: string | null;
  provider: string | null;
  slot: number | null;
}

export interface NumberAccount {
  id: string;
  service: string;
  handle: string;
}

export interface OwnedNumber {
  id: string;
  number: string;
  label: string;
  origin: NumberOrigin;
  source: NumberSource;
  state: NumberState;
  why: string;
  paused: boolean;
  expiresAt: number | null;
  expiresSoon: boolean;
  account: NumberAccount | null;
  seenAt: number | null;
  notes: string;
}

export interface NumbersPhone {
  deviceId: string;
  name: string;
  state: PhoneNumbersState;
  hint: string;
  numbers: number;
}

export interface NumbersOverview {
  summary: { total: number; byOrigin: Record<NumberOrigin, number>; ready: number; attention: number; expiringSoon: number; unassigned: number };
  numbers: OwnedNumber[];
  phones: NumbersPhone[];
  plugins: Array<{ name: string; status: string; title: string }>;
  accounts: NumberAccount[];
  at: number;
}

export interface NewNumber {
  number: string;
  origin: Exclude<NumberOrigin, "phone">;
  label?: string;
  source?: string;
  provider?: string;
  expiresAt?: number | null;
  accountId?: string | null;
  notes?: string;
}

export interface NumberChange {
  label?: string;
  paused?: boolean;
  accountId?: string | null;
  source?: string;
  provider?: string;
  expiresAt?: number | null;
  notes?: string;
}

const ORIGINS: NumberOrigin[] = ["phone", "plugin", "rental", "other"];
const STATES: NumberState[] = ["ready", "paused", "expired", "offline", "missing", "codes_off", "no_source", "source_off", "manual"];
const PHONE_STATES: PhoneNumbersState[] = ["ready", "offline", "unreadable", "no_permission", "off", "no_number"];
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const strOrNull = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const pick = <T extends string>(v: unknown, allowed: T[], fallback: T): T => (allowed.includes(v as T) ? (v as T) : fallback);

function parseAccount(v: unknown): NumberAccount | null {
  const a = obj(v);
  return str(a.id) ? { id: str(a.id), service: str(a.service), handle: str(a.handle) } : null;
}

export function parseNumber(v: unknown): OwnedNumber | null {
  const n = obj(v);
  if (!str(n.id) || !str(n.number)) return null;
  const s = obj(n.source);
  const origin = pick(n.origin, ORIGINS, "other");
  return {
    id: str(n.id),
    number: str(n.number),
    label: str(n.label),
    origin,
    source: { kind: origin, ref: strOrNull(s.ref), name: strOrNull(s.name), provider: strOrNull(s.provider), slot: num(s.slot) },
    state: pick(n.state, STATES, "manual"),
    why: str(n.why),
    paused: n.paused === true,
    expiresAt: num(n.expiresAt),
    expiresSoon: n.expiresSoon === true,
    account: parseAccount(n.account),
    seenAt: num(n.seenAt),
    notes: str(n.notes),
  };
}

export function parseOverview(v: unknown): NumbersOverview {
  const o = obj(v);
  const s = obj(o.summary);
  const by = obj(s.byOrigin);
  const count = (x: unknown): number => num(x) ?? 0;
  return {
    summary: {
      total: count(s.total),
      byOrigin: { phone: count(by.phone), plugin: count(by.plugin), rental: count(by.rental), other: count(by.other) },
      ready: count(s.ready),
      attention: count(s.attention),
      expiringSoon: count(s.expiringSoon),
      unassigned: count(s.unassigned),
    },
    numbers: list(o.numbers).map(parseNumber).filter((n): n is OwnedNumber => n !== null),
    phones: list(o.phones).map((p) => {
      const x = obj(p);
      return { deviceId: str(x.deviceId), name: str(x.name) || "Phone", state: pick(x.state, PHONE_STATES, "unreadable"), hint: str(x.hint), numbers: count(x.numbers) };
    }).filter((p) => p.deviceId),
    plugins: list(o.plugins).map((p) => ({ name: str(obj(p).name), status: str(obj(p).status), title: str(obj(p).title) || str(obj(p).name) })).filter((p) => p.name),
    accounts: list(o.accounts).map(parseAccount).filter((a): a is NumberAccount => a !== null),
    at: num(o.at) ?? 0,
  };
}

/** Easier to read: "+31612345678" → "+31 612 345 678". The stored number never changes. */
export function formatNumber(number: string): string {
  const plus = number.startsWith("+");
  const digits = number.replace(/\D/g, "");
  if (digits.length < 7) return number;
  const head = plus ? digits.slice(0, 2) : "";
  const rest = plus ? digits.slice(2) : digits;
  const groups = rest.match(/\d{1,3}/g) ?? [];
  if (groups.length > 1 && groups[groups.length - 1].length === 1) groups[groups.length - 2] += groups.pop();
  return `${plus ? `+${head} ` : ""}${groups.join(" ")}`;
}

export function originLabel(n: OwnedNumber): string {
  switch (n.origin) {
    case "phone":
      return `${n.source.name ?? "A phone"}${n.source.slot != null ? ` · SIM ${n.source.slot}` : " · confirmed"}`;
    case "plugin":
      return `Forwarded by ${n.source.ref ?? "a plugin"}`;
    case "rental":
      return `Rented · ${n.source.provider ?? "provider"}${n.source.ref ? ` · via ${n.source.ref}` : ""}`;
    default:
      return "Tracked here";
  }
}

export const STATE_LABEL: Record<NumberState, string> = {
  ready: "Ready",
  paused: "Paused",
  expired: "Rental ended",
  offline: "Phone offline",
  missing: "Not on the phone",
  codes_off: "Codes off",
  no_source: "No source",
  source_off: "Source off",
  manual: "Tracked",
};

export const numbersApi = {
  get: async (client: GatewayClient, refresh = false): Promise<NumbersOverview> =>
    parseOverview(await client.get<unknown>(`/v1/numbers${refresh ? "?refresh=true" : ""}`)),
  add: (client: GatewayClient, body: NewNumber): Promise<unknown> => client.post("/v1/numbers", body),
  update: (client: GatewayClient, id: string, change: NumberChange): Promise<unknown> =>
    client.post(`/v1/numbers/${encodeURIComponent(id)}`, change),
  remove: (client: GatewayClient, id: string): Promise<unknown> => client.post(`/v1/numbers/${encodeURIComponent(id)}/delete`),
};
