/**
 * Plan 26 (A42-1): keep the phone's background work on after restarts. One fixed gateway step
 * (`POST /v1/devices/{id}/background/keep-on`); Glass sends no command, only the request.
 */
import type { GatewayClient } from "./gateway.js";

export interface KeepOnResult {
  ok: boolean;
  state: "on" | "already_on" | "helper_missing" | "not_granted" | "unknown";
  message: string;
}

const STATES = new Set(["on", "already_on", "helper_missing", "not_granted"]);

export async function keepBackgroundOn(client: GatewayClient, deviceId: string): Promise<KeepOnResult> {
  const result = await client.post<{ ok?: unknown; state?: unknown; message?: unknown }>(
    `/v1/devices/${encodeURIComponent(deviceId)}/background/keep-on`,
    {},
  );
  const state = typeof result?.state === "string" && STATES.has(result.state) ? (result.state as KeepOnResult["state"]) : "unknown";
  return {
    ok: result?.ok === true,
    state,
    message: typeof result?.message === "string" ? result.message.slice(0, 400) : "The gateway gave no answer.",
  };
}
