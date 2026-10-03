/**
 * Ports (plan 48): the small pieces every Ports screen shares, so the page, the Add sheet and the plugin sheet look
 * and behave the same: monogram tiles, status pills, switches, port rows, the key card, the checks list, and the sheet
 * (a modal or a side drawer) with Esc to close and focus kept inside.
 */
import { copyText } from "../ui/clipboard.js";
import { el, button, setChildren } from "../ui/dom.js";
import { icon, type IconName } from "../ui/icons.js";
import {
  monogram,
  portAbout,
  portLabel,
  sensitivityLabel,
  statusInfo,
  checkLabel,
  type CheckItem,
  type KeyCard,
  type Plugin,
  type ServedPort,
} from "../services/ports.js";

export function tile(name: string, title: string, size: "sm" | "md" | "lg" = "md"): HTMLElement {
  const m = monogram(name, title);
  const node = el("span", `pt-tile pt-tile-${size} pt-tile-c${m.tile}`, m.letters);
  node.setAttribute("aria-hidden", "true");
  return node;
}

export function statusPill(plugin: Pick<Plugin, "status" | "healthDetail" | "checks">): HTMLElement {
  const info = statusInfo(plugin);
  const pill = el("span", `pt-pill pt-pill-${info.tone}`);
  pill.append(el("span", "pt-dot"), el("span", undefined, info.label));
  return pill;
}

export function wayGlyph(way: "out" | "in"): HTMLElement {
  const node = el("span", `pt-way pt-way-${way}`);
  node.append(icon(way === "out" ? "out" : "in"));
  node.title = way === "out" ? "Phone → plugin" : "Plugin → phone";
  return node;
}

export function sensitivityChip(s: ServedPort["sensitivity"]): HTMLElement {
  const node = el("span", `pt-sense pt-sense-${s}`);
  if (s !== "public") node.append(icon(s === "secret" ? "lock" : "user"));
  node.append(el("span", undefined, sensitivityLabel(s)));
  return node;
}

/** A real switch: a button with aria-pressed, a label for screen readers, and a busy state. */
export function toggle(on: boolean, label: string, onChange: (next: boolean) => Promise<void> | void): HTMLButtonElement {
  const node = button("", "pt-switch");
  node.setAttribute("role", "switch");
  node.setAttribute("aria-checked", String(on));
  node.setAttribute("aria-label", label);
  node.classList.toggle("on", on);
  node.append(el("span", "pt-knob"));
  node.addEventListener("click", async () => {
    if (node.disabled) return;
    const next = !node.classList.contains("on");
    node.disabled = true;
    node.classList.toggle("on", next);
    node.setAttribute("aria-checked", String(next));
    try {
      await onChange(next);
    } catch {
      node.classList.toggle("on", !next);
      node.setAttribute("aria-checked", String(!next));
    } finally {
      node.disabled = false;
    }
  });
  return node;
}

/** One port a plugin serves, with its switch. */
export function portRow(port: ServedPort, control: HTMLElement | null, note = ""): HTMLElement {
  const row = el("div", `pt-port-row${port.allowed ? "" : " off"}`);
  const text = el("div", "pt-port-text");
  const head = el("div", "pt-port-head");
  head.append(el("span", "pt-port-name", portLabel(port.port)), el("code", "pt-code", port.port), sensitivityChip(port.sensitivity));
  text.append(head, el("p", "pt-port-summary", note || portAbout(port.port, port.summary)));
  row.append(wayGlyph(port.way), text);
  if (control) row.append(control);
  return row;
}

/** A plugin key, shown once, with the line to set it in PowerShell, bash or cmd. */
export function keyCard(name: string, key: KeyCard, say: (text: string) => void): HTMLElement {
  const box = el("div", "pt-key");
  const head = el("div", "pt-key-head");
  head.append(icon("key"), el("span", undefined, `${name}'s key`), el("span", "pt-key-once", "Shown once"));
  const value = el("div", "pt-key-value");
  const code = el("code", "pt-key-code", key.value);
  value.append(code, copyButton(key.value, "Copy key", say));

  const shells: Array<["powershell" | "bash" | "cmd", string]> = [["powershell", "PowerShell"], ["bash", "bash / zsh"], ["cmd", "Command Prompt"]];
  let shell: "powershell" | "bash" | "cmd" = "powershell";
  const tabs = el("div", "pt-shells");
  tabs.setAttribute("role", "tablist");
  const line = el("div", "pt-key-line");
  const draw = () => {
    setChildren(tabs, ...shells.map(([id, label]) => {
      const tab = button(label, `pt-shell${id === shell ? " active" : ""}`);
      tab.setAttribute("role", "tab");
      tab.setAttribute("aria-selected", String(id === shell));
      tab.addEventListener("click", () => {
        shell = id;
        draw();
      });
      return tab;
    }));
    setChildren(line, el("code", "pt-key-code", key[shell]), copyButton(key[shell], "Copy line", say));
  };
  draw();
  box.append(head, value, el("p", "pt-fine", "Set it where the plugin starts, then restart the plugin:"), tabs, line,
    el("p", "pt-fine", "Cyclone keeps the key sealed on this PC and never shows it again. Lost it? Make a new one."));
  return box;
}

export function copyButton(text: string, label: string, say: (text: string) => void): HTMLButtonElement {
  const node = button("", "btn btn-secondary pt-copy");
  node.append(icon("copy"), el("span", "btn-label", "Copy"));
  node.setAttribute("aria-label", label);
  node.addEventListener("click", async () => {
    const ok = await copyText(text);
    say(ok ? "Copied." : "Your browser didn't allow copying. Select the text and copy it.");
    if (ok) {
      node.classList.add("done");
      setTimeout(() => node.classList.remove("done"), 1400);
    }
  });
  return node;
}

/**
 * The conformance checks. Failures come first, each with its hint. Checks that failed only because the plugin hasn't got
 * its key yet are one row, not one per check. Passed checks fold away unless nothing failed and [options.open] is set.
 */
export function checksList(items: CheckItem[], options: { reveal?: boolean; open?: boolean } = {}): HTMLElement {
  const wrap = el("div", "pt-checks-wrap");
  const failed = items.filter((c) => !c.ok);
  const keyed = failed.filter((c) => c.cause === "key");
  const other = failed.filter((c) => c.cause !== "key").sort((a, b) => Number(b.required) - Number(a.required));
  const passed = items.filter((c) => c.ok);
  let i = 0;
  const row = (state: "ok" | "bad" | "warn", title: string, hint: string, extra?: string): HTMLElement => {
    const node = el("li", `pt-check ${state}`);
    if (options.reveal) node.style.setProperty("--i", String(i++));
    const mark = el("span", "pt-check-mark");
    mark.append(icon(state === "ok" ? "check" : state === "bad" ? "close" : "alert"));
    const text = el("div", "pt-check-text");
    text.append(el("span", "pt-check-name", title));
    if (hint) text.append(el("span", "pt-check-hint", hint));
    if (extra) text.append(el("span", "pt-check-also", extra));
    node.append(mark, text);
    return node;
  };
  if (failed.length) {
    const list = el("ol", "pt-checks");
    if (keyed.length) {
      list.append(row("bad", keyed.length === 1 ? "1 check needs the plugin's key" : `${keyed.length} checks need the plugin's key`,
        keyed[0].hint, keyed.map((c) => checkLabel(c.name)).join(" · ")));
    }
    for (const c of other) {
      const item = row(c.required ? "bad" : "warn", checkLabel(c.name), c.hint || c.detail);
      item.title = c.detail;
      list.append(item);
    }
    wrap.append(list);
  }
  if (passed.length) {
    const fold = el("details", "pt-disclosure");
    if (!failed.length && options.open) fold.setAttribute("open", "");
    fold.append(el("summary", undefined, failed.length ? `${passed.length} checks passed` : `All ${passed.length} checks passed`));
    const list = el("ol", "pt-checks");
    for (const c of passed) list.append(row("ok", checkLabel(c.name), ""));
    fold.append(list);
    wrap.append(fold);
  }
  return wrap;
}

export interface Sheet {
  panel: HTMLElement;
  close(): void;
}

/** A modal (centered) or a drawer (from the right). Esc and the backdrop close it; focus stays inside. */
export function openSheet(host: HTMLElement, kind: "modal" | "drawer", label: string, onClose: () => void = () => {}): Sheet {
  const backdrop = el("div", `pt-backdrop pt-backdrop-${kind}`);
  const panel = el("div", `pt-sheet pt-sheet-${kind}`);
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-modal", "true");
  panel.setAttribute("aria-label", label);
  panel.tabIndex = -1;
  backdrop.append(panel);
  const before = (globalThis.document?.activeElement as HTMLElement | null) ?? null;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    globalThis.document?.removeEventListener?.("keydown", onKey as EventListener);
    backdrop.classList.add("closing");
    setTimeout(() => backdrop.remove(), 160);
    before?.focus?.();
    onClose();
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    }
    if (event.key === "Tab") {
      const focusable = [...panel.querySelectorAll("button, input, a, [tabindex]")].filter((n) => !(n as HTMLButtonElement).disabled) as HTMLElement[];
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = globalThis.document?.activeElement;
      if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    }
  };
  backdrop.addEventListener("mousedown", (event: MouseEvent) => {
    if (event.target === backdrop) close();
  });
  globalThis.document?.addEventListener?.("keydown", onKey as EventListener);
  host.append(backdrop);
  setTimeout(() => panel.focus?.(), 0);
  return { panel, close };
}

export function sheetHeader(title: string, subtitle: string, onClose: () => void, lead?: HTMLElement): HTMLElement {
  const head = el("header", "pt-sheet-head");
  if (lead) head.append(lead);
  const titles = el("div", "pt-sheet-titles");
  titles.append(el("h2", "pt-sheet-title", title));
  if (subtitle) titles.append(el("p", "pt-sheet-subtitle", subtitle));
  const close = button("", "btn btn-ghost pt-close");
  close.setAttribute("aria-label", "Close");
  close.append(icon("close"));
  close.addEventListener("click", onClose);
  head.append(titles, close);
  return head;
}

export function primary(label: string, iconName?: IconName): HTMLButtonElement {
  const node = button("", "btn btn-primary");
  if (iconName) node.append(icon(iconName));
  node.append(el("span", "btn-label", label));
  return node;
}

export function secondary(label: string, iconName?: IconName, variant: "secondary" | "ghost" | "danger" = "secondary"): HTMLButtonElement {
  const node = button("", `btn btn-${variant}`);
  if (iconName) node.append(icon(iconName));
  node.append(el("span", "btn-label", label));
  return node;
}

/** Runs an action with the button showing it is busy; errors go to [say]. */
export async function busy<T>(node: HTMLButtonElement, label: string, action: () => Promise<T>, say: (text: string, tone?: "ok" | "error") => void): Promise<T | null> {
  const before = node.querySelector(".btn-label")?.textContent ?? "";
  const labelNode = node.querySelector(".btn-label");
  node.disabled = true;
  node.classList.add("busy");
  if (labelNode) labelNode.textContent = label;
  try {
    return await action();
  } catch (err) {
    say((err as Error).message || "That didn't work.", "error");
    return null;
  } finally {
    node.disabled = false;
    node.classList.remove("busy");
    if (labelNode) labelNode.textContent = before;
  }
}
