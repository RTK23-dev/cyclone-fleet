import type { VideoRenderer, VideoRendererFactoryInput } from "./decoder.js";

export const DEGRADED_FOCUS_POLL_MS = 1500;
export const DEGRADED_THUMBNAIL_POLL_MS = 3000;

export function cacheBustedFrameUrl(url: string, nowMs: number): string {
  if (url.startsWith("data:") || url.startsWith("blob:")) return url;
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}t=${nowMs}`;
}

export class FallbackFrameRenderer implements VideoRenderer {
  private timer: number | null = null;
  private stopped = false;
  private loadedOnce = false;

  constructor(private readonly input: VideoRendererFactoryInput) {}

  start(): void {
    this.stopped = false;
    const image = this.input.target.fallbackImage;
    image.hidden = false;
    this.input.target.canvas.hidden = true;
    image.onload = () => {
      this.loadedOnce = true;
      this.input.callbacks.onState("LIVE");
    };
    image.onerror = () => {
      if (this.stopped) return;
      this.input.callbacks.onState("STREAM_ERROR");
      this.input.callbacks.onError(new Error("Fallback phone frame unavailable"));
    };

    if (!this.input.fallbackUrl) {
      this.input.callbacks.onState("UNAVAILABLE");
      this.input.callbacks.onError(new Error("Fallback phone frame URL unavailable"));
      return;
    }
    if (this.input.device.video.mode === "MJPEG") {
      image.src = this.input.fallbackUrl;
      this.input.callbacks.onState("CONNECTING");
      return;
    }
    this.refreshScreenshot();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer != null) window.clearTimeout(this.timer);
    this.timer = null;
    this.input.target.fallbackImage.onload = null;
    this.input.target.fallbackImage.onerror = null;
  }

  private refreshScreenshot(): void {
    if (this.stopped) return;
    if (!this.loadedOnce) this.input.callbacks.onState("CONNECTING");
    this.input.target.fallbackImage.src = cacheBustedFrameUrl(this.input.fallbackUrl, Date.now());
    const delay = this.input.profile === "focus" ? DEGRADED_FOCUS_POLL_MS : DEGRADED_THUMBNAIL_POLL_MS;
    this.timer = window.setTimeout(() => this.refreshScreenshot(), delay);
  }
}
