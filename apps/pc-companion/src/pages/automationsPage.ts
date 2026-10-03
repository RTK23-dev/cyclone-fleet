import type { DesktopDevice, DesktopService, DeviceSessionDescriptor, FleetWsEvent, Layer2Status } from "../services/types.js";
import { CycloneOneSessionClient } from "../services/sessionClient.js";
import {
  armedGoalLabel,
  bindLayer2Status,
  canPauseLayer2,
  canReleaseLayer2,
  gatedLabel,
  generationLabel,
  LAYER2_DISPLAY_LABEL,
  LAYER2_EMPTY_COPY,
  LAYER2_STRIP_COPY,
  LAYER2_STRIP_TITLE,
  lockOwnerLabel,
} from "../core/layer2.js";
import {
  applySessionEvent,
  bindSessionTile,
  FOREGROUND_KIND,
  FOREGROUND_PLANE_LABEL,
  isSessionFabricEvent,
  isSessionKernelVd,
  jpegFocusTarget,
  markSessionInventory,
  MAX_HOT_BACKGROUND_ASK,
  SESSION_KERNEL_VD_KIND,
  SESSION_TILES_COPY,
  SESSION_TILES_TITLE,
  sessionDisplayLabel,
  sessionPlaneLabel,
  VD_PLANE_LABEL,
  type SessionTile,
} from "../core/sessionTiles.js";
import { button, el } from "../ui/dom.js";

export interface AutomationsPageHandle {
  element: HTMLElement;
  destroy(): void;
  applyFleetEvent?(event: FleetWsEvent): void;
}

type SessionRow = { device: DesktopDevice; session: DeviceSessionDescriptor; tile: SessionTile };

export function createAutomationsPage(
  devices: DesktopDevice[],
  onOpenControl: (device?: DesktopDevice) => void,
  service?: DesktopService,
): AutomationsPageHandle {
  const page = el("section", "page content-page automations-page cyclone-one-tasks");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("div", "task-kicker", "CYCLONE ONE · SESSION FABRIC"),
    el("h1", "page-title", "Active Tasks"),
    el("p", "page-subtitle", "Three planes: Foreground (session_id=default-foreground, display 0, human display), Session Kernel VD (named session_id, displayId>0, isolated virtual display — not Layer 2), and Layer 2 workspace (display 0 time-sliced lock in the strip)."),
  );
  header.append(heading);

  const explainer = el("article", "task-safety-card");
  explainer.append(
    el("div", "task-safety-icon", "1"),
    el("div", "task-safety-copy"),
  );
  const explainerCopy = explainer.lastElementChild as HTMLElement;
  explainerCopy.append(
    el("h2", "task-section-title", "Android stays in charge"),
    el("p", "task-muted", `Foreground is session_id=default-foreground on display 0, the human display. A Session Kernel VD is a named session_id on displayId>0 — an isolated virtual display, not Layer 2. Layer 2 workspace is the display-0 time-sliced lock in the strip, not a VD tile. Product hot-gates ${MAX_HOT_BACKGROUND_ASK} background Ask task; N≥2 tiles are inventory. Policy gates, payment confirmation, screen-lock pauses and Android session identity remain authoritative on the phone.`),
  );

  const composer = el("article", "task-composer-card");
  composer.append(el("h2", "task-section-title", "Start a Session Kernel VD"));
  const form = el("form", "task-start-form") as HTMLFormElement;
  const deviceSelect = el("select", "task-input") as HTMLSelectElement;
  deviceSelect.setAttribute("aria-label", "Phone");
  const eligible = devices.filter((device) => device.paired && device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED");
  if (eligible.length === 0) {
    const option = document.createElement("option");
    option.textContent = "No trusted phone ready";
    option.value = "";
    deviceSelect.append(option);
    deviceSelect.disabled = true;
  } else {
    for (const device of eligible) {
      const option = document.createElement("option");
      option.value = device.id;
      option.textContent = `${device.name} · ${device.connectionLabel}`;
      deviceSelect.append(option);
    }
  }
  const packageInput = el("input", "task-input") as HTMLInputElement;
  packageInput.type = "text";
  packageInput.placeholder = "Android package, e.g. com.android.chrome";
  packageInput.autocomplete = "off";
  packageInput.spellcheck = false;
  packageInput.setAttribute("aria-label", "Android package name");
  const startButton = button("Start VD session", "button primary task-start-button");
  startButton.type = "submit";
  startButton.disabled = eligible.length === 0;
  form.append(deviceSelect, packageInput, startButton);
  const formStatus = el("div", "task-form-status");
  composer.append(form, formStatus);

  const layer2Section = el("section", "layer2-strip");
  const layer2Heading = el("div", "layer2-heading");
  layer2Heading.append(
    el("h2", "task-section-title", LAYER2_STRIP_TITLE),
    el("p", "task-muted", LAYER2_STRIP_COPY),
  );
  const layer2Host = el("div", "layer2-device-list");
  layer2Section.append(layer2Heading, layer2Host);

  const toolbar = el("div", "task-list-toolbar");
  toolbar.append(
    el("div", "task-list-heading-wrap"),
    button("Refresh", "button secondary compact task-refresh"),
  );
  const toolbarHeading = toolbar.firstElementChild as HTMLElement;
  toolbarHeading.append(
    el("h2", "task-section-title", SESSION_TILES_TITLE),
    el("p", "task-muted", SESSION_TILES_COPY),
  );
  const refreshButton = toolbar.lastElementChild as HTMLButtonElement;
  const list = el("div", "task-session-list");
  const footer = el("div", "task-page-footer");
  const openPhone = button("Open normal phone control", "button secondary compact");
  openPhone.addEventListener("click", () => onOpenControl(eligible[0]));
  footer.append(openPhone, el("span", "task-muted", "Foreground phone control remains separate from Session Kernel VD tiles and from the Layer 2 strip."));

  page.append(header, explainer, composer, layer2Section, toolbar, list, footer);

  let disposed = false;
  let client: CycloneOneSessionClient | null = null;
  let connecting: Promise<CycloneOneSessionClient> | null = null;
  let previewUrl: string | null = null;
  let tiles: SessionTile[] = [];
  const deviceById = new Map(eligible.map((device) => [device.id, device]));

  const getClient = async (): Promise<CycloneOneSessionClient> => {
    if (client) return client;
    if (!connecting) connecting = CycloneOneSessionClient.connect();
    client = await connecting;
    return client;
  };

  const listSessions = async (deviceId: string) => {
    if (service?.listDeviceSessions) return service.listDeviceSessions(deviceId);
    return (await getClient()).list(deviceId);
  };

  const startSession = async (deviceId: string, packageName: string) => {
    if (service?.startDeviceSession) return service.startDeviceSession(deviceId, packageName);
    return (await getClient()).start(deviceId, packageName);
  };

  const lifecycle = async (deviceId: string, sessionId: string, operation: "pause" | "resume" | "handoff" | "stop") => {
    if (operation === "pause" && service?.pauseDeviceSession) return service.pauseDeviceSession(deviceId, sessionId);
    if (operation === "resume" && service?.resumeDeviceSession) return service.resumeDeviceSession(deviceId, sessionId);
    if (operation === "handoff" && service?.handoffDeviceSession) return service.handoffDeviceSession(deviceId, sessionId);
    if (operation === "stop" && service?.stopDeviceSession) return service.stopDeviceSession(deviceId, sessionId);
    const api = await getClient();
    if (operation === "pause") return api.pause(deviceId, sessionId);
    if (operation === "resume") return api.resume(deviceId, sessionId);
    if (operation === "handoff") return api.handoff(deviceId, sessionId);
    return api.stop(deviceId, sessionId);
  };

  const snapshotSession = async (deviceId: string, sessionId: string) => {
    if (service?.snapshotDeviceSession) return service.snapshotDeviceSession(deviceId, sessionId);
    return (await getClient()).snapshot(deviceId, sessionId);
  };

  const sendHandoff = async (deviceId: string, sessionId: string, kind: "yield_ai" | "take_human") => {
    if (service?.sendSessionControl) return service.sendSessionControl(deviceId, sessionId, kind);
    if (service?.sendControl) return service.sendControl(deviceId, { type: kind, sessionId });
    throw new Error("Session control is unavailable");
  };

  const listLayer2 = async (deviceId: string) => {
    if (!service?.listLayer2Workspaces) throw new Error("Layer 2 workspaces need a DesktopService");
    return bindLayer2Status(deviceId, await service.listLayer2Workspaces(deviceId));
  };

  const runLayer2 = async (deviceId: string, operation: "pause" | "release") => {
    if (!service?.layer2Workspace) throw new Error("Layer 2 workspaces need a DesktopService");
    return bindLayer2Status(deviceId, await service.layer2Workspace(deviceId, operation));
  };

  const renderEmpty = (message: string): void => {
    list.replaceChildren(el("div", "task-empty", message));
  };

  const updateSession = async (device: DesktopDevice, session: DeviceSessionDescriptor, operation: "pause" | "resume" | "handoff" | "stop"): Promise<void> => {
    await lifecycle(device.id, session.sessionId, operation);
    if (!disposed) await refresh();
  };

  const showJpegFocus = async (device: DesktopDevice, tile: SessionTile, session: DeviceSessionDescriptor, container: HTMLElement, trigger: HTMLButtonElement): Promise<void> => {
    trigger.disabled = true;
    trigger.textContent = "Loading…";
    try {
      const target = jpegFocusTarget(tile);
      const vd = isSessionKernelVd(tile) || tile.kind === SESSION_KERNEL_VD_KIND || tile.kind !== FOREGROUND_KIND;
      if (vd && !(target.displayId > 0)) {
        throw new Error("SESSION_DISPLAY_MISMATCH");
      }
      const frame = await snapshotSession(device.id, target.sessionId);
      if (vd && !(frame.displayId > 0)) {
        throw new Error("SESSION_DISPLAY_MISMATCH");
      }
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = frame.url.startsWith("blob:") ? frame.url : null;
      const image = document.createElement("img");
      image.className = "task-preview-image task-jpeg-focus";
      image.src = frame.url;
      image.alt = `${sessionPlaneLabel(tile)} JPEG for ${session.sessionId} on Android display ${frame.displayId}`;
      image.dataset.sessionId = session.sessionId;
      image.dataset.displayId = String(frame.displayId);
      image.dataset.plane = tile.plane || (isSessionKernelVd(tile) ? SESSION_KERNEL_VD_KIND : tile.kind);
      const meta = el("div", "task-preview-meta", `Exact Android display ${frame.displayId} · session_id ${session.sessionId} · foreground substitution: false`);
      container.replaceChildren(image, meta);
    } catch (error) {
      container.replaceChildren(el("div", "task-inline-error", friendlyError(error)));
    } finally {
      trigger.disabled = false;
      trigger.textContent = "Focus JPEG";
    }
  };

  const rowsFromTiles = (): SessionRow[] => {
    const rows: SessionRow[] = [];
    for (const tile of tiles) {
      const device = deviceById.get(tile.deviceId) ?? eligible.find((item) => item.id === tile.deviceId);
      if (!device) continue;
      rows.push({
        device,
        tile,
        session: {
          sessionId: tile.sessionId,
          displayId: tile.displayId,
          targetPackage: tile.targetPackage,
          backend: tile.backend,
          inputOwner: tile.inputOwner,
          state: tile.state,
          executable: tile.executable,
          executionGeneration: tile.executionGeneration,
          frameHealthy: tile.frameHealthy,
        },
      });
    }
    return rows;
  };

  const renderRows = (rows: SessionRow[]): void => {
    if (rows.length === 0) {
      renderEmpty(eligible.length ? "No phone sessions are visible yet." : "Trust a phone in Connections to start a Cyclone One task.");
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const { device, session, tile } of rows) {
      const foreground = tile.kind === FOREGROUND_KIND;
      const vd = isSessionKernelVd(tile) || tile.kind === SESSION_KERNEL_VD_KIND || !foreground;
      const kindName = foreground ? FOREGROUND_KIND : SESSION_KERNEL_VD_KIND;
      const card = el("article", `task-session-card task-kind-${tile.kind} task-kind-${kindName}${tile.inventory ? " task-inventory" : ""}`);
      card.dataset.sessionId = tile.sessionId;
      if (tile.displayId != null) card.dataset.displayId = String(tile.displayId);
      card.dataset.kind = tile.kind;
      card.dataset.plane = tile.plane || kindName;
      const top = el("div", "task-session-top");
      const title = el("div", "task-session-title-wrap");
      const packageName = foreground
        ? `${FOREGROUND_PLANE_LABEL} (session_id=default-foreground, display 0)`
        : session.targetPackage || VD_PLANE_LABEL;
      title.append(
        el("div", "task-session-package", packageName),
        el("div", "task-session-device", `${device.name} · ${sessionPlaneLabel(tile)} · display ${sessionDisplayLabel(tile)} · ${tile.sessionId}`),
      );
      const badges = el("div", "task-session-badges");
      badges.append(
        el("span", `task-plane task-kind-${kindName}`, sessionPlaneLabel(tile)),
        ownerBadge(session.inputOwner),
        el("span", `task-state task-state-${normalizeState(session.state)}`, humanState(session.state)),
      );
      top.append(title, badges);

      const facts = el("div", "task-facts");
      facts.append(
        fact("Session", session.sessionId),
        fact("Display", sessionDisplayLabel(tile)),
        fact("Owner", ownerText(session.inputOwner)),
        fact("Plane", sessionPlaneLabel(tile)),
      );

      const actions = el("div", "task-session-actions");
      const focusJpeg = button("Focus JPEG", "button secondary compact");
      const pause = button("Pause", "button secondary compact");
      const resume = button("Resume", "button secondary compact");
      const takeHuman = button("Take control", "button secondary compact");
      const giveAi = button("Give to AI", "button secondary compact");
      const stop = button("Stop", "button danger compact");
      focusJpeg.disabled = !vd || tile.displayId == null || tile.displayId <= 0;
      pause.disabled = !vd || session.state !== "RUNNING";
      resume.disabled = !vd || session.state !== "PAUSED";
      takeHuman.disabled = session.state === "STOPPED";
      giveAi.disabled = session.state === "STOPPED";
      stop.disabled = !vd || session.state === "STOPPED";
      actions.append(focusJpeg, pause, resume, takeHuman, giveAi, stop);

      const previewHost = el("div", "task-preview-host");
      focusJpeg.addEventListener("click", () => void showJpegFocus(device, tile, session, previewHost, focusJpeg));
      for (const [node, operation] of [
        [pause, "pause"],
        [resume, "resume"],
        [stop, "stop"],
      ] as Array<[HTMLButtonElement, "pause" | "resume" | "stop"]>) {
        node.addEventListener("click", () => {
          node.disabled = true;
          void updateSession(device, session, operation).catch((error) => {
            if (!disposed) previewHost.replaceChildren(el("div", "task-inline-error", friendlyError(error)));
          }).finally(() => { if (!disposed) node.disabled = false; });
        });
      }
      for (const [node, kind] of [
        [takeHuman, "take_human"],
        [giveAi, "yield_ai"],
      ] as Array<[HTMLButtonElement, "take_human" | "yield_ai"]>) {
        node.addEventListener("click", () => {
          node.disabled = true;
          void sendHandoff(device.id, session.sessionId, kind)
            .then((result) => {
              if (result.ok === false) throw new Error(result.verification || "Session control failed");
              if (!disposed) return refresh();
            })
            .catch((error) => {
              if (!disposed) previewHost.replaceChildren(el("div", "task-inline-error", friendlyError(error)));
            })
            .finally(() => { if (!disposed) node.disabled = false; });
        });
      }

      card.append(top, facts, actions, previewHost);
      fragment.append(card);
    }
    list.replaceChildren(fragment);
  };

  const renderTiles = (): void => {
    renderRows(rowsFromTiles());
  };

  const applyEvent = (event: FleetWsEvent): void => {
    if (!isSessionFabricEvent(event)) return;
    tiles = applySessionEvent(tiles, event);
    if (!disposed) renderTiles();
  };

  const renderLayer2 = (device: DesktopDevice, status: Layer2Status): HTMLElement => {
    const card = el("article", "layer2-card");
    card.dataset.plane = "layer2";
    card.dataset.displayId = "0";
    const top = el("div", "layer2-card-top");
    const title = el("div", "layer2-card-title-wrap");
    title.append(
      el("div", "layer2-card-title", device.name),
      el("div", "layer2-card-meta", LAYER2_DISPLAY_LABEL),
    );
    top.append(title, el("span", `layer2-gate${status.gated ? " layer2-gate-pending" : ""}`, gatedLabel(status)));

    const facts = el("div", "layer2-facts");
    facts.append(
      layer2Fact("Lock owner", lockOwnerLabel(status).replace(/^Lock owner: /, "")),
      layer2Fact("Generation", generationLabel(status).replace(/^Generation: /, "")),
      layer2Fact("Armed goal", armedGoalLabel(status).replace(/^Armed goal: /, "")),
      layer2Fact("Display", "0 · time-sliced lock"),
    );

    const rows = el("div", "layer2-workspace-list");
    if (status.workspaces.length === 0) {
      rows.append(el("div", "layer2-empty", LAYER2_EMPTY_COPY));
    } else {
      for (const workspace of status.workspaces) {
        const row = el("div", "layer2-workspace-row");
        row.append(
          el("span", "layer2-workspace-label", workspace.label),
          el("span", "layer2-workspace-package", workspace.appPackage),
          el("span", "layer2-workspace-user", `user ${workspace.androidUserId}`),
          el("span", "layer2-workspace-state", workspace.state),
        );
        rows.append(row);
      }
    }

    const actions = el("div", "layer2-actions");
    const pause = button("Pause", "button secondary compact");
    const release = button("Release", "button secondary compact");
    pause.disabled = !canPauseLayer2(status);
    release.disabled = !canReleaseLayer2(status);
    const errorHost = el("div", "layer2-inline-error");
    const mutate = (operation: "pause" | "release", node: HTMLButtonElement) => {
      node.disabled = true;
      void runLayer2(device.id, operation)
        .then(() => { if (!disposed) return refreshLayer2(); })
        .catch((error) => {
          if (disposed) return;
          errorHost.textContent = friendlyError(error);
          node.disabled = operation === "pause" ? !canPauseLayer2(status) : !canReleaseLayer2(status);
        });
    };
    pause.addEventListener("click", () => mutate("pause", pause));
    release.addEventListener("click", () => mutate("release", release));
    actions.append(pause, release);
    card.append(top, facts, rows, actions, errorHost);
    return card;
  };

  const refreshLayer2 = async (): Promise<void> => {
    if (!service?.listLayer2Workspaces) {
      layer2Host.replaceChildren(el("div", "layer2-empty", "Layer 2 workspaces need a connected phone on mobile 4.0.3+."));
      return;
    }
    if (eligible.length === 0) {
      layer2Host.replaceChildren(el("div", "layer2-empty", "Trust a phone in Connections to register Layer 2 profiles."));
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const device of eligible) {
      try {
        fragment.append(renderLayer2(device, await listLayer2(device.id)));
      } catch (error) {
        const failed = el("div", "layer2-inline-error", friendlyError(error));
        fragment.append(failed);
      }
    }
    if (!disposed) layer2Host.replaceChildren(fragment.childNodes.length ? fragment : el("div", "layer2-empty", LAYER2_EMPTY_COPY));
  };

  const refresh = async (): Promise<void> => {
    refreshButton.disabled = true;
    const prior = refreshButton.textContent;
    refreshButton.textContent = "Refreshing…";
    try {
      const next: SessionTile[] = [];
      for (const device of eligible) {
        try {
          const result = await listSessions(device.id);
          for (const session of result.sessions) {
            next.push(bindSessionTile(device.id, session));
          }
        } catch {
          // One unavailable phone must not hide healthy sessions on another phone.
        }
      }
      tiles = markSessionInventory(next);
      if (!disposed) renderTiles();
      await refreshLayer2();
    } catch (error) {
      if (!disposed) renderEmpty(friendlyError(error));
    } finally {
      refreshButton.disabled = false;
      refreshButton.textContent = prior || "Refresh";
    }
  };

  refreshButton.addEventListener("click", () => void refresh());
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const deviceId = deviceSelect.value;
    const packageName = packageInput.value.trim();
    if (!deviceId || !packageName) {
      formStatus.textContent = "Choose a trusted phone and enter an Android package name.";
      return;
    }
    startButton.disabled = true;
    formStatus.textContent = "Asking Android to create a Session Kernel VD…";
    void startSession(deviceId, packageName)
      .then(async (result) => {
        if (disposed) return;
        const started = result.session.targetPackage || packageName;
        const displayId = result.session.displayId;
        formStatus.textContent = displayId != null && displayId > 0
          ? `Started ${started} as ${result.session.sessionId} on Session Kernel VD display ${displayId}.`
          : `Started ${started} as ${result.session.sessionId} as a Session Kernel VD (isolated virtual display, not display 0).`;
        packageInput.value = "";
        await refresh();
      })
      .catch((error) => {
        if (!disposed) formStatus.textContent = friendlyError(error);
      })
      .finally(() => { if (!disposed) startButton.disabled = eligible.length === 0; });
  });

  const unwatch = service?.watchFleet((event) => {
    if (event) applyEvent(event);
  });

  void refresh();
  void refreshLayer2();

  return {
    element: page,
    destroy: () => {
      disposed = true;
      unwatch?.();
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = null;
    },
    applyFleetEvent: applyEvent,
  };
}

function fact(label: string, value: string): HTMLElement {
  const node = el("div", "task-fact");
  const valueNode = el("span", "task-fact-value", value);
  const owner = label === "Owner" ? ownerClass(value) : "";
  if (owner) valueNode.classList.add(owner);
  node.append(el("span", "task-fact-label", label), valueNode);
  return node;
}

function ownerText(value?: string): string {
  const owner = String(value || "").toUpperCase();
  if (owner === "AI" || owner === "HUMAN") return owner;
  return owner || "—";
}

function ownerClass(value?: string): string {
  const owner = String(value || "").toUpperCase();
  if (owner === "AI") return "task-owner-ai";
  if (owner === "HUMAN") return "task-owner-human";
  return "";
}

function ownerBadge(value?: string): HTMLElement {
  const text = ownerText(value);
  return el("span", `task-owner-badge ${ownerClass(text)}`.trim(), text);
}

function layer2Fact(label: string, value: string): HTMLElement {
  const node = el("div", "layer2-fact");
  node.append(el("span", "layer2-fact-label", label), el("span", "layer2-fact-value", value));
  return node;
}

function humanState(value: string): string {
  if (value === "WAITING_FOR_CONFIRMATION") return "Needs you";
  if (value === "FOREGROUND") return "Foreground";
  if (value === "RUNNING") return "Running";
  if (value === "PAUSED") return "Paused";
  if (value === "ATTENTION") return "Attention";
  if (value === "STOPPED") return "Stopped";
  return value.replaceAll("_", " ").toLowerCase();
}

function normalizeState(value: string): string {
  return value.toLowerCase().replaceAll("_", "-");
}

function friendlyError(error: unknown): string {
  const message = error instanceof Error ? error.message : "Cyclone One task request failed.";
  if (/shizuku|background_mode_unavailable/i.test(message)) return "Session Kernel VD needs Shizuku installed, running and authorized on this phone.";
  if (/locked|phone_locked/i.test(message)) return "Unlock the phone, then resume this task.";
  if (/human_has_control/i.test(message)) return "Companion still owns input. Click Give control to AI, then retry.";
  if (/session_display_mismatch|rewrite to display 0/i.test(message)) return "Named Session Kernel VD JPEG refused display 0.";
  if (/policy|confirm|foreground_required/i.test(message)) return "Android paused autonomous work because this step needs you on the phone.";
  if (/trust|401|403|auth/i.test(message)) return "This phone needs a fresh trusted Cyclone connection.";
  return message.slice(0, 220);
}
