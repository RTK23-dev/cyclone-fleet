import type { DesktopDevice, StreamDiagnosticEvent, StreamProfile, StreamUiState } from "../services/types.js";

export interface VideoRenderTarget {
  container: HTMLElement;
  canvas: HTMLCanvasElement;
  fallbackImage: HTMLImageElement;
}

export interface VideoRendererCallbacks {
  onState(state: StreamUiState): void;
  onError(error: unknown): void;
  onDiagnostic(event: StreamDiagnosticEvent): void;
}

export interface VideoRenderer {
  start(): void;
  stop(): void;
}

export interface VideoRendererFactoryInput {
  device: DesktopDevice;
  profile: StreamProfile;
  streamUrl: string;
  streamProtocols: string[];
  fallbackUrl: string;
  target: VideoRenderTarget;
  callbacks: VideoRendererCallbacks;
}
