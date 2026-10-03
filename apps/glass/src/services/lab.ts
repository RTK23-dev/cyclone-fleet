/**
 * Cyclone Lab (`/v1/lab/*`): experiments of Mind missions on the phone, scored by the gateway from the phone's real
 * state. Glass only shows and starts them; the verdicts, rates and A/B tests are computed by the gateway.
 */
import type { GatewayClient } from "./gateway.js";

export interface LabMission {
  id: string;
  title: string;
  goal: string;
  category: string;
  suites: string[];
  apps: string[];
  expect: "done" | "boundary";
  minutes: number;
  notes: string;
}

export interface LabCatalog {
  missions: LabMission[];
  suites: string[];
  problems: string[];
  customDir: string;
}

export interface LabVariant {
  name: string;
  modelId?: string | null;
  effort?: "low" | "medium" | "high" | null;
  workingMinutes?: number | null;
  marks?: boolean;
  freshMemory?: boolean;
  /** Run from the learned map (hints, map card, go_to). On unless an arm turns it off. */
  useMap?: boolean;
  /** Plan 37: the mission workspace (live state, app stays, checks) or the classic context. Absent: classic. */
  context?: "classic" | "workspace" | null;
  promptAddendum?: string;
}

export interface LabCurrent {
  index: number;
  missionId: string;
  variant: string;
  phase: string;
  turns: number | null;
  costUsd: number | null;
}

export interface LabExperiment {
  id: string;
  name: string;
  deviceId: string;
  createdAt: number;
  finishedAt: number | null;
  missions: string[];
  variants: LabVariant[];
  repetitions: number;
  status: "running" | "done" | "stopped" | "halted";
  reason: string | null;
  total: number;
  done: number;
  current: LabCurrent | null;
  appVersion: string | null;
  gatewayVersion: string | null;
  arms?: Record<string, { rate: number | null; scored: number }>;
}

export interface NumberSummary {
  median: number | null;
  mean: number | null;
  total: number;
}

export interface LabArm {
  runs: number;
  scored: number;
  passes: number;
  rate: number | null;
  ci95: [number, number];
  infra: number;
  falseSuccess: number;
  safetyFailures: number;
  durationSec: NumberSummary;
  turns: NumberSummary;
  actions: NumberSummary;
  errors: NumberSummary;
  costUsd: NumberSummary;
  ownerAsks: number;
  categories: Record<string, number>;
  causes: Record<string, number>;
  tools: Record<string, number>;
  /** Plan 37: what the mission workspace did in this arm; absent for classic arms. */
  workspace?: LabWorkspaceStats | null;
}

export interface LabWorkspaceStats {
  missions: number;
  stays: NumberSummary;
  expectHitRate: number | null;
  checks: Record<string, number>;
  surprises: NumberSummary;
  diversions: number;
  folded: NumberSummary;
  done: Record<string, number>;
}

export interface LabComparison {
  a: string;
  b: string;
  rateA: number | null;
  rateB: number | null;
  delta: number | null;
  pValue: number;
  missionsBetterA: string[];
  missionsBetterB: string[];
  missionsSame: string[];
  costRatio: number | null;
  timeRatio: number | null;
  turnsRatio?: number | null;
  conclusion: string;
}

export interface LabCell {
  passes: number;
  scored: number;
  infra: number;
  categories: Record<string, number>;
}

export interface LabCheck {
  check: string;
  ok: boolean | null;
  detail: string;
}

export interface LabTrial {
  trialId: string;
  missionId: string;
  variant: string;
  rep: number;
  verdict: "pass" | "fail" | "infra" | "skipped";
  category: string;
  cause: string;
  signals: string[];
  checks: LabCheck[];
  durationMs: number;
  owner: Array<{ kind: string; action: string }>;
  error: string | null;
  phone: {
    status?: string;
    summary?: string;
    turns?: number;
    traceId?: string | null;
    usage?: { costUsd?: number };
    metrics?: { errorTail?: string[]; actions?: number; errors?: number };
  } | null;
}

export interface LabDetail {
  experiment: LabExperiment;
  arms: Record<string, LabArm>;
  comparisons: LabComparison[];
  matrix: Array<{ missionId: string; cells: Record<string, LabCell> }>;
  insights: string[];
  trials: LabTrial[];
}

export function getCatalog(client: GatewayClient, signal?: AbortSignal): Promise<LabCatalog> {
  return client.get<LabCatalog>("/v1/lab/missions", signal);
}

export async function listExperiments(client: GatewayClient, signal?: AbortSignal): Promise<LabExperiment[]> {
  const body = await client.get<{ experiments: LabExperiment[] }>("/v1/lab/experiments", signal);
  return Array.isArray(body.experiments) ? body.experiments : [];
}

export function getExperiment(client: GatewayClient, id: string, signal?: AbortSignal): Promise<LabDetail> {
  return client.get<LabDetail>(`/v1/lab/experiments/${encodeURIComponent(id)}`, signal);
}

export function startExperiment(
  client: GatewayClient,
  body: { deviceId: string; name: string; missions: string[]; variants: LabVariant[]; repetitions: number },
): Promise<LabExperiment> {
  return client.post<LabExperiment>("/v1/lab/experiments", body);
}

export function stopExperiment(client: GatewayClient, id: string): Promise<LabExperiment> {
  return client.post<LabExperiment>(`/v1/lab/experiments/${encodeURIComponent(id)}/stop`, {});
}

export async function exportTrials(client: GatewayClient, id: string): Promise<string> {
  const response = await client.authorizedFetch(`${client.baseUrl}/v1/lab/experiments/${encodeURIComponent(id)}/trials.jsonl`);
  if (!response.ok) throw new Error(`Export failed (${response.status}).`);
  return response.text();
}

/** A variant as the phone expects it: blank fields mean "keep the phone's own setting" and are left out. */
export function cleanVariant(raw: LabVariant): LabVariant {
  const out: LabVariant = { name: raw.name.trim() };
  if (raw.modelId && raw.modelId.trim()) out.modelId = raw.modelId.trim();
  if (raw.effort) out.effort = raw.effort;
  if (raw.workingMinutes != null && Number.isFinite(raw.workingMinutes)) out.workingMinutes = Math.round(raw.workingMinutes);
  if (raw.marks === false) out.marks = false;
  if (raw.freshMemory === false) out.freshMemory = false;
  if (raw.useMap === false) out.useMap = false;
  if (raw.context === "workspace" || raw.context === "classic") out.context = raw.context;
  if (raw.promptAddendum && raw.promptAddendum.trim()) out.promptAddendum = raw.promptAddendum.trim();
  return out;
}

export function rateText(rate: number | null, ci?: [number, number]): string {
  if (rate == null) return "—";
  const main = `${Math.round(rate * 100)}%`;
  return ci ? `${main} (${Math.round(ci[0] * 100)}–${Math.round(ci[1] * 100)})` : main;
}

export function estimate(missions: LabMission[], variants: number, repetitions: number): { runs: number; minutes: number } {
  const runs = missions.length * variants * repetitions;
  // Most missions finish well inside their limit; about a third of it plus setup is a fair planning number.
  const minutes = Math.round(missions.reduce((sum, m) => sum + m.minutes / 3 + 0.5, 0) * variants * repetitions);
  return { runs, minutes };
}

const CATEGORY_LABELS: Record<string, string> = {
  pass: "Passed",
  false_success: "Said done, wasn't",
  missed_boundary: "Skipped approval",
  boundary_broken: "Acted after decline",
  gave_up: "Gave up",
  out_of_budget: "Out of time",
  timeout: "Lab timeout",
  needs_owner: "Needed you",
  failed: "Failed",
  infra: "Not measured",
  skipped: "Skipped",
};

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category.replace(/_/g, " ");
}

export function categoryTone(category: string): "success" | "danger" | "warning" | "neutral" {
  if (category === "pass") return "success";
  if (["false_success", "missed_boundary", "boundary_broken"].includes(category)) return "danger";
  if (category === "infra" || category === "skipped") return "neutral";
  return "warning";
}

export function statusLabel(experiment: LabExperiment): string {
  switch (experiment.status) {
    case "running":
      return `Running · ${experiment.done}/${experiment.total}`;
    case "done":
      return "Finished";
    case "stopped":
      return "Stopped";
    default:
      return "Halted";
  }
}
