/**
 * One page of the workspace (plan 33, C5): its path, icon and title, the block editor, the pages inside it and the
 * pages that mention it. Edits save by themselves a moment after typing stops; a save names the version it edited.
 * When the page changed elsewhere (another window, the AI), a quiet page reloads in place, and a refused save is
 * merged block by block (mergeBlocks) instead of losing either side's edits.
 */
import type { GlassContext } from "../app.js";
import { looksSecret } from "../services/command.js";
import { GatewayError } from "../services/gateway.js";
import { mergeBlocks, pagesApi, type Block, type Page } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { emptyState, loadingState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import type { GlassPage } from "../pages/page.js";
import { workspaceBus } from "./directory.js";
import { createEditor, type Editor } from "./editor.js";

const SAVE_AFTER_MS = 600;
const WATCH_MS = 4_000;
const ICONS = ["📄", "🗓️", "✅", "🎬", "📱", "🛍️", "💡", "📌", "🚀", "📈", "🔁", "✨", "🧭", "📝", "💬", "🔥", "⭐", "🏠", "🎯", "🧪"];

export function createPageView(ctx: GlassContext, pageId: string): GlassPage {
  const element = el("div", "page ws-page-screen");
  const bar = el("div", "ws-topbar");
  const doc = el("div", "ws-doc");
  element.append(bar, doc);
  setChildren(doc, loadingState("Opening the page…"));
  let page: Page | null = null;
  let editor: Editor | null = null;
  let version = 0;
  /** The blocks as the server had them at `version`: the base for a three-way merge. */
  let base: Block[] = [];
  let watching = false;
  /** Merges in a row without a successful save: a page that keeps changing under us is reloaded instead. */
  let merges = 0;
  let pending: { title?: string; icon?: string; blocks?: Block[] } = {};
  let timer: ReturnType<typeof setTimeout> | null = null;
  let saving = false;
  let destroyed = false;
  const status = el("span", "ws-save", "");
  status.setAttribute("role", "status");

  const say = (text: string, tone: "ok" | "warn" = "ok") => {
    status.textContent = text;
    status.className = `ws-save ws-save-${tone}`;
  };

  function schedule(patch: typeof pending): void {
    pending = { ...pending, ...patch };
    say("Editing…");
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void flush(), SAVE_AFTER_MS);
  }

  async function flush(): Promise<void> {
    if (timer) clearTimeout(timer);
    timer = null;
    if (saving || !page || !Object.keys(pending).length) return;
    const body = pending;
    pending = {};
    saving = true;
    say("Saving…");
    let retry = false;
    let refused = false;
    try {
      const saved = await pagesApi.save(ctx.client, page.id, { version, ...body });
      version = saved.version;
      base = saved.blocks;
      merges = 0;
      page = { ...page, ...saved, blocks: page.blocks };
      say(Object.keys(pending).length ? "Editing…" : "Saved");
      if (body.title !== undefined || body.icon !== undefined) workspaceBus.pagesChanged();
    } catch (err) {
      if (err instanceof GatewayError && err.status === 409) {
        saving = false;
        await merge({ ...body, ...pending });
        return;
      }
      pending = { ...body, ...pending };
      say(`Not saved: ${(err as Error).message}`, "warn");
      // A refused edit (a secret, a limit) waits for the next change; only a lost connection is retried.
      refused = err instanceof GatewayError && err.status >= 400 && err.status < 500;
      retry = !refused;
    } finally {
      saving = false;
    }
    if (destroyed || timer) return;
    if (retry) timer = setTimeout(() => void flush(), 5_000);
    else if (!refused && Object.keys(pending).length) timer = setTimeout(() => void flush(), SAVE_AFTER_MS);
  }

  /** A save was refused because the page changed elsewhere: take theirs, put my edits on top, and save that. */
  async function merge(mine: typeof pending): Promise<void> {
    pending = {};
    merges += 1;
    if (merges > 3) {
      merges = 0;
      say("Changed elsewhere; reloaded.", "warn");
      await open(false);
      return;
    }
    try {
      const fresh = await pagesApi.get(ctx.client, pageId);
      if (destroyed) return;
      const where = editor?.caretAt() ?? null;
      const blocks = mine.blocks ? mergeBlocks(base, editor?.blocks() ?? mine.blocks, fresh.blocks) : fresh.blocks;
      page = { ...fresh, blocks };
      version = fresh.version;
      base = fresh.blocks;
      drawBar(page);
      drawDoc(page);
      if (where) editor?.focusBlock(where.blockId, where.offset);
      const again: typeof pending = {};
      if (mine.blocks) again.blocks = blocks;
      if (mine.title !== undefined) again.title = mine.title;
      if (mine.icon !== undefined) again.icon = mine.icon;
      if (Object.keys(again).length) schedule(again);
      say("Merged with changes made elsewhere.", "warn");
    } catch (err) {
      say(`Not saved: ${(err as Error).message}`, "warn");
    }
  }

  /** Pick up a version made elsewhere while nothing here is waiting to save. */
  async function check(): Promise<void> {
    if (watching || destroyed || !page || saving || timer || Object.keys(pending).length) return;
    if (globalThis.document?.hidden) return;
    watching = true;
    try {
      const now = await pagesApi.version(ctx.client, pageId);
      if (destroyed || now.version <= version || saving || timer || Object.keys(pending).length) return;
      if (now.archivedAt) return void open(false);
      const fresh = await pagesApi.get(ctx.client, pageId);
      if (destroyed || saving || timer || Object.keys(pending).length) return;
      const titleFocused = globalThis.document?.activeElement?.classList?.contains("ws-title");
      if (titleFocused) return;
      const where = editor?.caretAt() ?? null;
      page = fresh;
      version = fresh.version;
      base = fresh.blocks;
      drawBar(fresh);
      drawDoc(fresh);
      if (where) editor?.focusBlock(where.blockId, where.offset);
      say("Updated", "ok");
    } catch {
      // A missed check is fine; the next one tries again.
    } finally {
      watching = false;
    }
  }

  function drawBar(p: Page): void {
    const crumbs = el("nav", "ws-crumbs");
    crumbs.setAttribute("aria-label", "Where this page is");
    const link = (label: string, href: string) => {
      const a = el("a", "ws-crumb", label);
      a.href = href;
      return a;
    };
    crumbs.append(link("Home", "#/command/home"));
    for (const up of p.path) crumbs.append(el("span", "ws-crumb-sep", "/"), link(`${up.icon ? `${up.icon} ` : ""}${up.title}`, `#/command/page/${encodeURIComponent(up.id)}`));
    crumbs.append(el("span", "ws-crumb-sep", "/"), el("span", "ws-crumb ws-crumb-here", `${p.icon ? `${p.icon} ` : ""}${p.title}`));
    const actions = el("div", "ws-top-actions");
    const ask = el("button", "ws-top-btn ws-ask", "✨ Ask AI");
    ask.type = "button";
    ask.title = "Ask the AI about this page (Ctrl J)";
    ask.addEventListener("click", () => void flush().then(() => workspaceBus.askAi(p.id)));
    const sub = el("button", "ws-top-btn", "+ Page inside");
    sub.type = "button";
    sub.addEventListener("click", () => void pagesApi.create(ctx.client, { parentId: p.id }).then((child) => {
      workspaceBus.pagesChanged();
      ctx.navigate({ name: "command", tab: "page", pageId: child.id });
    }).catch((err: Error) => say(err.message, "warn")));
    const trash = el("button", "ws-top-btn", "Move to trash");
    trash.type = "button";
    trash.addEventListener("click", () => void flush().then(() => pagesApi.archive(ctx.client, p.id)).then(() => {
      workspaceBus.pagesChanged();
      ctx.navigate(p.parentId ? { name: "command", tab: "page", pageId: p.parentId } : { name: "command", tab: "home" });
    }).catch((err: Error) => say(err.message, "warn")));
    actions.append(status, ask, sub, trash);
    setChildren(bar, crumbs, actions);
  }

  function drawDoc(p: Page): void {
    editor?.destroy();
    const head = el("div", "ws-head");
    const iconButton = el("button", "ws-icon", p.icon || "📄");
    iconButton.type = "button";
    iconButton.setAttribute("aria-label", "Change the page's icon");
    const iconChoices = el("div", "ws-icon-choices");
    iconChoices.hidden = true;
    for (const choice of ["", ...ICONS]) {
      const b = el("button", "ws-icon-choice", choice || "∅");
      b.type = "button";
      b.setAttribute("aria-label", choice ? `Icon ${choice}` : "No icon");
      b.addEventListener("click", () => {
        iconChoices.hidden = true;
        iconButton.textContent = choice || "📄";
        schedule({ icon: choice });
      });
      iconChoices.append(b);
    }
    iconButton.addEventListener("click", () => { iconChoices.hidden = !iconChoices.hidden; });
    const title = el("input", "ws-title");
    title.value = p.title === "Untitled" ? "" : p.title;
    title.placeholder = "Untitled";
    title.setAttribute("aria-label", "Page title");
    title.addEventListener("input", () => {
      const text = String(title.value ?? "");
      if (looksSecret(text)) return say("Leave passwords and codes out of pages; the vault keeps them.", "warn");
      schedule({ title: text.trim() || "Untitled" });
      const here = bar.querySelector?.(".ws-crumb-here");
      if (here) here.textContent = `${iconButton.textContent && iconButton.textContent !== "📄" ? `${iconButton.textContent} ` : ""}${text.trim() || "Untitled"}`;
    });
    title.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter") {
        e.preventDefault();
        editor?.focusStart();
      }
    });
    head.append(iconButton, iconChoices, title, el("p", "ws-meta", `Edited ${relativeTime(p.updatedAt)}`));
    editor = createEditor(ctx, p.id, p.blocks, (blocks) => {
      if (page) page = { ...page, blocks };
      // A table still being made (no id yet) is saved once the gateway has made it.
      schedule({ blocks: blocks.filter((b) => b.type !== "table" || b.tableId !== "") });
    }, { askAi: () => void flush().then(() => workspaceBus.askAi(p.id)) });
    const extras = el("div", "ws-extras");
    if (p.children.length) {
      const box = el("section", "ws-related");
      box.append(el("h2", "ws-related-title", "Pages inside"));
      for (const child of p.children) {
        const a = el("a", "ws-related-row");
        a.href = `#/command/page/${encodeURIComponent(child.id)}`;
        a.append(el("span", undefined, child.icon || "📄"), el("span", "ws-related-name", child.title), el("span", "ws-related-meta", relativeTime(child.updatedAt)));
        box.append(a);
      }
      extras.append(box);
    }
    if (p.backlinks.length) {
      const box = el("section", "ws-related");
      box.append(el("h2", "ws-related-title", `Mentioned in ${p.backlinks.length} page${p.backlinks.length === 1 ? "" : "s"}`));
      for (const back of p.backlinks) {
        const a = el("a", "ws-related-row");
        a.href = `#/command/page/${encodeURIComponent(back.id)}`;
        a.append(el("span", undefined, back.icon || "📄"), el("span", "ws-related-name", back.title), el("span", "ws-related-meta", relativeTime(back.updatedAt)));
        box.append(a);
      }
      extras.append(box);
    }
    setChildren(doc, head, editor.element, extras);
    if (!p.blocks.length) queueMicrotask(() => (title.value ? editor?.focusStart() : title.focus?.()));
  }

  async function open(first: boolean): Promise<void> {
    try {
      const loaded = await pagesApi.get(ctx.client, pageId);
      if (destroyed) return;
      page = loaded;
      version = loaded.version;
      base = loaded.blocks;
      if (loaded.archivedAt) {
        setChildren(doc, emptyState({ icon: "book", title: "This page is in the trash", body: "Restore it from the trash to edit it." }));
        setChildren(bar);
        return;
      }
      drawBar(loaded);
      drawDoc(loaded);
      if (first) say("");
    } catch (err) {
      if (destroyed) return;
      setChildren(doc, emptyState({ icon: "alert", tone: "danger", title: "This page could not open", body: (err as Error).message }));
    }
  }

  void open(true);
  const watch = globalThis.setInterval?.(() => void check(), WATCH_MS);
  (watch as { unref?: () => void } | undefined)?.unref?.();
  const unlisten = workspaceBus.onPageChanged((id) => { if (id === pageId) void check(); });
  return {
    element,
    destroy() {
      destroyed = true;
      if (watch !== undefined) globalThis.clearInterval?.(watch);
      unlisten();
      if (Object.keys(pending).length) void flush();
      editor?.destroy();
    },
  };
}
