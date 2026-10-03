/** Glass component set. Every page composes these; pages do not invent their own chrome. */
import { el, button } from "./dom.js";
import { icon, type IconName } from "./icons.js";

export type Tone = "neutral" | "accent" | "success" | "warning" | "danger";

export function pageHeader(title: string, subtitle?: string, actions: Node[] = []): HTMLElement {
  const header = el("header", "page-header");
  const titles = el("div", "page-titles");
  titles.append(el("h1", "page-title", title));
  if (subtitle) titles.append(el("p", "page-subtitle", subtitle));
  header.append(titles);
  if (actions.length) {
    const bar = el("div", "page-actions");
    bar.append(...actions);
    header.append(bar);
  }
  return header;
}

export function chip(label: string, tone: Tone = "neutral"): HTMLSpanElement {
  const node = el("span", `chip chip-${tone}`, label);
  return node;
}

export function card(className = ""): HTMLElement {
  return el("section", className ? `card ${className}` : "card");
}

export function iconButton(name: IconName, label: string, className = "btn btn-ghost"): HTMLButtonElement {
  const node = button("", className);
  node.setAttribute("aria-label", label);
  node.title = label;
  node.append(icon(name));
  return node;
}

export function actionButton(label: string, options: { icon?: IconName; variant?: "primary" | "secondary" | "ghost" | "danger" } = {}): HTMLButtonElement {
  const node = button("", `btn btn-${options.variant ?? "secondary"}`);
  if (options.icon) node.append(icon(options.icon));
  node.append(el("span", "btn-label", label));
  return node;
}

export interface EmptyStateOptions {
  icon?: IconName;
  tone?: Tone;
  title: string;
  body?: string;
  action?: HTMLElement;
}

export function emptyState(options: EmptyStateOptions): HTMLElement {
  const node = el("div", `empty-state empty-${options.tone ?? "neutral"}`);
  if (options.icon) {
    const badge = el("div", "empty-icon");
    badge.append(icon(options.icon));
    node.append(badge);
  }
  node.append(el("h2", "empty-title", options.title));
  if (options.body) node.append(el("p", "empty-body", options.body));
  if (options.action) node.append(options.action);
  return node;
}

export function loadingState(label = "Loading…"): HTMLElement {
  const node = el("div", "loading-state");
  node.setAttribute("role", "status");
  node.append(el("span", "spinner"), el("span", "loading-label", label));
  return node;
}

export function keyValue(rows: Array<[string, string | Node]>): HTMLElement {
  const list = el("dl", "kv");
  for (const [key, value] of rows) {
    list.append(el("dt", "kv-key", key));
    const dd = el("dd", "kv-value");
    if (typeof value === "string") dd.textContent = value;
    else dd.append(value);
    list.append(dd);
  }
  return list;
}

export interface SegmentItem<T extends string> {
  id: T;
  label: string;
  count?: number;
}

/** Single-choice filter bar (like Minitap's Android / iOS switcher). */
export function segmented<T extends string>(
  items: Array<SegmentItem<T>>,
  active: T,
  onChange: (id: T) => void,
): { element: HTMLElement; set(id: T, counts?: Partial<Record<T, number>>): void } {
  const element = el("div", "segmented");
  element.setAttribute("role", "tablist");
  const buttons = new Map<T, HTMLButtonElement>();
  for (const item of items) {
    const node = button("", "segment");
    node.setAttribute("role", "tab");
    node.dataset.id = item.id;
    node.addEventListener("click", () => onChange(item.id));
    buttons.set(item.id, node);
    element.append(node);
  }
  const set = (id: T, counts: Partial<Record<T, number>> = {}): void => {
    for (const item of items) {
      const node = buttons.get(item.id)!;
      const count = counts[item.id] ?? item.count;
      node.replaceChildren(el("span", undefined, item.label));
      if (count != null) node.append(el("span", "segment-count", String(count)));
      node.classList.toggle("active", item.id === id);
      node.setAttribute("aria-selected", String(item.id === id));
    }
  };
  set(active);
  return { element, set };
}

export function searchInput(placeholder: string, onInput: (value: string) => void): HTMLLabelElement {
  const wrap = el("label", "search");
  wrap.append(icon("search"));
  const input = el("input", "search-input");
  input.type = "search";
  input.placeholder = placeholder;
  input.setAttribute("aria-label", placeholder);
  input.addEventListener("input", () => onInput(input.value));
  wrap.append(input);
  return wrap;
}

export function statTile(label: string, value: string, tone: Tone = "neutral"): HTMLElement {
  const tile = el("div", `stat stat-${tone}`);
  tile.append(el("div", "stat-value", value), el("div", "stat-label", label));
  return tile;
}

export function errorState(title: string, error: { code?: string; message: string }, onRetry?: () => void): HTMLElement {
  const retry = onRetry ? actionButton("Try again", { icon: "refresh" }) : undefined;
  retry?.addEventListener("click", onRetry!);
  return emptyState({ icon: "alert", tone: "danger", title, body: error.message, action: retry });
}
