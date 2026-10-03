/**
 * Pages (plan 33, C5): the Command Center's workspace documents. The gateway keeps them (typed blocks, versioned saves,
 * a references index); Glass edits them. This module holds the types, defensive parsers, the API, and the pure block
 * operations the editor uses (so they are tested without a browser).
 */
import type { GatewayClient } from "./gateway.js";

export type RefKind = "page" | "device" | "skill" | "routine" | "task" | "account" | "connection";
export const REF_KINDS: readonly RefKind[] = ["page", "device", "skill", "routine", "task", "account", "connection"];

export interface Ref {
  kind: RefKind;
  id: string;
  /** A short label kept with the reference, so a page reads well even when the thing is offline or gone. */
  label: string;
  deviceId?: string;
}

export type Mark = "b" | "i" | "c" | "s";
export type TextSpan = { t: string; b?: true; i?: true; c?: true; s?: true };
export type RefSpan = { ref: Ref };
export type Span = TextSpan | RefSpan;

export type TextType = "p" | "h1" | "h2" | "h3" | "todo" | "bullet" | "number" | "quote" | "callout";
export const TEXT_TYPES: readonly TextType[] = ["p", "h1", "h2", "h3", "todo", "bullet", "number", "quote", "callout"];
export type ViewSource = "tasks" | "routines" | "approvals" | "phones" | "pages" | "results" | "accounts" | "connections";
export type ViewLayout = "list" | "table" | "board" | "calendar" | "gallery";
export type PlanLayout = "board" | "table" | "calendar";
export type PlanStatus = "todo" | "doing" | "done";

export interface PlanItem {
  id: string;
  title: string;
  status: PlanStatus;
  due: number | null;
  refs: Ref[];
  note: string;
  taskId: string | null;
}

export type TextBlock = { id: string; type: TextType; text: Span[]; checked?: boolean; icon?: string };
export type Block =
  | TextBlock
  | { id: string; type: "divider" }
  | { id: string; type: "ref"; ref: Ref }
  | { id: string; type: "view"; source: ViewSource; layout: ViewLayout; title: string; filter: { status?: "open" | "done" | "all"; deviceId?: string; routineId?: string; accountId?: string } }
  | { id: string; type: "board"; title: string; layout: PlanLayout; items: PlanItem[] }
  /** Plan 43 (T1): one of the owner's tables through one of its saved views. An empty tableId is a table being made. */
  | { id: string; type: "table"; tableId: string; viewId: string | null };

export interface PageMeta {
  id: string;
  parentId: string | null;
  title: string;
  icon: string;
  position: number;
  updatedAt: number;
  createdAt: number;
}

export interface Page extends PageMeta {
  blocks: Block[];
  version: number;
  archivedAt: number | null;
  path: Array<{ id: string; title: string; icon: string }>;
  children: PageMeta[];
  backlinks: PageMeta[];
}

export type Template = "blank" | "weekly" | "daily" | "content";

// ------------------------------------------------------------------------------------------------ parsers

const str = (v: unknown, f = ""): string => (typeof v === "string" ? v : f);
const num = (v: unknown, f = 0): number => (typeof v === "number" && Number.isFinite(v) ? v : f);
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const oneOf = <T extends string>(v: unknown, allowed: readonly T[], f: T): T => (allowed.includes(v as T) ? (v as T) : f);

export function parseRef(raw: unknown): Ref | null {
  const r = obj(raw);
  if (!REF_KINDS.includes(r.kind as RefKind) || typeof r.id !== "string" || !r.id) return null;
  const ref: Ref = { kind: r.kind as RefKind, id: r.id, label: str(r.label) };
  if (typeof r.deviceId === "string") ref.deviceId = r.deviceId;
  return ref;
}

export function parseSpans(raw: unknown): Span[] {
  const out: Span[] = [];
  for (const item of list(raw)) {
    const s = obj(item);
    if (s.ref) {
      const ref = parseRef(s.ref);
      if (ref) out.push({ ref });
    } else if (typeof s.t === "string") {
      const span: TextSpan = { t: s.t };
      for (const m of ["b", "i", "c", "s"] as const) if (s[m] === true) span[m] = true;
      out.push(span);
    }
  }
  return out;
}

function parseItem(raw: unknown): PlanItem {
  const r = obj(raw);
  return { id: str(r.id), title: str(r.title), status: oneOf(r.status, ["todo", "doing", "done"] as const, "todo"),
    due: typeof r.due === "number" ? r.due : null, refs: list(r.refs).map(parseRef).filter((x): x is Ref => x !== null),
    note: str(r.note), taskId: typeof r.taskId === "string" ? r.taskId : null };
}

export function parseBlock(raw: unknown): Block | null {
  const r = obj(raw);
  const id = str(r.id);
  if (!id) return null;
  if (TEXT_TYPES.includes(r.type as TextType)) {
    const block: TextBlock = { id, type: r.type as TextType, text: parseSpans(r.text) };
    if (r.type === "todo") block.checked = r.checked === true;
    if (r.type === "callout") block.icon = str(r.icon, "💡");
    return block;
  }
  if (r.type === "divider") return { id, type: "divider" };
  if (r.type === "ref") {
    const ref = parseRef(r.ref);
    return ref ? { id, type: "ref", ref } : null;
  }
  if (r.type === "view") {
    const f = obj(r.filter);
    const filter: Extract<Block, { type: "view" }>["filter"] = {};
    if (f.status === "open" || f.status === "done" || f.status === "all") filter.status = f.status;
    for (const k of ["deviceId", "routineId", "accountId"] as const) if (typeof f[k] === "string") filter[k] = f[k] as string;
    return { id, type: "view", source: oneOf(r.source, ["tasks", "routines", "approvals", "phones", "pages", "results", "accounts", "connections"] as const, "tasks"),
      layout: oneOf(r.layout, ["list", "table", "board", "calendar", "gallery"] as const, "list"), title: str(r.title), filter };
  }
  if (r.type === "table") {
    return { id, type: "table", tableId: str(r.tableId), viewId: typeof r.viewId === "string" ? r.viewId : null };
  }
  if (r.type === "board") {
    return { id, type: "board", title: str(r.title), layout: oneOf(r.layout, ["board", "table", "calendar"] as const, "board"), items: list(r.items).map(parseItem) };
  }
  return null;
}

export function parseMeta(raw: unknown): PageMeta {
  const r = obj(raw);
  return { id: str(r.id), parentId: typeof r.parentId === "string" ? r.parentId : null, title: str(r.title, "Untitled"), icon: str(r.icon),
    position: num(r.position), updatedAt: num(r.updatedAt), createdAt: num(r.createdAt) };
}

export function parsePage(raw: unknown): Page {
  const r = obj(raw);
  return {
    ...parseMeta(raw),
    blocks: list(r.blocks).map(parseBlock).filter((b): b is Block => b !== null),
    version: num(r.version, 1),
    archivedAt: typeof r.archivedAt === "number" ? r.archivedAt : null,
    path: list(r.path).map((p) => ({ id: str(obj(p).id), title: str(obj(p).title), icon: str(obj(p).icon) })),
    children: list(r.children).map(parseMeta),
    backlinks: list(r.backlinks).map(parseMeta),
  };
}

// ------------------------------------------------------------------------------------------------ API

const enc = encodeURIComponent;

export const pagesApi = {
  tree: async (client: GatewayClient): Promise<PageMeta[]> => list((await client.get<{ pages?: unknown }>("/v1/cc/pages"))?.pages).map(parseMeta),
  create: async (client: GatewayClient, body: { title?: string; icon?: string; parentId?: string | null; template?: Template }) =>
    parsePage(await client.post("/v1/cc/pages", Object.fromEntries(Object.entries(body).filter(([, v]) => v !== undefined && v !== null)))),
  get: async (client: GatewayClient, id: string) => parsePage(await client.get(`/v1/cc/pages/${enc(id)}`)),
  version: async (client: GatewayClient, id: string) => {
    const r = obj(await client.get(`/v1/cc/pages/${enc(id)}/version`));
    return { version: num(r.version), archivedAt: typeof r.archivedAt === "number" ? r.archivedAt : null };
  },
  save: async (client: GatewayClient, id: string, body: { version: number; title?: string; icon?: string; blocks?: Block[] }) =>
    parsePage(await client.post(`/v1/cc/pages/${enc(id)}`, body)),
  move: async (client: GatewayClient, id: string, parentId: string | null, before?: string) =>
    parsePage(await client.post(`/v1/cc/pages/${enc(id)}/move`, before ? { parentId, before } : { parentId })),
  archive: (client: GatewayClient, id: string) => client.post(`/v1/cc/pages/${enc(id)}/archive`),
  restore: async (client: GatewayClient, id: string) => parsePage(await client.post(`/v1/cc/pages/${enc(id)}/restore`)),
  remove: (client: GatewayClient, id: string) => client.post(`/v1/cc/pages/${enc(id)}/delete`),
  trash: async (client: GatewayClient) => list((await client.get<{ pages?: unknown }>("/v1/cc/pages-trash"))?.pages).map((p) => ({ ...parseMeta(p), archivedAt: num(obj(p).archivedAt) })),
  search: async (client: GatewayClient, q: string) =>
    list((await client.get<{ pages?: unknown }>(`/v1/cc/pages-search?q=${enc(q)}`))?.pages).map((p) => ({ ...parseMeta(p), snippet: str(obj(p).snippet) })),
  backlinks: async (client: GatewayClient, kind: RefKind, id: string) =>
    list((await client.get<{ pages?: unknown }>(`/v1/cc/backlinks?kind=${enc(kind)}&id=${enc(id)}`))?.pages).map(parseMeta),
};

// ------------------------------------------------------------------------------------------------ pure block operations

export function newId(prefix: "b" | "i"): string {
  const bytes = new Uint8Array(9);
  globalThis.crypto.getRandomValues(bytes);
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
  return prefix + Array.from(bytes, (b) => alphabet[b & 63]).join("");
}

export const isRef = (span: Span): span is RefSpan => "ref" in span;

/** A span's length for caret arithmetic: a mention counts as one character. */
export const spanLength = (span: Span): number => (isRef(span) ? 1 : span.t.length);

export function plainText(spans: Span[]): string {
  return spans.map((s) => (isRef(s) ? s.ref.label : s.t)).join("");
}

const sameMarks = (a: TextSpan, b: TextSpan): boolean => (["b", "i", "c", "s"] as const).every((m) => Boolean(a[m]) === Boolean(b[m]));

/** Drops empty text and joins neighbours with the same marks. */
export function normalizeSpans(spans: Span[]): Span[] {
  const out: Span[] = [];
  for (const span of spans) {
    if (!isRef(span) && !span.t) continue;
    const last = out[out.length - 1];
    if (last && !isRef(last) && !isRef(span) && sameMarks(last, span)) out[out.length - 1] = { ...last, t: last.t + span.t };
    else out.push(isRef(span) ? { ref: { ...span.ref } } : { ...span });
  }
  return out;
}

/** Splits text at a caret offset (mentions count as one) into what stays and what moves to a new block. */
export function splitSpans(spans: Span[], offset: number): [Span[], Span[]] {
  const left: Span[] = [];
  const right: Span[] = [];
  let at = 0;
  for (const span of spans) {
    const len = spanLength(span);
    if (at + len <= offset) left.push(span);
    else if (at >= offset) right.push(span);
    else if (!isRef(span)) {
      const cut = offset - at;
      left.push({ ...span, t: span.t.slice(0, cut) });
      right.push({ ...span, t: span.t.slice(cut) });
    }
    at += len;
  }
  return [normalizeSpans(left), normalizeSpans(right)];
}

/** Removes the characters between two caret offsets (mentions count as one). */
export function deleteRange(spans: Span[], from: number, to: number): Span[] {
  if (to <= from) return normalizeSpans(spans);
  const [left, rest] = splitSpans(spans, from);
  const [, right] = splitSpans(rest, to - from);
  return normalizeSpans([...left, ...right]);
}

export const textLength = (spans: Span[]): number => spans.reduce((n, s) => n + spanLength(s), 0);

/** The text before a caret, with each mention as one private-use character (so trigger characters are found). */
export function textBefore(spans: Span[], offset: number): string {
  return splitSpans(spans, offset)[0].map((s) => (isRef(s) ? "\uE000" : s.t)).join("");
}

export function insertRef(spans: Span[], offset: number, ref: Ref): Span[] {
  const [left, right] = splitSpans(spans, offset);
  return normalizeSpans([...left, { ref }, { t: " " }, ...right]);
}

/** Markdown-style shortcuts typed at the start of a block: "# ", "- ", "[] ", "> ", "1. ", "---". */
export function markdownShortcut(text: string): { type: TextType | "divider"; rest: string; checked?: boolean } | null {
  const rules: Array<[RegExp, TextType | "divider", boolean?]> = [
    [/^###\s/, "h3"], [/^##\s/, "h2"], [/^#\s/, "h1"], [/^[-*•]\s/, "bullet"], [/^1[.)]\s/, "number"],
    [/^\[x\]\s/i, "todo", true], [/^\[ ?\]\s/, "todo", false], [/^>\s/, "quote"], [/^!\s/, "callout"], [/^---$/, "divider"],
  ];
  for (const [re, type, checked] of rules) {
    const match = text.match(re);
    if (match) return { type, rest: text.slice(match[0].length), ...(checked !== undefined ? { checked } : {}) };
  }
  return null;
}

export function textBlock(type: TextType = "p", text: Span[] = []): TextBlock {
  const block: TextBlock = { id: newId("b"), type, text };
  if (type === "todo") block.checked = false;
  if (type === "callout") block.icon = "💡";
  return block;
}

/** Turns a text block into another text type, keeping its words (and a to-do's tick when it stays a to-do). */
export function convertBlock(block: TextBlock, type: TextType): TextBlock {
  const out: TextBlock = { id: block.id, type, text: block.text };
  if (type === "todo") out.checked = block.type === "todo" ? Boolean(block.checked) : false;
  if (type === "callout") out.icon = block.icon ?? "💡";
  return out;
}

export function moveBlock<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= items.length) return items.slice();
  const out = items.slice();
  const [item] = out.splice(from, 1);
  out.splice(Math.max(0, Math.min(to, out.length)), 0, item);
  return out;
}

/** The number shown before a numbered-list block: it counts the numbered blocks directly above it. */
export function listNumber(blocks: Block[], index: number): number {
  let n = 1;
  for (let i = index - 1; i >= 0 && blocks[i]?.type === "number"; i -= 1) n += 1;
  return n;
}

// ------------------------------------------------------------------------------------------------ slash menu

export interface SlashItem {
  id: string;
  label: string;
  hint: string;
  keywords: string;
  group: "AI" | "Basic" | "Table" | "Live" | "Plan";
  /** An action instead of a block: "ai" opens the AI about the page. */
  action?: "ai";
  make(): Block;
}

export const SLASH_ITEMS: SlashItem[] = [
  { id: "ai", label: "Ask AI", hint: "Plan, write or organise this page with your AI", keywords: "ai ask assistant write plan summarise organise help",
    group: "AI", action: "ai", make: () => textBlock("p") },
  { id: "p", label: "Text", hint: "Plain writing", keywords: "text paragraph", group: "Basic", make: () => textBlock("p") },
  { id: "h1", label: "Heading 1", hint: "Big section heading", keywords: "title h1 heading", group: "Basic", make: () => textBlock("h1") },
  { id: "h2", label: "Heading 2", hint: "Medium heading", keywords: "h2 heading subtitle", group: "Basic", make: () => textBlock("h2") },
  { id: "h3", label: "Heading 3", hint: "Small heading", keywords: "h3 heading", group: "Basic", make: () => textBlock("h3") },
  { id: "todo", label: "To-do", hint: "Track a small task with a checkbox", keywords: "todo checkbox task check", group: "Basic", make: () => textBlock("todo") },
  { id: "bullet", label: "Bulleted list", hint: "A simple list", keywords: "bullet list ul", group: "Basic", make: () => textBlock("bullet") },
  { id: "number", label: "Numbered list", hint: "A list with numbers", keywords: "number ordered list ol", group: "Basic", make: () => textBlock("number") },
  { id: "quote", label: "Quote", hint: "Set a passage apart", keywords: "quote blockquote", group: "Basic", make: () => textBlock("quote") },
  { id: "callout", label: "Callout", hint: "A note that stands out", keywords: "callout note tip info", group: "Basic", make: () => textBlock("callout") },
  { id: "divider", label: "Divider", hint: "A line between sections", keywords: "divider line hr separator", group: "Basic", make: () => ({ id: newId("b"), type: "divider" }) },
  { id: "table", label: "Table", hint: "Your own database: properties, rows, and table or board views", keywords: "table database notion rows columns sheet spreadsheet crm ledger",
    group: "Table", make: () => ({ id: newId("b"), type: "table", tableId: "", viewId: null }) },
  { id: "board", label: "Plan board", hint: "Cards in To do, Doing and Done, as a board, table or calendar", keywords: "plan board kanban project tasks cards calendar table database", group: "Plan",
    make: () => ({ id: newId("b"), type: "board", title: "Plan", layout: "board", items: [] }) },
  { id: "calendar", label: "Plan calendar", hint: "Cards on a month calendar", keywords: "calendar schedule dates plan", group: "Plan",
    make: () => ({ id: newId("b"), type: "board", title: "Calendar", layout: "calendar", items: [] }) },
  { id: "view-tasks", label: "Tasks", hint: "Live: the phones' tasks", keywords: "tasks live view runs", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "tasks", layout: "list", title: "", filter: { status: "open" } }) },
  { id: "view-tasks-board", label: "Tasks board", hint: "Live: tasks by status", keywords: "tasks board kanban live", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "tasks", layout: "board", title: "", filter: { status: "all" } }) },
  { id: "view-routines", label: "Routines", hint: "Live: schedules and their next run", keywords: "routines schedule live", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "routines", layout: "table", title: "", filter: {} }) },
  { id: "view-approvals", label: "Approvals", hint: "Live: what is waiting for you", keywords: "approvals inbox waiting live", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "approvals", layout: "list", title: "", filter: {} }) },
  { id: "view-phones", label: "Phones", hint: "Live: every phone and what it is doing", keywords: "phones devices live fleet", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "phones", layout: "gallery", title: "", filter: {} }) },
  { id: "view-results", label: "Results", hint: "Live: recent runs and how they went", keywords: "results runs history live", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "results", layout: "list", title: "", filter: {} }) },
  { id: "view-pages", label: "Sub-pages", hint: "Live: the pages inside this one", keywords: "pages children subpages", group: "Live",
    make: () => ({ id: newId("b"), type: "view", source: "pages", layout: "gallery", title: "", filter: {} }) },
];

export function filterSlash(query: string): SlashItem[] {
  const q = query.trim().toLowerCase();
  if (!q) return SLASH_ITEMS;
  return SLASH_ITEMS.filter((item) => item.label.toLowerCase().includes(q) || item.keywords.includes(q));
}

// ------------------------------------------------------------------------------------------------ the directory (@ mentions)

export interface DirectoryEntry {
  ref: Ref;
  /** A second line, such as a phone's state or a routine's schedule. */
  detail: string;
}

export const KIND_LABEL: Record<RefKind, string> = {
  page: "Page", device: "Phone", skill: "Skill", routine: "Routine", task: "Task", account: "Account", connection: "Connection",
};

export const KIND_ICON: Record<RefKind, string> = {
  page: "📄", device: "📱", skill: "✨", routine: "🔁", task: "▶️", account: "👤", connection: "🔌",
};

/** Entries whose label, kind or detail match [query]; pages first, then phones, skills, routines, tasks, accounts. */
export function searchDirectory(entries: DirectoryEntry[], query: string, limit = 12): DirectoryEntry[] {
  const q = query.trim().toLowerCase();
  const order = REF_KINDS;
  const scored = entries
    .map((e) => {
      const label = e.ref.label.toLowerCase();
      const score = !q ? 1 : label.startsWith(q) ? 3 : label.includes(q) ? 2 : (KIND_LABEL[e.ref.kind].toLowerCase().startsWith(q) || e.detail.toLowerCase().includes(q)) ? 1 : 0;
      return { e, score };
    })
    .filter((x) => x.score > 0);
  scored.sort((a, b) => b.score - a.score || order.indexOf(a.e.ref.kind) - order.indexOf(b.e.ref.kind) || a.e.ref.label.localeCompare(b.e.ref.label));
  return scored.slice(0, limit).map((x) => x.e);
}

/** The page tree as nested nodes, siblings in their saved order. */
export interface TreeNode {
  page: PageMeta;
  children: TreeNode[];
}

export function buildTree(pages: PageMeta[]): TreeNode[] {
  const byParent = new Map<string | null, PageMeta[]>();
  const ids = new Set(pages.map((p) => p.id));
  for (const p of pages) {
    const parent = p.parentId && ids.has(p.parentId) ? p.parentId : null;
    byParent.set(parent, [...(byParent.get(parent) ?? []), p]);
  }
  const grow = (parent: string | null, depth: number): TreeNode[] =>
    (byParent.get(parent) ?? []).sort((a, b) => a.position - b.position || a.createdAt - b.createdAt)
      .map((page) => ({ page, children: depth < 12 ? grow(page.id, depth + 1) : [] }));
  return grow(null, 0);
}

/** Planning cards per status, in their saved order. */
export function itemsByStatus(items: PlanItem[]): Record<PlanStatus, PlanItem[]> {
  return { todo: items.filter((i) => i.status === "todo"), doing: items.filter((i) => i.status === "doing"), done: items.filter((i) => i.status === "done") };
}

export const STATUS_LABEL: Record<PlanStatus, string> = { todo: "To do", doing: "Doing", done: "Done" };

/** The weeks of a month as rows of seven days (Monday first); days outside the month are null. */
export function monthGrid(year: number, month: number): Array<Array<Date | null>> {
  const first = new Date(year, month, 1);
  const offset = (first.getDay() + 6) % 7;
  const days = new Date(year, month + 1, 0).getDate();
  const cells: Array<Date | null> = [...Array(offset).fill(null), ...Array.from({ length: days }, (_, i) => new Date(year, month, i + 1))];
  while (cells.length % 7) cells.push(null);
  const weeks: Array<Array<Date | null>> = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return weeks;
}

export function sameDay(a: Date, ms: number): boolean {
  const b = new Date(ms);
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

// ------------------------------------------------------------------------------------------------ merging a page changed elsewhere

/**
 * A three-way merge, block by block, for a save refused because the page changed elsewhere (another window, the AI):
 * blocks I changed keep my version; blocks only they changed keep theirs; blocks I added are placed after the block
 * they followed; blocks I deleted stay deleted unless they changed them. Their order wins.
 */
export function mergeBlocks(base: Block[], mine: Block[], theirs: Block[]): Block[] {
  const key = (b: Block) => JSON.stringify(b);
  const baseById = new Map(base.map((b) => [b.id, key(b)]));
  const mineById = new Map(mine.map((b) => [b.id, b]));
  const theirIds = new Set(theirs.map((b) => b.id));
  const out: Block[] = [];
  for (const t of theirs) {
    const m = mineById.get(t.id);
    const was = baseById.get(t.id);
    if (!m) {
      if (was !== undefined && was === key(t)) continue;
      out.push(t);
      continue;
    }
    out.push(was !== undefined && key(m) !== was ? m : t);
  }
  mine.forEach((m, i) => {
    if (theirIds.has(m.id)) return;
    if (baseById.has(m.id) && baseById.get(m.id) === key(m)) return;
    let at = -1;
    for (let j = i - 1; j >= 0 && at < 0; j--) at = out.findIndex((o) => o.id === mine[j].id);
    out.splice(at + 1, 0, m);
  });
  return out;
}
