/**
 * Command Center → Ports (plan 48): the owner's plugins and what each one may do in Cyclone runs.
 *
 * At a glance: how many plugins are live, how much of the port catalog is covered, today's messages, and anything
 * that needs the owner. Then the plugins as cards (each opens its drawer), the port catalog in two directions
 * (phone → plugin, plugin → phone) with who serves each port, and recent activity.
 *
 * Glass has no intelligence here either: the Port Hub in the gateway does the checks and the sending.
 */
import type { GlassContext } from "../app.js";
import { el, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { errorState, loadingState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import { STARTERS, portAbout, portLabel, ports, statusInfo, type CatalogPort, type Overview, type Plugin } from "../services/ports.js";
import type { GlassPage } from "./page.js";
import { openAddPlugin } from "./portsAdd.js";
import { createPortsActivity } from "./portsActivity.js";
import { createPortMap } from "./portsMap.js";
import { feedRow, openPluginSheet, type PluginSheet } from "./portsPlugin.js";
import { primary, secondary, sensitivityChip, statusPill, tile, wayGlyph } from "./portsUi.js";
import { createIdGeneratorPage } from "./idGeneratorPage.js";

const POLL_MS = 5_000;

export function createPortsPage(ctx: GlassContext, view: "plugins" | "map" | "activity" | "id-generator" = "plugins"): GlassPage {
  if (view === "id-generator") return createIdGeneratorPage(ctx);
  const element = el("div", "page page-ports");
  const header = el("header", "pt-page-head");
  const titles = el("div", "pt-page-titles");
  const eyebrow = el("div", "pt-eyebrow");
  eyebrow.append(icon("port"), el("span", undefined, "Cyclone Ports"));
  titles.append(eyebrow, el("h1", "pt-page-title", "Ports"),
    el("p", "pt-page-lead", "Plug your own tools into Cyclone runs. Each plugin gets only the ports you allow, and it's live only after it passes its checks."));
  const add = primary("Add plugin", "plus");
  add.addEventListener("click", () => openAdd());
  header.append(titles, add);
  const tabs = el("nav", "pt-tabs");
  tabs.setAttribute("aria-label", "Ports views");
  for (const [id, label, glyph] of [["plugins", "Plugins", "plug"], ["map", "Port map", "port"], ["activity", "Activity", "pulse"]] as const) {
    const tab = el("a", `pt-tab${id === view ? " active" : ""}`);
    tab.href = id === "plugins" ? "#/command/ports" : `#/command/ports/${id}`;
    if (id === view) tab.setAttribute("aria-current", "page");
    tab.append(icon(glyph), el("span", undefined, label));
    tabs.append(tab);
  }
  const note = el("p", "pt-note pt-page-note");
  note.setAttribute("role", "status");
  note.setAttribute("aria-live", "polite");
  const body = el("div", "pt-page-body");
  body.append(loadingState("Loading Ports…"));
  const starter = el("a", "card idg-starter");
  starter.href = "#/command/ports/id-generator";
  starter.append(tile("id-generator", "ID Generator"), el("strong", undefined, "ID Generator"),
    el("span", undefined, "Built-in starter · Local MRZ Studio · Company IDs, portraits, MRZ and signatures"),
    el("span", "btn btn-secondary", "Set up & generate"));
  element.append(header, tabs, starter, note, body);
  const say = (text: string, tone: "ok" | "error" = "ok") => {
    note.textContent = text;
    note.classList.toggle("pt-note-error", tone === "error");
  };
  const map = view === "map" ? createPortMap(ctx, say, () => openAdd())
    : view === "activity" ? createPortsActivity(ctx, element, say) : null;
  if (map) setChildren(body, map.element);

  let data: Overview | null = null;
  let rendered = false;
  let destroyed = false;
  let drawer: PluginSheet | null = null;
  let lastStatus = new Map<string, string>();

  const titleOf = (name: string) => (name === "ports" ? "Port map" : data?.plugins.find((p) => p.name === name)?.title ?? name);

  function openAdd(endpoint?: string): void {
    openAddPlugin(ctx, element, { endpoint, onChanged: () => void (map ? map.refresh() : load()) });
  }

  function openPlugin(name: string): void {
    drawer?.close();
    drawer = openPluginSheet(ctx, element, name, () => void load(), () => {
      drawer = null;
    });
  }

  async function load(): Promise<void> {
    if (map) return;
    try {
      const next = await ports.overview(ctx.client);
      if (destroyed) return;
      data = next;
      render();
      rendered = true;
      const now = new Map(next.plugins.map((p) => [p.name, `${p.status}|${p.seenAt}|${p.checkedAt}|${p.paused}`]));
      if (drawer && now.get(drawer.name) !== lastStatus.get(drawer.name)) void drawer.refresh();
      lastStatus = now;
    } catch (err) {
      // A failed poll keeps what is on screen; only a page that never drew shows the error.
      if (destroyed || rendered) return;
      setChildren(body, errorState("Ports didn't load", err as Error, () => void load()));
    }
  }

  function render(): void {
    if (!data) return;
    const d = data;
    if (!d.plugins.length) {
      setChildren(body, welcome(), catalogSection(d), );
      return;
    }
    setChildren(body, summary(d), attention(d.plugins, d.conflicts), pluginsSection(d.plugins), catalogSection(d), activitySection(d));
  }

  // ---------------------------------------------------------------------------------------------- summary
  function summary(d: Overview): HTMLElement {
    const live = d.plugins.filter((p) => p.status === "active").length;
    const servable = d.catalog.filter((c) => c.pluginServed);
    const covered = servable.filter((c) => c.servedBy.length).length;
    const waiting = d.plugins.filter((p) => p.status !== "active" && p.status !== "paused").length + d.conflicts.length;
    const strip = el("section", "pt-summary");
    strip.setAttribute("aria-label", "Ports at a glance");
    strip.append(
      stat("Live plugins", `${live}`, `of ${d.plugins.length}`, live ? "success" : "neutral"),
      stat("Ports covered", `${covered}`, `of ${servable.length}`, "accent", covered / Math.max(1, servable.length)),
      stat("Messages today", `${d.today.messages}`, d.today.failures ? `${d.today.failures} failed` : "all delivered", d.today.failures ? "warning" : "neutral"),
      stat("Needs you", `${waiting}`, waiting ? "see below" : "nothing", waiting ? "warning" : "neutral"),
    );
    return strip;
  }

  function stat(label: string, value: string, sub: string, tone: string, fill?: number): HTMLElement {
    const node = el("div", `pt-stat pt-stat-${tone}`);
    const top = el("div", "pt-stat-top");
    top.append(el("span", "pt-stat-value", value), el("span", "pt-stat-sub", sub));
    node.append(el("span", "pt-stat-label", label), top);
    if (fill != null) {
      const bar = el("div", "pt-meter");
      const inner = el("span", "pt-meter-fill");
      inner.style.setProperty("--fill", `${Math.round(fill * 100)}%`);
      bar.append(inner);
      bar.setAttribute("role", "img");
      bar.setAttribute("aria-label", `${Math.round(fill * 100)}% of ports covered`);
      node.append(bar);
    }
    return node;
  }

  // ---------------------------------------------------------------------------------------------- needs you
  function attention(plugins: Plugin[], conflicts: string[]): HTMLElement | null {
    const needing = plugins.filter((p) => !["active", "paused"].includes(p.status));
    if (!needing.length && !conflicts.length) return null;
    const box = el("section", "pt-attention");
    box.setAttribute("aria-label", "Needs you");
    for (const port of conflicts) {
      const row = el("div", "pt-attention-row pt-tone-warning");
      const text = el("div", "pt-attention-text");
      text.append(el("strong", undefined, `${portLabel(port)}: choose who serves it`),
        el("span", undefined, "Two plugins could answer, or the chosen one isn't live. Runs get nothing on it until you choose."));
      const glyph = el("span", "pt-way pt-way-in");
      glyph.append(icon("port"));
      const go = secondary("Open the port map");
      go.addEventListener("click", () => ctx.navigate({ name: "command", tab: "ports", view: "map" }));
      row.append(glyph, text, go);
      box.append(row);
    }
    for (const p of needing) {
      const info = statusInfo(p);
      const row = el("div", `pt-attention-row pt-tone-${info.tone}`);
      const text = el("div", "pt-attention-text");
      text.append(el("strong", undefined, `${p.title}: ${info.label.toLowerCase()}`), el("span", undefined, info.explain));
      const open = secondary(info.actionLabel || "Open");
      open.addEventListener("click", () => openPlugin(p.name));
      row.append(tile(p.name, p.title, "sm"), text, open);
      box.append(row);
    }
    return box;
  }

  // ---------------------------------------------------------------------------------------------- plugins
  function pluginsSection(plugins: Plugin[]): HTMLElement {
    const section = el("section", "pt-section");
    section.append(sectionHead("Plugins", `${plugins.length}`));
    const grid = el("div", "pt-grid");
    for (const p of plugins) grid.append(pluginCard(p));
    const more = el("button", "pt-card pt-card-add");
    more.type = "button";
    const plus = el("span", "pt-card-add-icon");
    plus.append(icon("plus"));
    more.append(plus, el("span", "pt-card-add-label", "Add a plugin"), el("span", "pt-fine", "A logger, an SMS bridge, a sheet, your own tool…"));
    more.addEventListener("click", () => openAdd());
    grid.append(more);
    section.append(grid);
    return section;
  }

  function pluginCard(p: Plugin): HTMLElement {
    const card = el("button", `pt-card pt-card-${p.status}`);
    card.type = "button";
    card.setAttribute("aria-label", `${p.title}, ${statusInfo(p).label}. Open.`);
    const top = el("div", "pt-card-top");
    const who = el("div", "pt-card-who");
    const line = el("div", "pt-card-line");
    line.append(statusPill(p), el("span", "pt-card-addr", p.endpoint.replace(/^https?:\/\//, "")));
    who.append(el("span", "pt-card-title", p.title), line);
    top.append(tile(p.name, p.title), who);
    const about = el("p", "pt-card-about", p.description || " ");
    const chips = el("div", "pt-chips");
    for (const s of p.serves.slice(0, 6)) {
      const chip = el("span", `pt-chip${s.allowed ? "" : " off"}`);
      chip.append(icon(s.way === "out" ? "out" : "in"), el("span", undefined, portLabel(s.port)));
      if (!s.allowed) chip.title = "Switched off";
      chips.append(chip);
    }
    if (p.serves.length > 6) chips.append(el("span", "pt-chip pt-chip-more", `+${p.serves.length - 6}`));
    const foot = el("div", "pt-card-foot");
    foot.append(el("span", undefined, p.seenAt ? `Seen ${relativeTime(p.seenAt)}` : "Not seen yet"), el("span", undefined, p.version ? `v${p.version}` : ""));
    card.append(top, about, chips, foot);
    card.addEventListener("click", () => openPlugin(p.name));
    return card;
  }

  // ---------------------------------------------------------------------------------------------- catalog
  function catalogSection(d: Overview): HTMLElement {
    const section = el("section", "pt-section");
    section.append(sectionHead("The port catalog", "cyclone.ports/1"),
      el("p", "pt-section-lead", "Every point where a run can send something out or wait for something to come in. A port works in a run once a live plugin serves it."));
    const cols = el("div", "pt-catalog");
    cols.append(catalogColumn("From the phone", "What a run can send to a plugin", d.catalog.filter((c) => c.way === "out")),
      catalogColumn("To the phone", "What a plugin can deliver to a waiting run", d.catalog.filter((c) => c.way === "in")));
    section.append(cols);
    if (d.extensions.length) {
      const ext = el("p", "pt-fine");
      ext.append(el("strong", undefined, "Extension ports: "), el("span", undefined, d.extensions.join(", ")));
      section.append(ext);
    }
    return section;
  }

  function catalogColumn(title: string, lead: string, rows: CatalogPort[]): HTMLElement {
    const col = el("div", "pt-catalog-col");
    const head = el("div", "pt-catalog-head");
    const words = el("div");
    words.append(el("h3", "pt-catalog-title", title), el("p", "pt-fine", lead));
    head.append(wayGlyph(rows[0]?.way ?? "out"), words);
    col.append(head);
    const list = el("ul", "pt-catalog-list");
    for (const c of rows) {
      const row = el("li", `pt-catalog-row${c.servedBy.length ? " served" : ""}${c.pluginServed ? "" : " vault"}`);
      const text = el("div", "pt-catalog-text");
      const name = el("div", "pt-catalog-name");
      name.append(el("span", undefined, portLabel(c.port)), el("code", "pt-code", c.port));
      text.append(name, el("p", "pt-port-summary", portAbout(c.port, c.summary)));
      const by = el("div", "pt-catalog-by");
      if (!c.pluginServed) {
        const vault = el("span", "pt-vault");
        vault.append(icon("lock"), el("span", undefined, "Vault only"));
        by.append(vault);
      } else if (c.servedBy.length) {
        const stack = el("span", "pt-stack");
        for (const n of c.servedBy.slice(0, 3)) {
          const t = tile(n, titleOf(n), "sm");
          t.title = titleOf(n);
          stack.append(t);
        }
        by.append(stack);
      } else {
        by.append(el("span", "pt-open", "No plugin"));
      }
      row.append(text, sensitivityChip(c.sensitivity), by);
      list.append(row);
    }
    col.append(list);
    return col;
  }

  // ---------------------------------------------------------------------------------------------- activity
  function activitySection(d: Overview): HTMLElement {
    const section = el("section", "pt-section");
    section.append(sectionHead("Activity", "metadata only"));
    if (!d.activity.length) {
      section.append(el("p", "pt-fine", "Nothing has happened yet."));
      return section;
    }
    const feed = el("ol", "pt-feed");
    for (const a of d.activity.slice(0, 20)) feed.append(feedRow(a, true, titleOf(a.plugin)));
    section.append(feed);
    return section;
  }

  // ---------------------------------------------------------------------------------------------- first visit
  function welcome(): HTMLElement {
    const hero = el("section", "pt-welcome");
    const art = el("div", "pt-welcome-art");
    art.setAttribute("aria-hidden", "true");
    for (let i = 0; i < 4; i += 1) art.append(el("span", `pt-orbit pt-orbit-${i + 1}`));
    const core = el("span", "pt-welcome-core");
    core.append(icon("port"));
    art.append(core);
    const text = el("div", "pt-welcome-text");
    text.append(el("h2", "pt-welcome-title", "Plug your tools into Cyclone runs"),
      el("p", "pt-welcome-body", "A plugin can log what a run does, hand the phone a file from this PC, or deliver a verification code from your other phone. You decide which ports each plugin gets."));
    const cta = primary("Add your first plugin", "plus");
    cta.addEventListener("click", () => openAdd());
    text.append(cta);
    hero.append(art, text);

    const starters = el("div", "pt-starter-grid");
    for (const s of STARTERS) {
      const card = el("div", "pt-starter-card");
      const top = el("div", "pt-card-top");
      const who = el("div", "pt-card-who");
      who.append(el("span", "pt-card-title", s.title), el("span", "pt-card-addr", s.endpoint.replace("http://", "")));
      top.append(tile(s.title.toLowerCase().replace(/\s+/g, "-"), s.title), who);
      const chips = el("div", "pt-chips");
      for (const port of s.ports) {
        const chip = el("span", "pt-chip");
        chip.append(icon(port.endsWith(".in") ? "in" : "out"), el("span", undefined, portLabel(port)));
        chips.append(chip);
      }
      const go = secondary("Add", "plus");
      go.addEventListener("click", () => openAdd(s.endpoint));
      card.append(top, el("p", "pt-card-about", s.about), chips, go);
      starters.append(card);
    }
    const wrap = el("div", "pt-welcome-wrap");
    wrap.append(hero, sectionHead("Start with an example", "from tools/cyclone-ports-sdk"), starters,
      el("p", "pt-fine", "Building your own? Give your developer or agent Cyclone V5 plan/HANDOFF-build-a-connector.md."));
    return wrap;
  }

  void load();
  const timer = setInterval(() => void load(), POLL_MS);
  return {
    element,
    destroy() {
      destroyed = true;
      clearInterval(timer);
      map?.destroy();
      drawer?.close();
    },
  };
}

function sectionHead(title: string, aside: string): HTMLElement {
  const head = el("div", "pt-section-head");
  head.append(el("h2", "pt-section-title", title));
  if (aside) head.append(el("span", "pt-section-aside", aside));
  return head;
}
