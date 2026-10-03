import type { AtlasScreen, AtlasViewModel } from "../maps/atlasViewModel.js";
import { cardGlyphs } from "../maps/atlasViewModel.js";
import { el } from "./dom.js";

export const CARD_WIDTH = 200;
export const CARD_HEIGHT = 122;
export const MIN_SCALE = 0.28;
export const MAX_SCALE = 2.4;
export const FIT_PADDING = 72;
export const DEFAULT_VIEWPORT = { width: 960, height: 640 };
export const EMPTY_START_LABEL = "Start mapping";
export const EMPTY_START_HINT = "Start mapping from the app header.";

const SVG_NS = "http://www.w3.org/2000/svg";

export interface CanvasTransform {
  x: number;
  y: number;
  scale: number;
}

export interface ViewportSize {
  width: number;
  height: number;
}

export interface AppMapCanvasOptions {
  onSelectScreen?: (screenId: string | null) => void;
  onSelectEdge?: (edgeId: string | null) => void;
}

export interface AppMapCanvasHandle {
  element: HTMLElement;
  destroy(): void;
  setViewModel(model: AtlasViewModel, options?: { fit?: boolean }): void;
  setSelectedScreenId(screenId: string | null): void;
  setSelectedEdgeId(edgeId: string | null): void;
  /** The room the phone is standing in during a mapping pass (pulses). Null clears it. */
  setCursorScreenId(screenId: string | null): void;
  /** Rooms a run walked through, in order (run inspector v2). Rooms and the doors between them light up. */
  setRoute(screenIds: string[]): void;
  fitAll(): CanvasTransform;
  getTransform(): CanvasTransform;
  getSelectedScreenId(): string | null;
  getSelectedEdgeId(): string | null;
}

export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return 1;
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

export function sanitizeTransform(transform: CanvasTransform): CanvasTransform {
  return {
    x: Number.isFinite(transform.x) ? transform.x : 0,
    y: Number.isFinite(transform.y) ? transform.y : 0,
    scale: clampScale(transform.scale),
  };
}

export function isFiniteTransform(transform: CanvasTransform): boolean {
  return Number.isFinite(transform.x) && Number.isFinite(transform.y) && Number.isFinite(transform.scale) && transform.scale > 0;
}

export function panBy(transform: CanvasTransform, dx: number, dy: number): CanvasTransform {
  const safeDx = Number.isFinite(dx) ? dx : 0;
  const safeDy = Number.isFinite(dy) ? dy : 0;
  return sanitizeTransform({ x: transform.x + safeDx, y: transform.y + safeDy, scale: transform.scale });
}

export function zoomToward(
  transform: CanvasTransform,
  factor: number,
  pivot: { x: number; y: number },
): CanvasTransform {
  const current = sanitizeTransform(transform);
  const nextScale = clampScale(current.scale * (Number.isFinite(factor) && factor > 0 ? factor : 1));
  const px = Number.isFinite(pivot.x) ? pivot.x : 0;
  const py = Number.isFinite(pivot.y) ? pivot.y : 0;
  const worldX = (px - current.x) / current.scale;
  const worldY = (py - current.y) / current.scale;
  return sanitizeTransform({
    x: px - worldX * nextScale,
    y: py - worldY * nextScale,
    scale: nextScale,
  });
}

export function computeFitTransform(
  screens: Array<{ layout: { x: number; y: number } }>,
  viewport: ViewportSize = DEFAULT_VIEWPORT,
): CanvasTransform {
  const vw = Number.isFinite(viewport.width) && viewport.width > 1 ? viewport.width : DEFAULT_VIEWPORT.width;
  const vh = Number.isFinite(viewport.height) && viewport.height > 1 ? viewport.height : DEFAULT_VIEWPORT.height;
  if (!screens.length) {
    return sanitizeTransform({ x: vw / 2, y: vh / 2, scale: 1 });
  }
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const screen of screens) {
    const x = Number.isFinite(screen.layout?.x) ? screen.layout.x : 0;
    const y = Number.isFinite(screen.layout?.y) ? screen.layout.y : 0;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x + CARD_WIDTH);
    maxY = Math.max(maxY, y + CARD_HEIGHT);
  }
  if (![minX, minY, maxX, maxY].every(Number.isFinite)) {
    return sanitizeTransform({ x: vw / 2, y: vh / 2, scale: 1 });
  }
  const boundsW = Math.max(1, maxX - minX);
  const boundsH = Math.max(1, maxY - minY);
  const scale = clampScale(Math.min((vw - FIT_PADDING * 2) / boundsW, (vh - FIT_PADDING * 2) / boundsH));
  return sanitizeTransform({
    x: (vw - boundsW * scale) / 2 - minX * scale,
    y: (vh - boundsH * scale) / 2 - minY * scale,
    scale,
  });
}

export function edgePath(x1: number, y1: number, x2: number, y2: number): string {
  if (![x1, y1, x2, y2].every(Number.isFinite)) return "M 0 0";
  const dx = x2 - x1;
  const dy = y2 - y1;
  const cx1 = x1 + dx * 0.42;
  const cy1 = y1 + dy * 0.05;
  const cx2 = x1 + dx * 0.58;
  const cy2 = y2 - dy * 0.05;
  return `M ${x1} ${y1} C ${cx1} ${cy1}, ${cx2} ${cy2}, ${x2} ${y2}`;
}

export interface RegionCluster {
  region: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export function clusterRegions(screens: AtlasScreen[]): RegionCluster[] {
  const groups = new Map<string, AtlasScreen[]>();
  for (const screen of screens) {
    const list = groups.get(screen.region) ?? [];
    list.push(screen);
    groups.set(screen.region, list);
  }
  const clusters: RegionCluster[] = [];
  for (const [region, members] of groups) {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const screen of members) {
      minX = Math.min(minX, screen.layout.x);
      minY = Math.min(minY, screen.layout.y);
      maxX = Math.max(maxX, screen.layout.x + CARD_WIDTH);
      maxY = Math.max(maxY, screen.layout.y + CARD_HEIGHT);
    }
    if (![minX, minY, maxX, maxY].every(Number.isFinite)) continue;
    const padX = 28;
    const padY = 36;
    clusters.push({
      region,
      x: minX - padX,
      y: minY - padY,
      width: maxX - minX + padX * 2,
      height: maxY - minY + padY + 22,
    });
  }
  return clusters;
}

function cardAnchor(from: AtlasScreen, to: AtlasScreen): { x: number; y: number } {
  const fromCx = from.layout.x + CARD_WIDTH / 2;
  const fromCy = from.layout.y + CARD_HEIGHT / 2;
  const toCx = to.layout.x + CARD_WIDTH / 2;
  const toCy = to.layout.y + CARD_HEIGHT / 2;
  const dx = toCx - fromCx;
  const dy = toCy - fromCy;
  const absX = Math.abs(dx);
  const absY = Math.abs(dy);
  if (absX >= absY) {
    return { x: from.layout.x + (dx >= 0 ? CARD_WIDTH : 0), y: fromCy };
  }
  return { x: fromCx, y: from.layout.y + (dy >= 0 ? CARD_HEIGHT : 0) };
}

export function createAppMapCanvas(options: AppMapCanvasOptions = {}): AppMapCanvasHandle {
  const root = el("div", "map-canvas");
  const toolbar = el("div", "map-canvas-toolbar");
  const zoomOut = toolButton("−", "Zoom out");
  const zoomIn = toolButton("+", "Zoom in");
  const fitButton = toolButton("Fit all", "Fit all rooms on the board");
  fitButton.classList.add("map-fit");
  toolbar.append(zoomOut, zoomIn, fitButton);

  const viewport = el("div", "map-canvas-viewport");
  viewport.tabIndex = 0;
  const world = el("div", "map-canvas-world");
  const empty = el("div", "map-canvas-empty");
  empty.hidden = true;
  const emptyCard = el("div", "map-empty-card");
  const emptyTitle = el("div", "map-empty-title", EMPTY_START_LABEL);
  const emptyCopy = el(
    "div",
    "map-empty-copy",
    "No rooms yet. Start mapping from the header; rooms appear here as the phone walks the app.",
  );
  const emptyAction = el("button", "btn btn-primary map-empty-action", EMPTY_START_LABEL) as HTMLButtonElement;
  emptyAction.type = "button";
  emptyAction.disabled = true;
  emptyAction.hidden = true; // Glass starts mapping from the app header, not from inside the board.
  emptyAction.title = EMPTY_START_HINT;
  emptyCard.append(emptyTitle, emptyCopy, emptyAction);
  empty.append(emptyCard);
  viewport.append(world, empty);
  root.append(toolbar, viewport);

  let model: AtlasViewModel | null = null;
  let selectedScreenId: string | null = null;
  let selectedEdgeId: string | null = null;
  let transform: CanvasTransform = { x: 0, y: 0, scale: 1 };
  let dragging = false;
  let moved = false;
  let lastX = 0;
  let lastY = 0;
  let hoverHint: HTMLElement | null = null;

  const applyTransform = (): void => {
    transform = sanitizeTransform(transform);
    world.style.transform = `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`;
    world.style.transformOrigin = "0 0";
    const size = Math.max(10, 22 * transform.scale);
    viewport.style.backgroundSize = `${size}px ${size}px`;
    viewport.style.backgroundPosition = `${transform.x}px ${transform.y}px`;
  };

  const viewportSize = (): ViewportSize => {
    const rect = viewport.getBoundingClientRect();
    return {
      width: rect.width || DEFAULT_VIEWPORT.width,
      height: rect.height || DEFAULT_VIEWPORT.height,
    };
  };

  const fitAll = (): CanvasTransform => {
    transform = computeFitTransform(model?.screens ?? [], viewportSize());
    applyTransform();
    return transform;
  };

  let cursorScreenId: string | null = null;
  let routeIds: string[] = [];
  const routeSteps = (): Map<string, number> => new Map(routeIds.map((id, index) => [id, index] as [string, number]).reverse());
  const routeEdge = (from: string, to: string): boolean =>
    routeIds.some((id, index) => index > 0 && routeIds[index - 1] === from && id === to);

  const paintSelection = (): void => {
    for (const card of world.querySelectorAll(".map-card")) {
      const button = card as HTMLElement;
      button.classList.toggle("selected", button.getAttribute("data-screen-id") === selectedScreenId);
      button.classList.toggle("mapping-cursor", cursorScreenId != null && button.getAttribute("data-screen-id") === cursorScreenId);
      button.classList.toggle("on-route", routeIds.includes(button.getAttribute("data-screen-id") ?? ""));
    }
    for (const path of world.querySelectorAll(".map-edge")) {
      const node = path as HTMLElement;
      node.classList.toggle("selected", node.getAttribute("data-edge-id") === selectedEdgeId);
    }
  };

  const select = (screenId: string | null): void => {
    selectedScreenId = screenId;
    if (screenId != null) selectedEdgeId = null;
    paintSelection();
    options.onSelectScreen?.(screenId);
    if (screenId != null) options.onSelectEdge?.(null);
  };

  const selectEdge = (edgeId: string | null): void => {
    selectedEdgeId = edgeId;
    if (edgeId != null) selectedScreenId = null;
    paintSelection();
    options.onSelectEdge?.(edgeId);
    if (edgeId != null) options.onSelectScreen?.(null);
  };

  const clearSelection = (): void => {
    selectedScreenId = null;
    selectedEdgeId = null;
    paintSelection();
    options.onSelectScreen?.(null);
    options.onSelectEdge?.(null);
  };

  const hideHint = (): void => {
    hoverHint?.remove();
    hoverHint = null;
  };

  const showHint = (text: string, clientX: number, clientY: number): void => {
    if (!hoverHint) {
      hoverHint = el("div", "map-edge-hint");
      root.append(hoverHint);
    }
    hoverHint.textContent = text;
    const rect = root.getBoundingClientRect();
    hoverHint.style.left = `${clientX - rect.left + 12}px`;
    hoverHint.style.top = `${clientY - rect.top + 12}px`;
  };

  const renderWorld = (): void => {
    hideHint();
    world.replaceChildren();
    const screens = model?.screens ?? [];
    const isEmpty = screens.length === 0;
    empty.hidden = !isEmpty;
    world.hidden = isEmpty;
    if (!model || isEmpty) return;

    const byId = new Map(screens.map((screen) => [screen.screenId, screen]));
    for (const cluster of clusterRegions(screens)) {
      const region = el("div", "map-region");
      region.style.left = `${cluster.x}px`;
      region.style.top = `${cluster.y}px`;
      region.style.width = `${cluster.width}px`;
      region.style.height = `${cluster.height}px`;
      region.append(el("div", "map-region-label", cluster.region));
      world.append(region);
    }

    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("class", "map-edges");
    const bounds = worldBounds(screens);
    svg.setAttribute("width", String(bounds.width));
    svg.setAttribute("height", String(bounds.height));
    svg.style.left = `${bounds.x}px`;
    svg.style.top = `${bounds.y}px`;
    svg.style.position = "absolute";
    svg.style.overflow = "visible";
    svg.style.pointerEvents = "none";

    for (const edge of model.edges) {
      const from = byId.get(edge.fromScreenId);
      const to = byId.get(edge.toScreenId);
      if (!from || !to) continue;
      const start = cardAnchor(from, to);
      const end = cardAnchor(to, from);
      const path = document.createElementNS(SVG_NS, "path");
      path.setAttribute("d", edgePath(start.x - bounds.x, start.y - bounds.y, end.x - bounds.x, end.y - bounds.y));
      path.setAttribute(
        "class",
        `map-edge${edge.risk.danger ? " danger" : ""}${edge.edgeId === selectedEdgeId ? " selected" : ""}${routeEdge(edge.fromScreenId, edge.toScreenId) ? " on-route" : ""}`,
      );
      path.setAttribute("data-edge-id", edge.edgeId);
      const opacity = 0.28 + 0.72 * (Number.isFinite(edge.confidence) ? edge.confidence : 0);
      path.setAttribute("stroke-opacity", String(Math.max(0.2, Math.min(1, opacity))));
      path.style.pointerEvents = "stroke";
      const title = document.createElementNS(SVG_NS, "title");
      title.textContent = edge.actionHint;
      path.append(title);
      path.addEventListener("pointerenter", (event) => {
        showHint(edge.actionHint, event.clientX, event.clientY);
      });
      path.addEventListener("pointermove", (event) => {
        showHint(edge.actionHint, event.clientX, event.clientY);
      });
      path.addEventListener("pointerleave", hideHint);
      path.addEventListener("pointerdown", (event) => {
        event.stopPropagation();
      });
      path.addEventListener("click", (event) => {
        event.stopPropagation();
        selectEdge(edge.edgeId);
      });
      svg.append(path);
    }
    world.append(svg);

    for (const screen of screens) {
      const card = renderCard(screen, screen.screenId === selectedScreenId, model.capabilities, (id, event) => {
        event.stopPropagation();
        select(id);
      });
      // A redraw during a live crawl must keep the pulse on the room the phone is in.
      if (cursorScreenId != null && screen.screenId === cursorScreenId) card.classList.add("mapping-cursor");
      const order = routeSteps().get(screen.screenId);
      if (order != null) {
        card.classList.add("on-route");
        card.append(el("span", "route-badge", String(order + 1)));
      }
      world.append(card);
    }
  };

  const onPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0) return;
    const target = event.target as HTMLElement | null;
    if (target?.closest(".map-card, .map-edge, .map-empty-action, .map-canvas-toolbar")) return;
    dragging = true;
    moved = false;
    lastX = event.clientX;
    lastY = event.clientY;
    viewport.classList.add("panning");
    viewport.setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event: PointerEvent): void => {
    if (!dragging) return;
    const dx = event.clientX - lastX;
    const dy = event.clientY - lastY;
    if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
    lastX = event.clientX;
    lastY = event.clientY;
    transform = panBy(transform, dx, dy);
    applyTransform();
  };

  const onPointerUp = (event: PointerEvent): void => {
    if (!dragging) return;
    dragging = false;
    viewport.classList.remove("panning");
    viewport.releasePointerCapture?.(event.pointerId);
    if (!moved) {
      const target = event.target as HTMLElement | null;
      if (target?.closest(".map-card, .map-edge")) return;
      clearSelection();
    }
  };

  const onWheel = (event: WheelEvent): void => {
    event.preventDefault();
    const rect = viewport.getBoundingClientRect();
    const factor = event.deltaY > 0 ? 0.92 : 1.08;
    transform = zoomToward(transform, factor, { x: event.clientX - rect.left, y: event.clientY - rect.top });
    applyTransform();
  };

  zoomIn.addEventListener("click", () => {
    const size = viewportSize();
    transform = zoomToward(transform, 1.15, { x: size.width / 2, y: size.height / 2 });
    applyTransform();
  });
  zoomOut.addEventListener("click", () => {
    const size = viewportSize();
    transform = zoomToward(transform, 1 / 1.15, { x: size.width / 2, y: size.height / 2 });
    applyTransform();
  });
  fitButton.addEventListener("click", () => {
    fitAll();
  });

  viewport.addEventListener("pointerdown", onPointerDown);
  viewport.addEventListener("pointermove", onPointerMove);
  viewport.addEventListener("pointerup", onPointerUp);
  viewport.addEventListener("pointerleave", onPointerUp);
  viewport.addEventListener("wheel", onWheel, { passive: false });
  applyTransform();

  return {
    element: root,
    destroy(): void {
      hideHint();
      viewport.removeEventListener("pointerdown", onPointerDown);
      viewport.removeEventListener("pointermove", onPointerMove);
      viewport.removeEventListener("pointerup", onPointerUp);
      viewport.removeEventListener("pointerleave", onPointerUp);
      viewport.removeEventListener("wheel", onWheel);
    },
    setViewModel(next, settings): void {
      model = next;
      if (selectedScreenId && !next.screens.some((screen) => screen.screenId === selectedScreenId)) {
        selectedScreenId = null;
      }
      if (selectedEdgeId && !next.edges.some((edge) => edge.edgeId === selectedEdgeId)) {
        selectedEdgeId = null;
      }
      renderWorld();
      if (settings?.fit) fitAll();
      applyTransform();
    },
    setSelectedScreenId(screenId): void {
      selectedScreenId = screenId;
      if (screenId != null) selectedEdgeId = null;
      renderWorld();
    },
    setSelectedEdgeId(edgeId): void {
      selectedEdgeId = edgeId;
      if (edgeId != null) selectedScreenId = null;
      renderWorld();
    },
    setCursorScreenId(screenId): void {
      if (cursorScreenId === screenId) return;
      cursorScreenId = screenId;
      paintSelection();
    },
    setRoute(screenIds): void {
      const next = screenIds.filter((id) => typeof id === "string" && id.length > 0).slice(0, 60);
      if (next.join("|") === routeIds.join("|")) return;
      routeIds = next;
      renderWorld();
    },
    fitAll,
    getTransform(): CanvasTransform {
      return { ...transform };
    },
    getSelectedScreenId(): string | null {
      return selectedScreenId;
    },
    getSelectedEdgeId(): string | null {
      return selectedEdgeId;
    },
  };
}

function worldBounds(screens: AtlasScreen[]): { x: number; y: number; width: number; height: number } {
  let minX = 0;
  let minY = 0;
  let maxX = 800;
  let maxY = 600;
  for (const screen of screens) {
    minX = Math.min(minX, screen.layout.x - 80);
    minY = Math.min(minY, screen.layout.y - 80);
    maxX = Math.max(maxX, screen.layout.x + CARD_WIDTH + 80);
    maxY = Math.max(maxY, screen.layout.y + CARD_HEIGHT + 80);
  }
  return { x: minX, y: minY, width: Math.max(1, maxX - minX), height: Math.max(1, maxY - minY) };
}

function renderCard(
  screen: AtlasScreen,
  selected: boolean,
  capabilities: string[],
  onSelect: (screenId: string, event: Event) => void,
): HTMLElement {
  const card = el("button", `map-card tone-${screen.tone}${selected ? " selected" : ""}`) as HTMLButtonElement;
  card.type = "button";
  card.setAttribute("data-screen-id", screen.screenId);
  card.style.left = `${screen.layout.x}px`;
  card.style.top = `${screen.layout.y}px`;
  const opacity = 0.48 + 0.52 * (Number.isFinite(screen.confidence) ? screen.confidence : 0);
  card.style.opacity = String(Math.max(0.45, Math.min(1, opacity)));
  const kicker = el("div", "map-card-kicker");
  kicker.append(el("span", "map-card-pip"), el("span", "", screen.region));
  card.append(
    kicker,
    el("div", "map-card-title", screen.label),
    el("div", "map-card-purpose", screen.purpose),
  );
  const glyphs = cardGlyphs(screen.purpose, capabilities);
  if (glyphs.length) {
    const row = el("div", "map-card-glyphs");
    for (const glyph of glyphs) {
      const mark = el("span", "map-glyph", glyph);
      mark.setAttribute("aria-hidden", "true");
      row.append(mark);
    }
    card.append(row);
  }
  if (screen.landmarks.length) {
    const marks = el("div", "map-card-landmarks");
    for (const landmark of screen.landmarks.slice(0, 3)) marks.append(el("span", "map-chip", landmark));
    card.append(marks);
  }
  card.append(el("div", "map-card-meta", `${Math.round(screen.confidence * 100)}% · ${screen.tone}`));
  card.addEventListener("click", (event) => onSelect(screen.screenId, event));
  card.addEventListener("pointerdown", (event) => event.stopPropagation());
  return card;
}

function toolButton(label: string, title: string): HTMLButtonElement {
  const node = el("button", "map-tool", label) as HTMLButtonElement;
  node.type = "button";
  node.title = title;
  node.setAttribute("aria-label", title);
  return node;
}

export function emptyBoardModel(placeLabel: string): { title: string; copy: string } {
  return {
    title: EMPTY_START_LABEL,
    copy: `${placeLabel} is not mapped yet. Start mapping and rooms appear here as the phone walks the app.`,
  };
}
