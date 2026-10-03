export type KeyboardCommand =
  | { type: "stop" }
  | { type: "text"; text: string }
  | { type: "key"; key: "ENTER" | "BACKSPACE" | "TAB" }
  | { type: "consume" }
  | { type: "ignore" };

/** Stateless on purpose: typed content is returned to the caller and never retained. */
export function keyboardCommandForEvent(event: {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
}): KeyboardCommand {
  if (event.key === "Escape") return { type: "stop" };
  if (event.ctrlKey || event.metaKey || event.altKey) return { type: "consume" };
  if (event.key === "Enter") return { type: "key", key: "ENTER" };
  if (event.key === "Backspace") return { type: "key", key: "BACKSPACE" };
  if (event.key === "Tab") return { type: "key", key: "TAB" };
  if (event.key.length === 1) return { type: "text", text: event.key };
  return { type: "ignore" };
}
