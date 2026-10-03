import {
  ASK_NEEDS_SECRET_FIXTURE,
  ASK_RUN_STATES,
  ASK_SAMPLE_SNAPSHOTS,
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  phoneSupportsGlassAtlas,
  type AskRunState,
  type GlassAskSnapshot,
} from "../core/fleet.js";
import {
  FOREGROUND_PLANE_LABEL,
  VD_PLANE_LABEL,
} from "../core/sessionTiles.js";
import { SECRET_PAYLOAD_REJECTED, type AskStatusView } from "../services/atlasClient.js";
import type { DesktopDevice } from "../services/types.js";
import { button, el } from "../ui/dom.js";
import { createSecretsCard } from "../ui/secretsCard.js";
import { formatAskHudLog, resolveAskLogSessionId } from "./askHudLog.js";

export { formatAskHudLog, redactAskHudLog, resolveAskLogSessionId } from "./askHudLog.js";

export interface AskPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface AskPageOptions {
  devices?: DesktopDevice[];
  mobileVersion?: string;
  /** Immediately render the needs-secret fixture (tests / demo, no phone required). */
  previewNeedsSecret?: boolean;
  /** Show sample snapshots, labeled (sample) — never as a live phone run. */
  previewSnapshots?: boolean;
  sessionId?: string;
  sessionPlane?: "foreground" | "session_kernel_vd";
  onOpenControl?: () => void;
  /** Real phone Ask. Absent in demo / no phone: Send stays off and says why. */
  ask?: AskOps;
  /** Test seam for the status poll timer. */
  askTimer?: { set(fn: () => void, ms: number): unknown; clear(handle: unknown): void };
}

export interface AskOps {
  askStart(goal: string): Promise<void>;
  askStatus(): Promise<AskStatusView>;
}

const LIVE_SEND_COPY = "Sends your sentence to the phone. The phone runs it; Glass mirrors the same progress.";
const ASK_POLL_MS = 1_000;

/** Phone snapshot → the HUD's shape. `idle` is no run. */
export function askSnapshotFromStatus(status: AskStatusView, sessionId: string): GlassAskSnapshot | null {
  if (status.state === "idle") return null;
  return {
    state: status.state,
    title: status.title || status.currentMilestone || "Phone task",
    supportingCopy: status.state === "done" || status.state === "failed"
      ? (status.outcomeCopy ?? status.supportingCopy ?? undefined)
      : (status.supportingCopy ?? undefined),
    slotLabel: status.state === "needs-secret" ? "password" : undefined,
    sessionId,
    milestones: status.milestones.map((milestone) => ({ label: milestone.label, state: milestone.state })),
  };
}

function askErrorCopy(code: string): string {
  switch (code) {
    case "ASK_BUSY":
      return "The phone is already running a task. Wait for it or stop it on the phone.";
    case "HUMAN_HAS_CONTROL":
      return "You have control of the phone. Give it back to Cyclone, then send.";
    case "OVERLAY_UNAVAILABLE":
      return "Turn on Cyclone's accessibility service on the phone, then send.";
    case "INVALID_REQUEST":
    case SECRET_PAYLOAD_REJECTED:
      return "Keep passwords out of the goal. Cyclone asks for them on the phone.";
    case "SESSION_DISPLAY_MISMATCH":
      return "Ask from Glass runs on the phone's main screen. Switch to the Foreground plane.";
    default:
      return `The phone didn't accept the goal${code ? ` (${code})` : ""}.`;
  }
}

const SAMPLE_DEFAULT_COPY = "Sample snapshot (no phone required)";
const SAMPLE_PREVIEW_COPY = "Sample snapshot — not a live phone run.";
const SAMPLE_BADGE = "(sample)";
const NO_RUN_YET = "No Ask run yet";
const SEND_OFF_COPY = "Send stays off until the phone Ask transport is connected.";
const HUD_LOG_FILENAME = "ask-hud-log.txt";

let askCssLinked = false;

function ensureAskCss(): void {
  if (askCssLinked) return;
  askCssLinked = true;
  if (typeof document === "undefined" || !document.head) return;
  if (document.getElementById("cyclone-ask-css")) return;
  const link = document.createElement("link");
  link.id = "cyclone-ask-css";
  link.rel = "stylesheet";
  try {
    link.href = new URL("../ask.css", import.meta.url).href;
  } catch {
    link.href = "/src/ask.css";
  }
  document.head.appendChild(link);
}

function hasMobileVersionOption(options: AskPageOptions): boolean {
  return Object.prototype.hasOwnProperty.call(options, "mobileVersion")
    || "mobileVersion" in options;
}

function askPlaneLabel(plane: AskPageOptions["sessionPlane"]): string {
  return plane === "session_kernel_vd" ? VD_PLANE_LABEL : FOREGROUND_PLANE_LABEL;
}

export function createAskPage(options: AskPageOptions = {}): AskPageHandle {
  ensureAskCss();
  const devices = options.devices ?? [];
  const versionPresent = hasMobileVersionOption(options);
  const preview = options.previewSnapshots === true || options.previewNeedsSecret === true;
  const showSamples = preview || !versionPresent;
  const atlasReady = phoneSupportsGlassAtlas(options.mobileVersion ?? "");
  const displaySessionId = resolveAskLogSessionId(options.sessionId);
  const plane = options.sessionPlane === "session_kernel_vd" ? "session_kernel_vd" : "foreground";
  const planeLabel = askPlaneLabel(plane);
  let snapshot: GlassAskSnapshot | null = options.previewNeedsSecret ? ASK_NEEDS_SECRET_FIXTURE : null;

  const page = el("section", "page content-page ask-page");
  const header = el("header", "page-header");
  const heading = el("div");
  const sessionLine = el("p", "ask-session-plane", `${planeLabel} · session_id ${displaySessionId}`);
  sessionLine.dataset.sessionId = displaySessionId;
  sessionLine.dataset.sessionPlane = plane;
  heading.append(
    el("div", "ask-kicker", "CYCLONE GLASS · ASK"),
    el("h1", "page-title", "Ask"),
    el("p", "page-subtitle", "Same phone run as the overlay. Glass commands and displays; the phone executes."),
    sessionLine,
  );
  const control = button("Take control on Phone", "button secondary compact");
  control.disabled = !options.onOpenControl;
  control.addEventListener("click", () => options.onOpenControl?.());
  header.append(heading, control);

  const banner = el("aside", "glass-compat-banner");
  banner.hidden = atlasReady;
  banner.append(
    el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
    el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
  );

  const composer = el("article", "ask-composer-card");
  composer.append(el("h2", "ask-section-title", "Goal"));
  const form = el("form", "ask-composer-form") as HTMLFormElement;
  const textarea = el("textarea", "ask-composer-input") as HTMLTextAreaElement;
  textarea.rows = 3;
  textarea.placeholder = "e.g. Check Facebook login status";
  textarea.setAttribute("aria-label", "Ask goal");
  textarea.autocomplete = "off";
  const send = button("Send to phone", "button primary ask-send");
  send.type = "submit";
  send.disabled = true;
  form.append(textarea, send);
  const formStatus = el("p", "ask-form-status", atlasReady
    ? `${SEND_OFF_COPY} Glass does not run goals on this PC.`
    : "Ask runs on Cyclone Mobile 5. Glass does not execute goals and will not invent a PC executor.");
  composer.append(form, formStatus);

  const hud = el("section", "ask-hud-host");
  hud.setAttribute("aria-live", "polite");

  const footer = el("p", "ask-footnote", "Take control remains the existing Phone live handoff. Mapping and Ask both honor HUMAN_HAS_CONTROL.");

  page.append(header, banner, composer, hud);
  let demoSelect: HTMLSelectElement | null = null;
  if (showSamples) {
    const demo = el("div", "ask-demo-bar");
    if (preview) {
      demo.append(el("span", "ask-sample-badge", SAMPLE_BADGE));
    }
    const demoLabel = el("span", "ask-demo-copy", preview ? SAMPLE_PREVIEW_COPY : SAMPLE_DEFAULT_COPY);
    demoSelect = el("select", "ask-demo-select") as HTMLSelectElement;
    demoSelect.setAttribute("aria-label", preview ? "Sample Ask snapshot (sample)" : "Sample Ask snapshot");
    const idleOption = document.createElement("option");
    idleOption.value = "";
    idleOption.textContent = "No run";
    demoSelect.append(idleOption);
    for (const state of ASK_RUN_STATES) {
      const option = document.createElement("option");
      option.value = state;
      option.textContent = preview ? `${sampleLabel(state)} ${SAMPLE_BADGE}` : sampleLabel(state);
      demoSelect.append(option);
    }
    if (options.previewNeedsSecret) demoSelect.value = "needs-secret";
    demo.append(demoLabel, demoSelect);
    page.append(demo);
  }
  page.append(footer);

  const paintHud = (): void => {
    if (!snapshot) {
      const empty = el("div", "ask-hud-empty");
      empty.append(
        el("h2", "ask-hud-empty-title", atlasReady || devices.length === 0
          ? NO_RUN_YET
          : GLASS_UPDATE_PHONE_TITLE),
        el("p", "ask-hud-empty-copy", emptyHudCopy(atlasReady, showSamples)),
      );
      hud.replaceChildren(empty);
      return;
    }
    hud.replaceChildren(renderAskHud(snapshot, displaySessionId, planeLabel));
  };

  const askOps = options.ask && atlasReady && plane === "foreground" ? options.ask : undefined;
  let pollHandle: unknown = null;
  let polling = false;
  let destroyed = false;
  const setTimer = options.askTimer?.set ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = options.askTimer?.clear ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));

  const schedulePoll = (): void => {
    if (!askOps || destroyed || pollHandle != null) return;
    pollHandle = setTimer(() => {
      pollHandle = null;
      void poll();
    }, ASK_POLL_MS);
  };

  async function poll(): Promise<void> {
    if (!askOps || destroyed || polling) return;
    polling = true;
    let keepGoing = false;
    try {
      const status = await askOps.askStatus();
      if (destroyed) return;
      snapshot = askSnapshotFromStatus(status, displaySessionId);
      keepGoing = status.state === "working" || status.state === "action-needed" || status.state === "needs-secret";
      paintHud();
    } catch {
      keepGoing = true;
    } finally {
      polling = false;
      if (keepGoing) schedulePoll();
    }
  }

  if (askOps) {
    send.disabled = false;
    formStatus.textContent = LIVE_SEND_COPY;
    textarea.placeholder = "e.g. open Gmail, check my email, then find Louella's message on Facebook";
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!askOps) {
      formStatus.textContent = "Glass does not execute this goal on the PC. Ask lives on the phone.";
      return;
    }
    const goal = textarea.value.trim();
    if (!goal) {
      formStatus.textContent = "Type a goal first.";
      return;
    }
    send.disabled = true;
    formStatus.textContent = "Sending to the phone…";
    void askOps.askStart(goal)
      .then(() => {
        if (destroyed) return;
        textarea.value = "";
        formStatus.textContent = LIVE_SEND_COPY;
        void poll();
      })
      .catch((error: unknown) => {
        const code = (error as { code?: unknown })?.code;
        formStatus.textContent = askErrorCopy(typeof code === "string" ? code : "");
      })
      .finally(() => {
        send.disabled = false;
      });
  });

  demoSelect?.addEventListener("change", () => {
    const value = demoSelect!.value as AskRunState | "";
    snapshot = value ? ASK_SAMPLE_SNAPSHOTS[value] : null;
    paintHud();
  });

  paintHud();
  // Follow a run started on the phone as well as one sent from here.
  if (askOps) void poll();

  return {
    element: page,
    destroy: () => {
      destroyed = true;
      if (pollHandle != null) clearTimer(pollHandle);
      pollHandle = null;
    },
  };
}

function emptyHudCopy(atlasReady: boolean, showSamples: boolean): string {
  if (!atlasReady) return GLASS_UPDATE_PHONE_COPY;
  if (showSamples) {
    return "Type a goal when Mobile 5 can start the same overlay run. Use a sample snapshot to preview HUD states.";
  }
  return `${SEND_OFF_COPY} Glass does not run goals on this PC.`;
}

function renderAskHud(snapshot: GlassAskSnapshot, sessionId: string, planeLabel: string): HTMLElement {
  const panel = el("article", `ask-hud ask-hud-${snapshot.state}`);
  panel.dataset.state = snapshot.state;
  panel.append(
    el("div", "ask-hud-kicker", `${hudKicker(snapshot.state)} · ${planeLabel}`),
    el("h2", "ask-hud-title", snapshot.title),
  );
  if (snapshot.supportingCopy) {
    panel.append(el("p", "ask-hud-copy", snapshot.supportingCopy));
  }
  if (snapshot.milestones?.length) {
    const list = el("ol", "ask-milestones");
    for (const milestone of snapshot.milestones) {
      const item = el("li", `ask-milestone ask-milestone-${milestone.state}`, milestone.label);
      item.dataset.milestoneState = milestone.state;
      list.append(item);
    }
    panel.append(list);
  }
  if (snapshot.state === "needs-secret") {
    panel.append(createSecretsCard({
      slotLabel: snapshot.slotLabel || "password",
      presence: "waiting",
    }));
  } else if (snapshot.state === "failed") {
    panel.append(el("p", "ask-hud-failed-note", "Failed is terminal. needs-secret is a wait, not this."));
  }
  panel.append(el("p", "ask-hud-session", `session_id ${sessionId}`));
  const download = button("Download HUD log", "button secondary compact ask-hud-download");
  download.setAttribute("aria-label", "Download HUD log");
  download.addEventListener("click", () => {
    downloadAskHudLog(formatAskHudLog(snapshot, sessionId));
  });
  panel.append(download);
  return panel;
}

function downloadAskHudLog(text: string): void {
  if (typeof URL === "undefined" || typeof URL.createObjectURL !== "function") return;
  if (typeof Blob !== "function") return;
  const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.setAttribute("download", HUD_LOG_FILENAME);
  (anchor as HTMLAnchorElement).download = HUD_LOG_FILENAME;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(href);
}

function hudKicker(state: AskRunState): string {
  if (state === "needs-secret") return "NEEDS YOU";
  if (state === "action-needed") return "ACTION NEEDED";
  if (state === "working") return "WORKING";
  if (state === "done") return "DONE";
  return "FAILED";
}

function sampleLabel(state: AskRunState): string {
  if (state === "needs-secret") return "needs-secret — wait on phone";
  if (state === "action-needed") return "action-needed";
  return state;
}
