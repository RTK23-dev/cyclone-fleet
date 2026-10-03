import { deviceOperatorHealth } from "../core/operatorHealth.js";
import type { DesktopDevice } from "../services/types.js";
import { el } from "./dom.js";

/** Compact, content-free health evidence for a single phone. */
export function createDeviceHealthPanel(device: DesktopDevice, compact = false): HTMLElement {
  const health = deviceOperatorHealth(device);
  const panel = el("section", compact ? "device-health-panel compact" : "device-health-panel");
  panel.setAttribute("aria-label", `${device.name} connection health`);
  panel.append(el("div", "device-health-heading", "Connection health"));
  const grid = el("div", "device-health-grid");
  for (const entry of health) {
    const item = el("div", `device-health-item state-${entry.state.toLowerCase()}`);
    item.title = entry.detail;
    item.append(
      el("span", "device-health-dot"),
      el("span", "device-health-label", entry.label),
      el("span", "device-health-state", displayState(entry.state)),
    );
    grid.append(item);
  }
  panel.append(grid);
  return panel;
}

function displayState(state: string): string {
  return state === "ACTION_REQUIRED" ? "Action needed" : state[0] + state.slice(1).toLowerCase();
}
