import type { DeviceSessionDescriptor, FleetWsEvent } from "../services/types.js";

export const MAX_HOT_BACKGROUND_ASK = 1;
export const DEFAULT_FOREGROUND_SESSION_ID = "default-foreground";
export const SESSION_KERNEL_VD_KIND = "session_kernel_vd";
export const FOREGROUND_KIND = "foreground";
export const FOREGROUND_PLANE_LABEL = "Foreground";
export const VD_PLANE_LABEL = "Session Kernel VD";
export const SESSION_TILES_TITLE = "Session Kernel VD tiles";
export const SESSION_TILES_COPY =
  "Named virtual-display (VD) sessions bound to session_id with displayId>0 and HUMAN/AI owner. These are not Layer 2 display-0 workspaces.";
export const FOREGROUND_PLANE_COPY =
  `${FOREGROUND_PLANE_LABEL} uses session_id=${DEFAULT_FOREGROUND_SESSION_ID} on display 0, the live human display.`;
export const MCP_FOREGROUND_SESSION_COPY =
  `MCP observe/act/locate require session_id=${DEFAULT_FOREGROUND_SESSION_ID} for the live human display (display 0).`;
export const MCP_FOREGROUND_OPERATOR_LINE =
  `MCP tools need session_id=${DEFAULT_FOREGROUND_SESSION_ID} for this Foreground JPEG (display 0). Named VD tiles use their own session_id plus display_id>0.`;
export const CODEX_MCP_PROMPT =
  `Use Cyclone to list my connected phones, observe the one I choose with session_id=${DEFAULT_FOREGROUND_SESSION_ID}, and tell me what is currently on screen.`;
export const HOME_MCP_SESSION_COPY =
  `Live control and MCP use session_id=${DEFAULT_FOREGROUND_SESSION_ID}.`;
export const SETTINGS_MCP_SESSION_COPY =
  `${MCP_FOREGROUND_SESSION_COPY} Doctor also reports this.`;

export type SessionTileKind = "foreground" | "session_kernel_vd";
export type SessionInputOwner = "HUMAN" | "AI";
export type SessionTilePlane = "foreground" | "session_kernel_vd";

export interface SessionTile {
  sessionId: string;
  deviceId: string;
  displayId?: number;
  kind: SessionTileKind;
  plane: SessionTilePlane;
  targetPackage?: string | null;
  backend?: string;
  inputOwner?: string;
  state: string;
  executable?: boolean;
  executionGeneration?: number | null;
  frameHealthy?: boolean | null;
  inventory: boolean;
  hotAsk: boolean;
}

export function isDefaultForegroundSession(sessionId: string): boolean {
  return sessionId === DEFAULT_FOREGROUND_SESSION_ID;
}

export function isSessionKernelVd(tile: { kind?: string; plane?: string }): boolean {
  if (tile.kind === FOREGROUND_KIND || tile.plane === "foreground" || tile.plane === "layer2") return false;
  return tile.kind === SESSION_KERNEL_VD_KIND || tile.plane === "session_kernel_vd";
}

export function sessionPlaneLabel(tile: SessionTile): string {
  return isSessionKernelVd(tile) ? VD_PLANE_LABEL : FOREGROUND_PLANE_LABEL;
}

export function normalizeInputOwner(value: unknown): SessionInputOwner | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toUpperCase();
  return normalized === "HUMAN" || normalized === "AI" ? normalized : undefined;
}

/** Named Session Kernel VD tiles keep their Android display; missing identity is not rewritten to 0.
 *  Layer 2 display-0 profile locks are a different plane — never bind them as session tiles. */
export function bindSessionTile(deviceId: string, session: DeviceSessionDescriptor): SessionTile {
  const sessionId = String(session.sessionId || "").trim();
  const foreground = isDefaultForegroundSession(sessionId);
  const rawDisplay = session.displayId;
  const displayId = Number.isInteger(rawDisplay) ? rawDisplay : undefined;
  const plane: SessionTilePlane = foreground ? "foreground" : "session_kernel_vd";
  return {
    sessionId,
    deviceId,
    displayId: foreground
      ? (displayId === 0 || displayId == null ? 0 : displayId)
      : (displayId != null && displayId > 0 ? displayId : undefined),
    kind: foreground ? FOREGROUND_KIND : SESSION_KERNEL_VD_KIND,
    plane,
    targetPackage: session.targetPackage,
    backend: session.backend,
    inputOwner: normalizeInputOwner(session.inputOwner) ?? session.inputOwner,
    state: session.state,
    executable: session.executable,
    executionGeneration: session.executionGeneration,
    frameHealthy: session.frameHealthy,
    inventory: false,
    hotAsk: false,
  };
}

export function markSessionInventory(tiles: SessionTile[]): SessionTile[] {
  let hot = 0;
  return tiles.map((tile) => {
    if (tile.kind === FOREGROUND_KIND || tile.plane === "foreground") return { ...tile, inventory: false, hotAsk: false };
    const active = tile.state !== "STOPPED";
    if (active && hot < MAX_HOT_BACKGROUND_ASK) {
      hot += 1;
      return { ...tile, inventory: false, hotAsk: true };
    }
    return { ...tile, inventory: true, hotAsk: false };
  });
}

export function parseFleetWsEvent(raw: unknown): FleetWsEvent | null {
  let data: unknown = raw;
  if (typeof raw === "string") {
    try { data = JSON.parse(raw); } catch { return null; }
  }
  if (!data || typeof data !== "object") return null;
  const rec = data as Record<string, unknown>;
  const nested = rec.payload && typeof rec.payload === "object" ? rec.payload as Record<string, unknown> : null;
  const event = stringField(rec, "event") ?? stringField(rec, "type") ?? stringField(rec, "value") ?? (nested ? stringField(nested, "event") : undefined);
  if (!event) return null;
  const sessionId = stringField(rec, "sessionId") ?? stringField(rec, "session_id") ?? (nested ? stringField(nested, "sessionId") ?? stringField(nested, "session_id") : undefined);
  const deviceId = stringField(rec, "deviceId") ?? stringField(rec, "device_id") ?? (nested ? stringField(nested, "deviceId") ?? stringField(nested, "device_id") : undefined);
  const displayId = intField(rec, "displayId") ?? intField(rec, "display_id") ?? (nested ? intField(nested, "displayId") ?? intField(nested, "display_id") : undefined);
  const inputOwner = stringField(rec, "inputOwner") ?? stringField(rec, "input_owner") ?? (nested ? stringField(nested, "inputOwner") ?? stringField(nested, "input_owner") : undefined);
  const owner = stringField(rec, "owner") ?? (nested ? stringField(nested, "owner") : undefined);
  const state = stringField(rec, "state") ?? (nested ? stringField(nested, "state") : undefined);
  return { event, sessionId, deviceId, displayId, inputOwner, owner, state };
}

export function isSessionFabricEvent(event: FleetWsEvent): boolean {
  const name = event.event.toLowerCase();
  return name === "session.added" || name === "session.removed";
}

export function applySessionEvent(tiles: SessionTile[], event: FleetWsEvent): SessionTile[] {
  const name = event.event.toLowerCase();
  const sessionId = String(event.sessionId || "").trim();
  if (!sessionId) return tiles;
  if (name === "session.removed") {
    return markSessionInventory(tiles.filter((tile) => tile.sessionId !== sessionId));
  }
  if (name !== "session.added") return tiles;
  const next = bindSessionTile(event.deviceId || "", {
    sessionId,
    displayId: event.displayId,
    state: event.state || "RUNNING",
    targetPackage: null,
    inputOwner: normalizeInputOwner(event.inputOwner) ?? normalizeInputOwner(event.owner),
  });
  const existing = tiles.findIndex((tile) => tile.sessionId === sessionId);
  if (existing >= 0) {
    const copy = tiles.slice();
    copy[existing] = {
      ...copy[existing],
      ...next,
      state: event.state || copy[existing].state || next.state,
      inputOwner: next.inputOwner ?? copy[existing].inputOwner,
    };
    return markSessionInventory(copy);
  }
  return markSessionInventory([...tiles, next]);
}

export function sessionDisplayLabel(tile: SessionTile): string {
  if (tile.kind === FOREGROUND_KIND || tile.plane === "foreground") {
    return tile.displayId === 0 || tile.displayId == null ? "default (human)" : String(tile.displayId);
  }
  if (tile.displayId != null && tile.displayId > 0) return String(tile.displayId);
  return "unknown (not display 0)";
}

export function jpegFocusTarget(tile: SessionTile): { sessionId: string; displayId: number } {
  const named = isSessionKernelVd(tile) || (tile.kind !== FOREGROUND_KIND && !isDefaultForegroundSession(tile.sessionId));
  if (named) {
    if (tile.displayId == null || tile.displayId <= 0) {
      throw new Error("Named Session Kernel VD JPEG focus cannot rewrite to display 0");
    }
    return { sessionId: tile.sessionId, displayId: tile.displayId };
  }
  return { sessionId: tile.sessionId, displayId: tile.displayId ?? 0 };
}

export function tileForSessionScope(
  tiles: SessionTile[],
  scope: { sessionId?: string; session_id?: string; displayId?: number; display_id?: number },
): SessionTile | null {
  const sessionId = String(scope.sessionId ?? scope.session_id ?? "").trim();
  const rawDisplay = scope.displayId ?? scope.display_id;
  const displayId = typeof rawDisplay === "number" && Number.isInteger(rawDisplay) ? rawDisplay : undefined;
  if (sessionId && isDefaultForegroundSession(sessionId)) {
    return tiles.find((tile) => tile.sessionId === sessionId && (tile.kind === FOREGROUND_KIND || tile.plane === "foreground")) ?? null;
  }
  if (displayId === 0) return null;
  return tiles.find((tile) => {
    if (!isSessionKernelVd(tile)) return false;
    if (sessionId && tile.sessionId !== sessionId) return false;
    if (!sessionId && (displayId == null || displayId <= 0)) return false;
    if (displayId != null && displayId > 0 && tile.displayId !== displayId) return false;
    return true;
  }) ?? null;
}

export function readExactSessionSnapshotHeaders(headers: { get(name: string): string | null }): { displayId: number } {
  const exact = String(headers.get("X-Cyclone-Foreground-Substitution") || "").toLowerCase();
  if (exact !== "false") throw new Error("Cyclone refused an unproven background preview");
  const displayRaw = headers.get("X-Cyclone-Display-Id") || "";
  const displayId = Number(displayRaw);
  if (!Number.isInteger(displayId) || displayId <= 0) throw new Error("Background preview returned an invalid display identity");
  return { displayId };
}

function stringField(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function intField(record: Record<string, unknown>, key: string): number | undefined {
  const value = record[key];
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) return Number(value.trim());
  return undefined;
}
