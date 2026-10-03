import type { DesktopDevice, StreamDiagnosticEvent, StreamProfile, StreamUiState } from "../services/types.js";
import type { DesktopService } from "../services/types.js";
import type { VideoRendererFactoryInput, VideoRenderTarget, VideoRenderer } from "./decoder.js";
import { FallbackFrameRenderer } from "./fallbackFrameDecoder.js";
import { WebCodecsH264Renderer } from "./webcodecsH264Decoder.js";

export const FALLBACK_WS_RETRY_MS = 30_000;

export class LivePhoneController {
  private renderer: VideoRenderer | null = null;
  private state: StreamUiState = "CONNECTING";
  private stopped = false;
  private fallbackActive = false;
  private fallbackTimer: number | null = null;

  constructor(
    private readonly service: DesktopService,
    private readonly device: DesktopDevice,
    private readonly profile: StreamProfile,
    private readonly target: VideoRenderTarget,
    private readonly onState: (state: StreamUiState) => void,
    private readonly onDiagnostic: (event: StreamDiagnosticEvent) => void = () => undefined,
    private readonly realRendererFactory: (input: VideoRendererFactoryInput) => VideoRenderer = (input) => new WebCodecsH264Renderer(input),
  ) {}

  start(): void {
    this.stopped = false;
    if (this.device.state === "SLEEPING" && this.service.mode === "mock") {
      this.setState("SLEEPING");
      this.startFallback();
      return;
    }
    if (this.device.state === "DISCONNECTED" && this.service.mode === "mock") {
      this.setState("RECONNECTING");
      this.startFallback();
      return;
    }
    if (this.service.mode === "real") {
      this.startRealRenderer(true);
      return;
    }
    this.startFallback();
  }

  stop(): void {
    this.stopped = true;
    this.fallbackActive = false;
    this.clearFallbackRetry();
    this.renderer?.stop();
    this.renderer = null;
  }

  currentState(): StreamUiState { return this.state; }

  restart(): void {
    this.report({ stage: "client.manual.retry", code: "USER_RETRY", retryable: true });
    this.fallbackActive = false;
    this.clearFallbackRetry();
    this.renderer?.stop();
    this.renderer = null;
    this.stopped = false;
    this.start();
  }

  private startFallback(): void {
    if (this.fallbackActive) return;
    this.fallbackActive = true;
    const renderer = new FallbackFrameRenderer(this.input());
    this.renderer = renderer;
    renderer.start();
    this.report({ stage: "client.fallback.started", code: "FALLBACK_PREVIEW", retryable: true });
    this.scheduleFallbackRetry();
  }

  private startRealRenderer(sendWake: boolean): void {
    // Stream and wake are deliberately independent. A failed/slow phone health request must not
    // prevent the WebSocket from reporting SLEEPING, AUTH_REJECTED, or another exact stream state.
    const renderer = this.realRendererFactory(this.input());
    this.renderer = renderer;
    this.report({ stage: "client.controller.start", code: `DEVICE_${this.device.state}` });
    renderer.start();

    // Opening a paired phone is an explicit user action. Wake the display but never unlock it.
    // Background fallback retries must not re-wake a phone the user has put back to sleep.
    if (!sendWake) return;
    void this.service.sendControl(this.device.id, { type: "wake" })
      .then((result) => this.report({
        stage: result.ok ? "client.wake.ok" : "client.wake.rejected",
        code: result.verification || (result.ok ? "WAKE_OK" : "WAKE_REJECTED"),
        retryable: !result.ok,
      }))
      .catch(() => this.report({ stage: "client.wake.failed", code: "WAKE_REQUEST_FAILED", retryable: true }));
  }

  private activateFallback(): void {
    if (this.stopped || this.fallbackActive || !this.canUseFallback()) return;
    this.renderer?.stop();
    this.renderer = null;
    this.startFallback();
  }

  private canUseFallback(): boolean {
    if (this.service.mode === "mock") return true;
    const fallbackUrl = this.device.lastFrameUrl ?? this.service.getFallbackFrameUrl(this.device.id, this.profile);
    return fallbackUrl.length > 0;
  }

  private scheduleFallbackRetry(): void {
    if (this.stopped || this.service.mode !== "real" || this.fallbackTimer != null) return;
    this.fallbackTimer = window.setTimeout(() => {
      this.fallbackTimer = null;
      if (this.stopped || !this.fallbackActive) return;
      this.fallbackActive = false;
      this.renderer?.stop();
      this.renderer = null;
      this.report({ stage: "client.fallback.retry", code: "RETRY_WS", retryable: true });
      this.startRealRenderer(false);
    }, FALLBACK_WS_RETRY_MS);
  }

  private clearFallbackRetry(): void {
    if (this.fallbackTimer != null) window.clearTimeout(this.fallbackTimer);
    this.fallbackTimer = null;
  }

  private input() {
    return {
      device: this.device,
      profile: this.profile,
      streamUrl: this.service.getVideoUrl(this.device.id, this.profile),
      streamProtocols: this.service.getVideoProtocols(),
      fallbackUrl: this.device.lastFrameUrl ?? this.service.getFallbackFrameUrl(this.device.id, this.profile),
      target: this.target,
      callbacks: {
        onState: (state: StreamUiState) => {
          if (state === "LIVE" && this.device.state === "SLEEPING") this.setState("SLEEPING");
          else if (state === "LIVE" && this.device.state === "DISCONNECTED") this.setState("RECONNECTING");
          else this.setState(state);
          if (state === "UNAVAILABLE") this.activateFallback();
        },
        onError: (error: unknown) => {
          // Specific connection codes are reported through onDiagnostic. Only emit
          // FRAME_RENDER_ERROR when the renderer failed without a known code.
          const message = error instanceof Error ? error.message : "";
          const known = /^[A-Z][A-Z0-9_.-]{2,80}$/.test(message) ? message : undefined;
          this.report({
            stage: "client.render.error",
            code: known ?? "FRAME_RENDER_ERROR",
            retryable: true,
          });
        },
        onDiagnostic: (event: StreamDiagnosticEvent) => this.report(event),
      },
    };
  }

  private setState(state: StreamUiState): void {
    this.state = state;
    this.onState(state);
  }

  private report(event: StreamDiagnosticEvent): void {
    this.onDiagnostic(event);
    void this.service.reportStreamDiagnostic(this.device.id, event);
  }
}
