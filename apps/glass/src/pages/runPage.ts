/**
 * Run inspector (V5 plan 11): one run step by step. The cause of death and the step it happened on come from the
 * phone; Glass lays them out so a developer can see exactly where the run broke and what would fix it.
 */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import {
  causeLabel,
  causeTone,
  formatDuration,
  getRun,
  lastGoodRun,
  learnRun,
  listRuns,
  markRun,
  routeSplits,
  appName,
  outcomeLabel,
  roomLabel,
  statusLabel,
  statusTone,
  type RunDetail,
  type RunPlace,
  type RunSummary,
  type RunStep,
} from "../services/runs.js";
import { getScenarios, healthLabel, healthTone, type Scenario } from "../services/knowledge.js";
import { routeHref } from "../core/router.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, keyValue, loadingState, statTile, type Tone } from "../ui/components.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import { askErrorCopy } from "./askPanel.js";
import { phoneClient } from "../services/phone.js";
import type { GlassPage } from "./page.js";
import { runsError } from "./runsPage.js";

export interface RunPageDeps {
  fetch?: typeof fetch;
  /** Save a file for the developer; defaults to a Blob download. */
  saveFile?: (name: string, text: string) => void;
  /** Timer for following a running run (defaults to setTimeout / clearTimeout). */
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/** How often a running run is re-read from the phone. */
export const LIVE_RUN_MS = 3_000;

/** Causes a developer fixes in the map (remap a room, teach a door). */
const MAP_CAUSES = new Set(["stale-door", "wrong-room", "door-missing", "element-not-found", "unchanged"]);

const OUTCOME_TONE: Record<RunStep["outcome"], Tone> = {
  ok: "success",
  failed: "danger",
  unverified: "warning",
  recovered: "accent",
  info: "neutral",
};

export function createRunPage(ctx: GlassContext, route: Extract<Route, { name: "run" }>, deps: RunPageDeps = {}): GlassPage {
  const element = el("div", "page page-run");
  const back = link("", "#/runs", "back-link");
  back.append(icon("back"), el("span", undefined, "Runs"));
  element.append(back);
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const controller = new AbortController();
  const body = el("div", "run-body");
  element.append(body);
  setChildren(body, loadingState("Loading this run from the phone…"));

  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  let timer: unknown = null;
  /** The step the developer picked; null follows the phone (the fatal step, else the newest). */
  let pinnedStep: number | null = null;

  const load = async (): Promise<void> => {
    timer = null;
    try {
      const run = await getRun(ctx.client, deviceId, route.runId, controller.signal);
      if (controller.signal.aborted) return;
      render(run);
      // Live: a run still going on the phone is re-read until it ends.
      if (run.status === "running") timer = setTimer(() => void load(), LIVE_RUN_MS);
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      setChildren(body, runsError(error, () => void load()));
    }
  };

  const render = (run: RunDetail): void => {
    let selected = pinnedStep ?? run.cause?.stepIndex ?? run.steps.at(-1)?.index ?? 0;
    const timeline = el("ol", "timeline");
    const detail = el("section", "step-detail");

    const header = el("header", "run-header");
    const titles = el("div", "run-titles");
    titles.append(el("h1", "page-title run-title", run.goal));
    const meta = el("div", "run-meta");
    meta.append(
      chip(statusLabel(run.status), statusTone(run.status)),
      el("span", "muted", `Started ${relativeTime(run.startedAt)} · took ${formatDuration(run.durationMs)} · ${run.model || "model unknown"}`),
    );
    titles.append(meta);
    const download = actionButton("Download report", { icon: "download" });
    download.addEventListener("click", () => {
      const save = deps.saveFile ?? saveWithBlob;
      save(`cyclone-run-${run.runId}.json`, JSON.stringify(run, null, 2));
    });
    const headerActions = el("div", "run-header-actions");
    if (run.expected !== null && run.status !== "completed") {
      const expected = run.expected;
      const mark = actionButton(expected ? "Count it again" : "Mark as expected", { variant: "ghost" });
      mark.title = "Expected runs stay in Runs but stop counting against scenario health.";
      mark.addEventListener("click", async () => {
        mark.disabled = true;
        try {
          run.expected = await markRun(ctx.client, deviceId, run.runId, !expected);
          render(run);
        } catch (error) {
          mark.disabled = false;
          mark.title = error instanceof Error ? error.message : String(error);
        }
      });
      headerActions.append(mark);
    }
    const again = actionButton("Ask again", { icon: "send", variant: "ghost" });
    again.title = "Send the same sentence to the phone. It runs on the phone exactly as before.";
    again.addEventListener("click", async () => {
      again.disabled = true;
      try {
        await phoneClient(ctx, deviceId, deps.fetch).askStart(run.goal);
        ctx.navigate({ name: "phone" });
      } catch (error) {
        again.disabled = false;
        const e = error as { code?: string; message?: string };
        again.title = askErrorCopy(e?.code ?? "", e?.message ?? "");
        meta.append(chip(again.title, "warning"));
      }
    });
    if (run.goal && run.status !== "running" && run.model !== "cyclone-mapper") headerActions.append(again);
    if (run.status !== "running" && run.model !== "cyclone-mapper") {
      const learn = actionButton("Learn", { variant: "ghost" });
      learn.title = "Learn every screen, control and move this run saw, so the next run on these apps starts knowing where things are.";
      learn.addEventListener("click", async () => {
        learn.disabled = true;
        learn.textContent = "Learning…";
        try {
          const result = await learnRun(ctx.client, deviceId, run.runId);
          learn.textContent = result.learned ? "Learned" : "Learn";
          learn.disabled = result.learned;
          meta.append(chip(result.learned ? result.sentence : result.refusal ?? "", result.learned ? "success" : "warning"));
        } catch (error) {
          learn.disabled = false;
          learn.textContent = "Learn";
          meta.append(chip(error instanceof Error ? error.message : String(error), "warning"));
        }
      });
      headerActions.append(learn);
    }
    headerActions.append(download);
    if (run.expected) meta.append(chip("Marked expected", "neutral"));
    if (run.status === "running") meta.append(chip("Live · updating", "accent"));
    header.append(titles, headerActions);

    const stats = el("div", "stats stats-6");
    stats.append(
      statTile("Steps", String(run.stepCount)),
      statTile("Actions", String(run.metrics.toolCalls)),
      statTile("Failed actions", String(run.metrics.toolFailures), run.metrics.toolFailures ? "danger" : "neutral"),
      statTile("Failed checks", String(run.metrics.verificationFailures), run.metrics.verificationFailures ? "warning" : "neutral"),
      statTile("Recoveries", String(run.metrics.recoveries), run.metrics.recoveries ? "accent" : "neutral"),
      statTile("Vision checks", String(run.metrics.visionChecks)),
    );
    if (run.mapSteps != null && run.modelSteps != null) {
      const decided = run.mapSteps + run.modelSteps;
      stats.className = "stats stats-7";
      stats.append(statTile("From the map", decided ? `${run.mapSteps} of ${decided}` : "—", run.mapSteps ? "success" : "neutral"));
    }

    const parts: Array<HTMLElement | null> = [header, stats];
    if (run.cause) {
      const cause = card(`cause-card cause-${causeTone(run.cause.kind)}`);
      const top = el("div", "cause-top");
      top.append(el("span", "cause-kicker", "Cause of death"), chip(causeLabel(run.cause.kind), causeTone(run.cause.kind)));
      cause.append(top, el("h2", "cause-headline", run.cause.headline));
      if (run.cause.detail) cause.append(el("p", "cause-detail", run.cause.detail));
      const fix = el("p", "cause-fix");
      fix.append(el("strong", undefined, "Fix: "), el("span", undefined, run.cause.fix));
      cause.append(fix);
      const causeActions = el("div", "cause-actions");
      if (run.cause.stepIndex != null) {
        const go = actionButton(`Go to step ${run.cause.stepIndex + 1}`, { icon: "chevron" });
        go.addEventListener("click", () => select(run.cause!.stepIndex!));
        causeActions.append(go);
        // Map problems get a way to the map: the failing room lit up, or the app's versions after an update.
        const failing = run.steps.find((s) => s.index === run.cause!.stepIndex);
        const rooms = [failing?.roomId, failing?.roomAfter].filter((room): room is string => !!room);
        if (failing?.placeId && rooms.length && MAP_CAUSES.has(run.cause.kind)) {
          const fix = actionButton("Open the room on the map", { icon: "map", variant: "primary" });
          fix.addEventListener("click", () => ctx.navigate({ name: "app", placeId: failing.placeId!, tab: "map", route: rooms, runId: run.runId }));
          causeActions.append(fix);
        }
        if (failing?.placeId && run.cause.kind === "stale-door") {
          const versions = actionButton("See app versions", { icon: "refresh" });
          versions.addEventListener("click", () => ctx.navigate({ name: "app", placeId: failing.placeId!, tab: "versions" }));
          causeActions.append(versions);
        }
      }
      cause.append(causeActions);
      parts.push(cause);
    } else if (run.status === "completed") {
      const done = card("cause-card cause-success");
      done.append(el("span", "cause-kicker", "Finished"), el("p", "cause-detail", run.result || "Cyclone reported the goal as done."));
      parts.push(done);
    }

    if (run.clauses.length) {
      const clauses = card("clauses-card");
      clauses.append(el("h2", "card-title", "Clauses"));
      const rows = el("ol", "event-list");
      for (const clause of run.clauses) {
        const row = el("li", "event");
        row.append(el("strong", undefined, clause.text), chip(clause.status.replaceAll("-", " "),
          clause.status === "verified" ? "success" : clause.status === "failed" ? "danger" : "neutral"));
        if (clause.place) row.append(el("p", "muted", appName(clause.place)));
        if (clause.proof) row.append(el("p", undefined, clause.proof));
        rows.append(row);
      }
      clauses.append(rows);
      parts.push(clauses);
    }
    if (run.ledger.length) {
      const facts = card("ledger-card");
      facts.append(el("h2", "card-title", "Observed facts · masked"));
      for (const fact of run.ledger) {
        facts.append(keyValue([[fact.key.replaceAll("-", " "), fact.value], ["Read in", appName(fact.sourcePlace)],
          ["Room", roomLabel(fact.sourceRoom)], ["Observed", relativeTime(fact.readAtMs)]]));
      }
      parts.push(facts);
    }
    if (run.places.length) parts.push(routeCard(run));
    if (run.status === "failed" || run.status === "cancelled") {
      const compare = card("compare-card");
      compare.hidden = true;
      parts.push(compare);
      void compareWithLastGood(run, compare);
    }

    const layout = el("div", "run-layout");
    const left = el("section", "timeline-card");
    left.append(el("h2", "card-title", "Steps"));
    if (run.stepsTruncated) left.append(el("p", "muted", `Showing the first ${run.steps.length} steps.`));
    left.append(timeline);
    layout.append(left, detail);
    parts.push(layout);
    setChildren(body, ...parts);

    const renderTimeline = (): void => {
      setChildren(
        timeline,
        ...run.steps.map((step) => {
          const item = el("li", `timeline-item outcome-${step.outcome}${step.index === selected ? " selected" : ""}${run.cause?.stepIndex === step.index ? " fatal" : ""}`);
          const button = el("button", "timeline-button");
          button.type = "button";
          button.dataset.step = String(step.index);
          button.append(
            el("span", "timeline-dot"),
            el("span", "timeline-index", String(step.index + 1)),
            el("span", "timeline-text", step.title),
          );
          if (step.action) button.append(el("span", "timeline-action", step.action));
          if (step.decisionSource === "map") button.append(el("span", "timeline-source", "map"));
          button.addEventListener("click", () => select(step.index));
          item.append(button);
          return item;
        }),
      );
    };

    const renderDetail = (): void => {
      const step = run.steps.find((s) => s.index === selected);
      if (!step) {
        setChildren(detail, el("p", "muted", "No steps were recorded for this run."));
        return;
      }
      const head = el("div", "step-head");
      head.append(el("span", "cause-kicker", `Step ${step.index + 1}`), chip(outcomeLabel(step.outcome), OUTCOME_TONE[step.outcome]));
      if (run.cause?.stepIndex === step.index) head.append(chip("Where it broke", "danger"));
      const facts = keyValue(
        [
          ["Action", step.action ?? "—"],
          ["Chosen by", step.decisionSource === "map" ? "A known route (no model call)" : step.decisionSource === "model" ? "The model" : step.action?.startsWith("mapper:") ? "The mapper, exploring" : "—"],
          ["App", step.placeId ? `${appName(step.placeId)}${step.appVersion ? ` · version ${step.appVersion}` : ""}` : "—"],
          ["Room", step.roomId ? roomLabel(step.roomId) : "not recorded"],
          ["Expected room", step.expectedRoomId ? roomLabel(step.expectedRoomId) : "—"],
          ["Room after", step.roomAfter ? roomLabel(step.roomAfter) : "—"],
          ["Screen", step.pageId ? `page …${step.pageId}` : "not recorded"],
          ["Verification", step.verification ?? "—"],
          ["Recovery", step.recovery ?? "—"],
          ["Vision", step.vision ? "Used a screenshot" : "Structured UI only"],
          ["Offset", `+${formatDuration(step.startedAt - run.startedAt)}`],
        ] as Array<[string, string]>,
      );
      const events = el("ol", "event-list");
      for (const event of step.events) {
        const row = el("li", `event event-${event.ok === false ? "bad" : event.ok === true ? "good" : "neutral"}`);
        const line = el("div", "event-line");
        line.append(el("span", "event-kind", event.kind), el("span", "event-text", event.text));
        if (event.code) line.append(el("code", "event-code", event.code));
        row.append(line);
        if (event.detail) row.append(el("div", "event-detail", event.detail));
        events.append(row);
      }
      setChildren(
        detail,
        replayStrip(step),
        head,
        el("h2", "step-title", step.title),
        facts,
        el("h3", "inspector-section", "What happened"),
        events,
        step.eventsTruncated ? el("p", "muted", "Later events in this step were trimmed.") : null,
        openRoom(step),
        el(
          "p",
          "muted inspector-note",
          step.roomId ? "Before/after screenshots for each step arrive in a later alpha." : "This phone did not record rooms for this step (older Cyclone, or the step had no screen).",
        ),
      );
    };

    /**
     * Replay (plan 22 §4.5): step through the run like a film. Structural only: what the map expected, what was done,
     * where the phone landed, how it ended and who chose it. No screenshots.
     */
    const stepPosition = (index: number): number => run.steps.findIndex((s) => s.index === index);
    const stepBy = (delta: number): void => {
      const at = stepPosition(selected);
      const next = run.steps[Math.max(0, Math.min(run.steps.length - 1, at + delta))];
      if (next && next.index !== selected) select(next.index);
    };
    const replayStrip = (step: RunStep): HTMLElement => {
      const strip = el("div", "replay");
      const at = stepPosition(step.index);
      const nav = el("div", "replay-nav");
      const prev = actionButton("Previous", { variant: "ghost" });
      prev.classList.add("replay-prev");
      prev.disabled = at <= 0;
      prev.addEventListener("click", () => stepBy(-1));
      const next = actionButton("Next", { variant: "ghost" });
      next.classList.add("replay-next");
      next.disabled = at >= run.steps.length - 1;
      next.addEventListener("click", () => stepBy(1));
      nav.append(prev, el("span", "replay-count", `Step ${at + 1} of ${run.steps.length}`), next, el("span", "muted replay-hint", "← → to step"));
      const film = el("div", "replay-film");
      for (const s of run.steps) {
        const frame = el("button", `replay-frame outcome-${s.outcome}${s.index === step.index ? " current" : ""}${s.decisionSource === "map" ? " map" : ""}`) as HTMLButtonElement;
        frame.type = "button";
        frame.dataset.step = String(s.index);
        frame.title = `${s.index + 1}. ${s.title}`;
        frame.textContent = String(s.index + 1);
        frame.addEventListener("click", () => select(s.index));
        film.append(frame);
      }
      const expected = step.expectedRoomId ? roomLabel(step.expectedRoomId) : step.decisionSource === "model" ? "The model chose; no map promise" : "—";
      const observed = step.roomAfter ? roomLabel(step.roomAfter) : "not recorded";
      const matched = step.expectedRoomId && step.roomAfter ? step.expectedRoomId === step.roomAfter : null;
      const cells = el("div", "replay-cells");
      const cell = (label: string, value: string, tone?: string): HTMLElement => {
        const node = el("div", `replay-cell${tone ? ` ${tone}` : ""}`);
        node.append(el("span", "replay-label", label), el("span", "replay-value", value));
        return node;
      };
      cells.append(
        cell("Expected", expected),
        cell("Action", step.action ?? "—"),
        cell("Observed", observed, matched === false ? "miss" : matched ? "hit" : undefined),
        cell("Result", matched === false ? `${outcomeLabel(step.outcome)} · landed elsewhere` : outcomeLabel(step.outcome), step.outcome === "failed" ? "miss" : undefined),
        cell("Source", step.decisionSource === "map" ? "Map" : step.decisionSource === "model" ? "Model" : step.action?.startsWith("mapper:") ? "Mapper" : "—"),
      );
      strip.append(nav, film, cells);
      const rooms = [step.expectedRoomId, step.roomAfter, step.roomId].filter((room): room is string => !!room);
      if (step.placeId && rooms.length) {
        const show = el("a", "replay-map-link", "Show on the map") as HTMLAnchorElement;
        show.href = "#";
        show.addEventListener("click", (event) => {
          event.preventDefault();
          ctx.navigate({ name: "app", placeId: step.placeId!, tab: "map", route: [...new Set(rooms)], runId: run.runId });
        });
        strip.append(show);
      }
      return strip;
    };
    element.onkeydown = (event: KeyboardEvent): void => {
      const target = event.target as HTMLElement | null;
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
      if (event.key === "ArrowRight") stepBy(1);
      else if (event.key === "ArrowLeft") stepBy(-1);
      else return;
      event.preventDefault();
    };

    const openRoom = (step: RunStep): HTMLElement | null => {
      const rooms = [step.roomId, step.roomAfter].filter((room): room is string => !!room);
      if (!step.placeId || !rooms.length) return null;
      const go = actionButton("Open this room on the map", { icon: "map" });
      go.addEventListener("click", () => ctx.navigate({ name: "app", placeId: step.placeId!, tab: "map", route: rooms, runId: run.runId }));
      return go;
    };

    function routeCard(detail: RunDetail): HTMLElement {
      const node = card("route-card");
      node.append(el("h2", "card-title", "Route on the map"));
      for (const place of detail.places) node.append(placeRoute(detail, place));
      return node;
    }

    function placeRoute(detail: RunDetail, place: RunPlace): HTMLElement {
      const row = el("div", "route-row");
      const head = el("div", "route-head");
      head.append(el("strong", undefined, appName(place.placeId)), el("span", "muted", place.appVersion ? `version ${place.appVersion}` : "version not recorded"));
      const rooms = el("ol", "route-rooms");
      place.route.forEach((room, index) => {
        const item = el("li", "route-room");
        item.append(el("span", "route-badge", String(index + 1)), el("span", undefined, roomLabel(room)));
        rooms.append(item);
      });
      row.append(head, place.route.length ? rooms : el("p", "muted", "No rooms recorded in this app."));
      if (place.route.length) {
        const show = actionButton("Show on the map", { icon: "map", variant: "primary" });
        show.addEventListener("click", () => ctx.navigate({ name: "app", placeId: place.placeId, tab: "map", route: place.route, runId: detail.runId }));
        row.append(show);
        const touched = el("div", "route-scenarios");
        row.append(touched);
        void scenariosReached(place, touched);
      }
      return row;
    }

    /** A failed run next to the last finished run of the same goal: where did the routes split? */
    async function compareWithLastGood(detail: RunDetail, into: HTMLElement): Promise<void> {
      let good: RunSummary | null;
      try {
        good = lastGoodRun(detail, await listRuns(ctx.client, deviceId, "completed", 100, controller.signal));
      } catch {
        return;
      }
      if (!good || controller.signal.aborted) return;
      const splits = routeSplits(detail, good).filter((split) => split.shared.length || split.goodNext);
      const head = el("div", "cause-top");
      head.append(el("span", "cause-kicker", "Compared with the last good run"), chip("Finished", "success"));
      const open = link(`Open the good run (${relativeTime(good.startedAt)})`, `#/runs/${encodeURIComponent(good.runId)}`, "compare-open");
      setChildren(into, head);
      if (!splits.length) {
        into.append(el("p", "muted", "The good run did not record rooms, so the routes cannot be compared."), open);
      }
      for (const split of splits) {
        const row = el("div", "compare-row");
        row.append(el("strong", undefined, appName(split.placeId)));
        const text =
          split.goodNext === null
            ? "This run walked every room the good run did."
            : `Both runs reached ${split.shared.length ? roomLabel(split.shared.at(-1)!) : "the app"}. The good run went on to ${roomLabel(split.goodNext)}; this run ${split.thisNext ? `went to ${roomLabel(split.thisNext)} instead` : "stopped there"}.`;
        row.append(el("p", "compare-text", text));
        if (split.goodNext) {
          const rooms = [...split.shared.slice(-1), split.goodNext, ...(split.thisNext ? [split.thisNext] : [])];
          const show = actionButton("Show the split on the map", { icon: "map" });
          show.addEventListener("click", () => ctx.navigate({ name: "app", placeId: split.placeId, tab: "map", route: rooms, runId: detail.runId }));
          row.append(show);
        }
        into.append(row);
      }
      if (splits.length) into.append(open);
      into.hidden = false;
    }

    /** Scenarios whose destination this run reached (Glass matches ids; the phone computed both). */
    async function scenariosReached(place: RunPlace, into: HTMLElement): Promise<void> {
      let scenarios: Scenario[];
      try {
        scenarios = (await getScenarios(ctx.client, deviceId, place.placeId, "mapping", controller.signal)).scenarios;
      } catch {
        return; // older phones or unmapped apps: nothing to add
      }
      const rooms = new Set(place.route);
      const reached = scenarios.filter((scenario) => rooms.has(scenario.endScreenId));
      if (!reached.length) return;
      const list = el("div", "route-scenario-list");
      for (const scenario of reached.slice(0, 8)) {
        const open = el("a", "route-scenario");
        open.href = routeHref({ name: "app", placeId: place.placeId, tab: "scenarios" });
        open.append(el("span", undefined, scenario.title), chip(healthLabel(scenario.health), healthTone(scenario.health)));
        list.append(open);
      }
      setChildren(into, el("span", "cause-kicker", "Scenarios this run reached"), list);
    }

    const select = (index: number, byUser = true): void => {
      if (byUser) pinnedStep = index;
      selected = index;
      renderTimeline();
      renderDetail();
    };
    select(selected, false);
  };

  void load();
  return {
    element,
    destroy: () => {
      controller.abort();
      if (timer !== null) clearTimer(timer);
    },
  };
}

export function saveWithBlob(name: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
  const anchor = el("a");
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
