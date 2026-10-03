import type { DesktopDevice } from "../services/types.js";
import { needsTrustRepair } from "../core/trustRecovery.js";
import { button, el } from "../ui/dom.js";

export interface HomePageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createHomePage(
  devices: DesktopDevice[],
  onControl: () => void,
  onAutomations: () => void,
  onConnections: () => void,
  onChatgpt?: () => void,
): HomePageHandle {
  const page = el("section", "page content-page home-page home-overview");
  const readyDevices = devices.filter((device) => device.state === "READY" && !needsTrustRepair(device));
  const attention = devices.filter((device) => needsTrustRepair(device) || ["ATTENTION", "UNAUTHORIZED", "DISCONNECTED"].includes(device.state));
  const featured = readyDevices[0] ?? devices[0] ?? null;

  const header = el("header", "overview-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "Overview"),
    el("p", "page-subtitle", "Your phones, tasks and AI connections in one place."),
  );
  const openControl = button(devices.length ? "Open phone" : "Connect a phone", "button primary overview-primary");
  openControl.addEventListener("click", onControl);
  header.append(heading, openControl);

  const stats = el("div", "overview-stats");
  stats.append(
    statCard("Phones", String(devices.length), devices.length ? "Connected to Cyclone One" : "No phone connected", devices.length > 0),
    statCard("Ready", String(readyDevices.length), readyDevices.length ? "Available for control" : "Waiting for a ready phone", readyDevices.length > 0),
    statCard("Attention", String(attention.length), attention.length ? "Something needs a quick check" : "Everything looks good", attention.length === 0),
  );

  const workspace = el("div", "overview-workspace");
  const phoneList = el("article", "overview-panel phone-list-panel");
  const listHead = el("div", "overview-panel-head");
  listHead.append(el("div", "overview-panel-title", "Phones"), el("span", "overview-count", String(devices.length)));
  phoneList.append(listHead);
  const list = el("div", "overview-phone-list");
  if (devices.length === 0) {
    const empty = el("div", "overview-empty");
    empty.append(
      el("div", "overview-empty-icon", "C"),
      el("div", "overview-empty-title", "No phone connected"),
      el("div", "overview-empty-copy", "Plug in your Android phone and Cyclone will find it automatically."),
    );
    const connect = button("Open Control", "button secondary compact");
    connect.addEventListener("click", onControl);
    empty.append(connect);
    list.append(empty);
  } else {
    for (const device of devices) list.append(phoneRow(device, featured?.id === device.id, onControl));
  }
  phoneList.append(list);

  const monitor = el("article", "overview-panel monitor-panel");
  const monitorHead = el("div", "overview-panel-head");
  monitorHead.append(
    el("div", "overview-panel-title", featured ? featured.name : "Live phone"),
    statusPill(featured ? humanDeviceState(featured) : "Offline", Boolean(featured && featured.state === "READY")),
  );
  monitor.append(monitorHead);
  const monitorBody = el("div", "overview-monitor-body");
  if (featured?.lastFrameUrl) {
    const image = document.createElement("img");
    image.className = "overview-phone-preview";
    image.src = featured.lastFrameUrl;
    image.alt = `${featured.name} live preview`;
    monitorBody.append(image);
  } else {
    const phone = el("div", "overview-phone-shell");
    phone.append(
      el("div", "overview-phone-speaker"),
      el("div", "overview-phone-screen"),
      el("div", "overview-phone-home"),
    );
    monitorBody.append(phone);
  }
  const monitorMeta = el("div", "overview-monitor-meta");
  monitorMeta.append(
    el("div", "overview-monitor-copy", featured ? `${featured.connectionLabel || humanDeviceState(featured)} · ${featured.source ?? "phone"}` : "Connect a phone to see live status here."),
  );
  const monitorAction = button(featured ? "Monitor & control" : "Connect phone", "button secondary compact");
  monitorAction.addEventListener("click", onControl);
  monitorMeta.append(monitorAction);
  monitor.append(monitorBody, monitorMeta);
  workspace.append(phoneList, monitor);

  const quick = el("div", "overview-quick-grid");
  quick.append(
    quickCard("Tasks", "Run foreground or background phone work without cluttering your dashboard.", "Open Tasks", onAutomations),
    quickCard("AI connections", "Connect cloud agents or Codex with a guided one-step setup.", "Manage connections", onConnections),
    quickCard("ChatGPT Attach", "Sync VMOS Cloud pads and copy a one-file ChatGPT handoff.", "Open ChatGPT Attach", onChatgpt ?? onConnections),
  );

  page.append(header, stats, workspace, quick);
  return { element: page, destroy: () => undefined };
}

function statCard(label: string, value: string, detail: string, positive: boolean): HTMLElement {
  const card = el("article", "overview-stat-card");
  card.append(
    el("div", "overview-stat-label", label),
    el("div", "overview-stat-value", value),
    el("div", `overview-stat-detail ${positive ? "positive" : "muted"}`, detail),
  );
  return card;
}

function phoneRow(device: DesktopDevice, selected: boolean, onControl: () => void): HTMLElement {
  const row = el("button", `overview-phone-row${selected ? " selected" : ""}`) as HTMLButtonElement;
  row.type = "button";
  row.addEventListener("click", onControl);
  row.append(
    el("span", `overview-status-dot ${device.state === "READY" ? "ready" : "attention"}`),
    el("span", "overview-phone-name", device.name),
    el("span", "overview-phone-state", humanDeviceState(device)),
  );
  return row;
}

function quickCard(title: string, copy: string, label: string, action: () => void): HTMLElement {
  const card = el("article", "overview-quick-card");
  const text = el("div");
  text.append(el("h2", "overview-quick-title", title), el("p", "overview-quick-copy", copy));
  const open = button(label, "button secondary compact");
  open.addEventListener("click", action);
  card.append(text, open);
  return card;
}

function statusPill(label: string, ready: boolean): HTMLElement {
  return el("span", `overview-status-pill ${ready ? "ready" : "neutral"}`, label);
}

function humanDeviceState(device: DesktopDevice): string {
  if (needsTrustRepair(device)) return "Needs attention";
  if (device.state === "READY") return "Ready";
  if (device.state === "UNPAIRED") return "Pair phone";
  if (device.state === "PAIRING") return "Pairing";
  if (device.state === "SLEEPING") return "Sleeping";
  if (device.state === "UNAUTHORIZED") return "USB approval needed";
  if (device.state === "DISCONNECTED") return "Offline";
  return "Needs attention";
}
