/**
 * A plan board inside a page (plan 33, C5): cards in To do, Doing and Done, seen as a board, a table or a calendar.
 * Cards link to phones, routines, skills, accounts and other pages, and a card can be sent to a phone as a Command
 * Center task (the phone then does it like any other task, with the same approvals).
 */
import type { GlassContext } from "../app.js";
import { command, looksSecret, taskStatusLabel } from "../services/command.js";
import {
  KIND_ICON, KIND_LABEL, STATUS_LABEL, itemsByStatus, monthGrid, newId, sameDay, searchDirectory,
  type Block, type DirectoryEntry, type PlanItem, type PlanLayout, type PlanStatus, type Ref,
} from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { loadDirectory, routeOfRef } from "./directory.js";

type Board = Extract<Block, { type: "board" }>;
const STATUSES: PlanStatus[] = ["todo", "doing", "done"];

export interface PlanView {
  element: HTMLElement;
  destroy(): void;
}

export function createPlanView(ctx: GlassContext, initial: Board, change: (next: Board) => void): PlanView {
  let board: Board = initial;
  const element = el("div", "ws-plan");
  const head = el("div", "ws-view-head");
  const body = el("div", "ws-plan-body");
  element.append(head, body);
  let editing: string | null = null;
  let month = new Date();
  let dragging: string | null = null;
  let taskStates: Record<string, string> = {};

  const commit = (next: Board, redraw = true) => {
    board = next;
    change(board);
    if (redraw) draw();
  };
  const setItem = (id: string, patch: Partial<PlanItem>, redraw = true) =>
    commit({ ...board, items: board.items.map((i) => (i.id === id ? { ...i, ...patch } : i)) }, redraw);
  const addItem = (status: PlanStatus, due: number | null = null) => {
    const item: PlanItem = { id: newId("i"), title: "", status, due, refs: [], note: "", taskId: null };
    editing = item.id;
    commit({ ...board, items: [...board.items, item] });
  };

  function drawHead(): void {
    const title = el("input", "ws-plan-title");
    title.value = board.title;
    title.placeholder = "Untitled plan";
    title.setAttribute("aria-label", "Plan name");
    title.addEventListener("input", () => {
      if (looksSecret(String(title.value))) return;
      commit({ ...board, title: String(title.value ?? "") }, false);
    });
    const seg = el("div", "ws-seg");
    for (const layout of ["board", "table", "calendar"] as PlanLayout[]) {
      const b = el("button", `ws-seg-item${layout === board.layout ? " active" : ""}`, layout[0].toUpperCase() + layout.slice(1));
      b.type = "button";
      b.setAttribute("aria-pressed", String(layout === board.layout));
      b.addEventListener("click", () => {
        commit({ ...board, layout });
        drawHead();
      });
      seg.append(b);
    }
    const add = el("button", "ws-new", "+ New card");
    add.type = "button";
    add.addEventListener("click", () => addItem("todo"));
    setChildren(head, title, seg, add);
  }

  function chips(item: PlanItem): HTMLElement {
    const wrap = el("span", "ws-chips");
    for (const ref of item.refs) wrap.append(el("span", "ws-ref-chip", `${KIND_ICON[ref.kind]} ${ref.label}`));
    if (item.taskId) wrap.append(el("span", "ws-ref-chip ws-task-chip", `▶️ ${taskStates[item.taskId] ?? "Task"}`));
    return wrap;
  }

  function dueLabel(due: number | null): string {
    return due ? new Date(due).toLocaleDateString(undefined, { day: "numeric", month: "short" }) : "";
  }

  function cardNode(item: PlanItem): HTMLElement {
    const node = el("div", `ws-card${item.status === "done" ? " done" : ""}`);
    node.draggable = true;
    node.dataset.itemId = item.id;
    node.tabIndex = 0;
    node.setAttribute("role", "button");
    node.setAttribute("aria-label", `Open card ${item.title || "Untitled"}`);
    node.append(el("div", "ws-card-title", item.title || "Untitled"));
    const meta = el("div", "ws-card-meta");
    if (item.due) meta.append(el("span", "ws-due", `📅 ${dueLabel(item.due)}`));
    meta.append(chips(item));
    node.append(meta);
    node.addEventListener("click", () => {
      editing = editing === item.id ? null : item.id;
      draw();
    });
    node.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        editing = item.id;
        draw();
      }
    });
    node.addEventListener("dragstart", (e: DragEvent) => {
      dragging = item.id;
      e.dataTransfer?.setData("text/plain", item.id);
    });
    return node;
  }

  function editor(item: PlanItem): HTMLElement {
    const box = el("div", "ws-card-editor");
    box.setAttribute("aria-label", "Card");
    const title = el("input", "ws-input ws-card-name");
    title.value = item.title;
    title.placeholder = "What needs doing?";
    title.setAttribute("aria-label", "Card title");
    title.addEventListener("input", () => {
      const text = String(title.value ?? "");
      if (looksSecret(text)) return;
      setItem(item.id, { title: text }, false);
    });
    title.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        editing = null;
        draw();
      }
    });
    const status = el("select", "ws-select");
    status.setAttribute("aria-label", "Card status");
    for (const s of STATUSES) {
      const o = el("option", undefined, STATUS_LABEL[s]);
      o.value = s;
      status.append(o);
    }
    status.value = item.status;
    status.addEventListener("change", () => setItem(item.id, { status: status.value as PlanStatus }));
    const due = el("input", "ws-input");
    due.type = "date";
    due.setAttribute("aria-label", "Due date");
    due.value = item.due ? isoDate(item.due) : "";
    due.addEventListener("change", () => {
      const value = String(due.value ?? "");
      setItem(item.id, { due: value ? new Date(`${value}T09:00`).getTime() : null });
    });
    const note = el("textarea", "ws-input ws-card-note");
    note.value = item.note;
    note.placeholder = "Notes";
    note.setAttribute("aria-label", "Card notes");
    note.addEventListener("input", () => {
      const text = String(note.value ?? "");
      if (looksSecret(text)) return;
      setItem(item.id, { note: text }, false);
    });
    const links = el("div", "ws-card-links");
    for (const ref of item.refs) {
      const c = el("span", "ws-ref-chip");
      const openRef = el("button", "ws-chip-open", `${KIND_ICON[ref.kind]} ${ref.label}`);
      openRef.type = "button";
      openRef.addEventListener("click", () => ctx.navigate(routeOfRef(ref)));
      const drop = el("button", "ws-chip-x", "×");
      drop.type = "button";
      drop.setAttribute("aria-label", `Unlink ${ref.label}`);
      drop.addEventListener("click", () => setItem(item.id, { refs: item.refs.filter((r) => !(r.kind === ref.kind && r.id === ref.id)) }));
      c.append(openRef, drop);
      links.append(c);
    }
    const linkBox = el("div", "ws-link-picker");
    const link = el("input", "ws-input");
    link.placeholder = "Link a phone, routine, skill, page…";
    link.setAttribute("aria-label", "Link something");
    const found = el("div", "ws-picker-list");
    let entries: DirectoryEntry[] = [];
    const drawFound = () => {
      const q = String(link.value ?? "");
      const hits = q.trim() ? searchDirectory(entries, q, 6) : [];
      setChildren(found, ...hits.map((e) => {
        const b = el("button", "ws-picker-row");
        b.type = "button";
        b.append(el("span", undefined, KIND_ICON[e.ref.kind]), el("span", "ws-picker-label", e.ref.label), el("span", "ws-picker-kind", KIND_LABEL[e.ref.kind]));
        b.addEventListener("click", () => {
          if (item.refs.length >= 10 || item.refs.some((r) => r.kind === e.ref.kind && r.id === e.ref.id)) return;
          setItem(item.id, { refs: [...item.refs, e.ref] });
        });
        return b;
      }));
    };
    link.addEventListener("focus", () => void loadDirectory(ctx).then((d) => { entries = d; drawFound(); }));
    link.addEventListener("input", () => {
      if (!entries.length) void loadDirectory(ctx).then((d) => { entries = d; drawFound(); });
      else drawFound();
    });
    linkBox.append(link, found);
    const actions = el("div", "ws-card-actions");
    if (item.taskId) {
      const openTask = el("button", "ws-new", `▶️ Task: ${taskStates[item.taskId] ?? "open it"}`);
      openTask.type = "button";
      openTask.addEventListener("click", () => ctx.navigate({ name: "command", tab: "tasks" }));
      actions.append(openTask);
    } else {
      const send = el("button", "ws-new ws-send", "Send to a phone");
      send.type = "button";
      send.title = "Make this card a Command Center task. A linked phone runs it (or any ready phone); approvals stay as always.";
      send.addEventListener("click", () => void sendToPhone(item, send));
      actions.append(send);
    }
    const remove = el("button", "ws-new ws-danger", "Delete card");
    remove.type = "button";
    remove.addEventListener("click", () => {
      editing = null;
      commit({ ...board, items: board.items.filter((i) => i.id !== item.id) });
    });
    const done = el("button", "ws-new", "Done");
    done.type = "button";
    done.addEventListener("click", () => {
      editing = null;
      draw();
    });
    actions.append(remove, done);
    const field = (label: string, control: HTMLElement) => {
      const rowNode = el("label", "ws-field-row");
      rowNode.append(el("span", "ws-field-label", label), control);
      return rowNode;
    };
    const linkField = el("div");
    linkField.append(links, linkBox);
    box.append(title, field("Status", status), field("Date", due), field("Links", linkField), note, actions);
    queueMicrotask(() => { if (!item.title) title.focus?.(); });
    return box;
  }

  async function sendToPhone(item: PlanItem, button: HTMLButtonElement): Promise<void> {
    const goal = [item.title.trim(), item.note.trim()].filter(Boolean).join("\n\n");
    if (!item.title.trim()) {
      button.textContent = "Give the card a title first";
      return;
    }
    const device = item.refs.find((r) => r.kind === "device");
    const account = item.refs.find((r) => r.kind === "account");
    button.disabled = true;
    button.textContent = "Sending…";
    try {
      const task = await command.createTask(ctx.client, { title: item.title.trim().slice(0, 80), goal: goal.slice(0, 1800),
        ...(device ? { deviceId: device.id } : {}), ...(account ? { accountId: account.id } : {}) });
      taskStates[task.id] = taskStatusLabel(task.status);
      setItem(item.id, { taskId: task.id, status: item.status === "todo" ? "doing" : item.status });
    } catch (err) {
      button.disabled = false;
      button.textContent = (err as Error).message.slice(0, 120) || "It could not be sent";
    }
  }

  function column(status: PlanStatus, items: PlanItem[]): HTMLElement {
    const col = el("div", `ws-col ws-col-${status}`);
    col.dataset.status = status;
    const head = el("div", "ws-col-head");
    head.append(el("span", `ws-dot ws-dot-${status}`), el("span", undefined, `${STATUS_LABEL[status]} · ${items.length}`));
    col.append(head);
    for (const item of items) {
      col.append(cardNode(item));
    }
    const add = el("button", "ws-col-add", "+ New");
    add.type = "button";
    add.setAttribute("aria-label", `New card in ${STATUS_LABEL[status]}`);
    add.addEventListener("click", () => addItem(status));
    col.append(add);
    col.addEventListener("dragover", (e: DragEvent) => { if (dragging) { e.preventDefault(); col.classList.add("ws-drop"); } });
    col.addEventListener("dragleave", () => col.classList.remove("ws-drop"));
    col.addEventListener("drop", (e: DragEvent) => {
      e.preventDefault();
      col.classList.remove("ws-drop");
      const id = dragging;
      dragging = null;
      if (id) setItem(id, { status });
    });
    return col;
  }

  function table(): HTMLElement {
    const wrap = el("div", "ws-plan-table");
    const t = el("table", "ws-table");
    const head = el("tr");
    for (const h of ["Card", "Status", "Due", "Links"]) head.append(el("th", undefined, h));
    t.append(head);
    for (const item of board.items) {
      const tr = el("tr");
      const name = el("td", "ws-td-name");
      const open = el("button", "ws-link-button", item.title || "Untitled");
      open.type = "button";
      open.addEventListener("click", () => {
        editing = editing === item.id ? null : item.id;
        draw();
      });
      name.append(open);
      const st = el("td");
      const select = el("select", "ws-select");
      select.setAttribute("aria-label", `Status of ${item.title || "card"}`);
      for (const s of STATUSES) {
        const o = el("option", undefined, STATUS_LABEL[s]);
        o.value = s;
        select.append(o);
      }
      select.value = item.status;
      select.addEventListener("change", () => setItem(item.id, { status: select.value as PlanStatus }));
      st.append(select);
      const due = el("td", "ws-td", dueLabel(item.due));
      const links = el("td");
      links.append(chips(item));
      tr.append(name, st, due, links);
      t.append(tr);
    }
    const add = el("button", "ws-col-add", "+ New");
    add.type = "button";
    add.addEventListener("click", () => addItem("todo"));
    wrap.append(t, add);
    return wrap;
  }

  function calendarView(): HTMLElement {
    const wrap = el("div", "ws-cal");
    const head = el("div", "ws-cal-head");
    const prev = el("button", "ws-mini", "‹");
    prev.type = "button";
    prev.setAttribute("aria-label", "Previous month");
    prev.addEventListener("click", () => { month = new Date(month.getFullYear(), month.getMonth() - 1, 1); draw(); });
    const next = el("button", "ws-mini", "›");
    next.type = "button";
    next.setAttribute("aria-label", "Next month");
    next.addEventListener("click", () => { month = new Date(month.getFullYear(), month.getMonth() + 1, 1); draw(); });
    head.append(prev, el("strong", undefined, month.toLocaleDateString(undefined, { month: "long", year: "numeric" })), next);
    const grid = el("div", "ws-cal-grid");
    for (const d of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) grid.append(el("div", "ws-cal-dow", d));
    const today = new Date();
    for (const week of monthGrid(month.getFullYear(), month.getMonth())) {
      for (const day of week) {
        const cell = el("div", `ws-cal-day${day && sameDay(day, today.getTime()) ? " today" : ""}${day ? "" : " off"}`);
        if (day) {
          const top = el("div", "ws-cal-top");
          top.append(el("span", "ws-cal-num", String(day.getDate())));
          const add = el("button", "ws-mini ws-cal-add", "+");
          add.type = "button";
          add.setAttribute("aria-label", `New card on ${day.toDateString()}`);
          add.addEventListener("click", () => addItem("todo", new Date(day.getFullYear(), day.getMonth(), day.getDate(), 9).getTime()));
          top.append(add);
          cell.append(top);
          for (const item of board.items.filter((i) => i.due != null && sameDay(day, i.due))) {
            const chipNode = el("button", `ws-cal-item${item.status === "done" ? " done" : ""}`, item.title || "Untitled");
            chipNode.type = "button";
            chipNode.addEventListener("click", () => { editing = item.id; draw(); });
            cell.append(chipNode);
          }
          cell.addEventListener("dragover", (e: DragEvent) => { if (dragging) e.preventDefault(); });
          cell.addEventListener("drop", (e: DragEvent) => {
            e.preventDefault();
            const id = dragging;
            dragging = null;
            if (id) setItem(id, { due: new Date(day.getFullYear(), day.getMonth(), day.getDate(), 9).getTime() });
          });
        }
        grid.append(cell);
      }
    }
    wrap.append(head, grid);
    const undated = board.items.filter((i) => i.due == null);
    if (undated.length) {
      const rest = el("div", "ws-undated");
      rest.append(el("div", "ws-col-head", `No date · ${undated.length}`));
      for (const item of undated) rest.append(cardNode(item));
      wrap.append(rest);
    }
    return wrap;
  }

  function draw(): void {
    let view: HTMLElement;
    if (board.layout === "table") view = table();
    else if (board.layout === "calendar") view = calendarView();
    else {
      const groups = itemsByStatus(board.items);
      view = el("div", "ws-board");
      for (const s of STATUSES) view.append(column(s, groups[s]));
    }
    // A card opens as a "peek" over the page, like Notion: click outside it or press Escape to close.
    const open = board.items.find((i) => i.id === editing);
    if (!open) return setChildren(body, view);
    const peek = el("div", "ws-peek");
    peek.setAttribute("role", "dialog");
    peek.setAttribute("aria-label", "Card");
    peek.append(editor(open));
    peek.addEventListener("click", (e: Event) => {
      if (e.target === peek) {
        editing = null;
        draw();
      }
    });
    setChildren(body, view, peek);
  }

  async function refreshTasks(): Promise<void> {
    const ids = board.items.map((i) => i.taskId).filter((x): x is string => Boolean(x));
    if (!ids.length) return;
    const tasks = await command.tasks(ctx.client).catch(() => []);
    const next: Record<string, string> = {};
    for (const t of tasks) if (ids.includes(t.id)) next[t.id] = taskStatusLabel(t.status);
    if (JSON.stringify(next) !== JSON.stringify(taskStates)) {
      taskStates = next;
      if (!editing) draw();
    }
  }

  // Escape closes an open card wherever the focus is (sending a card redraws it, and focus leaves the dialog).
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape" && editing) {
      editing = null;
      draw();
    }
  };
  globalThis.document?.addEventListener?.("keydown", onKey as EventListener);

  drawHead();
  draw();
  void refreshTasks();
  const timer = setInterval(() => void refreshTasks(), 15_000);
  return { element, destroy() { clearInterval(timer); globalThis.document?.removeEventListener?.("keydown", onKey as EventListener); } };
}

function isoDate(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export type { Ref };
