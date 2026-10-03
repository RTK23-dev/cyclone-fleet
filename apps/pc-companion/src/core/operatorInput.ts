import type { StreamUiState } from "../services/types.js";

/** Operator mouse remains usable while the JPEG preview is warming or reconnecting. */
export function operatorInputEnabled(state: StreamUiState, deviceState: string): boolean {
  if (deviceState === "SLEEPING" || deviceState === "DISCONNECTED" || deviceState === "UNAUTHORIZED") return false;
  if (state === "SLEEPING" || state === "UNAVAILABLE") return false;
  return deviceState === "READY" || deviceState === "ATTENTION" || deviceState === "PAIRING";
}

export function operatorInputPausedMessage(state: StreamUiState): string | null {
  if (state === "UNAVAILABLE") return "Input paused — stream down";
  return null;
}
