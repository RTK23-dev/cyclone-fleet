/**
 * One app (a "house"): header facts from `apps.list`, the rooms-and-doors board from `atlas.get`, and mapping
 * commanded on the phone (`mapping.*`) with the board following `atlas.diff`. Glass decides nothing: it shows the
 * phone's map and forwards the developer's Start / Pause / Stop.
 */
import type { GlassContext } from "../app.js";
import { routeHref, type Route } from "../core/router.js";
import { toMapsDocument } from "../maps/atlasDocument.js";
import {
  inspectorState,
  inspectorStateForEdge,
  statusLabel as roomStatusLabel,
  toViewModel,
  type AtlasViewModel,
  type InspectorState,
  type Persona,
} from "../maps/atlasViewModel.js";
import { createMappingWatcher, isActiveMapping, mappingStatusLine, type MappingWatcher } from "../maps/mappingWatcher.js";
import type { MappingJobView, MappingMission } from "../services/atlasClient.js";
import { GROUND_LABEL, GROUND_TONE, loadSkills, skillScreens, skillsThrough, type SkillView } from "../services/skills.js";
import { missionLive, missionReport, startSheet, type MissionEvent } from "../ui/missionPanel.js";
import { coverageReport } from "../maps/zones.js";
import { loadApps, scenarioSummary, statusLabel, statusTone, versionLabel, type PhoneApp } from "../services/apps.js";
import { mappingErrorCopy, phoneClient } from "../services/phone.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, segmented } from "../ui/components.js";
import { createAppMapCanvas } from "../ui/appMapCanvas.js";
import { icon } from "../ui/icons.js";
import { plural, relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";
import { startTeaching, stopTeaching } from "../services/teach.js";
import { appTabs } from "./appKnowledgePage.js";
import { deriveZones, layeredLayout, layoutCollapsed, runsThroughDoor, zoneSubModel, type LaneId, type ZoneMap } from "../maps/zones.js";
import { createZoneOverview } from "../ui/zoneOverview.js";
import { getScenarios } from "../services/knowledge.js";
import { applyManualNames, loadDictionary, loadModels } from "../services/dictionary.js";
import { listRuns, statusLabel as runStatusLabel, statusTone as runStatusTone, type RunSummary } from "../services/runs.js";

/** Semantic zoom (plan 22): the app's story, one zone, one scenario lane, or every place at once. */
export type AtlasView = { kind: "overview" } | { kind: "zone"; id: string } | { kind: "lane"; id: LaneId } | { kind: "all" };

export interface AppPageDeps {
  fetch?: typeof fetch;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  now?: () => number;
}

export function createAppPage(ctx: GlassContext, route: Extract<Route, { name: "app" }>, deps: AppPageDeps = {}): GlassPage {
  const placeId = route.placeId;
  const element = el("div", "page page-app");
  const back = link("", "#/apps", "back-link");
  back.append(icon("back"), el("span", undefined, "Apps"));
  element.append(back);

  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const phone = phoneClient(ctx, deviceId, deps.fetch);

  let app: PhoneApp | null = null;
  // A skill's saved way (and a run's rooms on learned screens) lives on the Taught map.
  const learnedRoute = !!route.route?.length && route.route.every((id) => id.startsWith("page:"));
  let persona: Persona = learnedRoute ? "live" : "mapping";
  /** The owner's saved skills, for "Skills through here" (plan 23). Loaded once; older phones have none. */
  let skills: SkillView[] = [];
  let model: AtlasViewModel | null = null;
  let job: MappingJobView | null = null;
  let selection: { kind: "screen" | "edge"; id: string } | null = null;
  const now = deps.now ?? (() => Date.now());
  let lastMission: MappingMission | null = null;
  let missionBaseline: { places: number; doors: number } | null = null;
  let missionEvents: MissionEvent[] = [];
  let sheet: HTMLElement | null = null;
  let teaching = false;
  let teachNote = "";
  let destroyed = false;
  let loadSeq = 0;
  let zones: ZoneMap | null = null;
  let entryHint: string | null = null;
  let view: AtlasView = route.route?.length ? { kind: "all" } : { kind: "overview" };
  let cursorScreenId: string | null = null;
  let runs: RunSummary[] | null = null;
  let runsLoading: Promise<void> | null = null;

  const header = el("header", "app-header");
  const controls = el("div", "mapping-controls");
  const statusLine = el("div", "mapping-status");
  statusLine.setAttribute("role", "status");
  const personaSwitch = segmented<Persona>(
    [
      { id: "mapping", label: "Mapping pass" },
      { id: "live", label: "Your teaching" },
    ],
    persona,
    (id) => {
      if (id === persona) return;
      persona = id;
      personaSwitch.set(persona);
      void loadAtlas(true);
    },
  );
  const tabs = appTabs(placeId, "map");

  const crumb = el("div", "atlas-crumb");
  const coverage = el("div", "board-coverage");
  const bar = el("div", "board-bar");
  bar.append(crumb, personaSwitch.element, coverage, statusLine);

  const canvas = createAppMapCanvas({
    onSelectScreen: (id) => {
      if (id) selection = { kind: "screen", id };
      else if (selection?.kind === "screen") selection = null;
      renderInspector();
    },
    onSelectEdge: (id) => {
      if (id) selection = { kind: "edge", id };
      else if (selection?.kind === "edge") selection = null;
      renderInspector();
    },
  });
  const inspector = el("aside", "inspector");
  const boardArea = el("div", "board-area");
  const rail = el("nav", "atlas-rail");
  rail.setAttribute("aria-label", "Scenarios and zones");
  const board = el("section", "board atlas-board");
  board.append(rail, boardArea, inspector);
  const overview = createZoneOverview({
    onZone: (id) => setView({ kind: "zone", id }),
    onLane: (id) => setView({ kind: "lane", id }),
  });

  const setView = (next: AtlasView): void => {
    view = next;
    selection = null;
    renderBoard(true);
    renderInspector();
  };

  const missionBox = el("div", "mission-slot");
  element.append(header, tabs, bar, missionBox, board);

  // Run inspector v2: "Show on the map" lights up the rooms a run walked through, in order.
  if (route.route?.length) {
    canvas.setRoute(route.route);
    const banner = el("div", "route-banner");
    banner.setAttribute("role", "status");
    banner.append(
      el("span", "route-banner-dot"),
      el("span", undefined, route.skill
        ? `Showing a skill's saved way: ${route.route.length} ${route.route.length === 1 ? "place" : "places"}, numbered in order. The last one is where it works.`
        : `Showing the route of a run: ${route.route.length} ${route.route.length === 1 ? "room" : "rooms"}, numbered in order.`),
    );
    if (route.skill) banner.append(link("Back to Skills", `#/apps/${encodeURIComponent(placeId)}/skills`, "route-banner-link"));
    if (route.runId) banner.append(link("Back to the run", `#/runs/${encodeURIComponent(route.runId)}`, "route-banner-link"));
    const clear = link("Hide route", `#/apps/${encodeURIComponent(placeId)}/map`, "route-banner-link");
    banner.append(clear);
    element.insertBefore(banner, board);
  }

  const watcher: MappingWatcher = createMappingWatcher({
    ops: phone,
    onJob: (next) => {
      job = next;
      cursorScreenId = next.placeId === placeId && isActiveMapping(next) ? next.currentAtlasNodeId : null;
      canvas.setCursorScreenId(cursorScreenId);
      if (view.kind === "overview" && zones) overview.render(zones, { activeScreenId: cursorScreenId });
      renderControls();
      renderMission();
    },
    onAtlasChanged: (changed) => {
      if (changed === placeId && persona === "mapping") void loadAtlas(false);
      if (changed === placeId && !isActiveMapping(job)) void loadHeader();
    },
    onError: (error) => {
      statusLine.textContent = error instanceof Error ? error.message : String(error);
      statusLine.dataset.tone = "danger";
    },
    setTimer: deps.setTimer,
    clearTimer: deps.clearTimer,
  });

  const renderHeader = (): void => {
    const title = el("div", "app-title-row");
    const avatar = el("span", `app-avatar lg${placeId.startsWith("chrome:") ? " web" : ""}`, ((app?.label ?? placeId.split(":")[1] ?? "?")[0] ?? "?").toUpperCase());
    const names = el("div", "app-names");
    names.append(el("h1", "page-title", app?.label ?? placeId.slice(placeId.indexOf(":") + 1)));
    names.append(el("p", "page-subtitle", app?.kind === "chrome-origin" ? `Web place · ${app.origin ?? ""}` : app?.packageName ?? placeId));
    title.append(avatar, names, controls);
    const facts = el("div", "app-facts");
    if (app) {
      facts.append(chip(statusLabel(app), statusTone(app)));
      if (app.kind === "package") facts.append(chip(`Installed ${versionLabel(app.installedVersion)}`, "neutral"));
      for (const version of app.mappedVersions.slice(0, 4)) facts.append(chip(`Mapped on ${versionLabel(version)}`, "neutral"));
      if (app.needsRemap) facts.append(chip("Map learned on an older version", "warning"));
      if (app.installed === false) facts.append(chip("No longer installed", "neutral"));
      const scenarios = scenarioSummary(app);
      if (scenarios) facts.append(chip(scenarios.text, scenarios.tone));
    }
    setChildren(header, title, facts);
  };

  const renderControls = (): void => {
    const active = isActiveMapping(job) && job?.placeId === placeId;
    const otherPlaceBusy = isActiveMapping(job) && job?.placeId !== placeId;
    const buttons: HTMLElement[] = [];
    if (!active) {
      const start = actionButton(model && model.screens.length ? "Map again" : "Start mapping", { icon: "play", variant: "primary" });
      start.disabled = otherPlaceBusy || !placeId.startsWith("package:") || app?.installed === false;
      if (!placeId.startsWith("package:")) start.title = mappingErrorCopy("PLACE_NOT_LAUNCHABLE");
      else start.title = "Choose whose account and how long. The phone never sends, posts, pays, deletes or changes settings or security.";
      start.addEventListener("click", () => openSheet(lastMission ?? undefined));
      // Teach a door yourself: Follow Me on the phone, then Done here. Only for installed apps, never during a pass.
      const teach = actionButton(teaching ? "Done teaching" : "Teach on the phone", { icon: "hand", variant: teaching ? "primary" : "secondary" });
      teach.disabled = !placeId.startsWith("package:") || app?.installed === false || otherPlaceBusy;
      teach.title = "Show Cyclone the way on the phone; it becomes 'Your teaching' on this map.";
      teach.addEventListener("click", () => void (teaching ? finishTeaching() : beginTeaching()));
      buttons.push(start, teach);
    } else {
      const paused = job?.state === "paused" || job?.state === "human-control";
      const toggle = actionButton(paused ? "Resume" : "Pause", { icon: paused ? "play" : "pause" });
      toggle.addEventListener("click", () => void command(() => (paused ? watcher.resume() : watcher.pause()), false));
      const stop = actionButton("Stop", { icon: "stop", variant: "danger" });
      stop.addEventListener("click", () => void command(() => watcher.stop(), false));
      buttons.push(toggle, stop);
    }
    setChildren(controls, ...buttons);
    statusLine.dataset.tone = job?.state === "failed" || job?.state === "needs-secret" ? "warning" : "neutral";
    statusLine.textContent = otherPlaceBusy
      ? "The phone is mapping another app."
      : job && job.placeId === placeId && isActiveMapping(job)
        ? mappingStatusLine(job)
        : teachNote || (job && job.placeId === placeId ? mappingStatusLine(job) : "");
  };

  const closeSheet = (): void => {
    sheet?.remove();
    sheet = null;
  };

  const startMission = (mission: MappingMission): void => {
    closeSheet();
    lastMission = mission;
    missionBaseline = { places: model?.screens.length ?? 0, doors: model?.edges.length ?? 0 };
    missionEvents = [];
    void command(() => watcher.start(placeId, mission), true);
  };

  const openSheet = (initial?: MappingMission): void => {
    closeSheet();
    sheet = startSheet({
      appLabel: app?.label ?? placeId.slice(placeId.indexOf(":") + 1),
      initial,
      models: loadModels(ctx.client, deviceId).catch(() => null),
      onStart: startMission,
      onCancel: closeSheet,
    });
    element.append(sheet);
  };

  /** Live board header while the phone maps this app, the report once the pass ends. */
  const renderMission = (): void => {
    const mine = job && job.placeId === placeId ? job : null;
    if (mine && isActiveMapping(mine)) {
      const here = mine.currentAtlasNodeId ? model?.screens.find((x) => x.screenId === mine.currentAtlasNodeId) ?? null : null;
      const hereZone = here && zones ? zones.zones.find((z) => z.id === zones?.zoneOf.get(here.screenId))?.name ?? null : null;
      setChildren(missionBox, missionLive(mine, { appLabel: app?.label ?? placeId, now: now(), here: here?.label ?? null, hereZone, events: missionEvents }));
      return;
    }
    if (mine && missionBaseline && ["completed", "stopped", "failed"].includes(mine.state) && model) {
      const map = zones ?? deriveZones(model, entryHint);
      const report = coverageReport(model, map);
      setChildren(missionBox, missionReport(mine, {
        appLabel: app?.label ?? placeId,
        before: missionBaseline,
        after: { places: model.screens.length, doors: model.edges.length, zones: map.zones.length, blocked: report.blocked, unconfirmed: report.unconfirmed, scenariosKnown: report.scenariosKnown },
        onViewMap: () => setView({ kind: "overview" }),
        onMapAgain: () => openSheet(lastMission ?? undefined),
        onMapDeeper: () => startMission({ identity: lastMission?.identity ?? mine.identity ?? "own", budget: "30m", model: lastMission?.model, deeper: true }),
      }));
      return;
    }
    setChildren(missionBox);
  };

  /** New places and doors since the last load, for the live timeline. */
  const noteDiscoveries = (before: AtlasViewModel | null, after: AtlasViewModel): void => {
    if (!before || !isActiveMapping(job) || job?.placeId !== placeId) return;
    const places = new Set(before.screens.map((x) => x.screenId));
    const doors = new Set(before.edges.map((x) => x.edgeId));
    const at = now();
    const fresh: MissionEvent[] = [
      ...after.screens.filter((x) => !places.has(x.screenId)).map((x) => ({ at, kind: "place" as const, text: `New place · ${x.label}` })),
      ...after.edges.filter((x) => !doors.has(x.edgeId)).map((x) => ({ at, kind: "door" as const, text: `New door · ${x.actionHint}${x.risk.danger ? " (blocked)" : ""}` })),
    ];
    missionEvents = [...fresh.reverse(), ...missionEvents].slice(0, 30);
  };

  const beginTeaching = async (): Promise<void> => {
    try {
      await startTeaching(ctx.client, deviceId, `Teach a route in ${app?.label ?? placeId}`);
      teaching = true;
      teachNote = "Follow Me is on. Show the way on the phone, then press Done teaching here.";
    } catch (error) {
      teachNote = error instanceof Error ? error.message : String(error);
    }
    renderControls();
  };

  const finishTeaching = async (): Promise<void> => {
    try {
      const summary = await stopTeaching(ctx.client, deviceId);
      teaching = false;
      teachNote = summary ? `Taught: ${summary}` : "Teaching saved on the phone.";
      persona = "live";
      personaSwitch.set(persona);
      void loadAtlas(true);
    } catch (error) {
      teachNote = error instanceof Error ? error.message : String(error);
    }
    renderControls();
  };

  const command = async (run: () => Promise<void>, switchToMapping: boolean): Promise<void> => {
    try {
      if (switchToMapping && persona !== "mapping") {
        persona = "mapping";
        personaSwitch.set(persona);
        void loadAtlas(true);
      }
      await run();
    } catch (error) {
      const code = (error as { code?: string })?.code ?? "";
      statusLine.textContent = mappingErrorCopy(code);
      statusLine.dataset.tone = "danger";
    }
  };

  const renderInspector = (): void => {
    if (!model) {
      setChildren(inspector);
      return;
    }
    const state: InspectorState =
      selection?.kind === "edge" ? inspectorStateForEdge(model, selection.id) : inspectorState(model, selection?.kind === "screen" ? selection.id : null);
    const nodes = inspectorNodes(state, model, {
      zoneName: state.kind === "screen" && selection ? zones?.zones.find((z) => z.id === zones?.zoneOf.get(selection!.id))?.name ?? null : null,
      onDoor: (edgeId) => {
        selection = { kind: "edge", id: edgeId };
        canvas.setSelectedEdgeId(edgeId);
        renderInspector();
      },
    });
    if (state.kind === "edge" && state.fromScreenId && state.toScreenId) nodes.push(doorEvidence(state.fromScreenId, state.toScreenId));
    if (state.kind === "screen" && selection?.kind === "screen") {
      const through = skillsThrough(skills, selection.id);
      if (through.length) nodes.splice(nodes.length - 1, 0, skillsHere(through, selection.id));
    }
    if (state.kind === "empty" && zones && view.kind === "overview") nodes.splice(1, 1, el("p", "muted", "Pick a zone to open it, or a scenario lane above the map. Rooms and doors show up here when you select them."));
    setChildren(inspector, ...nodes);
  };

  /** Skills whose saved way passes this place; the last stop of a way is where the skill works. */
  const skillsHere = (through: SkillView[], screenId: string): HTMLElement => {
    const box = el("div", "skills-here");
    box.append(el("h3", "inspector-section", `Skills through here (${through.length})`));
    for (const skill of through) {
      const works = skill.route[skill.route.length - 1]?.screenId === screenId;
      const row = el("a", "skill-here");
      row.href = routeHref({ name: "app", placeId, tab: "map", route: skillScreens(skill), skill: skill.skillId });
      row.append(el("span", "skill-here-name", skill.name), chip(works ? "Works here" : "Passes here", works ? "accent" : "neutral"),
        chip(GROUND_LABEL[skill.ground], GROUND_TONE[skill.ground]));
      box.append(row);
    }
    return box;
  };

  /** Evidence for a door: the runs whose route walked it, newest first, each with a way into its replay. */
  const doorEvidence = (from: string, to: string): HTMLElement => {
    const box = el("div", "door-evidence");
    box.append(el("h3", "inspector-section", "Evidence"));
    const fill = (): void => {
      if (!runs) {
        box.append(el("p", "muted", "Loading the runs that used this door…"));
        return;
      }
      const used = runsThroughDoor(runs, from, to).sort((a, b) => b.startedAt - a.startedAt);
      if (!used.length) {
        box.append(el("p", "muted", "No run has walked this door yet. It came from a mapping pass or from teaching."));
        return;
      }
      const ok = used.filter((r) => r.status === "completed").length;
      box.append(el("p", "evidence-summary", `Walked in ${plural(used.length, "run")} · ${ok} finished`));
      for (const run of used.slice(0, 5)) {
        const row = link("", `#/runs/${encodeURIComponent(run.runId)}`, "evidence-run");
        row.append(chip(runStatusLabel(run.status), runStatusTone(run.status)), el("span", "evidence-goal", run.goal), el("span", "muted evidence-when", relativeTime(run.startedAt)));
        box.append(row);
      }
    };
    fill();
    if (!runs) {
      runsLoading ??= listRuns(ctx.client, deviceId, "all", 200).then((list) => { runs = list; }).catch(() => { runs = []; });
      void runsLoading.then(() => {
        if (destroyed || selection?.kind !== "edge") return;
        renderInspector();
      });
    }
    return box;
  };

  const viewLabel = (): string => {
    if (!zones) return "All places";
    switch (view.kind) {
      case "overview":
        return "Overview";
      case "all":
        return "All places";
      case "lane":
        return zones.lanes.find((l) => l.id === (view as { id: LaneId }).id)?.label ?? "Scenario";
      case "zone":
        return zones.zones.find((z) => z.id === (view as { id: string }).id)?.name ?? "Zone";
    }
  };

  const renderRail = (): void => {
    if (!model || !model.screens.length || !zones) {
      setChildren(rail);
      rail.hidden = true;
      setChildren(crumb);
      return;
    }
    rail.hidden = false;
    const item = (label: string, count: number | null, active: boolean, onClick: () => void, extra?: HTMLElement): HTMLElement => {
      const node = el("button", `rail-item${active ? " active" : ""}`) as HTMLButtonElement;
      node.type = "button";
      node.append(el("span", "rail-label", label));
      if (extra) node.append(extra);
      if (count != null) node.append(el("span", "rail-count", String(count)));
      node.addEventListener("click", onClick);
      return node;
    };
    const nodes: HTMLElement[] = [item("Overview", null, view.kind === "overview", () => setView({ kind: "overview" }))];
    nodes.push(el("div", "rail-heading", "Scenarios"));
    for (const lane of zones.lanes) {
      nodes.push(item(lane.label, lane.screenIds.length, view.kind === "lane" && view.id === lane.id, () => setView({ kind: "lane", id: lane.id })));
    }
    nodes.push(el("div", "rail-heading", "Zones"));
    if (!zones.zones.length) nodes.push(el("p", "muted rail-note", "No signed-in places yet."));
    for (const zone of zones.zones) {
      const dot = el("span", `rail-dot ${zone.confidence == null ? "" : zone.confidence >= 0.85 ? "high" : zone.confidence >= 0.6 ? "mid" : "low"}`);
      dot.title = zone.confidence == null ? "Confidence unknown" : `Confidence ${Math.round(zone.confidence * 100)}%`;
      nodes.push(item(zone.name, zone.screenIds.length, view.kind === "zone" && view.id === zone.id, () => setView({ kind: "zone", id: zone.id }), dot));
    }
    nodes.push(el("div", "rail-heading", "Everything"));
    nodes.push(item("All places", model.screens.length, view.kind === "all", () => setView({ kind: "all" })));
    setChildren(rail, ...nodes);

    const name = app?.label ?? placeId.slice(placeId.indexOf(":") + 1);
    const parts: HTMLElement[] = [el("span", "crumb-app", name), el("span", "crumb-sep", "›"), el("span", "crumb-here", viewLabel())];
    if (view.kind !== "overview") {
      const back = el("button", "btn btn-ghost crumb-back", "Back to overview") as HTMLButtonElement;
      back.type = "button";
      back.addEventListener("click", () => setView({ kind: "overview" }));
      parts.push(back);
    }
    setChildren(crumb, ...parts);
  };

  const renderBoard = (fit: boolean): void => {
    if (!model) return;
    coverage.textContent = model.screens.length
      ? `${plural(model.coverage.screens, "place")} · ${plural(model.coverage.doors, "door")}${model.coverage.dark ? ` · ${model.coverage.dark} unconfirmed` : ""} · verified ${relativeTime(model.lastVerifiedAt)}`
      : "";
    if (!model.screens.length) {
      zones = null;
      renderRail();
      setChildren(
        boardArea,
        emptyState({
          icon: "map",
          title: persona === "mapping" ? "Not mapped yet" : "Nothing taught yet",
          body:
            persona === "mapping"
              ? "Start mapping and the phone walks this app's tabs, menus and settings. It never pays, sends, deletes or grants permissions. Rooms appear here as it goes."
              : "Rooms you show Cyclone with Follow Me on the phone appear here. They stay separate from the mapping pass.",
        }),
      );
      return;
    }
    zones = deriveZones(model, entryHint);
    if (view.kind === "zone" && !zones.zones.some((z) => z.id === (view as { id: string }).id)) view = { kind: "overview" };
    if (view.kind === "overview" && !zones.zones.length) view = { kind: "all" };
    renderRail();
    if (view.kind === "overview") {
      if (overview.element.parentElement !== boardArea) setChildren(boardArea, overview.element);
      overview.render(zones, { activeScreenId: cursorScreenId });
      return;
    }
    const shown =
      view.kind === "zone"
        ? layeredLayout(zoneSubModel(model, zones, view.id), zones.zones.find((z) => z.id === (view as { id: string }).id)?.baseScreenId ?? zones.entryScreenId)
        : view.kind === "lane"
          ? layeredLayout(laneSubModel(model, zones, view.id), null)
          : layoutCollapsed(model)
            ? layeredLayout(model, zones.entryScreenId)
            : model;
    if (canvas.element.parentElement !== boardArea) setChildren(boardArea, canvas.element);
    canvas.setViewModel(shown, { fit });
  };

  const loadAtlas = async (fit: boolean): Promise<void> => {
    const seq = ++loadSeq;
    if (!model || fit) setChildren(boardArea, loadingState("Asking the phone for this map…"));
    try {
      // The App Manual's names (alpha.60) ride along for the mapping map of an app; an older phone just has none.
      const manual = placeId.startsWith("package:") && persona === "mapping"
        ? loadDictionary(ctx.client, deviceId, placeId).catch(() => null)
        : Promise.resolve(null);
      const document = await phone.get(placeId, persona);
      const names = await manual;
      if (destroyed || seq !== loadSeq) return;
      const previous = model;
      model = applyManualNames(toViewModel(toMapsDocument(document)), names);
      noteDiscoveries(previous, model);
      if (selection?.kind === "screen" && !model.screens.some((s) => s.screenId === selection?.id)) selection = null;
      renderBoard(fit);
      renderInspector();
      renderControls();
      renderMission();
    } catch (error) {
      if (destroyed || seq !== loadSeq) return;
      model = null;
      renderInspector();
      setChildren(boardArea, errorState("Couldn't load this map", { message: error instanceof Error ? error.message : String(error) }, () => void loadAtlas(true)));
    }
  };

  const loadHeader = async (): Promise<void> => {
    try {
      const catalog = await loadApps(ctx.client, deviceId);
      if (destroyed) return;
      app = catalog.apps.find((entry) => entry.placeId === placeId) ?? null;
    } catch {
      app = null; // Older phones without apps.list still get the board.
    }
    renderHeader();
    renderControls();
  };

  renderHeader();
  renderControls();
  void (async () => {
    await loadHeader();
    if (destroyed) return;
    // Show the persona that has rooms: the mapping pass by default, the user's own teaching otherwise.
    const loaded = app as PhoneApp | null; // assigned by loadHeader(); TS cannot see through the closure
    const mapping = loaded?.personas.find((p) => p.persona === "mapping");
    const live = loaded?.personas.find((p) => p.persona === "live");
    if (!learnedRoute && (!mapping || mapping.rooms === 0) && live && live.rooms > 0) {
      persona = "live";
      personaSwitch.set(persona);
    }
    await loadAtlas(true);
    if (!destroyed) await watcher.attach().catch(() => undefined);
    if (!destroyed && placeId.startsWith("package:")) {
      const list = await loadSkills(ctx.client, deviceId).catch(() => null);
      if (!destroyed && list) {
        skills = list.skills.filter((skill) => skill.placeId === placeId);
        // selection is set by board clicks; TS cannot see through those closures.
        if ((selection as { kind: string } | null)?.kind === "screen") renderInspector();
      }
    }
    // The scenarios' entry place anchors the zones when the phone knows it.
    if (!destroyed && placeId.startsWith("package:")) {
      const scenarios = await getScenarios(ctx.client, deviceId, placeId, "mapping").catch(() => null);
      if (!destroyed && scenarios?.entryScreenId && scenarios.entryScreenId !== entryHint) {
        entryHint = scenarios.entryScreenId;
        renderBoard(false);
      }
    }
  })();

  return {
    element,
    destroy() {
      destroyed = true;
      closeSheet();
      watcher.dispose();
      canvas.destroy();
    },
  };
}

function laneSubModel(model: AtlasViewModel, zones: ZoneMap, laneId: LaneId): AtlasViewModel {
  const ids = new Set(zones.lanes.find((l) => l.id === laneId)?.screenIds ?? []);
  return { ...model, screens: model.screens.filter((s) => ids.has(s.screenId)), edges: model.edges.filter((e) => ids.has(e.fromScreenId) && ids.has(e.toScreenId)) };
}

function confidenceBar(value: number): HTMLElement {
  const bar = el("span", "conf-bar");
  const pct = Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
  const fill = el("span", `conf-fill ${pct >= 0.85 ? "high" : pct >= 0.6 ? "mid" : "low"}`);
  fill.style.width = `${Math.round(pct * 100)}%`;
  bar.append(fill);
  return bar;
}

function inspectorNodes(state: InspectorState, model: AtlasViewModel, extra: { zoneName?: string | null; onDoor?: (edgeId: string) => void } = {}): HTMLElement[] {
  if (state.kind === "empty") {
    return [
      el("h2", "inspector-title", "Inspector"),
      el("p", "muted", model.screens.length ? "Click a room or a door on the board to see what Cyclone knows about it." : "Rooms and doors appear here once the app is mapped."),
    ];
  }
  const nodes: HTMLElement[] = [el("div", "inspector-kicker", state.kind === "edge" ? "Door" : "Place"), el("h2", "inspector-title", state.title)];
  if (state.subtitle) nodes.push(el("p", "muted", state.subtitle));
  const facts = el("dl", "kv");
  const add = (key: string, value: string): void => {
    facts.append(el("dt", "kv-key", key), el("dd", "kv-value", value));
  };
  if (state.kind === "edge") {
    add("From", state.fromLabel ?? "—");
    add("To", state.toLabel ?? "—");
    if (state.actionHint) add("Action", state.actionHint);
  } else {
    add("Purpose", state.purpose ?? "—");
    if (extra.zoneName) add("Zone", extra.zoneName);
    if (state.tone) add("State", roomStatusLabel(state.tone));
  }
  add("Confidence", state.confidence != null ? `${Math.round(state.confidence * 100)}%` : "unknown");
  add("Verified", relativeTime(state.lastVerifiedAt ?? null));
  nodes.push(facts);

  if (state.kind === "screen") {
    nodes.push(el("h3", "inspector-section", "Facts on this screen"));
    if (!state.factSlots.length) nodes.push(el("p", "muted", "None recorded."));
    for (const slot of state.factSlots) {
      const row = el("div", "slot");
      row.append(el("span", "slot-name", slot.displayLabel), el("span", "slot-value", slot.maskedValue));
      if (slot.description) row.append(el("span", "slot-copy muted", slot.description));
      nodes.push(row);
    }
    nodes.push(el("h3", "inspector-section", `Doors from this place (${state.doors.length})`));
    if (!state.doors.length) nodes.push(el("p", "muted", "No doors mapped from this place yet."));
    for (const door of [...state.doors].sort((a, b) => b.confidence - a.confidence)) {
      const row = el("button", `door door-row${door.dark ? " dark" : ""}${door.risk.danger ? " danger" : ""}`) as HTMLButtonElement;
      row.type = "button";
      const names = el("span", "door-names");
      names.append(el("span", "door-name", door.actionHint), el("span", "door-to muted", `→ ${door.toLabel}${door.risk.danger ? " · blocked for mapping" : door.dark ? " · not confirmed" : ""}`));
      row.append(names, confidenceBar(door.confidence), el("span", "door-pct", Number.isFinite(door.confidence) ? `${Math.round(door.confidence * 100)}%` : "—"));
      row.addEventListener("click", () => extra.onDoor?.(door.edgeId));
      nodes.push(row);
    }
    nodes.push(el("p", "muted inspector-note", "Structure only: Cyclone keeps what a place is and its doors, never what was on the screen."));
  }
  return nodes;
}
