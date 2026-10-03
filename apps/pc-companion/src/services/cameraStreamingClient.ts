import { invoke } from "@tauri-apps/api/core";

interface GatewaySession {
  token: string;
  http_base: string;
}

export type CameraQuality = "high" | "native";
export type CameraFacing = "back" | "front";
export type CameraFps = 30 | 60;

export interface CameraStreamStartRequest {
  sourceDeviceId: string;
  targetDeviceIds: string[];
  facing: CameraFacing;
  quality: CameraQuality;
  fps: CameraFps;
}

export interface CameraLaunchFailure {
  deviceId: string;
  error: string;
}

export interface CameraStreamStatus {
  ok: boolean;
  active: boolean;
  degraded?: boolean;
  sourceDeviceId?: string | null;
  targetDeviceIds?: string[];
  requestedViewerCount?: number;
  viewerCount?: number;
  connectedViewerIds?: string[];
  maxViewers?: number;
  preserveSourceAspect?: boolean;
  launchFailures?: CameraLaunchFailure[];
  stream?: {
    state?: string;
    width?: number | null;
    height?: number | null;
    cameraFacing?: CameraFacing;
    quality?: CameraQuality;
    resolutionPolicy?: string;
    maxLongEdge?: number | null;
    targetFps?: number;
    bitrateBps?: number;
    lastError?: string | null;
  } | null;
}

export class CameraStreamingClient {
  async status(): Promise<CameraStreamStatus> {
    return this.request("/v1/camera-stream/status");
  }

  async start(value: CameraStreamStartRequest): Promise<CameraStreamStatus> {
    return this.request("/v1/camera-stream/start", {
      method: "POST",
      body: JSON.stringify({
        source_device_id: value.sourceDeviceId,
        target_device_ids: value.targetDeviceIds,
        facing: value.facing,
        quality: value.quality,
        fps: value.fps,
      }),
    });
  }

  async stop(): Promise<CameraStreamStatus> {
    return this.request("/v1/camera-stream/stop", { method: "POST" });
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    // Ask Tauri for the current ephemeral gateway session on every operation. This intentionally
    // does not cache a bearer across a runtime restart.
    const session = await invoke<GatewaySession>("gateway_session");
    const response = await fetch(`${session.http_base}${path}`, {
      ...init,
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${session.token}`,
        ...(init.headers ?? {}),
      },
    });
    if (!response.ok) {
      let message = `Camera stream request failed (${response.status}).`;
      try {
        const payload = await response.json() as { detail?: { message?: string } };
        if (payload.detail?.message) message = payload.detail.message;
      } catch { /* safe fallback */ }
      throw new Error(message.slice(0, 240));
    }
    return (await response.json()) as T;
  }
}
