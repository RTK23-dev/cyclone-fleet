/**
 * Semantic zoom for an app's Atlas (plan 22 §4.2). The phone's map is a flat set of places (rooms) and doors; people
 * think in *base pages* (Home, Search, Reels, DMs, Profile, Settings) and in *scenarios* (signed out → sign in →
 * signed in). This module projects the phone's Atlas into those, deterministically:
 *
 * - scenario lanes from the place's purpose (login / sign-up → Sign in; welcome / onboarding → Signed out);
 * - the entry place (the scenarios' entry, else home, else the best connected place);
 * - base pages = places one door away from the entry; each names a zone;
 * - every other place joins the zone of the base page on its shortest path from the entry; unreachable places go to
 *   "Elsewhere".
 *
 * Glass decides nothing here: no route, no guess about the app; only grouping of what the phone already knows.
 */
import { DARK_CONFIDENCE, type AtlasEdge, type AtlasScreen, type AtlasViewModel } from "./atlasViewModel.js";

export type LaneId = "signed-out" | "sign-in" | "signed-in";

export interface ScenarioLane {
  id: LaneId;
  label: string;
  screenIds: string[];
}

export interface Zone {
  /** `home`, `elsewhere`, or `zone:<base screen id>`. */
  id: string;
  name: string;
  glyph: string;
  entry: boolean;
  baseScreenId: string | null;
  screenIds: string[];
  /** Doors leaving places of this zone. */
  doors: number;
  /** Mean place confidence the phone reports, 0..1; null when unknown. */
  confidence: number | null;
  /** Places or doors below the "dark" confidence line: known, not confirmed. */
  unconfirmed: number;
  /** Places or doors the phone marked dangerous: the mapper refused them. */
  blocked: number;
}

export interface ZoneLink {
  from: string;
  to: string;
  doors: number;
}

export interface ZoneMap {
  entryScreenId: string | null;
  lanes: ScenarioLane[];
  zones: Zone[];
  links: ZoneLink[];
  zoneOf: Map<string, string>;
}

const SIGN_IN_PURPOSE = /(^|[^a-z])(log ?in|sign ?in|signin|login|sign ?up|signup|register|auth|verify|verification|otp|two.?factor)/;
const SIGNED_OUT_PURPOSE = /(^|[^a-z])(welcome|onboard|landing|intro|splash|signed ?out|logged ?out)/;
/** A label only decides the lane when the purpose says nothing (a settings page called "Password and security" stays signed in). */
const WEAK_PURPOSES = new Set(["", "unknown", "other", "detail", "screen", "page"]);
const SIGN_IN_LABEL = /^(log ?in|sign ?in|sign ?up|create (?:an )?account|register)\b/;
const SIGNED_OUT_LABEL = /^(welcome|get started|let'?s get started)\b/;
export const MAX_ZONES = 8;

export function laneOf(screen: Pick<AtlasScreen, "purpose" | "label">): LaneId {
  const purpose = (screen.purpose ?? "").toLowerCase().replace(/[_-]+/g, " ").trim();
  if (SIGN_IN_PURPOSE.test(purpose)) return "sign-in";
  if (SIGNED_OUT_PURPOSE.test(purpose)) return "signed-out";
  if (WEAK_PURPOSES.has(purpose)) {
    const label = (screen.label ?? "").toLowerCase().trim();
    if (SIGN_IN_LABEL.test(label)) return "sign-in";
    if (SIGNED_OUT_LABEL.test(label)) return "signed-out";
  }
  return "signed-in";
}

const GLYPHS: Array<[RegExp, string]> = [
  [/home|feed|main|start/, "home"],
  [/search|explore|discover|find/, "search"],
  [/reel|video|watch|shorts|play|music|stream/, "play"],
  [/message|chat|dm|inbox|mail|direct|conversation/, "chat"],
  [/profile|account|me\b|you\b/, "user"],
  [/setting|preference|option/, "gear"],
  [/notif|activity|alert|bell/, "bell"],
  [/shop|store|cart|market/, "bag"],
  [/camera|photo|gallery|media/, "camera"],
];

export function zoneGlyph(name: string): string {
  const key = name.toLowerCase();
  return GLYPHS.find(([pattern]) => pattern.test(key))?.[1] ?? "layers";
}

function pickEntry(screens: AtlasScreen[], edges: AtlasEdge[], preferred: string | null | undefined): string | null {
  const signedIn = screens.filter((s) => laneOf(s) === "signed-in");
  if (!signedIn.length) return null;
  if (preferred && signedIn.some((s) => s.screenId === preferred)) return preferred;
  const home = signedIn.find((s) => /(^|[^a-z])(home|feed|main)([^a-z]|$)/.test(`${s.purpose} ${s.label}`.toLowerCase()));
  if (home) return home.screenId;
  const out = new Map<string, number>();
  for (const edge of edges) out.set(edge.fromScreenId, (out.get(edge.fromScreenId) ?? 0) + 1);
  return [...signedIn].sort((a, b) => (out.get(b.screenId) ?? 0) - (out.get(a.screenId) ?? 0) || a.screenId.localeCompare(b.screenId))[0]!.screenId;
}

function isUnconfirmed(confidence: number): boolean {
  return !Number.isFinite(confidence) || confidence < DARK_CONFIDENCE;
}

export function deriveZones(model: Pick<AtlasViewModel, "screens" | "edges">, entryHint?: string | null): ZoneMap {
  const screens = model.screens;
  const byId = new Map(screens.map((s) => [s.screenId, s]));
  const lanes: ScenarioLane[] = [
    { id: "signed-out", label: "Signed out", screenIds: [] },
    { id: "sign-in", label: "Sign in", screenIds: [] },
    { id: "signed-in", label: "Signed in", screenIds: [] },
  ];
  for (const screen of screens) lanes.find((l) => l.id === laneOf(screen))!.screenIds.push(screen.screenId);
  const signedIn = new Set(lanes[2]!.screenIds);
  const edges = model.edges.filter((e) => byId.has(e.fromScreenId) && byId.has(e.toScreenId));
  const entry = pickEntry(screens, edges, entryHint);
  const zoneOf = new Map<string, string>();
  const zones: Zone[] = [];

  if (entry) {
    // Breadth-first from the entry over signed-in places, remembering the first hop (the base page) of each path.
    const out = new Map<string, string[]>();
    for (const edge of edges) {
      if (!signedIn.has(edge.fromScreenId) || !signedIn.has(edge.toScreenId) || edge.fromScreenId === edge.toScreenId) continue;
      const list = out.get(edge.fromScreenId) ?? [];
      if (!list.includes(edge.toScreenId)) list.push(edge.toScreenId);
      out.set(edge.fromScreenId, list);
    }
    for (const list of out.values()) list.sort();
    const firstHop = new Map<string, string>();
    const queue: string[] = [entry];
    const seen = new Set([entry]);
    while (queue.length) {
      const at = queue.shift()!;
      for (const next of out.get(at) ?? []) {
        if (seen.has(next)) continue;
        seen.add(next);
        firstHop.set(next, at === entry ? next : firstHop.get(at)!);
        queue.push(next);
      }
    }
    // Base pages ranked by how much hangs under them; the biggest MAX_ZONES - 1 get their own zone.
    const size = new Map<string, number>();
    for (const base of firstHop.values()) size.set(base, (size.get(base) ?? 0) + 1);
    const bases = [...size.keys()].sort((a, b) => (size.get(b)! - size.get(a)!) || labelOf(byId.get(a)!).localeCompare(labelOf(byId.get(b)!)));
    const kept = new Set(bases.slice(0, MAX_ZONES - 1));
    const entryName = labelOf(byId.get(entry)!);
    zones.push(emptyZone("home", entryName, true, entry));
    zoneOf.set(entry, "home");
    for (const base of bases) if (kept.has(base)) zones.push(emptyZone(`zone:${base}`, labelOf(byId.get(base)!), false, base));
    for (const [screenId, base] of firstHop) {
      // Too many base pages: the smallest ones fold into the entry's zone rather than hiding places.
      zoneOf.set(screenId, kept.has(base) ? `zone:${base}` : "home");
    }
  }
  const elsewhere = [...signedIn].filter((id) => !zoneOf.has(id));
  if (elsewhere.length) {
    zones.push(emptyZone("elsewhere", entry ? "Elsewhere" : "Places", false, null));
    for (const id of elsewhere) zoneOf.set(id, "elsewhere");
  }

  const zoneById = new Map(zones.map((z) => [z.id, z]));
  for (const screen of screens) {
    const zone = zoneById.get(zoneOf.get(screen.screenId) ?? "");
    if (!zone) continue;
    zone.screenIds.push(screen.screenId);
    if (isUnconfirmed(screen.confidence)) zone.unconfirmed++;
    if (screen.risk?.danger) zone.blocked++;
  }
  const linkCount = new Map<string, number>();
  for (const edge of edges) {
    const from = zoneOf.get(edge.fromScreenId);
    if (!from) continue;
    const zone = zoneById.get(from)!;
    zone.doors++;
    if (isUnconfirmed(edge.confidence)) zone.unconfirmed++;
    if (edge.risk?.danger) zone.blocked++;
    const to = zoneOf.get(edge.toScreenId);
    if (to && to !== from) linkCount.set(`${from}\u0000${to}`, (linkCount.get(`${from}\u0000${to}`) ?? 0) + 1);
  }
  for (const zone of zones) {
    const values = zone.screenIds.map((id) => byId.get(id)!.confidence).filter((c) => Number.isFinite(c));
    zone.confidence = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
    zone.screenIds.sort((a, b) => (a === zone.baseScreenId ? -1 : b === zone.baseScreenId ? 1 : labelOf(byId.get(a)!).localeCompare(labelOf(byId.get(b)!))));
  }
  const links = [...linkCount].map(([key, doors]) => {
    const [from, to] = key.split("\u0000") as [string, string];
    return { from, to, doors };
  }).sort((a, b) => a.from.localeCompare(b.from) || a.to.localeCompare(b.to));
  return { entryScreenId: entry, lanes, zones, links, zoneOf };
}

function emptyZone(id: string, name: string, entry: boolean, base: string | null): Zone {
  return { id, name, glyph: entry ? "home" : zoneGlyph(name), entry, baseScreenId: base, screenIds: [], doors: 0, confidence: null, unconfirmed: 0, blocked: 0 };
}

function labelOf(screen: Pick<AtlasScreen, "label" | "purpose">): string {
  return (screen.label || screen.purpose || "Screen").trim();
}

/** The part of the map inside one zone, for the zone view: its places and the doors between them. */
export function zoneSubModel<M extends Pick<AtlasViewModel, "screens" | "edges">>(model: M, zones: ZoneMap, zoneId: string): M {
  const ids = new Set(zones.zones.find((z) => z.id === zoneId)?.screenIds ?? []);
  return {
    ...model,
    screens: model.screens.filter((s) => ids.has(s.screenId)),
    edges: model.edges.filter((e) => ids.has(e.fromScreenId) && ids.has(e.toScreenId)),
  };
}

export interface CoverageReport {
  /** Scenario lanes with at least one known place, of 3. */
  scenariosKnown: number;
  zones: number;
  places: number;
  doors: number;
  /** Mean place confidence, 0..1, or null. Confidence, not a share of the app. */
  confidence: number | null;
  unconfirmed: number;
  blocked: number;
  /** Doors learned on the installed version, 0..1, or null when the phone did not say. */
  currentShare: number | null;
  staleDoors: number;
}

export function coverageReport(
  model: Pick<AtlasViewModel, "screens" | "edges">,
  zones: ZoneMap,
  versions?: { staleDoorCount: number } | null,
): CoverageReport {
  const values = model.screens.map((s) => s.confidence).filter((c) => Number.isFinite(c));
  const doors = model.edges.length;
  const stale = versions ? Math.min(doors, Math.max(0, versions.staleDoorCount)) : 0;
  return {
    scenariosKnown: zones.lanes.filter((l) => l.screenIds.length > 0).length,
    zones: zones.zones.length,
    places: model.screens.length,
    doors,
    confidence: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null,
    unconfirmed: model.screens.filter((s) => isUnconfirmed(s.confidence)).length + model.edges.filter((e) => isUnconfirmed(e.confidence)).length,
    blocked: model.screens.filter((s) => s.risk?.danger).length + model.edges.filter((e) => e.risk?.danger).length,
    currentShare: versions && doors ? (doors - stale) / doors : null,
    staleDoors: stale,
  };
}

/** Runs that walked a door: a route containing `from` immediately followed by `to`. */
export function runsThroughDoor<R extends { places: Array<{ route: string[] }> }>(runs: R[], fromScreenId: string, toScreenId: string): R[] {
  return runs.filter((run) => run.places.some((place) => place.route.some((room, i) => room === fromScreenId && place.route[i + 1] === toScreenId)));
}

/**
 * A deterministic layered layout for a sub-map: columns by distance from the root (the zone's base page, else the
 * first place), rows in label order. Used when the phone's stored positions would scatter or pile up a sub-map.
 */
export function layeredLayout<M extends Pick<AtlasViewModel, "screens" | "edges">>(model: M, rootId: string | null, gapX = 250, gapY = 150): M {
  if (!model.screens.length) return model;
  const ids = model.screens.map((s) => s.screenId);
  const root = rootId && ids.includes(rootId) ? rootId : ids[0]!;
  const next = new Map<string, string[]>();
  const add = (a: string, b: string): void => { next.set(a, [...(next.get(a) ?? []), b]); };
  for (const e of model.edges) { add(e.fromScreenId, e.toScreenId); }
  const depth = new Map<string, number>([[root, 0]]);
  const walk = (start: string, base: number): void => {
    const queue = [start];
    depth.set(start, base);
    while (queue.length) {
      const at = queue.shift()!;
      for (const to of next.get(at) ?? []) if (!depth.has(to)) { depth.set(to, depth.get(at)! + 1); queue.push(to); }
    }
  };
  walk(root, 0);
  // Places no door reaches from the root start their own column group to the right.
  let extra = Math.max(...depth.values()) + 1;
  for (const id of ids) if (!depth.has(id)) walk(id, extra++);
  const label = new Map(model.screens.map((s) => [s.screenId, (s.label || s.purpose || "").toLowerCase()]));
  const columns = new Map<number, string[]>();
  for (const id of ids) columns.set(depth.get(id)!, [...(columns.get(depth.get(id)!) ?? []), id]);
  const tallest = Math.max(...[...columns.values()].map((c) => c.length));
  const position = new Map<string, { x: number; y: number }>();
  for (const [col, members] of columns) {
    members.sort((a, b) => (label.get(a)!).localeCompare(label.get(b)!) || a.localeCompare(b));
    const offset = ((tallest - members.length) * gapY) / 2;
    members.forEach((id, row) => position.set(id, { x: col * gapX, y: offset + row * gapY }));
  }
  return { ...model, screens: model.screens.map((s) => ({ ...s, layout: position.get(s.screenId)! })) };
}

/** True when the phone's stored positions cannot draw the map (every card on one spot). */
export function layoutCollapsed(model: Pick<AtlasViewModel, "screens">): boolean {
  if (model.screens.length < 2) return false;
  const first = model.screens[0]!.layout;
  return model.screens.every((s) => s.layout.x === first.x && s.layout.y === first.y);
}
