/** Live phone video contract. Ported from the Cyclone One prototype with Glass-local types. */
export type StreamUiState = "CONNECTING" | "LIVE" | "RECONNECTING" | "SLEEPING" | "STREAM_ERROR" | "UNAVAILABLE";
export type StreamProfile = "thumbnail" | "focus";

export interface StreamDiagnosticEvent {
  stage: string;
  code?: string;
  attempt?: number;
  closeCode?: number;
  retryable?: boolean;
}

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
  device: { id: string };
  profile: StreamProfile;
  streamUrl: string;
  streamProtocols: string[];
  fallbackUrl: string;
  target: VideoRenderTarget;
  callbacks: VideoRendererCallbacks;
}
