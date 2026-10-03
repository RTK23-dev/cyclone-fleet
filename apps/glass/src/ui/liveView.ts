/**
 * Live phone view over the gateway's video WebSocket (USB H.264 with JPEG fallback).
 *
 * Profiles matter: `thumbnail` only watches, `focus` is the gateway's "a human is at the controls" stream.
 * The dedicated Phone page keeps `focus` active while AI or the owner controls input.
 */
import { mapPointerGesture } from "../core/coordinates.js";
import type { GatewayClient } from "../services/gateway.js";
import type { StreamProfile, StreamUiState, VideoRenderer, VideoRendererFactoryInput } from "../video/decoder.js";
import { WebCodecsH264Renderer } from "../video/webcodecsH264Decoder.js";
import { el } from "./dom.js";

export interface LiveGesture {
  type: "tap" | "swipe";
  x: number;
  y: number;
  x2?: number;
  y2?: number;
  durationMs?: number;
}

export interface LiveViewOptions {
  client: GatewayClient;
  deviceId: string;
  origin: string;
  onGesture(gesture: LiveGesture): void;
  onState?(state: StreamUiState): void;
  rendererFactory?: (input: VideoRendererFactoryInput) => VideoRenderer;
  /** The specific reason to show when the stream is unavailable (defaults to a generic hint). */
  unavailableMessage?: (state: StreamUiState) => string | null;
  /** Retry timer (tests); live view never gives up while the page is open. */
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/** How long to wait before reopening a live view the gateway could not serve. */
export const LIVE_RETRY_MS = 5_000;

export interface LiveView {
  element: HTMLElement;
  setProfile(profile: StreamProfile): void;
  /** Allow pointer input on a live frame; the page may claim human control first. */
  setInteractive(interactive: boolean): void;
  state(): StreamUiState;
  destroy(): void;
}

const STATE_COPY: Record<StreamUiState, string> = {
  CONNECTING: "Connecting to the phone…",
  LIVE: "",
  RECONNECTING: "Reconnecting…",
  SLEEPING: "The phone screen is off.",
  STREAM_ERROR: "The live view hit an error. Retrying…",
  UNAVAILABLE: "Live view is unavailable. Check USB / ADB authorization on the phone.",
};

export function createLiveView(options: LiveViewOptions): LiveView {
  const element = el("div", "live-view");
  const canvas = el("canvas", "live-canvas");
  const image = el("img", "live-fallback");
  image.alt = "";
  image.hidden = true;
  const overlay = el("div", "live-overlay");
  overlay.setAttribute("role", "status");
  element.append(canvas, image, overlay);

  let profile: StreamProfile = "focus";
  let renderer: VideoRenderer | null = null;
  let current: StreamUiState = "CONNECTING";
  let interactive = false;
  let pointer: { clientX: number; clientY: number; startedAtMs: number } | null = null;
  const factory = options.rendererFactory ?? ((input) => new WebCodecsH264Renderer(input));

  const setTimer = options.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = options.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  let retry: unknown = null;
  let destroyed = false;

  const setState = (state: StreamUiState): void => {
    current = state;
    if (state === "LIVE") {
      const width = canvas.hidden ? image.naturalWidth : canvas.width;
      const height = canvas.hidden ? image.naturalHeight : canvas.height;
      if (width > 0 && height > 0) {
        element.style.setProperty("--frame-ratio", String(width / height));
        element.classList.toggle("landscape", width > height);
      }
    }
    const reason = state === "UNAVAILABLE" || state === "RECONNECTING" ? options.unavailableMessage?.(state) : null;
    overlay.textContent =
      state === "UNAVAILABLE" ? `${reason || STATE_COPY.UNAVAILABLE} Retrying on its own…` : state === "RECONNECTING" && reason ? `Reconnecting… ${reason}` : STATE_COPY[state];
    overlay.hidden = state === "LIVE";
    element.dataset.state = state.toLowerCase();
    options.onState?.(state);
    // Like a remote-desktop client: an unavailable stream is reopened on its own until the page closes.
    if (state === "UNAVAILABLE" && retry === null && !destroyed) {
      retry = setTimer(() => {
        retry = null;
        if (!destroyed) start();
      }, LIVE_RETRY_MS);
    }
  };

  const start = (): void => {
    renderer?.stop();
    const socket = options.client.socket(`/v1/devices/${encodeURIComponent(options.deviceId)}/video?profile=${profile}`, options.origin);
    renderer = factory({
      device: { id: options.deviceId },
      profile,
      streamUrl: socket.url,
      streamProtocols: socket.protocols,
      fallbackUrl: "",
      target: { container: element, canvas, fallbackImage: image },
      callbacks: {
        onState: setState,
        onError: () => undefined,
        onDiagnostic: () => undefined,
      },
    });
    renderer.start();
  };

  element.addEventListener("pointerdown", (event: PointerEvent) => {
    if (!interactive || current !== "LIVE" || event.button !== 0) return;
    pointer = { clientX: event.clientX, clientY: event.clientY, startedAtMs: event.timeStamp };
    if (Number.isInteger(event.pointerId)) element.setPointerCapture?.(event.pointerId);
  });
  element.addEventListener("pointerup", (event: PointerEvent) => {
    const start = pointer;
    pointer = null;
    if (!interactive || current !== "LIVE" || !start) return;
    const width = canvas.width || image.naturalWidth;
    const height = canvas.height || image.naturalHeight;
    const gesture = mapPointerGesture(
      start,
      { clientX: event.clientX, clientY: event.clientY, endedAtMs: event.timeStamp },
      (canvas.hidden ? image : canvas).getBoundingClientRect(),
      width,
      height,
      0,
    );
    if (!gesture) return;
    if (gesture.type === "tap") options.onGesture({ type: "tap", x: gesture.x, y: gesture.y });
    else options.onGesture({ type: "swipe", x: gesture.x1, y: gesture.y1, x2: gesture.x2, y2: gesture.y2, durationMs: gesture.durationMs });
  });
  element.addEventListener("pointercancel", () => { pointer = null; });

  setState("CONNECTING");
  start();

  return {
    element,
    setProfile(next) {
      if (next === profile) return;
      profile = next;
      start();
    },
    setInteractive(value) {
      interactive = value;
      element.classList.toggle("interactive", value);
    },
    state: () => current,
    destroy() {
      destroyed = true;
      if (retry !== null) clearTimer(retry);
      retry = null;
      renderer?.stop();
      renderer = null;
    },
  };
}
