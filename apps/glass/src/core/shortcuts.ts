/**
 * Keyboard shortcuts: `g` then a letter jumps to a page (like GitHub), `/` focuses the page's search box.
 * Keys typed into a field, or with a modifier held, are never taken.
 */
import type { Route } from "./router.js";

export const GO_KEYS: Record<string, Route> = {
  h: { name: "home" },
  d: { name: "devices" },
  a: { name: "apps" },
  r: { name: "runs" },
  k: { name: "knowledge" },
  p: { name: "phone" },
  s: { name: "settings" },
};

const CHORD_MS = 1_200;

export interface KeyLike {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
  target?: unknown;
}

export type ShortcutAction = { kind: "go"; route: Route } | { kind: "search" } | null;

export interface ShortcutState {
  pendingG: number | null;
}

export function isTyping(target: unknown): boolean {
  const node = target as { tagName?: string; isContentEditable?: boolean } | null;
  const tag = node?.tagName?.toUpperCase();
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || node?.isContentEditable === true;
}

/** Pure: what a key press means given the previous `g`. Mutates `state.pendingG`. */
export function shortcutFor(event: KeyLike, state: ShortcutState, now: number): ShortcutAction {
  if (event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target)) {
    state.pendingG = null;
    return null;
  }
  const key = event.key.toLowerCase();
  if (state.pendingG !== null && now - state.pendingG <= CHORD_MS) {
    state.pendingG = null;
    const route = GO_KEYS[key];
    return route ? { kind: "go", route } : null;
  }
  if (key === "g") {
    state.pendingG = now;
    return null;
  }
  state.pendingG = null;
  return event.key === "/" ? { kind: "search" } : null;
}
