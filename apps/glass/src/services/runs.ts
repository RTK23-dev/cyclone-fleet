/**
 * The phone's runs (`runs.list` / `runs.get`). Steps and the cause of death are computed on the phone (RunInsight);
 * Glass parses defensively and labels them. Nothing here judges a run.
 */
import type { Tone } from "../ui/components.js";
import type { GatewayClient } from "./gateway.js";

export type RunStatus = "running" | "suspended" | "completed" | "failed" | "cancelled";
export type RunFilter = "all" | "failed" | "completed" | "stopped";
export type StepOutcome = "ok" | "failed" | "unverified" | "recovered" | "info";

export interface RunCause {
  kind: string;
  stepIndex: number | null;
  headline: string;
  detail: string;
  fix: string;
}

export interface RunMetrics {
  toolCalls: number;
  toolFailures: number;
  verificationFailures: number;
  recoveries: number;
  visionChecks: number;
  verifiedActions: number;
}

export interface RunSummary {
  runId: string;
  goal: string;
  model: string;
  status: RunStatus;
  startedAt: number;
  endedAt: number | null;
  durationMs: number;
  decisions: number;
  stepCount: number;
  metrics: RunMetrics;
  cause: RunCause | null;
  /** Run record v2 (phone alpha.11+). Null on older phones. */
  mapSteps: number | null;
  modelSteps: number | null;
  places: RunPlace[];
  /** The developer marked this run as expected; it no longer counts against scenario health. Null on older phones. */
  expected: boolean | null;
  /** Optional on the wire; empty for older phones. Proof and status are supplied by the phone. */
  clauses: RunClause[];
  ledger: RunFact[];
}

export interface RunClause {
  id: string;
  text: string;
  place: string | null;
  status: "pending" | "active" | "verified" | "needs-approval" | "failed";
  proof: string | null;
}

export interface RunFact {
  key: string;
  value: string;
  sourcePlace: string;
  sourceRoom: string;
  persona: "live";
  readAtMs: number;
}

export interface RunPlace {
  placeId: string;
  appVersion: string | null;
  /** Structural rooms in the order the run walked through them (same ids as the app's Map). */
  route: string[];
}

export interface RunEvent {
  at: number;
  kind: string;
  text: string;
  code: string | null;
  ok: boolean | null;
  detail: string | null;
}

export interface RunStep {
  index: number;
  startedAt: number;
  endedAt: number;
  title: string;
  action: string | null;
  pageId: string | null;
  outcome: StepOutcome;
  verification: string | null;
  recovery: string | null;
  vision: boolean;
  eventsTruncated: boolean;
  events: RunEvent[];
  roomId: string | null;
  roomAfter: string | null;
  placeId: string | null;
  appVersion: string | null;
  decisionSource: "map" | "model" | null;
  expectedRoomId: string | null;
}

export interface RunDetail extends RunSummary {
  result: string;
  stepsTruncated: boolean;
  steps: RunStep[];
}

const STATUSES = new Set<RunStatus>(["running", "suspended", "completed", "failed", "cancelled"]);
const OUTCOMES = new Set<StepOutcome>(["ok", "failed", "unverified", "recovered", "info"]);
const ROOM = /^screen:[a-z_]{1,40}:[0-9a-f]{8,64}$/;
const PLACE = /^package:[A-Za-z][A-Za-z0-9_.]{1,150}$/;
const VERSION = /^[A-Za-z0-9._+-]{1,40}$/;
const NAV_PLACE = /^(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?)$/;
const MASKED_FACT = /^[^\s*@]\*{3}(?:@[A-Za-z0-9.-]+\.[A-Za-z]{2,})?$/;
const CLAUSE_STATUSES = new Set(["pending", "active", "verified", "needs-approval", "failed"]);

export async function listRuns(client: GatewayClient, deviceId: string, filter: RunFilter = "all", limit = 100, signal?: AbortSignal): Promise<RunSummary[]> {
  const body = await client.get<{ runs?: unknown }>(
    `/v1/devices/${encodeURIComponent(deviceId)}/runs?limit=${limit}&filter=${filter}`,
    signal,
  );
  return Array.isArray(body?.runs) ? body.runs.map(parseRunSummary).filter((run): run is RunSummary => run !== null) : [];
}

export async function getRun(client: GatewayClient, deviceId: string, runId: string, signal?: AbortSignal): Promise<RunDetail> {
  const body = await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/runs/${encodeURIComponent(runId)}`, signal);
  const detail = parseRunDetail(body);
  if (!detail || detail.runId !== runId) throw new Error("The phone returned a different run.");
  return detail;
}

export function parseRunSummary(raw: unknown): RunSummary | null {
  const r = record(raw);
  const runId = str(r.runId);
  if (!/^[A-Za-z0-9_-]{4,120}$/.test(runId)) return null;
  const metrics = record(r.metrics);
  const cause = record(r.cause);
  return {
    runId,
    goal: str(r.goal) || "(no goal recorded)",
    model: str(r.model),
    status: STATUSES.has(r.status as RunStatus) ? (r.status as RunStatus) : "failed",
    startedAt: num(r.startedAt),
    endedAt: typeof r.endedAt === "number" ? r.endedAt : null,
    durationMs: num(r.durationMs),
    decisions: num(r.decisions),
    stepCount: num(r.stepCount),
    metrics: {
      toolCalls: num(metrics.toolCalls),
      toolFailures: num(metrics.toolFailures),
      verificationFailures: num(metrics.verificationFailures),
      recoveries: num(metrics.recoveries),
      visionChecks: num(metrics.visionChecks),
      verifiedActions: num(metrics.verifiedActions),
    },
    cause: str(cause.kind)
      ? {
          kind: str(cause.kind),
          stepIndex: typeof cause.stepIndex === "number" ? cause.stepIndex : null,
          headline: str(cause.headline) || causeLabel(str(cause.kind)),
          detail: str(cause.detail),
          fix: str(cause.fix),
        }
      : null,
    mapSteps: typeof r.mapSteps === "number" ? r.mapSteps : null,
    modelSteps: typeof r.modelSteps === "number" ? r.modelSteps : null,
    places: Array.isArray(r.places) ? r.places.map(parsePlace).filter((place): place is RunPlace => place !== null) : [],
    expected: typeof r.expected === "boolean" ? r.expected : null,
    clauses: Array.isArray(r.clauses) ? r.clauses.slice(0, 16).map(parseClause).filter((c): c is RunClause => c !== null) : [],
    ledger: Array.isArray(r.ledger) ? r.ledger.slice(0, 4).map(parseFact).filter((f): f is RunFact => f !== null) : [],
  };
}

function parseClause(raw: unknown): RunClause | null {
  const r = record(raw);
  if (!/^clause-(?:[1-9]|1[0-6])$/.test(str(r.id)) || typeof r.text !== "string" || r.text.length > 500 ||
      !CLAUSE_STATUSES.has(str(r.status)) || (r.place !== null && !match(r.place, NAV_PLACE)) ||
      (r.proof !== null && (typeof r.proof !== "string" || r.proof.length > 400))) return null;
  return { id: str(r.id), text: r.text, place: match(r.place, NAV_PLACE), status: r.status as RunClause["status"], proof: r.proof as string | null };
}

function parseFact(raw: unknown): RunFact | null {
  const r = record(raw);
  if (!["signed-in-email", "found-username", "thread-with", "connected-network"].includes(str(r.key)) ||
      !match(r.value, MASKED_FACT) || str(r.value).length > 256 || !match(r.sourcePlace, NAV_PLACE) ||
      !match(r.sourceRoom, ROOM) || r.persona !== "live" || !Number.isSafeInteger(r.readAtMs) || (r.readAtMs as number) < 0) return null;
  return { key: str(r.key), value: str(r.value), sourcePlace: str(r.sourcePlace), sourceRoom: str(r.sourceRoom), persona: "live", readAtMs: r.readAtMs as number };
}

export async function markRun(client: GatewayClient, deviceId: string, runId: string, expected: boolean): Promise<boolean> {
  const body = await client.post<{ expected?: unknown }>(
    `/v1/devices/${encodeURIComponent(deviceId)}/runs/${encodeURIComponent(runId)}/mark`,
    { expected },
  );
  return body?.expected === true;
}

/** What Learn did for one run: the phone's own sentence, or why it could not learn. Counts only, never content. */
export interface LearnResult {
  learned: boolean;
  alreadyLearned: boolean;
  sentence: string;
  refusal: string | null;
}

/** Learn: one press per run. The phone learns every screen, control and move the run saw and made. */
export async function learnRun(client: GatewayClient, deviceId: string, runId: string): Promise<LearnResult> {
  const body = await client.post<Record<string, unknown>>(
    `/v1/devices/${encodeURIComponent(deviceId)}/runs/${encodeURIComponent(runId)}/learn`,
    {},
  );
  return parseLearnResult(body);
}

export function parseLearnResult(raw: unknown): LearnResult {
  const r = record(raw);
  const refusal = record(r.refusal);
  const learned = r.learned === true;
  return {
    learned,
    alreadyLearned: learned && r.alreadyLearned === true,
    sentence: learned ? str(r.sentence).slice(0, 600) : "",
    refusal: learned ? null : str(refusal.message).slice(0, 200) || "The phone could not learn from this run.",
  };
}

function parsePlace(raw: unknown): RunPlace | null {
  const r = record(raw);
  const placeId = str(r.placeId);
  if (!PLACE.test(placeId)) return null;
  return {
    placeId,
    appVersion: match(r.appVersion, VERSION),
    route: Array.isArray(r.route) ? r.route.filter((room): room is string => typeof room === "string" && ROOM.test(room)).slice(0, 60) : [],
  };
}

function match(value: unknown, pattern: RegExp): string | null {
  return typeof value === "string" && pattern.test(value) ? value : null;
}

/** "screen:list:3fa2…" → "List screen · 3fa2". The phone keeps rooms structural; Glass only names the shape. */
export function roomLabel(roomId: string): string {
  if (roomId.startsWith("page:")) return `Taught screen · ${roomId.slice(-4)}`;
  const [, purpose = "screen", digest = ""] = roomId.split(":");
  const words = purpose.replace(/_/g, " ");
  return `${words.charAt(0).toUpperCase()}${words.slice(1)} screen · ${digest.slice(0, 4)}`;
}

export function appName(placeId: string): string {
  if (placeId.startsWith("chrome:")) return `Chrome · ${placeId.replace(/^chrome:https?:\/\//, "")}`;
  const pkg = placeId.replace(/^package:/, "");
  const known: Record<string, string> = {
    "com.google.android.gm": "Gmail",
    "com.android.chrome": "Chrome",
    "com.facebook.katana": "Facebook",
    "com.instagram.android": "Instagram",
    "com.whatsapp": "WhatsApp",
    "com.spotify.music": "Spotify",
    "com.google.android.deskclock": "Clock",
    "com.google.android.youtube": "YouTube",
  };
  return known[pkg] ?? pkg.split(".").filter((part) => !["com", "android", "google", "app"].includes(part)).pop() ?? pkg;
}

export function parseRunDetail(raw: unknown): RunDetail | null {
  const summary = parseRunSummary(raw);
  if (!summary) return null;
  const r = record(raw);
  const steps = Array.isArray(r.steps) ? r.steps.map(parseStep).filter((step): step is RunStep => step !== null) : [];
  return { ...summary, result: str(r.result), stepsTruncated: r.stepsTruncated === true, steps };
}

function parseStep(raw: unknown): RunStep | null {
  const r = record(raw);
  if (typeof r.index !== "number") return null;
  return {
    index: r.index,
    startedAt: num(r.startedAt),
    endedAt: num(r.endedAt),
    title: str(r.title) || `Step ${r.index + 1}`,
    action: str(r.action) || null,
    pageId: str(r.pageId) || null,
    outcome: OUTCOMES.has(r.outcome as StepOutcome) ? (r.outcome as StepOutcome) : "info",
    verification: str(r.verification) || null,
    recovery: str(r.recovery) || null,
    vision: r.vision === true,
    eventsTruncated: r.eventsTruncated === true,
    events: Array.isArray(r.events)
      ? r.events.map((e) => {
          const q = record(e);
          return {
            at: num(q.at),
            kind: str(q.kind),
            text: str(q.text),
            code: str(q.code) || null,
            ok: typeof q.ok === "boolean" ? q.ok : null,
            detail: str(q.detail) || null,
          };
        })
      : [],
    roomId: match(r.roomId, ROOM),
    roomAfter: match(r.roomAfter, ROOM),
    placeId: match(r.placeId, PLACE),
    appVersion: match(r.appVersion, VERSION),
    decisionSource: r.decisionSource === "map" || r.decisionSource === "model" ? r.decisionSource : null,
    expectedRoomId: match(r.expectedRoomId, ROOM),
  };
}

export function statusLabel(status: RunStatus): string {
  switch (status) {
    case "completed":
      return "Finished";
    case "failed":
      return "Failed";
    case "cancelled":
      return "Stopped";
    case "suspended":
      return "Waiting";
    case "running":
      return "Running";
  }
}

export function statusTone(status: RunStatus): Tone {
  switch (status) {
    case "completed":
      return "success";
    case "failed":
      return "danger";
    case "suspended":
      return "warning";
    case "running":
      return "accent";
    case "cancelled":
      return "neutral";
  }
}

const CAUSE_LABELS: Record<string, string> = {
  "needs-secret": "Login wall",
  gate: "Needs approval",
  "human-took-control": "You took the phone",
  cancelled: "Stopped by you",
  transport: "Phone unavailable",
  timeout: "Out of time",
  unchanged: "Action changed nothing",
  "element-not-found": "Control not found",
  "wrong-room": "Wrong screen",
  "stale-door": "Door no longer works",
  "door-missing": "No known door",
  "verification-failed": "Couldn't prove done",
  "clause-failed": "Clause not completed",
  "model-gave-up": "Model stuck",
  "provider-error": "Model provider failed",
  "text-not-delivered": "Text not entered",
  "tool-failed": "Action failed",
  blocked: "Hard blocker",
  unknown: "Stopped",
};

export function causeLabel(kind: string): string {
  return CAUSE_LABELS[kind] ?? kind.replace(/-/g, " ");
}

/** Causes the developer can fix in Cyclone's knowledge (map, doors) read as warnings; the rest are neutral facts. */
export function causeTone(kind: string): Tone {
  if (["cancelled", "human-took-control", "gate"].includes(kind)) return "neutral";
  if (["provider-error", "transport", "blocked"].includes(kind)) return "danger";
  return "warning";
}

export function outcomeLabel(outcome: StepOutcome): string {
  switch (outcome) {
    case "ok":
      return "Worked";
    case "failed":
      return "Failed";
    case "unverified":
      return "Not verified";
    case "recovered":
      return "Recovered";
    case "info":
      return "Info";
  }
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "—";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
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

export interface RouteSplit {
  placeId: string;
  /** Rooms both runs walked, in order, before they went different ways. */
  shared: string[];
  /** Where the good run went next; null when this run reached every room the good run did. */
  goodNext: string | null;
  /** Where this run went instead; null when it stopped there. */
  thisNext: string | null;
}

/** The latest finished run with the same goal (case and spaces ignored), started before this one. */
export function lastGoodRun(run: RunSummary, runs: RunSummary[]): RunSummary | null {
  const goal = normalizeGoal(run.goal);
  if (!goal) return null;
  return (
    runs
      .filter((other) => other.runId !== run.runId && other.status === "completed" && other.startedAt < run.startedAt && normalizeGoal(other.goal) === goal)
      .sort((a, b) => b.startedAt - a.startedAt)[0] ?? null
  );
}

/** Per app both runs entered: the shared prefix of their room routes and where each went next. */
export function routeSplits(run: RunSummary, good: RunSummary): RouteSplit[] {
  const splits: RouteSplit[] = [];
  for (const place of good.places) {
    const mine = run.places.find((p) => p.placeId === place.placeId)?.route ?? [];
    let i = 0;
    while (i < mine.length && i < place.route.length && mine[i] === place.route[i]) i++;
    splits.push({ placeId: place.placeId, shared: place.route.slice(0, i), goodNext: place.route[i] ?? null, thisNext: mine[i] ?? null });
  }
  return splits;
}

export interface GoalGroup {
  goal: string;
  runs: number;
  finished: number;
  failed: number;
  /** Finished share of the runs that counted (expected runs and still-running ones do not). Null when none counted. */
  successRate: number | null;
  last: RunSummary;
}

/** Runs grouped by sentence (case and spaces ignored), most recently run first. Mapping passes are left out. */
export function groupByGoal(runs: RunSummary[]): GoalGroup[] {
  const groups = new Map<string, RunSummary[]>();
  for (const run of runs) {
    if (run.model === "cyclone-mapper") continue;
    const key = normalizeGoal(run.goal);
    if (!key) continue;
    groups.set(key, [...(groups.get(key) ?? []), run]);
  }
  return [...groups.values()]
    .map((list) => {
      const sorted = [...list].sort((a, b) => b.startedAt - a.startedAt);
      const counted = sorted.filter((run) => run.expected !== true && run.status !== "running" && run.status !== "suspended");
      const finished = counted.filter((run) => run.status === "completed").length;
      return {
        goal: sorted[0]!.goal,
        runs: sorted.length,
        finished,
        failed: counted.filter((run) => run.status === "failed").length,
        successRate: counted.length ? finished / counted.length : null,
        last: sorted[0]!,
      };
    })
    .sort((a, b) => b.last.startedAt - a.last.startedAt);
}

export function normalizeGoal(goal: string): string {
  return goal.trim().toLowerCase().replace(/\s+/g, " ");
}

/** One CSV cell: quoted, with formula-looking text neutralised so spreadsheets never evaluate it. */
export function csvCell(value: string | number | null | undefined): string {
  let text = value === null || value === undefined ? "" : String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

/** The runs in view as CSV for a spreadsheet: one row per run, apps by package. */
export function runsCsv(runs: RunSummary[]): string {
  const header = ["runId", "goal", "status", "cause", "startedAt", "durationSeconds", "steps", "apps", "expected"];
  const lines = runs.map((run) =>
    [
      run.runId,
      run.goal,
      run.status,
      run.cause?.kind ?? "",
      new Date(run.startedAt).toISOString(),
      Math.round(run.durationMs / 1000),
      run.stepCount,
      run.places.map((place) => place.placeId.replace(/^package:/, "")).join(" "),
      run.expected === true ? "yes" : "",
    ]
      .map(csvCell)
      .join(","),
  );
  return [header.map(csvCell).join(","), ...lines].join("\r\n") + "\r\n";
}
