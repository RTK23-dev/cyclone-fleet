/**
 * Level 0 of the Atlas: the app's story at a glance. Scenario lanes across the top (Signed out → Sign in → Signed in),
 * then the signed-in zones: the entry zone on top and one card per base page below it, with the doors between them.
 * Pure SVG in a viewBox, so it scales without measuring the DOM and needs no library.
 */
import type { LaneId, ZoneMap } from "../maps/zones.js";
import { icon, type IconName } from "./icons.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const W = 900;
const CARD_W = 196;
const CARD_H = 106;
const ENTRY_W = 232;
const ENTRY_H = 108;
const PER_ROW = 4;

export interface ZoneOverviewOptions {
  onZone?: (zoneId: string) => void;
  onLane?: (laneId: LaneId) => void;
}

export interface ZoneOverviewHandle {
  element: HTMLElement;
  render(map: ZoneMap, state?: { selectedZoneId?: string | null; activeScreenId?: string | null }): void;
}

const GLYPH_ICON: Record<string, IconName> = {
  home: "home", search: "search", play: "play", chat: "chat", user: "user", gear: "settings", bell: "bell", bag: "bag",
  camera: "camera", layers: "layers",
};

export interface Placed {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Deterministic positions for the zone cards (exported for tests). */
export function layoutZones(map: ZoneMap): { cards: Placed[]; height: number } {
  const cards: Placed[] = [];
  const entry = map.zones.find((z) => z.entry);
  const rest = map.zones.filter((z) => !z.entry);
  const top = 118;
  if (entry) cards.push({ id: entry.id, x: (W - ENTRY_W) / 2, y: top, w: ENTRY_W, h: ENTRY_H });
  const rowsTop = entry ? top + ENTRY_H + 74 : top;
  rest.forEach((zone, i) => {
    const row = Math.floor(i / PER_ROW);
    const inRow = Math.min(PER_ROW, rest.length - row * PER_ROW);
    const gap = 30;
    const span = inRow * CARD_W + (inRow - 1) * gap;
    const col = i % PER_ROW;
    cards.push({ id: zone.id, x: (W - span) / 2 + col * (CARD_W + gap), y: rowsTop + row * (CARD_H + 56), w: CARD_W, h: CARD_H });
  });
  const bottom = cards.reduce((max, c) => Math.max(max, c.y + c.h), top + ENTRY_H);
  return { cards, height: bottom + 30 };
}

export function createZoneOverview(options: ZoneOverviewOptions = {}): ZoneOverviewHandle {
  const element = document.createElement("div");
  element.className = "zone-overview";
  return {
    element,
    render(map, state = {}) {
      element.replaceChildren(draw(map, state, options));
    },
  };
}

function svgEl<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string | number> = {}, className?: string): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG_NS, tag) as SVGElementTagNameMap[K];
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
  if (className) node.setAttribute("class", className);
  return node;
}

function text(x: number, y: number, value: string, className: string, anchor = "start"): SVGTextElement {
  const node = svgEl("text", { x, y, "text-anchor": anchor }, className);
  node.textContent = value;
  return node;
}

function trim(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

function draw(map: ZoneMap, state: { selectedZoneId?: string | null; activeScreenId?: string | null }, options: ZoneOverviewOptions): SVGSVGElement {
  const { cards, height } = layoutZones(map);
  const svg = svgEl("svg", { viewBox: `0 0 ${W} ${height}`, role: "img", "aria-label": "App overview: scenarios and zones" }, "zone-svg");
  const defs = svgEl("defs");
  const marker = svgEl("marker", { id: "zo-arrow", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" });
  marker.append(svgEl("path", { d: "M0 0L10 5L0 10z" }, "zo-arrowhead"));
  defs.append(marker);
  svg.append(defs);

  // Scenario lanes: three pills, left to right, with the count of places known in each.
  const laneW = 214;
  const laneX = [W / 2 - laneW * 1.5 - 50, W / 2 - laneW / 2, W / 2 + laneW / 2 + 50];
  map.lanes.forEach((lane, i) => {
    const x = laneX[i]!;
    const known = lane.screenIds.length > 0;
    const g = svgEl("g", { tabindex: 0, role: "button", "data-lane": lane.id }, `zo-lane${known ? "" : " empty"}${lane.id === "signed-in" ? " signed-in" : ""}`);
    g.append(svgEl("rect", { x, y: 14, width: laneW, height: 54, rx: 27 }, "zo-lane-box"));
    g.append(text(x + laneW / 2, 37, lane.label, "zo-lane-title", "middle"));
    g.append(text(x + laneW / 2, 57, known ? `${lane.screenIds.length} place${lane.screenIds.length === 1 ? "" : "s"}` : "not mapped yet", "zo-lane-sub", "middle"));
    g.addEventListener("click", () => options.onLane?.(lane.id));
    svg.append(g);
    if (i < 2) svg.append(svgEl("path", { d: `M${x + laneW + 8} 41H${laneX[i + 1]! - 8}`, "marker-end": "url(#zo-arrow)" }, "zo-flow"));
  });

  const placed = new Map(cards.map((c) => [c.id, c]));
  const entry = map.zones.find((z) => z.entry);
  const entryCard = entry ? placed.get(entry.id) : undefined;
  if (entryCard) {
    // Signed in → the entry zone.
    const lx = laneX[2]! + laneW / 2;
    svg.append(svgEl("path", { d: `M${lx} 64 C ${lx} 96, ${entryCard.x + entryCard.w / 2} 88, ${entryCard.x + entryCard.w / 2} ${entryCard.y - 4}`, "marker-end": "url(#zo-arrow)" }, "zo-flow"));
  }

  // Doors between zones: from the entry solid; between other zones dashed.
  const drawn = new Set<string>();
  for (const link of map.links) {
    const a = placed.get(link.from);
    const b = placed.get(link.to);
    if (!a || !b) continue;
    const key = [link.from, link.to].sort().join("|");
    if (drawn.has(key)) continue;
    drawn.add(key);
    const both = map.links.some((l) => l.from === link.to && l.to === link.from);
    const fromEntry = link.from === entry?.id || link.to === entry?.id;
    const [x1, y1] = [a.x + a.w / 2, a.y + a.h];
    const [x2, y2] = [b.x + b.w / 2, b.y];
    const down = b.y > a.y + 4;
    const d = down
      ? `M${x1} ${y1} C ${x1} ${y1 + 34}, ${x2} ${y2 - 34}, ${x2} ${y2 - 4}`
      : `M${a.x + a.w} ${a.y + a.h / 2} C ${(a.x + a.w + b.x) / 2} ${a.y + a.h / 2 - 22}, ${(a.x + a.w + b.x) / 2} ${b.y + b.h / 2 - 22}, ${b.x - 4} ${b.y + b.h / 2}`;
    const path = svgEl("path", { d, "marker-end": "url(#zo-arrow)" }, `zo-link${fromEntry ? "" : " cross"}`);
    if (both) path.setAttribute("marker-start", "url(#zo-arrow)");
    svg.append(path);
  }
  // Base pages that are one door from the entry but whose link was not drawn (door recorded one way only).
  if (entryCard) {
    for (const zone of map.zones) {
      if (zone.entry || zone.id === "elsewhere") continue;
      const card = placed.get(zone.id)!;
      if (drawn.has([entry!.id, zone.id].sort().join("|"))) continue;
      svg.append(svgEl("path", {
        d: `M${entryCard.x + entryCard.w / 2} ${entryCard.y + entryCard.h} C ${entryCard.x + entryCard.w / 2} ${entryCard.y + entryCard.h + 34}, ${card.x + card.w / 2} ${card.y - 34}, ${card.x + card.w / 2} ${card.y - 4}`,
        "marker-end": "url(#zo-arrow)",
      }, "zo-link"));
    }
  }

  const activeZone = state.activeScreenId ? map.zoneOf.get(state.activeScreenId) ?? null : null;
  for (const zone of map.zones) {
    const c = placed.get(zone.id)!;
    const classes = ["zo-zone"];
    if (zone.entry) classes.push("entry");
    if (zone.id === "elsewhere") classes.push("elsewhere");
    if (zone.id === state.selectedZoneId) classes.push("selected");
    if (zone.id === activeZone) classes.push("active");
    if (zone.blocked) classes.push("has-blocked");
    const g = svgEl("g", { tabindex: 0, role: "button", "data-zone": zone.id, "aria-label": `${zone.name}: ${zone.screenIds.length} places` }, classes.join(" "));
    g.append(svgEl("rect", { x: c.x, y: c.y, width: c.w, height: c.h, rx: 14 }, "zo-card"));
    const glyph = icon(GLYPH_ICON[zone.glyph] ?? "layers", "zo-glyph");
    glyph.setAttribute("x", String(c.x + 16));
    glyph.setAttribute("y", String(c.y + 16));
    glyph.setAttribute("width", "24");
    glyph.setAttribute("height", "24");
    g.append(glyph);
    g.append(text(c.x + 48, c.y + 34, trim(zone.name, zone.entry ? 19 : 15), "zo-name"));
    g.append(text(c.x + 16, c.y + 58, `${zone.screenIds.length} place${zone.screenIds.length === 1 ? "" : "s"} · ${zone.doors} door${zone.doors === 1 ? "" : "s"}`, "zo-meta"));
    const barW = c.w - 32;
    const confidence = zone.confidence ?? 0;
    g.append(svgEl("rect", { x: c.x + 16, y: c.y + 68, width: barW, height: 6, rx: 3 }, "zo-bar-track"));
    g.append(svgEl("rect", { x: c.x + 16, y: c.y + 68, width: Math.max(2, barW * confidence), height: 6, rx: 3 }, `zo-bar ${confidence >= 0.85 ? "high" : confidence >= 0.6 ? "mid" : "low"}`));
    g.append(text(c.x + 16, c.y + c.h - 12, zone.confidence == null ? "—" : `${Math.round(zone.confidence * 100)}%`, "zo-flags"));
    if (zone.blocked) g.append(text(c.x + c.w - 14, c.y + c.h - 12, `${zone.blocked} blocked`, "zo-flags warn", "end"));
    if (zone.id === activeZone) g.append(svgEl("circle", { cx: c.x + c.w - 16, cy: c.y + 16, r: 5 }, "zo-pulse"));
    g.addEventListener("click", () => options.onZone?.(zone.id));
    g.addEventListener("keydown", (event) => {
      if ((event as KeyboardEvent).key === "Enter" || (event as KeyboardEvent).key === " ") options.onZone?.(zone.id);
    });
    svg.append(g);
  }
  return svg;
}
