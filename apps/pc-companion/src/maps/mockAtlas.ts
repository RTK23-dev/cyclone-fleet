import type {
  AtlasDocument,
  AtlasEdgeRecord,
  AtlasFactSlot,
  AtlasPlace,
  AtlasRisk,
  AtlasScreenRecord,
  MapStatus,
  Persona,
  PlaceSummary,
} from "./atlasViewModel.js";

export const GMAIL_PLACE_ID = "package:com.google.android.gm";
export const FACEBOOK_PLACE_ID = "chrome:https://m.facebook.com";
export const UNMAPPED_PLACE_ID = "package:com.google.android.youtube";

export const GMAIL_LIVE_SCREEN_COUNT = 8;
export const GMAIL_REQUIRED_ROOMS = ["Account", "Inbox", "Message", "Compose", "Settings"] as const;

const OBSERVED = "2026-09-20T16:40:00Z";
const VERIFIED = "2026-09-19T11:12:00Z";
const MAPPING_OBSERVED = "2026-09-18T09:04:00Z";
const MAPPING_VERIFIED = "2026-09-18T09:30:00Z";

const GMAIL_PLACE: AtlasPlace = {
  placeId: GMAIL_PLACE_ID,
  kind: "package",
  label: "Gmail",
  packageName: "com.google.android.gm",
};

const FACEBOOK_PLACE: AtlasPlace = {
  placeId: FACEBOOK_PLACE_ID,
  kind: "chrome-origin",
  label: "Chrome · facebook",
  origin: "https://m.facebook.com",
};

const UNMAPPED_PLACE: AtlasPlace = {
  placeId: UNMAPPED_PLACE_ID,
  kind: "package",
  label: "YouTube",
  packageName: "com.google.android.youtube",
};

const SAFE: AtlasRisk = { danger: false, classes: [] };
const PAY: AtlasRisk = { danger: true, classes: ["payment"] };

function slot(name: string, factType: AtlasFactSlot["factType"], required: boolean, description: string): AtlasFactSlot {
  return { name, factType, required, description };
}

function screen(
  screenId: string,
  label: string,
  purpose: string,
  layout: { x: number; y: number },
  confidence: number,
  factSlots: AtlasFactSlot[],
  timestamps: { lastObservedAt: string | null; lastVerifiedAt: string | null },
  risk: AtlasRisk = SAFE,
): AtlasScreenRecord {
  return {
    screenId,
    label,
    purpose,
    factSlots,
    risk,
    confidence,
    lastObservedAt: timestamps.lastObservedAt,
    lastVerifiedAt: timestamps.lastVerifiedAt,
    layout,
  };
}

function edge(
  edgeId: string,
  fromScreenId: string,
  toScreenId: string,
  actionHint: string,
  confidence: number,
  lastVerifiedAt: string | null,
  risk: AtlasRisk = SAFE,
): AtlasEdgeRecord {
  return { edgeId, fromScreenId, toScreenId, actionHint, risk, confidence, lastVerifiedAt };
}

function documentOf(
  place: AtlasPlace,
  persona: Persona,
  mapStatus: MapStatus,
  screens: AtlasScreenRecord[],
  edges: AtlasEdgeRecord[],
  capabilities: string[],
  confidence: number,
  lastObservedAt: string | null,
  lastVerifiedAt: string | null,
): AtlasDocument {
  return { place, persona, mapStatus, screens, edges, capabilities, confidence, lastObservedAt, lastVerifiedAt };
}

const GMAIL_LIVE_SCREENS: AtlasScreenRecord[] = [
  screen(
    "gmail.account-switcher",
    "Account",
    "account-switcher",
    { x: 48, y: 48 },
    0.94,
    [
      slot(
        "signed-in-email",
        "identifier",
        true,
        "How to read the current identity row in the account header.",
      ),
      slot("account-count", "number", false, "How many accounts are listed in the switcher."),
    ],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.add-account",
    "Add account",
    "add-account",
    { x: 48, y: 248 },
    0.81,
    [slot("add-account-row", "boolean", true, "Whether the add-account row is visible.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.inbox",
    "Inbox",
    "inbox",
    { x: 360, y: 48 },
    0.91,
    [
      slot("unread-badge", "boolean", false, "Whether the inbox header shows unread mail."),
      slot("primary-tab-selected", "boolean", false, "Whether Primary is the selected inbox tab."),
    ],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.thread",
    "Message",
    "thread",
    { x: 360, y: 248 },
    0.86,
    [slot("message-subject-row", "text", false, "Where the subject line sits in the thread header.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.search",
    "Search",
    "search",
    { x: 360, y: 448 },
    0.31,
    [slot("query-field-present", "boolean", true, "Whether the search field is on screen.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: null },
  ),
  screen(
    "gmail.compose",
    "Compose",
    "compose",
    { x: 672, y: 48 },
    0.88,
    [slot("to-field-present", "boolean", true, "Whether the To field is on the compose sheet.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.settings",
    "Settings",
    "settings",
    { x: 672, y: 248 },
    0.77,
    [slot("general-row", "boolean", false, "Whether General is listed in settings.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
  ),
  screen(
    "gmail.storage",
    "Storage offer",
    "payment",
    { x: 672, y: 448 },
    0.71,
    [slot("offer-sheet-present", "boolean", false, "Whether a storage-upgrade sheet is on screen.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: VERIFIED },
    PAY,
  ),
];

const GMAIL_LIVE_EDGES: AtlasEdgeRecord[] = [
  edge("gmail.inbox.avatar", "gmail.inbox", "gmail.account-switcher", "open avatar", 0.93, VERIFIED),
  edge("gmail.account.back", "gmail.account-switcher", "gmail.inbox", "back to inbox", 0.9, VERIFIED),
  edge("gmail.account.add", "gmail.account-switcher", "gmail.add-account", "add another account", 0.8, VERIFIED),
  edge("gmail.inbox.message", "gmail.inbox", "gmail.thread", "open message", 0.89, VERIFIED),
  edge("gmail.thread.back", "gmail.thread", "gmail.inbox", "back to inbox", 0.9, VERIFIED),
  edge("gmail.inbox.compose", "gmail.inbox", "gmail.compose", "compose", 0.92, VERIFIED),
  edge("gmail.compose.back", "gmail.compose", "gmail.inbox", "close compose", 0.87, VERIFIED),
  edge("gmail.inbox.settings", "gmail.inbox", "gmail.settings", "open settings", 0.74, VERIFIED),
  edge("gmail.settings.back", "gmail.settings", "gmail.inbox", "navigate back", 0.78, VERIFIED),
  edge("gmail.inbox.search", "gmail.inbox", "gmail.search", "open search", 0.34, null),
  edge("gmail.settings.storage", "gmail.settings", "gmail.storage", "open storage offer", 0.7, VERIFIED, PAY),
];

const GMAIL_CAPABILITIES = [
  "FIND_SIGNED_IN_IDENTITY",
  "OPEN_COMPOSE",
  "OPEN_THREAD",
  "SEARCH_MAIL",
  "OPEN_SETTINGS",
  "SESSION_STATUS",
];

const GMAIL_MAPPING_SCREENS: AtlasScreenRecord[] = GMAIL_LIVE_SCREENS.map((item) => {
  if (item.screenId === "gmail.search") {
    return {
      ...item,
      confidence: 0.84,
      lastObservedAt: MAPPING_OBSERVED,
      lastVerifiedAt: MAPPING_VERIFIED,
    };
  }
  return {
    ...item,
    confidence: Math.min(1, Math.round((item.confidence + 0.04) * 100) / 100),
    lastObservedAt: MAPPING_OBSERVED,
    lastVerifiedAt: MAPPING_VERIFIED,
  };
});

const GMAIL_MAPPING_EDGES: AtlasEdgeRecord[] = GMAIL_LIVE_EDGES.map((item) => ({
  ...item,
  confidence: item.edgeId.endsWith("search") ? 0.82 : Math.min(1, Math.round((item.confidence + 0.03) * 100) / 100),
  lastVerifiedAt: MAPPING_VERIFIED,
}));

const FACEBOOK_LIVE_SCREENS: AtlasScreenRecord[] = [
  screen(
    "facebook.login",
    "Login",
    "login",
    { x: 80, y: 140 },
    0.42,
    [slot("session-status", "boolean", true, "Whether a session is already open.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: null },
  ),
  screen(
    "facebook.feed",
    "Feed",
    "feed",
    { x: 400, y: 140 },
    0.22,
    [slot("composer-row", "boolean", false, "Whether the status composer is on the feed.")],
    { lastObservedAt: OBSERVED, lastVerifiedAt: null },
  ),
  screen(
    "facebook.dm-list",
    "DMs",
    "dm-list",
    { x: 720, y: 140 },
    0.18,
    [slot("thread-list-present", "boolean", false, "Whether a conversation list is on screen.")],
    { lastObservedAt: null, lastVerifiedAt: null },
  ),
];

const FACEBOOK_LIVE_EDGES: AtlasEdgeRecord[] = [
  edge("facebook.login.feed", "facebook.login", "facebook.feed", "continue into feed", 0.2, null),
  edge("facebook.feed.dms", "facebook.feed", "facebook.dm-list", "open DMs", 0.16, null),
];

const FACEBOOK_MAPPING_SCREENS: AtlasScreenRecord[] = [
  screen(
    "facebook.login",
    "Login",
    "login",
    { x: 80, y: 140 },
    0.9,
    [slot("session-status", "boolean", true, "Whether a dummy session is already open.")],
    { lastObservedAt: MAPPING_OBSERVED, lastVerifiedAt: MAPPING_VERIFIED },
  ),
  screen(
    "facebook.feed",
    "Feed",
    "feed",
    { x: 400, y: 140 },
    0.86,
    [slot("composer-row", "boolean", false, "Whether the status composer is on the feed.")],
    { lastObservedAt: MAPPING_OBSERVED, lastVerifiedAt: MAPPING_VERIFIED },
  ),
  screen(
    "facebook.dm-list",
    "DMs",
    "dm-list",
    { x: 720, y: 140 },
    0.8,
    [slot("thread-list-present", "boolean", true, "Whether a conversation list is on screen.")],
    { lastObservedAt: MAPPING_OBSERVED, lastVerifiedAt: MAPPING_VERIFIED },
  ),
];

const FACEBOOK_MAPPING_EDGES: AtlasEdgeRecord[] = [
  edge("facebook.login.feed", "facebook.login", "facebook.feed", "continue into feed", 0.88, MAPPING_VERIFIED),
  edge("facebook.feed.dms", "facebook.feed", "facebook.dm-list", "open DMs", 0.84, MAPPING_VERIFIED),
  edge("facebook.dms.back", "facebook.dm-list", "facebook.feed", "back to feed", 0.81, MAPPING_VERIFIED),
];

const FACEBOOK_CAPABILITIES = ["OPEN_FEED", "OPEN_DM", "SESSION_STATUS"];

const GMAIL_LIVE = documentOf(
  GMAIL_PLACE,
  "live",
  "partial",
  GMAIL_LIVE_SCREENS,
  GMAIL_LIVE_EDGES,
  GMAIL_CAPABILITIES,
  0.82,
  OBSERVED,
  VERIFIED,
);

const GMAIL_MAPPING = documentOf(
  GMAIL_PLACE,
  "mapping",
  "mapped",
  GMAIL_MAPPING_SCREENS,
  GMAIL_MAPPING_EDGES,
  GMAIL_CAPABILITIES,
  0.91,
  MAPPING_OBSERVED,
  MAPPING_VERIFIED,
);

const FACEBOOK_LIVE = documentOf(
  FACEBOOK_PLACE,
  "live",
  "blocked",
  FACEBOOK_LIVE_SCREENS,
  FACEBOOK_LIVE_EDGES,
  FACEBOOK_CAPABILITIES,
  0.28,
  OBSERVED,
  null,
);

const FACEBOOK_MAPPING = documentOf(
  FACEBOOK_PLACE,
  "mapping",
  "partial",
  FACEBOOK_MAPPING_SCREENS,
  FACEBOOK_MAPPING_EDGES,
  FACEBOOK_CAPABILITIES,
  0.84,
  MAPPING_OBSERVED,
  MAPPING_VERIFIED,
);

const YOUTUBE_LIVE = documentOf(UNMAPPED_PLACE, "live", "unmapped", [], [], [], 0, null, null);
const YOUTUBE_MAPPING = documentOf(UNMAPPED_PLACE, "mapping", "unmapped", [], [], [], 0, null, null);

const DOCUMENTS: AtlasDocument[] = [
  GMAIL_LIVE,
  GMAIL_MAPPING,
  FACEBOOK_LIVE,
  FACEBOOK_MAPPING,
  YOUTUBE_LIVE,
  YOUTUBE_MAPPING,
];

function summaryOf(document: AtlasDocument): PlaceSummary {
  return {
    place: document.place,
    persona: document.persona,
    mapStatus: document.mapStatus,
    confidence: document.confidence,
    lastObservedAt: document.lastObservedAt,
    lastVerifiedAt: document.lastVerifiedAt,
  };
}

export interface MapsDataSource {
  listSummaries(persona: Persona): PlaceSummary[];
  getDocument(placeId: string, persona: Persona): AtlasDocument | null;
}

export function listMockDocuments(): AtlasDocument[] {
  return DOCUMENTS.map((document) => structuredClone(document));
}

export function listMockSummaries(persona: Persona): PlaceSummary[] {
  return DOCUMENTS.filter((document) => document.persona === persona).map((document) => summaryOf(structuredClone(document)));
}

export function getMockDocument(placeId: string, persona: Persona): AtlasDocument | null {
  const found = DOCUMENTS.find((document) => document.place.placeId === placeId && document.persona === persona);
  return found ? structuredClone(found) : null;
}

export const mockMapsDataSource: MapsDataSource = {
  listSummaries: listMockSummaries,
  getDocument: getMockDocument,
};

export function defaultMapsPlaceId(): string {
  return GMAIL_PLACE_ID;
}
