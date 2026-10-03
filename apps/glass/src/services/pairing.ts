/**
 * Connect and disconnect phones, like linking WhatsApp Web: Glass asks, the phone shows "Connect this PC?"
 * with the same six-digit code, the user taps Allow on the phone. The gateway and the phone own the trust;
 * Glass only starts the request, waits for the answer and shows it.
 */
import { GatewayError, type GatewayClient } from "./gateway.js";

export interface TrustStatus {
  state: string;
  trusted: boolean;
  sessionReady: boolean;
  confirmationRequired: boolean;
  matchCode: string | null;
  expiresAtEpochMs: number | null;
  completed: boolean;
  lastSafeError: string | null;
}

/** How long Glass waits for the tap on the phone before it offers to try again (the phone's own limit is 90 s). */
export const CONNECT_WAIT_MS = 90_000;
export const CONNECT_POLL_MS = 1_500;

export function beginConnect(client: GatewayClient, deviceId: string): Promise<TrustStatus> {
  return client.post<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/trust/begin`).then(parseTrust);
}

/** One check of the phone's answer. Waiting is `confirmationRequired: true`, never an error. */
export function checkConnect(client: GatewayClient, deviceId: string): Promise<TrustStatus> {
  return client.post<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/trust/complete`).then(parseTrust);
}

export function disconnect(client: GatewayClient, deviceId: string): Promise<TrustStatus> {
  return client.post<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/trust/revoke`).then(parseTrust);
}

export async function scanForPhones(client: GatewayClient): Promise<void> {
  await client.post<unknown>("/v1/fleet/scan");
}

export type ConnectOutcome =
  | { kind: "connected"; status: TrustStatus }
  | { kind: "declined" | "expired" | "cancelled" }
  | { kind: "failed"; error: GatewayError };

export interface ConnectWaitOptions {
  now(): number;
  sleep(ms: number): Promise<void>;
  isCancelled(): boolean;
}

/** Poll until the phone answers, the request expires or the user cancels. */
export async function waitForPhone(client: GatewayClient, deviceId: string, startedAt: number, options: ConnectWaitOptions): Promise<ConnectOutcome> {
  while (!options.isCancelled()) {
    if (options.now() - startedAt > CONNECT_WAIT_MS) return { kind: "expired" };
    try {
      const status = await checkConnect(client, deviceId);
      if (status.completed || status.sessionReady) return { kind: "connected", status };
      if (!status.confirmationRequired && status.state !== "CONFIRMATION_REQUIRED") return { kind: "declined" };
    } catch (error) {
      const failure = error instanceof GatewayError ? error : new GatewayError("CONNECT_FAILED", String(error), 0);
      if (failure.code === "TRUST_EXPIRED") return { kind: "expired" };
      if (failure.code === "TRUST_REJECTED" || failure.code === "TRUST_DECLINED") return { kind: "declined" };
      // The phone keeps saying "not yet" as a confirmation-required error on some builds; keep waiting.
      if (failure.code !== "TRUST_CONFIRMATION_REQUIRED") return { kind: "failed", error: failure };
    }
    await options.sleep(CONNECT_POLL_MS);
  }
  return { kind: "cancelled" };
}

export function parseTrust(raw: unknown): TrustStatus {
  const record = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const code = typeof record.matchCode === "string" && /^\d{6}$/.test(record.matchCode) ? record.matchCode : null;
  const expires = typeof record.expiresAtEpochMs === "number" ? record.expiresAtEpochMs : null;
  return {
    state: typeof record.state === "string" ? record.state : "UNKNOWN",
    trusted: record.trusted === true,
    sessionReady: record.sessionReady === true,
    confirmationRequired: record.confirmationRequired === true,
    matchCode: code,
    expiresAtEpochMs: expires,
    completed: record.completed === true,
    lastSafeError: typeof record.lastSafeError === "string" && record.lastSafeError ? record.lastSafeError : null,
  };
}

export function formatMatchCode(code: string): string {
  return code.length === 6 ? `${code.slice(0, 3)} ${code.slice(3)}` : code;
}
