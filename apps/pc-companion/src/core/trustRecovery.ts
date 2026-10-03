import type { DesktopDevice } from "../services/types.js";

const TRUST_ERROR_CODES = new Set(["TRUST_AUTH_FAILED", "AUTH_REJECTED"]);

export function needsTrustRepair(device: DesktopDevice): boolean {
  const errorClass = String(device.connectionHealth?.errorClass ?? "").toUpperCase();
  const safeError = `${device.connectionHealth?.lastError ?? ""} ${device.lastSafeError ?? ""}`;
  return TRUST_ERROR_CODES.has(errorClass)
    || device.planes?.bridge === "AUTH_FAILED"
    || device.planes?.aiTrust === "EXPIRED"
    || /identity no longer matches|trust (?:record|session).*(?:invalid|failed|expired)/i.test(safeError);
}

export function trustRepairMessage(device: DesktopDevice): string {
  return needsTrustRepair(device)
    ? "This phone no longer matches the trust saved on this PC. Forget the stale record, then approve Allow this PC on the phone again."
    : "Cyclone AI trust is ready.";
}
