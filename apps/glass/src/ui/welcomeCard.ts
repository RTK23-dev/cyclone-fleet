/** Plan 31: the small "how to run and stop" card. Shown once on the first Glass open; the sidebar's ? shows it again. */
import { RUN_STOP_CARD } from "../services/pc.js";
import { el } from "./dom.js";
import { actionButton, iconButton, keyValue } from "./components.js";

export function createWelcomeCard(onClose: () => void): HTMLElement {
  const backdrop = el("div", "welcome-backdrop");
  const dialog = el("section", "welcome-card");
  dialog.setAttribute("role", "dialog");
  dialog.setAttribute("aria-modal", "true");
  dialog.setAttribute("aria-label", RUN_STOP_CARD.title);
  const head = el("div", "welcome-head");
  const close = iconButton("close", "Close", "btn btn-ghost welcome-close");
  close.addEventListener("click", onClose);
  head.append(el("h2", "welcome-title", RUN_STOP_CARD.title), close);
  const rows = keyValue(RUN_STOP_CARD.rows.map(([label, text]) => {
    const value = el("span", undefined, text);
    return [label, value] as [string, HTMLElement];
  }));
  const done = actionButton("Got it", { variant: "primary" });
  done.addEventListener("click", onClose);
  dialog.append(head, rows, done);
  backdrop.append(dialog);
  backdrop.addEventListener("keydown", (event) => {
    if ((event as KeyboardEvent).key === "Escape") onClose();
  });
  return backdrop;
}
