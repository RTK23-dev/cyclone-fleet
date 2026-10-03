/** The trash (plan 33, C5): pages moved here with their sub-pages. Restore them, or delete them for good. */
import type { GlassContext } from "../app.js";
import { pagesApi } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, emptyState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import type { GlassPage } from "../pages/page.js";
import { workspaceBus } from "./directory.js";

export function createTrashPage(ctx: GlassContext): GlassPage {
  const element = el("div", "page ws-page-screen");
  const doc = el("div", "ws-doc");
  const list = el("div", "ws-list");
  const note = el("p", "ws-note");
  doc.append(el("h1", "ws-title-static", "Trash"), el("p", "ws-lead", "Pages you moved here, with the pages inside them. Nothing else is deleted."), note, list);
  element.append(doc);
  let destroyed = false;

  async function load(): Promise<void> {
    const pages = await pagesApi.trash(ctx.client).catch((err: Error) => {
      note.textContent = err.message;
      return [];
    });
    if (destroyed) return;
    if (!pages.length) {
      setChildren(list, emptyState({ icon: "book", title: "The trash is empty", body: "Pages you move to the trash wait here until you restore or delete them." }));
      return;
    }
    setChildren(list, ...pages.map((p) => {
      const row = el("div", "ws-list-row");
      const restore = actionButton("Restore", { variant: "secondary" });
      restore.addEventListener("click", () => void pagesApi.restore(ctx.client, p.id).then(() => {
        workspaceBus.pagesChanged();
        ctx.navigate({ name: "command", tab: "page", pageId: p.id });
      }).catch((err: Error) => { note.textContent = err.message; }));
      const remove = actionButton("Delete for good", { variant: "ghost" });
      remove.addEventListener("click", () => {
        if (typeof globalThis.confirm === "function" && !globalThis.confirm(`Delete “${p.title}” and the pages inside it for good?`)) return;
        void pagesApi.remove(ctx.client, p.id).then(() => load()).catch((err: Error) => { note.textContent = err.message; });
      });
      row.append(el("span", "ws-list-icon", p.icon || "📄"), el("span", "ws-list-title", p.title), el("span", "ws-list-meta", `Moved ${relativeTime(p.archivedAt)}`), restore, remove);
      return row;
    }));
  }
  void load();
  return { element, destroy() { destroyed = true; } };
}
