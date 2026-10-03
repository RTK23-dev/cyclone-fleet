/**
 * Glass types for protocol/cyclone-atlas-v1.schema.json and cyclone-secrets-v1.schema.json.
 * Names match the Mobile-owned schemas. Do not fork them here.
 */

export type Persona = "live" | "mapping";

export type MapStatus = "unmapped" | "partial" | "mapped" | "stale" | "blocked";

export type PlaceKind = "package" | "chrome-origin";

/** `package:com.example.app` or `chrome:https://example.com` */
export type PlaceId = string;

export type FactType = "text" | "boolean" | "number" | "timestamp" | "identifier";

export interface PackagePlace {
  placeId: PlaceId;
  kind: "package";
  label: string;
  packageName: string;
}

export interface ChromeOriginPlace {
  placeId: PlaceId;
  kind: "chrome-origin";
  label: string;
  origin: string;
}

export type Place = PackagePlace | ChromeOriginPlace;

export interface Risk {
  danger: boolean;
  classes: string[];
}

export interface FactSlot {
  name: string;
  factType: FactType;
  required: boolean;
  description?: string;
}

export interface Layout {
  x: number;
  y: number;
}

export interface Screen {
  screenId: string;
  label?: string;
  purpose: string;
  factSlots: FactSlot[];
  risk: Risk;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
  layout: Layout;
}

export interface Edge {
  edgeId: string;
  fromScreenId: string;
  toScreenId: string;
  actionHint: string;
  risk: Risk;
  confidence: number;
  lastVerifiedAt: string | null;
}

export interface PlaceSummary {
  place: Place;
  persona: Persona;
  mapStatus: MapStatus;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
}

export interface PlaceCatalog {
  places: PlaceSummary[];
}

export interface AtlasDocument {
  place: Place;
  persona: Persona;
  mapStatus: MapStatus;
  screens: Screen[];
  edges: Edge[];
  capabilities: string[];
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
}

/** Boolean presence only. Slot names may describe secret kinds; values must be boolean. */
export type SecretsSlots = Record<string, boolean>;

export interface SlotPresence {
  placeId: PlaceId;
  persona: Persona;
  slots: SecretsSlots;
}

export interface SecretRequest {
  placeId: PlaceId;
  persona: Persona;
  slot: string;
  reason: string;
}

/** Schema wire ack for secrets.request. Glass maps `needs-secret` → waiting. */
export interface SecretRequestAck {
  state: "needs-secret";
  request: SecretRequest;
}

/**
 * Glass-facing secrets.request result.
 * `waiting` is the schema `needs-secret` card. filled/skipped/cancelled are later lease acks
 * (`secrets.lease.ack` in plan/05) — not invented wire ops in this client.
 */
export type SecretsRequestStatus = "waiting" | "filled" | "skipped" | "cancelled";

export interface SecretsRequestResult {
  status: SecretsRequestStatus;
}

export const ATLAS_DOCUMENT_KEYS = [
  "place",
  "persona",
  "mapStatus",
  "screens",
  "edges",
  "capabilities",
  "confidence",
  "lastObservedAt",
  "lastVerifiedAt",
] as const;

export const PLACE_SUMMARY_KEYS = [
  "place",
  "persona",
  "mapStatus",
  "confidence",
  "lastObservedAt",
  "lastVerifiedAt",
] as const;
