/**
 * The connection line under a phone (alpha 88): the gateway's one answer for what's wrong with the link — the first
 * broken piece, in plain words — and at most one thing to do. When Cyclone is already fixing it (reconnecting,
 * restarting the app, waiting for Allow), it shows progress instead of a button. A healthy phone shows nothing here.
 */
import type { ConnectionActionKind, ConnectionVerdict } from "../services/devices.js";
import { actionButton } from "../ui/components.js";
import { el } from "../ui/dom.js";

export function connectionLine(verdict: ConnectionVerdict | null, onAction: (kind: ConnectionActionKind) => Promise<void> | void): HTMLElement | null {
  if (!verdict || verdict.ok) return null;
  const line = el("div", verdict.working ? "conn conn-working" : "conn conn-attention");
  line.setAttribute("role", "status");
  line.dataset.code = verdict.code;
  const dot = el("span", verdict.working ? "conn-dot spinner" : "conn-dot");
  dot.setAttribute("aria-hidden", "true");
  const text = el("div", "conn-text");
  text.append(el("span", "conn-title", verdict.title));
  if (verdict.message) text.append(el("span", "conn-message", verdict.message));
  line.append(dot, text);
  if (verdict.action) {
    const action = verdict.action;
    const button = actionButton(action.label, { variant: verdict.working ? "ghost" : "primary" });
    button.addEventListener("click", async () => {
      button.disabled = true;
      try {
        await onAction(action.kind);
      } finally {
        button.disabled = false;
      }
    });
    line.append(button);
  }
  return line;
}
