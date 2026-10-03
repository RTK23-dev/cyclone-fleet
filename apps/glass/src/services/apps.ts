/** The phone's app catalog (`GET /v1/devices/{id}/apps`, op `apps.list`). Package facts and map counts only. */
import type { GatewayClient } from "./gateway.js";

export type AppMapStatus = "unmapped" | "partial" | "mapped" | "stale";
export type AppKind = "package" | "chrome-origin";

export interface AppVersion {
  versionName: string | null;
  versionCode: number | null;
}

export interface AppPersonaMap {
  persona: "live" | "mapping";
  mapStatus: AppMapStatus;
  rooms: number;
  doors: number;
  lastVerifiedAt: number | null;
}

export interface PhoneApp {
  placeId: string;
  kind: AppKind;
  label: string;
  packageName: string | null;
  origin: string | null;
  installed: boolean | null;
  installedVersion: AppVersion | null;
  mapStatus: AppMapStatus;
  rooms: number;
  doors: number;
  lastVerifiedAt: number | null;
  needsRemap: boolean;
  personas: AppPersonaMap[];
  mappedVersions: Array<AppVersion & { doors: number }>;
  /** Scenario health counts (phone alpha.14+); null when unknown or the app has no scenarios. */
  scenarios: { passing: number; warning: number; critical: number; untested: number } | null;
}

export interface AppCatalog {
  apps: PhoneApp[];
  truncated: boolean;
}

export type AppFilter = "all" | "mapped" | "needs-remap" | "unmapped" | "web" | "failing";

const STATUSES = new Set<AppMapStatus>(["unmapped", "partial", "mapped", "stale"]);

export async function loadApps(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<AppCatalog> {
  const body = await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/apps`, signal);
  return parseAppCatalog(body);
}

export function parseAppCatalog(body: unknown): AppCatalog {
  const record = (body && typeof body === "object" ? body : {}) as { apps?: unknown; truncated?: unknown };
  const apps = Array.isArray(record.apps) ? record.apps.map(parseApp).filter((app): app is PhoneApp => app !== null) : [];
  return { apps, truncated: record.truncated === true };
}

function parseApp(raw: unknown): PhoneApp | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const placeId = str(r.placeId);
  if (!placeId || !(placeId.startsWith("package:") || placeId.startsWith("chrome:"))) return null;
  const kind: AppKind = r.kind === "chrome-origin" ? "chrome-origin" : "package";
  return {
    placeId,
    kind,
    label: str(r.label) || placeId.slice(placeId.indexOf(":") + 1),
    packageName: str(r.packageName) || null,
    origin: str(r.origin) || null,
    installed: typeof r.installed === "boolean" ? r.installed : null,
    installedVersion: version(r.installedVersion),
    mapStatus: status(r.mapStatus),
    rooms: count(r.rooms),
    doors: count(r.doors),
    lastVerifiedAt: typeof r.lastVerifiedAt === "number" ? r.lastVerifiedAt : null,
    needsRemap: r.needsRemap === true,
    personas: Array.isArray(r.personas)
      ? r.personas.flatMap((p) => {
          if (!p || typeof p !== "object") return [];
          const q = p as Record<string, unknown>;
          if (q.persona !== "live" && q.persona !== "mapping") return [];
          return [{
            persona: q.persona,
            mapStatus: status(q.mapStatus),
            rooms: count(q.rooms),
            doors: count(q.doors),
            lastVerifiedAt: typeof q.lastVerifiedAt === "number" ? q.lastVerifiedAt : null,
          } satisfies AppPersonaMap];
        })
      : [],
    mappedVersions: Array.isArray(r.mappedVersions)
      ? r.mappedVersions.flatMap((v) => {
          const parsed = version(v);
          return parsed ? [{ ...parsed, doors: count((v as Record<string, unknown>).doors) }] : [];
        })
      : [],
    scenarios: r.scenarios && typeof r.scenarios === "object"
      ? (() => {
          const s = r.scenarios as Record<string, unknown>;
          return { passing: count(s.passing), warning: count(s.warning), critical: count(s.critical), untested: count(s.untested) };
        })()
      : null,
  };
}

/** "9 scenarios · 2 critical" for the Apps page; null without scenarios. */
export function scenarioSummary(app: PhoneApp): { text: string; tone: "success" | "warning" | "danger" | "neutral" } | null {
  const s = app.scenarios;
  if (!s) return null;
  const total = s.passing + s.warning + s.critical + s.untested;
  if (!total) return null;
  const head = `${total} ${total === 1 ? "scenario" : "scenarios"}`;
  if (s.critical) return { text: `${head} · ${s.critical} critical`, tone: "danger" };
  if (s.warning) return { text: `${head} · ${s.warning} warning`, tone: "warning" };
  if (s.passing) return { text: `${head} · ${s.passing} passing`, tone: "success" };
  return { text: `${head} · not run yet`, tone: "neutral" };
}

export function versionLabel(v: AppVersion | null): string {
  if (!v) return "—";
  return v.versionName || (v.versionCode != null ? `build ${v.versionCode}` : "—");
}

export function statusLabel(app: Pick<PhoneApp, "mapStatus" | "needsRemap">): string {
  if (app.needsRemap) return "Needs remap";
  switch (app.mapStatus) {
    case "mapped":
      return "Mapped";
    case "partial":
      return "Partly mapped";
    case "stale":
      return "Stale";
    case "unmapped":
      return "Not mapped";
  }
}

export function statusTone(app: Pick<PhoneApp, "mapStatus" | "needsRemap">): "success" | "accent" | "warning" | "neutral" {
  if (app.needsRemap || app.mapStatus === "stale") return "warning";
  if (app.mapStatus === "mapped") return "success";
  if (app.mapStatus === "partial") return "accent";
  return "neutral";
}

export function filterApps(apps: PhoneApp[], filter: AppFilter, query: string): PhoneApp[] {
  const q = query.trim().toLowerCase();
  return apps.filter((app) => {
    if (q && !`${app.label} ${app.packageName ?? ""} ${app.origin ?? ""}`.toLowerCase().includes(q)) return false;
    switch (filter) {
      case "all":
        return true;
      case "mapped":
        return app.rooms > 0;
      case "needs-remap":
        return app.needsRemap || app.mapStatus === "stale";
      case "unmapped":
        return app.rooms === 0;
      case "web":
        return app.kind === "chrome-origin";
      case "failing":
        return true; // needs run facts; the Apps page applies it
    }
  });
}

/** Mapped apps first (most rooms first), then everything else alphabetically. */
export function sortApps(apps: PhoneApp[]): PhoneApp[] {
  return [...apps].sort((a, b) => {
    const mappedA = a.rooms > 0 ? 1 : 0;
    const mappedB = b.rooms > 0 ? 1 : 0;
    if (mappedA !== mappedB) return mappedB - mappedA;
    if (mappedA && a.rooms !== b.rooms) return b.rooms - a.rooms;
    return a.label.localeCompare(b.label, undefined, { sensitivity: "base" });
  });
}

export function catalogStats(apps: PhoneApp[]): { total: number; mapped: number; needsRemap: number; rooms: number } {
  return {
    total: apps.length,
    mapped: apps.filter((app) => app.rooms > 0).length,
    needsRemap: apps.filter((app) => app.needsRemap || app.mapStatus === "stale").length,
    rooms: apps.reduce((sum, app) => sum + app.rooms, 0),
  };
}

function version(raw: unknown): AppVersion | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const versionName = str(r.versionName) || null;
  const versionCode = typeof r.versionCode === "number" && Number.isFinite(r.versionCode) ? r.versionCode : null;
  return versionName || versionCode != null ? { versionName, versionCode } : null;
}

function status(raw: unknown): AppMapStatus {
  return STATUSES.has(raw as AppMapStatus) ? (raw as AppMapStatus) : "unmapped";
}

function count(raw: unknown): number {
  return typeof raw === "number" && Number.isFinite(raw) && raw >= 0 ? Math.floor(raw) : 0;
}

function str(raw: unknown): string {
  return typeof raw === "string" ? raw.trim() : "";
}

/** Fleet knowledge (plan 22 §4.4): what Cyclone can do with an app today, from facts the phone reported. */
export type AppKnowledge = "ready" | "attention" | "partial" | "unmapped";

export const KNOWLEDGE_LABEL: Record<AppKnowledge, string> = {
  ready: "Routing ready",
  attention: "Needs attention",
  partial: "Partial",
  unmapped: "Unmapped",
};

export const KNOWLEDGE_TONE: Record<AppKnowledge, "success" | "warning" | "accent" | "neutral"> = {
  ready: "success",
  attention: "warning",
  partial: "accent",
  unmapped: "neutral",
};

/**
 * Routing ready = mapped on the installed version and nothing says otherwise (no remap needed, no failing last run,
 * no critical scenario). Needs attention = something known is wrong. Partial = some map, not enough to route on.
 */
export function knowledgeOf(app: PhoneApp, lastRunFailed = false): AppKnowledge {
  if (app.rooms === 0) return "unmapped";
  if (app.needsRemap || app.mapStatus === "stale" || lastRunFailed || (app.scenarios?.critical ?? 0) > 0) return "attention";
  if (app.mapStatus === "mapped") return "ready";
  return "partial";
}

function sameVersion(a: AppVersion | null, b: AppVersion | null): boolean {
  if (!a || !b) return false;
  if (a.versionCode != null && b.versionCode != null) return a.versionCode === b.versionCode;
  return !!a.versionName && a.versionName === b.versionName;
}

/**
 * Share of the app's doors seen on the installed version (0..1), or null when unknown (web places, no versions).
 * This is the honest "confidence" the fleet shows: a door last seen on an older build may have moved.
 */
export function currentShare(app: PhoneApp): number | null {
  if (!app.doors || !app.installedVersion || !app.mappedVersions.length) return null;
  const current = app.mappedVersions.find((v) => sameVersion(v, app.installedVersion));
  return Math.min(1, (current?.doors ?? 0) / app.doors);
}

/** "Current" when the map was drawn on the installed version, "Map from v…" when only older versions are known. */
export function freshnessOf(app: PhoneApp): { text: string; tone: "success" | "warning" | "neutral" } {
  if (app.rooms === 0) return { text: "—", tone: "neutral" };
  if (app.kind === "chrome-origin" || !app.installedVersion || !app.mappedVersions.length) {
    return app.mapStatus === "stale" ? { text: "Stale", tone: "warning" } : { text: "Current", tone: "success" };
  }
  if (app.mappedVersions.some((v) => sameVersion(v, app.installedVersion)) && !app.needsRemap) return { text: "Current", tone: "success" };
  return { text: `Map from ${versionLabel(app.mappedVersions[0] ?? null)}`, tone: "warning" };
}
