/**
 * atlas.get (async client) → MapsDataSource (sync canvas).
 *
 * Maps page stays sync. This adapter fetches once, then serves a cache.
 * Does not mount itself. Does not call mapping.start. Does not enable demo.
 */

import type { AtlasClient } from "../services/atlasClient.js";
import type {
  AtlasDocument as ServicesAtlasDocument,
  Edge as ServicesEdge,
  FactSlot as ServicesFactSlot,
  Place as ServicesPlace,
  PlaceId,
  Screen as ServicesScreen,
} from "../services/atlasTypes.js";
import { isSecretLookingKey, looksLikeSecretValue } from "../services/secretGuards.js";
import type {
  AtlasDocument,
  AtlasEdgeRecord,
  AtlasFactSlot,
  AtlasPlace,
  AtlasRisk,
  AtlasScreenRecord,
  FactType,
  MapStatus,
  Persona,
  PlaceSummary,
} from "./atlasViewModel.js";
import type { MapsDataSource } from "./mockAtlas.js";

const FACT_TYPES = new Set<FactType>(["text", "boolean", "number", "timestamp", "identifier"]);
const MAP_STATUSES = new Set<MapStatus>(["unmapped", "partial", "mapped", "stale", "blocked"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function safeLabel(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  if (looksLikeSecretValue(value)) return undefined;
  return value;
}

function toMapsPlace(place: ServicesPlace): AtlasPlace {
  const mapped: AtlasPlace = {
    placeId: place.placeId,
    kind: place.kind,
    label: looksLikeSecretValue(place.label) ? place.kind : place.label,
  };
  if (place.kind === "package") mapped.packageName = place.packageName;
  else mapped.origin = place.origin;
  return mapped;
}

function toMapsRisk(risk: unknown): AtlasRisk {
  if (!isRecord(risk) || typeof risk.danger !== "boolean") {
    return { danger: false, classes: [] };
  }
  const classes = Array.isArray(risk.classes)
    ? risk.classes.filter((item): item is string => typeof item === "string" && !isSecretLookingKey(item) && !looksLikeSecretValue(item))
    : [];
  return { danger: risk.danger, classes };
}

function toMapsSlot(slot: ServicesFactSlot): AtlasFactSlot | null {
  if (!slot || typeof slot.name !== "string" || !slot.name) return null;
  if (isSecretLookingKey(slot.name) || looksLikeSecretValue(slot.name)) return null;
  if (!FACT_TYPES.has(slot.factType)) return null;
  const mapped: AtlasFactSlot = {
    name: slot.name,
    factType: slot.factType,
    required: slot.required === true,
  };
  const description = safeLabel(slot.description);
  if (description !== undefined) mapped.description = description;
  return mapped;
}

function toMapsScreen(screen: ServicesScreen): AtlasScreenRecord | null {
  if (!screen || typeof screen.screenId !== "string" || !screen.screenId) return null;
  if (typeof screen.purpose !== "string" || !screen.purpose) return null;
  if (looksLikeSecretValue(screen.screenId) || looksLikeSecretValue(screen.purpose)) return null;
  const factSlots = Array.isArray(screen.factSlots)
    ? screen.factSlots.map((slot) => toMapsSlot(slot)).filter((slot): slot is AtlasFactSlot => slot != null)
    : [];
  const layout = screen.layout && Number.isFinite(screen.layout.x) && Number.isFinite(screen.layout.y)
    ? { x: screen.layout.x, y: screen.layout.y }
    : { x: 0, y: 0 };
  const mapped: AtlasScreenRecord = {
    screenId: screen.screenId,
    purpose: screen.purpose,
    factSlots,
    risk: toMapsRisk(screen.risk),
    confidence: Number.isFinite(screen.confidence) ? screen.confidence : 0,
    lastObservedAt: typeof screen.lastObservedAt === "string" ? screen.lastObservedAt : null,
    lastVerifiedAt: typeof screen.lastVerifiedAt === "string" ? screen.lastVerifiedAt : null,
    layout,
  };
  const label = safeLabel(screen.label);
  if (label !== undefined) mapped.label = label;
  return mapped;
}

function toMapsEdge(edge: ServicesEdge): AtlasEdgeRecord | null {
  if (!edge) return null;
  if (typeof edge.edgeId !== "string" || typeof edge.fromScreenId !== "string" || typeof edge.toScreenId !== "string") {
    return null;
  }
  if (typeof edge.actionHint !== "string" || looksLikeSecretValue(edge.actionHint)) return null;
  if (looksLikeSecretValue(edge.edgeId) || looksLikeSecretValue(edge.fromScreenId) || looksLikeSecretValue(edge.toScreenId)) {
    return null;
  }
  return {
    edgeId: edge.edgeId,
    fromScreenId: edge.fromScreenId,
    toScreenId: edge.toScreenId,
    actionHint: edge.actionHint,
    risk: toMapsRisk(edge.risk),
    confidence: Number.isFinite(edge.confidence) ? edge.confidence : 0,
    lastVerifiedAt: typeof edge.lastVerifiedAt === "string" ? edge.lastVerifiedAt : null,
  };
}

function toMapsCapabilities(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== "string" || !item) continue;
    if (isSecretLookingKey(item) || looksLikeSecretValue(item)) continue;
    if (seen.has(item)) continue;
    seen.add(item);
    out.push(item);
  }
  return out;
}

function fallbackPlace(): AtlasPlace {
  return {
    placeId: "package:com.example.app",
    kind: "package",
    label: "app",
    packageName: "com.example.app",
  };
}

/**
 * Copy required atlas fields into the Maps document shape.
 * Unknown extras are dropped. Secret VALUES are never copied onto slots
 * (slots stay name / factType / required / description only).
 */
export function toMapsDocument(doc: ServicesAtlasDocument): AtlasDocument {
  const place = doc?.place ? toMapsPlace(doc.place) : fallbackPlace();
  const persona: Persona = doc?.persona === "mapping" ? "mapping" : "live";
  const mapStatus: MapStatus = MAP_STATUSES.has(doc?.mapStatus as MapStatus)
    ? (doc.mapStatus as MapStatus)
    : "unmapped";
  const screens = Array.isArray(doc?.screens)
    ? doc.screens.map((screen) => toMapsScreen(screen)).filter((screen): screen is AtlasScreenRecord => screen != null)
    : [];
  const edges = Array.isArray(doc?.edges)
    ? doc.edges.map((edge) => toMapsEdge(edge)).filter((edge): edge is AtlasEdgeRecord => edge != null)
    : [];
  return {
    place,
    persona,
    mapStatus,
    screens,
    edges,
    capabilities: toMapsCapabilities(doc?.capabilities),
    confidence: Number.isFinite(doc?.confidence) ? doc.confidence : 0,
    lastObservedAt: typeof doc?.lastObservedAt === "string" ? doc.lastObservedAt : null,
    lastVerifiedAt: typeof doc?.lastVerifiedAt === "string" ? doc.lastVerifiedAt : null,
  };
}

function summaryOf(document: AtlasDocument): PlaceSummary {
  return {
    place: structuredClone(document.place),
    persona: document.persona,
    mapStatus: document.mapStatus,
    confidence: document.confidence,
    lastObservedAt: document.lastObservedAt,
    lastVerifiedAt: document.lastVerifiedAt,
  };
}

function cacheKey(placeId: string, persona: Persona): string {
  return `${placeId}\0${persona}`;
}

/**
 * Fetch atlas.places then atlas.get per place, then return a sync MapsDataSource.
 * Does not enable demo. Does not start mapping.
 */
export async function loadMapsDataSourceFromClient(
  client: AtlasClient,
  persona: Persona,
): Promise<MapsDataSource> {
  const catalog = await client.places();
  const placeIds: PlaceId[] = [];
  const seen = new Set<string>();
  for (const summary of catalog?.places ?? []) {
    const placeId = summary?.place?.placeId;
    if (typeof placeId !== "string" || !placeId || seen.has(placeId)) continue;
    seen.add(placeId);
    placeIds.push(placeId);
  }

  const cache = new Map<string, AtlasDocument>();
  for (const placeId of placeIds) {
    const servicesDoc = await client.get(placeId, persona);
    const mapped = toMapsDocument(servicesDoc);
    cache.set(cacheKey(mapped.place.placeId, mapped.persona), mapped);
  }

  return {
    listSummaries(requested: Persona): PlaceSummary[] {
      return [...cache.values()]
        .filter((document) => document.persona === requested)
        .map((document) => summaryOf(document));
    },
    getDocument(placeId: string, requested: Persona): AtlasDocument | null {
      const found = cache.get(cacheKey(placeId, requested));
      return found ? structuredClone(found) : null;
    },
  };
}
