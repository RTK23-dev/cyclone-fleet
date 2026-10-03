/**
 * Apps (home): the fleet. Every app and web place on the phone, what Cyclone knows about it (routing ready, needs
 * attention, partial, unmapped), how fresh that map is, and what is happening now. The phone owns the list
 * (`apps.list`), the runs (`runs.list`) and the mapping job (`mapping.status`); Glass only groups and links.
 */
import type { GlassContext } from "../app.js";
import { routeHref, type Route } from "../core/router.js";
import {
  catalogStats,
  currentShare,
  filterApps,
  freshnessOf,
  knowledgeOf,
  KNOWLEDGE_LABEL,
  KNOWLEDGE_TONE,
  loadApps,
  scenarioSummary,
  sortApps,
  versionLabel,
  type AppCatalog,
  type AppKnowledge,
  type PhoneApp,
} from "../services/apps.js";
import { MAPPING_TERMINAL_STATES } from "../services/atlasClient.js";
import { phoneClient } from "../services/phone.js";
import { loadSkills, skillsByApp } from "../services/skills.js";
import { GatewayError } from "../services/gateway.js";
import { listRuns, statusLabel as runStatusLabel, statusTone as runStatusTone, type RunSummary } from "../services/runs.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, pageHeader, searchInput, segmented, statTile } from "../ui/components.js";
import { plural, relativeTime } from "../ui/format.js";
import { createAppPage } from "./appPage.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

type FleetFilter = "all" | AppKnowledge | "web" | "failing";
type FleetSort = "activity" | "least" | "name";

const FILTERS: Array<{ id: FleetFilter; label: string }> = [
  { id: "all", label: "All" },
  { id: "ready", label: "Routing ready" },
  { id: "attention", label: "Needs attention" },
  { id: "partial", label: "Partial" },
  { id: "unmapped", label: "Unmapped" },
  { id: "web", label: "Web" },
  { id: "failing", label: "Last run failed" },
];

const SORTS: Array<{ id: FleetSort; label: string }> = [
  { id: "activity", label: "Activity" },
  { id: "least", label: "Least mapped" },
  { id: "name", label: "Name" },
];

interface FleetFacts {
  lastRuns: Map<string, RunSummary>;
  mappingPlaceId: string | null;
  /** Saved skills per app and how many need a re-check (plan 23). */
  skills: Map<string, { count: number; recheck: number }>;
}

export function createAppsPage(ctx: GlassContext, route: Route): GlassPage {
  if (route.name === "app") return createAppPage(ctx, route);

  const element = el("div", "page page-apps");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Apps", "Every app on this phone: what Cyclone knows, how fresh it is, and what it is doing.", [refresh]));

  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;

  let catalog: AppCatalog | null = null;
  /** Latest run per app (phones from alpha.11 record which apps a run entered) and the app being mapped. Optional. */
  const facts: FleetFacts = { lastRuns: new Map(), mappingPlaceId: null, skills: new Map() };
  let filter: FleetFilter = "all";
  let sort: FleetSort = "activity";
  let query = "";
  let controller: AbortController | null = null;

  const stats = el("div", "stats");
  const filters = segmented(FILTERS, filter, (id) => {
    filter = id;
    render();
  });
  const sorter = segmented(SORTS, sort, (id) => {
    sort = id;
    sorter.set(sort);
    render();
  });
  sorter.element.classList.add("fleet-sort");
  const toolbar = el("div", "toolbar fleet-toolbar");
  toolbar.append(
    searchInput("Search apps", (value) => {
      query = value;
      render();
    }),
    filters.element,
    sorter.element,
  );
  const body = el("div", "apps-body");
  element.append(stats, toolbar, body);

  const knowledge = (app: PhoneApp): AppKnowledge => knowledgeOf(app, facts.lastRuns.get(app.placeId)?.status === "failed");

  const render = (): void => {
    if (!catalog) return;
    const all = catalog.apps;
    const s = catalogStats(all);
    const counts: Partial<Record<FleetFilter, number>> = { all: all.length, ready: 0, attention: 0, partial: 0, unmapped: 0 };
    for (const app of all) counts[knowledge(app)] = (counts[knowledge(app)] ?? 0) + 1;
    counts.web = all.filter((app) => app.kind === "chrome-origin").length;
    counts.failing = all.filter((app) => facts.lastRuns.get(app.placeId)?.status === "failed").length;
    setChildren(
      stats,
      statTile("Apps", String(s.total)),
      statTile("Routing ready", String(counts.ready ?? 0), "success"),
      statTile("Needs attention", String(counts.attention ?? 0), counts.attention ? "warning" : "neutral"),
      statTile("Places known", String(s.rooms), "accent"),
    );
    filters.set(filter, counts);
    const matching = filterApps(all, "all", query).filter((app) => {
      switch (filter) {
        case "all":
          return true;
        case "web":
          return app.kind === "chrome-origin";
        case "failing":
          return facts.lastRuns.get(app.placeId)?.status === "failed";
        default:
          return knowledge(app) === filter;
      }
    });
    const visible = sortFleet(matching, sort, facts);
    if (!visible.length) {
      setChildren(body, emptyState({ icon: "search", title: all.length ? "No apps match" : "No apps reported", body: all.length ? "Try another filter or search." : "The phone did not report any launchable apps." }));
      return;
    }
    const table = el("div", "app-table fleet-table");
    table.setAttribute("role", "list");
    table.append(tableHeader());
    for (const app of visible) table.append(appRow(app, knowledge(app), facts));
    setChildren(body, table, catalog.truncated ? el("p", "muted table-note", "Showing the first 600 apps the phone reported.") : null);
  };

  const load = async (): Promise<void> => {
    controller?.abort();
    controller = new AbortController();
    if (!catalog) setChildren(body, loadingState("Asking the phone for its apps…"));
    try {
      catalog = await loadApps(ctx.client, deviceId, controller.signal);
      render();
      void loadLastRuns(controller.signal);
      void loadMapping(controller.signal);
      void loadSkillCounts(controller.signal);
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      catalog = null;
      setChildren(stats);
      setChildren(body, appsError(error, () => void load()));
    }
  };

  async function loadLastRuns(signal: AbortSignal): Promise<void> {
    try {
      const runs = await listRuns(ctx.client, deviceId, "all", 200, signal);
      const latest = new Map<string, RunSummary>();
      for (const run of runs) {
        for (const place of run.places) {
          const known = latest.get(place.placeId);
          if (!known || known.startedAt < run.startedAt) latest.set(place.placeId, run);
        }
      }
      facts.lastRuns = latest;
      if (catalog && !signal.aborted) render();
    } catch {
      /* Older phones: the Apps page works without run facts. */
    }
  }

  async function loadSkillCounts(signal: AbortSignal): Promise<void> {
    try {
      const list = await loadSkills(ctx.client, deviceId, signal);
      if (signal.aborted) return;
      facts.skills = skillsByApp(list.skills);
      if (catalog) render();
    } catch {
      /* Phones before alpha.39 have no skills.list; the table works without it. */
    }
  }

  async function loadMapping(signal: AbortSignal): Promise<void> {
    try {
      const job = await phoneClient(ctx, deviceId, fetchFrom(ctx)).mappingStatus(null);
      if (signal.aborted) return;
      facts.mappingPlaceId = job.placeId && !MAPPING_TERMINAL_STATES.has(job.state) ? job.placeId : null;
      if (catalog) render();
    } catch {
      /* A phone without mapping.status (or not reachable for it): no live activity, the table still works. */
    }
  }

  refresh.addEventListener("click", () => void load());
  void load();
  return {
    element,
    destroy() {
      controller?.abort();
    },
  };
}

/** The gateway client's transport, so the mapping probe goes where the app list came from. */
function fetchFrom(ctx: GlassContext): typeof fetch {
  return (input, init) => ctx.client.authorizedFetch(input, init);
}

/** Activity: mapping now, then most recent run, then most places. Least mapped: fewest places first. Name: A→Z. */
export function sortFleet(apps: PhoneApp[], sort: FleetSort, facts: FleetFacts): PhoneApp[] {
  const byName = (a: PhoneApp, b: PhoneApp): number => a.label.localeCompare(b.label, undefined, { sensitivity: "base" });
  if (sort === "name") return [...apps].sort(byName);
  if (sort === "least") {
    return [...apps].sort((a, b) => {
      const mappedA = a.rooms > 0 ? 0 : 1;
      const mappedB = b.rooms > 0 ? 0 : 1;
      if (mappedA !== mappedB) return mappedA - mappedB;
      return a.rooms - b.rooms || byName(a, b);
    });
  }
  const base = sortApps(apps);
  const rank = new Map(base.map((app, i) => [app.placeId, i]));
  return base.sort((a, b) => {
    const liveA = a.placeId === facts.mappingPlaceId ? 1 : 0;
    const liveB = b.placeId === facts.mappingPlaceId ? 1 : 0;
    if (liveA !== liveB) return liveB - liveA;
    const runA = facts.lastRuns.get(a.placeId)?.startedAt ?? 0;
    const runB = facts.lastRuns.get(b.placeId)?.startedAt ?? 0;
    if (runA !== runB) return runB - runA;
    return rank.get(a.placeId)! - rank.get(b.placeId)!;
  });
}

function tableHeader(): HTMLElement {
  const row = el("div", "app-row app-row-head");
  row.append(
    el("span", "col-app", "App"),
    el("span", "col-status", "Knowledge"),
    el("span", "col-size", "Places · doors"),
    el("span", "col-conf", "Confidence"),
    el("span", "col-version", "Freshness"),
    el("span", "col-when", "Activity"),
    el("span", "col-go"),
  );
  return row;
}

function appRow(app: PhoneApp, known: AppKnowledge, facts: FleetFacts): HTMLAnchorElement {
  const row = el("a", `app-row knowledge-${known}`);
  row.href = routeHref({ name: "app", placeId: app.placeId, tab: "map" });
  row.setAttribute("role", "listitem");
  row.dataset.placeId = app.placeId;
  row.dataset.knowledge = known;

  const who = el("span", "col-app");
  const avatar = el("span", `app-avatar${app.kind === "chrome-origin" ? " web" : ""}`, (app.label[0] ?? "?").toUpperCase());
  const names = el("span", "app-names");
  const sub = app.kind === "chrome-origin" ? `Web · ${app.origin ?? ""}` : `${app.packageName ?? ""}${app.installedVersion ? ` · ${versionLabel(app.installedVersion)}` : ""}`;
  names.append(el("span", "app-name", app.label), el("span", "app-sub", sub));
  who.append(avatar, names);

  const statusCell = el("span", "col-status");
  statusCell.append(chip(KNOWLEDGE_LABEL[known], KNOWLEDGE_TONE[known]));
  const skills = facts.skills.get(app.placeId);
  if (skills?.count) {
    const skillChip = chip(skills.recheck ? `${skills.recheck} skill${skills.recheck === 1 ? "" : "s"} to re-check` : `${skills.count} skill${skills.count === 1 ? "" : "s"}`,
      skills.recheck ? "warning" : "accent");
    skillChip.classList.add("fleet-skills");
    statusCell.append(skillChip);
  }
  if (app.installed === false) statusCell.append(chip("Uninstalled", "neutral"));

  const go = el("span", "col-go");
  const live = app.placeId === facts.mappingPlaceId;
  const action = el("span", `fleet-action${known === "unmapped" && !live ? " primary" : ""}`, live ? "Watch" : known === "unmapped" ? "Start mapping" : "Open");
  go.append(action);

  const fresh = freshnessOf(app);
  const freshCell = el("span", "col-version");
  freshCell.append(fresh.text === "—" ? el("span", "muted", "—") : chip(fresh.text, fresh.tone));

  row.append(who, statusCell, sizeCell(app), confidenceCell(app), freshCell, activityCell(app, facts), go);
  return row;
}

function appsError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && (error.code === "PROTOCOL_MISMATCH" || error.code === "CAPABILITY_UNAVAILABLE")) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "This phone's Cyclone does not share its app list yet. Glass needs Cyclone Mobile 5.0.0-alpha.7 or newer.",
    });
  }
  const message = error instanceof Error ? error.message : String(error);
  return errorState("Couldn't load the phone's apps", { message }, retry);
}

function sizeCell(app: PhoneApp): HTMLElement {
  const cell = el("span", "col-size", app.rooms ? `${plural(app.rooms, "place")} · ${plural(app.doors, "door")}` : "—");
  const summary = scenarioSummary(app);
  if (summary) {
    cell.append(el("br"), chip(summary.text, summary.tone));
  }
  return cell;
}

function confidenceCell(app: PhoneApp): HTMLElement {
  const cell = el("span", "col-conf");
  const share = currentShare(app);
  if (share == null) {
    cell.append(el("span", "muted", app.rooms ? "not versioned" : "—"));
    return cell;
  }
  const pct = Math.round(share * 100);
  const bar = el("span", "conf-bar");
  const fill = el("span", `conf-fill ${share >= 0.85 ? "high" : share >= 0.6 ? "mid" : "low"}`);
  fill.style.width = `${Math.max(2, pct)}%`;
  bar.append(fill);
  cell.append(bar, el("span", "conf-pct", `${pct}%`));
  cell.title = "Share of this app's doors seen on the installed version";
  return cell;
}

function activityCell(app: PhoneApp, facts: FleetFacts): HTMLElement {
  const cell = el("span", "col-when");
  if (app.placeId === facts.mappingPlaceId) {
    const live = el("span", "fleet-live");
    live.append(el("span", "live-dot"), el("span", undefined, "Mapping…"));
    cell.append(live);
    return cell;
  }
  const lastRun = facts.lastRuns.get(app.placeId);
  if (lastRun) {
    cell.append(chip(`Last run ${runStatusLabel(lastRun.status).toLowerCase()}`, runStatusTone(lastRun.status)), el("span", "app-sub", relativeTime(lastRun.startedAt)));
    cell.title = lastRun.goal;
    return cell;
  }
  cell.append(el("span", "muted", app.rooms ? `verified ${relativeTime(app.lastVerifiedAt)}` : "—"));
  return cell;
}
