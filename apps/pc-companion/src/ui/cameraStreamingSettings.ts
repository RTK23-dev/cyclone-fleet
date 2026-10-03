import type { DesktopDevice, DesktopService } from "../services/types.js";
import {
  CameraStreamingClient,
  type CameraFacing,
  type CameraFps,
  type CameraQuality,
  type CameraStreamStatus,
} from "../services/cameraStreamingClient.js";

const CARD_ID = "cyclone-native-camera-streaming";
const MAX_VIEWERS = 5;
const PREFS_KEY = "cyclone.camera-streaming.v155";

type CameraPreferences = {
  facing: CameraFacing;
  quality: CameraQuality;
  fps: CameraFps;
};

/**
 * Settings is route-mounted by the main app. This adapter only detects that route boundary; the
 * camera card itself owns a stable lifecycle and never rebuilds its controls during status polls.
 */
export function mountCameraStreamingSettings(service: DesktopService): () => void {
  let destroyed = false;
  let card: CameraCardHandle | null = null;
  let mounting = false;

  const reconcile = async (): Promise<void> => {
    if (destroyed || mounting) return;
    const grid = document.querySelector<HTMLElement>(".settings-grid");
    if (!grid) {
      if (card && !card.element.isConnected) {
        card.destroy();
        card = null;
      }
      return;
    }
    if (card?.element.isConnected) return;

    mounting = true;
    try {
      card?.destroy();
      card = createCameraCard(service, await safeDevices(service));
      grid.prepend(card.element);
      void card.start();
    } finally {
      mounting = false;
    }
  };

  // Observe route/page replacement only. Reconciliation is idempotent, and once mounted no card
  // DOM is recreated by this observer or by the live-status loop.
  const observer = new MutationObserver(() => { void reconcile(); });
  const root = document.getElementById("app") ?? document.body;
  observer.observe(root, { childList: true, subtree: true });
  void reconcile();

  return () => {
    destroyed = true;
    observer.disconnect();
    card?.destroy();
    card = null;
  };
}

interface CameraCardHandle {
  element: HTMLElement;
  start(): Promise<void>;
  destroy(): void;
}

function createCameraCard(service: DesktopService, initialDevices: DesktopDevice[]): CameraCardHandle {
  const client = new CameraStreamingClient();
  const prefs = readPreferences();
  const card = element("article", "setting-card camera-stream-card");
  card.id = CARD_ID;

  const hero = element("div", "camera-stream-hero");
  const icon = element("div", "camera-stream-icon", "◉");
  icon.setAttribute("aria-hidden", "true");
  const heading = element("div", "camera-stream-heading");
  const titleRow = element("div", "camera-stream-title-row");
  titleRow.append(element("div", "setting-label", "Streaming camera"));
  const livePill = element("span", "camera-stream-pill is-idle", "Ready");
  livePill.setAttribute("role", "status");
  titleRow.append(livePill);
  heading.append(
    titleRow,
    element(
      "p",
      "setting-copy camera-stream-lead",
      "Send one phone camera to as many as five Cyclone phones from one shared encode. The source sensor shape is never cropped or stretched to fit a receiving screen.",
    ),
  );
  hero.append(icon, heading);

  const form = element("div", "camera-stream-form");
  const source = document.createElement("select");
  source.className = "camera-stream-select";
  source.setAttribute("aria-label", "Camera source phone");

  const facing = document.createElement("select");
  facing.className = "camera-stream-select";
  facing.setAttribute("aria-label", "Camera facing");
  facing.append(option("back", "Back camera"), option("front", "Front camera"));
  facing.value = prefs.facing;

  const quality = document.createElement("select");
  quality.className = "camera-stream-select";
  quality.setAttribute("aria-label", "Camera quality");
  quality.append(
    option("high", "High · up to 1920"),
    option("native", "Native sensor · greatest size"),
  );
  quality.value = prefs.quality;

  const fps = document.createElement("select");
  fps.className = "camera-stream-select";
  fps.setAttribute("aria-label", "Camera frame rate");
  fps.append(option("30", "30 fps · recommended"), option("60", "60 fps · if supported"));
  fps.value = String(prefs.fps);

  const controls = element("div", "camera-stream-control-grid");
  controls.append(
    field("Source phone", source, "The phone whose physical camera will be shared."),
    field("Camera", facing),
    field("Quality", quality, "High limits bandwidth. Native asks for the greatest supported size matching the sensor."),
    field("Frame rate", fps, "60 fps depends on the source camera's declared support."),
  );

  const invariant = element("div", "camera-aspect-lock");
  invariant.append(
    element("div", "camera-aspect-check", "✓"),
    copyStack(
      "Sensor aspect protected",
      "Always on · a 4:3 source remains 4:3 and a 3:4 source remains 3:4 on every viewer.",
    ),
    element("span", "camera-lock-badge", "LOCKED"),
  );

  const targetSection = element("div", "camera-target-section");
  const targetTop = element("div", "camera-target-top");
  const targetHeading = element("div", "camera-target-heading");
  targetHeading.append(
    element("span", "camera-target-title", "Receiving phones"),
    element("span", "camera-target-help", "Choose up to five. The source phone is excluded automatically."),
  );
  const targetCount = element("span", "camera-target-count", `0 / ${MAX_VIEWERS}`);
  targetTop.append(targetHeading, targetCount);
  const targetList = element("div", "camera-target-list");
  targetList.setAttribute("role", "group");
  targetList.setAttribute("aria-label", "Receiving Cyclone phones");
  targetSection.append(targetTop, targetList);

  const health = element("div", "camera-stream-health");
  const healthDot = element("span", "camera-health-dot");
  const healthCopy = element("div", "camera-health-copy");
  const statusMain = element("strong", "", "Choose a source and receiving phones");
  const statusDetail = element("span", "", "One encode · isolated viewers · USB loopback transport");
  healthCopy.append(statusMain, statusDetail);
  health.append(healthDot, healthCopy);

  const failures = element("div", "camera-stream-failures");
  failures.hidden = true;

  const actions = element("div", "camera-stream-actions");
  const startButton = document.createElement("button");
  startButton.className = "button primary camera-start-button";
  startButton.type = "button";
  startButton.textContent = "Start stream";
  const stopButton = document.createElement("button");
  stopButton.className = "button secondary camera-stop-button";
  stopButton.type = "button";
  stopButton.textContent = "Stop stream";
  stopButton.hidden = true;
  actions.append(startButton, stopButton);

  form.append(controls, invariant, targetSection, health, failures, actions);
  card.append(hero, form);

  let active = true;
  let busy = false;
  let devices = initialDevices;
  let streamStatus: CameraStreamStatus | null = null;
  let statusTimer: number | null = null;
  let fleetTimer: number | null = null;
  let deviceSignature = "";
  let targetsUiKey = "";
  const selectedTargets = new Set<string>();

  const usableDevices = (): DesktopDevice[] => devices.filter(isUsablePhysicalDevice);

  const updateSources = (): void => {
    const candidates = usableDevices();
    const previous = source.value;
    const nextSignature = candidates.map((device) => `${device.id}:${device.state}:${device.connectionLabel}`).join("|");
    if (nextSignature === deviceSignature && source.options.length > 0) return;
    deviceSignature = nextSignature;
    source.replaceChildren();
    if (candidates.length === 0) {
      source.append(option("", "Connect an Android phone"));
    } else {
      for (const device of candidates) source.append(option(device.id, device.name));
    }
    if (candidates.some((device) => device.id === previous)) source.value = previous;
    else if (streamStatus?.sourceDeviceId && candidates.some((device) => device.id === streamStatus?.sourceDeviceId)) {
      source.value = streamStatus.sourceDeviceId;
    }
    pruneTargets();
    renderTargets(true);
  };

  const pruneTargets = (): void => {
    const valid = new Set(usableDevices().filter((device) => device.id !== source.value).map((device) => device.id));
    for (const deviceId of selectedTargets) if (!valid.has(deviceId)) selectedTargets.delete(deviceId);
  };

  const renderTargets = (force = false): void => {
    const candidates = usableDevices().filter((device) => device.id !== source.value);
    const connected = new Set(streamStatus?.connectedViewerIds ?? []);
    const failed = new Map((streamStatus?.launchFailures ?? []).map((item) => [item.deviceId, item.error]));
    const key = [
      source.value,
      streamStatus?.active ? "active" : "idle",
      candidates.map((device) => `${device.id}:${device.state}:${device.connectionLabel}`).join("|"),
      Array.from(selectedTargets).sort().join("|"),
      Array.from(connected).sort().join("|"),
      Array.from(failed.keys()).sort().join("|"),
    ].join("::");
    if (!force && key === targetsUiKey) return;
    targetsUiKey = key;
    targetList.replaceChildren();

    if (candidates.length === 0) {
      targetList.append(element("div", "camera-target-empty", "Connect another Cyclone phone to receive this camera."));
    }

    for (const device of candidates) {
      const row = element("label", "camera-target-row");
      if (connected.has(device.id)) row.classList.add("is-connected");
      if (failed.has(device.id)) row.classList.add("is-failed");
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = selectedTargets.has(device.id);
      checkbox.disabled = Boolean(streamStatus?.active) || busy;
      checkbox.addEventListener("change", () => {
        if (checkbox.checked && selectedTargets.size >= MAX_VIEWERS) {
          checkbox.checked = false;
          announce(`You can stream to up to ${MAX_VIEWERS} phones at once.`, "attention");
          return;
        }
        if (checkbox.checked) selectedTargets.add(device.id);
        else selectedTargets.delete(device.id);
        targetsUiKey = "";
        renderTargets();
        updateControls();
      });

      const identity = element("span", "camera-target-identity");
      identity.append(
        element("span", "camera-target-name", device.name),
        element("span", "camera-target-state", targetStateCopy(device, connected.has(device.id), failed.has(device.id))),
      );
      const stateMark = element("span", "camera-target-mark", connected.has(device.id) ? "LIVE" : failed.has(device.id) ? "ISSUE" : "");
      row.append(checkbox, identity, stateMark);
      targetList.append(row);
    }
    targetCount.textContent = `${selectedTargets.size} / ${MAX_VIEWERS}`;
  };

  const updateControls = (): void => {
    const streaming = Boolean(streamStatus?.active);
    source.disabled = busy || streaming || usableDevices().length === 0;
    facing.disabled = busy || streaming;
    quality.disabled = busy || streaming;
    fps.disabled = busy || streaming;
    startButton.hidden = streaming;
    stopButton.hidden = !streaming;
    startButton.disabled = busy || service.mode !== "real" || !source.value || selectedTargets.size === 0;
    stopButton.disabled = busy || service.mode !== "real";
    startButton.textContent = selectedTargets.size > 1 ? `Stream to ${selectedTargets.size} phones` : "Start stream";
    targetCount.textContent = `${selectedTargets.size} / ${MAX_VIEWERS}`;
  };

  const applyStatus = (next: CameraStreamStatus): void => {
    streamStatus = next;
    if (next.active) {
      if (next.sourceDeviceId) source.value = next.sourceDeviceId;
      selectedTargets.clear();
      for (const id of next.targetDeviceIds ?? []) selectedTargets.add(id);

      const state = next.stream?.state ?? "STARTING";
      const width = Number(next.stream?.width ?? 0);
      const height = Number(next.stream?.height ?? 0);
      const connected = Number(next.viewerCount ?? 0);
      const intended = Math.max(1, Number(next.targetDeviceIds?.length ?? 0));
      const isLive = state === "LIVE";
      const degraded = Boolean(next.degraded) || (isLive && connected < intended);

      livePill.className = `camera-stream-pill ${degraded ? "is-attention" : isLive ? "is-live" : "is-starting"}`;
      livePill.textContent = degraded ? "DEGRADED" : isLive ? `LIVE · ${connected}/${intended}` : friendlyStreamState(state);
      health.dataset.kind = degraded ? "attention" : isLive ? "live" : "starting";

      const dimensions = width > 0 && height > 0 ? `${width}×${height} · ${ratioLabel(width, height)}` : "Waiting for source dimensions";
      statusMain.textContent = dimensions;
      statusDetail.textContent = `${next.stream?.quality === "native" ? "Native sensor" : "High"} · ${next.stream?.cameraFacing === "front" ? "front" : "back"} camera · ${next.stream?.targetFps ?? 30} fps · ${connected}/${intended} viewers connected`;
      renderFailures(next);
    } else {
      livePill.className = "camera-stream-pill is-idle";
      livePill.textContent = "Ready";
      health.dataset.kind = "idle";
      statusMain.textContent = "Choose a source and receiving phones";
      statusDetail.textContent = "One encode · isolated viewers · USB loopback transport";
      failures.hidden = true;
      failures.replaceChildren();
    }
    targetsUiKey = "";
    renderTargets();
    updateControls();
  };

  const renderFailures = (value: CameraStreamStatus): void => {
    const items = value.launchFailures ?? [];
    failures.replaceChildren();
    failures.hidden = items.length === 0;
    if (items.length === 0) return;
    failures.append(element("strong", "", `${items.length} receiving phone${items.length === 1 ? "" : "s"} could not start`));
    for (const item of items) {
      const device = devices.find((candidate) => candidate.id === item.deviceId);
      failures.append(element("span", "", `${device?.name ?? "A phone"}: ${item.error}`));
    }
  };

  const announce = (message: string, kind: "idle" | "starting" | "live" | "attention" | "error" = "idle"): void => {
    health.dataset.kind = kind;
    statusMain.textContent = message;
  };

  const refreshStatus = async (): Promise<void> => {
    if (!active || service.mode !== "real" || busy) return;
    try {
      applyStatus(await client.status());
    } catch {
      livePill.className = "camera-stream-pill is-attention";
      livePill.textContent = "Unavailable";
      announce("Camera streaming service is unavailable", "error");
      statusDetail.textContent = "Restart Cyclone One if the local gateway does not recover.";
    }
  };

  const refreshFleet = async (): Promise<void> => {
    if (!active) return;
    const next = await safeDevices(service);
    if (!active) return;
    devices = next;
    updateSources();
    renderTargets();
    updateControls();
  };

  source.addEventListener("change", () => {
    pruneTargets();
    targetsUiKey = "";
    renderTargets();
    updateControls();
  });
  for (const control of [facing, quality, fps]) {
    control.addEventListener("change", () => writePreferences({
      facing: facing.value as CameraFacing,
      quality: quality.value as CameraQuality,
      fps: Number(fps.value) as CameraFps,
    }));
  }

  startButton.addEventListener("click", async () => {
    if (busy || !source.value || selectedTargets.size === 0 || service.mode !== "real") return;
    busy = true;
    updateControls();
    livePill.className = "camera-stream-pill is-starting";
    livePill.textContent = "Starting";
    announce("Opening the camera and receiving phones…", "starting");
    statusDetail.textContent = "Cyclone is creating one source encode and isolated viewer tunnels.";
    try {
      applyStatus(await client.start({
        sourceDeviceId: source.value,
        targetDeviceIds: Array.from(selectedTargets).slice(0, MAX_VIEWERS),
        facing: facing.value as CameraFacing,
        quality: quality.value as CameraQuality,
        fps: Number(fps.value) as CameraFps,
      }));
    } catch (error) {
      livePill.className = "camera-stream-pill is-attention";
      livePill.textContent = "Needs attention";
      announce(error instanceof Error ? error.message : "Could not start the camera stream.", "error");
      statusDetail.textContent = "Nothing was left streaming if no receiving phone could be opened.";
    } finally {
      busy = false;
      updateControls();
      renderTargets(true);
    }
  });

  stopButton.addEventListener("click", async () => {
    if (busy || service.mode !== "real") return;
    busy = true;
    updateControls();
    announce("Stopping camera stream…", "starting");
    try {
      applyStatus(await client.stop());
    } catch (error) {
      announce(error instanceof Error ? error.message : "Could not stop the camera stream.", "error");
    } finally {
      busy = false;
      updateControls();
    }
  });

  updateSources();
  renderTargets(true);
  updateControls();

  return {
    element: card,
    async start(): Promise<void> {
      if (service.mode !== "real") {
        livePill.textContent = "Packaged app";
        announce("Camera streaming is available in the packaged Cyclone One build.");
        updateControls();
        return;
      }
      await refreshStatus();
      if (!active) return;
      statusTimer = window.setInterval(() => { void refreshStatus(); }, 1000);
      fleetTimer = window.setInterval(() => { void refreshFleet(); }, 5000);
    },
    destroy(): void {
      active = false;
      if (statusTimer != null) window.clearInterval(statusTimer);
      if (fleetTimer != null) window.clearInterval(fleetTimer);
      statusTimer = null;
      fleetTimer = null;
      card.remove();
    },
  };
}

function field(label: string, input: HTMLElement, hint?: string): HTMLElement {
  const wrap = element("label", "camera-stream-field");
  wrap.append(element("span", "camera-field-label", label), input);
  if (hint) wrap.append(element("small", "camera-field-hint", hint));
  return wrap;
}

function copyStack(title: string, copy: string): HTMLElement {
  const wrap = element("div", "camera-copy-stack");
  wrap.append(element("strong", "", title), element("span", "", copy));
  return wrap;
}

function option(value: string, label: string): HTMLOptionElement {
  const node = document.createElement("option");
  node.value = value;
  node.textContent = label;
  return node;
}

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className = "", text = ""): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function isUsablePhysicalDevice(device: DesktopDevice): boolean {
  return device.source !== "VIRTUAL" && device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED";
}

function targetStateCopy(device: DesktopDevice, connected: boolean, failed: boolean): string {
  if (failed) return "Could not open camera viewer";
  if (connected) return "Receiving live camera";
  return device.connectionLabel || "Ready";
}

function friendlyStreamState(state: string): string {
  switch (state) {
    case "WAITING_KEYFRAME": return "Syncing";
    case "RECONNECTING": return "Reconnecting";
    case "SLEEPING": return "Source sleeping";
    case "UNAVAILABLE": return "Unavailable";
    default: return "Starting";
  }
}

function ratioLabel(width: number, height: number): string {
  const divisor = gcd(width, height);
  return `${Math.round(width / divisor)}:${Math.round(height / divisor)}`;
}

function gcd(a: number, b: number): number {
  let x = Math.abs(Math.round(a));
  let y = Math.abs(Math.round(b));
  while (y) [x, y] = [y, x % y];
  return Math.max(1, x);
}

async function safeDevices(service: DesktopService): Promise<DesktopDevice[]> {
  try { return await service.listDevices(); } catch { return []; }
}

function readPreferences(): CameraPreferences {
  const fallback: CameraPreferences = { facing: "back", quality: "high", fps: 30 };
  try {
    const raw = JSON.parse(window.localStorage.getItem(PREFS_KEY) || "{}") as Partial<CameraPreferences>;
    return {
      facing: raw.facing === "front" ? "front" : "back",
      quality: raw.quality === "native" ? "native" : "high",
      fps: raw.fps === 60 ? 60 : 30,
    };
  } catch {
    return fallback;
  }
}

function writePreferences(value: CameraPreferences): void {
  try { window.localStorage.setItem(PREFS_KEY, JSON.stringify(value)); } catch { /* preference is optional */ }
}
