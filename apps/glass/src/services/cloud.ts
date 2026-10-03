/**
 * Cloud phones (plan 44, alpha 90): the owner's VMOS Cloud, DuoPlus and remote-ADB accounts, and which of their phones
 * this PC keeps connected. The gateway opens, renews and repairs each link; Glass shows its answer and sends the
 * owner's choices. Keys go to the gateway once and never come back.
 */
import type { GatewayClient } from "./gateway.js";

export type CloudProvider = "vmos" | "duoplus" | "adb";
export type CloudLinkState = "off" | "waiting" | "opening" | "renewing" | "tunnel" | "connecting" | "connected" | "needs_you";

export interface CloudPhone {
  remoteId: string;
  name: string;
  android: string | null;
  power: string;
  address: string | null;
  keep: boolean;
  state: CloudLinkState;
  message: string;
  deviceId: string | null;
  expiresAtMs: number | null;
}

export interface CloudAccount {
  id: string;
  provider: CloudProvider;
  providerLabel: string;
  label: string;
  installCyclone: boolean;
  error: { code: string; message: string } | null;
  phones: CloudPhone[];
}

export interface CloudStatus {
  ssh: boolean;
  memoryOnly: boolean;
  accounts: CloudAccount[];
}

export interface NewCloudAccount {
  provider: CloudProvider;
  label?: string;
  secrets?: Record<string, string>;
  installCyclone?: boolean;
}

const PROVIDERS: CloudProvider[] = ["vmos", "duoplus", "adb"];
const STATES: CloudLinkState[] = ["off", "waiting", "opening", "renewing", "tunnel", "connecting", "connected", "needs_you"];
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export function parsePhone(raw: unknown): CloudPhone | null {
  const r = obj(raw);
  const remoteId = str(r.remoteId);
  if (!remoteId) return null;
  const state = STATES.includes(r.state as CloudLinkState) ? (r.state as CloudLinkState) : "waiting";
  return {
    remoteId, name: str(r.name) ?? remoteId, android: str(r.android), power: str(r.power) ?? "unknown", address: str(r.address),
    keep: r.keep === true, state, message: str(r.message) ?? "", deviceId: str(r.deviceId), expiresAtMs: num(r.expiresAtMs),
  };
}

export function parseAccount(raw: unknown): CloudAccount | null {
  const r = obj(raw);
  const id = str(r.id);
  const provider = PROVIDERS.includes(r.provider as CloudProvider) ? (r.provider as CloudProvider) : null;
  if (!id || !provider) return null;
  const error = obj(r.error);
  return {
    id, provider, providerLabel: str(r.providerLabel) ?? provider, label: str(r.label) ?? str(r.providerLabel) ?? provider,
    installCyclone: r.installCyclone !== false,
    error: str(error.message) ? { code: str(error.code) ?? "", message: str(error.message)! } : null,
    phones: list(r.phones).map(parsePhone).filter((p): p is CloudPhone => p !== null),
  };
}

export function parseCloud(raw: unknown): CloudStatus {
  const r = obj(raw);
  return {
    ssh: r.ssh !== false,
    memoryOnly: r.security === "MEMORY_ONLY",
    accounts: list(r.accounts).map(parseAccount).filter((a): a is CloudAccount => a !== null),
  };
}

/** Whether anything is still on its way, so the page looks again soon. */
export function settling(status: CloudStatus): boolean {
  return status.accounts.some((a) => a.phones.some((p) => p.keep && p.state !== "connected" && p.state !== "needs_you" && p.state !== "off"));
}

const account = (id: string) => `/v1/cloud/accounts/${encodeURIComponent(id)}`;

export const cloudApi = {
  status: async (client: GatewayClient) => parseCloud(await client.get("/v1/cloud")),
  addAccount: async (client: GatewayClient, body: NewCloudAccount) => parseAccount(await client.post("/v1/cloud/accounts", body)),
  removeAccount: (client: GatewayClient, id: string) => client.post(`${account(id)}/remove`),
  refresh: async (client: GatewayClient, id: string) => parseAccount(await client.post(`${account(id)}/refresh`)),
  keep: async (client: GatewayClient, id: string, remoteId: string, keep: boolean) =>
    parseAccount(await client.post(`${account(id)}/phones/${encodeURIComponent(remoteId)}`, { keep })),
  setAddress: async (client: GatewayClient, id: string, remoteId: string, address: string) =>
    parseAccount(await client.post(`${account(id)}/phones/${encodeURIComponent(remoteId)}`, { address })),
  addAddress: async (client: GatewayClient, id: string, address: string, name?: string) =>
    parseAccount(await client.post(`${account(id)}/addresses`, { address, name: name || undefined })),
};
