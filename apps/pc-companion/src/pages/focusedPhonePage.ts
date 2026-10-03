import { keyboardCommandForEvent } from "../core/keyboard.js";
import { KeyboardCapture } from "../core/keyboardCapture.js";
import { needsTrustRepair, trustRepairMessage } from "../core/trustRecovery.js";
import type { ControlResult, DesktopDevice, DesktopService, DeviceControlAction, Layer2Status } from "../services/types.js";
import { CycloneOneSessionClient } from "../services/sessionClient.js";
import {
  bindLayer2Status,
  canPauseLayer2,
  canReleaseLayer2,
  generationLabel,
  LAYER2_COMPACT_COPY,
  LAYER2_EMPTY_COPY,
  LAYER2_STRIP_TITLE,
  lockOwnerLabel,
} from "../core/layer2.js";
import {
  bindSessionTile,
  DEFAULT_FOREGROUND_SESSION_ID,
  FOREGROUND_KIND,
  FOREGROUND_PLANE_COPY,
  FOREGROUND_PLANE_LABEL,
  isSessionKernelVd,
  jpegFocusTarget,
  markSessionInventory,
  MCP_FOREGROUND_OPERATOR_LINE,
  MCP_FOREGROUND_SESSION_COPY,
  SESSION_KERNEL_VD_KIND,
  SESSION_TILES_COPY,
  SESSION_TILES_TITLE,
  sessionDisplayLabel,
  sessionPlaneLabel,
  tileForSessionScope,
  VD_PLANE_LABEL,
  type SessionTile,
} from "../core/sessionTiles.js";
import { button, el, icon } from "../ui/dom.js";
import { createLivePhoneView } from "../ui/livePhoneView.js";
import { createDeviceHealthPanel } from "../ui/deviceHealthPanel.js";

export interface FocusedPhonePageHandle {
  element: HTMLElement;
  destroy(): void;
  updateDevice(device: DesktopDevice): void;
}

export function createFocusedPhonePage(
  service: DesktopService,
  device: DesktopDevice,
  onBack: () => void,
  onSettings: () => void,
  onPair: (device: DesktopDevice) => void,
  onSessionFocus?: (sessionId: string) => void,
): FocusedPhonePageHandle {
  const page = el("section", "page focus-page preview-first");
  const topbar = el("header", "focus-topbar");
  const back = button("Back", "back-to-fleet");
  back.prepend(icon("←"));
  back.addEventListener("click", onBack);
  const focusHeading = el("div", "focus-heading");
  focusHeading.append(el("div", "focus-kicker", "PHONE SCREEN"), el("h1", "focus-title", device.name));
  const identity = el("div", "focus-device-identity");
  const identityName = el("div", "focus-device-name", device.name);
  const identityConnection = el("div", "phone-connection", device.connectionLabel);
  identity.append(identityName, identityConnection);
  topbar.append(back, focusHeading, identity);

  const workspace = el("div", "focus-workspace");
  const contextPanel = el("aside", "focus-context-panel");
  const contextName = el("div", "context-device-name", device.name);
  const contextModel = el("div", "context-device-model", device.model || "Android phone");
  contextPanel.append(
    el("div", "panel-eyebrow", "ACTIVE PHONE"),
    contextName,
    contextModel,
  );
  const health = el("div", "context-health");
  const healthDot = el("span", `context-health-dot state-${device.state.toLowerCase()}`);
  const healthCopy = el("span", "context-health-copy", device.connectionLabel);
  health.append(healthDot, healthCopy);
  const healthSlot = el("div", "focus-health-slot");
  healthSlot.append(createDeviceHealthPanel(device));
  const humanInput = el("div", "context-card");
  const planeCopy = el("p", "context-card-copy", `${FOREGROUND_PLANE_COPY} Select a ${VD_PLANE_LABEL} tile to focus its isolated JPEG (displayId>0, never rewritten to display 0). Layer 2 is the display-0 time-sliced lock in the strip below — not a VD tile.`);
  const humanCopy = el("p", "context-card-copy", "Click anywhere on the Foreground JPEG to tap. Hold and drag naturally to swipe. Mouse control stays available while display 0 live view warms up. Session Kernel VD JPEG is snapshot-only.");
  humanInput.append(
    el("div", "context-card-title", "Human control"),
    planeCopy,
    el("p", "context-card-copy", MCP_FOREGROUND_OPERATOR_LINE),
    humanCopy,
  );
  const aiInput = el("div", "context-card accent");
  const ownerLabel = el("p", "context-card-copy", ownerCopy(device));
  const sessionList = el("p", "context-card-copy", `${FOREGROUND_PLANE_LABEL} session_id=${DEFAULT_FOREGROUND_SESSION_ID}`);
  const pauseSession = button("Pause", "button secondary compact");
  const yieldAi = button("Give to AI", "button primary compact");
  const takeHuman = button("Take control", "button secondary compact");
  pauseSession.disabled = true;
  aiInput.append(
    el("div", "context-card-title", "Human ↔ AI handoff"),
    ownerLabel,
    sessionList,
    el("p", "context-card-copy", MCP_FOREGROUND_SESSION_COPY),
    el("p", "context-card-copy", "MCP mutations fail with HUMAN_HAS_CONTROL while Companion owns input. Yield here, or pass request_ai_control=true. A locked phone is never stolen."),
    pauseSession,
    yieldAi,
    takeHuman,
  );
  const sessionStrip = el("div", "task-session-compact");
  sessionStrip.append(
    el("div", "context-card-title", SESSION_TILES_TITLE),
    el("p", "context-card-copy", FOREGROUND_PLANE_COPY),
    el("p", "context-card-copy", SESSION_TILES_COPY),
  );
  const sessionTileList = el("div", "task-session-list-compact");
  sessionStrip.append(sessionTileList);
  const layer2Strip = el("div", "layer2-strip layer2-strip-compact");
  contextPanel.append(health, healthSlot, humanInput, sessionStrip, aiInput, layer2Strip);

  const controlStatus = el("div", "control-status", "Ready");
  const trustRepairBanner = el("section", "trust-repair-banner");
  const trustRepairCopy = el("div");
  trustRepairCopy.append(
    el("div", "trust-repair-title", "Cyclone AI trust needs repair"),
    el("p", "trust-repair-copy", trustRepairMessage(device)),
  );
  const trustRepairButton = button("Forget & pair again", "button primary compact");
  trustRepairBanner.append(trustRepairCopy, trustRepairButton);
  trustRepairBanner.hidden = !needsTrustRepair(device);

  const repairTrust = async () => {
    if (!service.trustRevoke) {
      controlStatus.textContent = "Trust repair is unavailable in this Companion build";
      controlStatus.classList.add("error");
      return;
    }
    const approved = window.confirm(`Forget the stale trust for ${device.name} on this PC? You will need to approve Allow this PC on the phone again.`);
    if (!approved) return;
    trustRepairButton.disabled = true;
    controlStatus.textContent = "Forgetting stale trust…";
    controlStatus.classList.remove("error");
    try {
      await service.trustRevoke(device.id);
      controlStatus.textContent = "Old trust forgotten · approve the new request on the phone";
      trustRepairBanner.hidden = true;
      onPair(device);
    } catch {
      controlStatus.textContent = "Trust repair failed safely";
      controlStatus.classList.add("error");
      trustRepairButton.disabled = false;
    }
  };
  trustRepairButton.addEventListener("click", () => void repairTrust());
  const liveColumn = el("div", "focus-live-column");
  const live = createLivePhoneView({
    service,
    device,
    profile: "focus",
    interactive: true,
    showLabel: false,
    showHealth: false,
    onControl: (kind, ok) => {
      const label = kind === "tap" ? "Mouse tap" : "Mouse swipe";
      controlStatus.textContent = ok ? `${label} sent` : `${label} unavailable`;
      controlStatus.classList.toggle("error", !ok);
    },
  });
  live.element.classList.add("focused-live-phone");
  const liveHint = el("div", "direct-control-hint", "Click to tap · drag to swipe");
  const jpegHost = el("div", "task-jpeg-focus-host");
  jpegHost.hidden = true;
  liveColumn.append(liveHint, live.element, jpegHost);

  const controls = el("aside", "focus-controls");
  controls.append(el("div", "panel-eyebrow", "CONTROLLER"));
  const runControl = async (action: DeviceControlAction, label: string) => {
    controlStatus.textContent = `${label}…`;
    controlStatus.classList.remove("error");
    try {
      const result = await service.sendControl(device.id, action);
      controlStatus.textContent = result.ok ? `${label} sent` : ownershipFailureCopy(label, result.verification);
      controlStatus.classList.toggle("error", !result.ok);
    } catch (error) {
      controlStatus.textContent = ownershipFailureCopy(label, error instanceof Error ? error.message : undefined);
      controlStatus.classList.add("error");
    }
  };
  let disposed = false;
  let selectedSessionId = DEFAULT_FOREGROUND_SESSION_ID;
  let sessionTiles: SessionTile[] = [];
  let jpegUrl: string | null = null;

  const selectedTile = (): SessionTile | undefined => {
    return tileForSessionScope(sessionTiles, { sessionId: selectedSessionId })
      ?? sessionTiles.find((tile) => tile.sessionId === selectedSessionId)
      ?? undefined;
  };

  const restoreForegroundLive = (): void => {
    live.element.hidden = false;
    jpegHost.hidden = true;
    jpegHost.replaceChildren();
    if (jpegUrl) URL.revokeObjectURL(jpegUrl);
    jpegUrl = null;
    liveHint.textContent = "Foreground JPEG · display 0 live · click to tap · drag to swipe";
    planeCopy.textContent = `${FOREGROUND_PLANE_COPY} ${VD_PLANE_LABEL} JPEG is selected from the tiles below. Layer 2 is the display-0 time-sliced lock in the strip — not a VD tile.`;
  };

  const showVdJpeg = async (tile: SessionTile): Promise<void> => {
    const target = jpegFocusTarget(tile);
    if (!(target.displayId > 0)) throw new Error("SESSION_DISPLAY_MISMATCH");
    const snapshot = service.snapshotDeviceSession
      ? await service.snapshotDeviceSession(device.id, target.sessionId)
      : await (await CycloneOneSessionClient.connect()).snapshot(device.id, target.sessionId);
    if (!(snapshot.displayId > 0)) throw new Error("SESSION_DISPLAY_MISMATCH");
    if (jpegUrl) URL.revokeObjectURL(jpegUrl);
    jpegUrl = snapshot.url.startsWith("blob:") ? snapshot.url : null;
    const image = document.createElement("img");
    image.className = "task-jpeg-focus";
    image.src = snapshot.url;
    image.alt = `${VD_PLANE_LABEL} JPEG for ${tile.sessionId} on Android display ${snapshot.displayId}`;
    image.dataset.sessionId = tile.sessionId;
    image.dataset.displayId = String(snapshot.displayId);
    image.dataset.plane = tile.plane || (isSessionKernelVd(tile) ? SESSION_KERNEL_VD_KIND : tile.kind);
    const meta = el("div", "task-preview-meta", `Exact Android display ${snapshot.displayId} · session_id ${tile.sessionId}`);
    jpegHost.replaceChildren(image, meta);
    jpegHost.hidden = false;
    live.element.hidden = true;
    liveHint.textContent = `${VD_PLANE_LABEL} JPEG · display ${snapshot.displayId} · session_id ${tile.sessionId} · not display 0`;
    planeCopy.textContent = `${VD_PLANE_LABEL} JPEG for session_id=${tile.sessionId} on display ${snapshot.displayId}. Isolated virtual display — not Layer 2, not rewritten to display 0.`;
  };

  const isVdTile = (tile: SessionTile): boolean => {
    return isSessionKernelVd(tile) || tile.kind === SESSION_KERNEL_VD_KIND || tile.kind !== FOREGROUND_KIND;
  };

  const syncSelectedActions = (tile: SessionTile | undefined): void => {
    const vd = tile ? isVdTile(tile) : false;
    pauseSession.disabled = !tile || !vd || tile.state !== "RUNNING";
    takeHuman.disabled = !tile || tile.state === "STOPPED";
    yieldAi.disabled = !tile || tile.state === "STOPPED";
  };

  const renderSessionTiles = (): void => {
    if (sessionTiles.length === 0) {
      sessionTileList.replaceChildren(el("p", "context-card-copy", "No Session Kernel VD tiles yet. Foreground remains display 0 live."));
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const tile of sessionTiles) {
      const kindName = tile.kind === FOREGROUND_KIND ? FOREGROUND_KIND : SESSION_KERNEL_VD_KIND;
      const row = button("", `task-session-tile-compact task-kind-${tile.kind} task-kind-${kindName}${tile.sessionId === selectedSessionId ? " task-session-selected" : ""}`);
      row.dataset.sessionId = tile.sessionId;
      if (tile.displayId != null) row.dataset.displayId = String(tile.displayId);
      row.dataset.kind = tile.kind;
      row.dataset.plane = tile.plane || kindName;
      const owner = String(tile.inputOwner || "").toUpperCase();
      row.append(
        el("div", `task-plane task-kind-${kindName}`, sessionPlaneLabel(tile)),
        el("div", "task-session-compact-id", `${tile.sessionId} · display ${sessionDisplayLabel(tile)}`),
        el("span", owner === "AI" ? "task-owner-ai" : "task-owner-human", owner === "AI" || owner === "HUMAN" ? owner : (owner || "—")),
      );
      row.addEventListener("click", () => {
        void applyTileSelection(tile).catch((error) => {
          controlStatus.textContent = ownershipFailureCopy("Focus JPEG", error instanceof Error ? error.message : undefined);
          controlStatus.classList.add("error");
        });
      });
      fragment.append(row);
    }
    sessionTileList.replaceChildren(fragment);
  };

  const applyTileSelection = async (tile: SessionTile): Promise<void> => {
    selectedSessionId = tile.sessionId;
    onSessionFocus?.(tile.sessionId);
    syncSelectedActions(tile);
    renderSessionTiles();
    controlStatus.classList.remove("error");
    if (tile.kind === FOREGROUND_KIND || !isVdTile(tile)) {
      restoreForegroundLive();
      return;
    }
    try {
      await showVdJpeg(tile);
    } catch (error) {
      live.element.hidden = true;
      jpegHost.hidden = false;
      jpegHost.replaceChildren(el("div", "task-inline-error", ownershipFailureCopy("Focus JPEG", error instanceof Error ? error.message : undefined)));
      liveHint.textContent = `${VD_PLANE_LABEL} JPEG unavailable · not rewritten to display 0`;
      planeCopy.textContent = `${VD_PLANE_LABEL} JPEG for session_id=${tile.sessionId} did not land on display 0. Isolated virtual display remains distinct from Foreground JPEG and the Layer 2 strip.`;
      throw error;
    }
  };

  const refreshSessions = async (): Promise<void> => {
    try {
      const listed = service.listDeviceSessions
        ? await service.listDeviceSessions(device.id)
        : await (await CycloneOneSessionClient.connect()).list(device.id);
      sessionTiles = markSessionInventory(listed.sessions.map((session) => bindSessionTile(device.id, session)));
      if (disposed) return;
      const scoped = tileForSessionScope(sessionTiles, { sessionId: selectedSessionId })
        ?? sessionTiles.find((tile) => tile.sessionId === selectedSessionId)
        ?? sessionTiles.find((tile) => tile.kind === FOREGROUND_KIND)
        ?? sessionTiles[0];
      renderSessionTiles();
      if (scoped) {
        selectedSessionId = scoped.sessionId;
        syncSelectedActions(scoped);
        if (scoped.kind === FOREGROUND_KIND || !isVdTile(scoped)) restoreForegroundLive();
      } else {
        restoreForegroundLive();
        syncSelectedActions(undefined);
      }
    } catch {
      if (!disposed) {
        sessionTileList.replaceChildren(el("p", "context-card-copy", `${FOREGROUND_PLANE_LABEL} session_id=${DEFAULT_FOREGROUND_SESSION_ID}`));
        restoreForegroundLive();
      }
    }
  };

  const runOwnership = async (kind: "yield_ai" | "take_human", label: string) => {
    controlStatus.textContent = `${label}…`;
    controlStatus.classList.remove("error");
    try {
      const sessionId = selectedTile()?.sessionId ?? selectedSessionId;
      const result = await sendSessionHandoff(service, device.id, sessionId, kind);
      if (result.ok) {
        device.inputOwner = result.inputOwner ?? (kind === "yield_ai" ? "AI" : "HUMAN");
        ownerLabel.textContent = ownerCopy(device);
        ownerText.textContent = device.inputOwner === "AI" ? "AI" : "Human";
        ownerDot.classList.toggle("ai", device.inputOwner === "AI");
        controlStatus.textContent = kind === "yield_ai"
          ? "AI has control"
          : "You have control";
        if (!disposed) await refreshSessions();
      } else {
        controlStatus.textContent = ownershipFailureCopy(label, result.verification);
        controlStatus.classList.add("error");
      }
    } catch (error) {
      controlStatus.textContent = ownershipFailureCopy(label, error instanceof Error ? error.message : undefined);
      controlStatus.classList.add("error");
    }
  };
  yieldAi.addEventListener("click", () => void runOwnership("yield_ai", "Give to AI"));
  takeHuman.addEventListener("click", () => void runOwnership("take_human", "Take control"));
  pauseSession.addEventListener("click", () => {
    const tile = selectedTile();
    if (!tile || !isVdTile(tile)) return;
    pauseSession.disabled = true;
    controlStatus.textContent = "Pause…";
    controlStatus.classList.remove("error");
    const pause = service.pauseDeviceSession
      ? service.pauseDeviceSession(device.id, tile.sessionId)
      : CycloneOneSessionClient.connect().then((client) => client.pause(device.id, tile.sessionId));
    void pause
      .then(() => {
        if (!disposed) {
          controlStatus.textContent = `${VD_PLANE_LABEL} paused`;
          return refreshSessions();
        }
      })
      .catch((error) => {
        controlStatus.textContent = ownershipFailureCopy("Pause", error instanceof Error ? error.message : undefined);
        controlStatus.classList.add("error");
      })
      .finally(() => { if (!disposed) syncSelectedActions(selectedTile()); });
  });
  void runOwnership("take_human", "Take control");
  void loadSessionSummary(service, device.id, sessionList);
  void refreshSessions();
  const refreshLayer2 = () => loadLayer2Strip(service, device.id, layer2Strip, () => {
    if (!disposed) void refreshLayer2();
  });
  void refreshLayer2();
  const primary = el("div", "control-rail");
  const controlDefs: Array<[string, string, DeviceControlAction]> = [
    ["←", "Back", { type: "key", key: "BACK" }],
    ["⌂", "Home", { type: "key", key: "HOME" }],
  ];
  const quickControls = el("div", "quick-controls");
  for (const [symbol, label, action] of controlDefs) {
    const node = button("", "control-button");
    node.append(icon(symbol), el("span", "control-label", label));
    node.addEventListener("click", () => void runControl(action, label));
    quickControls.append(node);
  }
  primary.append(quickControls);

  const directionPad = el("div", "direction-pad");
  const directionalControls: Array<["up" | "left" | "right" | "down", string, DeviceControlAction]> = [
    ["up", "Scroll up", { type: "swipe", x1: .5, y1: .72, x2: .5, y2: .28, durationMs: 280 }],
    ["left", "Scroll left", { type: "swipe", x1: .72, y1: .5, x2: .28, y2: .5, durationMs: 280 }],
    ["right", "Scroll right", { type: "swipe", x1: .28, y1: .5, x2: .72, y2: .5, durationMs: 280 }],
    ["down", "Scroll down", { type: "swipe", x1: .5, y1: .28, x2: .5, y2: .72, durationMs: 280 }],
  ];
  for (const [direction, label, action] of directionalControls) {
    const symbol = direction === "up" ? "↑" : direction === "down" ? "↓" : direction === "left" ? "←" : "→";
    const node = button(symbol, `direction-button direction-${direction}`);
    node.setAttribute("aria-label", label);
    node.title = label;
    node.addEventListener("click", () => void runControl(action, label));
    directionPad.append(node);
  }
  directionPad.append(el("div", "direction-center", "SWIPE"));
  primary.append(directionPad, controlStatus);

  const keyboardCapture = new KeyboardCapture();
  let keyboardActive = false;
  const keyboardIndicator = el("div", "keyboard-indicator");
  keyboardIndicator.hidden = true;
  const keyboard = button("", "control-button");
  keyboard.append(icon("⌨"), el("span", "control-label", "Keyboard"));
  keyboard.disabled = !device.capabilities.keyboard;
  keyboard.addEventListener("click", () => setKeyboardActive(!keyboardActive));
  primary.append(keyboard);

  const clipboardPanel = el("div", "tool-popover clipboard-popover");
  clipboardPanel.hidden = true;
  const clipboard = button("", "control-button");
  clipboard.append(icon("▣"), el("span", "control-label", "Clipboard"));
  clipboard.addEventListener("click", () => {
    clipboardPanel.hidden = !clipboardPanel.hidden;
  });
  primary.append(clipboard);

  const clipTitle = el("div", "tool-title", "Clipboard");
  const clipAvailability = el("p", "tool-copy", device.capabilities.clipboard ? "Move text between this computer and phone." : "Clipboard sync isn't available on this device");
  const syncRow = el("label", "toggle-row");
  const sync = el("input") as HTMLInputElement;
  sync.type = "checkbox";
  sync.checked = device.capabilities.clipboardSync;
  sync.disabled = !device.capabilities.clipboard;
  sync.addEventListener("change", () => {
    void service.sendControl(device.id, { type: "clipboard_sync", enabled: sync.checked }).catch(() => {
      sync.checked = !sync.checked;
    });
  });
  syncRow.append(el("span", "toggle-label", "Clipboard sync"), sync);
  const paste = button("Paste from computer", "button secondary wide");
  paste.disabled = !device.capabilities.clipboard;
  paste.addEventListener("click", async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (!text) return;
      await service.sendControl(device.id, { type: "clipboard_paste", text });
      // Do not retain or display clipboard contents.
    } catch {
      clipAvailability.textContent = "Clipboard access is unavailable. Check your computer permissions.";
    }
  });
  clipboardPanel.append(clipTitle, clipAvailability, syncRow, paste);

  const more = el("details", "more-menu");
  const summary = el("summary", "control-button");
  summary.append(icon("•••"), el("span", "control-label", "More"));
  const menu = el("div", "more-menu-panel");
  const menuItems: Array<[string, () => void]> = [
    ["Forget & pair again", () => void repairTrust()],
    ["Reconnect", () => void service.sendControl(device.id, { type: "reconnect" }).catch(() => undefined)],
    ["Device settings", onSettings],
    ["Technical diagnostics", onSettings],
  ];
  for (const [label, action] of menuItems) {
    const item = button(label, "menu-item");
    item.addEventListener("click", action);
    menu.append(item);
  }
  more.append(summary, menu);

  const ownership = el("div", "focus-ownership");
  const ownerDot = el("span", "ownership-dot");
  const ownerText = el("span", "ownership-label", device.inputOwner === "AI" ? "AI" : "Human");
  ownership.append(el("div", "ownership-kicker", "Control"), ownerDot, ownerText, yieldAi, takeHuman);
  yieldAi.textContent = "Give AI control";
  takeHuman.textContent = "Take control";

  const details = el("details", "focus-details") as HTMLDetailsElement;
  const detailsSummary = el("summary", "focus-details-summary");
  detailsSummary.append(
    el("span", "focus-details-title", "Details"),
    el("span", "focus-details-copy", "Connection health, MCP session and diagnostics"),
  );
  details.append(detailsSummary, contextPanel);

  controls.append(primary, more, clipboardPanel, ownership);
  workspace.append(liveColumn, controls);
  page.append(topbar, trustRepairBanner, keyboardIndicator, workspace, details);

  const keydown = (event: KeyboardEvent) => {
    if (!keyboardActive) return;
    const command = keyboardCommandForEvent(event);
    if (command.type === "ignore") return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (command.type === "stop") {
      setKeyboardActive(false);
      return;
    }
    if (command.type === "consume") return;
    if (command.type === "text") {
      const text = command.text;
      void service.sendControl(device.id, { type: "text", text }).catch(() => undefined);
      return;
    }
    void service.sendControl(device.id, { type: "key", key: command.key }).catch(() => undefined);
  };
  window.addEventListener("keydown", keydown, true);

  function setKeyboardActive(active: boolean): void {
    keyboardActive = active && device.capabilities.keyboard;
    if (keyboardActive) keyboardCapture.start(device.id); else keyboardCapture.stop();
    keyboard.classList.toggle("active", keyboardActive);
    keyboardIndicator.hidden = !keyboardActive;
    keyboardIndicator.textContent = keyboardActive ? `Keyboard controlling ${device.name} · Esc to stop` : "";
    page.classList.toggle("keyboard-active", keyboardActive);
  }

  return {
    element: page,
    destroy: () => {
      disposed = true;
      setKeyboardActive(false);
      window.removeEventListener("keydown", keydown, true);
      if (jpegUrl) URL.revokeObjectURL(jpegUrl);
      jpegUrl = null;
      live.destroy();
    },
    updateDevice: (next) => {
      identityName.textContent = next.name;
      identityConnection.textContent = next.connectionLabel;
      contextName.textContent = next.name;
      contextModel.textContent = next.model || "Android phone";
      healthDot.className = `context-health-dot state-${next.state.toLowerCase()}`;
      healthCopy.textContent = next.connectionLabel;
      healthSlot.replaceChildren(createDeviceHealthPanel(next));
      trustRepairBanner.hidden = !needsTrustRepair(next);
      ownerLabel.textContent = ownerCopy(next);
      ownerText.textContent = next.inputOwner === "AI" ? "AI" : "Human";
      ownerDot.classList.toggle("ai", next.inputOwner === "AI");
      if (!disposed) {
        void refreshLayer2();
        void refreshSessions();
        void loadSessionSummary(service, next.id, sessionList);
      }
    },
  };
}

function ownerCopy(device: DesktopDevice): string {
  const owner = device.inputOwner === "AI" ? "AI" : "HUMAN";
  return owner === "AI"
    ? "Input owner: AI. Agents can phone.open_app / phone.click after observe."
    : "Input owner: HUMAN. Yield to AI before MCP mutations, or they return HUMAN_HAS_CONTROL.";
}

function ownershipFailureCopy(label: string, detail?: string): string {
  const code = String(detail || "").toUpperCase();
  if (code.includes("PHONE_LOCKED")) return "Phone is locked — unlock it first. A locked phone is never stolen.";
  if (code.includes("HUMAN_HAS_CONTROL")) return "Companion still owns input. Click Give control to AI, then retry.";
  if (code.includes("SESSION_DISPLAY_MISMATCH") || code.includes("REWRITE TO DISPLAY 0")) {
    return "Named Session Kernel VD JPEG refused display 0.";
  }
  return detail ? `${label} unavailable (${String(detail).slice(0, 80)})` : `${label} unavailable`;
}

async function sendSessionHandoff(
  service: DesktopService,
  deviceId: string,
  sessionId: string,
  kind: "yield_ai" | "take_human",
): Promise<ControlResult> {
  if (service.sendSessionControl) return service.sendSessionControl(deviceId, sessionId, kind);
  return service.sendControl(deviceId, { type: kind, sessionId });
}

async function loadLayer2Strip(
  service: DesktopService,
  deviceId: string,
  target: HTMLElement,
  onChanged: () => void,
): Promise<void> {
  target.replaceChildren(el("div", "context-card-title", LAYER2_STRIP_TITLE));
  const listWorkspaces = service.listLayer2Workspaces;
  const mutateWorkspace = service.layer2Workspace;
  if (!listWorkspaces || !mutateWorkspace) {
    target.append(el("p", "context-card-copy", LAYER2_EMPTY_COPY));
    return;
  }
  let status: Layer2Status;
  try {
    status = bindLayer2Status(deviceId, await listWorkspaces(deviceId));
  } catch {
    target.append(el("p", "context-card-copy", LAYER2_EMPTY_COPY));
    return;
  }
  if (status.workspaces.length === 0) {
    target.append(el("p", "context-card-copy", LAYER2_EMPTY_COPY));
    return;
  }
  target.append(
    el("p", "context-card-copy", LAYER2_COMPACT_COPY),
    el("p", "context-card-copy", lockOwnerLabel(status)),
    el("p", "context-card-copy", generationLabel(status)),
  );
  const actions = el("div", "layer2-actions");
  const pause = button("Pause", "button secondary compact");
  const release = button("Release", "button secondary compact");
  pause.disabled = !canPauseLayer2(status);
  release.disabled = !canReleaseLayer2(status);
  const run = (operation: "pause" | "release", node: HTMLButtonElement) => {
    node.disabled = true;
    void mutateWorkspace(deviceId, operation).finally(onChanged);
  };
  pause.addEventListener("click", () => run("pause", pause));
  release.addEventListener("click", () => run("release", release));
  actions.append(pause, release);
  target.append(actions);
}

async function loadSessionSummary(service: DesktopService, deviceId: string, target: HTMLElement): Promise<void> {
  try {
    const listed = service.listDeviceSessions
      ? await service.listDeviceSessions(deviceId)
      : await (await CycloneOneSessionClient.connect()).list(deviceId);
    const ids = listed.sessions.map((item) => {
      const tile = bindSessionTile(deviceId, item);
      return `${item.sessionId} (${sessionPlaneLabel(tile)})`;
    });
    target.textContent = ids.length
      ? `Sessions: ${ids.join(" · ")}`
      : `Sessions: ${DEFAULT_FOREGROUND_SESSION_ID} (${FOREGROUND_PLANE_LABEL})`;
  } catch {
    target.textContent = `Sessions: ${DEFAULT_FOREGROUND_SESSION_ID} (${FOREGROUND_PLANE_LABEL})`;
  }
}
