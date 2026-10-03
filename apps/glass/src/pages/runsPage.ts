/** Runs: every Cyclone run on this phone, newest first, with how it ended and why. */
import type { GlassContext } from "../app.js";
import { routeHref } from "../core/router.js";
import { GatewayError } from "../services/gateway.js";
import { appName, causeLabel, causeTone, formatDuration, groupByGoal, listRuns, runsCsv, statusLabel, statusTone, type GoalGroup, type RunFilter, type RunSummary } from "../services/runs.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, pageHeader, searchInput, segmented } from "../ui/components.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

const FILTERS: Array<{ id: RunFilter; label: string }> = [
  { id: "all", label: "All" },
  { id: "failed", label: "Failed" },
  { id: "completed", label: "Finished" },
  { id: "stopped", label: "Stopped or waiting" },
];

export interface RunsPageDeps {
  saveFile?: (name: string, text: string, type: string) => void;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export function createRunsPage(ctx: GlassContext, deps: RunsPageDeps = {}): GlassPage {
  const element = el("div", "page page-runs");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  const exportCsv = actionButton("Export CSV", { icon: "download" });
  element.append(pageHeader("Runs", "Every task Cyclone ran on this phone. Open one to see each step and why it ended.", [exportCsv, refresh]));
  let inView: RunSummary[] = [];
  exportCsv.addEventListener("click", () => {
    const save = deps.saveFile ?? saveText;
    save(`cyclone-runs-${new Date().toISOString().slice(0, 10)}.csv`, runsCsv(inView), "text/csv");
  });
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  let filter: RunFilter = "all";
  let query = "";
  let causeFilter = "";
  let appFilter = "";
  let runs: RunSummary[] | null = null;
  let view: "runs" | "goals" = "runs";
  let controller: AbortController | null = null;

  const filters = segmented(FILTERS, filter, (id) => {
    filter = id;
    filters.set(filter);
    void load();
  });
  const toolbar = el("div", "toolbar");
  toolbar.append(
    searchInput("Search goals", (value) => {
      query = value;
      render();
    }),
    filters.element,
  );
  const causeSelect = el("select", "picker-select runs-select");
  causeSelect.setAttribute("aria-label", "Why it ended");
  causeSelect.addEventListener("change", () => {
    causeFilter = causeSelect.value;
    render();
  });
  const appSelect = el("select", "picker-select runs-select");
  appSelect.setAttribute("aria-label", "App");
  appSelect.addEventListener("change", () => {
    appFilter = appSelect.value;
    render();
  });
  const views = segmented<"runs" | "goals">(
    [
      { id: "runs", label: "Runs" },
      { id: "goals", label: "Goals" },
    ],
    view,
    (id) => {
      view = id;
      views.set(view);
      render();
    },
  );
  toolbar.append(causeSelect, appSelect, views.element);
  const body = el("div", "runs-body");
  element.append(toolbar, body);

  const render = (): void => {
    if (!runs) return;
    const q = query.trim().toLowerCase();
    fillOptions(causeSelect, "Any reason", [...new Set(runs.map((run) => run.cause?.kind).filter((k): k is string => !!k))].map((kind) => [kind, causeLabel(kind)]), causeFilter);
    fillOptions(appSelect, "Any app", [...new Set(runs.flatMap((run) => run.places.map((place) => place.placeId)))].map((id) => [id, appName(id)]), appFilter);
    appSelect.hidden = appSelect.children.length <= 1;
    const visible = runs.filter(
      (run) =>
        (!q || run.goal.toLowerCase().includes(q)) &&
        (!causeFilter || run.cause?.kind === causeFilter) &&
        (!appFilter || run.places.some((place) => place.placeId === appFilter)),
    );
    inView = visible;
    if (!visible.length) {
      setChildren(
        body,
        emptyState({
          icon: "runs",
          title: runs.length ? "No runs match" : filter === "all" ? "No runs yet" : "No runs in this view",
          body: runs.length ? "Try another search." : "Ask Cyclone something on the phone or from the Phone page; runs appear here.",
        }),
      );
      return;
    }
    if (view === "goals") {
      setChildren(body, goalTable(groupByGoal(visible)));
      return;
    }
    const table = el("div", "run-table");
    table.setAttribute("role", "list");
    const head = el("div", "run-row run-row-head");
    head.append(el("span", undefined, "Goal"), el("span", undefined, "Result"), el("span", undefined, "Why it ended"), el("span", undefined, "Steps"), el("span", undefined, "Started"), el("span", undefined, "Took"), el("span"));
    table.append(head);
    for (const run of visible) table.append(runRow(run));
    setChildren(body, table);
  };

  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  let timer: unknown = null;

  const load = async (quiet = false): Promise<void> => {
    controller?.abort();
    controller = new AbortController();
    if (timer !== null) clearTimer(timer);
    timer = null;
    if (!quiet) setChildren(body, loadingState("Asking the phone for its runs…"));
    try {
      runs = await listRuns(ctx.client, deviceId, filter, 100, controller.signal);
      render();
      // While a run is going on the phone, the list keeps itself current.
      if (runs.some((run) => run.status === "running")) timer = setTimer(() => void load(true), 5_000);
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      runs = null;
      setChildren(body, runsError(error, () => void load()));
    }
  };

  refresh.addEventListener("click", () => void load());
  void load();
  return {
    element,
    destroy: () => {
      controller?.abort();
      if (timer !== null) clearTimer(timer);
    },
  };
}

/** Mapping passes are recorded as runs by the phone's mapper (model name `cyclone-mapper`). */
export const MAPPER = "cyclone-mapper";

export function runRow(run: RunSummary): HTMLAnchorElement {
  const row = el("a", `run-row status-${run.status}`);
  row.href = routeHref({ name: "run", runId: run.runId });
  row.setAttribute("role", "listitem");
  row.dataset.runId = run.runId;
  const goal = el("span", "run-goal");
  const sub = [
    run.places.length ? run.places.map((place) => appName(place.placeId)).join(" → ") : "",
    run.mapSteps ? `${run.mapSteps} ${run.mapSteps === 1 ? "step" : "steps"} from the map` : "",
    run.model === MAPPER ? "" : run.model,
  ].filter(Boolean).join(" · ");
  goal.append(el("span", "run-goal-text", run.goal), el("span", "run-sub", sub));
  if (run.model === MAPPER) goal.append(chip("Mapping pass", "accent"));
  if (run.expected) goal.append(chip("Expected", "neutral"));
  const result = el("span");
  result.append(chip(statusLabel(run.status), statusTone(run.status)));
  const why = el("span", "run-why");
  if (run.cause) {
    why.append(chip(causeLabel(run.cause.kind), causeTone(run.cause.kind)));
    why.title = run.cause.headline;
  } else why.append(el("span", "muted", run.status === "completed" ? "Done" : "—"));
  const failures = run.metrics.toolFailures + run.metrics.verificationFailures;
  const steps = el("span", "run-steps", String(run.stepCount));
  if (failures) steps.append(el("span", "run-failures", ` · ${failures} failed`));
  const go = el("span", "col-go");
  go.append(icon("chevron"));
  row.append(goal, result, why, steps, el("span", "muted", relativeTime(run.startedAt)), el("span", "muted", formatDuration(run.durationMs)), go);
  return row;
}

export function runsError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && (error.code === "PROTOCOL_MISMATCH" || error.code === "CAPABILITY_UNAVAILABLE")) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "This phone's Cyclone does not share its runs yet. The run inspector needs Cyclone Mobile 5.0.0-alpha.8 or newer.",
    });
  }
  if (error instanceof GatewayError && error.code === "RUN_NOT_FOUND") {
    return emptyState({ icon: "search", title: "Run not found", body: "The phone no longer has this run." });
  }
  return errorState("Couldn't load runs", { message: error instanceof Error ? error.message : String(error) }, retry);
}

function fillOptions(select: HTMLSelectElement, anyLabel: string, options: Array<[string, string]>, selected: string): void {
  const any = el("option", undefined, anyLabel);
  any.value = "";
  const rows = [any, ...options.sort((a, b) => a[1].localeCompare(b[1])).map(([value, label]) => {
    const option = el("option", undefined, label);
    option.value = value;
    return option;
  })];
  rows.forEach((option) => (option.selected = option.value === selected));
  select.replaceChildren(...rows);
}

/** One row per sentence: how often it worked and how the last run ended. */
function goalTable(groups: GoalGroup[]): HTMLElement {
  const table = el("div", "run-table goal-table");
  table.setAttribute("role", "list");
  const head = el("div", "goal-row run-row-head");
  head.append(el("span", undefined, "Goal"), el("span", undefined, "Runs"), el("span", undefined, "Worked"), el("span", undefined, "Last run"), el("span"));
  table.append(head);
  for (const group of groups) {
    const row = el("a", "goal-row");
    row.href = routeHref({ name: "run", runId: group.last.runId });
    row.setAttribute("role", "listitem");
    const rate = group.successRate;
    const tone = rate === null ? "neutral" : rate >= 0.8 ? "success" : rate >= 0.5 ? "warning" : "danger";
    const last = el("span", "goal-last");
    last.append(chip(group.last.expected ? "Expected" : statusLabel(group.last.status), group.last.expected ? "neutral" : statusTone(group.last.status)), el("span", "muted", relativeTime(group.last.startedAt)));
    row.append(
      el("span", "run-goal-text", group.goal),
      el("span", undefined, String(group.runs)),
      chip(rate === null ? "—" : `${Math.round(rate * 100)}% · ${group.finished} of ${group.finished + group.failed}`, tone),
      last,
      icon("chevron"),
    );
    table.append(row);
  }
  return table;
}

function saveText(name: string, text: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const anchor = el("a");
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
