/**
 * Phone atlas → MapsDataSource.
 *
 * Maps page stays sync. This helper is the async load + empty cache Agent 009
 * can pass as `loadSource`. Secret stripping lives in Agent 005's adapter —
 * this file does not copy slot values or reimplement guards.
 *
 * Does not call mapping.start. Does not enable demo.
 */

import type { AtlasClient } from "../services/atlasClient.js";
import {
  loadMapsDataSourceFromClient,
  toMapsDocument,
} from "./atlasClientAdapter.js";
import type { Persona } from "./atlasViewModel.js";
import type { MapsDataSource } from "./mockAtlas.js";

export { loadMapsDataSourceFromClient, toMapsDocument };

export const MAPS_EMPTY_ATLAS_TITLE = "No atlas from this phone yet";
export const MAPS_EMPTY_ATLAS_COPY =
  "This phone has not sent places to Glass yet. Map an app on the phone (Settings → App Maps) and the board fills in live.";
export const MAPS_LOADING_TITLE = "Loading atlas";
export const MAPS_LOADING_COPY = "Asking the phone for places.";
export const MAPS_DEMO_LABEL = "(demo)";
export const MAPS_DEMO_COPY = "Sample Mini house — not this phone.";

const NAMED_LOAD_CODES = ["PHONE_VERSION_UNSUPPORTED", "SESSION_REQUIRED", "HUMAN_HAS_CONTROL"] as const;
type NamedLoadCode = (typeof NAMED_LOAD_CODES)[number];

const NAMED_COPY: Record<NamedLoadCode, { title: string; copy: string }> = {
  PHONE_VERSION_UNSUPPORTED: {
    title: "PHONE_VERSION_UNSUPPORTED",
    copy: "This phone cannot serve atlas. Update Cyclone on the phone.",
  },
  SESSION_REQUIRED: {
    title: "SESSION_REQUIRED",
    copy: "A session_id is required to load this phone’s atlas.",
  },
  HUMAN_HAS_CONTROL: {
    title: "HUMAN_HAS_CONTROL",
    copy: "Companion currently owns input. Yield control, then retry Maps.",
  },
};

export interface MapsLoadErrorView {
  code: string;
  title: string;
  copy: string;
}

export function emptyMapsDataSource(): MapsDataSource {
  return {
    listSummaries() {
      return [];
    },
    getDocument() {
      return null;
    },
  };
}

/**
 * Fetch once via Agent 005's adapter and return a sync MapsDataSource cache.
 * `sessionId` is owned by the AtlasClient (`getSessionId`); this helper does
 * not default a display or invent a session.
 */
export async function loadPhoneAtlasSource(
  client: AtlasClient,
  persona: Persona = "live",
): Promise<MapsDataSource> {
  return loadMapsDataSourceFromClient(client, persona);
}

export function namedMapsLoadError(error: unknown): MapsLoadErrorView {
  const code = extractErrorCode(error);
  if (code && code in NAMED_COPY) {
    const named = NAMED_COPY[code as NamedLoadCode];
    return { code, title: named.title, copy: named.copy };
  }
  if (code === "NETWORK" || isNetworkError(error)) {
    return {
      code: "NETWORK",
      title: "NETWORK",
      copy: "Maps could not reach the phone. Check the connection and try again.",
    };
  }
  return {
    code: code || "LOAD_FAILED",
    title: code || "LOAD_FAILED",
    copy: "Maps could not load this phone’s atlas.",
  };
}

function extractErrorCode(error: unknown): string | null {
  if (error == null) return null;
  if (typeof error === "string") return matchNamedCode(error) ?? (isNetworkToken(error) ? "NETWORK" : error.trim() || null);
  if (typeof error !== "object") return null;
  const rec = error as { code?: unknown; name?: unknown; message?: unknown };
  if (typeof rec.code === "string" && rec.code.trim()) {
    const rawCode = rec.code.trim();
    return matchNamedCode(rawCode) ?? (isNetworkToken(rawCode) ? "NETWORK" : rawCode);
  }
  if (typeof rec.name === "string" && rec.name.trim()) {
    const named = matchNamedCode(rec.name);
    if (named) return named;
    if (rec.name === "TypeError" || rec.name === "NetworkError") return "NETWORK";
  }
  if (typeof rec.message === "string" && rec.message.trim()) {
    const named = matchNamedCode(rec.message);
    if (named) return named;
    if (isNetworkToken(rec.message)) return "NETWORK";
  }
  return null;
}

function matchNamedCode(value: string): NamedLoadCode | null {
  const upper = value.toUpperCase();
  for (const code of NAMED_LOAD_CODES) {
    if (upper.includes(code)) return code;
  }
  return null;
}

function isNetworkError(error: unknown): boolean {
  if (error == null) return false;
  if (typeof error === "string") return isNetworkToken(error);
  if (typeof error !== "object") return false;
  const rec = error as { code?: unknown; name?: unknown; message?: unknown };
  if (typeof rec.name === "string" && (rec.name === "TypeError" || rec.name === "NetworkError")) return true;
  if (typeof rec.code === "string" && isNetworkToken(rec.code)) return true;
  if (typeof rec.message === "string" && isNetworkToken(rec.message)) return true;
  return false;
}

function isNetworkToken(value: string): boolean {
  return /network|fetch|econnreset|econnrefused|enotfound|etimedout|offline|failed to fetch/i.test(value);
}
