/**
 * The page editor (plan 33, C5): Notion-like blocks, written with DOM APIs only.
 *
 * - Text blocks are contenteditable; what they hold is read back into typed spans (text with bold, italic, code or
 *   strike, and mentions), so the page never stores markup.
 * - "/" opens the block menu, "@" mentions a page, phone, skill, routine, task, account or connection. "# ", "- ",
 *   "[] ", "> ", "1. " and "---" turn a block into a heading, list, to-do, quote, numbered list or divider.
 * - Enter splits a block, Backspace at the start joins it to the one above, the ⋮⋮ handle moves or changes a block.
 * - Live views and plan boards are blocks too; they change only their own settings and cards.
 */
import type { GlassContext } from "../app.js";
import { looksSecret } from "../services/command.js";
import {
  KIND_ICON, KIND_LABEL, SLASH_ITEMS, TEXT_TYPES, convertBlock, deleteRange, filterSlash, insertRef, isRef, listNumber,
  markdownShortcut, moveBlock, newId, normalizeSpans, searchDirectory, spanLength, splitSpans, textBefore, textBlock, textLength,
  type Block, type DirectoryEntry, type Ref, type SlashItem, type Span, type TextBlock, type TextType,
} from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { loadDirectory, routeOfRef } from "./directory.js";
import { createPlanView } from "./plan.js";
import { createLiveView } from "./views.js";
import { createTableBlock } from "./tableView.js";

export interface Editor {
  element: HTMLElement;
  blocks(): Block[];
  focusStart(): void;
  /** Where the caret is (block id and offset), so a reloaded page can put it back. */
  caretAt(): { blockId: string; offset: number } | null;
  focusBlock(blockId: string, offset: number): void;
  destroy(): void;
}

export interface EditorOptions {
  /** "/ Ask AI": open the AI about this page. */
  askAi?(): void;
}

interface Row {
  element: HTMLElement;
  editable: HTMLElement | null;
  destroy(): void;
}

type Menu =
  | { kind: "slash"; index: number; start: number; active: number; items: SlashItem[] }
  | { kind: "mention"; index: number; start: number; active: number; items: DirectoryEntry[] }
  | { kind: "block"; index: number };

const PLACEHOLDER: Partial<Record<TextType, string>> = {
  p: "Type '/' for blocks, '@' to mention a phone, skill, routine or page",
  h1: "Heading 1", h2: "Heading 2", h3: "Heading 3", todo: "To-do", bullet: "List", number: "List", quote: "Quote", callout: "Note",
};
const TYPE_LABEL: Record<TextType, string> = {
  p: "Text", h1: "Heading 1", h2: "Heading 2", h3: "Heading 3", todo: "To-do", bullet: "Bulleted list", number: "Numbered list", quote: "Quote", callout: "Callout",
};
const isText = (b: Block): b is TextBlock => (TEXT_TYPES as readonly string[]).includes(b.type);

export function createEditor(ctx: GlassContext, pageId: string, initial: Block[], onChange: (blocks: Block[]) => void, options: EditorOptions = {}): Editor {
  const element = el("div", "ws-editor");
  let blocks: Block[] = initial.length ? initial.slice() : [textBlock("p")];
  let rows: Row[] = [];
  let menu: Menu | null = null;
  let popover: HTMLElement | null = null;
  let directory: DirectoryEntry[] = [];
  let dragging: number | null = null;

  const changed = () => onChange(blocks.slice());
  const loadDir = () => loadDirectory(ctx).then((d) => { directory = d; }).catch(() => undefined);

  // ------------------------------------------------------------------ spans <-> DOM

  function renderSpans(target: HTMLElement, spans: Span[]): void {
    target.replaceChildren();
    for (const span of spans) {
      if (isRef(span)) {
        target.append(mentionChip(span.ref));
        continue;
      }
      let host: HTMLElement = target;
      for (const [mark, tag] of [["b", "strong"], ["i", "em"], ["s", "s"], ["c", "code"]] as const) {
        if (span[mark]) {
          const wrap = el(tag);
          host.append(wrap);
          host = wrap;
        }
      }
      host.append(span.t);
    }
  }

  function mentionChip(ref: Ref): HTMLElement {
    const chip = el("span", `ws-mention ws-mention-${ref.kind}`);
    chip.setAttribute("contenteditable", "false");
    chip.dataset.refKind = ref.kind;
    chip.dataset.refId = ref.id;
    chip.dataset.refLabel = ref.label;
    if (ref.deviceId) chip.dataset.deviceId = ref.deviceId;
    chip.title = `${KIND_LABEL[ref.kind]}: ${ref.label}`;
    chip.append(el("span", "ws-mention-icon", KIND_ICON[ref.kind]), el("span", "ws-mention-label", ref.label || KIND_LABEL[ref.kind]));
    chip.addEventListener("click", () => ctx.navigate(routeOfRef(ref)));
    return chip;
  }

  function readSpans(node: Node): Span[] {
    const out: Span[] = [];
    const walk = (parent: Node, marks: Partial<Record<"b" | "i" | "c" | "s", true>>) => {
      const kids = Array.from((parent as HTMLElement).childNodes ?? []) as Node[];
      if (!kids.length && parent !== node) return;
      if (!kids.length) {
        const text = (parent as HTMLElement).textContent ?? "";
        if (text) out.push({ t: text, ...marks });
        return;
      }
      kids.forEach((child, i) => {
        const tag = String((child as HTMLElement).tagName ?? "").toUpperCase();
        if ((child as Node).nodeType === 3 || tag === "#TEXT") {
          const text = child.textContent ?? "";
          if (text) out.push({ t: text, ...marks });
          return;
        }
        const data = (child as HTMLElement).dataset;
        if (data?.refKind && data.refId) {
          const ref: Ref = { kind: data.refKind as Ref["kind"], id: data.refId, label: data.refLabel ?? "" };
          if (data.deviceId) ref.deviceId = data.deviceId;
          out.push({ ref });
          return;
        }
        if (tag === "BR") {
          out.push({ t: "\n", ...marks });
          return;
        }
        const next = { ...marks };
        if (tag === "B" || tag === "STRONG") next.b = true;
        if (tag === "I" || tag === "EM") next.i = true;
        if (tag === "CODE") next.c = true;
        if (tag === "S" || tag === "STRIKE" || tag === "DEL") next.s = true;
        if ((tag === "DIV" || tag === "P") && i > 0) out.push({ t: "\n", ...marks });
        const kidsOf = Array.from((child as HTMLElement).childNodes ?? []);
        if (!kidsOf.length) {
          const text = child.textContent ?? "";
          if (text) out.push({ t: text, ...next });
        } else walk(child, next);
      });
    };
    walk(node, {});
    return normalizeSpans(out);
  }

  /** The caret's offset in a block (mentions count as one); the end of the block when there is no selection there. */
  function caret(editable: HTMLElement, fallback: number): number {
    const sel = globalThis.getSelection?.();
    if (!sel || !sel.rangeCount || !editable.contains?.(sel.anchorNode)) return fallback;
    const range = sel.getRangeAt(0);
    const before = document.createRange();
    before.selectNodeContents(editable);
    before.setEnd(range.startContainer, range.startOffset);
    return textLength(readSpans(before.cloneContents()));
  }

  function hasSelection(editable: HTMLElement): boolean {
    const sel = globalThis.getSelection?.();
    return Boolean(sel && sel.rangeCount && !sel.isCollapsed && editable.contains?.(sel.anchorNode));
  }

  function place(editable: HTMLElement, offset: number): void {
    editable.focus?.();
    const sel = globalThis.getSelection?.();
    if (!sel || typeof document.createRange !== "function") return;
    const range = document.createRange();
    let left = offset;
    const visit = (node: Node): boolean => {
      for (const child of Array.from(node.childNodes)) {
        if (child.nodeType === 3) {
          const len = child.textContent?.length ?? 0;
          if (left <= len) {
            range.setStart(child, left);
            return true;
          }
          left -= len;
        } else if ((child as HTMLElement).dataset?.refKind) {
          if (left <= 0) {
            range.setStartBefore(child);
            return true;
          }
          left -= 1;
          if (left === 0) {
            range.setStartAfter(child);
            return true;
          }
        } else if (visit(child)) return true;
      }
      return false;
    };
    if (!visit(editable)) {
      range.selectNodeContents(editable);
      range.collapse(false);
    } else range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);
  }

  // ------------------------------------------------------------------ rows

  function rowFor(index: number): Row {
    const block = blocks[index];
    const element = el("div", `ws-block ws-block-${block.type}`);
    element.dataset.blockId = block.id;
    element.dataset.type = block.type;
    const gutter = el("div", "ws-gutter");
    const plus = el("button", "ws-gutter-btn", "+");
    plus.type = "button";
    plus.setAttribute("aria-label", "Add a block below");
    plus.title = "Add a block below";
    plus.addEventListener("click", () => {
      const at = rows.findIndex((r) => r.element === element);
      insertAt(at + 1, textBlock("p", [{ t: "/" }]), 1);
      openSlash(at + 1, 0);
    });
    const handle = el("button", "ws-gutter-btn ws-handle", "⋮⋮");
    handle.type = "button";
    handle.draggable = true;
    handle.setAttribute("aria-label", "Move or change this block");
    handle.title = "Drag to move, click for more";
    handle.addEventListener("click", () => openBlockMenu(rows.findIndex((r) => r.element === element)));
    handle.addEventListener("dragstart", (e: DragEvent) => {
      dragging = rows.findIndex((r) => r.element === element);
      e.dataTransfer?.setData("text/plain", block.id);
    });
    gutter.append(plus, handle);
    const content = el("div", "ws-block-content");
    element.append(gutter, content);
    element.addEventListener("dragover", (e: DragEvent) => {
      if (dragging === null) return;
      e.preventDefault();
      const box = element.getBoundingClientRect();
      const below = e.clientY > box.top + box.height / 2;
      element.classList.toggle("ws-drop-below", below);
      element.classList.toggle("ws-drop-above", !below);
    });
    element.addEventListener("dragleave", () => element.classList.remove("ws-drop-below", "ws-drop-above"));
    element.addEventListener("drop", (e: DragEvent) => {
      e.preventDefault();
      const below = element.classList.contains("ws-drop-below");
      element.classList.remove("ws-drop-below", "ws-drop-above");
      const from = dragging;
      dragging = null;
      const to = rows.findIndex((r) => r.element === element) + (below ? 1 : 0);
      if (from === null || from === to || from + 1 === to) return;
      blocks = moveBlock(blocks, from, from < to ? to - 1 : to);
      renderAll();
      changed();
    });

    if (!isText(block)) return nonTextRow(block, element, content);

    if (block.type === "todo") {
      const box = el("input", "ws-check");
      box.type = "checkbox";
      box.checked = Boolean(block.checked);
      box.setAttribute("aria-label", "Done");
      box.addEventListener("change", () => {
        const at = rows.findIndex((r) => r.element === element);
        const current = blocks[at] as TextBlock;
        blocks[at] = { ...current, checked: Boolean(box.checked) };
        element.classList.toggle("checked", Boolean(box.checked));
        changed();
      });
      element.classList.toggle("checked", Boolean(block.checked));
      content.append(box);
    } else if (block.type === "bullet") content.append(el("span", "ws-bullet", "•"));
    else if (block.type === "number") content.append(el("span", "ws-number", `${listNumber(blocks, index)}.`));
    else if (block.type === "callout") {
      const icon = el("button", "ws-callout-icon", block.icon ?? "💡");
      icon.type = "button";
      icon.setAttribute("aria-label", "Change the callout's icon");
      icon.addEventListener("click", () => {
        const at = rows.findIndex((r) => r.element === element);
        const choices = ["💡", "⚠️", "✅", "📌", "🔥", "📱", "🗓️", "ℹ️"];
        const current = blocks[at] as TextBlock;
        const next = choices[(choices.indexOf(current.icon ?? "💡") + 1) % choices.length];
        blocks[at] = { ...current, icon: next };
        icon.textContent = next;
        changed();
      });
      content.append(icon);
    }
    const editable = el("div", "ws-text");
    editable.setAttribute("contenteditable", "true");
    editable.setAttribute("role", "textbox");
    editable.setAttribute("aria-multiline", "true");
    editable.setAttribute("aria-label", `${TYPE_LABEL[block.type]} block`);
    editable.dataset.placeholder = PLACEHOLDER[block.type] ?? "";
    editable.spellcheck = true;
    renderSpans(editable, block.text);
    content.append(editable);
    editable.addEventListener("input", () => onInput(element, editable));
    editable.addEventListener("keydown", (e: KeyboardEvent) => onKey(element, editable, e));
    editable.addEventListener("paste", (e: ClipboardEvent) => onPaste(element, editable, e));
    editable.addEventListener("blur", () => setTimeout(() => {
      if (menu && menu.kind !== "block" && rows[menu.index]?.editable === editable && globalThis.document?.activeElement !== editable) closeMenu();
    }, 150));
    return { element, editable, destroy() {} };
  }

  function nonTextRow(block: Block, element: HTMLElement, content: HTMLElement): Row {
    const replace = (next: Block) => {
      const at = rows.findIndex((r) => r.element === element);
      if (at < 0) return;
      blocks[at] = next;
      changed();
    };
    if (block.type === "divider") {
      content.append(el("hr", "ws-divider"));
      return { element, editable: null, destroy() {} };
    }
    if (block.type === "ref") {
      const cardNode = el("button", `ws-ref-card ws-mention-${block.ref.kind}`);
      cardNode.type = "button";
      cardNode.append(el("span", "ws-ref-icon", KIND_ICON[block.ref.kind]), el("span", "ws-ref-label", block.ref.label || KIND_LABEL[block.ref.kind]),
        el("span", "ws-ref-kind", KIND_LABEL[block.ref.kind]));
      cardNode.addEventListener("click", () => ctx.navigate(routeOfRef(block.ref)));
      content.append(cardNode);
      return { element, editable: null, destroy() {} };
    }
    if (block.type === "table") {
      const tableBlock = createTableBlock(ctx, block, replace);
      content.append(tableBlock.element);
      return { element, editable: null, destroy: () => tableBlock.destroy() };
    }
    if (block.type === "view") {
      const view = createLiveView(ctx, block, pageId, replace);
      content.append(view.element);
      return { element, editable: null, destroy: () => view.destroy() };
    }
    const plan = createPlanView(ctx, block as Extract<Block, { type: "board" }>, replace);
    content.append(plan.element);
    return { element, editable: null, destroy: () => plan.destroy() };
  }

  function renderAll(): void {
    for (const r of rows) r.destroy();
    closeMenu();
    rows = blocks.map((_, i) => rowFor(i));
    const tail = el("div", "ws-editor-tail");
    tail.setAttribute("aria-hidden", "true");
    tail.addEventListener("click", () => {
      const last = blocks[blocks.length - 1];
      if (last && isText(last) && !textLength(last.text)) place(rows[rows.length - 1].editable!, 0);
      else insertAt(blocks.length, textBlock("p"), 0);
    });
    setChildren(element, ...rows.map((r) => r.element), tail);
  }

  function rerender(index: number, focusAt: number | null = null): void {
    const old = rows[index];
    old.destroy();
    const next = rowFor(index);
    element.insertBefore(next.element, old.element);
    old.element.remove();
    rows[index] = next;
    renumber();
    if (focusAt !== null && next.editable) place(next.editable, focusAt);
  }

  function renumber(): void {
    blocks.forEach((b, i) => {
      if (b.type !== "number") return;
      const label = rows[i]?.element.querySelector?.(".ws-number");
      if (label) label.textContent = `${listNumber(blocks, i)}.`;
    });
  }

  function insertAt(index: number, block: Block, focusAt: number | null = 0): void {
    blocks.splice(index, 0, block);
    const row = rowFor(index);
    const before = rows[index]?.element ?? element.querySelector?.(".ws-editor-tail") ?? null;
    element.insertBefore(row.element, before);
    rows.splice(index, 0, row);
    renumber();
    changed();
    if (focusAt !== null && row.editable) place(row.editable, focusAt);
  }

  function removeAt(index: number): void {
    rows[index].destroy();
    rows[index].element.remove();
    rows.splice(index, 1);
    blocks.splice(index, 1);
    if (!blocks.length) insertAt(0, textBlock("p"));
    renumber();
    changed();
  }

  // ------------------------------------------------------------------ typing

  function indexOf(row: HTMLElement): number {
    return rows.findIndex((r) => r.element === row);
  }

  function onInput(row: HTMLElement, editable: HTMLElement): void {
    const at = indexOf(row);
    if (at < 0) return;
    const block = blocks[at] as TextBlock;
    const spans = readSpans(editable);
    const plain = spans.map((s) => (isRef(s) ? "" : s.t)).join("");
    if (block.type === "p" && !menu) {
      const shortcut = markdownShortcut(plain);
      if (shortcut) {
        if (shortcut.type === "divider") {
          blocks[at] = { id: block.id, type: "divider" };
          rerender(at);
          insertAt(at + 1, textBlock("p"), 0);
          changed();
          return;
        }
        const rest = splitSpans(spans, plain.length - shortcut.rest.length)[1];
        const next = convertBlock({ ...block, text: rest }, shortcut.type);
        if (shortcut.type === "todo") next.checked = Boolean(shortcut.checked);
        blocks[at] = next;
        rerender(at, 0);
        changed();
        return;
      }
    }
    if (spans.some((s) => !isRef(s) && looksSecret(s.t))) row.classList.add("ws-warn");
    else row.classList.remove("ws-warn");
    blocks[at] = { ...block, text: spans };
    changed();
    trackTriggers(at, editable, spans);
  }

  function trackTriggers(at: number, editable: HTMLElement, spans: Span[]): void {
    const offset = caret(editable, textLength(spans));
    const before = textBefore(spans, offset);
    if (menu && (menu.kind === "slash" || menu.kind === "mention") && menu.index === at) {
      const query = before.slice(menu.start + 1);
      if (offset <= menu.start || before[menu.start] !== (menu.kind === "slash" ? "/" : "@") || /\n/.test(query) || query.length > 40 || /\s{2}/.test(query)) {
        closeMenu();
        return;
      }
      if (menu.kind === "slash") {
        menu.items = filterSlash(query);
        if (!menu.items.length && query.length > 3) return closeMenu();
      } else menu.items = searchDirectory(directory, query, 8);
      menu.active = 0;
      drawMenu();
      return;
    }
    const last = before.slice(-1);
    const prev = before.slice(-2, -1);
    if ((last === "/" || last === "@") && (!prev || /\s/.test(prev))) {
      if (last === "/") openSlash(at, before.length - 1);
      else openMention(at, before.length - 1);
    }
  }

  function onKey(row: HTMLElement, editable: HTMLElement, e: KeyboardEvent): void {
    const at = indexOf(row);
    if (at < 0) return;
    if (menu && (menu.kind === "slash" || menu.kind === "mention") && menu.index === at) {
      const count = menu.items.length;
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (count) menu.active = (menu.active + (e.key === "ArrowDown" ? 1 : count - 1)) % count;
        drawMenu();
        return;
      }
      if ((e.key === "Enter" || e.key === "Tab") && count) {
        e.preventDefault();
        choose(menu.active);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        closeMenu();
        return;
      }
    }
    const block = blocks[at] as TextBlock;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && ["b", "i", "e"].includes(String(e.key).toLowerCase()) || (mod && e.shiftKey && String(e.key).toLowerCase() === "s")) {
      e.preventDefault();
      const key = String(e.key).toLowerCase();
      const exec = (globalThis.document as unknown as { execCommand?: (c: string) => boolean })?.execCommand;
      if (key === "e") wrapSelection(editable, "code");
      else exec?.call(globalThis.document, key === "b" ? "bold" : key === "i" ? "italic" : "strikeThrough");
      onInput(row, editable);
      return;
    }
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      const spans = readSpans(editable);
      const offset = caret(editable, textLength(spans));
      if ((block.type === "bullet" || block.type === "number" || block.type === "todo") && !textLength(spans)) {
        blocks[at] = convertBlock({ ...block, text: [] }, "p");
        rerender(at, 0);
        changed();
        return;
      }
      const [left, right] = splitSpans(spans, offset);
      blocks[at] = { ...block, text: left };
      renderSpans(editable, left);
      const nextType: TextType = block.type === "bullet" || block.type === "number" || block.type === "todo" ? block.type : "p";
      insertAt(at + 1, textBlock(nextType, right), 0);
      return;
    }
    if (e.key === "Enter" && e.shiftKey) {
      e.preventDefault();
      (globalThis.document as unknown as { execCommand?: (c: string, u: boolean, v: string) => boolean })?.execCommand?.call(globalThis.document, "insertText", false, "\n");
      return;
    }
    if (e.key === "Backspace" && !hasSelection(editable)) {
      const spans = readSpans(editable);
      const offset = caret(editable, textLength(spans));
      if (offset !== 0) return;
      e.preventDefault();
      if (block.type !== "p") {
        blocks[at] = convertBlock(block, "p");
        rerender(at, 0);
        changed();
        return;
      }
      const prev = blocks[at - 1];
      if (!prev) return;
      if (isText(prev)) {
        const join = textLength(prev.text);
        blocks[at - 1] = { ...prev, text: normalizeSpans([...prev.text, ...spans]) };
        removeAt(at);
        rerender(at - 1, join);
        changed();
      } else if (prev.type === "divider" || !textLength(spans)) {
        removeAt(prev.type === "divider" ? at - 1 : at);
        const target = prev.type === "divider" ? at - 1 : focusableBefore(at);
        if (target !== null && rows[target]?.editable) place(rows[target].editable!, prev.type === "divider" ? 0 : textLength((blocks[target] as TextBlock).text));
      }
      return;
    }
    if (e.key === "ArrowUp" || e.key === "ArrowDown") {
      const spans = readSpans(editable);
      const offset = caret(editable, textLength(spans));
      const up = e.key === "ArrowUp";
      if ((up && offset === 0) || (!up && offset === textLength(spans))) {
        const target = up ? focusableBefore(at) : focusableAfter(at);
        if (target !== null) {
          e.preventDefault();
          place(rows[target].editable!, up ? textLength((blocks[target] as TextBlock).text) : 0);
        }
      }
    }
  }

  function focusableBefore(at: number): number | null {
    for (let i = at - 1; i >= 0; i -= 1) if (rows[i]?.editable) return i;
    return null;
  }

  function focusableAfter(at: number): number | null {
    for (let i = at + 1; i < rows.length; i += 1) if (rows[i]?.editable) return i;
    return null;
  }

  function wrapSelection(editable: HTMLElement, tag: "code"): void {
    const sel = globalThis.getSelection?.();
    if (!sel || !sel.rangeCount || sel.isCollapsed || !editable.contains?.(sel.anchorNode)) return;
    const range = sel.getRangeAt(0);
    const wrap = el(tag);
    wrap.append(range.extractContents());
    range.insertNode(wrap);
  }

  function onPaste(row: HTMLElement, editable: HTMLElement, e: ClipboardEvent): void {
    const text = e.clipboardData?.getData("text/plain");
    if (text == null) return;
    e.preventDefault();
    const at = indexOf(row);
    const lines = text.replace(/\r\n?/g, "\n").split("\n");
    const spans = readSpans(editable);
    const offset = caret(editable, textLength(spans));
    const [left, right] = splitSpans(spans, offset);
    if (lines.length === 1) {
      const next = normalizeSpans([...left, { t: lines[0] }, ...right]);
      blocks[at] = { ...(blocks[at] as TextBlock), text: next };
      renderSpans(editable, next);
      place(editable, textLength(left) + lines[0].length);
      changed();
      return;
    }
    blocks[at] = { ...(blocks[at] as TextBlock), text: normalizeSpans([...left, { t: lines[0] }]) };
    renderSpans(editable, (blocks[at] as TextBlock).text);
    const middle = lines.slice(1, -1).slice(0, 500).map((line) => textBlock("p", line ? [{ t: line }] : []));
    const last = lines[lines.length - 1];
    middle.forEach((b, i) => insertAt(at + 1 + i, b, null));
    insertAt(at + 1 + middle.length, textBlock("p", normalizeSpans([{ t: last }, ...right])), last.length);
  }

  // ------------------------------------------------------------------ menus

  function openSlash(index: number, start: number): void {
    menu = { kind: "slash", index, start, active: 0, items: SLASH_ITEMS };
    drawMenu();
  }

  function openMention(index: number, start: number): void {
    menu = { kind: "mention", index, start, active: 0, items: searchDirectory(directory, "", 8) };
    drawMenu();
    void loadDir().then(() => {
      if (menu?.kind === "mention" && menu.index === index) {
        const editable = rows[index]?.editable;
        const spans = editable ? readSpans(editable) : [];
        const offset = editable ? caret(editable, textLength(spans)) : 0;
        menu.items = searchDirectory(directory, textBefore(spans, offset).slice(start + 1), 8);
        drawMenu();
      }
    });
  }

  function openBlockMenu(index: number): void {
    if (index < 0) return;
    menu = menu?.kind === "block" && menu.index === index ? null : { kind: "block", index };
    drawMenu();
  }

  function closeMenu(): void {
    menu = null;
    popover?.remove();
    popover = null;
  }

  function drawMenu(): void {
    popover?.remove();
    popover = null;
    if (!menu) return;
    const row = rows[menu.index];
    if (!row) return;
    const box = el("div", "ws-popover");
    box.setAttribute("role", "listbox");
    const option = (label: string, hint: string, icon: string, active: boolean, pick: () => void) => {
      const b = el("button", `ws-pop-row${active ? " active" : ""}`);
      b.type = "button";
      b.setAttribute("role", "option");
      b.setAttribute("aria-selected", String(active));
      b.append(el("span", "ws-pop-icon", icon), el("span", "ws-pop-label", label), el("span", "ws-pop-hint", hint));
      b.addEventListener("mousedown", (e: MouseEvent) => e.preventDefault());
      b.addEventListener("click", pick);
      return b;
    };
    if (menu.kind === "slash") {
      box.setAttribute("aria-label", "Blocks");
      let group = "";
      menu.items.forEach((item, i) => {
        if (item.group !== group) {
          group = item.group;
          box.append(el("div", "ws-pop-group", group === "Live" ? "Live from the Command Center" : group));
        }
        box.append(option(item.label, item.hint, SLASH_ICON[item.id] ?? "▫️", i === (menu as { active: number }).active, () => choose(i)));
      });
      if (!menu.items.length) box.append(el("div", "ws-pop-empty", "No block by that name"));
    } else if (menu.kind === "mention") {
      box.setAttribute("aria-label", "Mention");
      menu.items.forEach((entry, i) => box.append(option(entry.ref.label, entry.detail ? `${KIND_LABEL[entry.ref.kind]} · ${entry.detail}` : KIND_LABEL[entry.ref.kind], KIND_ICON[entry.ref.kind],
        i === (menu as { active: number }).active, () => choose(i))));
      if (!menu.items.length) box.append(el("div", "ws-pop-empty", directory.length ? "Nothing by that name" : "Loading…"));
    } else {
      box.setAttribute("aria-label", "Block");
      const index = menu.index;
      const block = blocks[index];
      if (isText(block)) {
        box.append(el("div", "ws-pop-group", "Turn into"));
        for (const type of TEXT_TYPES) {
          box.append(option(TYPE_LABEL[type], "", SLASH_ICON[type] ?? "▫️", block.type === type, () => {
            blocks[index] = convertBlock(blocks[index] as TextBlock, type);
            closeMenu();
            rerender(index, 0);
            changed();
          }));
        }
      }
      box.append(el("div", "ws-pop-group", "Block"));
      box.append(option("Duplicate", "", "⧉", false, () => {
        closeMenu();
        const copy = JSON.parse(JSON.stringify(blocks[index])) as Block;
        copy.id = newId("b");
        if (copy.type === "board") copy.items = copy.items.map((it) => ({ ...it, id: newId("i"), taskId: null }));
        insertAt(index + 1, copy, null);
      }));
      if (index > 0) box.append(option("Move up", "", "↑", false, () => { closeMenu(); blocks = moveBlock(blocks, index, index - 1); renderAll(); changed(); }));
      if (index < blocks.length - 1) box.append(option("Move down", "", "↓", false, () => { closeMenu(); blocks = moveBlock(blocks, index, index + 1); renderAll(); changed(); }));
      box.append(option("Delete", "", "🗑️", false, () => { closeMenu(); removeAt(index); }));
    }
    row.element.append(box);
    popover = box;
  }

  function choose(i: number): void {
    if (!menu || menu.kind === "block") return;
    const at = menu.index;
    const editable = rows[at]?.editable;
    if (!editable) return closeMenu();
    const spans = readSpans(editable);
    const offset = Math.max(caret(editable, textLength(spans)), menu.start + 1);
    const rest = deleteRange(spans, menu.start, offset);
    if (menu.kind === "mention") {
      const entry = menu.items[i];
      const start = menu.start;
      closeMenu();
      if (!entry) return;
      const placed = insertRef(rest, start, entry.ref);
      blocks[at] = { ...(blocks[at] as TextBlock), text: placed };
      renderSpans(editable, placed);
      place(editable, start + 2);
      changed();
      return;
    }
    const item = menu.items[i];
    const slashAt = menu.start;
    closeMenu();
    if (!item) return;
    const current = blocks[at] as TextBlock;
    if (item.action === "ai") {
      blocks[at] = { ...current, text: rest };
      renderSpans(editable, rest);
      place(editable, slashAt);
      changed();
      options.askAi?.();
      return;
    }
    const made = item.make();
    if (isText(made)) {
      if (!textLength(rest)) {
        blocks[at] = convertBlock({ ...current, text: [] }, made.type as TextType);
        rerender(at, 0);
        changed();
      } else {
        blocks[at] = { ...current, text: rest };
        renderSpans(editable, rest);
        insertAt(at + 1, made, 0);
      }
      return;
    }
    if (!textLength(rest)) {
      blocks[at] = { ...made, id: current.id } as Block;
      rerender(at);
      insertAt(at + 1, textBlock("p"), 0);
    } else {
      blocks[at] = { ...current, text: rest };
      renderSpans(editable, rest);
      insertAt(at + 1, made, null);
      insertAt(at + 2, textBlock("p"), 0);
    }
    changed();
  }

  renderAll();
  void loadDir();
  return {
    element,
    blocks: () => blocks.slice(),
    focusStart() {
      const first = rows.findIndex((r) => r.editable);
      if (first >= 0) place(rows[first].editable!, 0);
    },
    caretAt() {
      const active = globalThis.document?.activeElement;
      const index = rows.findIndex((r) => r.editable && (r.editable === active || r.editable.contains?.(active as Node)));
      if (index < 0) return null;
      const editable = rows[index].editable!;
      return { blockId: blocks[index].id, offset: caret(editable, textLength(readSpans(editable))) };
    },
    focusBlock(blockId: string, offset: number) {
      const index = blocks.findIndex((b) => b.id === blockId);
      const editable = index >= 0 ? rows[index]?.editable : null;
      if (editable) place(editable, Math.min(offset, textLength(readSpans(editable))));
    },
    destroy() {
      for (const r of rows) r.destroy();
      closeMenu();
    },
  };
}

const SLASH_ICON: Record<string, string> = {
  p: "¶", h1: "H1", h2: "H2", h3: "H3", todo: "☑", bullet: "•", number: "1.", quote: "❝", callout: "💡", divider: "—",
  table: "▤", board: "▦", calendar: "🗓️", "view-tasks": "▶️", "view-tasks-board": "▦", "view-routines": "🔁", "view-approvals": "✋",
  "view-phones": "📱", "view-results": "✅", "view-pages": "📄", ai: "✨",
};

export { spanLength };
