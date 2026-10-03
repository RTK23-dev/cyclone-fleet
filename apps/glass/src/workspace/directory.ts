/**
 * The workspace's shared pieces: a change bus (so the sidebar redraws when a page is created or renamed), the
 * directory of things a page can mention with @, and where each kind of reference opens.
 */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import { command } from "../services/command.js";
import { pagesApi, type DirectoryEntry, type PageMeta, type Ref } from "../services/pages.js";
import { loadSkills } from "../services/skills.js";
import { deviceReadiness } from "../services/devices.js";

type Listener = () => void;
const listeners = new Set<Listener>();
const pageListeners = new Set<(pageId: string) => void>();
let askAiHandler: ((pageId: string | null) => void) | null = null;

export const workspaceBus = {
  /** A page changed outside its editor (the AI applied an edit); an open page checks for the new version. */
  onPageChanged(fn: (pageId: string) => void): () => void {
    pageListeners.add(fn);
    return () => pageListeners.delete(fn);
  },
  pageChanged(pageId: string): void {
    for (const fn of [...pageListeners]) fn(pageId);
  },
  /** Opens the AI panel (the app owns it), about one page or the whole workspace. */
  handleAskAi(fn: ((pageId: string | null) => void) | null): void {
    askAiHandler = fn;
  },
  askAi(pageId: string | null = null): void {
    askAiHandler?.(pageId);
  },
  onPagesChanged(fn: Listener): () => void {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
  pagesChanged(): void {
    for (const fn of [...listeners]) fn();
    cache = null;
  },
};

let cache: { at: number; key: string; entries: DirectoryEntry[] } | null = null;

/** Everything a page can reference: pages, phones, the chosen phone's skills, routines, tasks, accounts, connections. */
export async function loadDirectory(ctx: GlassContext, now = Date.now()): Promise<DirectoryEntry[]> {
  const key = ctx.devices.map((d) => d.id).join(",") + `|${ctx.device?.id ?? ""}`;
  if (cache && cache.key === key && now - cache.at < 15_000) return cache.entries;
  const settle = <T>(p: Promise<T>, fallback: T): Promise<T> => p.catch(() => fallback);
  const device = ctx.device && deviceReadiness(ctx.device).ready ? ctx.device : null;
  const [pages, routines, tasks, accounts, connections, skills] = await Promise.all([
    settle(pagesApi.tree(ctx.client), [] as PageMeta[]),
    settle(command.routines(ctx.client), []),
    settle(command.tasks(ctx.client), []),
    settle(command.accounts(ctx.client), []),
    settle(command.connections(ctx.client).then((r) => r.connections), []),
    device ? settle(loadSkills(ctx.client, device.id).then((r) => r.skills), []) : Promise.resolve([]),
  ]);
  const entries: DirectoryEntry[] = [
    ...pages.map((p) => ({ ref: { kind: "page" as const, id: p.id, label: p.title || "Untitled" }, detail: p.parentId ? "Inside another page" : "" })),
    ...ctx.devices.map((d) => ({ ref: { kind: "device" as const, id: d.id, label: d.name }, detail: deviceReadiness(d).ready ? `Ready · Cyclone ${d.mobileVersion}` : "Not ready" })),
    ...skills.map((s) => ({ ref: { kind: "skill" as const, id: s.skillId, label: s.name, ...(device ? { deviceId: device.id } : {}) }, detail: `Skill on ${device?.name ?? "the phone"}` })),
    ...routines.map((r) => ({ ref: { kind: "routine" as const, id: r.id, label: r.title }, detail: r.scheduleLabel || "Routine" })),
    ...tasks.slice(0, 60).map((t) => ({ ref: { kind: "task" as const, id: t.id, label: t.title }, detail: `Task · ${t.status.replace("_", " ")}` })),
    ...accounts.map((a) => ({ ref: { kind: "account" as const, id: a.id, label: a.handle }, detail: a.service })),
    ...connections.map((c) => ({ ref: { kind: "connection" as const, id: c.id, label: c.name }, detail: c.kind === "api" ? "API" : c.kind === "local" ? "Program on this PC" : "MCP server" })),
  ];
  cache = { at: now, key, entries };
  return entries;
}

/** Where a reference opens. Phones and skills live on the Glass side; everything else in the Command Center. */
export function routeOfRef(ref: Ref): Route {
  switch (ref.kind) {
    case "page":
      return { name: "command", tab: "page", pageId: ref.id };
    case "device":
      return { name: "devices" };
    case "skill":
      return { name: "knowledge" };
    case "routine":
      return { name: "command", tab: "routines" };
    case "task":
      return { name: "command", tab: "tasks" };
    case "account":
      return { name: "command", tab: "accounts" };
    case "connection":
      return { name: "command", tab: "connections" };
  }
}
