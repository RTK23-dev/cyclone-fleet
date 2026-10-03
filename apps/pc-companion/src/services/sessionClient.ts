import { invoke } from "@tauri-apps/api/core";
import type { DeviceSessionDescriptor, DeviceSessionList, DeviceSessionResult } from "./types.js";
import { isDefaultForegroundSession, readExactSessionSnapshotHeaders } from "../core/sessionTiles.js";

interface GatewaySession {
  token: string;
  http_base: string;
}

export type CycloneOneSessionState = DeviceSessionDescriptor["state"];
export type CycloneOneSession = DeviceSessionDescriptor;
export type CycloneOneSessionList = DeviceSessionList;
export type CycloneOneSessionResult = DeviceSessionResult;

/** Exact-session task API. The UI never supplies displayId; Android owns that identity. */
export class CycloneOneSessionClient {
  private constructor(private readonly base: string, private readonly token: string) {}

  static async connect(): Promise<CycloneOneSessionClient> {
    let session: GatewaySession;
    try {
      session = await invoke<GatewaySession>("gateway_session");
    } catch {
      const token = String(import.meta.env.VITE_CYCLONE_GATEWAY_TOKEN || "").trim();
      if (!token) throw new Error("Cyclone One local Gateway session is unavailable");
      session = {
        token,
        http_base: import.meta.env.VITE_CYCLONE_HTTP_BASE || "http://127.0.0.1:8765",
      };
    }
    return new CycloneOneSessionClient(session.http_base.replace(/\/$/, ""), session.token);
  }

  list(deviceId: string): Promise<CycloneOneSessionList> {
    return this.json(`/v1/devices/${encodeURIComponent(deviceId)}/sessions`);
  }

  start(deviceId: string, packageName: string): Promise<CycloneOneSessionResult> {
    return this.json(`/v1/devices/${encodeURIComponent(deviceId)}/sessions`, {
      method: "POST",
      body: JSON.stringify({ package: packageName }),
    });
  }

  status(deviceId: string, sessionId: string): Promise<CycloneOneSessionResult> {
    return this.json(this.sessionPath(deviceId, sessionId));
  }

  pause(deviceId: string, sessionId: string): Promise<CycloneOneSessionResult> {
    return this.lifecycle(deviceId, sessionId, "pause");
  }

  resume(deviceId: string, sessionId: string): Promise<CycloneOneSessionResult> {
    return this.lifecycle(deviceId, sessionId, "resume");
  }

  handoff(deviceId: string, sessionId: string): Promise<CycloneOneSessionResult> {
    return this.lifecycle(deviceId, sessionId, "handoff");
  }

  stop(deviceId: string, sessionId: string): Promise<CycloneOneSessionResult> {
    return this.lifecycle(deviceId, sessionId, "stop");
  }

  async snapshot(deviceId: string, sessionId: string): Promise<{ url: string; displayId: number; sessionId?: string }> {
    if (isDefaultForegroundSession(sessionId)) {
      throw new Error("Cyclone refused an unproven background preview");
    }
    const response = await this.fetch(this.sessionPath(deviceId, sessionId, "snapshot"), {
      headers: { Accept: "image/png" },
    });
    const { displayId } = readExactSessionSnapshotHeaders(response.headers);
    const headerSessionId = response.headers.get("X-Cyclone-Session-Id")?.trim() || undefined;
    const blob = await response.blob();
    if (blob.type && blob.type !== "image/png") throw new Error("Background preview is not a PNG frame");
    return { url: URL.createObjectURL(blob), displayId, sessionId: headerSessionId };
  }

  private lifecycle(deviceId: string, sessionId: string, op: "pause" | "resume" | "handoff" | "stop"): Promise<CycloneOneSessionResult> {
    return this.json(this.sessionPath(deviceId, sessionId, op), { method: "POST" });
  }

  private sessionPath(deviceId: string, sessionId: string, suffix = ""): string {
    const base = `/v1/devices/${encodeURIComponent(deviceId)}/sessions/${encodeURIComponent(sessionId)}`;
    return suffix ? `${base}/${suffix}` : base;
  }

  private async json<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetch(path, init);
    return (await response.json()) as T;
  }

  private async fetch(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.token}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        ...(init.headers ?? {}),
      },
      cache: "no-store",
    });
    if (!response.ok) {
      let message = `Cyclone One request failed (${response.status})`;
      try {
        const body = await response.json() as { detail?: { message?: string; code?: string } };
        message = body.detail?.message || body.detail?.code || message;
      } catch { /* bounded safe fallback */ }
      throw new Error(message.slice(0, 220));
    }
    return response;
  }
}
