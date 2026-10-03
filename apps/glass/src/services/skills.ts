/**
 * The owner's saved skills and where each lives on the map (`GET /v1/devices/{id}/skills`, op `skills.list`, plan 23).
 * The phone decides a skill's health from its map; Glass only shows it and draws the saved way.
 */
import type { GatewayClient } from "./gateway.js";

export type SkillGround = "grounded" | "partial" | "needs-recheck" | "not-grounded";

export interface SkillWaypoint {
  title: string;
  /** The Taught map's screen id, when the phone knows the screen; null otherwise. */
  screenId: string | null;
}

export interface SkillView {
  skillId: string;
  name: string;
  placeId: string | null;
  ground: SkillGround;
  detail: string;
  routeMoves: number | null;
  route: SkillWaypoint[];
  finishSteps: number;
  savedAt: number | null;
}

export interface SkillList {
  skills: SkillView[];
  truncated: boolean;
}

const GROUNDS = new Set<SkillGround>(["grounded", "partial", "needs-recheck", "not-grounded"]);
const SKILL_ID = /^you\.[a-f0-9]{12}$/;
const SCREEN_ID = /^(?:page|screen):[A-Za-z0-9._:-]{1,173}$/;

export async function loadSkills(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<SkillList> {
  return parseSkills(await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/skills`, signal));
}

export function parseSkills(body: unknown): SkillList {
  const record = (body && typeof body === "object" ? body : {}) as { skills?: unknown; truncated?: unknown };
  const skills = Array.isArray(record.skills) ? record.skills.map(parseSkill).filter((s): s is SkillView => s !== null) : [];
  return { skills, truncated: record.truncated === true };
}

function parseSkill(raw: unknown): SkillView | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.skillId !== "string" || !SKILL_ID.test(r.skillId)) return null;
  const ground = GROUNDS.has(r.ground as SkillGround) ? (r.ground as SkillGround) : "not-grounded";
  const route = Array.isArray(r.route)
    ? r.route.flatMap((p) => {
        if (!p || typeof p !== "object") return [];
        const q = p as Record<string, unknown>;
        if (typeof q.title !== "string") return [];
        const screenId = typeof q.screenId === "string" && SCREEN_ID.test(q.screenId) ? q.screenId : null;
        return [{ title: q.title.slice(0, 60) || "Screen", screenId }];
      }).slice(0, 12)
    : [];
  return {
    skillId: r.skillId,
    name: typeof r.name === "string" && r.name.trim() ? r.name.trim().slice(0, 80) : "Skill",
    placeId: typeof r.placeId === "string" && r.placeId.startsWith("package:") ? r.placeId : null,
    ground,
    detail: typeof r.detail === "string" ? r.detail.slice(0, 200) : "",
    routeMoves: typeof r.routeMoves === "number" && r.routeMoves >= 0 ? Math.floor(r.routeMoves) : null,
    route,
    finishSteps: typeof r.finishSteps === "number" && r.finishSteps >= 0 ? Math.floor(r.finishSteps) : 0,
    savedAt: typeof r.savedAt === "number" && r.savedAt > 0 ? r.savedAt : null,
  };
}

export const GROUND_LABEL: Record<SkillGround, string> = {
  grounded: "Route known",
  partial: "Destination known",
  "needs-recheck": "Needs re-check",
  "not-grounded": "Not grounded",
};

export const GROUND_TONE: Record<SkillGround, "success" | "accent" | "warning" | "neutral"> = {
  grounded: "success",
  partial: "accent",
  "needs-recheck": "warning",
  "not-grounded": "neutral",
};

/** Skills per app for the fleet: how many, and whether any needs a re-check. */
export function skillsByApp(skills: SkillView[]): Map<string, { count: number; recheck: number }> {
  const out = new Map<string, { count: number; recheck: number }>();
  for (const skill of skills) {
    if (!skill.placeId) continue;
    const entry = out.get(skill.placeId) ?? { count: 0, recheck: 0 };
    entry.count++;
    if (skill.ground === "needs-recheck") entry.recheck++;
    out.set(skill.placeId, entry);
  }
  return out;
}

/** The Taught map's screen ids along a skill's saved way, in order (unknown screens left out). */
export function skillScreens(skill: SkillView): string[] {
  return skill.route.map((point) => point.screenId).filter((id): id is string => id !== null);
}

/** Skills whose saved way passes through (or ends on) this screen. */
export function skillsThrough(skills: SkillView[], screenId: string): SkillView[] {
  return skills.filter((skill) => skill.route.some((point) => point.screenId === screenId));
}
