/**
 * The phone's profiles (plan 43 T4): Profile A and the profiles Cyclone made, which one is in front, switching between
 * them from the PC, and each Cyclone profile's apps (add one Profile A has, remove one from that profile only).
 */
import type { GatewayClient } from "./gateway.js";

export const MAIN_PROFILE = "main";

export interface PhoneProfile {
  id: string;
  label: string;
  emoji: string | null;
  /** #AARRGGBB, as the phone stores it. */
  color: string | null;
  ready: boolean;
  current: boolean;
  inTrash: boolean;
}
export interface ProfileApp { packageName: string; label: string }
export interface ProfileApps { apps: ProfileApp[]; available: ProfileApp[]; truncated: boolean }

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const ID = /^(main|Cyclone_[a-f0-9]{16})$/;

export function parseProfiles(raw: unknown): { profiles: PhoneProfile[]; current: string | null } {
  const r = obj(raw);
  const profiles = list(r.profiles).flatMap((p): PhoneProfile[] => {
    const o = obj(p);
    const id = str(o.id);
    if (!ID.test(id)) return [];
    return [{
      id, label: str(o.label) || (id === MAIN_PROFILE ? "Profile A" : "Profile"), emoji: str(o.emoji) || null,
      color: /^#[0-9A-F]{8}$/.test(str(o.color)) ? str(o.color) : null, ready: o.ready === true, current: o.current === true, inTrash: o.inTrash === true,
    }];
  });
  return { profiles, current: ID.test(str(r.current)) ? str(r.current) : null };
}

export function parseProfileApps(raw: unknown): ProfileApps {
  const r = obj(raw);
  const apps = (v: unknown) => list(v).map((a) => obj(a)).filter((a) => str(a.package)).map((a) => ({ packageName: str(a.package), label: str(a.label) || str(a.package) }));
  return { apps: apps(r.apps), available: apps(r.available), truncated: r.truncated === true };
}

/** "#FF7C4DFF" (Android ARGB) → "#7C4DFF" for CSS. */
export function cssColor(color: string | null): string | null {
  return color && /^#[0-9A-F]{8}$/.test(color) ? `#${color.slice(3)}` : null;
}

const base = (device: string) => `/v1/devices/${encodeURIComponent(device)}/profiles`;

export const profilesApi = {
  list: async (client: GatewayClient, device: string, signal?: AbortSignal) => parseProfiles(await client.get(base(device), signal)),
  apps: async (client: GatewayClient, device: string, profile: string) =>
    parseProfileApps(await client.get(`${base(device)}/${encodeURIComponent(profile)}/apps`)),
  switchTo: (client: GatewayClient, device: string, profile: string) => client.post(`${base(device)}/${encodeURIComponent(profile)}/switch`),
  change: (client: GatewayClient, device: string, profile: string, packageName: string, action: "install" | "remove") =>
    client.post(`${base(device)}/${encodeURIComponent(profile)}/apps`, { package: packageName, action }),
};
