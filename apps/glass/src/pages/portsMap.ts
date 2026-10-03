/**
 * Ports → Port map (plan 48, run 2): a switchboard. Ports on the left, plugins on the right, a line for every route.
 *
 * - Pick where a choice applies: Everywhere, one routine, or one app being set up. A routine or app inherits
 *   Everywhere until it makes its own choice.
 * - Each port shows its state (goes to N plugins, answered, choose one, off, unavailable, vault only) and opens a
 *   chooser: automatic, the plugins that serve it (several for an out port, one for an in port), or off.
 * - Solid lines are the routes runs use. Dashed lines are plugins that could serve the port but don't here.
 */
import type { GlassContext } from "../app.js";
import { el, button, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { command } from "../services/command.js";
import {
  bindingInfo,
  portAbout,
  portLabel,
  ports,
  scopeLabel,
  statusInfo,
  type BindingRow,
  type BindingsView,
} from "../services/ports.js";
import { primary, secondary, tile, wayGlyph } from "./portsUi.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const APP = /^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$/;

export interface PortMap {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

export function createPortMap(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void, onAdd: () => void): PortMap {
  const element = el("div", "pm");
  const scopesBar = el("div", "pm-scopes");
  scopesBar.setAttribute("role", "toolbar");
  scopesBar.setAttribute("aria-label", "Where these choices apply");
  const lead = el("p", "pm-lead");
  const board = el("div", "pm-board");
  const legend = el("div", "pm-legend");
  element.append(scopesBar, lead, board, legend);
  legend.append(legendItem("pm-line-sample", "Route a run uses"), legendItem("pm-line-sample pm-dashed", "Could serve it, not used here"),
    legendItem("pm-line-sample pm-warn", "Needs your choice"));

  let scope = "default";
  let view: BindingsView | null = null;
  let routines = new Map<string, string>();
  let open: string | null = null;
  let adding = false;
  let destroyed = false;
  let frame = 0;
  const onResize = () => scheduleLines();
  globalThis.addEventListener?.("resize", onResize);

  async function refresh(): Promise<void> {
    try {
      const [next, list] = await Promise.all([
        ports.bindings(ctx.client, scope),
        command.routines(ctx.client).catch(() => []),
      ]);
      if (destroyed) return;
      routines = new Map(list.map((r) => [r.id, r.title]));
      view = next;
      draw();
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  // ------------------------------------------------------------------------------------------------ scopes
  function drawScopes(): void {
    if (!view) return;
    const pills: HTMLElement[] = [];
    const pill = (value: string, label: string, count?: number) => {
      const node = button("", `pm-scope${value === scope ? " active" : ""}`);
      node.setAttribute("aria-pressed", String(value === scope));
      node.append(icon(value === "default" ? "port" : value.startsWith("routine:") ? "clock" : "apps"), el("span", undefined, label));
      if (count) node.append(el("span", "pm-scope-count", String(count)));
      node.addEventListener("click", () => {
        scope = value;
        open = null;
        void refresh();
      });
      pills.push(node);
    };
    pill("default", "Everywhere");
    const known = new Set(view.scopes.map((s) => s.scope));
    for (const s of view.scopes) pill(s.scope, scopeLabel(s.scope, routines), s.choices);
    if (!known.has(scope) && scope !== "default") pill(scope, scopeLabel(scope, routines));
    const add = button("", "pm-scope pm-scope-add");
    add.append(icon("plus"), el("span", undefined, "For a routine or app"));
    add.setAttribute("aria-expanded", String(adding));
    add.addEventListener("click", () => {
      adding = !adding;
      drawScopes();
    });
    pills.push(add);
    setChildren(scopesBar, ...pills, adding ? scopeAdder() : null);
  }

  function scopeAdder(): HTMLElement {
    const box = el("div", "pm-adder");
    const select = el("select", "cc-input");
    select.setAttribute("aria-label", "Routine");
    const first = el("option", undefined, routines.size ? "Choose a routine…" : "No routines yet");
    first.value = "";
    select.append(first);
    for (const [id, title] of routines) {
      const option = el("option", undefined, title);
      option.value = id;
      select.append(option);
    }
    const app = el("input", "cc-input");
    app.placeholder = "or an app, like com.instagram.android";
    app.setAttribute("aria-label", "App package");
    const go = primary("Show its map");
    go.addEventListener("click", () => {
      const pkg = app.value.trim().toLowerCase();
      if (select.value) scope = `routine:${select.value}`;
      else if (APP.test(pkg)) scope = `app:${pkg}`;
      else {
        say("Choose a routine, or type an app's package name.", "error");
        return;
      }
      adding = false;
      open = null;
      void refresh();
    });
    box.append(select, app, go);
    return box;
  }

  // ------------------------------------------------------------------------------------------------ board
  function draw(): void {
    if (!view) return;
    drawScopes();
    lead.textContent = scope === "default"
      ? "Choices here apply to every run, unless a routine or app makes its own."
      : `Choices for ${scopeName(scope)} only. Ports without a choice of their own follow Everywhere.`;
    if (!view.plugins.length) {
      const cta = primary("Add a plugin", "plus");
      cta.addEventListener("click", onAdd);
      const empty = el("div", "pm-empty");
      empty.append(el("h3", undefined, "Nothing to connect yet"), el("p", "pt-fine", "Add a plugin, and its ports appear here with a line to it."), cta);
      setChildren(board, empty);
      return;
    }
    const left = el("div", "pm-ports");
    const groups: Array<[string, BindingRow[]]> = [
      ["From the phone", view.ports.filter((r) => r.way === "out" && r.pluginServed)],
      ["To the phone", view.ports.filter((r) => r.way === "in" && r.pluginServed)],
    ];
    for (const [title, rows] of groups) {
      const group = el("section", "pm-group");
      const head = el("div", "pm-group-head");
      head.append(wayGlyph(rows[0]?.way ?? "out"), el("h3", undefined, title));
      group.append(head);
      for (const row of rows) group.append(portRow(row));
      left.append(group);
    }
    const vault = view.ports.filter((r) => !r.pluginServed);
    if (vault.length) {
      const note = el("p", "pm-vault-note");
      note.append(icon("lock"), el("span", undefined, "Passwords and keys never reach a plugin. They stay with the hub and your vault."));
      left.append(note);
    }
    const lines = document.createElementNS(SVG_NS, "svg") as SVGSVGElement;
    lines.setAttribute("class", "pm-lines");
    lines.setAttribute("aria-hidden", "true");
    const right = el("div", "pm-plugins");
    right.append(el("div", "pm-col-title", "Plugins"));
    for (const p of view.plugins) {
      const node = el("div", `pm-plugin pm-plugin-${p.status}`);
      node.dataset.plugin = p.name;
      const info = statusInfo({ status: p.status, healthDetail: "", checks: null });
      const words = el("div", "pm-plugin-words");
      words.append(el("span", "pm-plugin-title", p.title), el("span", `pm-plugin-status pt-tone-${info.tone}`, info.label));
      node.append(tile(p.name, p.title, "sm"), words);
      right.append(node);
    }
    setChildren(board, left, lines, right);
    scheduleLines();
  }

  function portRow(row: BindingRow): HTMLElement {
    const info = bindingInfo(row);
    const wrap = el("div", `pm-port pm-state-${row.state}${open === row.port ? " open" : ""}`);
    wrap.dataset.port = row.port;
    const head = button("", "pm-port-head");
    head.setAttribute("aria-expanded", String(open === row.port));
    head.setAttribute("aria-label", `${portLabel(row.port)}: ${info.label}. Change.`);
    const words = el("div", "pm-port-words");
    const name = el("div", "pm-port-name");
    name.append(el("span", undefined, portLabel(row.port)), el("code", "pt-code", row.port));
    words.append(name, el("span", "pm-port-about", portAbout(row.port, "")));
    const badge = el("span", `pm-badge pt-pill pt-pill-${info.tone}`);
    badge.append(el("span", "pt-dot"), el("span", undefined, info.label));
    head.append(words);
    if (row.override && scope !== "default") head.append(el("span", "pm-own", "Own choice"));
    head.append(badge, el("span", "pm-anchor"));
    head.addEventListener("click", () => {
      open = open === row.port ? null : row.port;
      draw();
    });
    wrap.append(head);
    if (open === row.port) wrap.append(chooser(row));
    return wrap;
  }

  function chooser(row: BindingRow): HTMLElement {
    const box = el("div", "pm-chooser");
    const info = bindingInfo(row);
    box.append(el("p", "pt-fine", info.explain));
    const single = row.way === "in";
    // What is selected in the chooser: "auto" (automatic or inherit), "off", or a set of plugins.
    let mode: "auto" | "off" | "pick" = row.override || scope === "default" ? (row.chosen === null ? "auto" : row.chosen.length ? "pick" : "off") : "auto";
    const picked = new Set(mode === "pick" ? row.chosen ?? [] : []);
    const options = el("div", "pm-options");
    options.setAttribute("role", single ? "radiogroup" : "group");
    options.setAttribute("aria-label", `Who serves ${portLabel(row.port)}`);
    const drawOptions = () => {
      const items: HTMLElement[] = [];
      const option = (key: string, title: string, sub: string, on: boolean, select: () => void, lead?: HTMLElement, disabled = false) => {
        const node = button("", `pm-option${on ? " on" : ""}`);
        node.setAttribute("role", single || key === "auto" || key === "off" ? "radio" : "checkbox");
        node.setAttribute("aria-checked", String(on));
        node.disabled = disabled;
        const mark = el("span", `pm-mark ${single || key === "auto" || key === "off" ? "round" : "square"}`);
        if (on) mark.append(icon("check"));
        const words = el("span", "pm-option-words");
        words.append(el("span", "pm-option-title", title), el("span", "pm-option-sub", sub));
        node.append(mark);
        if (lead) node.append(lead);
        node.append(words);
        node.addEventListener("click", () => {
          select();
          drawOptions();
        });
        items.push(node);
      };
      const autoTitle = scope === "default" ? "Automatic" : "Same as Everywhere";
      const autoSub = scope === "default"
        ? (single ? "The one live plugin answers. With two, you choose." : "Every live plugin that serves it.")
        : describe(row.inherits?.effective ?? [], row.inherits?.state ?? "empty");
      option("auto", autoTitle, autoSub, mode === "auto", () => { mode = "auto"; picked.clear(); });
      for (const c of row.candidates) {
        const status = statusInfo({ status: c.status, healthDetail: "", checks: null });
        option(c.name, c.title, c.live ? "Live" : `${status.label}: gets nothing until it's live`, mode === "pick" && picked.has(c.name), () => {
          if (single) picked.clear();
          if (picked.has(c.name) && !single) picked.delete(c.name);
          else picked.add(c.name);
          mode = picked.size ? "pick" : "auto";
        }, tile(c.name, c.title, "sm"));
      }
      option("off", "Off", single ? "Runs never wait on this port here." : "Runs send nothing on this port here.", mode === "off", () => { mode = "off"; picked.clear(); });
      setChildren(options, ...items);
    };
    drawOptions();
    if (!row.candidates.length) box.append(el("p", "pt-fine", "No plugin serves this port yet, or its plugins aren't allowed it. Allow it in a plugin's drawer, or add one that serves it."));
    const save = primary("Save");
    save.addEventListener("click", async () => {
      const plugins = mode === "auto" ? null : mode === "off" ? [] : [...picked];
      save.disabled = true;
      try {
        view = await ports.setBinding(ctx.client, scope, row.port, plugins);
        say(`${portLabel(row.port)}: saved for ${scope === "default" ? "every run" : scopeName(scope)}.`);
        open = null;
        draw();
      } catch (err) {
        say((err as Error).message, "error");
      } finally {
        save.disabled = false;
      }
    });
    const cancel = secondary("Cancel", undefined, "ghost");
    cancel.addEventListener("click", () => {
      open = null;
      draw();
    });
    const foot = el("div", "pm-chooser-foot");
    foot.append(cancel, save);
    box.append(options, foot);
    return box;
  }

  /** "the routine Daily post", "com.instagram.android". */
  function scopeName(value: string): string {
    if (value.startsWith("routine:")) return `the routine ${routines.get(value.slice(8)) ?? value.slice(8)}`;
    if (value.startsWith("app:")) return value.slice(4);
    return "every run";
  }

  function describe(effective: string[], state: string): string {
    if (state === "conflict") return "Everywhere hasn't chosen yet.";
    if (state === "off") return "Off everywhere.";
    if (!effective.length) return "Nothing serves it everywhere.";
    const titles = effective.map((n) => view?.plugins.find((p) => p.name === n)?.title ?? n);
    return titles.join(", ");
  }

  // ------------------------------------------------------------------------------------------------ lines
  function scheduleLines(): void {
    const raf = globalThis.requestAnimationFrame;
    if (typeof raf !== "function") return;
    if (frame) globalThis.cancelAnimationFrame?.(frame);
    frame = raf(() => {
      frame = 0;
      drawLines();
    });
  }

  function drawLines(): void {
    const svg = board.querySelector(".pm-lines") as unknown as SVGSVGElement | null;
    if (!svg || !view) return;
    const box = board.getBoundingClientRect();
    svg.setAttribute("width", String(box.width));
    svg.setAttribute("height", String(box.height));
    svg.setAttribute("viewBox", `0 0 ${box.width} ${box.height}`);
    svg.replaceChildren();
    const plugins = new Map<string, DOMRect>();
    for (const node of [...board.querySelectorAll(".pm-plugin")] as HTMLElement[]) plugins.set(node.dataset.plugin ?? "", node.getBoundingClientRect());
    for (const row of view.ports) {
      if (!row.pluginServed) continue;
      const anchor = board.querySelector(`.pm-port[data-port="${row.port}"] .pm-anchor`) as HTMLElement | null;
      if (!anchor) continue;
      const a = anchor.getBoundingClientRect();
      const x1 = a.left - box.left + a.width / 2;
      const y1 = a.top - box.top + a.height / 2;
      for (const c of row.candidates) {
        const target = plugins.get(c.name);
        if (!target) continue;
        const x2 = target.left - box.left;
        const y2 = target.top - box.top + target.height / 2;
        const used = row.effective.includes(c.name);
        const kind = row.state === "conflict" && c.live ? "warn" : used ? "used" : "idle";
        const path = document.createElementNS(SVG_NS, "path");
        const bend = Math.max(40, (x2 - x1) * 0.5);
        path.setAttribute("d", `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`);
        path.setAttribute("class", `pm-line pm-line-${kind}${open === row.port ? " focus" : ""}`);
        svg.append(path);
      }
    }
  }

  void refresh();
  // Keep plugin states current, but never redraw under an open chooser or the scope form.
  const timer = setInterval(() => {
    if (!open && !adding) void refresh();
  }, 10_000);
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      clearInterval(timer);
      globalThis.removeEventListener?.("resize", onResize);
      if (frame) globalThis.cancelAnimationFrame?.(frame);
    },
  };
}

function legendItem(sample: string, label: string): HTMLElement {
  const node = el("span", "pm-legend-item");
  node.append(el("span", sample), el("span", undefined, label));
  return node;
}
