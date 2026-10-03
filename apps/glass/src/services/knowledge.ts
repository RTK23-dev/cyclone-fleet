/**
 * What the phone knows about one app beyond the map: its **scenarios** (known routes to end results, with health from
 * real runs) and its **versions** (which app versions the doors were learned on). Both are computed on the phone
 * (`scenarios.list`, `atlas.versions`); Glass parses defensively and shows them.
 */
import type { Tone } from "../ui/components.js";
import type { GatewayClient } from "./gateway.js";
import type { RunStatus } from "./runs.js";

export type ScenarioHealth = "passing" | "warning" | "critical" | "untested";
/** `sign-in` walks through the app's login room; `signed-in` reaches the app without one; `reach` is any other room. */
export type ScenarioKind = "reach" | "sign-in" | "signed-in";

export interface Scenario {
  scenarioId: string;
  kind: ScenarioKind;
  title: string;
  startScreenId: string;
  endScreenId: string;
  route: string[];
  steps: number;
  danger: boolean;
  health: ScenarioHealth;
  lastVerifiedAt: number | null;
  appVersion: string | null;
  runs: Array<{ runId: string; status: RunStatus; startedAt: number }>;
}

export interface ScenarioList {
  placeId: string;
  persona: "live" | "mapping";
  entryScreenId: string | null;
  scenarios: Scenario[];
}

export interface AppVersionRow {
  versionName: string | null;
  versionCode: number | null;
  installed: boolean;
  doors: number;
  rooms: number;
  failingDoors: number;
  lastSeenAt: number;
}

export interface StaleDoor {
  edgeId: string;
  fromScreenId: string;
  toScreenId: string;
  versionName: string | null;
  versionCode: number | null;
}

export interface AppVersions {
  placeId: string;
  installedVersion: { versionName: string | null; versionCode: number | null } | null;
  needsRemap: boolean;
  versions: AppVersionRow[];
  staleDoorCount: number;
  staleDoors: StaleDoor[];
}

const SCREEN = /^(?:page|screen):[A-Za-z0-9._:-]{1,173}$/;
const KINDS = new Set<ScenarioKind>(["reach", "sign-in", "signed-in"]);
const HEALTH = new Set<ScenarioHealth>(["passing", "warning", "critical", "untested"]);
const RUN_STATUS = new Set<RunStatus>(["running", "suspended", "completed", "failed", "cancelled"]);

export async function getScenarios(client: GatewayClient, deviceId: string, placeId: string, persona: "live" | "mapping" = "mapping", signal?: AbortSignal): Promise<ScenarioList> {
  const body = await client.get<unknown>(
    `/v1/devices/${encodeURIComponent(deviceId)}/apps/scenarios?placeId=${encodeURIComponent(placeId)}&persona=${persona}`,
    signal,
  );
  return parseScenarios(body, placeId);
}

export async function getVersions(client: GatewayClient, deviceId: string, placeId: string, signal?: AbortSignal): Promise<AppVersions> {
  const body = await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/apps/versions?placeId=${encodeURIComponent(placeId)}`, signal);
  return parseVersions(body, placeId);
}

export function parseScenarios(raw: unknown, placeId: string): ScenarioList {
  const r = record(raw);
  const scenarios = Array.isArray(r.scenarios) ? r.scenarios.map(parseScenario).filter((s): s is Scenario => s !== null) : [];
  return {
    placeId,
    persona: r.persona === "live" ? "live" : "mapping",
    entryScreenId: screen(r.entryScreenId),
    scenarios,
  };
}

function parseScenario(raw: unknown): Scenario | null {
  const r = record(raw);
  const route = Array.isArray(r.route) ? r.route.filter((id): id is string => typeof id === "string" && SCREEN.test(id)) : [];
  if (typeof r.scenarioId !== "string" || route.length < 2) return null;
  return {
    scenarioId: r.scenarioId,
    kind: KINDS.has(r.kind as ScenarioKind) ? (r.kind as ScenarioKind) : "reach",
    title: str(r.title) || "Reach a screen",
    startScreenId: route[0]!,
    endScreenId: route[route.length - 1]!,
    route,
    steps: route.length - 1,
    danger: r.danger === true,
    health: HEALTH.has(r.health as ScenarioHealth) ? (r.health as ScenarioHealth) : "untested",
    lastVerifiedAt: typeof r.lastVerifiedAt === "number" ? r.lastVerifiedAt : null,
    appVersion: str(r.appVersion) || null,
    runs: Array.isArray(r.runs)
      ? r.runs
          .map((run) => record(run))
          .filter((run) => typeof run.runId === "string" && RUN_STATUS.has(run.status as RunStatus))
          .map((run) => ({ runId: run.runId as string, status: run.status as RunStatus, startedAt: num(run.startedAt) }))
      : [],
  };
}

export function parseVersions(raw: unknown, placeId: string): AppVersions {
  const r = record(raw);
  const installed = record(r.installedVersion);
  return {
    placeId,
    installedVersion: r.installedVersion ? { versionName: str(installed.versionName) || null, versionCode: numOrNull(installed.versionCode) } : null,
    needsRemap: r.needsRemap === true,
    versions: Array.isArray(r.versions)
      ? r.versions.map((row) => {
          const v = record(row);
          return {
            versionName: str(v.versionName) || null,
            versionCode: numOrNull(v.versionCode),
            installed: v.installed === true,
            doors: num(v.doors),
            rooms: num(v.rooms),
            failingDoors: num(v.failingDoors),
            lastSeenAt: num(v.lastSeenAt),
          };
        })
      : [],
    staleDoorCount: num(r.staleDoorCount),
    staleDoors: Array.isArray(r.staleDoors)
      ? r.staleDoors
          .map((row) => record(row))
          .filter((d) => typeof d.edgeId === "string" && screen(d.fromScreenId) && screen(d.toScreenId))
          .map((d) => ({
            edgeId: d.edgeId as string,
            fromScreenId: d.fromScreenId as string,
            toScreenId: d.toScreenId as string,
            versionName: str(d.versionName) || null,
            versionCode: numOrNull(d.versionCode),
          }))
      : [],
  };
}

/** The chip that marks the two login scenarios; none for ordinary routes. */
export function kindLabel(kind: ScenarioKind): string | null {
  if (kind === "sign-in") return "Login";
  if (kind === "signed-in") return "No login";
  return null;
}

export function healthLabel(health: ScenarioHealth): string {
  switch (health) {
    case "passing":
      return "Passing";
    case "warning":
      return "Warning";
    case "critical":
      return "Critical";
    case "untested":
      return "Not run yet";
  }
}

export function healthTone(health: ScenarioHealth): Tone {
  switch (health) {
    case "passing":
      return "success";
    case "warning":
      return "warning";
    case "critical":
      return "danger";
    case "untested":
      return "neutral";
  }
}

export function versionText(version: { versionName: string | null; versionCode: number | null } | null): string {
  if (!version) return "unknown";
  if (version.versionName && version.versionCode != null) return `${version.versionName} (${version.versionCode})`;
  return version.versionName ?? (version.versionCode != null ? String(version.versionCode) : "unknown");
}

function screen(value: unknown): string | null {
  return typeof value === "string" && SCREEN.test(value) ? value : null;
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function str(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function numOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export interface Here {
  placeId: string | null;
  roomId: string | null;
  appVersion: string | null;
  observedAt: number | null;
}

/** Where the phone is now (`atlas.here`): the app and structural room on the foreground screen. */
export async function getHere(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<Here> {
  const r = record(await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/atlas/here`, signal));
  return {
    placeId: typeof r.placeId === "string" && /^package:[A-Za-z][A-Za-z0-9_.]{1,150}$/.test(r.placeId) ? r.placeId : null,
    roomId: screen(r.roomId),
    appVersion: str(r.appVersion) || null,
    observedAt: typeof r.observedAt === "number" ? r.observedAt : null,
  };
}

export const STALE_AFTER_DAYS = 14;

/** Days since the route last worked when that is longer than STALE_AFTER_DAYS; null when fresh or never verified. */
export function staleDays(lastVerifiedAt: number | null, now: number): number | null {
  if (lastVerifiedAt === null) return null;
  const days = Math.floor((now - lastVerifiedAt) / 86_400_000);
  return days > STALE_AFTER_DAYS ? days : null;
}
