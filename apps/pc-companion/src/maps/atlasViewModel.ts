/**
 * Renderer-facing atlas contract.
 *
 * Glass Maps consumes this shape only. Agent 003 can replace mockAtlas
 * with atlas.get output by mapping into AtlasViewModel / AtlasDocument
 * without changing appMapCanvas internals.
 *
 * Protocol documents stay compatible with cyclone-atlas-v1.schema.json.
 * Region, tone, landmarks, masked slots, and coverage are derived here.
 */

export type MapStatus = "unmapped" | "partial" | "mapped" | "stale" | "blocked";
export type Persona = "live" | "mapping";
export type ScreenTone = "mapped" | "partial" | "stale" | "blocked" | "danger" | "dark";
export type PlaceKind = "package" | "chrome-origin";
export type FactType = "text" | "boolean" | "number" | "timestamp" | "identifier";

export const DARK_CONFIDENCE = 0.5;
export const STALE_CONFIDENCE = 0.7;
export const MASKED_SLOT_DISPLAY = "Masked";

export interface AtlasPlace {
  placeId: string;
  kind: PlaceKind;
  label: string;
  packageName?: string;
  origin?: string;
}

export interface AtlasRisk {
  danger: boolean;
  classes: string[];
}

export interface AtlasFactSlot {
  name: string;
  factType: FactType;
  required: boolean;
  description?: string;
}

export interface AtlasLayout {
  x: number;
  y: number;
}

export interface AtlasScreenRecord {
  screenId: string;
  label?: string;
  purpose: string;
  factSlots: AtlasFactSlot[];
  risk: AtlasRisk;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
  layout: AtlasLayout;
}

export interface AtlasEdgeRecord {
  edgeId: string;
  fromScreenId: string;
  toScreenId: string;
  actionHint: string;
  risk: AtlasRisk;
  confidence: number;
  lastVerifiedAt: string | null;
}

export interface AtlasDocument {
  place: AtlasPlace;
  persona: Persona;
  mapStatus: MapStatus;
  screens: AtlasScreenRecord[];
  edges: AtlasEdgeRecord[];
  capabilities: string[];
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
}

export interface PlaceSummary {
  place: AtlasPlace;
  persona: Persona;
  mapStatus: MapStatus;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
}

export interface AtlasScreen {
  screenId: string;
  label: string;
  purpose: string;
  region: string;
  factSlots: Array<AtlasFactSlot & { maskedValue: string; displayLabel: string }>;
  risk: AtlasRisk;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
  layout: AtlasLayout;
  landmarks: string[];
  tone: ScreenTone;
}

export interface AtlasEdge {
  edgeId: string;
  fromScreenId: string;
  toScreenId: string;
  actionHint: string;
  risk: AtlasRisk;
  confidence: number;
  lastVerifiedAt: string | null;
}

export interface AtlasCoverage {
  screens: number;
  doors: number;
  dark: number;
}

export interface AtlasViewModel {
  place: AtlasPlace;
  persona: Persona;
  mapStatus: MapStatus;
  screens: AtlasScreen[];
  edges: AtlasEdge[];
  capabilities: string[];
  coverage: AtlasCoverage;
  confidence: number;
  lastObservedAt: string | null;
  lastVerifiedAt: string | null;
}

export interface BoardFilters {
  stale: boolean;
  blocked: boolean;
  danger: boolean;
  unmappedDoors?: boolean;
}

export interface InspectorDoor {
  edgeId: string;
  actionHint: string;
  toScreenId: string;
  toLabel: string;
  risk: AtlasRisk;
  confidence: number;
  dark: boolean;
}

export interface InspectorState {
  kind: "empty" | "screen" | "edge";
  title: string;
  subtitle: string;
  purpose?: string;
  region?: string;
  tone?: ScreenTone;
  confidence?: number;
  lastObservedAt?: string | null;
  lastVerifiedAt?: string | null;
  factSlots: Array<{ name: string; displayLabel: string; maskedValue: string; description: string; required: boolean }>;
  doors: InspectorDoor[];
  frameCopy: string;
  actionHint?: string;
  fromLabel?: string;
  toLabel?: string;
  fromScreenId?: string;
  toScreenId?: string;
  dark?: boolean;
}

const PURPOSE_REGION: Record<string, string> = {
  "account-switcher": "Account",
  "add-account": "Account",
  identity: "Account",
  login: "Account",
  signup: "Account",
  inbox: "Inbox",
  search: "Inbox",
  thread: "Inbox",
  message: "Inbox",
  compose: "Compose",
  settings: "Settings",
  "dm-list": "Chat",
  thread_dm: "Chat",
  feed: "Feed",
  payment: "Danger",
  checkout: "Danger",
};

const LEAF_PURPOSES = new Set(["thread", "settings", "compose", "add-account"]);

export function regionForPurpose(purpose: string): string {
  const key = purpose.trim().toLowerCase();
  if (PURPOSE_REGION[key]) return PURPOSE_REGION[key];
  if (key.includes("account") || key.includes("login") || key.includes("identity")) return "Account";
  if (key.includes("inbox") || key.includes("mail")) return "Inbox";
  if (key.includes("compose") || key.includes("draft")) return "Compose";
  if (key.includes("dm") || key.includes("chat") || key.includes("message")) return "Chat";
  if (key.includes("setting")) return "Settings";
  if (key.includes("feed")) return "Feed";
  if (key.includes("pay") || key.includes("danger")) return "Danger";
  return "Other";
}

export function humanizeSlotName(name: string): string {
  return name
    .split(/[._-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function coverageOf(document: Pick<AtlasDocument, "screens" | "edges">): AtlasCoverage {
  const screens = Array.isArray(document.screens) ? document.screens.length : 0;
  const doors = Array.isArray(document.edges) ? document.edges.length : 0;
  const dark = Array.isArray(document.screens)
    ? document.screens.filter((screen) => !Number.isFinite(screen.confidence) || screen.confidence < DARK_CONFIDENCE).length
    : 0;
  return { screens, doors, dark };
}

export function screenTone(screen: AtlasScreenRecord, mapStatus: MapStatus): ScreenTone {
  if (screen.risk?.danger) return "danger";
  const purpose = (screen.purpose || "").toLowerCase();
  if (mapStatus === "blocked" && (purpose === "login" || purpose === "signup")) return "blocked";
  if (Number.isFinite(screen.confidence) && screen.confidence < DARK_CONFIDENCE) return "dark";
  if (mapStatus === "stale" || (Number.isFinite(screen.confidence) && screen.confidence < STALE_CONFIDENCE)) return "stale";
  if (mapStatus === "partial") return "partial";
  if (mapStatus === "blocked") return "blocked";
  return "mapped";
}

export function toViewModel(document: AtlasDocument): AtlasViewModel {
  const coverage = coverageOf(document);
  const outgoing = new Map<string, string[]>();
  for (const edge of document.edges) {
    const list = outgoing.get(edge.fromScreenId) ?? [];
    list.push(edge.actionHint);
    outgoing.set(edge.fromScreenId, list);
  }

  const screens: AtlasScreen[] = document.screens.map((screen) => {
    const label = (screen.label && screen.label.trim()) || humanizeSlotName(screen.purpose);
    const slots = screen.factSlots.map((slot) => ({
      ...slot,
      maskedValue: MASKED_SLOT_DISPLAY,
      displayLabel: humanizeSlotName(slot.name),
    }));
    const landmarks = [
      ...slots.slice(0, 2).map((slot) => slot.displayLabel),
      ...(outgoing.get(screen.screenId) ?? []).slice(0, 2),
    ].slice(0, 3);
    return {
      screenId: screen.screenId,
      label,
      purpose: screen.purpose,
      region: regionForPurpose(screen.purpose),
      factSlots: slots,
      risk: screen.risk,
      confidence: screen.confidence,
      lastObservedAt: screen.lastObservedAt,
      lastVerifiedAt: screen.lastVerifiedAt,
      layout: {
        x: Number.isFinite(screen.layout?.x) ? screen.layout.x : 0,
        y: Number.isFinite(screen.layout?.y) ? screen.layout.y : 0,
      },
      landmarks,
      tone: screenTone(screen, document.mapStatus),
    };
  });

  return {
    place: document.place,
    persona: document.persona,
    mapStatus: document.mapStatus,
    screens,
    edges: document.edges.map((edge) => ({
      edgeId: edge.edgeId,
      fromScreenId: edge.fromScreenId,
      toScreenId: edge.toScreenId,
      actionHint: edge.actionHint,
      risk: edge.risk,
      confidence: edge.confidence,
      lastVerifiedAt: edge.lastVerifiedAt,
    })),
    capabilities: [...document.capabilities],
    coverage,
    confidence: document.confidence,
    lastObservedAt: document.lastObservedAt,
    lastVerifiedAt: document.lastVerifiedAt,
  };
}

export function emptyViewModel(place: AtlasPlace, persona: Persona): AtlasViewModel {
  return toViewModel({
    place,
    persona,
    mapStatus: "unmapped",
    screens: [],
    edges: [],
    capabilities: [],
    confidence: 0,
    lastObservedAt: null,
    lastVerifiedAt: null,
  });
}

export function applyBoardFilters(model: AtlasViewModel, filters: BoardFilters): AtlasViewModel {
  const unmappedDoors = Boolean(filters.unmappedDoors);
  const active = Boolean(filters.stale || filters.blocked || filters.danger || unmappedDoors);
  if (!active) return model;
  const darkEdges = model.edges.filter((edge) => !Number.isFinite(edge.confidence) || edge.confidence < DARK_CONFIDENCE);
  const darkFrom = new Set(darkEdges.map((edge) => edge.fromScreenId));
  const darkTo = new Set(darkEdges.map((edge) => edge.toScreenId));
  const visible = model.screens.filter((screen) => {
    if (filters.danger && (screen.tone === "danger" || screen.risk.danger)) return true;
    if (filters.blocked && screen.tone === "blocked") return true;
    if (filters.stale && (screen.tone === "stale" || screen.tone === "dark")) return true;
    if (unmappedDoors && (darkFrom.has(screen.screenId) || darkTo.has(screen.screenId))) return true;
    return false;
  });
  const ids = new Set(visible.map((screen) => screen.screenId));
  const onlyDarkDoors = unmappedDoors && !filters.stale && !filters.blocked && !filters.danger;
  const edges = onlyDarkDoors
    ? darkEdges.filter((edge) => ids.has(edge.fromScreenId) && ids.has(edge.toScreenId))
    : model.edges.filter((edge) => ids.has(edge.fromScreenId) && ids.has(edge.toScreenId));
  return {
    ...model,
    screens: visible,
    edges,
  };
}

export function resolveSelection(model: AtlasViewModel, screenId: string | null): string | null {
  if (!screenId) return null;
  return model.screens.some((screen) => screen.screenId === screenId) ? screenId : null;
}

export function resolveEdgeSelection(model: AtlasViewModel, edgeId: string | null): string | null {
  if (!edgeId) return null;
  return model.edges.some((edge) => edge.edgeId === edgeId) ? edgeId : null;
}

export function inspectorState(model: AtlasViewModel, screenId: string | null): InspectorState {
  const id = resolveSelection(model, screenId);
  if (!id) {
    return {
      kind: "empty",
      title: "Select a room",
      subtitle: "Click a screen card to see purpose, masked fact slots, and doors.",
      factSlots: [],
      doors: [],
      frameCopy: "No room selected.",
    };
  }
  const screen = model.screens.find((item) => item.screenId === id)!;
  const labelById = new Map(model.screens.map((item) => [item.screenId, item.label]));
  const doors: InspectorDoor[] = model.edges
    .filter((edge) => edge.fromScreenId === screen.screenId)
    .map((edge) => ({
      edgeId: edge.edgeId,
      actionHint: edge.actionHint,
      toScreenId: edge.toScreenId,
      toLabel: labelById.get(edge.toScreenId) ?? edge.toScreenId,
      risk: edge.risk,
      confidence: edge.confidence,
      dark: edge.confidence < DARK_CONFIDENCE,
    }));
  return {
    kind: "screen",
    title: screen.label,
    subtitle: `${screen.region} · ${statusLabel(screen.tone)}`,
    purpose: screen.purpose,
    region: screen.region,
    tone: screen.tone,
    confidence: screen.confidence,
    lastObservedAt: screen.lastObservedAt,
    lastVerifiedAt: screen.lastVerifiedAt,
    factSlots: screen.factSlots.map((slot) => ({
      name: slot.name,
      displayLabel: slot.displayLabel,
      maskedValue: slot.maskedValue,
      description: slot.description ?? "How to read this slot on the phone, not a stored value.",
      required: slot.required,
    })),
    doors,
    frameCopy: "Redacted frame · vault fields stripped on the phone before a still leaves the device.",
  };
}

export function inspectorStateForEdge(model: AtlasViewModel, edgeId: string | null): InspectorState {
  const id = resolveEdgeSelection(model, edgeId);
  if (!id) {
    return {
      kind: "empty",
      title: "Select a room",
      subtitle: "Click a screen card to see purpose, masked fact slots, and doors.",
      factSlots: [],
      doors: [],
      frameCopy: "No room selected.",
    };
  }
  const edge = model.edges.find((item) => item.edgeId === id)!;
  const labelById = new Map(model.screens.map((item) => [item.screenId, item.label]));
  const fromLabel = labelById.get(edge.fromScreenId) ?? edge.fromScreenId;
  const toLabel = labelById.get(edge.toScreenId) ?? edge.toScreenId;
  return {
    kind: "edge",
    title: edge.actionHint,
    subtitle: `${fromLabel} → ${toLabel}`,
    actionHint: edge.actionHint,
    fromLabel,
    toLabel,
    fromScreenId: edge.fromScreenId,
    toScreenId: edge.toScreenId,
    confidence: edge.confidence,
    lastVerifiedAt: edge.lastVerifiedAt,
    dark: !Number.isFinite(edge.confidence) || edge.confidence < DARK_CONFIDENCE,
    factSlots: [],
    doors: [],
    frameCopy: "Door proof · English action from the phone atlas.",
  };
}

export function statusLabel(status: MapStatus | ScreenTone): string {
  switch (status) {
    case "unmapped":
      return "Not mapped";
    case "partial":
      return "Partial";
    case "mapped":
      return "Mapped";
    case "stale":
      return "Stale";
    case "blocked":
      return "Blocked";
    case "danger":
      return "Danger";
    case "dark":
      return "Dark";
    default:
      return status;
  }
}

export function formatCoverage(coverage: AtlasCoverage): string {
  return `${coverage.screens} screens · ${coverage.doors} doors · ${coverage.dark} dark`;
}

export function isLeafPurpose(purpose: string): boolean {
  return LEAF_PURPOSES.has(purpose);
}

const PURPOSE_GLYPH: Record<string, string> = {
  login: "LG",
  signup: "SU",
  inbox: "IN",
  compose: "CO",
  payment: "PY",
  checkout: "CK",
  search: "SR",
  settings: "ST",
  thread: "TH",
  message: "MS",
  identity: "ID",
  "account-switcher": "AC",
  "add-account": "AA",
  feed: "FD",
  "dm-list": "DM",
  thread_dm: "TD",
};

export function purposeGlyph(purpose: string): string {
  const key = purpose.trim().toLowerCase();
  if (PURPOSE_GLYPH[key]) return PURPOSE_GLYPH[key];
  const parts = key.split(/[-_\s]+/).filter(Boolean);
  if (parts.length >= 2) return `${parts[0][0] ?? ""}${parts[1][0] ?? ""}`.toUpperCase();
  const compact = key.replace(/[^a-z0-9]/g, "");
  return (compact.slice(0, 2) || "·").toUpperCase();
}

export function capabilityGlyph(name: string): string {
  const parts = name.split(/[_-]+/).filter(Boolean);
  if (!parts.length) return "";
  if (parts.length === 1) return parts[0].slice(0, 3).toUpperCase();
  return parts.map((part) => part[0] ?? "").join("").slice(0, 4).toUpperCase();
}

export function cardGlyphs(purpose: string, capabilities: string[] = []): string[] {
  const glyphs = [purposeGlyph(purpose)];
  for (const cap of capabilities) {
    if (glyphs.length >= 3) break;
    const glyph = capabilityGlyph(cap);
    if (glyph && !glyphs.includes(glyph)) glyphs.push(glyph);
  }
  return glyphs;
}

const PLACE_ID_PATTERN = /^(package:[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+|chrome:https?:\/\/[^\s/]+(?::[0-9]{1,5})?)$/;
const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ORIGIN_PATTERN = /^https?:\/\/[^\s/]+(?::[0-9]{1,5})?$/;
const SLOT_NAME_PATTERN = /^(?!.*(?:password|passcode|passwd|pin|otp|token|secret|api_key|authorization|cookie|cvv|credential|typed_text|typed_value))[a-z][a-z0-9._-]*$/;
const RISK_CLASS_PATTERN = /^[a-z][a-z0-9._-]*$/;
const CAPABILITY_PATTERN = /^[A-Za-z][A-Za-z0-9._-]*$/;
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

const DOCUMENT_KEYS = [
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
const PLACE_KEYS = ["placeId", "kind", "label", "packageName", "origin"] as const;
const SCREEN_KEYS = [
  "screenId",
  "label",
  "purpose",
  "factSlots",
  "risk",
  "confidence",
  "lastObservedAt",
  "lastVerifiedAt",
  "layout",
] as const;
const EDGE_KEYS = ["edgeId", "fromScreenId", "toScreenId", "actionHint", "risk", "confidence", "lastVerifiedAt"] as const;
const SLOT_KEYS = ["name", "factType", "required", "description"] as const;
const SUMMARY_KEYS = ["place", "persona", "mapStatus", "confidence", "lastObservedAt", "lastVerifiedAt"] as const;

export interface ValidationIssue {
  path: string;
  message: string;
}

export function validateAtlasDocument(input: unknown): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (!isRecord(input)) {
    return [{ path: "", message: "atlas document must be an object" }];
  }
  extraKeys(input, DOCUMENT_KEYS, "", issues);
  requireKeys(input, DOCUMENT_KEYS, "", issues);
  validatePlace(input.place, "place", issues);
  if (!isPersona(input.persona)) issues.push({ path: "persona", message: "must be live or mapping" });
  if (!isMapStatus(input.mapStatus)) issues.push({ path: "mapStatus", message: "invalid mapStatus" });
  validateConfidence(input.confidence, "confidence", issues);
  validateTimestamp(input.lastObservedAt, "lastObservedAt", issues);
  validateTimestamp(input.lastVerifiedAt, "lastVerifiedAt", issues);
  if (!Array.isArray(input.screens)) issues.push({ path: "screens", message: "must be an array" });
  else input.screens.forEach((screen, index) => validateScreen(screen, `screens[${index}]`, issues));
  if (!Array.isArray(input.edges)) issues.push({ path: "edges", message: "must be an array" });
  else input.edges.forEach((edge, index) => validateEdge(edge, `edges[${index}]`, issues));
  if (!Array.isArray(input.capabilities)) issues.push({ path: "capabilities", message: "must be an array" });
  else {
    const seen = new Set<string>();
    input.capabilities.forEach((cap, index) => {
      const path = `capabilities[${index}]`;
      if (typeof cap !== "string" || !CAPABILITY_PATTERN.test(cap) || cap.length > 80) {
        issues.push({ path, message: "invalid capability" });
      } else if (seen.has(cap)) issues.push({ path, message: "capabilities must be unique" });
      else seen.add(cap);
    });
  }
  return issues;
}

export function validatePlaceSummary(input: unknown): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (!isRecord(input)) return [{ path: "", message: "place summary must be an object" }];
  extraKeys(input, SUMMARY_KEYS, "", issues);
  requireKeys(input, SUMMARY_KEYS, "", issues);
  validatePlace(input.place, "place", issues);
  if (!isPersona(input.persona)) issues.push({ path: "persona", message: "must be live or mapping" });
  if (!isMapStatus(input.mapStatus)) issues.push({ path: "mapStatus", message: "invalid mapStatus" });
  validateConfidence(input.confidence, "confidence", issues);
  validateTimestamp(input.lastObservedAt, "lastObservedAt", issues);
  validateTimestamp(input.lastVerifiedAt, "lastVerifiedAt", issues);
  return issues;
}

function validatePlace(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(value)) {
    issues.push({ path, message: "place must be an object" });
    return;
  }
  extraKeys(value, PLACE_KEYS, path, issues);
  requireKeys(value, ["placeId", "kind", "label"], path, issues);
  if (typeof value.placeId !== "string" || value.placeId.length < 9 || value.placeId.length > 512 || !PLACE_ID_PATTERN.test(value.placeId)) {
    issues.push({ path: `${path}.placeId`, message: "invalid placeId" });
  }
  if (typeof value.label !== "string" || value.label.length < 1 || value.label.length > 120) {
    issues.push({ path: `${path}.label`, message: "invalid label" });
  }
  if (value.kind === "package") {
    if (typeof value.packageName !== "string" || !PACKAGE_PATTERN.test(value.packageName)) {
      issues.push({ path: `${path}.packageName`, message: "package places require packageName" });
    }
    if (typeof value.placeId === "string" && !value.placeId.startsWith("package:")) {
      issues.push({ path: `${path}.placeId`, message: "package placeId must start with package:" });
    }
  } else if (value.kind === "chrome-origin") {
    if (typeof value.origin !== "string" || !ORIGIN_PATTERN.test(value.origin)) {
      issues.push({ path: `${path}.origin`, message: "chrome places require origin" });
    }
    if (typeof value.placeId === "string" && !value.placeId.startsWith("chrome:")) {
      issues.push({ path: `${path}.placeId`, message: "chrome placeId must start with chrome:" });
    }
  } else {
    issues.push({ path: `${path}.kind`, message: "kind must be package or chrome-origin" });
  }
}

function validateScreen(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(value)) {
    issues.push({ path, message: "screen must be an object" });
    return;
  }
  extraKeys(value, SCREEN_KEYS, path, issues);
  requireKeys(value, ["screenId", "purpose", "factSlots", "risk", "confidence", "lastObservedAt", "lastVerifiedAt", "layout"], path, issues);
  if (typeof value.screenId !== "string" || value.screenId.length < 1 || value.screenId.length > 160) {
    issues.push({ path: `${path}.screenId`, message: "invalid screenId" });
  }
  if (value.label != null && (typeof value.label !== "string" || value.label.length > 120)) {
    issues.push({ path: `${path}.label`, message: "invalid label" });
  }
  if (typeof value.purpose !== "string" || value.purpose.length < 1 || value.purpose.length > 200) {
    issues.push({ path: `${path}.purpose`, message: "invalid purpose" });
  }
  if (!Array.isArray(value.factSlots) || value.factSlots.length > 64) {
    issues.push({ path: `${path}.factSlots`, message: "factSlots must be an array" });
  } else {
    value.factSlots.forEach((slot, index) => validateSlot(slot, `${path}.factSlots[${index}]`, issues));
  }
  validateRisk(value.risk, `${path}.risk`, issues);
  validateConfidence(value.confidence, `${path}.confidence`, issues);
  validateTimestamp(value.lastObservedAt, `${path}.lastObservedAt`, issues);
  validateTimestamp(value.lastVerifiedAt, `${path}.lastVerifiedAt`, issues);
  if (!isRecord(value.layout) || !Number.isFinite(value.layout.x) || !Number.isFinite(value.layout.y)) {
    issues.push({ path: `${path}.layout`, message: "layout requires finite x,y" });
  } else {
    extraKeys(value.layout, ["x", "y"], `${path}.layout`, issues);
  }
}

function validateEdge(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(value)) {
    issues.push({ path, message: "edge must be an object" });
    return;
  }
  extraKeys(value, EDGE_KEYS, path, issues);
  requireKeys(value, EDGE_KEYS, path, issues);
  for (const key of ["edgeId", "fromScreenId", "toScreenId"] as const) {
    if (typeof value[key] !== "string" || value[key].length < 1 || value[key].length > 160) {
      issues.push({ path: `${path}.${key}`, message: `invalid ${key}` });
    }
  }
  if (typeof value.actionHint !== "string" || value.actionHint.length < 1 || value.actionHint.length > 160) {
    issues.push({ path: `${path}.actionHint`, message: "invalid actionHint" });
  }
  validateRisk(value.risk, `${path}.risk`, issues);
  validateConfidence(value.confidence, `${path}.confidence`, issues);
  validateTimestamp(value.lastVerifiedAt, `${path}.lastVerifiedAt`, issues);
}

function validateSlot(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(value)) {
    issues.push({ path, message: "factSlot must be an object" });
    return;
  }
  extraKeys(value, SLOT_KEYS, path, issues);
  requireKeys(value, ["name", "factType", "required"], path, issues);
  if (typeof value.name !== "string" || !SLOT_NAME_PATTERN.test(value.name) || value.name.length > 64) {
    issues.push({ path: `${path}.name`, message: "invalid factSlot name" });
  }
  if (!["text", "boolean", "number", "timestamp", "identifier"].includes(value.factType as string)) {
    issues.push({ path: `${path}.factType`, message: "invalid factType" });
  }
  if (typeof value.required !== "boolean") issues.push({ path: `${path}.required`, message: "required must be boolean" });
  if (value.description != null && (typeof value.description !== "string" || value.description.length > 160)) {
    issues.push({ path: `${path}.description`, message: "invalid description" });
  }
}

function validateRisk(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(value)) {
    issues.push({ path, message: "risk must be an object" });
    return;
  }
  extraKeys(value, ["danger", "classes"], path, issues);
  if (typeof value.danger !== "boolean") issues.push({ path: `${path}.danger`, message: "danger must be boolean" });
  if (!Array.isArray(value.classes) || value.classes.length > 16) {
    issues.push({ path: `${path}.classes`, message: "classes must be an array" });
    return;
  }
  const seen = new Set<string>();
  value.classes.forEach((item, index) => {
    if (typeof item !== "string" || !RISK_CLASS_PATTERN.test(item) || item.length > 64) {
      issues.push({ path: `${path}.classes[${index}]`, message: "invalid risk class" });
    } else if (seen.has(item)) issues.push({ path: `${path}.classes[${index}]`, message: "classes must be unique" });
    else seen.add(item);
  });
}

function validateConfidence(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 1) {
    issues.push({ path, message: "confidence must be a number 0..1" });
  }
}

function validateTimestamp(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (value === null) return;
  if (typeof value !== "string" || !TIMESTAMP_PATTERN.test(value)) {
    issues.push({ path, message: "timestamp must be date-time or null" });
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isPersona(value: unknown): value is Persona {
  return value === "live" || value === "mapping";
}

function isMapStatus(value: unknown): value is MapStatus {
  return value === "unmapped" || value === "partial" || value === "mapped" || value === "stale" || value === "blocked";
}

function extraKeys(value: Record<string, unknown>, allowed: readonly string[], path: string, issues: ValidationIssue[]): void {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) issues.push({ path: path ? `${path}.${key}` : key, message: "additional property not allowed" });
  }
}

function requireKeys(value: Record<string, unknown>, required: readonly string[], path: string, issues: ValidationIssue[]): void {
  for (const key of required) {
    if (!(key in value)) issues.push({ path: path ? `${path}.${key}` : key, message: "missing required field" });
  }
}
