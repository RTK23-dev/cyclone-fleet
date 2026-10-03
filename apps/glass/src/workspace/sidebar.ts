/**
 * The Command Center's sidebar (plan 33, C5), Notion-like: search, Home and Inbox, the workspace's databases, the
 * page tree (open/close, add a sub-page, rename, drag a page onto another to nest it, trash), and the trash.
 */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import { command } from "../services/command.js";
import { buildTree, pagesApi, type PageMeta, type TreeNode } from "../services/pages.js";
import { el, setChildren } from "../ui/dom.js";
import { icon, type IconName } from "../ui/icons.js";
import { workspaceBus } from "./directory.js";
import { openQuickFind } from "./quickFind.js";

const DATABASES: Array<{ tab: "tasks" | "routines" | "results" | "accounts" | "connections" | "ports" | "vault"; label: string; icon: IconName }> = [
  { tab: "tasks", label: "Tasks", icon: "runs" },
  { tab: "routines", label: "Routines", icon: "clock" },
  { tab: "results", label: "Results", icon: "star" },
  { tab: "accounts", label: "Accounts", icon: "user" },
  { tab: "connections", label: "Connections", icon: "plug" },
  { tab: "ports", label: "Ports", icon: "port" },
  { tab: "vault", label: "Vault", icon: "lock" },
];
const REFRESH_MS = 15_000;

export interface WorkspaceSidebar {
  element: HTMLElement;
  setRoute(route: Route): void;
  /** Ctrl/⌘ K. */
  find(): void;
  destroy(): void;
}

export function createWorkspaceSidebar(context: () => GlassContext, brand: HTMLElement, host: HTMLElement): WorkspaceSidebar {
  const ctx = new Proxy({} as GlassContext, { get: (_t, key) => context()[key as keyof GlassContext] });
  const element = el("aside", "glass-sidebar ws-sidebar");
  const nav = el("nav", "ws-nav");
  const tree = el("div", "ws-tree");
  tree.setAttribute("role", "tree");
  tree.setAttribute("aria-label", "Pages");
  const bottom = el("nav", "ws-nav ws-nav-bottom");
  let route: Route = { name: "command", tab: "home" };
  let pages: PageMeta[] = [];
  let approvals = 0;
  const open = new Set<string>();
  let renaming: string | null = null;
  let menuFor: string | null = null;
  let dragging: string | null = null;
  let destroyed = false;

  const find = () => openQuickFind(ctx, host);
  const search = el("button", "ws-search");
  search.type = "button";
  search.append(icon("search"), el("span", "ws-search-label", "Search"), el("kbd", "ws-kbd", "Ctrl K"));
  search.addEventListener("click", find);
  const askAi = el("button", "ws-search ws-ask-ai");
  askAi.type = "button";
  askAi.append(el("span", "ws-spark", "✨"), el("span", "ws-search-label", "Ask AI"), el("kbd", "ws-kbd", "Ctrl J"));
  askAi.addEventListener("click", () => workspaceBus.askAi(route.name === "command" && route.tab === "page" ? route.pageId ?? null : null));

  const item = (label: string, name: IconName, target: Route, key: string, badge?: number): HTMLAnchorElement => {
    const a = el("a", "ws-item");
    a.href = routeHrefOf(target);
    a.dataset.key = key;
    a.append(icon(name), el("span", "ws-item-label", label));
    if (badge) a.append(el("span", "ws-badge", String(badge)));
    return a;
  };

  function drawNav(): void {
    setChildren(nav,
      item("Home", "home", { name: "command", tab: "home" }, "home"),
      item("Inbox", "bell", { name: "command", tab: "approvals" }, "approvals", approvals),
      el("div", "ws-heading", "Workspace"),
      ...DATABASES.map((d) => item(d.label, d.icon, { name: "command", tab: d.tab }, d.tab)),
      item("AI", "chat", { name: "command", tab: "ai" }, "ai"));
    markActive();
  }

  async function newPage(parentId: string | null): Promise<void> {
    try {
      const page = await pagesApi.create(ctx.client, { parentId });
      if (parentId) open.add(parentId);
      workspaceBus.pagesChanged();
      ctx.navigate({ name: "command", tab: "page", pageId: page.id });
    } catch (err) {
      host.append(toast((err as Error).message));
    }
  }

  const pagesHead = el("div", "ws-heading ws-heading-row");
  const addTop = el("button", "ws-mini");
  addTop.type = "button";
  addTop.setAttribute("aria-label", "New page");
  addTop.title = "New page";
  addTop.textContent = "+";
  addTop.addEventListener("click", () => void newPage(null));
  pagesHead.append(el("span", undefined, "Pages"), addTop);
  // Dropping a page on the "Pages" heading moves it to the top level.
  pagesHead.addEventListener("dragover", (e: DragEvent) => { if (dragging) e.preventDefault(); });
  pagesHead.addEventListener("drop", (e: DragEvent) => {
    e.preventDefault();
    if (dragging) void movePage(dragging, null);
  });

  async function movePage(id: string, parentId: string | null): Promise<void> {
    dragging = null;
    try {
      await pagesApi.move(ctx.client, id, parentId);
      if (parentId) open.add(parentId);
      workspaceBus.pagesChanged();
    } catch (err) {
      host.append(toast((err as Error).message));
    }
  }

  function row(node: TreeNode, depth: number): HTMLElement[] {
    const page = node.page;
    const wrap = el("div", "ws-page");
    wrap.setAttribute("role", "treeitem");
    wrap.dataset.pageId = page.id;
    wrap.style.setProperty("--depth", String(depth));
    wrap.draggable = true;
    const caret = el("button", "ws-caret");
    caret.type = "button";
    const expanded = open.has(page.id);
    caret.textContent = node.children.length ? (expanded ? "▾" : "▸") : "•";
    caret.setAttribute("aria-label", expanded ? `Close ${page.title}` : `Open ${page.title}`);
    if (node.children.length) wrap.setAttribute("aria-expanded", String(expanded));
    caret.addEventListener("click", () => {
      if (open.has(page.id)) open.delete(page.id);
      else open.add(page.id);
      drawTree();
    });
    wrap.append(caret);
    if (renaming === page.id) {
      const input = el("input", "ws-rename");
      input.value = page.title;
      input.setAttribute("aria-label", "Page name");
      const finish = (save: boolean) => {
        if (renaming !== page.id) return;
        renaming = null;
        const title = String(input.value ?? "").trim();
        if (save && title && title !== page.title) {
          void pagesApi.get(ctx.client, page.id).then((full) => pagesApi.save(ctx.client, page.id, { version: full.version, title }))
            .then(() => workspaceBus.pagesChanged()).catch((err: Error) => host.append(toast(err.message)));
        } else drawTree();
      };
      input.addEventListener("keydown", (e: KeyboardEvent) => {
        if (e.key === "Enter") finish(true);
        if (e.key === "Escape") finish(false);
      });
      input.addEventListener("blur", () => finish(true));
      wrap.append(input);
      queueMicrotask(() => input.focus?.());
    } else {
      const link = el("a", "ws-page-link");
      link.href = routeHrefOf({ name: "command", tab: "page", pageId: page.id });
      link.append(el("span", "ws-page-icon", page.icon || "📄"), el("span", "ws-page-title", page.title || "Untitled"));
      link.addEventListener("dblclick", () => {
        renaming = page.id;
        drawTree();
      });
      wrap.append(link);
    }
    const more = el("button", "ws-mini ws-page-more");
    more.type = "button";
    more.textContent = "⋯";
    more.setAttribute("aria-label", `More for ${page.title}`);
    more.addEventListener("click", () => {
      menuFor = menuFor === page.id ? null : page.id;
      drawTree();
    });
    const add = el("button", "ws-mini ws-page-add");
    add.type = "button";
    add.textContent = "+";
    add.setAttribute("aria-label", `Add a page inside ${page.title}`);
    add.addEventListener("click", () => void newPage(page.id));
    wrap.append(more, add);
    wrap.addEventListener("dragstart", (e: DragEvent) => {
      dragging = page.id;
      e.dataTransfer?.setData("text/plain", page.id);
    });
    wrap.addEventListener("dragover", (e: DragEvent) => {
      if (dragging && dragging !== page.id) {
        e.preventDefault();
        wrap.classList.add("ws-drop");
      }
    });
    wrap.addEventListener("dragleave", () => wrap.classList.remove("ws-drop"));
    wrap.addEventListener("drop", (e: DragEvent) => {
      e.preventDefault();
      wrap.classList.remove("ws-drop");
      if (dragging && dragging !== page.id) void movePage(dragging, page.id);
    });
    const out: HTMLElement[] = [wrap];
    if (menuFor === page.id) out.push(pageMenu(page, depth));
    if (expanded) for (const child of node.children) out.push(...row(child, depth + 1));
    return out;
  }

  function pageMenu(page: PageMeta, depth: number): HTMLElement {
    const menu = el("div", "ws-menu");
    menu.style.setProperty("--depth", String(depth));
    const entry = (label: string, fn: () => void) => {
      const b = el("button", "ws-menu-item", label);
      b.type = "button";
      b.addEventListener("click", () => {
        menuFor = null;
        fn();
      });
      menu.append(b);
    };
    entry("Rename", () => {
      renaming = page.id;
      drawTree();
    });
    entry("Add a page inside", () => void newPage(page.id));
    if (page.parentId) entry("Move to the top level", () => void movePage(page.id, null));
    entry("Move to trash", () => void pagesApi.archive(ctx.client, page.id).then(() => {
      workspaceBus.pagesChanged();
      if (route.name === "command" && route.tab === "page" && route.pageId === page.id) ctx.navigate({ name: "command", tab: "home" });
    }).catch((err: Error) => host.append(toast(err.message))));
    return menu;
  }

  function drawTree(): void {
    const nodes = buildTree(pages);
    if (!nodes.length) {
      const empty = el("button", "ws-item ws-empty-pages");
      empty.type = "button";
      empty.append(el("span", "ws-item-label", "+ Add your first page"));
      empty.addEventListener("click", () => void newPage(null));
      setChildren(tree, empty);
    } else {
      setChildren(tree, ...nodes.flatMap((n) => row(n, 0)));
    }
    markActive();
  }

  function markActive(): void {
    const key = route.name === "command" ? (route.tab === "page" ? `page:${route.pageId}` : route.tab) : "";
    for (const node of [...Array.from(nav.querySelectorAll?.("a") ?? []), ...Array.from(bottom.querySelectorAll?.("a") ?? [])] as HTMLElement[]) {
      const on = node.dataset.key === key;
      node.classList.toggle("active", on);
      if (on) node.setAttribute("aria-current", "page");
      else node.removeAttribute("aria-current");
    }
    for (const node of Array.from(tree.querySelectorAll?.(".ws-page") ?? []) as HTMLElement[]) {
      node.classList.toggle("active", `page:${node.dataset.pageId}` === key);
    }
  }

  async function load(): Promise<void> {
    const [listed, waiting] = await Promise.all([pagesApi.tree(ctx.client).catch(() => pages), command.approvals(ctx.client).then((a) => a.length).catch(() => approvals)]);
    if (destroyed) return;
    pages = listed;
    // Keep the path to the open page expanded.
    const here = route.name === "command" && route.tab === "page" ? route.pageId : undefined;
    if (here) {
      let walk = pages.find((p) => p.id === here)?.parentId ?? null;
      while (walk) {
        open.add(walk);
        walk = pages.find((p) => p.id === walk)?.parentId ?? null;
      }
    }
    const badge = waiting !== approvals;
    approvals = waiting;
    if (badge) drawNav();
    if (!renaming) drawTree();
  }

  const newButton = el("button", "ws-item ws-new-page");
  newButton.type = "button";
  newButton.append(el("span", "ws-plus", "+"), el("span", "ws-item-label", "New page"));
  newButton.addEventListener("click", () => void newPage(null));
  setChildren(bottom, item("Trash", "close", { name: "command", tab: "trash" }, "trash"));
  const scroll = el("div", "ws-scroll");
  scroll.append(nav, pagesHead, tree, newButton);
  element.append(brand, search, askAi, scroll, el("div", "sidebar-spacer"), bottom);
  drawNav();
  drawTree();
  void load();
  const timer = setInterval(() => void load(), REFRESH_MS);
  const unlisten = workspaceBus.onPagesChanged(() => void load());

  return {
    element,
    setRoute(next) {
      route = next;
      if (next.name === "command" && next.tab === "page") void load();
      markActive();
    },
    find,
    destroy() {
      destroyed = true;
      clearInterval(timer);
      unlisten();
    },
  };
}

function routeHrefOf(route: Route): string {
  if (route.name === "command" && route.tab === "page" && route.pageId) return `#/command/page/${encodeURIComponent(route.pageId)}`;
  return route.name === "command" ? `#/command/${route.tab}` : "#/home";
}

export function toast(text: string): HTMLElement {
  const node = el("div", "ws-toast", text);
  node.setAttribute("role", "status");
  setTimeout(() => node.remove(), 5_000);
  return node;
}
