/**
 * Multi-phone command (the alpha.59 fleet patch, ported to alpha.95): one sentence becomes one Command Center task per
 * named phone, tracked as one mission. The gateway splits the sentence (deterministically, no model) and the Command
 * Center hands each goal to that phone's own Mind. Glass shows the answer and sends the owner's choices.
 *
 * Nothing here answers an approval: a phone that needs the owner shows what it is asking, and the owner answers on the
 * Approvals card (or the phone). Approving stays a person's act.
 */
import type { GatewayClient } from "./gateway.js";

export type PhoneState = "QUEUED" | "WAITING" | "RUNNING" | "NEEDS_YOU" | "COMPLETED" | "FAILED" | "CANCELLED";
export type MissionStatus = PhoneState | "PARTIAL_FAILURE";
const PHONE_STATES: PhoneState[] = ["QUEUED", "WAITING", "RUNNING", "NEEDS_YOU", "COMPLETED", "FAILED", "CANCELLED"];
const MISSION_STATES: MissionStatus[] = [...PHONE_STATES, "PARTIAL_FAILURE"];
const TERMINAL: MissionStatus[] = ["COMPLETED", "FAILED", "CANCELLED", "PARTIAL_FAILURE"];

export interface FleetApproval {
  id: string;
  kind: string;
  text: string;
  gate: string | null;
  send: { text: string; recipient: string; app: string } | null;
  /** The Command Center can take the owner's answer to this one from Glass (not secret input, not a hand-over). */
  answerHere: boolean;
  approvableHere: boolean;
  choices: string[];
}

export interface MissionPhone {
  deviceId: string;
  label: string;
  goal: string;
  taskId: string | null;
  state: PhoneState;
  cause: string;
  summary: string;
  turns: number;
  /** One honest line on why a queued/waiting phone has not started ("This phone is sleeping…"). */
  hint: string;
  approval: FleetApproval | null;
}

export interface Mission {
  missionId: string;
  command: string;
  createdAt: number;
  notes: string[];
  status: MissionStatus;
  counts: Record<string, number>;
  phones: MissionPhone[];
}

export interface PlanAssignment {
  deviceId: string;
  label: string;
  goal: string;
}

export interface FleetPlan {
  assignments: PlanAssignment[];
  clarification: string | null;
  needsConfirmation: boolean;
  confidence: string;
  kind: string;
  notes: string[];
}

export interface CommandResult {
  dispatched: boolean;
  /** Preflight reasons to look twice (low battery, permission off, phone already busy). Shown before the owner confirms. */
  warnings: string[];
  needsConfirmation: boolean;
  clarification: string | null;
  plan: FleetPlan | null;
  mission: Mission | null;
}

export interface PhoneTask {
  taskId: string;
  title: string;
  status: PhoneState;
  needsYou: boolean;
  approvalText?: string;
}

export interface FleetPhone {
  deviceId: string;
  label: string;
  nickname: string | null;
  model: string | null;
  /** The gateway's own state word (ready, sleeping, attention, unpaired…), kept for tone; show `stateLabel`. */
  state: string;
  stateLabel: string;
  paired: boolean;
  source: string;
  lastSeenMs: number | null;
  /** Can a sentence address this phone right now (paired)? An unpaired phone is listed but never targeted. */
  addressable: boolean;
  tasks: PhoneTask[];
  health?: { batteryPercent?: number | null; charging?: boolean; network?: string; freeStorageMb?: number | null } | null;
}

export interface FleetOverview {
  phones: FleetPhone[];
  counts: { phones: number; ready: number; busy: number; needYou: number };
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const optStr = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown, fallback = 0): number => (typeof v === "number" && Number.isFinite(v) ? v : fallback);

export function parseApproval(raw: unknown): FleetApproval | null {
  const r = obj(raw);
  const id = str(r.id);
  if (!id) return null;
  const send = obj(r.send);
  return {
    id,
    kind: str(r.kind, "question"),
    text: str(r.text),
    gate: optStr(r.gate),
    send: optStr(send.text) || optStr(send.recipient) ? { text: str(send.text), recipient: str(send.recipient), app: str(send.app) } : null,
    answerHere: r.answerHere === true,
    approvableHere: r.approvableHere === true,
    choices: list(r.choices).filter((c): c is string => typeof c === "string"),
  };
}

export function parseMissionPhone(raw: unknown): MissionPhone {
  const r = obj(raw);
  return {
    deviceId: str(r.deviceId),
    label: str(r.label, str(r.deviceId)),
    goal: str(r.goal),
    taskId: optStr(r.taskId),
    state: PHONE_STATES.includes(r.state as PhoneState) ? (r.state as PhoneState) : "FAILED",
    cause: str(r.cause),
    summary: str(r.summary),
    turns: num(r.turns),
    hint: str(r.hint),
    approval: parseApproval(r.approval),
  };
}

export function parseMission(raw: unknown): Mission | null {
  const r = obj(raw);
  const missionId = str(r.missionId);
  if (!missionId) return null;
  const counts: Record<string, number> = {};
  for (const [key, value] of Object.entries(obj(r.counts))) counts[key] = num(value);
  return {
    missionId,
    command: str(r.command),
    createdAt: num(r.createdAt),
    notes: list(r.notes).filter((n): n is string => typeof n === "string"),
    status: MISSION_STATES.includes(r.status as MissionStatus) ? (r.status as MissionStatus) : "FAILED",
    counts,
    phones: list(r.phones).map(parseMissionPhone),
  };
}

export function parsePlan(raw: unknown): FleetPlan | null {
  const r = obj(raw);
  if (!Array.isArray(r.assignments)) return null;
  return {
    assignments: list(r.assignments).map((a) => ({ deviceId: str(obj(a).deviceId), label: str(obj(a).label), goal: str(obj(a).goal) })),
    clarification: optStr(r.clarification),
    needsConfirmation: r.needsConfirmation === true,
    confidence: str(r.confidence, "high"),
    kind: str(r.kind, "named"),
    notes: list(r.notes).filter((n): n is string => typeof n === "string"),
  };
}

export function parseCommandResult(raw: unknown): CommandResult {
  const r = obj(raw);
  return {
    dispatched: r.dispatched === true,
    warnings: list(r.warnings).filter((w): w is string => typeof w === "string"),
    needsConfirmation: r.needsConfirmation === true,
    clarification: optStr(r.clarification),
    plan: parsePlan(r.plan),
    mission: parseMission(r.mission),
  };
}

export function parseOverview(raw: unknown): FleetOverview {
  const r = obj(raw);
  const phones = list(r.phones).flatMap((item): FleetPhone[] => {
    const p = obj(item);
    const deviceId = str(p.deviceId);
    if (!deviceId) return [];
    return [{
      deviceId,
      label: str(p.label, deviceId),
      nickname: optStr(p.nickname),
      model: optStr(p.model),
      state: str(p.state, "unknown"),
      stateLabel: str(p.stateLabel, str(p.state, "Unknown")),
      paired: p.paired === true,
      source: str(p.source, "USB"),
      lastSeenMs: typeof p.lastSeenMs === "number" ? p.lastSeenMs : null,
      addressable: p.addressable === true,
      health: obj(p.health).batteryPercent == null && !p.health ? null : {
        batteryPercent: typeof obj(p.health).batteryPercent === "number" ? obj(p.health).batteryPercent as number : null,
        charging: obj(p.health).charging === true,
        network: str(obj(p.health).network),
        freeStorageMb: typeof obj(p.health).freeStorageMb === "number" ? obj(p.health).freeStorageMb as number : null,
      },
      tasks: list(p.tasks).map((t) => ({
        taskId: str(obj(t).taskId),
        title: str(obj(t).title),
        status: PHONE_STATES.includes(obj(t).status as PhoneState) ? (obj(t).status as PhoneState) : "RUNNING",
        needsYou: obj(t).needsYou === true,
        approvalText: str(obj(obj(t).approval).text),
      })),
    }];
  });
  const c = obj(r.counts);
  return { phones, counts: { phones: num(c.phones, phones.length), ready: num(c.ready), busy: num(c.busy), needYou: num(c.needYou) } };
}

export function isOpen(status: MissionStatus): boolean {
  return !TERMINAL.includes(status);
}

export function stateLabel(state: MissionStatus): string {
  return {
    QUEUED: "Queued", WAITING: "Waiting for the phone", RUNNING: "Working", NEEDS_YOU: "Needs you", COMPLETED: "Done",
    FAILED: "Failed", CANCELLED: "Stopped", PARTIAL_FAILURE: "Some failed",
  }[state];
}

export function stateTone(state: MissionStatus): "neutral" | "accent" | "success" | "warning" | "danger" {
  return ({
    QUEUED: "neutral", WAITING: "warning", RUNNING: "accent", NEEDS_YOU: "warning", COMPLETED: "success",
    FAILED: "danger", CANCELLED: "neutral", PARTIAL_FAILURE: "warning",
  } as const)[state];
}

/** A phone state word from the gateway ("ready", "sleeping", "attention"…) as a chip tone. */
export function phoneTone(phone: Pick<FleetPhone, "state" | "paired">): "neutral" | "accent" | "success" | "warning" | "danger" {
  if (!phone.paired) return "neutral";
  if (phone.state === "ready") return "success";
  if (phone.state === "attention" || phone.state === "unauthorized" || phone.state === "disconnected") return "danger";
  return "warning";
}

/** An idempotency key for one submit of the command box, so a double click can never start two missions. */
export function newRequestId(): string {
  const random = globalThis.crypto?.randomUUID?.();
  if (random) return random;
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export interface CommandInput {
  command: string;
  confirm?: boolean;
  requestId?: string;
  /** Restrict to a Workspace group's phones. */
  groupId?: string;
}

export const fleetApi = {
  overview: async (client: GatewayClient): Promise<FleetOverview> => parseOverview(await client.get("/v1/fleet/overview")),
  missions: async (client: GatewayClient, limit = 20): Promise<Mission[]> =>
    list(obj(await client.get(`/v1/fleet/missions?limit=${limit}`)).missions).map(parseMission).filter((m): m is Mission => m !== null),
  command: async (client: GatewayClient, input: CommandInput): Promise<CommandResult> =>
    parseCommandResult(await client.post("/v1/fleet/command", input)),
  cancel: async (client: GatewayClient, missionId: string): Promise<Mission | null> =>
    parseMission(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/cancel`)),
  retry: async (client: GatewayClient, missionId: string): Promise<number> =>
    num(obj(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/retry`)).retried),
  handoff: async (client: GatewayClient, missionId: string, deviceId: string, goal: string): Promise<Mission | null> =>
    parseMission(obj(await client.post(`/v1/fleet/missions/${encodeURIComponent(missionId)}/handoff`, { deviceId, goal })).mission),
  stopFleetMissions: async (client: GatewayClient): Promise<number> =>
    num(obj(await client.post("/v1/fleet/stop-fleet-missions")).stopped),
  stopAll: async (client: GatewayClient): Promise<number> => num(obj(await client.post("/v1/fleet/stop-all")).stopped),
  nickname: async (client: GatewayClient, deviceId: string, nickname: string): Promise<void> => {
    await client.post(`/v1/fleet/phones/${encodeURIComponent(deviceId)}/nickname`, { nickname });
  },
};
