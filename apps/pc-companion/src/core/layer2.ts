import type { Layer2Status, Layer2Workspace } from "../services/types.js";

export const LAYER2_PROTOCOL = "cyclone.one.layer2.v1";
export const LAYER2_PLANE = "layer2" as const;
export const LAYER2_SESSION_ID = "default-foreground";
export const LAYER2_DISPLAY_LABEL = "Layer 2 · display 0";
export const LAYER2_STRIP_TITLE = "Layer 2 workspaces";
export const LAYER2_STRIP_COPY =
  "These are display-0 time-sliced app/profile locks, not named virtual-display (VD) sessions. Pause releases the global mutate lock; Release clears selection and armed jobs. GATE stays authoritative on the phone.";
export const LAYER2_COMPACT_COPY =
  "Display-0 time-sliced app/profile lock, not a named VD session.";
export const LAYER2_EMPTY_COPY = "No Layer 2 profiles registered on the phone";

export function isLayer2Plane(value: unknown): boolean {
  return Boolean(value && typeof value === "object" && (value as { plane?: unknown }).plane === LAYER2_PLANE);
}

export function bindLayer2Status(deviceId: string, raw: unknown): Layer2Status {
  return normalizeLayer2Status(raw, deviceId);
}

/** Coerce Layer 2 payloads. Drop display>0 rows; never rewrite a Layer 2 row into a session tile. */
export function normalizeLayer2Status(raw: unknown, deviceId = ""): Layer2Status {
  const rec = asRecord(raw);
  const workspaces = Array.isArray(rec.workspaces) ? rec.workspaces : [];
  const holder = stringOrNull(rec.holder) ?? stringOrNull(rec.lockOwner);
  const lockOwner = stringOrNull(rec.lockOwner) ?? holder;
  const status: Layer2Status = {
    protocol: stringField(rec, "protocol") || LAYER2_PROTOCOL,
    deviceId: stringField(rec, "deviceId") || deviceId,
    sessionId: LAYER2_SESSION_ID,
    displayId: 0,
    plane: LAYER2_PLANE,
    workspaces: workspaces.map(normalizeLayer2Workspace).filter((row): row is Layer2Workspace => row != null),
    holder,
    lockOwner,
    workspaceGeneration: numberOrNull(rec.workspaceGeneration),
    armed: stringList(rec.armed),
    gated: rec.gated === true,
  };
  const goals = stringRecord(rec.goals);
  if (goals) status.goals = goals;
  const root = stringField(rec, "root");
  if (root) status.root = root;
  const workspaceId = stringField(rec, "workspaceId");
  if (workspaceId) status.workspaceId = workspaceId;
  if (typeof rec.verified === "boolean") status.verified = rec.verified;
  const next = stringField(rec, "next");
  if (next) status.next = next;
  return status;
}

export function lockOwnerLabel(status: Layer2Status): string {
  const owner = status.lockOwner ?? status.holder;
  if (!owner) return "Lock owner: none";
  const workspace = status.workspaces.find((row) => row.id === owner);
  return workspace ? `Lock owner: ${workspace.label}` : `Lock owner: ${owner}`;
}

export function generationLabel(status: Layer2Status): string {
  return status.workspaceGeneration == null ? "Generation: —" : `Generation: ${status.workspaceGeneration}`;
}

export function armedGoalLabel(status: Layer2Status): string {
  const first = status.armed[0];
  if (!first) return "Armed goal: none";
  const goal = status.goals?.[first]?.trim();
  return goal ? `Armed goal: ${goal.slice(0, 80)}` : `Armed goal: ${first}`;
}

export function gatedLabel(status: Layer2Status): string {
  return status.gated ? "GATE pending" : "GATE clear";
}

export function canPauseLayer2(status: Layer2Status): boolean {
  return Boolean(status.lockOwner ?? status.holder);
}

export function canReleaseLayer2(status: Layer2Status): boolean {
  return !status.gated;
}

function normalizeLayer2Workspace(raw: unknown): Layer2Workspace | null {
  const rec = asRecord(raw);
  const displayId = rec.displayId;
  if (typeof displayId === "number" && displayId !== 0) return null;
  const id = stringField(rec, "id");
  const label = stringField(rec, "label");
  const appPackage = stringField(rec, "appPackage") || stringField(rec, "package");
  if (!id || !label || !appPackage) return null;
  const androidUserId = typeof rec.androidUserId === "number" && Number.isInteger(rec.androidUserId) && rec.androidUserId >= 0
    ? rec.androidUserId
    : 0;
  const state = stringField(rec, "state") || "idle";
  return { id, label, appPackage, androidUserId, displayId: 0, state };
}

function asRecord(raw: unknown): Record<string, unknown> {
  return raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
}

function stringField(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function stringOrNull(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) ? value : null;
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string" && Boolean(item.trim())).map((item) => item.trim());
}

function stringRecord(value: unknown): Record<string, string> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const next: Record<string, string> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (!key.trim() || typeof item !== "string" || !item.trim()) continue;
    next[key] = item.slice(0, 500);
  }
  return Object.keys(next).length ? next : undefined;
}
