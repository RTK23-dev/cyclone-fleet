/**
 * An app's manual (plan 36 §8, §10, §11): `GET /v1/devices/{id}/manual?placeId=&q=`. The phone derives the abilities
 * (what you can do in the app and the path to each) from its dictionary, answers the self-quiz and scores the map;
 * Glass only shows them. The app's own words and structure only: never a chat, a name or a message.
 */
import type { GatewayClient } from "./gateway.js";

export type AbilityKind = "open" | "panel" | "switch" | "offer" | "control" | "find";
export type AbilityEffect = "navigate" | "reveal" | "switch" | "choose" | "asks";

export interface Ability {
  id: string;
  kind: AbilityKind;
  name: string;
  place: string;
  placeName: string | null;
  path: string[];
  tap: string | null;
  pick: string | null;
  effect: AbilityEffect;
  setId: string | null;
  provenance: "mapped" | "walked";
  confidence: number;
  note: string | null;
  say: string[];
}

export interface QuizGoal { goal: string; abilityId: string | null; score: number }
export interface Quiz { at: number; asked: number; answered: number; goals: QuizGoal[] }

export interface ManualScores {
  map: number | null;
  dictionary: number | null;
  quiz: number | null;
  walks: number | null;
  places: number;
  named: number;
  panels: number;
  lists: number;
  ordered: number;
  abilities: number;
  walkedAbilities: number;
}

export interface AppManualView {
  placeId: string;
  appLabel: string;
  currentVersion: string | null;
  abilities: Ability[];
  truncated: boolean;
  query: string | null;
  hits: Array<{ id: string; score: number }>;
  clear: boolean;
  quiz: Quiz | null;
  scores: ManualScores;
  markdown: string;
}

const ABILITY_ID = /^ab:[0-9a-f]{12}$/;
const ROOM = /^screen:[a-z_]{1,20}:[0-9a-f]{16}$/;
const KINDS = new Set<AbilityKind>(["open", "panel", "switch", "offer", "control", "find"]);
const EFFECTS = new Set<AbilityEffect>(["navigate", "reveal", "switch", "choose", "asks"]);

const str = (v: unknown, max: number): string => (typeof v === "string" ? v.slice(0, max) : "");
const strOrNull = (v: unknown, max: number): string | null => (typeof v === "string" && v ? v.slice(0, max) : null);
const strs = (v: unknown, max: number, each = 70): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === "string").map((x) => x.slice(0, each)).slice(0, max) : [];
const int = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);
const share = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) && v >= 0 && v <= 1 ? v : null);

export async function loadManual(client: GatewayClient, deviceId: string, placeId: string, query = "", signal?: AbortSignal): Promise<AppManualView> {
  const q = query.trim().slice(0, 200);
  const path = `/v1/devices/${encodeURIComponent(deviceId)}/manual?placeId=${encodeURIComponent(placeId)}${q ? `&q=${encodeURIComponent(q)}` : ""}`;
  return parseManual(await client.get<unknown>(path, signal));
}

function parseAbility(raw: unknown): Ability | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !ABILITY_ID.test(r.id) || typeof r.place !== "string" || !ROOM.test(r.place)) return null;
  if (!KINDS.has(r.kind as AbilityKind) || !EFFECTS.has(r.effect as AbilityEffect)) return null;
  const name = str(r.name, 120);
  if (!name) return null;
  return {
    id: r.id,
    kind: r.kind as AbilityKind,
    name,
    place: r.place,
    placeName: strOrNull(r.placeName, 60),
    path: strs(r.path, 10, 60),
    tap: strOrNull(r.tap, 60),
    pick: strOrNull(r.pick, 60),
    effect: r.effect as AbilityEffect,
    setId: strOrNull(r.setId, 48),
    provenance: r.provenance === "walked" ? "walked" : "mapped",
    confidence: share(r.confidence) ?? 0,
    note: strOrNull(r.note, 200),
    say: strs(r.say, 8),
  };
}

export function parseManual(body: unknown): AppManualView {
  const r = (body && typeof body === "object" ? body : {}) as Record<string, unknown>;
  const abilities = Array.isArray(r.abilities) ? r.abilities.map(parseAbility).filter((a): a is Ability => a !== null).slice(0, 400) : [];
  const ids = new Set(abilities.map((a) => a.id));
  const s = (r.scores && typeof r.scores === "object" ? r.scores : {}) as Record<string, unknown>;
  const q = r.quiz && typeof r.quiz === "object" ? (r.quiz as Record<string, unknown>) : null;
  return {
    placeId: str(r.placeId, 200),
    appLabel: str(r.appLabel, 80),
    currentVersion: strOrNull(r.currentVersion, 40),
    abilities,
    truncated: r.truncated === true,
    query: strOrNull(r.query, 200),
    hits: Array.isArray(r.hits)
      ? r.hits.flatMap((h) => {
        if (!h || typeof h !== "object") return [];
        const hit = h as Record<string, unknown>;
        return typeof hit.id === "string" && ids.has(hit.id) ? [{ id: hit.id, score: share(hit.score) ?? 0 }] : [];
      }).slice(0, 8)
      : [],
    clear: r.clear === true,
    quiz: q
      ? {
        at: int(q.at),
        asked: int(q.asked),
        answered: int(q.answered),
        goals: Array.isArray(q.goals)
          ? q.goals.flatMap((g) => {
            if (!g || typeof g !== "object") return [];
            const goal = g as Record<string, unknown>;
            const text = str(goal.goal, 90);
            if (!text) return [];
            return [{ goal: text, abilityId: typeof goal.abilityId === "string" && ABILITY_ID.test(goal.abilityId) ? goal.abilityId : null, score: share(goal.score) ?? 0 }];
          }).slice(0, 24)
          : [],
      }
      : null,
    scores: {
      map: share(s.map), dictionary: share(s.dictionary), quiz: share(s.quiz), walks: share(s.walks),
      places: int(s.places), named: int(s.named), panels: int(s.panels), lists: int(s.lists), ordered: int(s.ordered),
      abilities: int(s.abilities), walkedAbilities: int(s.walkedAbilities),
    },
    markdown: str(r.markdown, 60_000),
  };
}

/** "Answers 17 of 20 goals · 3 to explore" (plan 36 §5.5), or null before the first quiz. */
export function quizLine(quiz: Quiz | null): string | null {
  if (!quiz || !quiz.goals.length) return null;
  const gaps = quiz.goals.length - quiz.answered;
  return `Answers ${quiz.answered} of ${quiz.goals.length} goals${gaps ? ` · ${gaps} to explore` : ""}`;
}

/** Plain words for what an ability does once there. */
export function effectLabel(ability: Ability): string {
  switch (ability.effect) {
    case "navigate": return ability.kind === "find" ? "Goes to the list" : "Goes there";
    case "reveal": return "Opens a panel";
    case "switch": return "Switches category";
    case "choose": return "You choose";
    case "asks": return "Asks you first";
  }
}

/** The file name for the exported manual: "Instagram manual.md". */
export function manualFileName(view: AppManualView): string {
  const name = (view.appLabel || view.placeId.replace(/^package:/, "")).replace(/[\\/:*?"<>|]+/g, " ").trim() || "app";
  return `${name} manual.md`;
}
