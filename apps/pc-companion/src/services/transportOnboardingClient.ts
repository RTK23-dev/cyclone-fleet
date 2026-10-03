import { invoke } from "@tauri-apps/api/core";

interface GatewaySession {
  token: string;
  http_base: string;
}

export type TransportMode = "usb" | "wifi" | "vmos";

export interface TransportDeviceStatus {
  serial: string;
  state: string;
  model?: string | null;
  ready: boolean;
  action: string;
}

export interface TransportStatus {
  mode: TransportMode;
  ok: boolean;
  next: string;
  endpoint?: string;
  readyDeviceCount?: number;
  deviceCount?: number;
  devices?: TransportDeviceStatus[];
  device?: TransportDeviceStatus;
}

export class TransportOnboardingClient {
  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const session = await invoke<GatewaySession>("gateway_session");
    const response = await fetch(`${session.http_base.replace(/\/$/, "")}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${session.token}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        ...(init.headers ?? {}),
      },
    });
    const payload = await response.json().catch(() => ({})) as Record<string, unknown>;
    if (!response.ok) {
      const detail = typeof payload.detail === "object" && payload.detail != null
        ? payload.detail as Record<string, unknown>
        : payload;
      const message = typeof detail.message === "string"
        ? detail.message
        : "Cyclone could not complete that phone connection step.";
      throw new Error(message);
    }
    return payload as T;
  }

  usbStatus(): Promise<TransportStatus> {
    return this.request("/v1/transport/usb");
  }

  pairWireless(pairEndpoint: string, connectEndpoint: string, pairingCode: string): Promise<TransportStatus> {
    return this.request("/v1/transport/wifi/pair", {
      method: "POST",
      body: JSON.stringify({
        pair_endpoint: pairEndpoint,
        connect_endpoint: connectEndpoint,
        pairing_code: pairingCode,
      }),
    });
  }

  connectVmos(endpoint: string): Promise<TransportStatus> {
    return this.request("/v1/transport/vmos/connect", {
      method: "POST",
      body: JSON.stringify({ endpoint }),
    });
  }
}
