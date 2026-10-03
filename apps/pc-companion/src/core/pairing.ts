export const PAIR_CODE_LENGTH = 4;

export function normalizePairCode(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z]/g, "").slice(0, PAIR_CODE_LENGTH);
}

export function updatePairCodeAt(code: string, index: number, raw: string): string {
  const chars = normalizePairCode(code).padEnd(PAIR_CODE_LENGTH, " ").split("");
  const next = normalizePairCode(raw).slice(-1);
  chars[index] = next || " ";
  return chars.join("").replace(/ /g, "").slice(0, PAIR_CODE_LENGTH);
}

export function pairSecondsRemaining(expiresAtEpochMs: number, nowEpochMs: number): number {
  return Math.max(0, Math.ceil((expiresAtEpochMs - nowEpochMs) / 1000));
}

export function isPairCodeComplete(code: string): boolean {
  return normalizePairCode(code).length === PAIR_CODE_LENGTH;
}

export type PairSubmissionReason = "READY" | "NO_CHALLENGE" | "INCOMPLETE_CODE" | "EXPIRED" | "SUBMITTING";

export interface PairSubmissionState {
  ready: boolean;
  reason: PairSubmissionReason;
}

/**
 * Canonical pairing-button state. The modal uses this same result for its label,
 * disabled property, Enter-key handling, click handling, and countdown updates so
 * it can never display "ready" while silently ignoring a click.
 */
export function pairSubmissionState(
  code: string,
  hasActivePairing: boolean,
  submitting: boolean,
  expiresAtEpochMs: number,
  nowEpochMs: number,
): PairSubmissionState {
  if (submitting) return { ready: false, reason: "SUBMITTING" };
  if (!hasActivePairing) return { ready: false, reason: "NO_CHALLENGE" };
  if (pairSecondsRemaining(expiresAtEpochMs, nowEpochMs) === 0) return { ready: false, reason: "EXPIRED" };
  if (!isPairCodeComplete(code)) return { ready: false, reason: "INCOMPLETE_CODE" };
  return { ready: true, reason: "READY" };
}

export function canSubmitPairCode(
  code: string,
  hasActivePairing: boolean,
  submitting: boolean,
  expiresAtEpochMs: number,
  nowEpochMs: number,
): boolean {
  return pairSubmissionState(code, hasActivePairing, submitting, expiresAtEpochMs, nowEpochMs).ready;
}
