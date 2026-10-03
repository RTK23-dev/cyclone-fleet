/**
 * App → Scenarios and App → Versions (V5 plan 04). The phone computes both: scenarios are known routes from the app's
 * entry room to each destination, with health from the runs that went there; versions say which app versions the map
 * was learned on and which doors may be stale after an update. Glass lays them out and links into the Map and Runs.
 */
import { dictionaryView, type DictionaryViewState } from "../ui/dictionaryView.js";
import { abilitiesView, tryItGoal } from "../ui/abilitiesView.js";
import { loadManual, manualFileName } from "../services/manual.js";
import { editDictionary, loadDictionary, type AppDictionaryView } from "../services/dictionary.js";
import type { GlassContext } from "../app.js";
import type { AppTab, Route } from "../core/router.js";
import { loadApps, scenarioSummary, statusLabel as appStatusLabel, statusTone as appStatusTone, versionLabel, type PhoneApp } from "../services/apps.js";
import { GatewayError } from "../services/gateway.js";
import {
  getScenarios,
  getVersions,
  healthLabel,
  kindLabel,
  staleDays,
  healthTone,
  versionText,
  type AppVersions,
  type Scenario,
  type ScenarioList,
} from "../services/knowledge.js";
import { listRuns, roomLabel, statusLabel as runStatusLabel, statusTone as runStatusTone, type RunSummary } from "../services/runs.js";
import { phoneClient } from "../services/phone.js";
import { toMapsDocument } from "../maps/atlasDocument.js";
import { toViewModel, type AtlasViewModel, type Persona } from "../maps/atlasViewModel.js";
import { coverageReport, deriveZones } from "../maps/zones.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, searchInput, segmented, statTile } from "../ui/components.js";
import { runRow, runsError } from "./runsPage.js";
import { getKnowledge, type VaultSlot } from "../services/knowledgeSummary.js";
import { GROUND_LABEL, GROUND_TONE, loadSkills, skillScreens, type SkillView } from "../services/skills.js";
import { runListing } from "../services/market.js";
import { appIssues } from "../services/issues.js";
import { el, link, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { icon } from "../ui/icons.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

type KnowledgeTab = Exclude<AppTab, "map">;

export function appTabs(placeId: string, active: AppTab): HTMLElement {
  const tabs = el("nav", "tabs");
  const items: Array<[AppTab, string]> = [["map", "Map"], ["coverage", "Coverage"]];
  if (placeId.startsWith("package:")) items.push(["abilities", "Abilities"], ["skills", "Skills"], ["runs", "Runs"], ["versions", "Changes"], ["scenarios", "Scenarios"], ["screens", "Screens"], ["dictionary", "Dictionary"], ["issues", "Issues"]);
  else items.push(["screens", "Screens"]);
  for (const [tab, label] of items) {
    if (tab === active) {
      const current = el("span", "tab active", label);
      current.setAttribute("aria-current", "page");
      tabs.append(current);
    } else {
      tabs.append(link(label, `#/apps/${encodeURIComponent(placeId)}/${tab}`, "tab"));
    }
  }
  return tabs;
}

export function createAppKnowledgePage(
  ctx: GlassContext,
  route: Extract<Route, { name: "app" }> & { tab: KnowledgeTab },
  deps: { fetch?: typeof fetch } = {},
): GlassPage {
  const placeId = route.placeId;
  const element = el("div", "page page-app page-knowledge");
  const back = link("", "#/apps", "back-link");
  back.append(icon("back"), el("span", undefined, "Apps"));
  element.append(back);
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const controller = new AbortController();
  const header = el("header", "app-header");
  const body = el("div", "knowledge-body");
  element.append(header, appTabs(placeId, route.tab), body);
  renderHeader(null);
  setChildren(body, loadingState(`Loading ${route.tab} from the phone…`));
  let scenarioView: "cards" | "board" = "cards";
  let screensPersona: Persona = "mapping";
  let scenariosPersona: Persona = "mapping";

  function renderHeader(app: PhoneApp | null): void {
    const title = el("div", "app-title-row");
    const label = app?.label ?? placeId.slice(placeId.indexOf(":") + 1);
    const avatar = el("span", "app-avatar lg", (label[0] ?? "?").toUpperCase());
    const names = el("div", "app-names");
    names.append(el("h1", "page-title", label), el("p", "page-subtitle", app?.packageName ?? placeId));
    title.append(avatar, names);
    if (app) {
      const facts = el("div", "app-facts");
      if (app.installedVersion) facts.append(chip(`Installed ${versionLabel(app.installedVersion)}`, "neutral"));
      facts.append(chip(appStatusLabel(app), appStatusTone(app)));
      const scenarios = scenarioSummary(app);
      if (scenarios) facts.append(chip(scenarios.text, scenarios.tone));
      names.append(facts);
    }
    setChildren(header, title);
  }

  const showMap = (rooms: string[]): void => ctx.navigate({ name: "app", placeId, tab: "map", route: rooms });

  const load = async (): Promise<void> => {
    void loadApps(ctx.client, deviceId)
      .then((catalog) => renderHeader(catalog.apps.find((app) => app.placeId === placeId) ?? null))
      .catch(() => undefined);
    try {
      if (route.tab === "scenarios") renderScenarios(await getScenarios(ctx.client, deviceId, placeId, scenariosPersona, controller.signal));
      else if (route.tab === "versions") renderVersions(await getVersions(ctx.client, deviceId, placeId, controller.signal));
      else if (route.tab === "screens") await loadScreens();
      else if (route.tab === "coverage") await loadCoverage();
      else if (route.tab === "issues") await loadIssues();
      else if (route.tab === "skills") await loadSkillsTab();
      else if (route.tab === "dictionary") await loadDictionaryTab();
      else if (route.tab === "abilities") await loadAbilitiesTab();
      else await loadRuns();
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      setChildren(body, route.tab === "runs" ? runsError(error, () => void load()) : knowledgeError(error, () => void load()));
    }
  };

  async function loadIssues(): Promise<void> {
    const signal = controller.signal;
    const [versions, scenarios, runs, rooms] = await Promise.all([
      getVersions(ctx.client, deviceId, placeId, signal).catch(() => null),
      getScenarios(ctx.client, deviceId, placeId, "mapping", signal).catch(() => null),
      listRuns(ctx.client, deviceId, "failed", 100, signal).catch(() => null),
      phoneClient(ctx, deviceId, deps.fetch)
        .get(placeId as never, "mapping")
        .then((document) => toViewModel(toMapsDocument(document)).screens.map((screen) => ({ screenId: screen.screenId, confidence: screen.confidence })))
        .catch(() => null),
    ]);
    if (signal.aborted) return;
    if (!versions && !scenarios && !runs && !rooms) throw new GatewayError("PHONE_UNAVAILABLE", "The phone did not answer.", 0, true);
    const issues = appIssues({ placeId, versions, scenarios, runs, rooms });
    if (!issues.length) {
      setChildren(body, emptyState({ icon: "map", tone: "success", title: "No open issues", body: "The map is current, no scenario is failing and no recent run broke in this app." }));
      return;
    }
    const counts = { critical: 0, warning: 0, info: 0 };
    issues.forEach((issue) => counts[issue.severity]++);
    const stats = el("div", "stats stats-4");
    stats.append(
      statTile("Critical", String(counts.critical), counts.critical ? "danger" : "neutral"),
      statTile("Warnings", String(counts.warning), counts.warning ? "warning" : "neutral"),
      statTile("Notes", String(counts.info)),
      statTile("Open issues", String(issues.length)),
    );
    const list = el("div", "issue-list");
    for (const issue of issues) {
      const node = card(`issue-card issue-${issue.severity}`);
      node.dataset.issueId = issue.id;
      const top = el("div", "scenario-top");
      top.append(el("h2", "scenario-title", issue.title), chip(issue.severity === "critical" ? "Critical" : issue.severity === "warning" ? "Warning" : "Note", issue.severity === "critical" ? "danger" : issue.severity === "warning" ? "warning" : "neutral"));
      const act = actionButton(issue.action.label, { icon: issue.action.kind === "run" ? "runs" : "map" });
      const action = issue.action;
      act.addEventListener("click", () => {
        if (action.kind === "run") ctx.navigate({ name: "run", runId: action.runId });
        else if (action.kind === "map") ctx.navigate({ name: "app", placeId, tab: "map", route: action.rooms });
        else ctx.navigate({ name: "app", placeId, tab: action.tab });
      });
      node.append(top, el("p", "muted", issue.detail), act);
      list.append(node);
    }
    setChildren(body, stats, list);
  }

  /**
   * Skills (plan 23): the owner's saved skills that work in this app, each with its health on the map and the saved way
   * to where it works. Show on the map draws that way on the Taught map; Run starts it on the phone like any Ask.
   */
  const dictionaryState: DictionaryViewState = { showHidden: false, renaming: null };
  let dictionary: AppDictionaryView | null = null;

  async function loadDictionaryTab(): Promise<void> {
    dictionary = await loadDictionary(ctx.client, deviceId, placeId, controller.signal);
    if (controller.signal.aborted) return;
    paintDictionary();
  }

  function paintDictionary(message?: string): void {
    if (!dictionary) return;
    const view = dictionaryView(dictionary, dictionaryState, {
      edit: (action, id, extra) => {
        void editDictionary(ctx.client, deviceId, placeId, action, id, extra)
          .then((next) => { dictionary = next; paintDictionary(); })
          .catch((error: unknown) => paintDictionary((error as { message?: string })?.message ?? "The phone refused that change."));
      },
    }, () => paintDictionary());
    setChildren(body, message ? el("p", "form-error", message) : null, view);
  }

  /** Abilities (plan 36 §10): what you can do in the app, the self-quiz, Map deeper, Try it and Export. */
  let abilityQuery = "";
  async function loadAbilitiesTab(note?: string): Promise<void> {
    const view = await loadManual(ctx.client, deviceId, placeId, abilityQuery, controller.signal);
    if (controller.signal.aborted) return;
    setChildren(body, abilitiesView(view, abilityQuery, {
      search: (query) => {
        abilityQuery = query.trim();
        void loadAbilitiesTab().catch((error) => setChildren(body, knowledgeError(error, () => void load())));
      },
      mapDeeper: () => {
        void phoneClient(ctx, deviceId, deps.fetch).mappingStart(placeId as never, { identity: "own", budget: "30m", deeper: true })
          .then(() => ctx.navigate({ name: "app", placeId, tab: "map" }))
          .catch((error: unknown) => void loadAbilitiesTab(error instanceof Error ? error.message : String(error)));
      },
      tryIt: (ability) => {
        void phoneClient(ctx, deviceId, deps.fetch).askStart(tryItGoal(ability, view.appLabel || placeId))
          .then(() => void loadAbilitiesTab(`Started on the phone: ${ability.name}. Follow it on the Phone page.`))
          .catch((error: unknown) => void loadAbilitiesTab(error instanceof Error ? error.message : String(error)));
      },
      exportManual: () => downloadText(manualFileName(view), view.markdown),
    }, note));
  }

  async function loadSkillsTab(): Promise<void> {
    const list = await loadSkills(ctx.client, deviceId, controller.signal);
    if (controller.signal.aborted) return;
    const here = list.skills.filter((skill) => skill.placeId === placeId);
    const loose = list.skills.filter((skill) => !skill.placeId).length;
    if (!here.length) {
      setChildren(body, emptyState({
        icon: "star",
        title: "No skills in this app yet",
        body: "Finish a run in this app on the phone and press Save skill. Cyclone remembers where the skill works on the map and keeps it there.",
      }), loose ? el("p", "muted table-note", `${loose} saved ${loose === 1 ? "skill is" : "skills are"} not tied to an app yet (saved before skills were grounded).`) : null);
      return;
    }
    const counts = { grounded: 0, partial: 0, "needs-recheck": 0, "not-grounded": 0 };
    here.forEach((skill) => counts[skill.ground]++);
    const stats = el("div", "stats stats-4");
    stats.append(
      statTile("Skills", String(here.length)),
      statTile("Route known", String(counts.grounded), "success"),
      statTile("Destination known", String(counts.partial), "accent"),
      statTile("Needs re-check", String(counts["needs-recheck"]), counts["needs-recheck"] ? "warning" : "neutral"),
    );
    const cards = el("div", "skill-list");
    for (const skill of here) cards.append(skillCard(skill));
    setChildren(body, stats, cards, el("p", "muted table-note",
      "A skill's way is advice: every run walks it checking each screen, and a finished run moves the skill to where it worked. Needs re-check means the map no longer knows where it works; the next run finds the way again."));
  }

  function skillCard(skill: SkillView): HTMLElement {
    const node = card("skill-card");
    node.dataset.skillId = skill.skillId;
    const head = el("div", "skill-head");
    head.append(icon("star", "skill-glyph"), el("h3", "skill-name", skill.name), chip(GROUND_LABEL[skill.ground], GROUND_TONE[skill.ground]));
    node.append(head, el("p", "muted skill-detail", skill.detail));
    if (skill.route.length) {
      const way = el("ol", "skill-way");
      skill.route.forEach((point, i) => {
        if (i > 0) way.append(el("li", "skill-arrow", "→"));
        const item = el("li", `skill-stop${i === skill.route.length - 1 ? " destination" : ""}${point.screenId ? "" : " unknown"}`);
        item.append(el("span", "skill-stop-index", String(i + 1)), el("span", undefined, point.title));
        way.append(item);
      });
      node.append(way);
    }
    const facts = el("div", "skill-facts muted");
    facts.append(el("span", undefined, skill.finishSteps ? `About ${skill.finishSteps} step${skill.finishSteps === 1 ? "" : "s"} of work at the destination` : "Work happens where the way ends"));
    if (skill.savedAt) facts.append(el("span", undefined, `grounded ${relativeTime(skill.savedAt)}`));
    node.append(facts);
    const actions = el("div", "skill-actions");
    const screens = skillScreens(skill);
    if (screens.length) {
      const show = actionButton("Show on the map", { icon: "map" });
      show.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map", route: screens, skill: skill.skillId }));
      actions.append(show);
    }
    const run = actionButton("Run on the phone", { icon: "play", variant: "primary" });
    const note = el("span", "muted skill-note");
    run.addEventListener("click", async () => {
      run.disabled = true;
      try {
        await runListing(ctx.client, deviceId, skill.skillId, {});
        note.textContent = "Started on the phone. Follow it on the Phone page.";
      } catch (error) {
        run.disabled = false;
        note.textContent = error instanceof Error ? error.message : String(error);
      }
    });
    actions.append(run, note);
    node.append(actions);
    return node;
  }

  /** Coverage (plan 22 §4.2): confidence, freshness, unconfirmed and blocked, per zone. Never "% of the app". */
  async function loadCoverage(): Promise<void> {
    const signal = controller.signal;
    const [document, versions, scenarios] = await Promise.all([
      phoneClient(ctx, deviceId, deps.fetch).get(placeId as never, "mapping"),
      placeId.startsWith("package:") ? getVersions(ctx.client, deviceId, placeId, signal).catch(() => null) : Promise.resolve(null),
      placeId.startsWith("package:") ? getScenarios(ctx.client, deviceId, placeId, "mapping", signal).catch(() => null) : Promise.resolve(null),
    ]);
    if (signal.aborted) return;
    const model = toViewModel(toMapsDocument(document));
    if (!model.screens.length) {
      setChildren(body, emptyState({ icon: "map", title: "Nothing mapped yet", body: "Start a mapping pass from the Map tab, or press Learn on a run. Coverage appears as soon as Cyclone knows a place." }));
      return;
    }
    const zones = deriveZones(model, scenarios?.entryScreenId ?? null);
    const report = coverageReport(model, zones, versions);
    const pct = (value: number | null): string => (value == null ? "—" : `${Math.round(value * 100)}%`);
    const tiles = el("div", "stats stats-4 coverage-stats");
    tiles.append(
      statTile("Scenarios known", `${report.scenariosKnown} / 3`, report.scenariosKnown === 3 ? "success" : "neutral"),
      statTile("Zones", String(report.zones)),
      statTile("Places", String(report.places)),
      statTile("Doors", String(report.doors)),
      statTile("Confidence", pct(report.confidence), (report.confidence ?? 0) >= 0.85 ? "success" : (report.confidence ?? 0) >= 0.6 ? "warning" : "danger"),
      statTile("On the installed version", pct(report.currentShare), report.currentShare == null ? "neutral" : report.currentShare >= 0.9 ? "success" : "warning"),
      statTile("Unconfirmed", String(report.unconfirmed), report.unconfirmed ? "warning" : "success"),
      statTile("Blocked for mapping", String(report.blocked), report.blocked ? "danger" : "neutral"),
    );
    const lanes = card("coverage-lanes");
    lanes.append(el("h2", "card-title", "Scenarios"));
    for (const lane of zones.lanes) {
      const row = el("div", "coverage-row");
      const known = lane.screenIds.length > 0;
      row.append(el("span", "coverage-name", lane.label), coverageBar(known ? 1 : 0, known ? "high" : "none"),
        el("span", "coverage-num", known ? `${lane.screenIds.length} place${lane.screenIds.length === 1 ? "" : "s"}` : "Not mapped yet"));
      lanes.append(row);
    }
    const table = card("coverage-zones");
    table.append(el("h2", "card-title", "Zones"));
    const head = el("div", "coverage-row coverage-head");
    head.append(el("span", "coverage-name", "Zone"), el("span", undefined, "Confidence"), el("span", "coverage-num", "Places"), el("span", "coverage-num", "Doors"),
      el("span", "coverage-num", "Unconfirmed"), el("span", "coverage-num", "Blocked"));
    table.append(head);
    for (const zone of zones.zones) {
      const row = el("div", "coverage-row coverage-zone");
      const tone = zone.confidence == null ? "none" : zone.confidence >= 0.85 ? "high" : zone.confidence >= 0.6 ? "mid" : "low";
      const bar = el("span", "coverage-conf");
      bar.append(coverageBar(zone.confidence ?? 0, tone), el("span", "coverage-pct", pct(zone.confidence)));
      row.append(el("span", "coverage-name", zone.name), bar, el("span", "coverage-num", String(zone.screenIds.length)), el("span", "coverage-num", String(zone.doors)),
        el("span", `coverage-num${zone.unconfirmed ? " warn" : ""}`, String(zone.unconfirmed)), el("span", `coverage-num${zone.blocked ? " danger" : ""}`, String(zone.blocked)));
      table.append(row);
    }
    const note = el("p", "muted coverage-note",
      "Confidence, not a percentage of the app: Cyclone cannot know how big an app really is. Unconfirmed = known but not yet confirmed by a walk. " +
      "Blocked = doors the mapper refused because they could pay, send, delete, change a setting or touch security.");
    setChildren(body, tiles, lanes, table, note);
  }

  function coverageBar(value: number, tone: string): HTMLElement {
    const bar = el("span", "conf-bar coverage-bar");
    const fill = el("span", `conf-fill ${tone === "none" ? "" : tone}`);
    fill.style.width = `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`;
    bar.append(fill);
    return bar;
  }

  async function loadScreens(): Promise<void> {
    const document = await phoneClient(ctx, deviceId, deps.fetch).get(placeId as never, screensPersona);
    if (controller.signal.aborted) return;
    renderScreens(toViewModel(toMapsDocument(document)));
  }

  function renderScreens(model: AtlasViewModel): void {
    const toggle = segmented<Persona>(
      [
        { id: "mapping", label: "Mapping pass" },
        { id: "live", label: "Your teaching" },
      ],
      screensPersona,
      (id) => {
        screensPersona = id;
        setChildren(body, loadingState("Loading screens from the phone…"));
        void loadScreens().catch((error) => setChildren(body, knowledgeError(error, () => void load())));
      },
    );
    if (!model.screens.length) {
      setChildren(body, toggle.element, emptyState({ icon: "map", title: "No screens known yet", body: "Map the app, or show Cyclone around it with Follow Me on the phone." }));
      return;
    }
    const doorsOut = new Map<string, number>();
    const doorsIn = new Map<string, number>();
    for (const edge of model.edges) {
      doorsOut.set(edge.fromScreenId, (doorsOut.get(edge.fromScreenId) ?? 0) + 1);
      doorsIn.set(edge.toScreenId, (doorsIn.get(edge.toScreenId) ?? 0) + 1);
    }
    const table = el("div", "run-table screens-table");
    const head = el("div", "run-row run-row-head screen-row");
    ["Screen", "Purpose", "Doors out", "Doors in", "Confidence", "Last seen"].forEach((label) => head.append(el("span", undefined, label)));
    table.append(head);
    const rows = [...model.screens].sort((a, b) => (doorsOut.get(b.screenId) ?? 0) - (doorsOut.get(a.screenId) ?? 0) || a.label.localeCompare(b.label));
    const rowFor = new Map<string, HTMLElement>();
    for (const screen of rows) {
      const row = el("button", "run-row screen-row");
      row.type = "button";
      row.dataset.screenId = screen.screenId;
      const name = el("span", "run-goal");
      name.append(el("span", "run-goal-text", screen.label), el("span", "run-sub", roomLabel(screen.screenId)));
      if (screen.risk.danger) name.append(chip("Guarded", "warning"));
      const confidence = Math.round((Number.isFinite(screen.confidence) ? screen.confidence : 0) * 100);
      row.append(
        name,
        el("span", undefined, screen.purpose || "—"),
        el("span", undefined, String(doorsOut.get(screen.screenId) ?? 0)),
        el("span", undefined, String(doorsIn.get(screen.screenId) ?? 0)),
        el("span", confidence < 50 ? "text-danger" : undefined, `${confidence}%`),
        el("span", "muted", screen.lastObservedAt ? relativeTime(Date.parse(screen.lastObservedAt)) : "—"),
      );
      row.addEventListener("click", () => showMap([screen.screenId]));
      rowFor.set(screen.screenId, row);
      table.append(row);
    }
    const summary = el("p", "muted knowledge-note", `${model.screens.length} screens · ${model.edges.length} doors. Click a screen to find it on the map.`);
    const search = searchInput("Find a screen", (value) => {
      const q = value.trim().toLowerCase();
      for (const screen of rows) {
        const hit = !q || `${screen.label} ${screen.purpose ?? ""} ${roomLabel(screen.screenId)}`.toLowerCase().includes(q);
        rowFor.get(screen.screenId)!.hidden = !hit;
      }
    });
    const tools = el("div", "scenario-toggles");
    tools.append(toggle.element, search);
    setChildren(body, tools, summary, table);
  }

  async function loadRuns(): Promise<void> {
    const runs = await listRuns(ctx.client, deviceId, "all", 200, controller.signal);
    renderRuns(runs.filter((run) => run.places.some((place) => place.placeId === placeId)), runs.some((run) => run.mapSteps != null));
  }

  function renderRuns(runs: RunSummary[], phoneRecordsApps: boolean): void {
    if (!runs.length) {
      setChildren(
        body,
        emptyState({
          icon: "runs",
          title: "No runs in this app yet",
          body: phoneRecordsApps
            ? "Runs that enter this app show up here. Ask Cyclone something that uses it."
            : "This phone does not record which app each run used yet (Cyclone Mobile 5.0.0-alpha.11 or newer does).",
        }),
      );
      return;
    }
    const failed = runs.filter((run) => run.status === "failed").length;
    const stats = el("div", "stats stats-4");
    const mapSteps = runs.reduce((sum, run) => sum + (run.mapSteps ?? 0), 0);
    const modelSteps = runs.reduce((sum, run) => sum + (run.modelSteps ?? 0), 0);
    stats.append(
      statTile("Runs", String(runs.length)),
      statTile("Finished", String(runs.filter((run) => run.status === "completed").length), "success"),
      statTile("Failed", String(failed), failed ? "danger" : "neutral"),
      statTile("From the map", mapSteps + modelSteps ? `${Math.round((100 * mapSteps) / (mapSteps + modelSteps))}%` : "—"),
    );
    const table = el("div", "run-table");
    table.setAttribute("role", "list");
    table.append(...runs.map(runRow));
    setChildren(body, stats, table);
  }

  function renderScenarios(list: ScenarioList): void {
    const persona = segmented<Persona>(
      [
        { id: "mapping", label: "Mapping pass" },
        { id: "live", label: "Your teaching" },
      ],
      scenariosPersona,
      (id) => {
        scenariosPersona = id;
        setChildren(body, loadingState("Loading scenarios from the phone…"));
        void getScenarios(ctx.client, deviceId, placeId, scenariosPersona, controller.signal)
          .then(renderScenarios)
          .catch((error) => {
            if ((error as { name?: string })?.name !== "AbortError") setChildren(body, knowledgeError(error, () => void load()));
          });
      },
    );
    persona.element.classList.add("persona-toggle");
    if (!list.scenarios.length) {
      const map = actionButton("Open the map", { icon: "map", variant: "primary" });
      map.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map" }));
      setChildren(
        body,
        persona.element,
        emptyState({
          icon: "map",
          title: "No scenarios yet",
          body: "Scenarios are the routes Cyclone knows from this app's first screen to each other screen. Map the app (or teach it) and they appear here.",
          action: map,
        }),
      );
      return;
    }
    const counts = { passing: 0, warning: 0, critical: 0, untested: 0 };
    list.scenarios.forEach((scenario) => counts[scenario.health]++);
    const stats = el("div", "stats stats-4");
    stats.append(
      statTile("Passing", String(counts.passing), counts.passing ? "success" : "neutral"),
      statTile("Warning", String(counts.warning), counts.warning ? "warning" : "neutral"),
      statTile("Critical", String(counts.critical), counts.critical ? "danger" : "neutral"),
      statTile("Not run yet", String(counts.untested)),
    );
    const note = el(
      "p",
      "muted knowledge-note",
      `Health measures whether Cyclone can get there, from the runs that reached each screen${list.entryScreenId ? `. Every route starts at ${roomLabel(list.entryScreenId)}` : ""}.`,
    );
    const view = segmented<"cards" | "board">(
      [
        { id: "cards", label: "Cards" },
        { id: "board", label: "Board" },
      ],
      scenarioView,
      (id) => {
        scenarioView = id;
        renderScenarios(list);
      },
    );
    const toggles = el("div", "scenario-toggles");
    toggles.append(persona.element, view.element);
    if (scenarioView === "board") {
      setChildren(body, stats, toggles, note, scenarioBoard(list));
      return;
    }
    const grid = el("div", "scenario-grid");
    grid.append(...list.scenarios.map(scenarioCard));
    setChildren(body, stats, toggles, note, grid);
  }

  /** Minitap-style: the entry room on the left, then one column per number of doors away. */
  function scenarioBoard(list: ScenarioList): HTMLElement {
    const board = el("div", "scenario-board");
    const entry = el("section", "scenario-column entry");
    entry.append(el("h3", "scenario-column-title", "Start"));
    const start = el("div", "scenario-mini entry");
    start.append(el("strong", undefined, list.entryScreenId ? roomLabel(list.entryScreenId) : "Entry"), el("span", "muted", "where every route begins"));
    entry.append(start);
    board.append(entry);
    const depths = [...new Set(list.scenarios.map((scenario) => scenario.steps))].sort((a, b) => a - b);
    for (const depth of depths) {
      const column = el("section", "scenario-column");
      column.append(el("h3", "scenario-column-title", `${depth} ${depth === 1 ? "door" : "doors"} away`));
      for (const scenario of list.scenarios.filter((s) => s.steps === depth)) {
        const mini = el("button", `scenario-mini health-${scenario.health}`);
        mini.type = "button";
        mini.dataset.scenarioId = scenario.scenarioId;
        mini.append(el("strong", undefined, scenario.title), chip(healthLabel(scenario.health), healthTone(scenario.health)));
        const kind = kindLabel(scenario.kind);
        if (kind) mini.append(chip(kind, "accent"));
        if (scenario.danger) mini.append(chip("Guarded", "warning"));
        mini.addEventListener("click", () => showMap(scenario.route));
        column.append(mini);
      }
      board.append(column);
    }
    return board;
  }

  /** Whether this app's Vault slots are set (never their values), for the Sign in card. */
  async function vaultState(into: HTMLElement): Promise<void> {
    let slots: VaultSlot[];
    try {
      slots = (await getKnowledge(ctx.client, deviceId, controller.signal)).vault.slots.filter((slot) => slot.placeId === placeId);
    } catch {
      return;
    }
    if (!slots.length) {
      setChildren(into, chip("No password slot yet", "warning"), el("span", "muted", "The phone asks for one the first time this login runs."));
      return;
    }
    setChildren(into, ...slots.map((slot) => chip(`${slot.slot}${slot.persona === "mapping" ? " (test account)" : ""} · ${slot.set ? "set" : "not set"}`, slot.set ? "success" : "warning")));
  }

  function scenarioCard(scenario: Scenario): HTMLElement {
    const node = card(`scenario-card health-${scenario.health}`);
    node.dataset.scenarioId = scenario.scenarioId;
    const top = el("div", "scenario-top");
    top.append(el("h2", "scenario-title", scenario.title), chip(healthLabel(scenario.health), healthTone(scenario.health)));
    const meta = el("div", "scenario-meta");
    meta.append(el("span", "muted", `${scenario.steps} ${scenario.steps === 1 ? "door" : "doors"}`));
    if (scenario.appVersion) meta.append(el("span", "muted", `version ${scenario.appVersion}`));
    if (scenario.lastVerifiedAt) meta.append(el("span", "muted", `last worked ${relativeTime(scenario.lastVerifiedAt)}`));
    const stale = staleDays(scenario.lastVerifiedAt, Date.now());
    if (stale !== null) meta.append(chip(`Not checked for ${stale} days`, "warning"));
    const kind = kindLabel(scenario.kind);
    if (kind) meta.append(chip(kind, "accent"));
    if (scenario.danger) meta.append(chip("Passes a guarded door", "warning"));
    const rooms = el("ol", "route-rooms");
    scenario.route.forEach((room, index) => {
      const item = el("li", "route-room");
      item.append(el("span", "route-badge", String(index + 1)), el("span", undefined, roomLabel(room)));
      rooms.append(item);
    });
    const actions = el("div", "scenario-actions");
    const show = actionButton("Show on the map", { icon: "map" });
    show.addEventListener("click", () => showMap(scenario.route));
    actions.append(show);
    node.append(top, meta, rooms);
    if (scenario.kind === "sign-in") {
      node.append(el("p", "muted knowledge-note", "The phone fills the login from its Vault. Glass never sees the password."));
      const vault = el("div", "scenario-vault");
      node.append(vault);
      void vaultState(vault);
    }
    node.append(actions);
    if (scenario.runs.length) {
      const runs = el("ul", "scenario-runs");
      for (const run of scenario.runs) {
        const item = el("li");
        const open = link("", `#/runs/${encodeURIComponent(run.runId)}`, "scenario-run");
        open.append(chip(runStatusLabel(run.status), runStatusTone(run.status)), el("span", "muted", relativeTime(run.startedAt)));
        item.append(open);
        runs.append(item);
      }
      node.append(el("h3", "inspector-section", "Runs that went here"), runs);
    }
    return node;
  }

  function renderVersions(info: AppVersions): void {
    const parts: HTMLElement[] = [];
    const summary = card("versions-summary");
    summary.append(el("h2", "card-title", "Installed now"), el("p", "versions-installed", versionText(info.installedVersion)));
    if (info.needsRemap) {
      summary.classList.add("needs-remap");
      summary.append(chip("Needs remap", "warning"), el("p", "muted", "No door has been confirmed on the installed version yet. Remap so Cyclone trusts its routes again."));
      const remap = actionButton("Remap on the phone", { icon: "play", variant: "primary" });
      remap.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map" }));
      summary.append(remap);
    } else if (info.versions.length) {
      summary.append(chip("Map matches this version", "success"));
    }
    parts.push(summary);

    if (!info.versions.length) {
      parts.push(
        emptyState({
          icon: "map",
          title: "No versions recorded yet",
          body: "Doors learned from alpha.11 on carry the app version they were seen on. Map the app to record this version.",
        }),
      );
      setChildren(body, ...parts);
      return;
    }

    const table = el("div", "run-table versions-table");
    const head = el("div", "run-row run-row-head version-row");
    ["Version", "Doors", "Rooms", "Failing doors", "Last seen"].forEach((label) => head.append(el("span", undefined, label)));
    table.append(head);
    for (const row of info.versions) {
      const line = el("div", "run-row version-row");
      const name = el("span", "version-name", versionText(row));
      if (row.installed) name.append(chip("Installed", "success"));
      line.append(
        name,
        el("span", undefined, String(row.doors)),
        el("span", undefined, String(row.rooms)),
        el("span", row.failingDoors ? "text-danger" : undefined, String(row.failingDoors)),
        el("span", "muted", row.lastSeenAt ? relativeTime(row.lastSeenAt) : "—"),
      );
      table.append(line);
    }
    parts.push(table);

    if (info.staleDoorCount) {
      const stale = card("stale-card");
      stale.append(
        el("h2", "card-title", `${info.staleDoorCount} ${info.staleDoorCount === 1 ? "door" : "doors"} last confirmed on an older version`),
        el("p", "muted", "They may have moved in the update. The next mapping pass or run that uses them confirms or replaces them."),
      );
      const list = el("ul", "stale-list");
      for (const door of info.staleDoors) {
        const item = el("li", "stale-door");
        item.append(
          el("span", undefined, `${roomLabel(door.fromScreenId)} → ${roomLabel(door.toScreenId)}`),
          el("span", "muted", `seen on ${versionText(door)}`),
        );
        const show = link("Show", "#", "stale-show");
        show.addEventListener("click", (event) => {
          event.preventDefault?.();
          showMap([door.fromScreenId, door.toScreenId]);
        });
        item.append(show);
        list.append(item);
      }
      stale.append(list);
      parts.push(stale);
    }
    setChildren(body, ...parts);
  }

  void load();
  return { element, destroy: () => controller.abort() };
}

/** Saves text as a file in the browser (the exported manual). Nothing leaves the PC. */
function downloadText(name: string, text: string): void {
  if (typeof document === "undefined" || typeof URL === "undefined" || typeof Blob === "undefined") return;
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

export function knowledgeError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && ["PROTOCOL_MISMATCH", "CAPABILITY_UNAVAILABLE", "UNKNOWN_OPERATION"].includes(error.code)) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "Scenarios and Versions need Cyclone Mobile 5.0.0-alpha.11 or newer on the phone.",
    });
  }
  if (error instanceof GatewayError && error.code === "INVALID_REQUEST") {
    return emptyState({ icon: "map", title: "Not available for websites yet", body: "Scenarios and Versions work for apps for now." });
  }
  return errorState("Couldn't load from the phone", { message: error instanceof Error ? error.message : String(error) }, retry);
}
