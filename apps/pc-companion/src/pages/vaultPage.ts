import {
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  GLASS_VAULT_FIXTURE_SLOTS,
  phoneSupportsGlassAtlas,
  vaultSlotPresenceLabel,
  type GlassVaultSlot,
} from "../core/fleet.js";
import type { DesktopDevice } from "../services/types.js";
import { el } from "../ui/dom.js";
import { createSecretsCard } from "../ui/secretsCard.js";

export interface VaultPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface VaultPageOptions {
  devices?: DesktopDevice[];
  mobileVersion?: string | null;
  slots?: readonly GlassVaultSlot[];
  loadSlots?: () => Promise<readonly GlassVaultSlot[]>;
  previewSlots?: boolean;
  onRequestSlot?: (slotId: string) => void;
}

const LIVE_NOTE = "Presence from the connected phone. Values stay in Android Keystore.";
const SAMPLE_NOTE = "Sample inventory — not live. secrets.slots is not on this shell.";
const PRIVACY_NOTE = "No password, OTP, cookie, or token fields exist on this page. Cyclone will not keep secrets in chat or logs.";
const EMPTY_COPY = "No vault slots to show.";
const LOADING_COPY = "Loading vault slots…";
const LOAD_ERROR_COPY = "Couldn't load vault slots from the phone.";

let vaultCssLinked = false;

function ensureVaultCss(): void {
  if (vaultCssLinked) return;
  vaultCssLinked = true;
  if (typeof document === "undefined" || !document.head) return;
  if (document.getElementById("cyclone-vault-css")) return;
  const link = document.createElement("link");
  link.id = "cyclone-vault-css";
  link.rel = "stylesheet";
  try {
    link.href = new URL("../vault.css", import.meta.url).href;
  } catch {
    link.href = "/src/vault.css";
  }
  document.head.appendChild(link);
}

export function createVaultPage(options: VaultPageOptions = {}): VaultPageHandle {
  ensureVaultCss();
  const versionPassed = Object.prototype.hasOwnProperty.call(options, "mobileVersion")
    || "mobileVersion" in options;
  const atlasReady = phoneSupportsGlassAtlas(options.mobileVersion || "");
  const preview = options.previewSlots === true;
  const onRequestSlot = options.onRequestSlot;

  const page = el("section", "page content-page vault-page");
  const header = el("header", "page-header");
  header.append(
    el("div", "vault-kicker", "CYCLONE GLASS · VAULT"),
    el("h1", "page-title", "Vault"),
    el("p", "page-subtitle", "Slot presence only. Glass never sees, stores, or displays secret values."),
  );

  const banner = el("aside", "glass-compat-banner");
  banner.hidden = atlasReady;
  banner.append(
    el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
    el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
  );

  const note = el("p", "vault-source-note");
  const list = el("div", "vault-slot-list");
  const requestHost = el("div", "vault-request-host");
  requestHost.hidden = true;
  const privacy = el("p", "vault-privacy", PRIVACY_NOTE);

  page.append(header, banner, note, list, requestHost, privacy);

  let destroyed = false;
  const known = new Map<string, GlassVaultSlot>();

  const paintNote = (kind: "live" | "sample" | "blocked"): void => {
    note.dataset.vaultSource = kind;
    if (kind === "live") {
      note.hidden = false;
      note.textContent = LIVE_NOTE;
      return;
    }
    if (kind === "sample") {
      note.hidden = false;
      note.textContent = SAMPLE_NOTE;
      return;
    }
    note.hidden = true;
    note.textContent = "";
  };

  const paintSlots = (rawSlots: readonly GlassVaultSlot[], kind: "live" | "sample" | "blocked"): void => {
    const slots = sanitizeVaultSlots(rawSlots);
    known.clear();
    for (const slot of slots) known.set(slot.id, slot);
    paintNote(kind);
    list.replaceChildren();
    if (slots.length === 0) {
      list.append(el("div", "vault-empty", EMPTY_COPY));
      return;
    }
    for (const slot of slots) {
      list.append(renderSlotRow(slot, onRequestSlot ? requestMissing : undefined));
    }
  };

  const requestMissing = (slotId: string): void => {
    const slot = known.get(slotId);
    if (!slot || slot.set) return;
    onRequestSlot?.(slotId);
    requestHost.hidden = false;
    requestHost.replaceChildren(createSecretsCard({
      slotLabel: `${slot.placeLabel} ${slot.slotLabel}`.trim() || slot.slotLabel,
      presence: "waiting",
    }));
  };

  const paintLoading = (): void => {
    paintNote("live");
    list.replaceChildren(el("div", "vault-empty vault-loading", LOADING_COPY));
  };

  const paintLoadError = (): void => {
    paintNote("live");
    list.replaceChildren(el("div", "vault-empty", LOAD_ERROR_COPY));
  };

  if (preview) {
    paintSlots(options.slots ?? GLASS_VAULT_FIXTURE_SLOTS, "sample");
  } else if (versionPassed && !atlasReady) {
    paintSlots([], "blocked");
  } else if (atlasReady && typeof options.loadSlots === "function") {
    paintLoading();
    void Promise.resolve()
      .then(() => options.loadSlots!())
      .then((rows) => {
        if (destroyed) return;
        paintSlots(Array.isArray(rows) ? rows : [], "live");
      }, () => {
        if (destroyed) return;
        paintLoadError();
      });
  } else if (options.slots) {
    paintSlots(options.slots, atlasReady ? "live" : "sample");
  } else if (!versionPassed) {
    paintSlots(GLASS_VAULT_FIXTURE_SLOTS, "sample");
  } else {
    paintSlots([], atlasReady ? "live" : "blocked");
  }

  return {
    element: page,
    destroy: () => {
      destroyed = true;
    },
  };
}

function renderSlotRow(
  slot: GlassVaultSlot,
  onRequest?: (slotId: string) => void,
): HTMLElement {
  const clickable = Boolean(onRequest) && slot.set === false;
  const row = el("article", clickable ? "vault-slot-row vault-slot-request" : "vault-slot-row");
  row.dataset.slotId = slot.id;
  row.dataset.slotSet = slot.set ? "true" : "false";
  const label = el("div", "vault-slot-label", vaultSlotPresenceLabel(slot));
  const pill = el("span", `vault-slot-pill vault-slot-${slot.set ? "set" : "missing"}`, slot.set ? "set" : "missing");
  row.append(label, pill);
  if (clickable && onRequest) {
    row.setAttribute("role", "button");
    row.tabIndex = 0;
    row.append(el("span", "vault-slot-action", "Request on phone"));
    row.addEventListener("click", () => onRequest(slot.id));
  }
  return row;
}

function sanitizeVaultSlots(slots: readonly unknown[]): GlassVaultSlot[] {
  const out: GlassVaultSlot[] = [];
  for (const raw of slots) {
    const clean = sanitizeVaultSlot(raw);
    if (clean) out.push(clean);
  }
  return out;
}

function sanitizeVaultSlot(raw: unknown): GlassVaultSlot | null {
  if (raw == null || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const id = typeof record.id === "string" ? record.id.trim() : "";
  const placeLabel = typeof record.placeLabel === "string" ? record.placeLabel.trim() : "";
  const slotLabel = typeof record.slotLabel === "string" ? record.slotLabel.trim() : "";
  if (!id || !placeLabel || !slotLabel) return null;
  if (typeof record.set !== "boolean") return null;
  return { id, placeLabel, slotLabel, set: record.set };
}
