/** Manual phone control through the gateway (`POST /v1/devices/{id}/control`). The phone's GATE still applies. */
import { GatewayError, type GatewayClient } from "./gateway.js";

export type ControlBody =
  | { kind: "tap"; x: number; y: number }
  | { kind: "swipe"; x1: number; y1: number; x2: number; y2: number; duration_ms: number }
  | { kind: "back" | "home" | "wake" | "scroll_up" | "scroll_down" }
  | { kind: "text"; text: string }
  | { kind: "take_human" | "yield_ai"; sessionId?: string };

export interface ControlResult {
  ok: boolean;
  /** Who owns input after the command, when the gateway reports it. */
  inputOwner: "HUMAN" | "AI" | null;
  /** Gateway status or a named refusal such as PHONE_LOCKED / HUMAN_HAS_CONTROL. */
  verification: string;
}

export async function sendControl(client: GatewayClient, deviceId: string, body: ControlBody): Promise<ControlResult> {
  try {
    const result = await client.post<{ ok?: unknown; status?: unknown; inputOwner?: unknown }>(
      `/v1/devices/${encodeURIComponent(deviceId)}/control`,
      body,
    );
    const owner = String(result?.inputOwner ?? "").toUpperCase();
    return {
      ok: result?.ok === true,
      inputOwner: owner === "HUMAN" || owner === "AI" ? owner : null,
      verification: typeof result?.status === "string" ? result.status : "android-result",
    };
  } catch (error) {
    if (error instanceof GatewayError && (error.code === "PHONE_LOCKED" || error.code === "HUMAN_HAS_CONTROL")) {
      return { ok: false, inputOwner: null, verification: error.code };
    }
    throw error;
  }
}
