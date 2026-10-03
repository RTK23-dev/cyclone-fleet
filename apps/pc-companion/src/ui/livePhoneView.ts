import { mapPointerGesture } from "../core/coordinates.js";
import { operatorInputEnabled, operatorInputPausedMessage } from "../core/operatorInput.js";
import type { DesktopDevice, DesktopService, StreamProfile, StreamUiState } from "../services/types.js";
import { LivePhoneController } from "../video/livePhoneController.js";
import { createDeviceHealthPanel } from "./deviceHealthPanel.js";
import { button, el } from "./dom.js";

export interface LivePhoneViewOptions {
  service: DesktopService;
  device: DesktopDevice;
  profile: StreamProfile;
  interactive?: boolean;
  showLabel?: boolean;
  showHealth?: boolean;
  autoStart?: boolean;
  onOpen?: (device: DesktopDevice) => void;
  onPair?: (device: DesktopDevice) => void;
  onControl?: (kind: "tap" | "swipe", ok: boolean) => void;
}

export interface LivePhoneViewHandle {
  element: HTMLElement;
  destroy(): void;
}

export { operatorInputEnabled, operatorInputPausedMessage };

export function createLivePhoneView(options: LivePhoneViewOptions): LivePhoneViewHandle {
  const { device } = options;
  const card = el("article", `phone-card phone-state-${device.state.toLowerCase()}`);
  const displayedWidth = device.video.rotationDegrees === 90 || device.video.rotationDegrees === 270 ? device.video.height : device.video.width;
  const displayedHeight = device.video.rotationDegrees === 90 || device.video.rotationDegrees === 270 ? device.video.width : device.video.height;
  card.style.setProperty("--phone-aspect", `${displayedWidth} / ${displayedHeight}`);
  const stage = el("div", "phone-stage");
  const frame = el("div", "phone-frame");
  const canvas = el("canvas", "phone-canvas");
  const image = el("img", "phone-fallback") as HTMLImageElement;
  image.alt = `${device.name} screen`;
  image.draggable = false;
  image.hidden = true;
  const status = el("div", "phone-stream-status");
  const overlay = el("div", "phone-state-overlay");

  frame.append(canvas, image, status, overlay);
  stage.append(frame);
  card.append(stage);

  if (options.showHealth !== false) card.append(createDeviceHealthPanel(device, options.profile === "thumbnail"));

  if (options.showLabel !== false) {
    const footer = el("footer", "phone-card-footer");
    const labelWrap = el("div", "phone-label-wrap");
    labelWrap.append(el("div", "phone-label", device.name), el("div", "phone-connection", friendlyConnection(device)));
    footer.append(labelWrap);
    if (!device.paired || device.state === "UNPAIRED") {
      const pair = button("Pair", "button compact primary");
      pair.addEventListener("click", (event) => {
        event.stopPropagation();
        options.onPair?.(device);
      });
      footer.append(pair);
    }
    card.append(footer);
  }

  if (!device.paired || device.state === "UNPAIRED" || device.state === "PAIRING") {
    canvas.hidden = true;
    image.hidden = true;
    overlay.classList.add("visible");
    overlay.append(
      el("div", "state-kicker", device.state === "PAIRING" ? "Pairing" : "New phone"),
      el("div", "state-title", device.state === "PAIRING" ? "Pairing in progress" : "Pair this phone"),
    );
    const pairButton = button(device.state === "PAIRING" ? "Continue pairing" : "Pair this phone", "button primary");
    pairButton.addEventListener("click", (event) => {
      event.stopPropagation();
      options.onPair?.(device);
    });
    overlay.append(pairButton);
    return { element: card, destroy: () => undefined };
  }

  // Fleet cards are intentionally connection-only. Pairing must never implicitly start a continuous
  // adb screencap/video workload. The live stream starts only after the user opens a paired phone.
  if (options.autoStart === false) {
    card.classList.add("passive-device-card");
    canvas.hidden = true;
    image.hidden = true;
    overlay.classList.add("visible", "passive");
    overlay.append(
      el("div", "state-kicker", "Connected"),
      el("div", "state-title", device.state === "SLEEPING" ? "Phone sleeping" : "Phone ready"),
      el("div", "state-copy", "Open this phone to start the live view."),
    );
    if (options.onOpen) {
      card.classList.add("clickable");
      card.addEventListener("click", () => options.onOpen?.(device));
      const open = button("Open phone", "button primary");
      open.addEventListener("click", (event) => {
        event.stopPropagation();
        options.onOpen?.(device);
      });
      overlay.append(open);
    }
    return { element: card, destroy: () => undefined };
  }

  let currentState: StreamUiState = device.state === "SLEEPING" ? "SLEEPING" : "CONNECTING";
  let diagnosticCode = device.state === "SLEEPING" ? "PHONE_SCREEN_OFF" : "STARTING";
  let bundleInFlight = false;
  const renderCurrent = () => renderStreamStatus(
    status,
    overlay,
    currentState,
    device,
    diagnosticCode,
    () => controller.restart(),
    async () => {
      if (bundleInFlight) return;
      bundleInFlight = true;
      diagnosticCode = "COLLECTING_DEBUG_BUNDLE";
      renderCurrent();
      try {
        const bundle = await options.service.createConnectionDiagnosticBundle(device.id);
        diagnosticCode = bundle.ok ? `SAVED_${bundle.path.split(/[\\/]/).pop() || "CONNECTION_BUNDLE"}` : "BUNDLE_FAILED";
      } catch {
        diagnosticCode = "BUNDLE_FAILED";
      } finally {
        bundleInFlight = false;
        renderCurrent();
      }
    },
  );
  const controller = new LivePhoneController(
    options.service,
    device,
    options.profile,
    { container: frame, canvas, fallbackImage: image },
    (next) => {
      currentState = next;
      renderCurrent();
    },
    (event) => {
      if (event.code) diagnosticCode = event.code;
      if (currentState !== "LIVE" || diagnosticCode === "FALLBACK_PREVIEW") renderCurrent();
    },
  );
  renderCurrent();
  controller.start();

  if (options.onOpen) {
    card.classList.add("clickable");
    card.addEventListener("click", () => options.onOpen?.(device));
  }

  if (options.interactive) {
    frame.classList.add("interactive");
    frame.setAttribute("aria-label", `Control ${device.name}. Click to tap, hold and drag to swipe.`);
    let pointerStart: { clientX: number; clientY: number; startedAtMs: number; pointerId: number } | null = null;
    frame.addEventListener("pointerdown", (event) => {
      if (!operatorInputEnabled(currentState, device.state)) return;
      if (event.button !== 0) return;
      pointerStart = { clientX: event.clientX, clientY: event.clientY, startedAtMs: performance.now(), pointerId: event.pointerId };
      frame.setPointerCapture(event.pointerId);
      frame.classList.add("pointer-active");
      event.preventDefault();
    });
    frame.addEventListener("pointermove", (event) => {
      if (!pointerStart || pointerStart.pointerId !== event.pointerId) return;
      if (Math.hypot(event.clientX - pointerStart.clientX, event.clientY - pointerStart.clientY) >= 8) frame.classList.add("pointer-dragging");
      event.preventDefault();
    });
    frame.addEventListener("pointerup", (event) => {
      if (!pointerStart || pointerStart.pointerId !== event.pointerId) return;
      const rect = frame.getBoundingClientRect();
      const gesture = mapPointerGesture(
        pointerStart,
        { clientX: event.clientX, clientY: event.clientY, endedAtMs: performance.now() },
        rect,
        device.video.width,
        device.video.height,
        device.video.rotationDegrees,
      );
      pointerStart = null;
      frame.classList.remove("pointer-active", "pointer-dragging");
      if (frame.hasPointerCapture(event.pointerId)) frame.releasePointerCapture(event.pointerId);
      if (!gesture) return;
      void options.service.sendControl(device.id, gesture)
        .then((result) => options.onControl?.(gesture.type, result.ok))
        .catch(() => options.onControl?.(gesture.type, false));
      event.preventDefault();
    });
    const cancelPointer = (event: PointerEvent) => {
      if (pointerStart?.pointerId !== event.pointerId) return;
      pointerStart = null;
      frame.classList.remove("pointer-active", "pointer-dragging");
    };
    frame.addEventListener("pointercancel", cancelPointer);
    frame.addEventListener("lostpointercapture", cancelPointer);
  }

  return {
    element: card,
    destroy: () => controller.stop(),
  };
}

function renderStreamStatus(
  status: HTMLElement,
  overlay: HTMLElement,
  state: StreamUiState,
  device: DesktopDevice,
  diagnosticCode: string,
  onRetry: () => void,
  onBundle: () => void,
): void {
  status.textContent = "";
  overlay.replaceChildren();
  const inputEnabled = operatorInputEnabled(state, device.state);
  overlay.classList.toggle("visible", state !== "LIVE");
  overlay.classList.toggle("passive", state === "CONNECTING" || state === "RECONNECTING" || (inputEnabled && state !== "SLEEPING"));
  overlay.classList.toggle("input-enabled", inputEnabled && state !== "LIVE");

  if (state === "LIVE") {
    status.textContent = diagnosticCode === "FALLBACK_PREVIEW"
      ? "JPEG live preview (~2 fps). Mouse control stays active."
      : diagnosticCode === "JPEG_FRAME_OK"
        ? ""
        : "";
    status.hidden = diagnosticCode !== "FALLBACK_PREVIEW";
    return;
  }
  const paused = !inputEnabled ? operatorInputPausedMessage(state) : null;
  status.textContent = paused ?? "";
  status.hidden = paused == null;
  const title = state === "SLEEPING"
    ? "Sleeping"
    : state === "RECONNECTING"
      ? "Reconnecting"
      : state === "CONNECTING"
        ? "Connecting"
        : state === "UNAVAILABLE"
          ? "Live view unavailable"
          : "Stream interrupted";
  overlay.append(el("div", "state-title", title));
  if (state === "SLEEPING") {
    overlay.append(el("div", "state-copy", `Unlock ${device.name} to show protected screen content. Cyclone can wake the display but never bypass Android's lock screen.`));
  } else {
    overlay.append(el("div", "state-copy", `Connection code: ${diagnosticCode}`));
    if (inputEnabled) {
      overlay.append(el("div", "state-copy", "Mouse tap and swipe stay available while the preview recovers."));
    } else if (paused) {
      overlay.append(el("div", "state-copy", paused));
    }
  }
  if (state === "RECONNECTING" || state === "STREAM_ERROR" || state === "UNAVAILABLE") {
    const actions = el("div", "stream-recovery-actions");
    const retry = button("Retry live view", "button primary compact");
    retry.addEventListener("click", (event) => { event.stopPropagation(); onRetry(); });
    const bundle = button("Save debug bundle", "button secondary compact");
    bundle.addEventListener("click", (event) => { event.stopPropagation(); onBundle(); });
    actions.append(retry, bundle);
    overlay.append(actions);
  }
}

function friendlyConnection(device: DesktopDevice): string {
  if (device.state === "READY") return device.connectionLabel || "Ready";
  if (device.state === "SLEEPING") return "Sleeping";
  if (device.state === "DISCONNECTED") return "Reconnecting";
  if (device.state === "PAIRING") return "Pairing";
  return "Not paired";
}
