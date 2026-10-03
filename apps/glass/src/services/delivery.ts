/**
 * Sealed delivery (plan 33, C2), browser side. A task that uses a vault login waits until this tab, with the vault
 * unlocked, seals the password (and the authenticator seed, if any) to the task's phone with HPKE. The gateway stores
 * and relays the envelope; only that phone's Keystore key opens it, for that task, on that app or site, until it expires.
 */
import type { GatewayClient } from "./gateway.js";
import { fromB64, random, toB64, type OpenItem } from "./vault.js";
import { fingerprint, seal } from "./hpke.js";

export const INFO = "cyclone-sealed-delivery/v1";
export const LEASE_MS = 30 * 60_000;
const MAX_LEASE_MS = 24 * 60 * 60_000;
/** A routine's run may be prepared up to 8 days ahead; its lease still ends 30 minutes after that run. */
export const MAX_AHEAD_MS = 8 * 24 * 60 * 60_000;

export interface PhoneKey { publicKey: string; fingerprint: string; strongBox: boolean; fetchedAt: number; trusted: boolean; trustedAt: number | null }
export interface Phone { deviceId: string; name: string; ready: boolean; key: PhoneKey | null }
export interface PendingLease {
  taskId: string;
  title: string;
  deviceId: string;
  vaultItemId: string;
  accountId: string;
  handle: string;
  place: string;
  dueAt: number | null;
  deviceKey: { publicKey: string; fingerprint: string } | null;
  /** A routine's future run, prepared ahead (a pre-authorised lease): it may be days away. */
  ahead?: boolean;
  routineId?: string | null;
}
export interface Lease { id: string; taskId: string; deviceId: string; slot: string; place: string; expiresAt: number; state: string; createdAt: number }
export interface Envelope { leaseId: string; slot: "password" | "otp"; enc: string; ct: string; aad: string }

export const deliveryApi = {
  phones: async (client: GatewayClient) => ((await client.get<{ phones?: Phone[] }>("/v1/cc/phones"))?.phones ?? []),
  fetchKey: (client: GatewayClient, deviceId: string) => client.post(`/v1/cc/phones/${encodeURIComponent(deviceId)}/key`),
  trust: (client: GatewayClient, deviceId: string, seen: string) => client.post(`/v1/cc/phones/${encodeURIComponent(deviceId)}/trust`, { fingerprint: seen }),
  untrust: (client: GatewayClient, deviceId: string) => client.post(`/v1/cc/phones/${encodeURIComponent(deviceId)}/untrust`),
  pending: async (client: GatewayClient) => ((await client.get<{ pending?: PendingLease[] }>("/v1/cc/leases/pending"))?.pending ?? []),
  leases: async (client: GatewayClient) => ((await client.get<{ leases?: Lease[] }>("/v1/cc/leases"))?.leases ?? []),
  submit: (client: GatewayClient, taskId: string, envelopes: Envelope[]) => client.post(`/v1/cc/tasks/${encodeURIComponent(taskId)}/leases`, { envelopes }),
  revoke: (client: GatewayClient, leaseId: string) => client.post(`/v1/cc/leases/${encodeURIComponent(leaseId)}/revoke`),
};

export function leaseId(now = Date.now()): string {
  const tail = toB64(random(9)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `ls_${now.toString(16).padStart(11, "0")}${tail}`;
}

/** What goes into the envelopes: the login's password and its authenticator seed, or an authenticator's seed. */
export function secretsOf(item: OpenItem): Array<{ slot: "password" | "otp"; value: string }> {
  const out: Array<{ slot: "password" | "otp"; value: string }> = [];
  if (item.kind === "login" && item.secret) out.push({ slot: "password", value: item.secret });
  const seed = item.kind === "totp" ? item.secret || item.totp : item.totp;
  if (seed && /^[A-Z2-7=\s-]{16,}$/i.test(seed)) out.push({ slot: "otp", value: seed.replace(/[\s=-]/g, "").toUpperCase() });
  return out;
}

/**
 * Seal [item]'s secrets for one pending task. Checks first that the phone key the gateway gives is the trusted one
 * (its fingerprint is recomputed here from the key bytes).
 */
export async function sealForTask(pending: PendingLease, item: OpenItem, now = Date.now()): Promise<Envelope[]> {
  if (!pending.deviceKey) throw new Error("Trust the phone's key first.");
  const publicKey = fromB64(pending.deviceKey.publicKey);
  if ((await fingerprint(publicKey)) !== pending.deviceKey.fingerprint) throw new Error("The phone key does not match its fingerprint. Nothing was sent.");
  if (item.id !== pending.vaultItemId) throw new Error("That is not the vault item this task uses.");
  if (item.moved || item.accountId !== pending.accountId) throw new Error("This vault item's account changed outside Glass. Save it again first.");
  const secrets = secretsOf(item);
  if (!secrets.length) throw new Error("This vault item has no password or authenticator seed.");
  let expiresAt: number;
  if (pending.ahead) {
    if (!pending.dueAt || pending.dueAt <= now || pending.dueAt > now + MAX_AHEAD_MS) throw new Error("That run is not within the next 8 days.");
    expiresAt = pending.dueAt + LEASE_MS;
  } else {
    expiresAt = Math.min(Math.max(now, pending.dueAt ?? now) + LEASE_MS, now + MAX_LEASE_MS);
  }
  const out: Envelope[] = [];
  for (const secret of secrets) {
    const id = leaseId(now);
    const aad = JSON.stringify({ deviceKey: pending.deviceKey.fingerprint, expiresAt, leaseId: id, place: pending.place, slot: secret.slot, taskId: pending.taskId });
    const plain = new TextEncoder().encode(secret.value);
    try {
      const box = await seal(publicKey, new TextEncoder().encode(INFO), new TextEncoder().encode(aad), plain);
      out.push({ leaseId: id, slot: secret.slot, enc: toB64(box.enc), ct: toB64(box.ct), aad });
    } finally {
      plain.fill(0);
    }
  }
  return out;
}

export function placeLabel(place: string): string {
  return place.startsWith("package:") ? `the ${place.slice(8)} app` : place.replace(/^chrome:https:\/\//, "https://");
}

export function leaseStateLabel(state: string): { label: string; tone: "neutral" | "accent" | "success" | "warning" | "danger" } {
  return ({
    ready: { label: "Sealed, waiting to send", tone: "accent" },
    delivered: { label: "On the phone", tone: "accent" },
    used: { label: "Used once", tone: "success" },
    unused: { label: "Not needed, wiped", tone: "neutral" },
    failed: { label: "Fill failed, wiped", tone: "warning" },
    expired: { label: "Expired", tone: "neutral" },
    rejected: { label: "Refused by the phone", tone: "danger" },
    revoked: { label: "Revoked", tone: "neutral" },
    replaced: { label: "Replaced", tone: "neutral" },
  } as Record<string, { label: string; tone: "neutral" | "accent" | "success" | "warning" | "danger" }>)[state] ?? { label: state, tone: "neutral" };
}
