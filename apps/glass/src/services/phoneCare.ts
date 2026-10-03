/**
 * Phone care (alpha 87): one calm answer per phone from the gateway — is Cyclone on the phone current, did it stop
 * or freeze — plus the owner's Update button. The gateway decides; Glass shows the answer and keeps the evidence
 * one click away for whoever debugs.
 */
import type { GatewayClient } from "./gateway.js";

export type CareStatus = "good" | "attention" | "problem" | "working" | "offline";

export interface CareAction {
  kind: "update";
  label: string;
}

export interface CareExit {
  atMs: number;
  kind: string;
  label: string | null;
  description: string | null;
  unexpected: boolean;
  mainThread: string[];
}

export interface CareStall {
  startedAtMs: number;
  durationMs: number;
  suspect: string | null;
  frames: string[];
}

export interface CareUpdate {
  state: string;
  target: string | null;
  from: string | null;
  errorCode: string | null;
  errorDetail: string | null;
}

export interface CareDecisions {
  provider: string | null;
  speed: string | null;
  phoneModel: string | null;
  lessons: number;
  onPhoneShare: number | null;
  decisionsP50: number | null;
  decisionsP95: number | null;
  instantVerified: number | null;
  instantChecked: number;
  shadowAgreement: number | null;
  shadowChecked: number;
  earned: string[];
}

export interface PhoneCare {
  deviceId: string;
  status: CareStatus;
  headline: string;
  detail: string;
  hint: string | null;
  atMs: number | null;
  action: CareAction | null;
  details: {
    phoneVersion: string | null;
    phoneCode: number | null;
    pcVersion: string | null;
    exits: CareExit[];
    stalls: CareStall[];
    freezesToday: number;
    longestFreezeMs: number;
    update: CareUpdate | null;
    diagnosticsPath: string | null;
    healthCollectedAtMs: number | null;
    /** Alpha 89: how this phone decides requests (the grammar, JEV, the phone model). */
    decisions: CareDecisions | null;
  };
}

const STATUSES: CareStatus[] = ["good", "attention", "problem", "working", "offline"];
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const strings = (v: unknown): string[] => list(v).filter((x): x is string => typeof x === "string").slice(0, 24);

export function parsePhoneCare(raw: unknown): PhoneCare {
  const r = obj(raw);
  const d = obj(r.details);
  const versions = obj(d.versions);
  const freezes = obj(d.freezes);
  const update = obj(d.update);
  const error = obj(update.error);
  const action = obj(r.action);
  const status = STATUSES.includes(r.status as CareStatus) ? (r.status as CareStatus) : "offline";
  return {
    deviceId: str(r.deviceId) ?? "",
    status,
    headline: str(r.headline) ?? "",
    detail: str(r.detail) ?? "",
    hint: str(r.hint),
    atMs: num(r.atMs),
    action: action.kind === "update" && str(action.label) ? { kind: "update", label: str(action.label)! } : null,
    details: {
      phoneVersion: str(versions.phone),
      phoneCode: num(versions.phoneCode),
      pcVersion: str(versions.pc),
      exits: list(d.exits).map(obj).filter((e) => num(e.atMs)).map((e) => ({
        atMs: num(e.atMs)!, kind: str(e.kind) ?? "unknown", label: str(e.label), description: str(e.description),
        unexpected: e.unexpected === true, mainThread: strings(e.mainThread),
      })),
      stalls: list(d.stalls).map(obj).filter((s) => num(s.startedAtMs)).map((s) => ({
        startedAtMs: num(s.startedAtMs)!, durationMs: num(s.durationMs) ?? 0, suspect: str(s.suspect), frames: strings(s.frames),
      })),
      freezesToday: num(freezes.count) ?? 0,
      longestFreezeMs: num(freezes.longestMs) ?? 0,
      update: str(update.state) ? {
        state: str(update.state)!, target: str(update.target), from: str(update.from),
        errorCode: str(error.code), errorDetail: str(error.detail),
      } : null,
      diagnosticsPath: str(d.diagnosticsPath),
      healthCollectedAtMs: num(d.healthCollectedAtMs),
      decisions: parseDecisions(d.decisions),
    },
  };
}

/** Android's exit kinds in a few words, for the developer details. */
export function exitKindLabel(kind: string): string {
  const labels: Record<string, string> = {
    anr: "Stopped responding", crash: "Crashed", crash_native: "Crashed (native)", low_memory: "Closed for memory",
    excessive_resource_usage: "Used too much power", initialization_failure: "Failed to start", signaled: "Stopped by the system",
    dependency_died: "Dependency stopped", user_requested: "Closed on the phone", user_stopped: "Force-stopped",
    package_updated: "Updated", package_state_change: "App changed", exit_self: "Closed itself",
    permission_change: "Permission changed", freezer: "Paused in background", other: "System reason", unknown: "Unknown reason",
  };
  return labels[kind] ?? labels.unknown;
}

const base = (device: string) => `/v1/devices/${encodeURIComponent(device)}/care`;

export const phoneCareApi = {
  get: async (client: GatewayClient, device: string) => parsePhoneCare(await client.get(base(device))),
  update: async (client: GatewayClient, device: string) => parsePhoneCare(await client.post(`${base(device)}/update`)),
};

function parseDecisions(raw: unknown): CareDecisions | null {
  if (!raw || typeof raw !== "object") return null;
  const r = obj(raw);
  const ms = obj(r.decisionsMs);
  return {
    provider: str(r.provider), speed: str(r.speed), phoneModel: str(r.phoneModel),
    lessons: num(r.lessons) ?? 0, onPhoneShare: num(r.onPhoneShare),
    decisionsP50: num(ms.p50), decisionsP95: num(ms.p95),
    instantVerified: num(r.instantVerified), instantChecked: num(r.instantChecked) ?? 0,
    shadowAgreement: num(r.shadowAgreement), shadowChecked: num(r.shadowChecked) ?? 0,
    earned: strings(r.earned).filter((e) => /^[a-z_]{1,40}$/.test(e)),
  };
}

/** "volume_up" → "Volume up". */
export function actionLabel(intent: string): string {
  const words = intent.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}
