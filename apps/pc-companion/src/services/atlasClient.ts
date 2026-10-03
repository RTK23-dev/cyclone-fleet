/**
 * Glass V5 atlas/secrets client. Phone is source of truth. Glass only commands and displays.
 *
 * Gateway HTTP routes from apps/device-gateway/.../api/v5_contract_api.py,
 * matching CONTRACT.md op names (do not invent a second vocabulary):
 *
 *   atlas.places      GET  /v1/devices/{device_id}/atlas/places
 *   atlas.get         GET  /v1/devices/{device_id}/atlas?placeId=&persona=
 *   secrets.slots     GET  /v1/devices/{device_id}/secrets/slots?placeId=&persona=
 *   secrets.request   POST /v1/devices/{device_id}/secrets/request
 *                     body is frozen to { placeId, persona, slot, reason }
 *   atlas.diff        GET  /v1/devices/{device_id}/atlas/diff?placeId=&persona=&since=
 *   mapping.start     POST /v1/devices/{device_id}/mapping/start   (local operator act)
 *   mapping.pause     POST /v1/devices/{device_id}/mapping/pause
 *   mapping.stop      POST /v1/devices/{device_id}/mapping/stop
 *   mapping.status    POST /v1/devices/{device_id}/mapping/status
 *   ask.start         POST /v1/devices/{device_id}/ask/start      (goal text only; phone runs it)
 *   ask.status        POST /v1/devices/{device_id}/ask/status
 *
 * session_id is required on every scoped call. Sent as `session_id` query and
 * `X-Cyclone-Session-Id` header. Never placed on the secrets.request JSON body
 * (gateway rejects unexpected fields). Never defaulted to display 0 / default-foreground.
 *
 * Mapping is commanded, never performed, here: the phone owns the job and walks the app. Glass
 * starts only `persona=mapping` passes on the foreground plane (display 0) in this cut.
 */

import type {
  AtlasDocument,
  Edge,
  FactSlot,
  Place,
  PlaceCatalog,
  PlaceId,
  PlaceSummary,
  Persona,
  MapStatus,
  Screen,
  SecretsRequestResult,
  SlotPresence,
} from "./atlasTypes.js";
import { ATLAS_DOCUMENT_KEYS, PLACE_SUMMARY_KEYS } from "./atlasTypes.js";
import {
  SECRET_PAYLOAD_REJECTED,
  SecretPayloadRejectedError,
  assertNoSecretValues,
  isSecretLookingKey,
  looksLikeSecretValue,
} from "./secretGuards.js";

export { SECRET_PAYLOAD_REJECTED, SecretPayloadRejectedError, assertNoSecretValues } from "./secretGuards.js";
export { ATLAS_DOCUMENT_KEYS, PLACE_SUMMARY_KEYS } from "./atlasTypes.js";
export type {
  AtlasDocument,
  Place,
  PlaceCatalog,
  PlaceId,
  PlaceSummary,
  Persona,
  MapStatus,
  SecretsRequestResult,
  SecretsRequestStatus,
  SlotPresence,
  SecretRequest,
} from "./atlasTypes.js";

/** Never auto-enable the demo graph for a V5 phone. Demo is an explicit opt-in only. */
export const DEMO_ATLAS_DISABLED_WHEN_PHONE_V5 = true;

const PERSONAS = new Set<Persona>(["live", "mapping"]);
const MAP_STATUSES = new Set<MapStatus>(["unmapped", "partial", "mapped", "stale", "blocked"]);
const PLACE_ID_RE =
  /^(package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|chrome:https?:\/\/[^\s/]+(?::[0-9]{1,5})?)$/;
const SLOT_RE = /^[A-Za-z][A-Za-z0-9._-]{0,63}$/;
const REASON_RE = /^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$/;
const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ORIGIN_RE = /^https?:\/\/[^\s/]+(?::[0-9]{1,5})?$/;

export class AtlasClientError extends Error {
  readonly code: string;
  readonly status?: number;
  readonly retryable: boolean;

  constructor(code: string, message: string, options: { status?: number; retryable?: boolean } = {}) {
    super(message.slice(0, 220));
    this.name = "AtlasClientError";
    this.code = code;
    this.status = options.status;
    this.retryable = options.retryable === true;
  }
}

export interface AtlasClientOptions {
  baseUrl: string;
  getBearer: () => string;
  getSessionId: () => string;
  /** Required by gateway `/v1/devices/{device_id}/…` routes. */
  getDeviceId: () => string;
  getPhoneVersion?: () => string | null | undefined;
  /** Explicit demo path. Default false. Never silently substituted for a connected V5 phone. */
  useDemoGraph?: boolean;
  fetch?: typeof fetch;
}

export type MappingState =
  | "idle"
  | "running"
  | "paused"
  | "needs-secret"
  | "human-control"
  | "completed"
  | "stopped"
  | "failed";

/** The part of a phone mapping job Glass shows. Structural ids and counts only. */
export interface MappingJobView {
  mappingJobId: string | null;
  placeId: PlaceId | null;
  state: MappingState;
  currentAtlasNodeId: string | null;
  newScreens: number;
  verifiedMutations: number;
  failureCode: string | null;
  atlasStatus: string | null;
}

export interface AtlasDiffChange {
  cursor: string;
  entity: "place" | "screen" | "edge";
  change: "upsert" | "remove";
  id: string;
}

export interface AtlasDiffView {
  cursor: string;
  resyncRequired: boolean;
  changes: AtlasDiffChange[];
}

/** Mirrors the phone's quick pass: small enough to watch, large enough to show a house. */
export const GLASS_MAPPING_BUDGET = Object.freeze({
  maxNewScreens: 12,
  maxElapsedMs: 180_000,
  maxConsecutiveNonProgress: 6,
  maxAttemptsPerDoor: 2,
});

export type AskStatusState = "idle" | "working" | "action-needed" | "needs-secret" | "done" | "failed";
export type AskMilestoneState = "pending" | "active" | "done" | "action-needed" | "failed";

/** The phone's own presentation snapshot of its current Ask run, mirrored on Glass. */
export interface AskStatusView {
  taskId: string | null;
  state: AskStatusState;
  title: string;
  app: string;
  currentMilestone: string | null;
  milestones: Array<{ label: string; state: AskMilestoneState }>;
  supportingCopy: string | null;
  outcomeCopy: string | null;
}

export const ASK_MAX_GOAL = 2000;

export const MAPPING_TERMINAL_STATES: ReadonlySet<MappingState> = new Set(["idle", "completed", "stopped", "failed"]);

export interface AtlasClient {
  readonly usingDemoGraph: boolean;
  places(): Promise<PlaceCatalog>;
  get(placeId: PlaceId, persona: Persona): Promise<AtlasDocument>;
  secretsSlots(placeId: PlaceId, persona: Persona): Promise<{ slots: Record<string, boolean> }>;
  secretsRequest(
    placeId: PlaceId,
    persona: Persona,
    slot: string,
    reason: string,
  ): Promise<SecretsRequestResult>;
  atlasDiff(placeId: PlaceId, persona: Persona, since: string | null): Promise<AtlasDiffView>;
  mappingStart(placeId: PlaceId): Promise<MappingJobView>;
  mappingResume(mappingJobId: string): Promise<MappingJobView>;
  mappingPause(mappingJobId: string): Promise<MappingJobView>;
  mappingStop(mappingJobId: string): Promise<MappingJobView>;
  mappingStatus(mappingJobId?: string | null): Promise<MappingJobView>;
  askStart(goal: string): Promise<void>;
  askStatus(): Promise<AskStatusView>;
}

/**
 * Mobile 5.x (including 5.0.0-alpha.1) supports Glass atlas/Vault.
 * Mobile 4.8.0 does not.
 */
export function supportsGlassAtlas(version: string): boolean {
  const trimmed = String(version ?? "").trim().replace(/^v/i, "");
  const match = trimmed.match(/^(\d+)\.(\d+)/);
  if (!match) return false;
  const major = Number(match[1]);
  return major >= 5;
}

export function createAtlasClient(options: AtlasClientOptions): AtlasClient {
  const baseUrl = stripSlash(options.baseUrl);
  const useDemoGraph = options.useDemoGraph === true;
  const fetchImpl = options.fetch ?? fetch.bind(globalThis);

  function sessionId(): string {
    const value = String(options.getSessionId?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("SESSION_REQUIRED", "session_id is required on atlas/secrets operations.");
    }
    return value;
  }

  function deviceId(): string {
    const value = String(options.getDeviceId?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("DEVICE_REQUIRED", "device_id is required on atlas/secrets operations.");
    }
    return value;
  }

  function bearer(): string {
    const value = String(options.getBearer?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("AUTH_REJECTED", "Gateway bearer token is required.");
    }
    return value;
  }

  function assertPhoneCapability(): void {
    const version = options.getPhoneVersion?.();
    if (version == null || version === "") return;
    if (!supportsGlassAtlas(version)) {
      throw new AtlasClientError(
        "PHONE_VERSION_UNSUPPORTED",
        "Update the phone to Cyclone Mobile 5.0 to use Maps, Ask, and Vault.",
      );
    }
  }

  async function requestJson(path: string, init: RequestInit = {}): Promise<unknown> {
    assertPhoneCapability();
    const sid = sessionId();
    const did = deviceId();
    const token = bearer();
    const separator = path.includes("?") ? "&" : "?";
    const url = `${baseUrl}${path.replace("{device_id}", encodeURIComponent(did))}${separator}session_id=${encodeURIComponent(sid)}`;
    const response = await fetchImpl(url, {
      ...init,
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        "X-Cyclone-Session-Id": sid,
        ...(init.headers ?? {}),
      },
    });
    if (!response.ok) {
      throw await errorFromResponse(response);
    }
    try {
      return await response.json();
    } catch {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Android V5 result must be an object.", {
        status: response.status,
      });
    }
  }

  return {
    usingDemoGraph: useDemoGraph,
    async places(): Promise<PlaceCatalog> {
      if (useDemoGraph) {
        sessionId();
        return demoPlaceCatalog();
      }
      const payload = await requestJson(`/v1/devices/{device_id}/atlas/places`);
      return parsePlaceCatalog(payload);
    },
    async get(placeId: PlaceId, persona: Persona): Promise<AtlasDocument> {
      assertPlacePersona(placeId, persona);
      if (useDemoGraph) {
        sessionId();
        return demoAtlasDocument(placeId, persona);
      }
      const payload = await requestJson(
        `/v1/devices/{device_id}/atlas?placeId=${encodeURIComponent(placeId)}&persona=${encodeURIComponent(persona)}`,
      );
      return parseAtlasDocument(payload, placeId, persona);
    },
    async secretsSlots(placeId: PlaceId, persona: Persona): Promise<{ slots: Record<string, boolean> }> {
      assertPlacePersona(placeId, persona);
      if (useDemoGraph) {
        sessionId();
        return { slots: {} };
      }
      const payload = await requestJson(
        `/v1/devices/{device_id}/secrets/slots?placeId=${encodeURIComponent(placeId)}&persona=${encodeURIComponent(persona)}`,
      );
      return parseSlotPresence(payload, placeId, persona);
    },
    async secretsRequest(
      placeId: PlaceId,
      persona: Persona,
      slot: string,
      reason: string,
    ): Promise<SecretsRequestResult> {
      assertPlacePersona(placeId, persona);
      if (!SLOT_RE.test(slot)) {
        throw new AtlasClientError("INVALID_REQUEST", "slot must be metadata only.");
      }
      if (!REASON_RE.test(reason) || looksLikeSecretValue(reason)) {
        throw new AtlasClientError("INVALID_REQUEST", "reason must be a bounded-safe-label.");
      }
      const body = { placeId, persona, slot, reason };
      assertNoSecretValues(body);
      if (useDemoGraph) {
        sessionId();
        return { status: "waiting" };
      }
      const payload = await requestJson(`/v1/devices/{device_id}/secrets/request`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      return parseSecretsRequestResult(payload);
    },
    async atlasDiff(placeId: PlaceId, persona: Persona, since: string | null): Promise<AtlasDiffView> {
      assertPlacePersona(placeId, persona);
      requireRealPhone();
      const sinceQuery = since ? `&since=${encodeURIComponent(since)}` : "";
      const payload = await requestJson(
        `/v1/devices/{device_id}/atlas/diff?placeId=${encodeURIComponent(placeId)}&persona=${encodeURIComponent(persona)}${sinceQuery}`,
      );
      return parseAtlasDiff(payload);
    },
    async mappingStart(placeId: PlaceId): Promise<MappingJobView> {
      assertPlacePersona(placeId, "mapping");
      if (!placeId.startsWith("package:")) {
        throw new AtlasClientError("PLACE_NOT_LAUNCHABLE", "Glass maps installed apps in this alpha; websites come later.");
      }
      requireRealPhone();
      return mappingCall("start", { placeId, persona: "mapping", budget: { ...GLASS_MAPPING_BUDGET } });
    },
    async mappingResume(mappingJobId: string): Promise<MappingJobView> {
      requireRealPhone();
      // The phone accepts only the job id plus plane identity when resuming.
      return mappingCall("start", { resumeJobId: jobId(mappingJobId) });
    },
    async mappingPause(mappingJobId: string): Promise<MappingJobView> {
      requireRealPhone();
      return mappingCall("pause", { mappingJobId: jobId(mappingJobId) });
    },
    async mappingStop(mappingJobId: string): Promise<MappingJobView> {
      requireRealPhone();
      return mappingCall("stop", { mappingJobId: jobId(mappingJobId) });
    },
    async mappingStatus(mappingJobId?: string | null): Promise<MappingJobView> {
      requireRealPhone();
      return mappingCall("status", mappingJobId ? { mappingJobId: jobId(mappingJobId) } : {});
    },
    async askStart(goal: string): Promise<void> {
      requireRealPhone();
      const text = String(goal ?? "").trim();
      if (!text || text.length > ASK_MAX_GOAL) {
        throw new AtlasClientError("INVALID_REQUEST", "Type a goal of up to 2000 characters.");
      }
      const body = { goal: text, ...foregroundPlane() };
      // Secrets are never typed into a goal; the phone's Secrets Card asks for them.
      assertNoSecretValues(body);
      const payload = await requestJson(`/v1/devices/{device_id}/ask/start`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      assertObject(payload, "ask.start");
      if ((payload as Record<string, unknown>).accepted !== true) {
        throw new AtlasClientError("PROTOCOL_MISMATCH", "Android ask.start acknowledgement is malformed.");
      }
    },
    async askStatus(): Promise<AskStatusView> {
      requireRealPhone();
      const payload = await requestJson(`/v1/devices/{device_id}/ask/status`, {
        method: "POST",
        body: JSON.stringify(foregroundPlane()),
      });
      return parseAskStatus(payload);
    },
  };

  function foregroundPlane(): { sessionId: string; displayId: number } {
    const sid = sessionId();
    if (sid !== FOREGROUND_SESSION_ID) {
      throw new AtlasClientError(
        "SESSION_DISPLAY_MISMATCH",
        "Ask from Glass runs on the phone's main screen (default-foreground) in this alpha.",
      );
    }
    return { sessionId: sid, displayId: 0 };
  }

  function requireRealPhone(): void {
    if (useDemoGraph) {
      throw new AtlasClientError("DEMO_MODE", "Mapping needs a connected Mobile 5 phone.");
    }
  }

  /** Foreground plane only: Glass never guesses a named workspace display. */
  async function mappingCall(op: "start" | "pause" | "stop" | "status", fields: Record<string, unknown>): Promise<MappingJobView> {
    const body = { ...fields, sessionId: sessionId(), displayId: 0 };
    if (body.sessionId !== FOREGROUND_SESSION_ID) {
      throw new AtlasClientError(
        "SESSION_DISPLAY_MISMATCH",
        "Glass starts mapping on the phone's main screen (default-foreground) in this alpha.",
      );
    }
    assertNoSecretValues(body);
    const payload = await requestJson(`/v1/devices/{device_id}/mapping/${op}`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    return parseMappingJob(payload);
  }
}

const FOREGROUND_SESSION_ID = "default-foreground";
const JOB_ID_RE = /^[A-Za-z0-9_-]{8,120}$/;
const SCREEN_ID_RE = /^(?:page|screen):[A-Za-z0-9._:-]{1,173}$/;
const CURSOR_RE = /^c1:[a-f0-9]{20}:[0-9]+$/;
const MAPPING_STATES = new Set<MappingState>([
  "idle", "running", "paused", "needs-secret", "human-control", "completed", "stopped", "failed",
]);

function jobId(value: string): string {
  if (!JOB_ID_RE.test(String(value ?? ""))) {
    throw new AtlasClientError("INVALID_REQUEST", "mappingJobId is malformed.");
  }
  return value;
}

function countOf(value: unknown): number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : 0;
}

export function parseMappingJob(payload: unknown): MappingJobView {
  assertObject(payload, "mapping");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  const state = record.state;
  if (typeof state !== "string" || !MAPPING_STATES.has(state as MappingState)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android mapping state is invalid.");
  }
  const id = record.mappingJobId;
  if (id !== null && (typeof id !== "string" || !JOB_ID_RE.test(id))) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android mappingJobId is malformed.");
  }
  const placeId = record.placeId == null ? null : parsePlaceId(record.placeId);
  const node = record.currentAtlasNodeId;
  if (node != null && (typeof node !== "string" || !SCREEN_ID_RE.test(node))) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android mapping node id must be page:/screen:.");
  }
  const progress = (record.progress && typeof record.progress === "object" ? record.progress : {}) as Record<string, unknown>;
  const failure = record.failureCode;
  const atlasStatus = record.atlasStatus;
  return {
    mappingJobId: (id as string | null) ?? null,
    placeId,
    state: state as MappingState,
    currentAtlasNodeId: (node as string | null | undefined) ?? null,
    newScreens: countOf(progress.newScreens),
    verifiedMutations: countOf(progress.verifiedMutations),
    failureCode: typeof failure === "string" && /^[A-Z0-9_]{1,80}$/.test(failure) ? failure : null,
    atlasStatus: typeof atlasStatus === "string" && MAP_STATUSES.has(atlasStatus as MapStatus) ? atlasStatus : null,
  };
}

const ASK_STATES = new Set<AskStatusState>(["idle", "working", "action-needed", "needs-secret", "done", "failed"]);
const ASK_MILESTONE_STATES = new Set<AskMilestoneState>(["pending", "active", "done", "action-needed", "failed"]);

function optionalText(value: unknown, max: number): string | null {
  return typeof value === "string" && value.trim() ? value.slice(0, max) : null;
}

export function parseAskStatus(payload: unknown): AskStatusView {
  assertObject(payload, "ask.status");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  const state = record.state;
  if (typeof state !== "string" || !ASK_STATES.has(state as AskStatusState)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android ask.status state is invalid.");
  }
  if (!Array.isArray(record.milestones)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android ask.status milestones are malformed.");
  }
  const milestones = record.milestones.slice(0, 8).map((item) => {
    assertObject(item, "ask.status milestone");
    const milestone = item as Record<string, unknown>;
    if (typeof milestone.label !== "string" || !ASK_MILESTONE_STATES.has(milestone.state as AskMilestoneState)) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Android ask.status milestone is malformed.");
    }
    return { label: milestone.label.slice(0, 90), state: milestone.state as AskMilestoneState };
  });
  return {
    taskId: optionalText(record.taskId, 120),
    state: state as AskStatusState,
    title: optionalText(record.title, 200) ?? "",
    app: optionalText(record.app, 80) ?? "",
    currentMilestone: optionalText(record.currentMilestone, 120),
    milestones,
    supportingCopy: optionalText(record.supportingCopy, 240),
    outcomeCopy: optionalText(record.outcomeCopy, 600),
  };
}

export function parseAtlasDiff(payload: unknown): AtlasDiffView {
  assertObject(payload, "atlas.diff");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  if (typeof record.cursor !== "string" || !CURSOR_RE.test(record.cursor)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android atlas.diff cursor is malformed.");
  }
  if (typeof record.resyncRequired !== "boolean" || !Array.isArray(record.changes)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android atlas.diff metadata is malformed.");
  }
  const changes = record.changes.map((item): AtlasDiffChange => {
    assertObject(item, "atlas.diff change");
    const change = item as Record<string, unknown>;
    if (
      (change.entity !== "place" && change.entity !== "screen" && change.entity !== "edge") ||
      (change.change !== "upsert" && change.change !== "remove") ||
      typeof change.id !== "string" ||
      typeof change.cursor !== "string"
    ) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas diff change is malformed.");
    }
    return { cursor: change.cursor, entity: change.entity, change: change.change, id: change.id };
  });
  return { cursor: record.cursor, resyncRequired: record.resyncRequired, changes };
}

export function parsePlaceCatalog(payload: unknown): PlaceCatalog {
  assertObject(payload, "atlas.places");
  assertNoSecretValues(payload);
  const places = (payload as { places?: unknown }).places;
  if (!Array.isArray(places)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android atlas.places result is malformed.");
  }
  return { places: places.map((item) => parsePlaceSummary(item)) };
}

export function parseAtlasDocument(payload: unknown, expectedPlaceId?: PlaceId, expectedPersona?: Persona): AtlasDocument {
  assertObject(payload, "atlas.get");
  assertNoSecretValues(payload);
  requireKeys(payload, ATLAS_DOCUMENT_KEYS, "Atlas document");
  const record = payload as Record<string, unknown>;
  const place = parsePlace(record.place);
  const persona = parsePersona(record.persona);
  if (expectedPlaceId && place.placeId !== expectedPlaceId) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned the wrong placeId.");
  }
  if (expectedPersona && persona !== expectedPersona) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned the wrong persona.");
  }
  if (!Array.isArray(record.screens) || !Array.isArray(record.edges) || !Array.isArray(record.capabilities)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas document screens/edges/capabilities must be arrays.");
  }
  return {
    place,
    persona,
    mapStatus: parseMapStatus(record.mapStatus),
    screens: record.screens.map((item) => parseScreen(item)),
    edges: record.edges.map((item) => parseEdge(item)),
    capabilities: record.capabilities.map((item) => parseCapability(item)),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

export function parseSlotPresence(payload: unknown, expectedPlaceId?: PlaceId, expectedPersona?: Persona): SlotPresence {
  assertObject(payload, "secrets.slots");
  assertNoSecretValues(payload, { slotPresence: true });
  const record = payload as Record<string, unknown>;
  const placeId = parsePlaceId(record.placeId);
  const persona = parsePersona(record.persona);
  if (expectedPlaceId && placeId !== expectedPlaceId) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots identity mismatch.");
  }
  if (expectedPersona && persona !== expectedPersona) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots identity mismatch.");
  }
  const slotsRaw = record.slots;
  if (slotsRaw === null || typeof slotsRaw !== "object" || Array.isArray(slotsRaw)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots presence map is malformed.");
  }
  const slots: Record<string, boolean> = {};
  for (const [slot, present] of Object.entries(slotsRaw as Record<string, unknown>)) {
    if (!SLOT_RE.test(slot) || typeof present !== "boolean") {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Secret slot presence must be boolean metadata only.");
    }
    slots[slot] = present;
  }
  return { placeId, persona, slots };
}

export function parseSecretsRequestResult(payload: unknown): SecretsRequestResult {
  assertObject(payload, "secrets.request");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  if (record.state === "needs-secret") {
    return { status: "waiting" };
  }
  const status = record.status ?? record.state;
  if (status === "waiting" || status === "filled" || status === "skipped" || status === "cancelled") {
    return { status };
  }
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.request acknowledgement is malformed.");
}

export function unmappedAtlasDocument(placeId: PlaceId, persona: Persona): AtlasDocument {
  return {
    place: placeFromId(placeId),
    persona,
    mapStatus: "unmapped",
    screens: [],
    edges: [],
    capabilities: [],
    confidence: 0,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

function parsePlaceSummary(payload: unknown): PlaceSummary {
  assertObject(payload, "place summary");
  requireKeys(payload, PLACE_SUMMARY_KEYS, "Atlas place summary");
  const record = payload as Record<string, unknown>;
  return {
    place: parsePlace(record.place),
    persona: parsePersona(record.persona),
    mapStatus: parseMapStatus(record.mapStatus),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

function parsePlace(payload: unknown): Place {
  assertObject(payload, "place");
  const record = payload as Record<string, unknown>;
  const placeId = parsePlaceId(record.placeId);
  const kind = record.kind;
  const label = record.label;
  if (typeof label !== "string" || label.length < 1 || label.length > 120) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source place label is required.");
  }
  if (kind === "package") {
    const packageName = record.packageName;
    if (typeof packageName !== "string" || !PACKAGE_RE.test(packageName)) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source packageName does not match placeId.");
    }
    if (!placeId.startsWith("package:") || placeId.slice("package:".length) !== packageName) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source packageName does not match placeId.");
    }
    return { placeId, kind, label, packageName };
  }
  if (kind === "chrome-origin") {
    const origin = record.origin;
    if (typeof origin !== "string" || !ORIGIN_RE.test(origin.replace(/\/$/, ""))) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source origin does not match placeId.");
    }
    const canonical = origin.replace(/\/$/, "");
    if (!placeId.startsWith("chrome:") || placeId.slice("chrome:".length) !== canonical) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source origin does not match placeId.");
    }
    return { placeId, kind, label, origin: canonical };
  }
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source place kind does not match placeId.");
}

function parsePlaceId(value: unknown): PlaceId {
  if (typeof value !== "string" || !PLACE_ID_RE.test(value)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  return value;
}

function parsePersona(value: unknown): Persona {
  if (value !== "live" && value !== "mapping") {
    throw new AtlasClientError("INVALID_REQUEST", "persona must be live or mapping.");
  }
  return value;
}

function parseMapStatus(value: unknown): MapStatus {
  if (typeof value !== "string" || !MAP_STATUSES.has(value as MapStatus)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned an invalid mapStatus.");
  }
  return value as MapStatus;
}

function parseConfidence(value: unknown): number {
  if (typeof value !== "number" || Number.isNaN(value) || value < 0 || value > 1) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas confidence must be a number between 0 and 1.");
  }
  return value;
}

function parseTimestamp(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value === "string") return value;
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas timestamp must be a date-time string or null.");
}

function parseScreen(payload: unknown): Screen {
  assertObject(payload, "screen");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  if (typeof record.screenId !== "string" || !record.screenId || typeof record.purpose !== "string" || !record.purpose) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas screen is malformed.");
  }
  if (!Array.isArray(record.factSlots)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas screen factSlots must be an array.");
  }
  return {
    screenId: record.screenId,
    label: typeof record.label === "string" ? record.label : undefined,
    purpose: record.purpose,
    factSlots: record.factSlots.map((item) => parseFactSlot(item)),
    risk: parseRisk(record.risk),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
    layout: parseLayout(record.layout),
  };
}

function parseFactSlot(payload: unknown): FactSlot {
  assertObject(payload, "factSlot");
  const record = payload as Record<string, unknown>;
  if (typeof record.name !== "string" || !record.name) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  if (isSecretLookingKey(record.name)) {
    throw new SecretPayloadRejectedError();
  }
  const factType = record.factType;
  if (
    factType !== "text" &&
    factType !== "boolean" &&
    factType !== "number" &&
    factType !== "timestamp" &&
    factType !== "identifier"
  ) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  if (typeof record.required !== "boolean") {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  return {
    name: record.name,
    factType,
    required: record.required,
    description: typeof record.description === "string" ? record.description : undefined,
  };
}

function parseEdge(payload: unknown): Edge {
  assertObject(payload, "edge");
  const record = payload as Record<string, unknown>;
  if (
    typeof record.edgeId !== "string" ||
    typeof record.fromScreenId !== "string" ||
    typeof record.toScreenId !== "string" ||
    typeof record.actionHint !== "string"
  ) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas edge is malformed.");
  }
  return {
    edgeId: record.edgeId,
    fromScreenId: record.fromScreenId,
    toScreenId: record.toScreenId,
    actionHint: record.actionHint,
    risk: parseRisk(record.risk),
    confidence: parseConfidence(record.confidence),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

function parseRisk(payload: unknown): { danger: boolean; classes: string[] } {
  assertObject(payload, "risk");
  const record = payload as Record<string, unknown>;
  if (typeof record.danger !== "boolean" || !Array.isArray(record.classes)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas risk is malformed.");
  }
  return {
    danger: record.danger,
    classes: record.classes.map((item) => {
      if (typeof item !== "string") throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas risk is malformed.");
      return item;
    }),
  };
}

function parseLayout(payload: unknown): { x: number; y: number } {
  assertObject(payload, "layout");
  const record = payload as Record<string, unknown>;
  if (typeof record.x !== "number" || typeof record.y !== "number") {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas layout is malformed.");
  }
  return { x: record.x, y: record.y };
}

function parseCapability(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z][A-Za-z0-9._-]{0,79}$/.test(value)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas capability is malformed.");
  }
  if (isSecretLookingKey(value)) throw new SecretPayloadRejectedError();
  return value;
}

function placeFromId(placeId: PlaceId): Place {
  if (!PLACE_ID_RE.test(placeId)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  if (placeId.startsWith("package:")) {
    const packageName = placeId.slice("package:".length);
    const label = packageName.split(".").pop() || packageName;
    return { placeId, kind: "package", label, packageName };
  }
  const origin = placeId.slice("chrome:".length);
  let label = origin.replace(/^https?:\/\//, "");
  try {
    label = new URL(origin).hostname || label;
  } catch {
    /* bounded fallback: origin without scheme */
  }
  return { placeId, kind: "chrome-origin", label, origin };
}

function assertPlacePersona(placeId: PlaceId, persona: Persona): void {
  if (!PLACE_ID_RE.test(placeId)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  if (!PERSONAS.has(persona)) {
    throw new AtlasClientError("INVALID_REQUEST", "persona must be live or mapping.");
  }
}

function assertObject(payload: unknown, label: string): asserts payload is Record<string, unknown> {
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", `Android ${label} result must be an object.`);
  }
}

function requireKeys(payload: Record<string, unknown>, keys: readonly string[], label: string): void {
  for (const key of keys) {
    if (!(key in payload)) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", `${label} is missing required key ${key}.`);
    }
  }
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}

function demoPlaceCatalog(): PlaceCatalog {
  const document = demoAtlasDocument("package:com.google.android.gm", "live");
  return {
    places: [
      {
        place: document.place,
        persona: document.persona,
        mapStatus: document.mapStatus,
        confidence: document.confidence,
        lastObservedAt: document.lastObservedAt,
        lastVerifiedAt: document.lastVerifiedAt,
      },
    ],
  };
}

function demoAtlasDocument(placeId: PlaceId, persona: Persona): AtlasDocument {
  if (placeId !== "package:com.google.android.gm") {
    const empty = unmappedAtlasDocument(placeId, persona);
    return { ...empty, place: { ...empty.place, label: `${empty.place.label} (demo)` } };
  }
  return {
    place: {
      placeId: "package:com.google.android.gm",
      kind: "package",
      label: "Gmail (demo)",
      packageName: "com.google.android.gm",
    },
    persona,
    mapStatus: "partial",
    screens: [
      {
        screenId: "demo.inbox",
        label: "Inbox",
        purpose: "List messages",
        factSlots: [],
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastObservedAt: null,
        lastVerifiedAt: null,
        layout: { x: 0, y: 0 },
      },
      {
        screenId: "demo.compose",
        label: "Compose",
        purpose: "Write a message",
        factSlots: [{ name: "recipient", factType: "identifier", required: true }],
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastObservedAt: null,
        lastVerifiedAt: null,
        layout: { x: 240, y: 0 },
      },
    ],
    edges: [
      {
        edgeId: "demo.inbox.compose",
        fromScreenId: "demo.inbox",
        toScreenId: "demo.compose",
        actionHint: "Compose",
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastVerifiedAt: null,
      },
    ],
    capabilities: ["DemoGraph"],
    confidence: 0.4,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

async function errorFromResponse(response: Response): Promise<AtlasClientError> {
  const status = response.status;
  let code = status >= 400 && status < 500 ? `HTTP_${status}` : "HTTP_ERROR";
  let message = `Cyclone atlas request failed (${status})`;
  let retryable = status >= 500;
  try {
    const body = await response.json();
    try {
      assertNoSecretValues(body);
    } catch (error) {
      if (error instanceof SecretPayloadRejectedError) {
        return new AtlasClientError(SECRET_PAYLOAD_REJECTED, error.message, { status });
      }
      throw error;
    }
    const detail = (body as { detail?: unknown }).detail;
    if (typeof detail === "string" && detail.trim()) {
      message = detail;
    } else if (detail && typeof detail === "object") {
      const record = detail as { code?: unknown; message?: unknown; retryable?: unknown };
      if (typeof record.code === "string" && record.code.trim()) code = record.code.trim();
      if (typeof record.message === "string" && record.message.trim()) message = record.message;
      if (typeof record.retryable === "boolean") retryable = record.retryable;
    }
  } catch (error) {
    if (error instanceof AtlasClientError) return error;
  }
  return new AtlasClientError(code, message, { status, retryable });
}
