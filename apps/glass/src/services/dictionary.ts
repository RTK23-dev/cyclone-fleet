/**
 * An app's dictionary (plan 36 §7): `GET /v1/devices/{id}/dictionary?placeId=`, the owner's edits
 * (`POST …/dictionary/edit`) and the phone's models for the mapping picker (`GET …/models`).
 * The phone keeps the dictionary and applies the organizer's rules; Glass only shows it and sends the owner's choices.
 * Structure only: set names in the app's own words, where they live, ids and counts. Never members or content.
 */
import type { GatewayClient } from "./gateway.js";
import type { AtlasViewModel } from "../maps/atlasViewModel.js";

export type SetStatus = "candidate" | "confirmed" | "locked" | "rejected" | "merged" | "retired";
export type DictAction = "confirm" | "reject" | "lock" | "unlock" | "rename" | "merge" | "unmerge" | "move" | "kind" | "app_word" | "mine";

/** A place of the map named in the app's own words (alpha.60). */
export interface ManualScreen {
  roomKey: string;
  name: string | null;
  title: string | null;
  category: string | null;
  via: string | null;
  panelOf: string | null;
  items: string[];
  sets: string[];
  seen: number;
  /** One line on what the place is for, written by the describer from the app's words (alpha.64). */
  purpose: string | null;
}

export interface ManualDoor { edgeId: string; from: string; to: string; label: string | null; kind: string }

/** A downloaded name waiting for the owner's "app word or yours?" (held in memory on the phone). */
export interface ReviewItem { id: string; name: string; screenTitle: string | null; siblings: string[]; seenAt: number }

export interface DictAnchor {
  kind: "list" | "view";
  screenTitle: string | null;
  position: number | null;
  siblings: string[];
  groups: string[];
  rowShape: string | null;
  searchable: boolean;
  searchLabel: string | null;
}

export interface DictSet {
  id: string;
  kind: string;
  name: string;
  shownName: string;
  nameProof: "lexicon" | "vocabulary";
  aliases: string[];
  parentId: string | null;
  path: string;
  status: SetStatus;
  redirectTo: string | null;
  anchors: DictAnchor[];
  markers: string[];
  observations: number;
  days: number;
  versions: string[];
  missedPasses: number;
  note: string | null;
  failedGates: string[];
  waiting: string | null;
}

export interface DictAudit { at: number; action: string; entryId: string; detail: string; by: string }

export interface DictHealth {
  orphans: string[];
  nearDuplicates: Array<[string, string]>;
  tooDeep: string[];
  tooWide: string[];
  staleCandidates: string[];
  notSeenInVersion: string[];
}

export interface AppDictionaryView {
  placeId: string;
  appLabel: string;
  currentVersion: string | null;
  passes: number;
  updatedAt: number;
  coreKinds: Array<{ wire: string; label: string }>;
  sets: DictSet[];
  truncated: boolean;
  audit: DictAudit[];
  health: DictHealth;
  jev: { summary: string; answered: number; agreed: number };
  glossary: string;
  screens: ManualScreen[];
  doors: ManualDoor[];
  review: ReviewItem[];
}

export interface PhoneModel { id: string; label: string; vision: boolean }
export interface PhoneModels { active: PhoneModel | null; models: PhoneModel[] }

const SET_ID = /^set:[\p{L}\p{N}_]{1,40}$/u;
const STATUSES = new Set<SetStatus>(["candidate", "confirmed", "locked", "rejected", "merged", "retired"]);

const str = (v: unknown, max: number): string => (typeof v === "string" ? v.slice(0, max) : "");
const strOrNull = (v: unknown, max: number): string | null => (typeof v === "string" && v ? v.slice(0, max) : null);
const strs = (v: unknown, max: number, each = 60): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === "string").map((x) => x.slice(0, each)).slice(0, max) : [];
const int = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);
const setId = (v: unknown): string | null => (typeof v === "string" && SET_ID.test(v) ? v : null);

function base(client: GatewayClient, deviceId: string): string {
  void client;
  return `/v1/devices/${encodeURIComponent(deviceId)}`;
}

export async function loadDictionary(client: GatewayClient, deviceId: string, placeId: string, signal?: AbortSignal): Promise<AppDictionaryView> {
  return parseDictionary(await client.get<unknown>(`${base(client, deviceId)}/dictionary?placeId=${encodeURIComponent(placeId)}`, signal));
}

export async function editDictionary(
  client: GatewayClient,
  deviceId: string,
  placeId: string,
  action: DictAction,
  id: string,
  extra: { into?: string; label?: string; parentId?: string | null; kind?: string } = {},
): Promise<AppDictionaryView> {
  const body: Record<string, unknown> = { placeId, action, id };
  if (extra.into !== undefined) body.into = extra.into;
  if (extra.label !== undefined) body.label = extra.label;
  if (extra.parentId !== undefined) body.parentId = extra.parentId;
  if (extra.kind !== undefined) body.kind = extra.kind;
  return parseDictionary(await client.post<unknown>(`${base(client, deviceId)}/dictionary/edit`, body));
}

export async function loadModels(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<PhoneModels> {
  return parseModels(await client.get<unknown>(`${base(client, deviceId)}/models`, signal));
}

export function parseModels(body: unknown): PhoneModels {
  const r = (body && typeof body === "object" ? body : {}) as Record<string, unknown>;
  const model = (raw: unknown): PhoneModel | null => {
    if (!raw || typeof raw !== "object") return null;
    const m = raw as Record<string, unknown>;
    if (typeof m.id !== "string" || !/^[A-Za-z0-9._:/~-]{1,200}$/.test(m.id)) return null;
    return { id: m.id, label: str(m.label, 80) || m.id, vision: m.vision === true };
  };
  return {
    active: model(r.active),
    models: Array.isArray(r.models) ? r.models.map(model).filter((m): m is PhoneModel => m !== null).slice(0, 40) : [],
  };
}

function parseAnchor(raw: unknown): DictAnchor | null {
  if (!raw || typeof raw !== "object") return null;
  const a = raw as Record<string, unknown>;
  if (a.kind !== "list" && a.kind !== "view") return null;
  return {
    kind: a.kind,
    screenTitle: strOrNull(a.screenTitle, 60),
    position: typeof a.position === "number" ? int(a.position) : null,
    siblings: strs(a.siblings, 11),
    groups: strs(a.groups, 12),
    rowShape: strOrNull(a.rowShape, 40),
    searchable: a.searchable === true,
    searchLabel: strOrNull(a.searchLabel, 60),
  };
}

function parseSet(raw: unknown): DictSet | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const id = setId(r.id);
  if (!id || !STATUSES.has(r.status as SetStatus)) return null;
  const name = str(r.name, 60);
  if (!name) return null;
  return {
    id,
    kind: str(r.kind, 20) || "other",
    name,
    shownName: str(r.shownName, 60) || name,
    nameProof: r.nameProof === "vocabulary" ? "vocabulary" : "lexicon",
    aliases: strs(r.aliases, 8),
    parentId: setId(r.parentId),
    path: str(r.path, 200) || name,
    status: r.status as SetStatus,
    redirectTo: setId(r.redirectTo),
    anchors: Array.isArray(r.anchors) ? r.anchors.map(parseAnchor).filter((a): a is DictAnchor => a !== null).slice(0, 6) : [],
    markers: strs(r.markers, 6),
    observations: int(r.observations),
    days: int(r.days),
    versions: strs(r.versions, 8, 40),
    missedPasses: int(r.missedPasses),
    note: strOrNull(r.note, 160),
    failedGates: strs(r.failedGates, 8, 20),
    waiting: strOrNull(r.waiting, 200),
  };
}

export function parseDictionary(body: unknown): AppDictionaryView {
  const r = (body && typeof body === "object" ? body : {}) as Record<string, unknown>;
  const health = (r.health && typeof r.health === "object" ? r.health : {}) as Record<string, unknown>;
  const jev = (r.jev && typeof r.jev === "object" ? r.jev : {}) as Record<string, unknown>;
  const ids = (v: unknown): string[] => (Array.isArray(v) ? v.map(setId).filter((x): x is string => x !== null).slice(0, 50) : []);
  return {
    placeId: str(r.placeId, 200),
    appLabel: str(r.appLabel, 80),
    currentVersion: strOrNull(r.currentVersion, 40),
    passes: int(r.passes),
    updatedAt: int(r.updatedAt),
    coreKinds: Array.isArray(r.coreKinds)
      ? r.coreKinds.flatMap((k) => (k && typeof k === "object" && typeof (k as { wire?: unknown }).wire === "string"
        ? [{ wire: str((k as { wire: string }).wire, 20), label: str((k as { label?: unknown }).label, 40) || (k as { wire: string }).wire }] : []))
      : [],
    sets: Array.isArray(r.entries) ? r.entries.map(parseSet).filter((s): s is DictSet => s !== null) : [],
    truncated: r.truncated === true,
    audit: Array.isArray(r.audit)
      ? r.audit.flatMap((e) => {
        if (!e || typeof e !== "object") return [];
        const a = e as Record<string, unknown>;
        return [{ at: int(a.at), action: str(a.action, 24), entryId: str(a.entryId, 48), detail: str(a.detail, 160), by: str(a.by, 60) }];
      }).slice(0, 50)
      : [],
    health: {
      orphans: ids(health.orphans),
      nearDuplicates: Array.isArray(health.nearDuplicates)
        ? health.nearDuplicates.flatMap((p) => {
          if (!Array.isArray(p) || p.length !== 2) return [];
          const a = setId(p[0]);
          const b = setId(p[1]);
          return a && b ? [[a, b] as [string, string]] : [];
        }).slice(0, 20)
        : [],
      tooDeep: ids(health.tooDeep),
      tooWide: ids(health.tooWide),
      staleCandidates: ids(health.staleCandidates),
      notSeenInVersion: ids(health.notSeenInVersion),
    },
    jev: { summary: str(jev.summary, 160) || "No decisions yet.", answered: int(jev.answered), agreed: int(jev.agreed) },
    glossary: str(r.glossary, 6000),
    screens: Array.isArray(r.screens) ? r.screens.map(parseScreen).filter((x): x is ManualScreen => x !== null).slice(0, 300) : [],
    doors: Array.isArray(r.doors) ? r.doors.map(parseDoor).filter((x): x is ManualDoor => x !== null).slice(0, 600) : [],
    review: Array.isArray(r.review) ? r.review.map(parseReview).filter((x): x is ReviewItem => x !== null).slice(0, 20) : [],
  };
}

const ROOM = /^screen:[a-z_]{1,20}:[0-9a-f]{16}$/;
const EDGE = /^edge:[0-9a-f]{16,64}$/;
const REVIEW_ID = /^rv:[0-9a-f]{16}$/;
const room = (v: unknown): string | null => (typeof v === "string" && ROOM.test(v) ? v : null);

function parseScreen(raw: unknown): ManualScreen | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const roomKey = room(r.roomKey);
  if (!roomKey) return null;
  return {
    roomKey,
    name: strOrNull(r.name, 60),
    title: strOrNull(r.title, 60),
    category: strOrNull(r.category, 60),
    via: strOrNull(r.via, 60),
    panelOf: room(r.panelOf),
    items: strs(r.items, 12),
    sets: Array.isArray(r.sets) ? r.sets.map(setId).filter((x): x is string => x !== null).slice(0, 12) : [],
    seen: int(r.seen),
    purpose: strOrNull(r.purpose, 120),
  };
}

function parseDoor(raw: unknown): ManualDoor | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const from = room(r.from);
  const to = room(r.to);
  if (typeof r.edgeId !== "string" || !EDGE.test(r.edgeId) || !from || !to) return null;
  return { edgeId: r.edgeId, from, to, label: strOrNull(r.label, 60), kind: str(r.kind, 24) || "navigate" };
}

function parseReview(raw: unknown): ReviewItem | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !REVIEW_ID.test(r.id)) return null;
  const name = str(r.name, 60);
  if (!name) return null;
  return { id: r.id, name, screenTitle: strOrNull(r.screenTitle, 60), siblings: strs(r.siblings, 11), seenAt: int(r.seenAt) };
}

/**
 * Puts the App Manual's names on the map (alpha.60): a place with a name in the app's words shows it instead of
 * "Screen", a panel says what opens it, and a walked door shows the words on it. Places without a name are unchanged.
 */
export function applyManualNames(model: AtlasViewModel, manual: AppDictionaryView | null): AtlasViewModel {
  if (!manual || (!manual.screens.length && !manual.doors.length)) return model;
  const cards = new Map(manual.screens.map((c) => [c.roomKey, c]));
  const doors = new Map(manual.doors.map((d) => [d.edgeId, d]));
  const nameOf = (id: string): string | null => cards.get(id)?.name ?? null;
  return {
    ...model,
    screens: model.screens.map((screen) => {
      const card = cards.get(screen.screenId);
      if (!card?.name) return screen;
      const over = card.panelOf ? nameOf(card.panelOf) : null;
      const purpose = card.panelOf ? (over ? `Panel over ${over}` : "Panel") : card.purpose ?? (card.category ? `${card.category} selected` : screen.purpose);
      return { ...screen, label: card.name, purpose, landmarks: card.items.length ? card.items.slice(0, 3) : screen.landmarks };
    }),
    edges: model.edges.map((edge) => {
      const label = doors.get(edge.edgeId)?.label;
      return label ? { ...edge, actionHint: label } : edge;
    }),
  };
}

/** Plain words for a set's status. */
export function statusLabel(set: DictSet, byId: Map<string, DictSet>): string {
  switch (set.status) {
    case "candidate": return "Waiting";
    case "confirmed": return "Confirmed";
    case "locked": return "Locked";
    case "rejected": return "Rejected";
    case "retired": return "Gone from the app";
    case "merged": return `Same as ${set.redirectTo ? byId.get(set.redirectTo)?.shownName ?? set.redirectTo : "another set"}`;
  }
}

/** Where a set lives, in one line: "a category on “Messages” · find one: “Search” · rows show “Remove”". */
export function whereLine(set: DictSet): string {
  const parts: string[] = [];
  const view = set.anchors.find((a) => a.kind === "view");
  const list = set.anchors.find((a) => a.kind === "list");
  if (view) parts.push(`a category${view.screenTitle ? ` on “${view.screenTitle}”` : ""}${view.siblings.length ? ` next to ${view.siblings.map((s) => `“${s}”`).join(", ")}` : ""}`);
  if (list) parts.push(`a list${!view && list.screenTitle ? ` on “${list.screenTitle}”` : ""}${list.rowShape ? ` (rows: ${list.rowShape})` : ""}`);
  const search = set.anchors.find((a) => a.searchable);
  if (search) parts.push(`find one: ${search.searchLabel ? `“${search.searchLabel}”` : "search"}`);
  const groups = list?.groups ?? [];
  if (groups.length) parts.push(`grouped ${groups.join(" / ")}`);
  if (set.markers.length) parts.push(`rows show ${set.markers.map((m) => `“${m}”`).join(", ")}`);
  return parts.join(" · ");
}

const GATE_WORDS: Record<string, string> = {
  named_by_app: "not named by the app",
  anchored: "no place on screen yet",
  seen_twice: "seen once so far",
  depth: "would be too deep",
  fan_out: "too many next to it",
  kind_known: "kind not clear yet",
  distinct: "close to an existing set",
};

/** Why a waiting set waits, in plain words. */
export function waitingLine(set: DictSet): string {
  if (set.status !== "candidate") return "";
  const reason = set.waiting?.replace(/^(wait|ask|confirm): /, "") ?? "";
  if (reason) return reason;
  return set.failedGates.map((g) => GATE_WORDS[g] ?? g).join(", ");
}
