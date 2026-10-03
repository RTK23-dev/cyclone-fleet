import { invoke } from "@tauri-apps/api/core";
import { HttpDesktopService } from "./httpDesktopService.js";
import { MockDesktopService } from "./mockDesktopService.js";
import type { DesktopService } from "./types.js";

interface GatewaySession {
  token: string;
  http_base: string;
  ws_base: string;
}

export async function createDesktopService(locationSearch = window.location.search): Promise<DesktopService> {
  const params = new URLSearchParams(locationSearch);
  const requestedMockCount = Number(params.get("mock") || import.meta.env.VITE_CYCLONE_MOCK_DEVICES || "0");
  const explicitMock = import.meta.env.VITE_CYCLONE_BACKEND === "mock" || requestedMockCount > 0;
  if (explicitMock) return new MockDesktopService(requestedMockCount > 0 ? requestedMockCount : 4);

  let session: GatewaySession;
  try {
    session = await invoke<GatewaySession>("gateway_session");
  } catch {
    const token = String(import.meta.env.VITE_CYCLONE_GATEWAY_TOKEN || "").trim();
    if (!token) throw new Error("Cyclone local Gateway session is unavailable");
    session = {
      token,
      http_base: import.meta.env.VITE_CYCLONE_HTTP_BASE || "http://127.0.0.1:8765",
      ws_base: import.meta.env.VITE_CYCLONE_WS_BASE || "ws://127.0.0.1:8765",
    };
  }
  const service = new HttpDesktopService({
    httpBaseUrl: session.http_base,
    wsBaseUrl: session.ws_base,
    token: session.token,
  });
  // Tauri starts the frozen Python Gateway concurrently with the WebView. Do not mistake the
  // normal sidecar startup window for "zero phones"; wait until this exact authenticated runtime
  // responds before mounting the fleet UI.
  await service.waitUntilReady();
  return service;
}
