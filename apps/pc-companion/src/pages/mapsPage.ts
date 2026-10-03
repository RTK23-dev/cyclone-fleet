import {
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  phoneSupportsGlassAtlas,
} from "../core/fleet.js";
import {
  DEFAULT_FOREGROUND_SESSION_ID,
  FOREGROUND_PLANE_LABEL,
  VD_PLANE_LABEL,
} from "../core/sessionTiles.js";
import {
  applyBoardFilters,
  formatCoverage,
  inspectorState,
  inspectorStateForEdge,
  resolveEdgeSelection,
  resolveSelection,
  statusLabel,
  toViewModel,
  type AtlasViewModel,
  type BoardFilters,
  type InspectorState,
  type Persona,
  type PlaceKind,
  type PlaceSummary,
} from "../maps/atlasViewModel.js";
import {
  defaultMapsPlaceId,
  mockMapsDataSource,
  type MapsDataSource,
} from "../maps/mockAtlas.js";
import {
  emptyMapsDataSource,
  MAPS_DEMO_COPY,
  MAPS_DEMO_LABEL,
  MAPS_EMPTY_ATLAS_COPY,
  MAPS_EMPTY_ATLAS_TITLE,
  MAPS_LOADING_COPY,
  MAPS_LOADING_TITLE,
  namedMapsLoadError,
  type MapsLoadErrorView,
} from "../maps/phoneAtlasSource.js";
import {
  createMappingWatcher,
  isActiveMapping,
  mappingStatusLine,
  type MappingOps,
  type MappingWatcher,
} from "../maps/mappingWatcher.js";
import type { MappingJobView } from "../services/atlasClient.js";
import { button, el, setChildren } from "../ui/dom.js";
import { createAppMapCanvas, type AppMapCanvasHandle } from "../ui/appMapCanvas.js";

export interface MapsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface MapsPageOptions {
  source?: MapsDataSource;
  loadSource?: () => Promise<MapsDataSource>;
  phoneVersion?: string | null;
  demo?: boolean;
  sessionId?: string;
  sessionPlane?: "foreground" | "session_kernel_vd";
  onOpenControl?: () => void;
  /** Real phone mapping commands. Absent in demo / no phone: Start stays disabled and says why. */
  mapping?: MappingOps;
  /** Test seam for the mapping poll timer. */
  mappingTimer?: { set(fn: () => void, ms: number): unknown; clear(handle: unknown): void };
}

const ALPHA_HINT = "Connect a Mobile 5 phone to map";
const MAP_START_HINT = "Walks this app's tabs, menus and settings on the phone. Never pays, sends, deletes or grants.";
const MAP_VD_HINT = "Mapping runs on the phone's main screen (Foreground) in this alpha.";
const MAP_NO_APP_HINT = "Pick an installed app, or start the first pass on the phone: Settings → App Maps.";
const TAKE_CONTROL_HINT = "Phone live handoff, not mapping pause.";

type BoardPhase = "update-phone" | "loading" | "ready" | "empty" | "error";

let mapsCssLinked = false;

function ensureMapsCss(): void {
  if (mapsCssLinked) return;
  mapsCssLinked = true;
  if (typeof document === "undefined" || !document.head) return;
  if (document.getElementById("cyclone-maps-css")) return;
  const link = document.createElement("link");
  link.id = "cyclone-maps-css";
  link.rel = "stylesheet";
  try {
    link.href = new URL("../maps.css", import.meta.url).href;
  } catch {
    link.href = "/src/maps.css";
  }
  document.head.appendChild(link);
}

function hasPhoneVersionOption(options: MapsPageOptions): boolean {
  return Object.prototype.hasOwnProperty.call(options, "phoneVersion");
}

export function createMapsPage(options: MapsPageOptions = {}): MapsPageHandle {
  ensureMapsCss();
  const demo = options.demo === true;
  const versionGiven = hasPhoneVersionOption(options);
  const atlasReady = phoneSupportsGlassAtlas(options.phoneVersion || "");
  const loadSource = options.loadSource;

  let source: MapsDataSource = emptyMapsDataSource();
  let phase: BoardPhase = "empty";
  let loadError: MapsLoadErrorView | null = null;
  let destroyed = false;
  let loadGen = 0;

  if (demo) {
    source = options.source ?? mockMapsDataSource;
    phase = "ready";
  } else if (versionGiven && !atlasReady) {
    source = emptyMapsDataSource();
    phase = "update-phone";
  } else if (loadSource) {
    source = emptyMapsDataSource();
    phase = "loading";
  } else if (versionGiven && atlasReady) {
    source = options.source ?? emptyMapsDataSource();
    phase = sourceHasPlaces(source, "live") ? "ready" : "empty";
  } else if (options.source) {
    source = options.source;
    phase = sourceHasPlaces(source, "live") ? "ready" : "empty";
  } else {
    source = mockMapsDataSource;
    phase = "ready";
  }

  const page = el("section", "page maps-page");
  page.setAttribute("aria-label", "Maps");

  let persona: Persona = "live";
  let placeId = phase === "ready" ? defaultPlaceId(source, persona) : "";
  let selectedScreenId: string | null = null;
  let selectedEdgeId: string | null = null;
  let viewMode: "screens" | "capabilities" = "screens";
  let railQuery = "";
  let mappedOnly = false;
  let kindFilter: "all" | PlaceKind = "all";
  const filters: BoardFilters = { stale: false, blocked: false, danger: false, unmappedDoors: false };

  const banner = el("aside", "glass-compat-banner");
  banner.hidden = true;

  const top = el("div", "maps-top");
  const heading = el("div", "maps-place-heading");
  const kicker = el("div", "maps-kicker", "Maps");
  const placeTitle = el("div", "maps-place-title", "");
  heading.append(kicker, placeTitle);

  const personaSeg = segment("persona", [
    { id: "live", label: "Live" },
    { id: "mapping", label: "Dummy" },
  ]);
  const viewSeg = segment("view", [
    { id: "screens", label: "Screens" },
    { id: "capabilities", label: "Capabilities" },
  ]);
  const coverage = el("div", "maps-coverage");
  const sessionPlane = el("div", "maps-session-plane");
  sessionPlane.setAttribute("aria-label", "Session plane");
  const planeLabel = options.sessionPlane === "session_kernel_vd" ? VD_PLANE_LABEL : FOREGROUND_PLANE_LABEL;
  const sessionIdDisplay = displaySessionId(options.sessionId);
  sessionPlane.textContent = `${planeLabel} · session_id ${sessionIdDisplay}`;
  const takeControl = button("Take control", "button secondary compact maps-take-control");
  takeControl.disabled = typeof options.onOpenControl !== "function";
  takeControl.title = TAKE_CONTROL_HINT;
  takeControl.addEventListener("click", () => {
    if (typeof options.onOpenControl !== "function") return;
    options.onOpenControl();
  });
  const start = button("Start mapping", "button primary compact maps-start");
  start.disabled = true;
  start.title = ALPHA_HINT;
  const pauseMapping = button("Pause", "button secondary compact maps-pause");
  pauseMapping.hidden = true;
  const stopMapping = button("Stop", "button secondary compact maps-stop");
  stopMapping.hidden = true;
  const mappingStatus = el("div", "maps-mapping-status");
  mappingStatus.setAttribute("aria-live", "polite");

  const filterStale = chip("Stale", "stale");
  const filterBlocked = chip("Blocked", "blocked");
  const filterDanger = chip("Danger", "danger");
  const filterDark = chip("Dark doors", "unmapped");
  const filtersWrap = el("div", "maps-filters");
  filtersWrap.append(filterStale, filterBlocked, filterDanger, filterDark, takeControl, mappingStatus, pauseMapping, stopMapping, start);

  top.append(heading, sessionPlane, personaSeg.root, viewSeg.root, coverage, el("div", "maps-top-spacer"), filtersWrap);

  const body = el("div", "maps-body");
  const rail = el("aside", "maps-rail");
  rail.append(el("div", "maps-pane-head", "Places"));
  const search = el("input", "maps-search") as HTMLInputElement;
  search.type = "search";
  search.placeholder = "Search places";
  search.setAttribute("aria-label", "Search places");
  const railFilters = el("div", "maps-rail-filters");
  const mappedChip = chip("Mapped only");
  const nativeChip = chip("Native");
  const chromeChip = chip("Chrome");
  railFilters.append(mappedChip, nativeChip, chromeChip);
  const placeList = el("div", "maps-place-list");
  rail.append(search, railFilters, placeList);

  const boardHost = el("div", "maps-board-host");
  boardHost.style.minWidth = "0";
  boardHost.style.minHeight = "0";
  boardHost.style.display = "flex";
  const canvas: AppMapCanvasHandle = createAppMapCanvas({
    onSelectScreen(screenId) {
      selectedScreenId = screenId;
      if (screenId != null) selectedEdgeId = null;
      renderInspector();
    },
    onSelectEdge(edgeId) {
      selectedEdgeId = edgeId;
      if (edgeId != null) selectedScreenId = null;
      renderInspector();
    },
  });
  canvas.element.style.flex = "1";
  const overlay = el("div", "maps-board-state");
  overlay.hidden = true;
  overlay.setAttribute("aria-live", "polite");
  const capabilities = el("div", "maps-capabilities");
  capabilities.hidden = true;
  boardHost.append(canvas.element, overlay, capabilities);

  const inspector = el("aside", "maps-inspector");
  const inspectorHead = el("div", "maps-pane-head", "Inspector");
  const inspectorBody = el("div", "maps-inspector-body");
  inspector.append(inspectorHead, inspectorBody);

  body.append(rail, boardHost, inspector);
  page.append(banner, top, body);

  const loadModel = (): AtlasViewModel => {
    const document = source.getDocument(placeId, persona);
    const summaries = source.listSummaries(persona);
    const summary = summaries.find((item) => item.place.placeId === placeId);
    if (!document) {
      return toViewModel({
        place: summary?.place ?? { placeId: placeId || "package:com.unknown.app", kind: "package", label: placeId || "Maps", packageName: "com.unknown.app" },
        persona,
        mapStatus: "unmapped",
        screens: [],
        edges: [],
        capabilities: [],
        confidence: 0,
        lastObservedAt: null,
        lastVerifiedAt: null,
      });
    }
    return applyBoardFilters(toViewModel(document), filters);
  };

  const paintBanner = (): void => {
    banner.replaceChildren();
    banner.classList.remove("maps-demo-banner", "maps-update-banner");
    if (demo) {
      banner.hidden = false;
      banner.classList.add("maps-demo-banner");
      banner.append(
        el("div", "glass-compat-title", MAPS_DEMO_LABEL),
        el("p", "glass-compat-copy", MAPS_DEMO_COPY),
      );
      return;
    }
    if (phase === "update-phone") {
      banner.hidden = false;
      banner.classList.add("maps-update-banner");
      banner.append(
        el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
        el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
      );
      return;
    }
    banner.hidden = true;
  };

  const paintOverlay = (): void => {
    const show = phase === "loading" || phase === "update-phone" || phase === "error" || phase === "empty";
    overlay.hidden = !show;
    overlay.replaceChildren();
    overlay.className = "maps-board-state";
    if (!show) return;
    overlay.classList.add(`maps-${phase}`);
    const card = el("div", "maps-state-card");
    if (phase === "loading") {
      card.classList.add("maps-loading");
      card.append(
        el("div", "maps-state-title", MAPS_LOADING_TITLE),
        el("p", "maps-state-copy", MAPS_LOADING_COPY),
      );
    } else if (phase === "update-phone") {
      card.classList.add("glass-compat-banner", "maps-update-phone");
      card.append(
        el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
        el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
      );
    } else if (phase === "error") {
      card.classList.add("maps-error");
      card.append(
        el("div", "maps-state-kicker", loadError?.code ?? "LOAD_FAILED"),
        el("div", "maps-state-title", loadError?.title ?? "LOAD_FAILED"),
        el("p", "maps-state-copy", loadError?.copy ?? "Maps could not load this phone’s atlas."),
      );
    } else {
      card.classList.add("maps-empty");
      card.append(
        el("div", "maps-state-title", MAPS_EMPTY_ATLAS_TITLE),
        el("p", "maps-state-copy", MAPS_EMPTY_ATLAS_COPY),
      );
    }
    overlay.append(card);
  };

  const syncPlace = (): void => {
    const summaries = source.listSummaries(persona);
    if (!summaries.length) {
      placeId = "";
      return;
    }
    if (!summaries.some((item) => item.place.placeId === placeId)) {
      placeId = summaries[0].place.placeId;
    }
  };

  const renderRail = (): void => {
    const summaries = source.listSummaries(persona).filter((item) => {
      if (mappedOnly && item.mapStatus === "unmapped") return false;
      if (kindFilter !== "all" && item.place.kind !== kindFilter) return false;
      const q = railQuery.trim().toLowerCase();
      if (!q) return true;
      return `${item.place.label} ${item.place.placeId}`.toLowerCase().includes(q);
    });
    if (!summaries.length) {
      const emptyRail = el("div", "maps-muted maps-rail-empty");
      emptyRail.textContent = phase === "update-phone"
        ? GLASS_UPDATE_PHONE_TITLE
        : phase === "loading"
          ? MAPS_LOADING_TITLE
          : MAPS_EMPTY_ATLAS_TITLE;
      setChildren(placeList, emptyRail);
      return;
    }
    setChildren(
      placeList,
      ...summaries.map((item) => placeRow(item, item.place.placeId === placeId, () => {
        placeId = item.place.placeId;
        selectedScreenId = null;
        selectedEdgeId = null;
        renderAll(true);
      })),
    );
  };

  const renderInspector = (): void => {
    if (phase !== "ready") {
      const title = phase === "update-phone"
        ? GLASS_UPDATE_PHONE_TITLE
        : phase === "loading"
          ? MAPS_LOADING_TITLE
          : phase === "error"
            ? (loadError?.title ?? "LOAD_FAILED")
            : MAPS_EMPTY_ATLAS_TITLE;
      const subtitle = phase === "update-phone"
        ? GLASS_UPDATE_PHONE_COPY
        : phase === "loading"
          ? MAPS_LOADING_COPY
          : phase === "error"
            ? (loadError?.copy ?? "")
            : MAPS_EMPTY_ATLAS_COPY;
      setChildren(
        inspectorBody,
        el("div", "maps-inspector-title", title),
        el("p", "maps-muted", subtitle),
      );
      return;
    }
    const model = loadModel();
    const state = selectedEdgeId
      ? inspectorStateForEdge(model, selectedEdgeId)
      : inspectorState(model, selectedScreenId);
    setChildren(inspectorBody, ...inspectorNodes(state));
  };

  const renderCapabilities = (model: AtlasViewModel): void => {
    if (!model.capabilities.length) {
      setChildren(capabilities, el("div", "maps-muted", "No capabilities recorded for this place yet."));
      return;
    }
    setChildren(
      capabilities,
      ...model.capabilities.map((name) => {
        const card = el("article", "maps-cap-card");
        card.append(el("div", "maps-cap-name", name), el("div", "maps-cap-copy", "Place capability · phone atlas"));
        return card;
      }),
    );
  };

  let renderAll = (fit: boolean): void => {
    if (phase === "ready" && !sourceHasPlaces(source, persona)) phase = "empty";
    if (phase === "empty" && sourceHasPlaces(source, persona)) phase = "ready";
    page.dataset.mapsPhase = phase;
    paintBanner();
    paintOverlay();
    const boardReady = phase === "ready";
    if (!boardReady) {
      placeTitle.textContent = "Maps";
      coverage.textContent = "";
      canvas.element.hidden = true;
      capabilities.hidden = true;
      personaSeg.set(persona);
      viewSeg.set(viewMode);
      renderRail();
      renderInspector();
      return;
    }
    syncPlace();
    const model = loadModel();
    selectedScreenId = resolveSelection(model, selectedScreenId);
    selectedEdgeId = resolveEdgeSelection(model, selectedEdgeId);
    if (selectedEdgeId) selectedScreenId = null;
    placeTitle.textContent = model.place.label;
    coverage.textContent = formatCoverage(model.coverage);
    personaSeg.set(persona);
    viewSeg.set(viewMode);
    canvas.element.hidden = viewMode !== "screens";
    capabilities.hidden = viewMode === "screens";
    canvas.setViewModel(model, { fit: viewMode === "screens" && fit });
    if (selectedEdgeId) canvas.setSelectedEdgeId(selectedEdgeId);
    else canvas.setSelectedScreenId(selectedScreenId);
    if (viewMode === "capabilities") renderCapabilities(model);
    renderRail();
    renderInspector();
  };

  personaSeg.root.addEventListener("click", (event) => {
    const next = (event.target as HTMLElement | null)?.closest("button")?.dataset.id;
    if (next !== "live" && next !== "mapping") return;
    persona = next;
    selectedScreenId = null;
    selectedEdgeId = null;
    renderAll(true);
  });
  viewSeg.root.addEventListener("click", (event) => {
    const next = (event.target as HTMLElement | null)?.closest("button")?.dataset.id;
    if (next !== "screens" && next !== "capabilities") return;
    viewMode = next;
    renderAll(false);
  });
  filterStale.addEventListener("click", () => {
    filters.stale = !filters.stale;
    filterStale.classList.toggle("active", filters.stale);
    renderAll(false);
  });
  filterBlocked.addEventListener("click", () => {
    filters.blocked = !filters.blocked;
    filterBlocked.classList.toggle("active", filters.blocked);
    renderAll(false);
  });
  filterDanger.addEventListener("click", () => {
    filters.danger = !filters.danger;
    filterDanger.classList.toggle("active", filters.danger);
    renderAll(false);
  });
  filterDark.addEventListener("click", () => {
    filters.unmappedDoors = !filters.unmappedDoors;
    filterDark.classList.toggle("active", Boolean(filters.unmappedDoors));
    renderAll(false);
  });
  mappedChip.addEventListener("click", () => {
    mappedOnly = !mappedOnly;
    mappedChip.classList.toggle("active", mappedOnly);
    renderRail();
  });
  const setKindFilter = (next: "all" | PlaceKind): void => {
    kindFilter = next;
    nativeChip.classList.toggle("active", kindFilter === "package");
    chromeChip.classList.toggle("active", kindFilter === "chrome-origin");
    renderRail();
  };
  nativeChip.addEventListener("click", () => {
    setKindFilter(kindFilter === "package" ? "all" : "package");
  });
  chromeChip.addEventListener("click", () => {
    setKindFilter(kindFilter === "chrome-origin" ? "all" : "chrome-origin");
  });
  search.addEventListener("input", () => {
    railQuery = search.value;
    renderRail();
  });

  // ── Live mapping: the phone walks, the board watches. ───────────────────
  const mappingOps = demo ? undefined : options.mapping;
  const mappingAllowed = Boolean(mappingOps) && atlasReady && options.sessionPlane !== "session_kernel_vd";
  let mappingJob: MappingJobView | null = null;
  let mappingBusy = false;
  let reloading = false;
  let reloadAgain = false;

  const paintMappingControls = (): void => {
    const active = isActiveMapping(mappingJob);
    const line = mappingStatusLine(mappingJob);
    mappingStatus.textContent = line;
    mappingStatus.hidden = line === "";
    mappingStatus.classList.toggle("needs-secret", mappingJob?.state === "needs-secret");
    pauseMapping.hidden = !active;
    stopMapping.hidden = !active;
    pauseMapping.textContent = mappingJob?.state === "running" ? "Pause" : "Resume";
    pauseMapping.disabled = mappingBusy;
    stopMapping.disabled = mappingBusy;
    start.hidden = active;
    if (!mappingOps) {
      start.disabled = true;
      start.title = ALPHA_HINT;
    } else if (!mappingAllowed) {
      start.disabled = true;
      start.title = atlasReady ? MAP_VD_HINT : ALPHA_HINT;
    } else if (!placeId.startsWith("package:")) {
      start.disabled = true;
      start.title = MAP_NO_APP_HINT;
    } else {
      start.disabled = mappingBusy;
      start.title = MAP_START_HINT;
    }
    const cursorVisible = mappingJob != null && persona === "mapping" && mappingJob.placeId === placeId;
    canvas.setCursorScreenId(cursorVisible ? mappingJob?.currentAtlasNodeId ?? null : null);
  };

  const reloadBoard = (focusPlaceId: string): void => {
    if (!loadSource || destroyed) return;
    if (reloading) {
      reloadAgain = true;
      return;
    }
    reloading = true;
    void Promise.resolve()
      .then(() => loadSource())
      .then((next) => {
        if (destroyed) return;
        source = next ?? emptyMapsDataSource();
        if (persona === "mapping" && source.listSummaries("mapping").some((item) => item.place.placeId === focusPlaceId)) {
          placeId = focusPlaceId;
        }
        if (phase !== "update-phone") phase = sourceHasPlaces(source, persona) ? "ready" : "empty";
        // Keep the operator's pan/zoom: new cards appear, the board does not jump.
        renderAll(false);
        paintMappingControls();
      })
      .catch(() => undefined)
      .finally(() => {
        reloading = false;
        if (reloadAgain && !destroyed) {
          reloadAgain = false;
          reloadBoard(focusPlaceId);
        }
      });
  };

  const watcher: MappingWatcher | null = mappingOps && mappingAllowed
    ? createMappingWatcher({
        ops: mappingOps,
        onJob(job) {
          mappingJob = job;
          paintMappingControls();
        },
        onAtlasChanged(changedPlaceId) {
          reloadBoard(changedPlaceId);
        },
        onError() {
          mappingStatus.textContent = "Lost contact with the phone's mapping pass; retrying.";
          mappingStatus.hidden = false;
        },
        setTimer: options.mappingTimer?.set,
        clearTimer: options.mappingTimer?.clear,
      })
    : null;

  const runMappingCommand = (command: () => Promise<void>): void => {
    if (mappingBusy) return;
    mappingBusy = true;
    paintMappingControls();
    void command()
      .catch((error: unknown) => {
        const code = (error as { code?: unknown })?.code;
        mappingStatus.textContent = mappingErrorCopy(typeof code === "string" ? code : "");
        mappingStatus.hidden = false;
      })
      .finally(() => {
        mappingBusy = false;
        paintMappingControls();
      });
  };

  start.addEventListener("click", () => {
    if (!watcher || start.disabled) return;
    const target = placeId;
    persona = "mapping";
    selectedScreenId = null;
    selectedEdgeId = null;
    runMappingCommand(() => watcher.start(target));
  });
  pauseMapping.addEventListener("click", () => {
    if (!watcher) return;
    runMappingCommand(() => (mappingJob?.state === "running" ? watcher.pause() : watcher.resume()));
  });
  stopMapping.addEventListener("click", () => {
    if (!watcher) return;
    runMappingCommand(() => watcher.stop());
  });

  const baseRenderAll = renderAll;
  renderAll = (fit: boolean): void => {
    baseRenderAll(fit);
    paintMappingControls();
  };

  renderAll(true);
  if (watcher) void watcher.attach().catch(() => undefined);

  if (phase === "loading" && loadSource) {
    const gen = ++loadGen;
    let pending: Promise<MapsDataSource>;
    try {
      pending = Promise.resolve(loadSource());
    } catch (error) {
      pending = Promise.reject(error);
    }
    void pending
      .then((next) => {
        if (destroyed || gen !== loadGen) return;
        source = next ?? emptyMapsDataSource();
        syncPlace();
        phase = sourceHasPlaces(source, persona) ? "ready" : "empty";
        loadError = null;
        renderAll(true);
      })
      .catch((error: unknown) => {
        if (destroyed || gen !== loadGen) return;
        source = emptyMapsDataSource();
        placeId = "";
        loadError = namedMapsLoadError(error);
        phase = "error";
        renderAll(false);
      });
  }

  return {
    element: page,
    destroy(): void {
      destroyed = true;
      loadGen += 1;
      watcher?.dispose();
      canvas.destroy();
    },
  };
}

function mappingErrorCopy(code: string): string {
  switch (code) {
    case "MAPPING_PLANE_BUSY":
      return "A mapping pass is already running on this phone.";
    case "HUMAN_HAS_CONTROL":
      return "You have control of the phone. Give it back to Cyclone, then start mapping.";
    case "SESSION_REQUIRED":
    case "SESSION_DISPLAY_MISMATCH":
      return "The phone's main screen isn't available to Cyclone. Check accessibility on the phone.";
    case "PLACE_NOT_LAUNCHABLE":
      return "Glass maps installed apps in this alpha.";
    default:
      return `Mapping couldn't start${code ? ` (${code})` : ""}.`;
  }
}

function sourceHasPlaces(source: MapsDataSource, persona: Persona): boolean {
  return source.listSummaries(persona).length > 0;
}

function defaultPlaceId(source: MapsDataSource, persona: Persona): string {
  return source.listSummaries(persona)[0]?.place.placeId ?? defaultMapsPlaceId();
}

function placeRow(summary: PlaceSummary, selected: boolean, onSelect: () => void): HTMLButtonElement {
  const row = el("button", `maps-place${selected ? " selected" : ""}`) as HTMLButtonElement;
  row.type = "button";
  row.append(
    el("span", "maps-place-name", summary.place.label),
    el("span", `maps-status ${summary.mapStatus}`, statusLabel(summary.mapStatus)),
    el("span", "maps-place-id", summary.place.placeId),
  );
  row.addEventListener("click", onSelect);
  return row;
}

function inspectorNodes(state: InspectorState): HTMLElement[] {
  if (state.kind === "empty") {
    return [
      el("div", "maps-inspector-title", state.title),
      el("p", "maps-muted", state.subtitle),
    ];
  }
  if (state.kind === "edge") {
    const nodes: HTMLElement[] = [
      el("div", "maps-inspector-title", state.title),
      el("div", "maps-inspector-sub", state.subtitle),
      el("div", "maps-block-label", "From"),
      el("div", "maps-purpose", state.fromLabel ?? ""),
      el("div", "maps-block-label", "To"),
      el("div", "maps-purpose", state.toLabel ?? ""),
    ];
    const proof = el("div", "maps-muted");
    const pct = state.confidence != null ? `${Math.round(state.confidence * 100)}% confidence` : "confidence unknown";
    proof.textContent = `${pct} · last verified ${state.lastVerifiedAt ?? "never"}`;
    nodes.push(proof);
    const actions = el("div", "maps-actions");
    actions.append(disabledAction("Pin"), disabledAction("Remap this room"), disabledAction("Never"));
    nodes.push(actions);
    return nodes;
  }
  const nodes: HTMLElement[] = [
    el("div", "maps-inspector-title", state.title),
    el("div", "maps-inspector-sub", state.subtitle),
    el("div", "maps-block-label", "Purpose"),
    el("div", "maps-purpose", state.purpose ?? ""),
    el("div", "maps-block-label", "Redacted frame"),
    el("div", "maps-frame", state.frameCopy),
    el("div", "maps-block-label", "Fact slots"),
  ];
  if (!state.factSlots.length) nodes.push(el("div", "maps-muted", "No fact slots on this room."));
  for (const slot of state.factSlots) {
    const card = el("div", "maps-slot");
    card.append(
      el("div", "maps-slot-name", slot.displayLabel),
      el("div", "maps-slot-value", slot.maskedValue),
      el("div", "maps-slot-copy", slot.description),
    );
    nodes.push(card);
  }
  nodes.push(el("div", "maps-block-label", "Doors"));
  if (!state.doors.length) nodes.push(el("div", "maps-muted", "No outgoing doors mapped from this room."));
  for (const door of state.doors) {
    const card = el("div", `maps-door${door.dark ? " dark" : ""}`);
    card.append(
      el("div", "maps-door-name", door.actionHint),
      el("div", "maps-door-copy", `to ${door.toLabel}${door.dark ? " · still dark" : ""}`),
    );
    nodes.push(card);
  }
  if (state.lastVerifiedAt || state.confidence != null) {
    const proof = el("div", "maps-muted");
    const pct = state.confidence != null ? `${Math.round(state.confidence * 100)}% confidence` : "confidence unknown";
    proof.textContent = `${pct} · last verified ${state.lastVerifiedAt ?? "never"}`;
    nodes.push(proof);
  }
  const actions = el("div", "maps-actions");
  actions.append(disabledAction("Pin"), disabledAction("Remap this room"), disabledAction("Never"));
  nodes.push(actions);
  return nodes;
}

function disabledAction(label: string): HTMLButtonElement {
  const node = button(label, "button secondary compact");
  node.disabled = true;
  node.title = ALPHA_HINT;
  return node;
}

function chip(label: string, extraClass = ""): HTMLButtonElement {
  const node = el("button", `maps-chip${extraClass ? ` ${extraClass}` : ""}`, label) as HTMLButtonElement;
  node.type = "button";
  return node;
}

function segment(name: string, items: Array<{ id: string; label: string }>): { root: HTMLElement; set(id: string): void } {
  const root = el("div", "maps-seg");
  root.setAttribute("role", "group");
  root.setAttribute("aria-label", name);
  const buttons = items.map((item) => {
    const node = el("button", "", item.label) as HTMLButtonElement;
    node.type = "button";
    node.dataset.id = item.id;
    root.append(node);
    return node;
  });
  return {
    root,
    set(id: string) {
      for (const node of buttons) node.classList.toggle("active", node.dataset.id === id);
    },
  };
}

export { inspectorState, inspectorStateForEdge, toViewModel, applyBoardFilters };

function displaySessionId(sessionId?: string): string {
  const trimmed = (sessionId ?? "").trim();
  return trimmed || DEFAULT_FOREGROUND_SESSION_ID;
}
