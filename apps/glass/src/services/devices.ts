/** Phones as the local gateway sees them (`GET /v1/fleet`). Glass shows them; the gateway owns discovery and trust. */
import type { GatewayClient } from "./gateway.js";

export type AiTrust = "TRUSTED" | "UNPAIRED" | "CONFIRMATION_REQUIRED" | "EXPIRED" | "REVOKED" | string;

export interface GlassDevice {
  id: string;
  name: string;
  model: string;
  state: string;
  paired: boolean;
  connectionLabel: string;
  aiTrust: AiTrust;
  mobileVersion: string | null;
  /** USB, LAN or VIRTUAL. */
  transport: string;
  /** Trusted phone session is open right now (not only remembered). */
  sessionReady: boolean;
  /** Six digits shown on the phone while it is being connected. */
  matchCode: string | null;
  /** The name the phone shows for this PC (Linked PCs, "Connect <name>?"). */
  pcLabel: string | null;
  /** Gateway's own words for why a trusted phone is not usable yet (phone locked, gateway off, …). */
  problem: string | null;
  /** USB/ADB state for live view (the gateway's usbAuthorization plane); null when not reported. */
  usb: "USB_AUTHORIZED" | "USB_UNAUTHORIZED" | "USB_OFFLINE" | "USB_ABSENT" | null;
  /** The gateway's one answer for this phone's connection (alpha 88); null from older runtimes. */
  connection: ConnectionVerdict | null;
}

export type ConnectionActionKind = "connect" | "install" | "start_app" | "open_accessibility" | "open_cyclone";

/** The first broken link of the connection, in plain words, with at most one action. */
export interface ConnectionVerdict {
  layer: string;
  code: string;
  ok: boolean;
  title: string;
  message: string;
  action: { kind: ConnectionActionKind; label: string } | null;
  /** Cyclone is already fixing it (reconnecting, restarting the app). */
  working: boolean;
}

const ACTION_KINDS: ConnectionActionKind[] = ["connect", "install", "start_app", "open_accessibility", "open_cyclone"];

export function parseConnection(raw: unknown): ConnectionVerdict | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const title = text(r.title);
  if (!title) return null;
  const action = (r.action && typeof r.action === "object" ? r.action : null) as Record<string, unknown> | null;
  const kind = action ? text(action.kind) as ConnectionActionKind : null;
  return {
    layer: text(r.layer) || "unknown",
    code: text(r.code),
    ok: r.ok === true,
    title,
    message: text(r.message),
    action: kind && ACTION_KINDS.includes(kind) && text(action?.label) ? { kind, label: text(action?.label) } : null,
    working: r.working === true,
  };
}

/** Runs one of the gateway's fixed connection repairs (start Cyclone, open Accessibility settings, open Cyclone). */
export async function fixConnection(client: GatewayClient, deviceId: string, action: "start_app" | "open_accessibility" | "open_cyclone"): Promise<void> {
  await client.post(`/v1/devices/${encodeURIComponent(deviceId)}/connection/fix`, { action });
}

/** Glass features that read the Atlas need Cyclone Mobile 5.x. Unknown version fails closed. */
export type DeviceReadiness =
  | { ready: true }
  | { ready: false; reason: NotReadyReason; message: string };

export type NotReadyReason = "disconnected" | "unpaired" | "connecting" | "needs-update" | "version-unknown";

export async function listDevices(client: GatewayClient, signal?: AbortSignal): Promise<GlassDevice[]> {
  const body = await client.get<{ devices?: unknown }>("/v1/fleet", signal);
  const rows = Array.isArray(body?.devices) ? body.devices : [];
  return rows.map(parseDevice).filter((device): device is GlassDevice => device !== null);
}

export function parseDevice(raw: unknown): GlassDevice | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const id = text(record.deviceId) || text(record.id);
  if (!id) return null;
  const planes = (record.planes && typeof record.planes === "object" ? record.planes : {}) as Record<string, unknown>;
  const model = text(record.model);
  const trust = (record.trust && typeof record.trust === "object" ? record.trust : {}) as Record<string, unknown>;
  const matchCode = text(trust.matchCode);
  return {
    id,
    name: text(record.name) || model || "Android phone",
    model,
    state: text(record.state) || "DISCONNECTED",
    paired: record.paired === true,
    connectionLabel: text(record.connectionLabel),
    aiTrust: text(planes.aiTrust) || "UNPAIRED",
    mobileVersion: text(record.mobileVersion) || null,
    transport: text(record.source) || "USB",
    sessionReady: trust.sessionReady === true,
    matchCode: /^\d{6}$/.test(matchCode) ? matchCode : null,
    pcLabel: text(trust.pcLabel).slice(0, 80) || null,
    problem: gatewayProblem(record, trust),
    usb: usbState(record),
    connection: parseConnection(record.connection),
  };
}

function usbState(record: Record<string, unknown>): GlassDevice["usb"] {
  const health = (record.health && typeof record.health === "object" ? record.health : {}) as Record<string, unknown>;
  const planes = (health.planes && typeof health.planes === "object" ? health.planes : {}) as Record<string, unknown>;
  const usb = (planes.usbAuthorization && typeof planes.usbAuthorization === "object" ? planes.usbAuthorization : {}) as Record<string, unknown>;
  const code = text(usb.reasonCode);
  return code === "USB_AUTHORIZED" || code === "USB_UNAUTHORIZED" || code === "USB_OFFLINE" || code === "USB_ABSENT" ? code : null;
}

/**
 * Why live view cannot start, in plain words. Live view is the phone's screen video over USB (ADB); the trusted session
 * that answers Ask and You are here does not need it, so a phone can be connected while live view is not.
 */
export function liveViewProblem(device: GlassDevice): string {
  switch (device.usb) {
    case "USB_UNAUTHORIZED":
      return "This PC is not allowed to use USB debugging yet. Unplug and replug the cable, then tap Allow on the phone's “Allow USB debugging?” prompt (tick Always allow).";
    case "USB_OFFLINE":
      return "The phone is offline to USB debugging. Replug the cable, or turn USB debugging off and on in Developer options.";
    case "USB_ABSENT":
      return device.transport === "CLOUD"
        ? "This is a cloud phone. Cyclone is reopening its link; live view starts again once it is back."
        : device.transport === "LAN"
        ? "The phone is connected over Wi‑Fi. Live view needs the USB cable: plug it in with a data cable and allow USB debugging."
        : "This PC does not see the phone over USB. Use a data cable (not charge-only), set USB mode to File transfer, and close Android Studio, scrcpy or other ADB tools that can hold the phone.";
    case "USB_AUTHORIZED":
      return "The PC sees the phone over USB, but the live video did not start. Close and reopen Cyclone One; if it stays, wake and unlock the phone and press Reconnect in Devices.";
    default:
      return "Live view is unavailable. Check that the phone is plugged in by USB with USB debugging allowed for this PC.";
  }
}

/** First concrete reason the gateway gives for a phone that is present but not usable. */
function gatewayProblem(record: Record<string, unknown>, trust: Record<string, unknown>): string | null {
  const trustError = text(trust.lastSafeError);
  if (trustError) return trustError;
  const health = (record.health && typeof record.health === "object" ? record.health : {}) as Record<string, unknown>;
  const planes = (health.planes && typeof health.planes === "object" ? health.planes : {}) as Record<string, unknown>;
  for (const name of ["gateway", "accessibility", "tokenSession"]) {
    const plane = planes[name] as Record<string, unknown> | undefined;
    if (plane && plane.ready === false && text(plane.message)) return text(plane.message);
  }
  const connection = (record.connectionHealth && typeof record.connectionHealth === "object" ? record.connectionHealth : {}) as Record<string, unknown>;
  return text(connection.lastError) || text(record.lastSafeError) || null;
}

export function deviceReadiness(device: GlassDevice): DeviceReadiness {
  const base = baseReadiness(device);
  const verdict = device.connection;
  if (!verdict || verdict.ok) return base;
  // The gateway's own verdict names the broken link more precisely than any guess from fields (alpha 88).
  const message = verdict.message ? `${verdict.title}. ${verdict.message}` : verdict.title;
  if (!base.ready) return { ...base, message };
  // A remembered phone whose link is broken (still resuming, app stopped, gateway off) is not usable yet. Accessibility
  // alone doesn't block Glass: it reads what the phone knows without it.
  return verdict.layer === "accessibility" ? base : { ready: false, reason: "connecting", message };
}

function baseReadiness(device: GlassDevice): DeviceReadiness {
  if (device.state === "DISCONNECTED" || device.state === "UNAUTHORIZED") {
    return { ready: false, reason: "disconnected", message: "The phone is not connected to this PC. Check the USB cable or wireless debugging." };
  }
  if (device.aiTrust === "REVOKED") {
    return { ready: false, reason: "unpaired", message: "The phone logged this PC out. Connect again: Glass shows a code, you tap Allow on the phone." };
  }
  if (!device.paired) {
    return { ready: false, reason: "unpaired", message: "Connect this phone in Devices: Glass shows a code, you tap Allow on the phone." };
  }
  const major = majorVersion(device.mobileVersion);
  if (major === null) {
    if (!device.sessionReady || device.problem) {
      return {
        ready: false,
        reason: "connecting",
        message: device.problem
          ? `Connected before, not reachable right now: ${device.problem}`
          : "Connected before; Cyclone is reopening the session with the phone. Unlock the phone if it is locked.",
      };
    }
    return { ready: false, reason: "version-unknown", message: "Cyclone on the phone has not reported its version yet." };
  }
  if (major < 5) {
    return { ready: false, reason: "needs-update", message: `Update Cyclone on the phone. Glass needs Cyclone Mobile 5, this phone runs ${device.mobileVersion}.` };
  }
  return { ready: true };
}

/** Keep the chosen phone when it is still listed; otherwise prefer a ready phone, then any phone. */
export function pickDevice(devices: GlassDevice[], preferredId: string | null): GlassDevice | null {
  if (preferredId) {
    const kept = devices.find((device) => device.id === preferredId);
    if (kept) return kept;
  }
  return devices.find((device) => deviceReadiness(device).ready) ?? devices[0] ?? null;
}

export function majorVersion(version: string | null): number | null {
  const match = /^(\d+)\./.exec(version ?? "");
  return match ? Number(match[1]) : null;
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
