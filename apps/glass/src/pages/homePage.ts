/**
 * Home: one look at the phone. Which apps need attention (needs remap, critical scenarios, last run failed), the
 * latest runs, and what Cyclone knows and never presses. Every fact comes from existing phone ops; each card loads
 * on its own so an older phone still shows the parts it can answer.
 */
import type { GlassContext } from "../app.js";
import { routeHref } from "../core/router.js";
import { loadApps, type PhoneApp } from "../services/apps.js";
import { getKnowledge, type KnowledgeSummary } from "../services/knowledgeSummary.js";
import { appName, causeLabel, listRuns, statusLabel, statusTone, type RunSummary } from "../services/runs.js";
import { actionButton, card, chip, loadingState, pageHeader, statTile } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { icon } from "../ui/icons.js";
import { deviceGate } from "./deviceGate.js";
import { saveWithBlob } from "./runPage.js";
import type { GlassPage } from "./page.js";

export interface Attention {
  placeId: string;
  label: string;
  reason: string;
  tone: "danger" | "warning";
  href: string;
}

/** Apps worth a look, worst first: critical scenarios, then a failed last run, then needs remap. */
export function attentionList(apps: PhoneApp[], runs: RunSummary[]): Attention[] {
  const last = new Map<string, RunSummary>();
  for (const run of runs) {
    for (const place of run.places) {
      const known = last.get(place.placeId);
      if (!known || known.startedAt < run.startedAt) last.set(place.placeId, run);
    }
  }
  const rows: Array<Attention & { rank: number }> = [];
  for (const app of apps) {
    const base = { placeId: app.placeId, label: app.label || appName(app.placeId) };
    const critical = app.scenarios?.critical ?? 0;
    const run = last.get(app.placeId);
    if (critical) {
      rows.push({ ...base, rank: 0, tone: "danger", reason: `${critical} critical ${critical === 1 ? "scenario" : "scenarios"}`, href: routeHref({ name: "app", placeId: app.placeId, tab: "scenarios" }) });
    } else if (run && run.status === "failed" && run.expected !== true) {
      rows.push({ ...base, rank: 1, tone: "danger", reason: `Last run failed${run.cause ? `: ${causeLabel(run.cause.kind)}` : ""}`, href: routeHref({ name: "run", runId: run.runId }) });
    } else if (app.needsRemap) {
      rows.push({ ...base, rank: 2, tone: "warning", reason: "App updated since it was mapped", href: routeHref({ name: "app", placeId: app.placeId, tab: "versions" }) });
    }
  }
  return rows
    .sort((a, b) => a.rank - b.rank || a.label.localeCompare(b.label))
    .map(({ rank: _rank, ...row }) => row);
}

export interface DayBar {
  label: string;
  finished: number;
  failed: number;
  other: number;
}

/** Runs per local day for the last `days` days, oldest first. Expected runs count as other, not failed. */
export function runsPerDay(runs: RunSummary[], now: number, days = 7): DayBar[] {
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const bars: DayBar[] = [];
  for (let i = days - 1; i >= 0; i--) {
    const from = new Date(start);
    from.setDate(start.getDate() - i);
    const to = new Date(from);
    to.setDate(from.getDate() + 1);
    const inDay = runs.filter((run) => run.startedAt >= from.getTime() && run.startedAt < to.getTime());
    bars.push({
      label: i === 0 ? "Today" : from.toLocaleDateString(undefined, { weekday: "short" }),
      finished: inDay.filter((run) => run.status === "completed").length,
      failed: inDay.filter((run) => run.status === "failed" && run.expected !== true).length,
      other: inDay.filter((run) => run.status !== "completed" && !(run.status === "failed" && run.expected !== true)).length,
    });
  }
  return bars;
}

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? "" : "s"}`;
}

export interface HomeReport {
  kind: "cyclone-glass-home-report";
  version: 1;
  glass: string;
  phone: { name: string; mobileVersion: string | null };
  createdAt: string;
  apps: Array<{ placeId: string; label: string; mapStatus: string; rooms: number; doors: number; needsRemap: boolean; scenarios: PhoneApp["scenarios"] }> | null;
  attention: Attention[] | null;
  runs: Array<{ runId: string; goal: string; status: string; cause: string | null; startedAt: number; expected: boolean | null }> | null;
  knowledge: { atlas: KnowledgeSummary["atlas"]; secretsSet: number; secretSlots: number; skills: number; automations: number; guarded: KnowledgeSummary["guarded"] } | null;
}

/** A shareable snapshot of Home: structure, counts and run outcomes. Secret slots appear only as counts. */
export function homeReport(glass: string, phone: { name: string; mobileVersion: string | null }, apps: PhoneApp[] | null, runs: RunSummary[] | null, knowledge: KnowledgeSummary | null, now: number): HomeReport {
  return {
    kind: "cyclone-glass-home-report",
    version: 1,
    glass,
    phone,
    createdAt: new Date(now).toISOString(),
    apps: apps?.map((a) => ({ placeId: a.placeId, label: a.label, mapStatus: a.mapStatus, rooms: a.rooms, doors: a.doors, needsRemap: a.needsRemap, scenarios: a.scenarios })) ?? null,
    attention: apps ? attentionList(apps, runs ?? []) : null,
    runs: runs?.map((r) => ({ runId: r.runId, goal: r.goal, status: r.status, cause: r.cause?.kind ?? null, startedAt: r.startedAt, expected: r.expected })) ?? null,
    knowledge: knowledge
      ? {
          atlas: knowledge.atlas,
          secretsSet: knowledge.vault.setCount,
          secretSlots: knowledge.vault.slotCount,
          skills: knowledge.skills.length,
          automations: knowledge.automations.length,
          guarded: knowledge.guarded,
        }
      : null,
  };
}

export interface HomePageDeps {
  saveFile?: (name: string, text: string) => void;
}

export function createHomePage(ctx: GlassContext, deps: HomePageDeps = {}): GlassPage {
  const element = el("div", "page page-home");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  const download = actionButton("Download report", { icon: "download" });
  download.disabled = true;
  element.append(pageHeader("Home", "The phone at a glance. Cyclone decides on the phone; this is what it knows and did.", [download, refresh]));
  let latest: HomeReport | null = null;
  download.addEventListener("click", () => {
    if (!latest) return;
    (deps.saveFile ?? saveWithBlob)(`cyclone-home-${latest.createdAt.slice(0, 10)}.json`, JSON.stringify(latest, null, 2));
  });
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const device = ctx.device;
  const stats = el("div", "stats stats-4");
  const attention = card("home-attention");
  const recent = card("home-runs");
  const known = card("home-knowledge");
  const trend = card("home-trend");
  trend.hidden = true;
  const grid = el("div", "home-grid");
  grid.append(attention, recent, known);
  element.append(stats, trend, grid);
  let controller = new AbortController();

  const load = async (): Promise<void> => {
    controller.abort();
    controller = new AbortController();
    const signal = controller.signal;
    setChildren(attention, el("h2", "card-title", "Needs attention"), loadingState("Checking apps…"));
    setChildren(recent, el("h2", "card-title", "Latest runs"), loadingState("Loading runs…"));
    setChildren(known, el("h2", "card-title", "Knowledge"), loadingState("Asking the phone…"));
    const [apps, runs, knowledge] = await Promise.all([
      loadApps(ctx.client, device.id, signal).then((c) => c.apps).catch(() => null),
      listRuns(ctx.client, device.id, "all", 100, signal).catch(() => null),
      getKnowledge(ctx.client, device.id, signal).catch(() => null),
    ]);
    if (signal.aborted) return;
    latest = homeReport(ctx.version, { name: device.name, mobileVersion: device.mobileVersion ?? null }, apps, runs, knowledge, Date.now());
    download.disabled = false;
    renderStats(apps, runs);
    renderAttention(apps, runs);
    renderRuns(runs);
    renderTrend(runs);
    renderKnowledge(knowledge);
  };

  function renderTrend(runs: RunSummary[] | null): void {
    if (!runs?.length) {
      trend.hidden = true;
      return;
    }
    const bars = runsPerDay(runs, Date.now());
    const most = Math.max(1, ...bars.map((bar) => bar.finished + bar.failed + bar.other));
    const chart = el("div", "trend-chart");
    chart.setAttribute("role", "img");
    chart.setAttribute("aria-label", bars.map((b) => `${b.label}: ${b.finished} finished, ${b.failed} failed`).join("; "));
    for (const bar of bars) {
      const column = el("div", "trend-day");
      const stack = el("div", "trend-stack");
      for (const [kind, count] of [["other", bar.other], ["failed", bar.failed], ["finished", bar.finished]] as const) {
        if (!count) continue;
        const segment = el("div", `trend-seg trend-${kind}`);
        segment.style.height = `${Math.round((count / most) * 100)}%`;
        segment.title = `${count} ${kind === "other" ? "stopped or expected" : kind}`;
        stack.append(segment);
      }
      column.append(stack, el("span", "trend-label", bar.label));
      chart.append(column);
    }
    const legend = el("div", "trend-legend");
    legend.append(chip("Finished", "success"), chip("Failed", "danger"), chip("Stopped or expected", "neutral"));
    setChildren(trend, el("h2", "card-title", "Runs, last 7 days"), chart, legend);
    trend.hidden = false;
  }

  function renderStats(apps: PhoneApp[] | null, runs: RunSummary[] | null): void {
    const mapped = apps?.filter((a) => a.mapStatus !== "unmapped").length;
    const day = Date.now() - 86_400_000;
    const today = runs?.filter((r) => r.startedAt >= day) ?? null;
    const failed = today?.filter((r) => r.status === "failed" && r.expected !== true).length;
    setChildren(
      stats,
      statTile(device.mobileVersion ? `Cyclone ${device.mobileVersion}` : "Phone", device.name, "success"),
      statTile("Apps mapped", apps ? `${mapped} of ${apps.length}` : "—"),
      statTile("Runs today", today ? String(today.length) : "—"),
      statTile("Failed today", failed == null ? "—" : String(failed), failed ? "danger" : "neutral"),
    );
  }

  function renderAttention(apps: PhoneApp[] | null, runs: RunSummary[] | null): void {
    const title = el("h2", "card-title", "Needs attention");
    if (!apps) {
      setChildren(attention, title, el("p", "muted", "The phone did not list its apps. Try Refresh."));
      return;
    }
    const rows = attentionList(apps, runs ?? []);
    if (!rows.length) {
      setChildren(attention, title, el("p", "muted", "Nothing needs you. Every mapped app is current and no scenario is critical."));
      return;
    }
    const list = el("ul", "home-list");
    for (const row of rows.slice(0, 8)) {
      const item = el("li", "home-row");
      item.dataset.placeId = row.placeId;
      const open = el("a", "home-link");
      open.href = row.href;
      open.append(el("strong", undefined, row.label), chip(row.reason, row.tone));
      item.append(open);
      list.append(item);
    }
    setChildren(attention, title, list);
    if (rows.length > 8) attention.append(el("p", "muted", `and ${rows.length - 8} more in Apps`));
  }

  function renderRuns(runs: RunSummary[] | null): void {
    const title = el("h2", "card-title", "Latest runs");
    if (!runs) {
      setChildren(recent, title, el("p", "muted", "Runs are not available from this phone yet."));
      return;
    }
    if (!runs.length) {
      setChildren(recent, title, el("p", "muted", "No runs yet. Ask the phone something from the Phone page."));
      return;
    }
    const list = el("ul", "home-list");
    for (const run of [...runs].sort((a, b) => b.startedAt - a.startedAt).slice(0, 6)) {
      const item = el("li", "home-row");
      const open = el("a", "home-link");
      open.href = routeHref({ name: "run", runId: run.runId });
      const names = el("span", "device-names");
      names.append(el("span", "device-name", run.goal || "Run"), el("span", "muted", relativeTime(run.startedAt)));
      open.append(names, chip(run.expected ? "Expected" : statusLabel(run.status), run.expected ? "neutral" : statusTone(run.status)));
      item.append(open);
      list.append(item);
    }
    const all = el("a", "home-more", "All runs");
    all.href = routeHref({ name: "runs" });
    setChildren(recent, title, list, all);
  }

  function renderKnowledge(summary: KnowledgeSummary | null): void {
    const title = el("h2", "card-title", "Knowledge");
    if (!summary) {
      setChildren(known, title, el("p", "muted", "Update Cyclone on the phone to see what it knows."));
      return;
    }
    const facts = el("ul", "home-facts");
    const fact = (name: Parameters<typeof icon>[0], text: string): void => {
      const item = el("li");
      item.append(icon(name), el("span", undefined, text));
      facts.append(item);
    };
    fact("map", `${summary.atlas.rooms} rooms and ${summary.atlas.doors} doors in ${summary.atlas.places} places`);
    fact("lock", `${summary.vault.setCount} of ${summary.vault.slotCount} secrets set (values stay on the phone)`);
    fact("book", `${plural(summary.skills.length, "skill")}, ${plural(summary.automations.length, "automation")}`);
    if (summary.guarded) {
      const total = summary.guarded.reduce((sum, row) => sum + row.doors + row.rooms, 0);
      const apps = new Set(summary.guarded.map((row) => row.placeId)).size;
      fact("shield", total ? `${total} guarded doors in ${apps} ${apps === 1 ? "app" : "apps"}, never pressed` : "No guarded doors found yet");
    }
    const more = el("a", "home-more", "Open Knowledge");
    more.href = routeHref({ name: "knowledge" });
    setChildren(known, title, facts, more);
  }

  refresh.addEventListener("click", () => void load());
  void load();
  return { element, destroy: () => controller.abort() };
}
