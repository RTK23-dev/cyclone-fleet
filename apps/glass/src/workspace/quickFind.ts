/**
 * Quick find (Ctrl/⌘ K): pages by title and text, and every phone, skill, routine, task, account and connection the
 * workspace knows. Enter opens the highlighted one.
 */
import type { GlassContext } from "../app.js";
import { KIND_ICON, KIND_LABEL, pagesApi, searchDirectory, type DirectoryEntry } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { loadDirectory, routeOfRef } from "./directory.js";

interface Hit {
  icon: string;
  title: string;
  detail: string;
  open(): void;
}

export function openQuickFind(ctx: GlassContext, host: HTMLElement): { element: HTMLElement; close(): void } {
  const overlay = el("div", "ws-overlay");
  const box = el("div", "ws-find");
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-label", "Search the workspace");
  const input = el("input", "ws-find-input");
  input.placeholder = "Search pages, phones, skills, routines…";
  input.setAttribute("aria-label", "Search the workspace");
  const results = el("div", "ws-find-results");
  box.append(input, results);
  overlay.append(box);
  host.append(overlay);
  let hits: Hit[] = [];
  let active = 0;
  let directory: DirectoryEntry[] = [];
  let seq = 0;

  const close = () => overlay.remove();
  const draw = () => {
    if (!hits.length) {
      setChildren(results, el("p", "ws-find-empty", String(input.value ?? "").trim() ? "Nothing found." : "Type to search."));
      return;
    }
    setChildren(results, ...hits.map((hit, i) => {
      const row = el("button", `ws-find-row${i === active ? " active" : ""}`);
      row.type = "button";
      row.append(el("span", "ws-find-icon", hit.icon), el("span", "ws-find-title", hit.title), el("span", "ws-find-detail", hit.detail));
      row.addEventListener("click", () => {
        close();
        hit.open();
      });
      return row;
    }));
  };
  const search = async () => {
    const q = String(input.value ?? "").trim();
    const mine = (seq += 1);
    const local = searchDirectory(directory.filter((e) => e.ref.kind !== "page"), q, 8).map((e): Hit => ({
      icon: KIND_ICON[e.ref.kind], title: e.ref.label, detail: e.detail ? `${KIND_LABEL[e.ref.kind]} · ${e.detail}` : KIND_LABEL[e.ref.kind], open: () => ctx.navigate(routeOfRef(e.ref)),
    }));
    let pages: Hit[] = [];
    if (q) {
      const found = await pagesApi.search(ctx.client, q).catch(() => []);
      pages = found.map((p) => ({ icon: p.icon || "📄", title: p.title, detail: p.snippet ? `…${p.snippet}…` : "Page",
        open: () => ctx.navigate({ name: "command", tab: "page", pageId: p.id }) }));
    } else {
      pages = directory.filter((e) => e.ref.kind === "page").slice(0, 8).map((e) => ({ icon: KIND_ICON.page, title: e.ref.label, detail: "Page",
        open: () => ctx.navigate(routeOfRef(e.ref)) }));
    }
    if (mine !== seq) return;
    hits = [...pages, ...local].slice(0, 14);
    active = 0;
    draw();
  };
  input.addEventListener("input", () => void search());
  input.addEventListener("keydown", (event: KeyboardEvent) => {
    if (event.key === "Escape") close();
    else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (hits.length) active = (active + (event.key === "ArrowDown" ? 1 : hits.length - 1)) % hits.length;
      draw();
    } else if (event.key === "Enter" && hits[active]) {
      event.preventDefault();
      const hit = hits[active];
      close();
      hit.open();
    }
  });
  overlay.addEventListener("click", (event: Event) => {
    if (event.target === overlay) close();
  });
  void loadDirectory(ctx).then((entries) => {
    directory = entries;
    void search();
  });
  draw();
  input.focus?.();
  return { element: overlay, close };
}
