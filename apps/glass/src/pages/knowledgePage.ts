/**
 * Knowledge (V5 plan 03): what Cyclone knows beyond maps. Vault slots show only set / not set; values never leave
 * the phone. Skills and automations are what the user taught or approved. Totals come from the phone's Atlas.
 */
import type { GlassContext } from "../app.js";
import { appName } from "../services/runs.js";
import { dangerLabel, getKnowledge, type GuardedRow, type KnowledgeSummary, type VaultSlot } from "../services/knowledgeSummary.js";
import { actionButton, card, chip, emptyState, loadingState, pageHeader, statTile } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { icon } from "../ui/icons.js";
import { deviceGate } from "./deviceGate.js";
import { knowledgeError } from "./appKnowledgePage.js";
import type { GlassPage } from "./page.js";

export function createKnowledgePage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-knowledge-home");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Knowledge", "What Cyclone on the phone knows beyond maps. Secrets show only whether they are set.", [refresh]));
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const body = el("div", "knowledge-body");
  element.append(body);
  let controller = new AbortController();

  const load = async (): Promise<void> => {
    controller.abort();
    controller = new AbortController();
    setChildren(body, loadingState("Asking the phone what it knows…"));
    try {
      render(await getKnowledge(ctx.client, deviceId, controller.signal));
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      setChildren(body, knowledgeError(error, () => void load()));
    }
  };

  function render(summary: KnowledgeSummary): void {
    const stats = el("div", "stats stats-6");
    stats.append(
      statTile("Places mapped", String(summary.atlas.places)),
      statTile("Rooms", String(summary.atlas.rooms)),
      statTile("Doors", String(summary.atlas.doors)),
      statTile("Secrets set", `${summary.vault.setCount} of ${summary.vault.slotCount}`, summary.vault.setCount ? "success" : "neutral"),
      statTile("Skills", String(summary.skills.length)),
      statTile("Automations", String(summary.automations.length)),
    );
    setChildren(body, stats, vaultCard(summary.vault.slots), guardedCard(summary.guarded), listCard("Skills", "Routes Cyclone learned from you and replays without asking the model.", summary.skills.map((s) => ({
      title: s.name,
      meta: `${s.steps} ${s.steps === 1 ? "step" : "steps"} · version ${s.version}`,
      enabled: s.enabled,
    }))), listCard("Automations", "Routines that start on a trigger. Pay, send and delete steps still ask on the phone.", summary.automations.map((a) => ({
      title: a.name,
      meta: `${a.trigger.replace(/_/g, " ") || "manual"} · ${a.steps} ${a.steps === 1 ? "step" : "steps"}`,
      enabled: a.enabled,
    }))));
  }

  function vaultCard(slots: VaultSlot[]): HTMLElement {
    const node = card("vault-card");
    const title = el("h2", "card-title");
    title.append(icon("lock"), el("span", undefined, "Vault"));
    node.append(title, el("p", "muted", "Passwords and codes live only on the phone, sealed by its keystore. Set or change them on the phone in Settings → Vault."));
    if (!slots.length) {
      node.append(el("p", "muted", "No secret slots yet. Cyclone asks for one on the phone the first time a login needs it."));
      return node;
    }
    const byPlace = new Map<string, VaultSlot[]>();
    for (const slot of slots) byPlace.set(slot.placeId, [...(byPlace.get(slot.placeId) ?? []), slot]);
    const list = el("ul", "vault-list");
    for (const [placeId, placeSlots] of byPlace) {
      const item = el("li", "vault-place");
      item.dataset.placeId = placeId;
      item.append(el("strong", undefined, placeLabel(placeId)));
      const chips = el("span", "vault-slots");
      for (const slot of placeSlots) {
        const c = chip(`${slot.slot} · ${slot.set ? "set" : "not set"}`, slot.set ? "success" : "warning");
        if (slot.updatedAt) c.title = `Changed ${relativeTime(slot.updatedAt)}`;
        chips.append(c);
      }
      item.append(chips);
      list.append(item);
    }
    node.append(list);
    return node;
  }

  function guardedCard(rows: GuardedRow[] | null): HTMLElement {
    const node = card("guarded-card");
    node.id = "never-pressed";
    const title = el("h2", "card-title");
    title.append(icon("shield"), el("span", undefined, "Never pressed"));
    node.append(title, el("p", "muted", "Doors Cyclone marked as pay, send, delete or permission. Mapping never presses them; in an Ask, the phone asks you first."));
    if (rows === null) {
      node.append(el("p", "muted", "Update Cyclone on the phone to see this list."));
      return node;
    }
    if (!rows.length) {
      node.append(el("p", "muted", "Nothing guarded yet. Map an app and Cyclone lists the doors it will not press here."));
      return node;
    }
    const byPlace = new Map<string, GuardedRow[]>();
    for (const row of rows) byPlace.set(row.placeId, [...(byPlace.get(row.placeId) ?? []), row]);
    const list = el("ul", "vault-list");
    for (const [placeId, placeRows] of byPlace) {
      const item = el("li", "vault-place");
      item.dataset.placeId = placeId;
      const name = el("a", "guarded-app", placeRows[0]!.label || placeLabel(placeId));
      (name as HTMLAnchorElement).href = `#/apps/${encodeURIComponent(placeId)}/screens`;
      item.append(name);
      const chips = el("span", "vault-slots");
      for (const row of placeRows) {
        const count = row.doors + row.rooms;
        chips.append(chip(`${dangerLabel(row.danger)} · ${count}`, row.danger === "permission" ? "warning" : "danger"));
      }
      item.append(chips);
      const rooms = [...new Set(placeRows.flatMap((row) => row.roomIds))].slice(0, 10);
      if (rooms.length) {
        const show = actionButton("Show on the map", { icon: "map", variant: "ghost" });
        show.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map", route: rooms }));
        item.append(show);
      }
      list.append(item);
    }
    node.append(list);
    return node;
  }

  refresh.addEventListener("click", () => void load());
  void load();
  return { element, destroy: () => controller.abort() };
}

function listCard(title: string, subtitle: string, rows: Array<{ title: string; meta: string; enabled: boolean }>): HTMLElement {
  const node = card("knowledge-list-card");
  node.append(el("h2", "card-title", title), el("p", "muted", subtitle));
  if (!rows.length) {
    node.append(emptyState({ title: `No ${title.toLowerCase()} yet` }));
    return node;
  }
  const list = el("ul", "knowledge-list");
  for (const row of rows) {
    const item = el("li", "knowledge-row");
    const names = el("span", "device-names");
    names.append(el("span", "device-name", row.title), el("span", "muted", row.meta));
    item.append(names, chip(row.enabled ? "On" : "Off", row.enabled ? "success" : "neutral"));
    list.append(item);
  }
  node.append(list);
  return node;
}

function placeLabel(placeId: string): string {
  if (placeId.startsWith("chrome:")) return placeId.slice("chrome:".length).replace(/^https?:\/\//, "");
  return appName(placeId);
}
