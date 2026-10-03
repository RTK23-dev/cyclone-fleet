/**
 * Live views inside a page (plan 33, C5): the Command Center's own data, always current. A view reads through the same
 * gateway routes as the database screens and never changes anything by itself; each row opens where it lives.
 */
import type { GlassContext } from "../app.js";
import { command, taskStatusLabel, taskStatusTone, type CcTask, type TaskStatus } from "../services/command.js";
import { deviceReadiness } from "../services/devices.js";
import { monthGrid, pagesApi, sameDay, type Block, type ViewLayout, type ViewSource } from "../services/pages.js";
import { chip } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime, whenLabel } from "../ui/format.js";

type View = Extract<Block, { type: "view" }>;

const SOURCE_LABEL: Record<ViewSource, string> = {
  tasks: "Tasks", routines: "Routines", approvals: "Waiting for you", phones: "Phones", pages: "Pages inside", results: "Results",
  accounts: "Accounts", connections: "Connections",
};
export const LAYOUTS_FOR: Record<ViewSource, ViewLayout[]> = {
  tasks: ["list", "table", "board", "calendar"], routines: ["table", "list"], approvals: ["list"], phones: ["gallery", "list"],
  pages: ["gallery", "list"], results: ["list", "table"], accounts: ["table", "list"], connections: ["list", "table"],
};
const LAYOUT_LABEL: Record<ViewLayout, string> = { list: "List", table: "Table", board: "Board", calendar: "Calendar", gallery: "Gallery" };
const REFRESH_MS = 10_000;

interface Row {
  icon: string;
  title: string;
  status?: { label: string; tone: "neutral" | "accent" | "success" | "warning" | "danger" };
  cells: string[];
  when?: number | null;
  group?: string;
  open(): void;
}

export interface LiveView {
  element: HTMLElement;
  destroy(): void;
}

/** A live view block. [change] saves the view's own settings (layout, filter), never the data it shows. */
export function createLiveView(ctx: GlassContext, block: View, pageId: string | null, change: (next: View) => void): LiveView {
  const element = el("div", "ws-view");
  const head = el("div", "ws-view-head");
  const body = el("div", "ws-view-body");
  element.append(head, body);
  let destroyed = false;
  let month = new Date();

  function drawHead(): void {
    const title = el("span", "ws-view-title", block.title || SOURCE_LABEL[block.source]);
    const layouts = el("div", "ws-seg");
    for (const layout of LAYOUTS_FOR[block.source]) {
      const b = el("button", `ws-seg-item${layout === block.layout ? " active" : ""}`, LAYOUT_LABEL[layout]);
      b.type = "button";
      b.setAttribute("aria-pressed", String(layout === block.layout));
      b.addEventListener("click", () => {
        block = { ...block, layout };
        change(block);
        drawHead();
        void load();
      });
      layouts.append(b);
    }
    const tools: HTMLElement[] = [];
    if (block.source === "tasks") {
      const status = el("select", "ws-select");
      status.setAttribute("aria-label", "Which tasks");
      for (const [v, l] of [["open", "Open"], ["done", "Finished"], ["all", "All"]] as const) {
        const o = el("option", undefined, l);
        o.value = v;
        status.append(o);
      }
      status.value = block.filter.status ?? "open";
      status.addEventListener("change", () => {
        block = { ...block, filter: { ...block.filter, status: status.value as "open" | "done" | "all" } };
        change(block);
        void load();
      });
      const phone = el("select", "ws-select");
      phone.setAttribute("aria-label", "On which phone");
      const any = el("option", undefined, "Every phone");
      any.value = "";
      phone.append(any, ...ctx.devices.map((d) => {
        const o = el("option", undefined, d.name);
        o.value = d.id;
        return o;
      }));
      phone.value = block.filter.deviceId ?? "";
      phone.addEventListener("change", () => {
        const filter = { ...block.filter };
        if (phone.value) filter.deviceId = String(phone.value);
        else delete filter.deviceId;
        block = { ...block, filter };
        change(block);
        void load();
      });
      tools.push(status, phone);
    }
    setChildren(head, title, ...tools, layouts);
  }

  async function rows(): Promise<Row[]> {
    const go = ctx.navigate;
    switch (block.source) {
      case "tasks": {
        const status = block.filter.status ?? "open";
        let tasks = await command.tasks(ctx.client, status === "all" ? undefined : status);
        if (block.filter.deviceId) tasks = tasks.filter((t) => (t.run?.deviceId ?? t.deviceId) === block.filter.deviceId);
        if (block.filter.routineId) tasks = tasks.filter((t) => t.routineId === block.filter.routineId);
        return tasks.slice(0, 100).map((t) => taskRow(ctx, t));
      }
      case "routines":
        return (await command.routines(ctx.client)).map((r) => ({
          icon: "🔁", title: r.title, status: r.paused ? { label: "Paused", tone: "neutral" } : { label: "On", tone: "success" },
          cells: [r.scheduleLabel, r.nextRunAt && !r.paused ? `Next ${whenLabel(r.nextRunAt)}` : "—", `${r.succeeded} done · ${r.failed} failed`],
          when: r.nextRunAt, open: () => go({ name: "command", tab: "routines" }),
        }));
      case "approvals":
        return (await command.approvals(ctx.client)).map((a) => ({
          icon: a.kind === "spend" ? "🔌" : a.kind === "login" ? "🔐" : "✋", title: a.text || a.title,
          status: { label: a.kind === "spend" ? "Connection" : a.kind === "login" ? "Vault" : "Phone", tone: "warning" },
          cells: [a.title, relativeTime(a.createdAt)], open: () => go({ name: "command", tab: "approvals" }),
        }));
      case "phones":
        return ctx.devices.map((d) => {
          const ready = deviceReadiness(d);
          return { icon: "📱", title: d.name, status: ready.ready ? { label: "Ready", tone: "success" } : { label: "Not ready", tone: "warning" },
            cells: [d.mobileVersion ? `Cyclone ${d.mobileVersion}` : "—"], open: () => go({ name: "devices" }) };
        });
      case "results":
        return (await command.results(ctx.client)).slice(0, 50).map((r) => ({
          icon: r.status === "succeeded" ? "✅" : r.status === "failed" ? "⚠️" : "▶️", title: r.title,
          status: { label: taskStatusLabel(r.status === "running" ? "running" : (r.status as TaskStatus)), tone: taskStatusTone(r.status) },
          cells: [r.summary || r.cause || "", relativeTime(r.startedAt)], when: r.startedAt, open: () => go({ name: "command", tab: "results" }),
        }));
      case "accounts":
        return (await command.accounts(ctx.client)).map((a) => ({
          icon: "👤", title: a.handle, status: { label: a.status === "active" ? "Active" : "Paused", tone: a.status === "active" ? "success" : "neutral" },
          cells: [a.service, a.lastOutcome ?? ""], open: () => go({ name: "command", tab: "accounts" }),
        }));
      case "connections":
        return (await command.connections(ctx.client)).connections.map((c) => ({
          icon: "🔌", title: c.name, status: { label: c.status === "ready" ? "Ready" : "Needs you", tone: c.status === "ready" ? "success" : "warning" },
          cells: [c.kind === "api" ? "API" : c.kind === "local" ? "On this PC" : "MCP server", `${c.allowed.length} tools on`], open: () => go({ name: "command", tab: "connections" }),
        }));
      case "pages": {
        if (!pageId) return [];
        const page = await pagesApi.get(ctx.client, pageId);
        return page.children.map((p) => ({ icon: p.icon || "📄", title: p.title, cells: [`Edited ${relativeTime(p.updatedAt)}`],
          open: () => go({ name: "command", tab: "page", pageId: p.id }) }));
      }
    }
  }

  async function load(): Promise<void> {
    let list: Row[];
    try {
      list = await rows();
    } catch (err) {
      if (!destroyed) setChildren(body, el("p", "ws-view-empty", (err as Error).message || "This view could not load."));
      return;
    }
    if (destroyed) return;
    if (!list.length) {
      setChildren(body, el("p", "ws-view-empty", block.source === "approvals" ? "Nothing is waiting for you." : "Nothing here yet."));
      return;
    }
    setChildren(body, renderRows(list, block.layout, block.source === "tasks" ? TASK_GROUPS : null, () => month, (m) => {
      month = m;
      void load();
    }));
  }

  drawHead();
  void load();
  const timer = setInterval(() => void load(), REFRESH_MS);
  return { element, destroy() { destroyed = true; clearInterval(timer); } };
}

export const TASK_GROUPS = ["Waiting", "Running", "Needs you", "Done", "Stopped"];

function taskGroup(status: TaskStatus): string {
  return status === "running" ? "Running" : status === "needs_you" ? "Needs you" : status === "succeeded" ? "Done"
    : status === "failed" || status === "cancelled" ? "Stopped" : "Waiting";
}

export function taskRow(ctx: GlassContext, t: CcTask): Row {
  const phone = ctx.devices.find((d) => d.id === (t.run?.deviceId ?? t.deviceId))?.name ?? (t.deviceId ? t.deviceId : "Any ready phone");
  return {
    icon: t.status === "succeeded" ? "✅" : t.status === "failed" ? "⚠️" : t.status === "needs_you" ? "✋" : "▶️", title: t.title,
    status: { label: taskStatusLabel(t.status), tone: taskStatusTone(t.status) },
    cells: [phone, t.run?.summary || t.cause || ""], when: t.dueAt ?? t.createdAt, group: taskGroup(t.status),
    open: () => ctx.navigate({ name: "command", tab: "tasks" }),
  };
}

/** Rows as a list, table, board (by group), calendar (by date) or gallery. Shared with the Tasks database. */
export function renderRows(rows: Row[], layout: ViewLayout, groups: string[] | null, getMonth: () => Date, setMonth: (m: Date) => void): HTMLElement {
  const open = (row: Row, node: HTMLElement) => {
    node.tabIndex = 0;
    node.setAttribute("role", "link");
    node.addEventListener("click", () => row.open());
    node.addEventListener("keydown", (e: KeyboardEvent) => { if (e.key === "Enter") row.open(); });
    return node;
  };
  if (layout === "table") {
    const table = el("table", "ws-table");
    for (const row of rows) {
      const tr = open(row, el("tr"));
      const name = el("td", "ws-td-name");
      name.append(el("span", "ws-row-icon", row.icon), el("span", undefined, row.title));
      tr.append(name);
      const st = el("td");
      if (row.status) st.append(chip(row.status.label, row.status.tone));
      tr.append(st, ...row.cells.map((c) => el("td", "ws-td", c)));
      table.append(tr);
    }
    return table;
  }
  if (layout === "board" && groups) {
    const board = el("div", "ws-board");
    for (const group of groups) {
      const col = el("div", "ws-col");
      const items = rows.filter((r) => r.group === group);
      col.append(el("div", "ws-col-head", `${group} · ${items.length}`));
      for (const row of items) {
        const cardNode = open(row, el("div", "ws-card"));
        cardNode.append(el("div", "ws-card-title", `${row.icon} ${row.title}`), el("div", "ws-card-meta", row.cells.filter(Boolean).join(" · ")));
        col.append(cardNode);
      }
      board.append(col);
    }
    return board;
  }
  if (layout === "calendar") return calendar(rows, getMonth(), setMonth, open);
  if (layout === "gallery") {
    const grid = el("div", "ws-gallery");
    for (const row of rows) {
      const tile = open(row, el("div", "ws-tile"));
      tile.append(el("div", "ws-tile-icon", row.icon), el("div", "ws-tile-title", row.title));
      if (row.status) tile.append(chip(row.status.label, row.status.tone));
      tile.append(el("div", "ws-tile-meta", row.cells.filter(Boolean).join(" · ")));
      grid.append(tile);
    }
    return grid;
  }
  const listNode = el("div", "ws-rows");
  for (const row of rows) {
    const line = open(row, el("div", "ws-row"));
    line.append(el("span", "ws-row-icon", row.icon), el("span", "ws-row-title", row.title));
    if (row.status) line.append(chip(row.status.label, row.status.tone));
    line.append(el("span", "ws-row-meta", row.cells.filter(Boolean).join(" · ")));
    listNode.append(line);
  }
  return listNode;
}

function calendar(rows: Row[], month: Date, setMonth: (m: Date) => void, open: (row: Row, node: HTMLElement) => HTMLElement): HTMLElement {
  const wrap = el("div", "ws-cal");
  const head = el("div", "ws-cal-head");
  const prev = el("button", "ws-mini", "‹");
  prev.type = "button";
  prev.setAttribute("aria-label", "Previous month");
  prev.addEventListener("click", () => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1)));
  const next = el("button", "ws-mini", "›");
  next.type = "button";
  next.setAttribute("aria-label", "Next month");
  next.addEventListener("click", () => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1)));
  head.append(prev, el("strong", undefined, month.toLocaleDateString(undefined, { month: "long", year: "numeric" })), next);
  const grid = el("div", "ws-cal-grid");
  for (const day of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) grid.append(el("div", "ws-cal-dow", day));
  const today = new Date();
  for (const week of monthGrid(month.getFullYear(), month.getMonth())) {
    for (const day of week) {
      const cell = el("div", `ws-cal-day${day && sameDay(day, today.getTime()) ? " today" : ""}${day ? "" : " off"}`);
      if (day) {
        cell.append(el("div", "ws-cal-num", String(day.getDate())));
        for (const row of rows.filter((r) => r.when != null && sameDay(day, r.when))) {
          const item = open(row, el("div", "ws-cal-item", `${row.icon} ${row.title}`));
          cell.append(item);
        }
      }
      grid.append(cell);
    }
  }
  wrap.append(head, grid);
  return wrap;
}
