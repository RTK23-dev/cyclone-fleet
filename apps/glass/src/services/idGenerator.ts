import type { GatewayClient } from "./gateway.js";
import type { Plugin } from "./ports.js";

export interface IdUsage {
  version: 1; apiBase: string; agentEnabled: boolean; apps: string[]; routines: string[];
  whenToUse: string; instructions: string;
}
export interface IdStarter {
  state: "found" | "offline" | "incompatible"; detail: string; apiBase: string;
  config: IdUsage; plugin: Plugin | null; panelUrl: string | null; settingsUrl: string | null;
  ports: string[]; skill: { description: string; workflow: string };
  health: { api: boolean; worker: boolean; busy: boolean; dryRun: boolean; photoshopFound: boolean; templateFound: boolean };
}
const base = "/v1/ports/starters/id-generator";
export const idGenerator = {
  status: (c: GatewayClient, refresh = false) => c.get<IdStarter>(base + (refresh ? "?refresh=true" : "")),
  save: (c: GatewayClient, config: IdUsage) => c.post<IdStarter>(base + "/config", config),
  connect: (c: GatewayClient, allowed: string[]) => c.post<IdStarter>(base + "/connect", { allowed }),
  schema: (c: GatewayClient) => c.get<Record<string, unknown>>(base + "/schema"),
};

/** The backend checks this too. Never frame a remote URL or the Glass origin supplied by a plugin. */
export function studioFrame(raw: string | null, path: string, glassOrigin?: string): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw);
    if (u.protocol !== "http:" || !["127.0.0.1", "[::1]"].includes(u.hostname) || u.username || u.password ||
        u.pathname !== path || u.search !== "?embed=1" || u.hash || u.origin === glassOrigin) return null;
    return u.href;
  } catch { return null; }
}
