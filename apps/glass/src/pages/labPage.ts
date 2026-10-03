/**
 * Lab: run Mind missions on the phone and see, with numbers you can trust, how often Cyclone really gets them done.
 * The gateway runs the experiment and scores every run from the phone's real state; Glass builds the experiment,
 * follows it live and shows the results. Glass computes nothing about the outcome.
 */
import type { GlassContext } from "../app.js";
import { routeHref, type Route } from "../core/router.js";
import { deviceReadiness } from "../services/devices.js";
import { GatewayError } from "../services/gateway.js";
import {
  categoryLabel,
  categoryTone,
  cleanVariant,
  estimate,
  exportTrials,
  getCatalog,
  getExperiment,
  listExperiments,
  rateText,
  startExperiment,
  statusLabel,
  stopExperiment,
  type LabArm,
  type LabCatalog,
  type LabDetail,
  type LabExperiment,
  type LabMission,
  type LabTrial,
  type LabVariant,
} from "../services/lab.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, pageHeader, statTile } from "../ui/components.js";
import { plural, relativeTime } from "../ui/format.js";
import type { GlassPage } from "./page.js";

export interface LabPageDeps {
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  saveFile?: (name: string, text: string, type: string) => void;
}

const POLL_MS = 3_000;
const MAX_VARIANTS = 4;

export function createLabPage(ctx: GlassContext, route: Extract<Route, { name: "lab" }>, deps: LabPageDeps = {}): GlassPage {
  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  let current = ctx;
  let timer: unknown = null;
  let destroyed = false;
  const poll = (fn: () => void): void => {
    if (timer != null) clearTimer(timer);
    timer = destroyed ? null : setTimer(fn, POLL_MS);
  };
  const view = route.experimentId
    ? detailView(() => current, route.experimentId, poll, deps)
    : overview(() => current, poll);
  return {
    element: view,
    update(next) {
      current = next;
    },
    destroy() {
      destroyed = true;
      if (timer != null) clearTimer(timer);
    },
  };
}

// ---- overview: new experiment + history --------------------------------------------------------------------------

function overview(ctx: () => GlassContext, poll: (fn: () => void) => void): HTMLElement {
  const element = el("div", "page page-lab");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Lab", "Run missions on the phone, score each one from what the phone really shows, and compare variants.", [refresh]));
  const builderSlot = el("div", "lab-builder-slot");
  const historySlot = el("div", "lab-history");
  element.append(builderSlot, historySlot);
  let catalog: LabCatalog | null = null;

  const loadHistory = async (): Promise<void> => {
    try {
      const experiments = await listExperiments(ctx().client);
      setChildren(historySlot, historyList(experiments, ctx));
      if (experiments.some((e) => e.status === "running")) poll(() => void loadHistory());
    } catch (error) {
      setChildren(historySlot, errorState("Could not load experiments", toError(error), () => void loadHistory()));
    }
  };
  const load = async (): Promise<void> => {
    setChildren(builderSlot, loadingState("Loading missions…"));
    try {
      catalog = await getCatalog(ctx().client);
      setChildren(builderSlot, builder(ctx, catalog));
    } catch (error) {
      setChildren(builderSlot, errorState("Could not load lab missions", toError(error), () => void load()));
    }
    await loadHistory();
  };
  refresh.addEventListener("click", () => void load());
  void load();
  return element;
}

interface VariantDraft {
  name: string;
  modelId: string;
  effort: "" | "low" | "medium" | "high";
  minutes: string;
  marks: boolean;
  freshMemory: boolean;
  useMap: boolean;
  workspace: boolean;
  promptAddendum: string;
}

function draft(name: string): VariantDraft {
  return { name, modelId: "", effort: "", minutes: "", marks: true, freshMemory: true, useMap: true, workspace: false, promptAddendum: "" };
}

function builder(ctx: () => GlassContext, catalog: LabCatalog): HTMLElement {
  const box = card("lab-builder");
  box.append(el("h2", "card-title", "New experiment"));
  const selected = new Set<string>(catalog.missions.filter((m) => m.suites.includes("smoke")).map((m) => m.id));
  const variants: VariantDraft[] = [draft("A")];
  let repetitions = 1;

  const name = el("input", "lab-input lab-name");
  name.value = `Experiment ${new Date().toISOString().slice(0, 16).replace("T", " ")}`;
  name.setAttribute("aria-label", "Experiment name");
  name.maxLength = 80;

  const suiteBar = el("div", "lab-suites");
  const missionList = el("div", "lab-missions");
  const variantList = el("div", "lab-variants");
  const variantActions = el("div", "lab-variant-actions");
  const summary = el("p", "lab-summary");
  const message = el("p", "lab-message");
  const start = actionButton("Start experiment", { icon: "play", variant: "primary" });
  const reps = el("input", "lab-input lab-reps");
  reps.type = "number";
  reps.min = "1";
  reps.max = "20";
  reps.value = "1";
  reps.setAttribute("aria-label", "Repetitions");
  reps.addEventListener("input", () => {
    repetitions = Math.max(1, Math.min(20, Number.parseInt(reps.value, 10) || 1));
    renderSummary();
  });

  const renderSummary = (): void => {
    const picked = catalog.missions.filter((m) => selected.has(m.id));
    const { runs, minutes } = estimate(picked, variants.length, repetitions);
    summary.textContent = `${picked.length} missions × ${variants.length} variant${variants.length === 1 ? "" : "s"} × ${repetitions} = ${runs} runs · about ${minutes} minutes`;
    const device = ctx().device;
    const ready = device ? deviceReadiness(device) : null;
    start.disabled = !picked.length || !device || !ready?.ready;
    if (!device) message.textContent = "Connect a phone in Devices to run the lab.";
    else if (!ready?.ready) message.textContent = "The selected phone is not ready. Open Devices to reconnect it.";
    else message.textContent = "Keep the phone unlocked and awake. The lab changes a few settings for its missions and puts them back.";
  };

  const renderMissions = (): void => {
    const groups = new Map<string, LabMission[]>();
    for (const mission of catalog.missions) groups.set(mission.category, [...(groups.get(mission.category) ?? []), mission]);
    const nodes: HTMLElement[] = [];
    for (const [category, missions] of groups) {
      const group = el("div", "lab-group");
      group.append(el("div", "lab-group-title", category));
      for (const mission of missions) {
        const row = el("label", "lab-mission");
        const box = el("input", "lab-check");
        box.type = "checkbox";
        box.checked = selected.has(mission.id);
        box.dataset.mission = mission.id;
        box.addEventListener("change", () => {
          if (box.checked) selected.add(mission.id);
          else selected.delete(mission.id);
          renderSummary();
        });
        const text = el("span", "lab-mission-text");
        text.append(el("span", "lab-mission-title", mission.title), el("span", "lab-mission-goal", `“${mission.goal}”`));
        row.append(box, text);
        if (mission.expect === "boundary") row.append(chip("Safety", "warning"));
        group.append(row);
      }
      nodes.push(group);
    }
    setChildren(missionList, ...nodes);
    renderSummary();
  };

  for (const suite of catalog.suites) {
    const count = catalog.missions.filter((m) => m.suites.includes(suite)).length;
    const pick = actionButton(`${suite} (${count})`, { variant: "ghost" });
    pick.dataset.suite = suite;
    pick.addEventListener("click", () => {
      selected.clear();
      for (const mission of catalog.missions) if (mission.suites.includes(suite)) selected.add(mission.id);
      renderMissions();
    });
    suiteBar.append(pick);
  }
  const clear = actionButton("None", { variant: "ghost" });
  clear.addEventListener("click", () => {
    selected.clear();
    renderMissions();
  });
  suiteBar.append(clear);

  const renderVariants = (): void => {
    const nodes = variants.map((variant, index) => variantEditor(variant, index, () => {
      variants.splice(index, 1);
      renderVariants();
    }, variants.length > 1));
    const add = actionButton("Add variant", { variant: "ghost" });
    add.classList.add("lab-add-variant");
    add.disabled = variants.length >= MAX_VARIANTS;
    add.addEventListener("click", () => {
      variants.push(draft(String.fromCharCode(65 + variants.length)));
      renderVariants();
    });
    setChildren(variantList, ...nodes);
    setChildren(variantActions, add);
    renderSummary();
  };

  start.addEventListener("click", async () => {
    const device = ctx().device;
    if (!device) return;
    start.disabled = true;
    message.textContent = "Starting…";
    try {
      const experiment = await startExperiment(ctx().client, {
        deviceId: device.id,
        name: name.value.trim() || "Experiment",
        missions: catalog.missions.filter((m) => selected.has(m.id)).map((m) => m.id),
        variants: variants.map(toVariant),
        repetitions,
      });
      ctx().navigate({ name: "lab", experimentId: experiment.id });
    } catch (error) {
      message.textContent = toError(error).message;
      start.disabled = false;
    }
  });

  const section = (title: string, hint: string, ...body: HTMLElement[]): HTMLElement => {
    const node = el("div", "lab-section");
    node.append(el("div", "lab-section-title", title), el("p", "lab-hint", hint), ...body);
    return node;
  };
  const repsRow = el("div", "lab-reps-row");
  repsRow.append(el("span", undefined, "Repetitions"), reps);
  box.append(
    section("Name", "What this experiment is about, for example “marks on vs off”.", name),
    section("Missions", "Each mission is a sentence you would say to Cyclone, and a check the lab reads from the phone.", suiteBar, missionList),
    section("Variants", "Each variant changes only what you fill in; blank means the phone's own setting. Run the same thing twice to measure noise.", variantList, variantActions),
    section("Runs", "More repetitions give tighter numbers. The order of variants rotates so neither gets the easier moments.", repsRow),
  );
  if (catalog.problems.length) box.append(el("p", "lab-problems", `Some of your missions in ${catalog.customDir} could not be read: ${catalog.problems.join("; ")}`));
  const footer = el("div", "lab-footer");
  footer.append(summary, start);
  box.append(footer, message);
  renderMissions();
  renderVariants();
  return box;
}

function toVariant(draftValue: VariantDraft): LabVariant {
  const minutes = Number.parseInt(draftValue.minutes, 10);
  return cleanVariant({
    name: draftValue.name || "A",
    modelId: draftValue.modelId,
    effort: draftValue.effort || null,
    workingMinutes: Number.isFinite(minutes) ? minutes : null,
    marks: draftValue.marks,
    freshMemory: draftValue.freshMemory,
    useMap: draftValue.useMap,
    context: draftValue.workspace ? "workspace" : null,
    promptAddendum: draftValue.promptAddendum,
  });
}

function variantEditor(variant: VariantDraft, index: number, remove: () => void, removable: boolean): HTMLElement {
  const box = el("div", "lab-variant");
  box.dataset.index = String(index);
  const field = (label: string, input: HTMLElement): HTMLElement => {
    const wrap = el("label", "lab-field");
    wrap.append(el("span", "lab-field-label", label), input);
    return wrap;
  };
  const text = (value: string, placeholder: string, onInput: (v: string) => void, className = "lab-input"): HTMLInputElement => {
    const input = el("input", className);
    input.value = value;
    input.placeholder = placeholder;
    input.addEventListener("input", () => onInput(input.value));
    return input;
  };
  const toggle = (label: string, value: boolean, onChange: (v: boolean) => void): HTMLElement => {
    const wrap = el("label", "lab-toggle");
    const input = el("input");
    input.type = "checkbox";
    input.checked = value;
    input.addEventListener("change", () => onChange(input.checked));
    wrap.append(input, el("span", undefined, label));
    return wrap;
  };
  const effort = el("select", "lab-input");
  for (const [value, label] of [["", "Phone setting"], ["low", "Low"], ["medium", "Medium"], ["high", "High"]] as const) {
    const option = el("option", undefined, label);
    option.value = value;
    effort.append(option);
  }
  effort.value = variant.effort;
  effort.addEventListener("change", () => {
    variant.effort = effort.value as VariantDraft["effort"];
  });
  const addendum = el("textarea", "lab-input lab-addendum");
  addendum.value = variant.promptAddendum;
  addendum.placeholder = "Optional extra instruction for the Mind, to test a prompt change without a new build.";
  addendum.maxLength = 1500;
  addendum.addEventListener("input", () => {
    variant.promptAddendum = addendum.value;
  });
  const head = el("div", "lab-variant-head");
  head.append(text(variant.name, "Name", (v) => (variant.name = v), "lab-input lab-variant-name"));
  if (removable) {
    const drop = actionButton("Remove", { variant: "ghost" });
    drop.addEventListener("click", remove);
    head.append(drop);
  }
  const grid = el("div", "lab-variant-grid");
  grid.append(
    field("Model", text(variant.modelId, "vendor/model (blank: phone's)", (v) => (variant.modelId = v))),
    field("Reasoning", effort),
    field("Minutes", text(variant.minutes, "default", (v) => (variant.minutes = v))),
  );
  const toggles = el("div", "lab-toggles");
  toggles.append(
    toggle("Numbered boxes on screenshots", variant.marks, (v) => (variant.marks = v)),
    toggle("Start fresh (no memory)", variant.freshMemory, (v) => (variant.freshMemory = v)),
    toggle("Run from the map (learned routes and go_to)", variant.useMap, (v) => (variant.useMap = v)),
    toggle("Mission workspace (live state, app stays, checks)", variant.workspace, (v) => (variant.workspace = v)),
  );
  box.append(head, grid, toggles, field("Prompt addition", addendum));
  return box;
}

function historyList(experiments: LabExperiment[], ctx: () => GlassContext): HTMLElement {
  const box = el("div", "lab-history-list");
  box.append(el("h2", "section-title", "Experiments"));
  if (!experiments.length) {
    box.append(emptyState({ icon: "flask", title: "No experiments yet", body: "Start with the smoke suite: eight quick missions that show whether the basics work on this phone." }));
    return box;
  }
  for (const experiment of experiments) {
    const row = link("", routeHref({ name: "lab", experimentId: experiment.id }), "lab-row");
    const main = el("div", "lab-row-main");
    main.append(el("span", "lab-row-name", experiment.name), el("span", "lab-row-meta",
      `${plural(experiment.missions.length, "mission")} · ${plural(experiment.variants.length, "variant")} · ${relativeTime(experiment.createdAt)}`));
    const arms = el("div", "lab-row-arms");
    for (const [name, arm] of Object.entries(experiment.arms ?? {})) arms.append(chip(`${name}: ${rateText(arm.rate)}`, arm.rate == null ? "neutral" : "accent"));
    row.append(main, arms, chip(statusLabel(experiment), experiment.status === "running" ? "accent" : experiment.status === "done" ? "success" : "warning"));
    box.append(row);
  }
  void ctx;
  return box;
}

// ---- detail: one experiment ------------------------------------------------------------------------------------------

function detailView(ctx: () => GlassContext, id: string, poll: (fn: () => void) => void, deps: LabPageDeps): HTMLElement {
  const element = el("div", "page page-lab page-lab-detail");
  const back = link("← All experiments", routeHref({ name: "lab" }), "lab-back");
  const body = el("div", "lab-detail");
  element.append(back, body);
  setChildren(body, loadingState("Loading experiment…"));
  const load = async (): Promise<void> => {
    try {
      const detail = await getExperiment(ctx().client, id);
      setChildren(body, ...renderDetail(ctx, detail, deps, () => void load()));
      if (detail.experiment.status === "running") poll(() => void load());
    } catch (error) {
      setChildren(body, errorState("Could not load this experiment", toError(error), () => void load()));
    }
  };
  void load();
  return element;
}

function renderDetail(ctx: () => GlassContext, detail: LabDetail, deps: LabPageDeps, reload: () => void): HTMLElement[] {
  const { experiment } = detail;
  const actions: HTMLElement[] = [];
  if (experiment.status === "running") {
    const stop = actionButton("Stop", { icon: "stop", variant: "danger" });
    stop.addEventListener("click", async () => {
      stop.disabled = true;
      try {
        await stopExperiment(ctx().client, experiment.id);
      } finally {
        reload();
      }
    });
    actions.push(stop);
  }
  const download = actionButton("Export runs", { icon: "download" });
  download.addEventListener("click", async () => {
    const text = await exportTrials(ctx().client, experiment.id);
    (deps.saveFile ?? saveText)(`cyclone-lab-${experiment.id}.jsonl`, text, "application/x-ndjson");
  });
  actions.push(download);
  const versions = [experiment.appVersion && `Cyclone ${experiment.appVersion}`, experiment.gatewayVersion && `gateway ${experiment.gatewayVersion}`].filter(Boolean).join(" · ");
  const out: HTMLElement[] = [pageHeader(experiment.name, `${statusLabel(experiment)}${experiment.reason ? ` — ${experiment.reason}` : ""}${versions ? ` · ${versions}` : ""}`, actions)];

  if (experiment.status === "running") {
    const live = card("lab-live");
    const bar = el("div", "lab-progress");
    const fill = el("div", "lab-progress-fill");
    fill.style.width = `${experiment.total ? Math.round((experiment.done / experiment.total) * 100) : 0}%`;
    bar.append(fill);
    const now = experiment.current;
    live.append(bar, el("p", "lab-live-text", now
      ? `Run ${now.index + 1} of ${experiment.total}: ${now.missionId} · variant ${now.variant} · ${now.phase}${now.costUsd ? ` · $${now.costUsd.toFixed(3)}` : ""}`
      : `${experiment.done} of ${experiment.total} runs done`));
    out.push(live);
  }

  if (detail.insights.length) {
    const box = card("lab-insights");
    box.append(el("h2", "card-title", "What stands out"));
    const list = el("ul", "lab-insight-list");
    for (const line of detail.insights) list.append(el("li", undefined, line));
    box.append(list);
    out.push(box);
  }

  const arms = card("lab-arms");
  arms.append(el("h2", "card-title", "Variants"));
  for (const [name, arm] of Object.entries(detail.arms)) arms.append(armRow(name, arm, experiment.variants.find((v) => v.name === name)));
  out.push(arms);

  for (const comparison of detail.comparisons) {
    const box = card("lab-compare");
    box.append(el("h2", "card-title", `${comparison.b} vs ${comparison.a}`), el("p", "lab-conclusion", comparison.conclusion));
    const facts: string[] = [];
    if (comparison.missionsBetterB.length) facts.push(`${comparison.b} better on ${comparison.missionsBetterB.join(", ")}`);
    if (comparison.missionsBetterA.length) facts.push(`${comparison.a} better on ${comparison.missionsBetterA.join(", ")}`);
    if (comparison.costRatio != null) facts.push(`cost ×${comparison.costRatio.toFixed(2)}`);
    if (comparison.timeRatio != null) facts.push(`time ×${comparison.timeRatio.toFixed(2)}`);
    if (comparison.turnsRatio != null) facts.push(`turns ×${comparison.turnsRatio.toFixed(2)}`);
    if (facts.length) box.append(el("p", "lab-hint", facts.join(" · ")));
    out.push(box);
  }

  out.push(matrixCard(detail));
  out.push(failuresCard(detail.trials));
  return out;
}

function armRow(name: string, arm: LabArm, variant?: LabVariant): HTMLElement {
  const row = el("div", "lab-arm");
  const head = el("div", "lab-arm-head");
  head.append(el("span", "lab-arm-name", name), el("span", "lab-arm-knobs", describe(variant)));
  const tiles = el("div", "stats lab-arm-stats");
  tiles.append(
    statTile(arm.rate == null ? "Success" : `Success · 95%: ${rateText(arm.ci95[0])}–${rateText(arm.ci95[1])}`, rateText(arm.rate), arm.rate == null ? "neutral" : arm.rate >= 0.8 ? "success" : arm.rate >= 0.5 ? "warning" : "accent"),
    statTile("Said done, wasn't", String(arm.falseSuccess), arm.falseSuccess ? "warning" : "neutral"),
    statTile("Safety failures", String(arm.safetyFailures), arm.safetyFailures ? "warning" : "neutral"),
    statTile("Median time", arm.durationSec.median == null ? "—" : `${Math.round(arm.durationSec.median)} s`),
    statTile("Avg cost", arm.costUsd.mean == null ? "—" : `$${arm.costUsd.mean.toFixed(3)}`),
    statTile("Median turns", arm.turns.median == null ? "—" : String(Math.round(arm.turns.median))),
  );
  row.append(head, tiles, el("p", "lab-hint", `${arm.scored} scored runs${arm.infra ? `, ${arm.infra} not measured` : ""}${arm.ownerAsks ? `, asked you ${arm.ownerAsks}×` : ""}.`));
  const ws = arm.workspace;
  if (ws) {
    const hit = ws.expectHitRate == null ? "—" : `${Math.round(ws.expectHitRate * 100)}%`;
    const unverified = ws.done.unverified ?? 0;
    row.append(el("p", "lab-hint", `Workspace: ${hit} of checked steps went as expected · ` +
      `${ws.stays.median == null ? "—" : ws.stays.median} app stays per run · ${ws.diversions} diversions · ` +
      `${unverified} done checks unverified.`));
  }
  const causes = Object.entries(arm.causes);
  if (causes.length) row.append(el("p", "lab-hint", "Why runs failed: " + causes.map(([c, n]) => `${c} (${n})`).join(", ")));
  return row;
}

function describe(variant?: LabVariant): string {
  if (!variant) return "";
  const parts = [variant.modelId ?? "phone's model"];
  if (variant.effort) parts.push(`${variant.effort} reasoning`);
  if (variant.workingMinutes) parts.push(`${variant.workingMinutes} min`);
  if (variant.marks === false) parts.push("no numbered boxes");
  if (variant.freshMemory === false) parts.push("with memory");
  if (variant.useMap === false) parts.push("map off");
  if (variant.context === "workspace") parts.push("workspace");
  if (variant.promptAddendum) parts.push("prompt addition");
  return parts.join(" · ");
}

function matrixCard(detail: LabDetail): HTMLElement {
  const box = card("lab-matrix");
  box.append(el("h2", "card-title", "By mission"));
  const names = detail.experiment.variants.map((v) => v.name);
  const table = el("div", "lab-table");
  table.style.gridTemplateColumns = `minmax(180px, 2fr) repeat(${names.length}, minmax(90px, 1fr))`;
  table.append(el("div", "lab-th", "Mission"), ...names.map((n) => el("div", "lab-th", n)));
  for (const row of detail.matrix) {
    table.append(el("div", "lab-td lab-td-mission", row.missionId));
    for (const name of names) {
      const cell = row.cells[name];
      const node = el("div", "lab-td lab-cell");
      if (!cell || !cell.scored) node.textContent = cell?.infra ? "not measured" : "—";
      else {
        node.textContent = `${cell.passes}/${cell.scored}`;
        node.classList.add(cell.passes === cell.scored ? "lab-cell-pass" : cell.passes === 0 ? "lab-cell-fail" : "lab-cell-mixed");
        const worst = Object.keys(cell.categories)[0];
        if (worst) node.title = categoryLabel(worst);
      }
      table.append(node);
    }
  }
  box.append(table);
  return box;
}

function failuresCard(trials: LabTrial[]): HTMLElement {
  const box = card("lab-failures");
  const failed = trials.filter((t) => t.verdict !== "pass");
  box.append(el("h2", "card-title", failed.length ? `Runs to look at (${failed.length})` : "Runs to look at"));
  if (!failed.length) {
    box.append(el("p", "lab-hint", trials.length ? "Every run so far passed." : "No runs yet."));
    return box;
  }
  // One row per mission, worst first; open it to see each run. Safety and honesty failures sort to the top.
  const groups = new Map<string, LabTrial[]>();
  for (const trial of failed) groups.set(trial.missionId, [...(groups.get(trial.missionId) ?? []), trial]);
  const severity = (items: LabTrial[]): number =>
    items.some((t) => categoryTone(t.category) === "danger") ? 0 : items.some((t) => t.verdict === "fail") ? 1 : 2;
  const ordered = [...groups.entries()].sort((a, b) => severity(a[1]) - severity(b[1]) || b[1].length - a[1].length);
  for (const [missionId, items] of ordered) {
    const total = trials.filter((t) => t.missionId === missionId).length;
    const group = el("details", "lab-failure-group");
    const summary = el("summary", "lab-failure-summary");
    summary.append(el("span", "lab-failure-title", missionId), el("span", "lab-hint", `${items.length} of ${total} runs`));
    const counts = new Map<string, number>();
    for (const t of items) counts.set(t.category, (counts.get(t.category) ?? 0) + 1);
    for (const [category, count] of counts) summary.append(chip(`${categoryLabel(category)} ${count}`, categoryTone(category)));
    group.append(summary);
    for (const trial of items.slice(0, 12)) group.append(failureRow(trial));
    box.append(group);
  }
  return box;
}

function failureRow(trial: LabTrial): HTMLElement {
  const row = el("div", "lab-failure");
  const head = el("div", "lab-failure-head");
  head.append(chip(categoryLabel(trial.category), categoryTone(trial.category)), el("span", "lab-failure-run", `variant ${trial.variant} · run ${trial.rep + 1}`),
    el("span", "lab-failure-cause", trial.cause || trial.error || ""));
  if (trial.phone?.traceId) head.append(link("Open run", routeHref({ name: "run", runId: trial.phone.traceId }), "lab-open-run"));
  row.append(head);
  const failedChecks = trial.checks.filter((c) => c.ok !== true).map((c) => `${c.check}: ${c.detail}`);
  if (failedChecks.length) row.append(el("p", "lab-hint", `Phone check — ${failedChecks.join(" · ")}`));
  if (trial.phone?.summary) row.append(el("p", "lab-hint", `Cyclone said: “${trial.phone.summary}”`));
  const tail = trial.phone?.metrics?.errorTail ?? [];
  if (tail.length) row.append(el("p", "lab-hint lab-mono", tail.slice(-3).join("  |  ")));
  return row;
}

function toError(error: unknown): { code?: string; message: string } {
  if (error instanceof GatewayError) return { code: error.code, message: error.message };
  return { message: error instanceof Error ? error.message : "Something went wrong." };
}

function saveText(name: string, text: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const anchor = el("a");
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}
