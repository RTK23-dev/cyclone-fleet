/**
 * The Command Center's home (plan 33, C5): what needs you, what runs now and next, your phones, and your pages.
 * Quiet by design: a greeting, a few sections, and one-click ways to start a page from a template.
 */
import type { GlassContext } from "../app.js";
import { command, taskStatusLabel, taskStatusTone, type CcApproval, type CcRoutine, type CcTask } from "../services/command.js";
import { deviceReadiness } from "../services/devices.js";
import { pagesApi, type PageMeta, type Template } from "../services/pages.js";
import { chip } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime, whenLabel } from "../ui/format.js";
import type { GlassPage } from "../pages/page.js";
import { workspaceBus } from "./directory.js";

const TEMPLATES: Array<{ id: Template; icon: string; title: string; body: string }> = [
  { id: "blank", icon: "📄", title: "Empty page", body: "Write, plan, and mention anything with @" },
  { id: "weekly", icon: "🗓️", title: "Weekly plan", body: "A board for the week and what runs now" },
  { id: "daily", icon: "✅", title: "Daily app check", body: "A checklist, approvals, phones and routines" },
  { id: "content", icon: "🎬", title: "Content calendar", body: "Posts on a calendar, results below" },
];

export function createWorkspaceHome(ctx: GlassContext, now = () => new Date()): GlassPage {
  const element = el("div", "page ws-page-screen");
  const doc = el("div", "ws-doc ws-home");
  element.append(doc);
  const hello = el("h1", "ws-title-static");
  const lead = el("p", "ws-lead");
  const templates = el("div", "ws-templates");
  const inbox = el("section", "ws-section");
  const running = el("section", "ws-section");
  const phones = el("section", "ws-section");
  const recent = el("section", "ws-section");
  const next = el("section", "ws-section");
  const note = el("p", "ws-note");
  doc.append(hello, lead, templates, note, inbox, recent, running, next, phones);
  let destroyed = false;

  const hour = now().getHours();
  hello.textContent = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
  lead.textContent = now().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" });

  for (const t of TEMPLATES) {
    const b = el("button", "ws-template");
    b.type = "button";
    b.append(el("span", "ws-template-icon", t.icon), el("span", "ws-template-title", t.title), el("span", "ws-template-body", t.body));
    b.addEventListener("click", () => void pagesApi.create(ctx.client, { template: t.id }).then((page) => {
      workspaceBus.pagesChanged();
      ctx.navigate({ name: "command", tab: "page", pageId: page.id });
    }).catch((err: Error) => { note.textContent = err.message; }));
    templates.append(b);
  }

  const section = (node: HTMLElement, title: string, href: string | null, rows: HTMLElement[], empty: string) => {
    const head = el("div", "ws-section-head");
    head.append(el("h2", "ws-section-title", title));
    if (href) {
      const a = el("a", "ws-section-link", "Open");
      a.href = href;
      head.append(a);
    }
    setChildren(node, head, ...(rows.length ? rows : [el("p", "ws-view-empty", empty)]));
  };
  const row = (icon: string, title: string, meta: string, href: string, tag?: HTMLElement) => {
    const a = el("a", "ws-row");
    a.href = href;
    a.append(el("span", "ws-row-icon", icon), el("span", "ws-row-title", title));
    if (tag) a.append(tag);
    a.append(el("span", "ws-row-meta", meta));
    return a;
  };

  function draw(approvals: CcApproval[], tasks: CcTask[], routines: CcRoutine[], pages: PageMeta[]): void {
    section(inbox, approvals.length ? `Waiting for you · ${approvals.length}` : "Waiting for you", "#/command/approvals",
      approvals.slice(0, 5).map((a) => row(a.kind === "spend" ? "🔌" : "✋", a.text || a.title, relativeTime(a.createdAt), "#/command/approvals")),
      "Nothing is waiting for you.");
    const live = tasks.filter((t) => ["running", "needs_you", "making", "waiting_device", "scheduled"].includes(t.status));
    section(running, "Running and queued", "#/command/tasks",
      live.slice(0, 6).map((t) => row("▶️", t.title, t.cause || relativeTime(t.createdAt), "#/command/tasks", chip(taskStatusLabel(t.status), taskStatusTone(t.status)))),
      "No task is running. Start one from a page's plan, or from Tasks.");
    const upcoming = routines.filter((r) => !r.paused && r.nextRunAt).sort((a, b) => (a.nextRunAt ?? 0) - (b.nextRunAt ?? 0));
    section(next, "Coming up", "#/command/routines",
      upcoming.slice(0, 5).map((r) => row("🔁", r.title, `${whenLabel(r.nextRunAt)} · ${r.scheduleLabel}`, "#/command/routines")),
      "No routine is scheduled.");
    const sorted = pages.slice().sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 8);
    const gallery = el("div", "ws-gallery");
    for (const p of sorted) {
      const tile = el("a", "ws-tile");
      tile.href = `#/command/page/${encodeURIComponent(p.id)}`;
      tile.append(el("div", "ws-tile-icon", p.icon || "📄"), el("div", "ws-tile-title", p.title), el("div", "ws-tile-meta", `Edited ${relativeTime(p.updatedAt)}`));
      gallery.append(tile);
    }
    section(recent, "Recent pages", null, sorted.length ? [gallery] : [], "No pages yet. Pick a template above, or press + New page in the sidebar.");
    const tiles = el("div", "ws-gallery");
    for (const d of ctx.devices) {
      const ready = deviceReadiness(d).ready;
      const tile = el("a", "ws-tile");
      tile.href = "#/devices";
      const doing = tasks.find((t) => (t.run?.deviceId ?? t.deviceId) === d.id && (t.status === "running" || t.status === "needs_you"));
      tile.append(el("div", "ws-tile-icon", "📱"), el("div", "ws-tile-title", d.name), chip(ready ? "Ready" : "Not ready", ready ? "success" : "warning"),
        el("div", "ws-tile-meta", doing ? `Doing: ${doing.title}` : d.mobileVersion ? `Cyclone ${d.mobileVersion}` : ""));
      tiles.append(tile);
    }
    section(phones, "Phones", null, ctx.devices.length ? [tiles] : [], "No phone is connected. Switch to Cyclone Glass (the logo, top left) to connect one.");
  }

  async function load(): Promise<void> {
    const settle = <T>(p: Promise<T>, f: T) => p.catch(() => f);
    const [approvals, tasks, routines, pages] = await Promise.all([
      settle(command.approvals(ctx.client), [] as CcApproval[]), settle(command.tasks(ctx.client, "open"), [] as CcTask[]),
      settle(command.routines(ctx.client), [] as CcRoutine[]), settle(pagesApi.tree(ctx.client), [] as PageMeta[]),
    ]);
    if (!destroyed) draw(approvals, tasks, routines, pages);
  }
  void load();
  const timer = setInterval(() => void load(), 10_000);
  const unlisten = workspaceBus.onPagesChanged(() => void load());
  return { element, destroy() { destroyed = true; clearInterval(timer); unlisten(); }, update(next) { ctx = next; void load(); } };
}
