/**
 * Cyclone Marketplace (`/v1/devices/{id}/market`, `/v1/pc/connections`). The phone owns what is added and what runs;
 * this PC adds its own MCP connections. Glass shows and commands; it decides nothing.
 */
import type { GatewayClient } from "./gateway.js";

export interface MarketInput {
  name: string;
  label: string;
  kind: "text" | "number" | "app" | "choice";
  default: string;
  choices: string[];
  required: boolean;
}

export interface MarketListing {
  id: string;
  kind: "recipe" | "connection";
  version: string;
  name: string;
  publisher: { id: string; name: string; verified: boolean };
  summary: string;
  category: string;
  glyph: string;
  goal: string;
  inputs: MarketInput[];
  apps: string[];
  does: string[];
  asksFirst: string[];
  suggestFor: string[];
  featured: boolean;
  added: boolean;
  savedInputs: Record<string, string> | null;
  runs: number;
  lastRunAt: number | null;
}

export interface PhoneConnection {
  id: string;
  name: string;
  glyph: string;
  state: "connected" | "needs_setup" | "off";
  detail: string;
  where: string;
}

export interface MarketCatalog {
  listings: MarketListing[];
  suggestions: Array<{ id: string; reason: string }>;
  installedCount: number;
  connections: PhoneConnection[];
}

export interface PcConnection {
  id: string;
  name: string;
  description: string;
  state: "connected" | "configured" | "detected" | "ready" | "attention" | "not_found" | "unknown";
  detected: boolean;
  configured: boolean;
}

export interface PcConnections {
  available: boolean;
  reason: string | null;
  connections: PcConnection[];
}

const base = (deviceId: string) => `/v1/devices/${encodeURIComponent(deviceId)}/market`;

export function getMarket(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<MarketCatalog> {
  return client.get<MarketCatalog>(base(deviceId), signal);
}

export function addListing(client: GatewayClient, deviceId: string, id: string, inputs: Record<string, string>): Promise<unknown> {
  return client.post(`${base(deviceId)}/${encodeURIComponent(id)}/install`, { inputs });
}

export function removeListing(client: GatewayClient, deviceId: string, id: string): Promise<unknown> {
  return client.post(`${base(deviceId)}/${encodeURIComponent(id)}/remove`, {});
}

export function runListing(client: GatewayClient, deviceId: string, id: string, inputs: Record<string, string>): Promise<unknown> {
  return client.post(`${base(deviceId)}/${encodeURIComponent(id)}/run`, { inputs });
}

export function getPcConnections(client: GatewayClient, signal?: AbortSignal): Promise<PcConnections> {
  return client.get<PcConnections>("/v1/pc/connections", signal);
}

export function connectPc(client: GatewayClient, id: string): Promise<{ id: string; ok: boolean; message: string; config: string | null }> {
  return client.post(`/v1/pc/connections/${encodeURIComponent(id)}/connect`, {});
}

/** Search across name, summary, category and what it does. */
export function searchListings(listings: MarketListing[], query: string): MarketListing[] {
  const q = query.trim().toLowerCase();
  if (!q) return listings;
  return listings.filter((l) => [l.name, l.summary, l.category, ...l.does].some((text) => text.toLowerCase().includes(q)));
}

export function pcStateLabel(state: PcConnection["state"]): { label: string; tone: "success" | "accent" | "warning" | "neutral" } {
  switch (state) {
    case "connected":
      return { label: "Connected", tone: "success" };
    case "configured":
      return { label: "Configured", tone: "accent" };
    case "ready":
      return { label: "Ready", tone: "accent" };
    case "detected":
      return { label: "Found on this PC", tone: "neutral" };
    case "attention":
      return { label: "Needs attention", tone: "warning" };
    default:
      return { label: "Not found", tone: "neutral" };
  }
}
