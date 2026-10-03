/**
 * Ports → Activity (plan 48, run 3): what went through the ports.
 *
 * - **Test runs:** the owner plays a fixed scenario through the real hub and real plugins (events, a sign-up with a
 *   code, an image from the PC, a value) and watches each step land, before a phone run depends on it.
 * - **Runs:** every run that used a port, each opening its lane: what went out, what it waited for, what answered,
 *   in order.
 * - **Everything:** the log, filtered by plugin, port and outcome.
 *
 * Metadata only: Glass is never sent a code. A code that came in says so and how long it is.
 */
import type { GlassContext } from "../app.js";
import { el, button, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import {
  TEST_SCENARIOS,
  activityText,
  portLabel,
  ports,
  scopeLabel,
  type Activity,
  type PortRun,
  type TestRun,
} from "../services/ports.js";
import { openSheet, primary, secondary, sheetHeader, tile, wayGlyph } from "./portsUi.js";

const POLL_MS = 4_000;
const LIVE_MS = 1_000;
const OUT_KINDS = new Set(["emit"]);

export interface PortsActivity {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

export function createPortsActivity(ctx: GlassContext, host: HTMLElement, say: (text: string, tone?: "ok" | "error") => void): PortsActivity {
  const element = el("div", "pa");
  const bar = el("div", "pa-bar");
  const filters = el("div", "pa-filters");
  const start = primary("Start a test run", "play");
  start.addEventListener("click", () => openTestRun());
  bar.append(filters, start);
  const testsBox = el("section", "pt-section");
  const runsBox = el("section", "pt-section");
  const feedBox = el("section", "pt-section");
  element.append(bar, testsBox, runsBox, feedBox);

  let filter: { plugin: string; port: string; status: "" | "ok" | "failed" } = { plugin: "", port: "", status: "" };
  let titles = new Map<string, string>();
  let portsSeen: string[] = [];
  let runs: PortRun[] = [];
  let tests: TestRun[] = [];
  let feed: Activity[] = [];
  const open = new Set<string>();
  const lanes = new Map<string, Activity[]>();
  let destroyed = false;

  const titleOf = (name: string) => (name === "ports" ? "Port map" : titles.get(name) ?? name);

  async function refresh(): Promise<void> {
    try {
      const [overview, both, entries] = await Promise.all([
        ports.overview(ctx.client),
        ports.runs(ctx.client),
        ports.activity(ctx.client, filter),
      ]);
      if (destroyed) return;
      titles = new Map(overview.plugins.map((p) => [p.name, p.title]));
      portsSeen = overview.catalog.filter((c) => c.pluginServed).map((c) => c.port);
      runs = both.runs;
      tests = both.testRuns;
      feed = entries;
      for (const runId of open) lanes.set(runId, (await ports.run(ctx.client, runId)).activity);
      draw();
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  // ------------------------------------------------------------------------------------------------ filters
  function drawFilters(): void {
    const plugin = el("select", "cc-input pa-select");
    plugin.setAttribute("aria-label", "Plugin");
    plugin.append(option("", "All plugins", filter.plugin === ""));
    for (const [name, title] of titles) plugin.append(option(name, title, filter.plugin === name));
    plugin.addEventListener("change", () => {
      filter = { ...filter, plugin: plugin.value };
      void refresh();
    });
    const port = el("select", "cc-input pa-select");
    port.setAttribute("aria-label", "Port");
    port.append(option("", "All ports", filter.port === ""));
    for (const p of portsSeen) port.append(option(p, portLabel(p), filter.port === p));
    port.addEventListener("change", () => {
      filter = { ...filter, port: port.value };
      void refresh();
    });
    const status = el("div", "pa-status");
    status.setAttribute("role", "radiogroup");
    status.setAttribute("aria-label", "Outcome");
    for (const [value, label] of [["", "All"], ["ok", "Went through"], ["failed", "Failed"]] as const) {
      const chip = button(label, `pm-scope${filter.status === value ? " active" : ""}`);
      chip.setAttribute("role", "radio");
      chip.setAttribute("aria-checked", String(filter.status === value));
      chip.addEventListener("click", () => {
        filter = { ...filter, status: value };
        void refresh();
      });
      status.append(chip);
    }
    setChildren(filters, plugin, port, status);
  }

  // ------------------------------------------------------------------------------------------------ test runs
  function drawTests(): void {
    if (!tests.length) {
      setChildren(testsBox);
      return;
    }
    const list = el("div", "pa-tests");
    for (const run of tests.slice(0, 4)) list.append(testCard(run, false));
    setChildren(testsBox, sectionHead("Test runs", "played through your plugins"), list);
  }

  function testCard(run: TestRun, large: boolean): HTMLElement {
    const card = el("div", `pa-test pa-test-${run.state}${large ? " large" : ""}`);
    const head = el("div", "pa-test-head");
    const words = el("div", "pa-test-words");
    words.append(el("strong", undefined, run.title), el("span", "pt-fine", [
      relativeTime(run.startedAt),
      run.routine ? scopeLabel(`routine:${run.routine}`) : run.app ? scopeLabel(`app:${run.app}`) : "",
    ].filter(Boolean).join(" · ")));
    head.append(stateBadge(run.state), words);
    if (run.state === "running") {
      const stop = secondary("Stop", "stop", "ghost");
      stop.addEventListener("click", async () => {
        try {
          await ports.stopTestRun(ctx.client, run.runId);
          void refresh();
        } catch (err) {
          say((err as Error).message, "error");
        }
      });
      head.append(stop);
    }
    const track = el("ol", "pa-track");
    track.setAttribute("aria-label", `${run.title} steps`);
    for (const step of run.steps) {
      const item = el("li", `pa-step pa-step-${step.state}`);
      const dot = el("span", "pa-step-dot");
      if (step.state === "ok") dot.append(icon("check"));
      else if (step.state === "bad") dot.append(icon("close"));
      item.append(dot);
      const words = el("div", "pa-step-words");
      const name = el("span", "pa-step-name");
      name.append(wayGlyph(step.kind === "await" ? "in" : "out"), el("span", undefined, step.label || (step.kind === "await" ? "Wait for " : "Send ") + portLabel(step.port).toLowerCase()));
      words.append(name);
      const detail = step.state === "now" && step.kind === "await"
        ? `${step.detail || "Asking"}… ${awaitHint(step.port)}`
        : step.detail;
      if (detail && (large || step.state === "bad" || step.state === "now")) words.append(el("span", "pa-step-detail", detail));
      item.append(words);
      track.append(item);
    }
    card.append(head, track);
    return card;
  }

  function awaitHint(port: string): string {
    if (port === "code.in") return "Send the code to the phone or inbox your plugin watches.";
    if (port === "file.in") return "Drop an image in the folder your plugin watches.";
    return "The plugin has up to 2 minutes.";
  }

  // ------------------------------------------------------------------------------------------------ runs and lanes
  function drawRuns(): void {
    if (!runs.length) {
      setChildren(runsBox, sectionHead("Runs", ""), emptyLine("No run has used a port yet. Start a test run to see one here."));
      return;
    }
    const list = el("div", "pa-runs");
    for (const run of runs.slice(0, 15)) {
      const row = el("div", `pa-run${open.has(run.runId) ? " open" : ""}`);
      const head = button("", "pa-run-head");
      head.setAttribute("aria-expanded", String(open.has(run.runId)));
      const test = run.runId.startsWith("run_test_");
      const chevron = el("span", "pa-chevron");
      chevron.append(icon("chevron"));
      head.append(
        el("span", `pa-run-glyph${run.failures ? " bad" : ""}`),
        el("code", "pt-code", run.runId),
        test ? el("span", "pa-tag", "Test") : el("span"),
        el("span", "pa-run-meta", `${run.messages} ${run.messages === 1 ? "message" : "messages"}${run.failures ? ` · ${run.failures} failed` : ""}`),
        el("span", "pa-run-time", relativeTime(run.lastAt)),
        chevron,
      );
      head.addEventListener("click", async () => {
        if (open.has(run.runId)) open.delete(run.runId);
        else {
          open.add(run.runId);
          try {
            lanes.set(run.runId, (await ports.run(ctx.client, run.runId)).activity);
          } catch (err) {
            say((err as Error).message, "error");
          }
        }
        drawRuns();
      });
      row.append(head);
      if (open.has(run.runId)) row.append(lane(lanes.get(run.runId) ?? []));
      list.append(row);
    }
    setChildren(runsBox, sectionHead("Runs", "each opens its lane"), list);
  }

  function lane(entries: Activity[]): HTMLElement {
    const node = el("ol", "pa-lane");
    if (!entries.length) node.append(el("li", "pt-fine", "Nothing recorded."));
    for (const a of entries) {
      const item = el("li", `pa-lane-item ${a.ok ? "ok" : "bad"}`);
      const glyph = a.port ? wayGlyph(OUT_KINDS.has(a.kind) || a.kind === "run" ? "out" : "in") : el("span", "pa-lane-blank");
      const words = el("div", "pa-lane-words");
      words.append(el("span", "pa-lane-text", activityText(a)));
      const meta = el("span", "pa-lane-meta");
      if (a.plugin && a.plugin !== "ports") meta.append(tile(a.plugin, titleOf(a.plugin), "sm"), el("span", undefined, titleOf(a.plugin)));
      meta.append(el("span", undefined, [a.latencyMs != null ? `${a.latencyMs} ms` : "", relativeTime(a.at)].filter(Boolean).join(" · ")));
      words.append(meta);
      item.append(el("span", "pa-lane-dot"), glyph, words);
      node.append(item);
    }
    return node;
  }

  // ------------------------------------------------------------------------------------------------ everything
  function drawFeed(): void {
    const head = sectionHead("Everything", "metadata only");
    if (!feed.length) {
      setChildren(feedBox, head, emptyLine(filter.plugin || filter.port || filter.status ? "Nothing matches these filters." : "Nothing has happened yet."));
      return;
    }
    const list = el("ol", "pt-feed");
    for (const a of feed.slice(0, 60)) {
      const row = el("li", `pt-feed-row ${a.ok ? "ok" : "bad"}`);
      const text = el("span", "pt-feed-text");
      text.append(el("strong", "pt-feed-who", titleOf(a.plugin)), el("span", undefined, activityText(a)));
      if (a.runId) text.append(el("code", "pt-code pa-feed-run", a.runId));
      row.append(el("span", "pt-feed-dot"), text, el("span", "pt-feed-meta", [a.latencyMs != null ? `${a.latencyMs} ms` : "", relativeTime(a.at)].filter(Boolean).join(" · ")));
      list.append(row);
    }
    setChildren(feedBox, head, list);
  }

  function draw(): void {
    drawFilters();
    drawTests();
    drawRuns();
    drawFeed();
  }

  // ------------------------------------------------------------------------------------------------ the test run sheet
  function openTestRun(): void {
    let chosen = "events";
    let live: TestRun | null = null;
    let timer: ReturnType<typeof setInterval> | null = null;
    const sheet = openSheet(host, "modal", "Start a test run", () => {
      if (timer) clearInterval(timer);
      void refresh();
    });
    const body = el("div", "pt-sheet-body");
    const foot = el("footer", "pt-sheet-foot");
    sheet.panel.append(sheetHeader("Start a test run", "Play a run through your plugins with test details, and watch each step land.", sheet.close), body, foot);

    const choose = () => {
      const grid = el("div", "pa-scenarios");
      grid.setAttribute("role", "radiogroup");
      grid.setAttribute("aria-label", "Test");
      for (const s of TEST_SCENARIOS) {
        const card = button("", `pa-scenario${s.id === chosen ? " on" : ""}`);
        card.setAttribute("role", "radio");
        card.setAttribute("aria-checked", String(s.id === chosen));
        const chips = el("div", "pt-chips");
        for (const port of s.ports) {
          const chip = el("span", "pt-chip");
          chip.append(icon(port.endsWith(".in") ? "in" : "out"), el("span", undefined, portLabel(port)));
          chips.append(chip);
        }
        card.append(el("span", "pa-scenario-title", s.title), el("span", "pa-scenario-about", s.about), chips);
        card.addEventListener("click", () => {
          chosen = s.id;
          choose();
        });
        grid.append(card);
      }
      const app = el("input", "cc-input");
      app.placeholder = "Optional: an app's choices, like com.instagram.android";
      app.setAttribute("aria-label", "Use the Port map choices of this app");
      const field = el("label", "pt-field");
      field.append(el("span", "pt-field-label", "Which Port map choices"), app);
      setChildren(body, grid, field, el("p", "pt-fine", "Uses the Port map's routes. Test details only (Sam Example); a code that comes in is dropped at once."));
      const cancel = secondary("Cancel", undefined, "ghost");
      cancel.addEventListener("click", sheet.close);
      const go = primary("Start", "play");
      go.addEventListener("click", async () => {
        go.disabled = true;
        try {
          const pkg = app.value.trim().toLowerCase();
          live = await ports.startTestRun(ctx.client, chosen, pkg ? { app: pkg } : {});
          watch();
        } catch (err) {
          say((err as Error).message, "error");
          go.disabled = false;
        }
      });
      setChildren(foot, cancel, go);
    };

    const watch = () => {
      if (!live) return;
      setChildren(body, testCard(live, true));
      const stop = secondary("Stop", "stop", "ghost");
      stop.addEventListener("click", async () => {
        if (live) live = await ports.stopTestRun(ctx.client, live.runId).catch(() => live);
      });
      const done = primary(live.state === "running" ? "Hide" : "Done");
      done.addEventListener("click", sheet.close);
      setChildren(foot, ...(live.state === "running" ? [stop, done] : [done]));
      if (!timer) {
        timer = setInterval(async () => {
          if (!live) return;
          try {
            live = await ports.testRun(ctx.client, live.runId);
            watch();
            if (live.state !== "running" && timer) {
              clearInterval(timer);
              timer = null;
            }
          } catch {
            /* keep the last view */
          }
        }, LIVE_MS);
      }
    };
    choose();
  }

  draw();
  void refresh();
  const poll = setInterval(() => void refresh(), POLL_MS);
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      clearInterval(poll);
    },
  };
}

function option(value: string, label: string, selected: boolean): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  node.selected = selected;
  return node;
}

function sectionHead(title: string, aside: string): HTMLElement {
  const head = el("div", "pt-section-head");
  head.append(el("h2", "pt-section-title", title));
  if (aside) head.append(el("span", "pt-section-aside", aside));
  return head;
}

function emptyLine(text: string): HTMLElement {
  return el("p", "pa-empty", text);
}

function stateBadge(state: TestRun["state"]): HTMLElement {
  const label = { running: "Running", done: "Went through", needs_you: "Needs a look", stopped: "Stopped" }[state];
  const tone = { running: "accent", done: "success", needs_you: "warning", stopped: "neutral" }[state];
  const pill = el("span", `pt-pill pt-pill-${tone}`);
  pill.append(el("span", "pt-dot"), el("span", undefined, label));
  return pill;
}
