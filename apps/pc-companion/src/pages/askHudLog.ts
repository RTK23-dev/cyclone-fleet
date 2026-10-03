import type { GlassAskSnapshot } from "../core/fleet.js";
import { DEFAULT_FOREGROUND_SESSION_ID } from "../core/sessionTiles.js";

/** Assignment-shaped secret VALUES. Slot labels such as "Facebook password" do not match. */
const SECRET_ASSIGNMENT = /\b(password|passcode|passwd|otp|cookie|token|secret|bearer)\s*[:=]\s*\S+/gi;
const HUNTER = /hunter2/gi;

/**
 * Replica HUD log: state, title, milestones, session_id.
 * Password/otp/cookie/token VALUES are stripped. Slot labels stay.
 * Named session ids are never rewritten to default-foreground.
 */
export function formatAskHudLog(snapshot: GlassAskSnapshot, sessionId?: string): string {
  const sid = resolveAskLogSessionId(sessionId ?? snapshot.sessionId);
  const lines = [
    `state: ${snapshot.state}`,
    `title: ${snapshot.title}`,
    `session_id: ${sid}`,
  ];
  if (snapshot.milestones?.length) {
    lines.push("milestones:");
    for (const milestone of snapshot.milestones) {
      lines.push(`- ${milestone.label} [${milestone.state}]`);
    }
  }
  return redactAskHudLog(lines.join("\n"));
}

export function resolveAskLogSessionId(sessionId?: string | null): string {
  const trimmed = String(sessionId ?? "").trim();
  return trimmed || DEFAULT_FOREGROUND_SESSION_ID;
}

export function redactAskHudLog(text: string): string {
  return String(text ?? "")
    .replace(HUNTER, "[redacted]")
    .replace(SECRET_ASSIGNMENT, (_, key: string) => `${key}=[redacted]`);
}
