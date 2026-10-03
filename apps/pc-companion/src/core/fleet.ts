import type { DesktopDevice } from "../services/types.js";

export type AppRoute =
  | "home"
  | "fleet"
  | "focused"
  | "ask"
  | "maps"
  | "vault"
  | "automations"
  | "connections"
  | "chatgpt"
  | "settings";

export type AskRunState = "working" | "action-needed" | "needs-secret" | "done" | "failed";

export const ASK_RUN_STATES: readonly AskRunState[] = [
  "working",
  "action-needed",
  "needs-secret",
  "done",
  "failed",
];

export const GLASS_UPDATE_PHONE_TITLE = "Update Cyclone on the phone";
export const GLASS_UPDATE_PHONE_COPY =
  "Update Cyclone Mobile to 5.0 to use Ask atlas, Maps, and Vault on Glass.";
export const GLASS_SECRET_PRIVACY_COPY = "Cyclone will not keep this in chat or logs.";
export const GLASS_SECRET_PHONE_COPY =
  "Enter the secret on the phone. Glass does not store passwords.";

export interface CompanionState {
  route: AppRoute;
  devices: DesktopDevice[];
  focusedDeviceId: string | null;
  focusedSessionId: string | null;
}

export type CompanionAction =
  | { type: "devices_updated"; devices: DesktopDevice[] }
  | { type: "focus_device"; deviceId: string }
  | { type: "focus_session"; sessionId: string | null }
  | { type: "back_to_fleet" }
  | { type: "navigate"; route: Exclude<AppRoute, "focused"> };

export interface GlassAskMilestone {
  label: string;
  state: "pending" | "active" | "done" | "action-needed" | "failed";
}

export interface GlassAskSnapshot {
  state: AskRunState;
  title: string;
  slotLabel?: string;
  supportingCopy?: string;
  sessionId?: string;
  milestones?: GlassAskMilestone[];
}

export interface GlassVaultSlot {
  id: string;
  placeLabel: string;
  slotLabel: string;
  set: boolean;
}

export interface AskHudDescription {
  state: AskRunState;
  waitTitle: string | null;
  isFailure: boolean;
  showsSecretsCard: boolean;
}

/** Slot presence only. Never a secret value. */
export const GLASS_VAULT_FIXTURE_SLOTS: readonly GlassVaultSlot[] = [
  { id: "facebook-password", placeLabel: "Facebook", slotLabel: "password", set: true },
  { id: "gmail-password", placeLabel: "Gmail", slotLabel: "password", set: false },
  { id: "dummy-gmail-password", placeLabel: "Dummy Gmail", slotLabel: "password", set: true },
];

export const ASK_NEEDS_SECRET_FIXTURE: GlassAskSnapshot = {
  state: "needs-secret",
  title: "Facebook needs a password",
  slotLabel: "Facebook password",
  supportingCopy: "Secure input is required to continue.",
  sessionId: "default-foreground",
  milestones: [
    { label: "Open Facebook", state: "done" },
    { label: "Find the login wall", state: "action-needed" },
  ],
};

export const ASK_SAMPLE_SNAPSHOTS: Record<AskRunState, GlassAskSnapshot> = {
  working: {
    state: "working",
    title: "Opening Facebook",
    supportingCopy: "The phone is working the same run as the overlay.",
    sessionId: "default-foreground",
    milestones: [{ label: "Open Facebook", state: "active" }],
  },
  "action-needed": {
    state: "action-needed",
    title: "Needs a tap on the phone",
    supportingCopy: "Take control from Phone if you want to finish this step yourself.",
    sessionId: "default-foreground",
    milestones: [{ label: "Confirm the next step", state: "action-needed" }],
  },
  "needs-secret": ASK_NEEDS_SECRET_FIXTURE,
  done: {
    state: "done",
    title: "Signed-in state checked",
    supportingCopy: "The requested result was checked.",
    sessionId: "default-foreground",
    milestones: [{ label: "Check login status", state: "done" }],
  },
  failed: {
    state: "failed",
    title: "Couldn't finish",
    supportingCopy: "This run failed. A password wall is needs-secret, not this state.",
    sessionId: "default-foreground",
    milestones: [{ label: "Check login status", state: "failed" }],
  },
};

export function initialCompanionState(devices: DesktopDevice[] = []): CompanionState {
  return { route: "home", devices, focusedDeviceId: null, focusedSessionId: null };
}

const GLASS_KEEP_FOCUS_ROUTES: ReadonlySet<Exclude<AppRoute, "focused">> = new Set(["ask", "maps", "vault"]);

export function reduceCompanionState(state: CompanionState, action: CompanionAction): CompanionState {
  switch (action.type) {
    case "devices_updated": {
      const focusedStillExists = state.focusedDeviceId == null || action.devices.some((d) => d.id === state.focusedDeviceId);
      return {
        ...state,
        devices: [...action.devices],
        ...(focusedStillExists ? {} : { route: "fleet" as const, focusedDeviceId: null, focusedSessionId: null }),
      };
    }
    case "focus_device":
      if (!state.devices.some((device) => device.id === action.deviceId)) return state;
      return { ...state, route: "focused", focusedDeviceId: action.deviceId };
    case "focus_session": {
      const trimmed = String(action.sessionId ?? "").trim();
      return { ...state, focusedSessionId: trimmed ? trimmed : null };
    }
    case "back_to_fleet":
      return { ...state, route: "fleet", focusedDeviceId: null, focusedSessionId: null };
    case "navigate":
      if (GLASS_KEEP_FOCUS_ROUTES.has(action.route)) {
        return { ...state, route: action.route };
      }
      return { ...state, route: action.route, focusedDeviceId: null, focusedSessionId: null };
  }
}

export function canPreserveFocusedPage(state: CompanionState, devices: DesktopDevice[]): boolean {
  return state.route === "focused"
    && state.focusedDeviceId != null
    && devices.some((device) => device.id === state.focusedDeviceId);
}

/** True for Mobile 5.x and later. False for 4.x, empty, or unparseable versions. */
export function phoneSupportsGlassAtlas(version: string): boolean {
  const raw = String(version ?? "").trim().replace(/^v/i, "");
  const major = Number.parseInt(raw, 10);
  return Number.isFinite(major) && major >= 5;
}

export function needsSecretWaitTitle(slotLabel: string): string {
  const label = slotLabel.trim() || "secret";
  return `Needs you — ${label}`;
}

export function vaultSlotPresenceLabel(slot: GlassVaultSlot): string {
  return `${slot.placeLabel} ${slot.slotLabel}: ${slot.set ? "set" : "missing"}`;
}

/**
 * Map `secrets.slots` boolean presence onto Glass rows.
 * Non-boolean values are dropped (fail closed). Never copies a value field.
 * Slot names such as `password` are allowed as keys when the value is boolean.
 */
export function slotsFromPresence(placeLabel: string, slots: Record<string, boolean>): GlassVaultSlot[] {
  const place = String(placeLabel ?? "").trim();
  if (!place) return [];
  if (slots == null || typeof slots !== "object" || Array.isArray(slots)) return [];
  const placeSlug = slugVaultToken(place);
  const out: GlassVaultSlot[] = [];
  for (const [rawName, present] of Object.entries(slots)) {
    if (typeof present !== "boolean") continue;
    const slotLabel = String(rawName ?? "").trim();
    if (!slotLabel) continue;
    out.push({
      id: `${placeSlug}-${slugVaultToken(slotLabel)}`,
      placeLabel: place,
      slotLabel,
      set: present,
    });
  }
  return out;
}

function slugVaultToken(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "slot";
}

export function describeAskHud(snapshot: GlassAskSnapshot): AskHudDescription {
  const waiting = snapshot.state === "needs-secret";
  return {
    state: snapshot.state,
    waitTitle: waiting ? needsSecretWaitTitle(snapshot.slotLabel || "secret") : null,
    isFailure: snapshot.state === "failed",
    showsSecretsCard: waiting,
  };
}

export function vaultFixtureContainsSecretValues(
  slots: readonly GlassVaultSlot[] = GLASS_VAULT_FIXTURE_SLOTS,
): boolean {
  return slots.some((slot) => {
    if (typeof slot.set !== "boolean") return true;
    const blob = `${slot.id} ${slot.placeLabel} ${slot.slotLabel}`;
    return /hunter2|otp\s*[:=]|cookie\s*[:=]|token\s*[:=]/i.test(blob);
  });
}
