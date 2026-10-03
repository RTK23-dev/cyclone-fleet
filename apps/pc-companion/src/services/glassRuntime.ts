/**
 * Glass V5 runtime mount. Factory only — not a second HTTP stack.
 *
 * Builds a live `AtlasClient` (`useDemoGraph: false`) from gateway URL, bearer,
 * device id, session id, and phone version. Pages receive loaders + version;
 * they never see the bearer.
 *
 * Mapping is commanded through `atlas` (the phone walks the app). Does not implement ask.start
 * or encrypted fill.
 */

import { DEFAULT_FOREGROUND_SESSION_ID } from "../core/sessionTiles.js";
import type { GlassVaultSlot } from "../core/fleet.js";
import { loadMapsDataSourceFromClient } from "../maps/atlasClientAdapter.js";
import type { MapsDataSource } from "../maps/mockAtlas.js";
import { createAtlasClient, type AtlasClient } from "./atlasClient.js";
import type { Persona, SecretsRequestResult } from "./atlasTypes.js";

export const GLASS_OPERATOR_REQUEST_REASON = "operator-request";

export interface GlassRuntimeOptions {
  httpBase: string;
  getBearer: () => string;
  getDeviceId: () => string;
  getSessionId: () => string;
  getPhoneVersion: () => string | null;
  fetch?: typeof fetch;
}

export interface GlassRuntime {
  atlas: AtlasClient;
  loadMapsSource(persona: "live" | "mapping"): Promise<MapsDataSource>;
  loadVaultSlots(placeId: string, persona: "live" | "mapping", placeLabel: string): Promise<GlassVaultSlot[]>;
  requestSecret(
    placeId: string,
    persona: "live" | "mapping",
    slot: string,
    reason: string,
  ): Promise<SecretsRequestResult>;
}

export type GlassSessionPlane = "foreground" | "session_kernel_vd";

/**
 * Named session if non-empty; otherwise the live human display.
 * Never rewrites a named id (e.g. `vd-mail`) to display 0 / default-foreground.
 */
export function resolveGlassSessionId(focusedSessionId?: string | null): string {
  const named = String(focusedSessionId ?? "").trim();
  if (named) return named;
  return DEFAULT_FOREGROUND_SESSION_ID;
}

/** Foreground for default-foreground; named ids are Session Kernel VD. Never `layer2`. */
export function sessionPlaneFromSessionId(sessionId?: string | null): GlassSessionPlane {
  return resolveGlassSessionId(sessionId) === DEFAULT_FOREGROUND_SESSION_ID
    ? "foreground"
    : "session_kernel_vd";
}

/**
 * Map `secrets.slots` presence onto Vault rows. Boolean values only.
 * Slot names such as `password` are allowed as labels; string values are dropped.
 */
export function slotsFromPresence(
  placeId: string,
  placeLabel: string,
  slots: Record<string, unknown> | null | undefined,
): GlassVaultSlot[] {
  if (!slots || typeof slots !== "object" || Array.isArray(slots)) return [];
  const out: GlassVaultSlot[] = [];
  for (const [name, present] of Object.entries(slots)) {
    if (typeof present !== "boolean") continue;
    const slotLabel = String(name ?? "").trim();
    if (!slotLabel) continue;
    out.push({
      id: `${placeId}:${slotLabel}`,
      placeLabel,
      slotLabel,
      set: present,
    });
  }
  return out;
}

/** Prefer `mobileVersion`, then `appVersion`, then `version`. Absent ⇒ undefined (fail closed, not 5.x). */
export function readDeviceMobileVersion(source: unknown): string | undefined {
  if (!source || typeof source !== "object") return undefined;
  const record = source as Record<string, unknown>;
  for (const key of ["mobileVersion", "appVersion", "version"] as const) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return undefined;
}

export function applyDeviceMobileVersion<T extends object>(device: T): T & { mobileVersion?: string };
export function applyDeviceMobileVersion(device: undefined | null): undefined;
export function applyDeviceMobileVersion<T extends object>(
  device: T | undefined | null,
): (T & { mobileVersion?: string }) | undefined;
export function applyDeviceMobileVersion<T extends object>(
  device: T | undefined | null,
): (T & { mobileVersion?: string }) | undefined {
  if (device == null) return undefined;
  const mobileVersion = readDeviceMobileVersion(device);
  if (mobileVersion === undefined) {
    if (!("mobileVersion" in device)) return device;
    const copy = { ...device } as T & { mobileVersion?: string };
    delete copy.mobileVersion;
    return copy;
  }
  return { ...device, mobileVersion };
}

export function createGlassRuntime(options: GlassRuntimeOptions): GlassRuntime {
  const atlas = createAtlasClient({
    baseUrl: options.httpBase,
    getBearer: options.getBearer,
    getSessionId: options.getSessionId,
    getDeviceId: options.getDeviceId,
    getPhoneVersion: options.getPhoneVersion,
    useDemoGraph: false,
    fetch: options.fetch,
  });

  return {
    atlas,
    loadMapsSource(persona: Persona): Promise<MapsDataSource> {
      return loadMapsDataSourceFromClient(atlas, persona);
    },
    async loadVaultSlots(placeId: string, persona: Persona, placeLabel: string): Promise<GlassVaultSlot[]> {
      const presence = await atlas.secretsSlots(placeId, persona);
      return slotsFromPresence(placeId, placeLabel, presence.slots);
    },
    requestSecret(
      placeId: string,
      persona: Persona,
      slot: string,
      reason: string,
    ): Promise<SecretsRequestResult> {
      return atlas.secretsRequest(placeId, persona, slot, reason);
    },
  };
}
