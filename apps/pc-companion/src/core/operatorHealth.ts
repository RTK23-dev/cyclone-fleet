import type { DesktopDevice } from "../services/types.js";

export type OperatorHealthState = "READY" | "RECOVERING" | "ACTION_REQUIRED" | "OFFLINE" | "UNKNOWN";
export type OperatorHealthId = "usb" | "bridge" | "accessibility" | "session" | "semantic" | "media";

export interface OperatorHealthItem {
  id: OperatorHealthId;
  label: string;
  state: OperatorHealthState;
  detail: string;
}

export interface OperatorRecovery {
  label: string;
  detail: string;
  needsPairing?: boolean;
}

/**
 * The fleet contract has always had four independently useful planes.  This mapper deliberately
 * accepts the optional two newer planes when supplied, but does not turn their absence into a
 * failure for older Gateways.
 */
export function deviceOperatorHealth(device: DesktopDevice): OperatorHealthItem[] {
  const extra = device.operatorHealth;
  const plane = device.planes;
  const discovery = extra?.usb?.state ?? discoveryState(device);
  const bridge = extra?.bridge?.state ?? bridgeState(device);
  const accessibility = extra?.accessibility?.state ?? "UNKNOWN";
  const session = extra?.session?.state ?? sessionState(device);
  const semantic = extra?.semantic?.state ?? "UNKNOWN";
  const media = extra?.media?.state ?? mediaState(device);
  return [
    item("usb", "USB & authorization", discovery, extra?.usb?.message ?? discoveryDetail(device)),
    item("bridge", "Android bridge", bridge, extra?.bridge?.message ?? bridgeDetail(device)),
    item("accessibility", "Accessibility", accessibility, extra?.accessibility?.message ?? "Not reported by this Gateway"),
    item("session", "Session & token", session, extra?.session?.message ?? sessionDetail(device)),
    item("semantic", "Semantic plane", semantic, extra?.semantic?.message ?? "Not reported by this Gateway"),
    item("media", "Live view", media, extra?.media?.message ?? mediaDetail(device)),
  ];
}

export function operatorRecovery(device: DesktopDevice): OperatorRecovery | null {
  const health = deviceOperatorHealth(device);
  const usb = health.find((item) => item.id === "usb")!;
  const session = health.find((item) => item.id === "session")!;
  const bridge = health.find((item) => item.id === "bridge")!;
  if (usb.state === "ACTION_REQUIRED") {
    return { label: "Approve USB debugging", detail: "Unlock the phone, approve its USB debugging prompt, then retry discovery." };
  }
  if (session.state === "ACTION_REQUIRED") {
    return { label: "Restore phone access", detail: "Allow this PC again on the phone. Cyclone never displays or stores the session token.", needsPairing: true };
  }
  if (bridge.state === "OFFLINE" || bridge.state === "RECOVERING") {
    return { label: "Retry connection", detail: device.connectionHealth?.lastError || "The Android bridge is reconnecting with bounded retries." };
  }
  if (device.state === "DISCONNECTED") {
    return { label: "Retry discovery", detail: "The phone is no longer visible to the local Gateway. Check USB, then retry discovery." };
  }
  return null;
}

function item(id: OperatorHealthId, label: string, state: OperatorHealthState, detail: string): OperatorHealthItem {
  return { id, label, state, detail };
}

function discoveryState(device: DesktopDevice): OperatorHealthState {
  if (device.planes?.discovery === "ADB_READY" || device.readiness?.phoneConnection.ready) return "READY";
  if (device.planes?.discovery === "UNAUTHORIZED" || device.state === "UNAUTHORIZED") return "ACTION_REQUIRED";
  if (device.planes?.discovery === "OFFLINE" || device.planes?.discovery === "ABSENT" || device.state === "DISCONNECTED") return "OFFLINE";
  return "UNKNOWN";
}

function discoveryDetail(device: DesktopDevice): string {
  if (device.planes?.discovery === "UNAUTHORIZED" || device.state === "UNAUTHORIZED") return "Approve USB debugging on the phone";
  if (device.planes?.discovery === "OFFLINE" || device.planes?.discovery === "ABSENT" || device.state === "DISCONNECTED") return "Phone is not reachable through ADB";
  return device.readiness?.phoneConnection.message ?? "ADB status not reported";
}

function bridgeState(device: DesktopDevice): OperatorHealthState {
  if (device.planes?.bridge === "CONNECTED" || device.connectionHealth?.bridgeReachable === true) return "READY";
  if (device.planes?.bridge === "AUTH_FAILED") return "ACTION_REQUIRED";
  if (["DEGRADED", "SOCKET_STARTING"].includes(device.planes?.bridge ?? "") || device.connectionHealth?.bridgeReachable === false) return "RECOVERING";
  if (["APP_MISSING", "APP_STOPPED"].includes(device.planes?.bridge ?? "")) return "OFFLINE";
  return "UNKNOWN";
}

function bridgeDetail(device: DesktopDevice): string {
  if (device.planes?.bridge === "AUTH_FAILED") return "Phone session authentication was rejected";
  if (device.planes?.bridge === "APP_MISSING") return "Cyclone Mobile is not installed";
  if (device.planes?.bridge === "APP_STOPPED") return "Cyclone Mobile bridge is not running";
  return device.connectionHealth?.lastError ?? (device.connectionHealth?.bridgeReachable === true ? "Bridge heartbeat is healthy" : "Bridge status not reported");
}

function sessionState(device: DesktopDevice): OperatorHealthState {
  if (device.planes?.aiTrust === "TRUSTED") return "READY";
  if (["EXPIRED", "REVOKED", "CONFIRMATION_REQUIRED"].includes(device.planes?.aiTrust ?? "")) return "ACTION_REQUIRED";
  if (device.planes?.aiTrust === "UNPAIRED" || device.state === "UNPAIRED") return "ACTION_REQUIRED";
  return "UNKNOWN";
}

function sessionDetail(device: DesktopDevice): string {
  if (device.planes?.aiTrust === "EXPIRED") return "The phone session expired; allow this PC again";
  if (device.planes?.aiTrust === "REVOKED") return "Phone access was revoked";
  if (device.planes?.aiTrust === "CONFIRMATION_REQUIRED") return "Confirm Allow this PC on the phone";
  if (device.planes?.aiTrust === "UNPAIRED" || device.state === "UNPAIRED") return "Allow this PC to enable governed actions";
  return device.readiness?.aiCodexAccess.message ?? "Session status not reported";
}

function mediaState(device: DesktopDevice): OperatorHealthState {
  if (device.planes?.media === "LIVE" || device.readiness?.liveDisplay.ready) return "READY";
  if (["STARTING", "WAITING_KEYFRAME", "RECONNECTING"].includes(device.planes?.media ?? "")) return "RECOVERING";
  if (device.planes?.media === "SLEEPING") return "ACTION_REQUIRED";
  if (device.planes?.media === "STOPPED") return "UNKNOWN";
  if (device.planes?.media === "UNAVAILABLE") return "OFFLINE";
  return "UNKNOWN";
}

function mediaDetail(device: DesktopDevice): string {
  return device.readiness?.liveDisplay.message
    ?? (device.planes?.media === "LIVE" ? "Live display ready" : device.planes?.media === "SLEEPING" ? "Unlock the phone to show protected content" : "Open the phone to start live view");
}
