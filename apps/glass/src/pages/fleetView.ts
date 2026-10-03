/**
 * Fleet tab of the Command Center: type one sentence for several phones, watch each phone's part, stop what you
 * started. The gateway splits the sentence (no model on the PC) and each phone's own Mind does its part.
 *
 * Approving stays the owner's own act: a phone that needs you shows what it is asking, with a button to the Approvals
 * card. This view never answers for you.
 */
import type { GatewayClient } from "../services/gateway.js";
import type { Route } from "../core/router.js";
import {
  fleetApi, isOpen, newRequestId, phoneTone, stateLabel, stateTone,
  type FleetOverview, type FleetPhone, type FleetPlan, type Mission, type MissionPhone,
} from "../services/fleet.js";
import { looksSecret } from "../services/command.js";
import { actionButton, card, chip, emptyState, statTile } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { plural, relativeTime } from "../ui/format.js";

export interface FleetViewDeps {
  client: GatewayClient;
  navigate(route: Route): void;
  later?(fn: () => void, ms: number): () => void;
  newRequestId?(): string;
  /** Test seam. Production uses the browser WebSocket with the gateway's existing bearer subprotocol. */
  socket?(url: string, protocols: string[]): FleetSocket;
}

export interface FleetSocket {
  close(): void;
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
}

export const FLEET_POLL_MS = 5_000;
const SOCKET_BACKOFF_MS = 1_000;
const SOCKET_BACKOFF_MAX_MS = 30_000;
const LIVE_EVENTS = new Set(["MISSION_CREATED", "TASK_UPDATED", "NEEDS_YOU", "MISSION_DONE"]);

export interface FleetView {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

const defaultLater = (fn: () => void, ms: number): (() => void) => {
  const id = setTimeout(fn, ms);
  (id as unknown as { unref?: () => void }).unref?.();
  return () => clearTimeout(id);
};

export function createFleetView(deps: FleetViewDeps): FleetView {
  const later = deps.later ?? defaultLater;
  const makeId = deps.newRequestId ?? newRequestId;
  const element = el("section", "fleet");
  const message = el("p", "cc-hint fleet-message");
  const stats = el("div", "cc-stats");
  const commandHost = el("div", "fleet-command");
  const planHost = el("div", "fleet-plan-host");
  const missionsHost = el("div", "fleet-missions");
  const phonesHost = el("div", "fleet-phones");
  element.append(
    stats, commandHost, planHost, message,
    el("h2", "fleet-title", "Missions"), missionsHost,
    el("h2", "fleet-title", "Phones"), phonesHost,
  );

  let overview: FleetOverview | null = null;
  let missions: Mission[] = [];
  let loadError: string | null = null;
  let pending: FleetPlan | null = null;
  let pendingWarnings: string[] = [];
  let requestId: string | null = null;
  let busy = false;
  let destroyed = false;
  let confirmStop = false;
  let renaming: string | null = null;
  let renameError = "";
  let cancel: (() => void) | null = null;
  let socket: FleetSocket | null = null;
  let socketUp = false;
  let backoff = SOCKET_BACKOFF_MS;
  let reconnect: (() => void) | null = null;

  const say = (text: string, tone: "info" | "error" = "info"): void => {
    message.textContent = text;
    message.className = tone === "error" ? "cc-hint fleet-message fleet-error" : "cc-hint fleet-message";
  };
  const messageOf = (error: unknown): string => (error instanceof Error ? error.message : "Something went wrong.");

  // ------------------------------------------------------------------ the command box (built once; polling never wipes typing)

  const input = el("input", "cc-input fleet-input");
  input.placeholder = "check messages on Work Phone, open the camera on Tablet";
  input.setAttribute("aria-label", "What should each phone do?");
  const run = actionButton("Run", { variant: "primary", icon: "send" });
  const row = el("div", "cc-actions");
  row.append(input, run);
  commandHost.append(row, el("p", "cc-hint", "Name each phone in the sentence. If it isn't clear which phone you mean, nothing runs and you're asked."));

  input.addEventListener("input", () => {
    requestId = null; // a different sentence is a different request
    if (pending) {
      pending = null;
      renderPlan();
    }
  });
  input.addEventListener("keydown", (event) => {
    if ((event as KeyboardEvent).key === "Enter") void submit(false);
  });
  run.addEventListener("click", () => void submit(false));

  async function submit(confirm: boolean): Promise<void> {
    if (busy) return;
    const text = String(input.value ?? "").trim();
    if (!text) return say("Type what each phone should do.", "error");
    if (looksSecret(text)) return say("That looks like a secret. Type secrets on the phone, never here.", "error");
    busy = true;
    requestId ??= makeId();
    say("Working out which phone each part is for…");
    try {
      const result = await fleetApi.command(deps.client, { command: text, confirm, requestId });
      if (destroyed) return;
      if (result.dispatched && result.mission) {
        const started = result.mission.phones.filter((p) => p.state !== "FAILED").length;
        const failed = result.mission.phones.length - started;
        input.value = "";
        pending = null;
        requestId = null;
        say(failed ? `Started on ${plural(started, "phone")}; ${failed} could not start (see below).` : `Started on ${plural(started, "phone")}.`);
        renderPlan();
        await refresh();
      } else if (result.clarification) {
        pending = null;
        requestId = null;
        say(result.clarification, "error");
        renderPlan();
      } else if (result.needsConfirmation && result.plan) {
        pending = result.plan;
        pendingWarnings = result.warnings;
        say("");
        renderPlan();
      } else {
        say("The gateway did not start anything.", "error");
      }
    } catch (error) {
      say(messageOf(error), "error");
    } finally {
      busy = false;
    }
  }

  function renderPlan(): void {
    if (!pending) return planHost.replaceChildren();
    const box = card("fleet-plan");
    const count = pending.assignments.length;
    box.append(el("strong", undefined, count === 1 ? "Run this on 1 phone?" : `Run this on ${count} phones?`));
    const rows = el("ul", "fleet-plan-list");
    for (const a of pending.assignments) {
      const item = el("li");
      item.append(el("strong", undefined, a.label), el("span", undefined, ` → ${a.goal}`));
      rows.append(item);
    }
    box.append(rows);
    for (const warning of pendingWarnings) box.append(el("p", "cc-hint fleet-warning", warning));
    if (pending.confidence !== "high") box.append(el("p", "cc-hint", "I wasn't fully sure how to split this. Check each phone's part before running."));
    for (const note of pending.notes) box.append(el("p", "cc-hint", note));
    const actions = el("div", "cc-actions");
    const go = actionButton(count === 1 ? "Run" : `Run on ${count} phones`, { variant: "primary" });
    go.addEventListener("click", () => void submit(true));
    const no = actionButton("Cancel", { variant: "ghost" });
    no.addEventListener("click", () => {
      pending = null;
      requestId = null;
      renderPlan();
    });
    actions.append(go, no);
    box.append(actions);
    planHost.replaceChildren(box);
  }

  // ------------------------------------------------------------------ missions

  const act = async (fn: () => Promise<unknown>): Promise<void> => {
    try {
      await fn();
    } catch (error) {
      say(messageOf(error), "error");
    }
    await refresh();
  };

  function phoneRow(phone: MissionPhone): HTMLElement {
    const box = el("div", "fleet-phone-row");
    const head = el("div", "cc-row");
    head.append(el("strong", undefined, phone.label), chip(stateLabel(phone.state), stateTone(phone.state)));
    if (phone.state === "RUNNING" && phone.turns) head.append(el("span", "muted", plural(phone.turns, "step")));
    box.append(head, el("p", "fleet-goal", phone.goal));
    const detail = phone.summary || phone.cause || phone.hint;
    if (detail) box.append(el("p", "cc-hint", detail));
    if (phone.state === "NEEDS_YOU" && phone.approval) {
      const a = phone.approval;
      const ask = el("div", "cc-approval fleet-ask");
      ask.append(el("p", "cc-approval-text", a.text));
      if (a.send) {
        const dl = el("dl", "cc-send");
        dl.append(el("dt", undefined, "Message"), el("dd", "cc-send-text", a.send.text), el("dt", undefined, "To"), el("dd", undefined, a.send.recipient), el("dt", undefined, "In"), el("dd", undefined, a.send.app));
        ask.append(dl);
      }
      if (a.answerHere) {
        const open = actionButton("Review and answer", { variant: "primary" });
        open.addEventListener("click", () => deps.navigate({ name: "command", tab: "approvals" }));
        ask.append(open);
      } else {
        ask.append(el("p", "cc-hint", "Answer this one on the phone."));
      }
      box.append(ask);
    }
    return box;
  }

  function missionCard(mission: Mission): HTMLElement {
    const box = card("fleet-mission");
    const head = el("div", "cc-row");
    head.append(chip(stateLabel(mission.status), stateTone(mission.status)), el("strong", undefined, mission.command || "Mission"), el("span", "muted", relativeTime(mission.createdAt)));
    if (isOpen(mission.status)) {
      const stop = actionButton("Stop mission", { variant: "ghost" });
      stop.addEventListener("click", () => void act(() => fleetApi.cancel(deps.client, mission.missionId)));
      head.append(stop);
    }
    const failed = mission.phones.filter((phone) => phone.state === "FAILED" || phone.state === "CANCELLED");
    if (failed.length) {
      const retry = actionButton("Retry failed phones", { variant: "ghost" });
      retry.addEventListener("click", () => void act(async () => {
        const n = await fleetApi.retry(deps.client, mission.missionId);
        say(n ? `Retrying ${plural(n, "phone")}. The ones that finished are left alone.` : "Nothing failed to retry.");
      }));
      head.append(retry);
    }
    box.append(head);
    for (const note of mission.notes) box.append(el("p", "cc-hint", note));
    for (const phone of mission.phones) box.append(phoneRow(phone));
    return box;
  }

  function renderMissions(): void {
    if (loadError) return setChildren(missionsHost, el("p", "cc-hint fleet-error", loadError));
    if (!missions.length) {
      return setChildren(missionsHost, emptyState({ icon: "phone", title: "No missions yet", body: "Type a sentence above. Each phone you name gets its own part." }));
    }
    setChildren(missionsHost, ...missions.map(missionCard));
  }

  // ------------------------------------------------------------------ phones

  function phoneCard(phone: FleetPhone): HTMLElement {
    const box = card("fleet-phone");
    const head = el("div", "cc-row");
    head.append(el("strong", undefined, phone.label), chip(phone.paired ? phone.stateLabel : "Not paired", phoneTone(phone)));
    if (phone.source !== "USB") head.append(chip(phone.source.toLowerCase(), "neutral"));
    head.append(el("span", "muted", `seen ${relativeTime(phone.lastSeenMs)}`));
    box.append(head);
    if (phone.health && phone.health.batteryPercent != null) {
      box.append(el("p", "cc-hint", `Battery ${phone.health.batteryPercent}%${phone.health.charging ? ", charging" : ""} · ${phone.health.network || "network unknown"} · ${phone.health.freeStorageMb ?? "?"} MB free`));
    }
    if (!phone.addressable) box.append(el("p", "cc-hint", "Pair this phone before a sentence can address it."));
    for (const task of phone.tasks) {
      const line = el("div", "cc-row fleet-task");
      line.append(chip(task.needsYou ? "Needs you" : stateLabel(task.status), task.needsYou ? "warning" : stateTone(task.status)), el("span", undefined, task.title));
      box.append(line);
      if (task.needsYou) {
        if (task.approvalText) box.append(el("p", "cc-approval-text", task.approvalText));
        const open = actionButton("Review and answer", { variant: "primary" });
        open.addEventListener("click", () => deps.navigate({ name: "command", tab: "approvals" }));
        box.append(open);
      }
    }
    if (renaming === phone.deviceId) {
      const field = el("input", "cc-input");
      field.value = phone.nickname ?? "";
      field.placeholder = "Work Phone";
      field.setAttribute("aria-label", "Phone name");
      const save = actionButton("Save", { variant: "primary" });
      save.addEventListener("click", () => void saveName(phone.deviceId, String(field.value ?? "")));
      const no = actionButton("Cancel", { variant: "ghost" });
      no.addEventListener("click", () => {
        renaming = null;
        renameError = "";
        renderPhones();
      });
      const edit = el("div", "cc-actions");
      edit.append(field, save, no);
      box.append(edit);
      if (renameError) box.append(el("p", "cc-hint fleet-error", renameError));
    } else {
      const rename = actionButton(phone.nickname ? "Rename" : "Name this phone", { variant: "ghost" });
      rename.addEventListener("click", () => {
        renaming = phone.deviceId;
        renameError = "";
        renderPhones();
      });
      box.append(rename);
    }
    return box;
  }

  async function saveName(deviceId: string, name: string): Promise<void> {
    try {
      await fleetApi.nickname(deps.client, deviceId, name);
      renaming = null;
      renameError = "";
    } catch (error) {
      renameError = messageOf(error); // e.g. the name is already used by another phone
      renderPhones(); // polling skips this redraw while renaming, so show the reason now
      return;
    }
    await refresh();
  }

  let phoneWindow = 40;
  let phoneQuery = "";
  let needsOnly = false;

  function renderPhones(): void {
    if (!overview || !overview.phones.length) {
      return setChildren(phonesHost, emptyState({ icon: "phone", title: "No phones connected", body: "Connect and pair a phone in Devices. It appears here with its name and what it is doing." }));
    }
    const query = phoneQuery.trim().toLowerCase();
    const matched = overview.phones.filter((phone) => {
      if (needsOnly && !phone.tasks.some((task) => task.needsYou)) return false;
      return !query || phone.label.toLowerCase().includes(query) || phone.state.toLowerCase().includes(query);
    });
    const shown = matched.slice(0, phoneWindow);
    const more = matched.length - shown.length;
    const search = el("input", "cc-input");
    search.placeholder = "Search phones";
    search.setAttribute("aria-label", "Search phones");
    search.value = phoneQuery;
    search.addEventListener("input", () => {
      phoneQuery = String(search.value ?? "");
      phoneWindow = 40;
      renderPhones();
    });
    const filter = actionButton(needsOnly ? "Showing needs you" : "Needs you only", { variant: "ghost" });
    filter.addEventListener("click", () => {
      needsOnly = !needsOnly;
      renderPhones();
    });
    const nodes: HTMLElement[] = [search, filter, ...shown.map(phoneCard)];
    if (more > 0) {
      const rest = actionButton(`Show ${more} more phones`, { variant: "ghost" });
      rest.addEventListener("click", () => {
        phoneWindow += 40;
        renderPhones();
      });
      nodes.push(rest);
    }
    setChildren(phonesHost, ...nodes);
  }

  function renderStats(): void {
    if (!overview) return stats.replaceChildren();
    const c = overview.counts;
    const stopFleet = actionButton("Stop all fleet missions", { variant: "ghost" });
    stopFleet.title = "Stops open tasks that belong to a fleet mission. Other Command Center tasks keep running.";
    stopFleet.addEventListener("click", () => {
      void act(async () => say(`Stopped ${await fleetApi.stopFleetMissions(deps.client)}.`));
    });
    const stopAll = actionButton(confirmStop ? "Yes, cancel every open task" : "Stop everything", { variant: confirmStop ? "danger" : "ghost" });
    stopAll.title = "Emergency stop: cancels every open Command Center task, not only fleet missions.";
    stopAll.addEventListener("click", () => {
      if (!confirmStop) {
        confirmStop = true;
        return renderStats();
      }
      confirmStop = false;
      void act(async () => say(`Stopped ${await fleetApi.stopAll(deps.client)}.`));
    });
    setChildren(
      stats,
      statTile("Phones", String(c.phones)),
      statTile("Ready", String(c.ready), c.ready ? "success" : "neutral"),
      statTile("Working", String(c.busy), c.busy ? "accent" : "neutral"),
      statTile("Need you", String(c.needYou), c.needYou ? "warning" : "neutral"),
      stopFleet,
      stopAll,
    );
  }

  // ------------------------------------------------------------------ loading

  async function refresh(): Promise<void> {
    cancel?.();
    try {
      [overview, missions] = await Promise.all([fleetApi.overview(deps.client), fleetApi.missions(deps.client)]);
      loadError = null;
    } catch (error) {
      loadError = messageOf(error);
    }
    if (destroyed) return;
    renderStats();
    renderMissions();
    if (renaming === null) renderPhones(); // never redraw a name the owner is typing
    if (!destroyed) cancel = later(() => void refresh(), FLEET_POLL_MS);
  }

  function applyLive(event: { event?: string; missionId?: string; taskId?: string; deviceId?: string; status?: string }): void {
    const kind = String(event.event || "");
    if (!LIVE_EVENTS.has(kind)) return;
    if (kind === "TASK_UPDATED" || kind === "NEEDS_YOU") {
      const next = kind === "NEEDS_YOU" || event.status === "needs_you" ? "NEEDS_YOU"
        : event.status === "succeeded" ? "COMPLETED"
        : event.status === "failed" ? "FAILED"
        : event.status === "cancelled" ? "CANCELLED"
        : event.status === "running" ? "RUNNING" : "";
      for (const mission of missions) {
        if (event.missionId && mission.missionId !== event.missionId) continue;
        for (const phone of mission.phones) {
          if ((event.taskId && phone.taskId === event.taskId) || (event.deviceId && phone.deviceId === event.deviceId)) {
            if (next) phone.state = next as MissionPhone["state"];
          }
        }
      }
      renderMissions();
      renderStats();
    }
    void refresh();
  }

  function openSocket(): void {
    if (destroyed) return;
    if (!deps.socket && typeof WebSocket === "undefined") return;
    const open = deps.socket ?? ((url: string, protocols: string[]) => new WebSocket(url, protocols) as unknown as FleetSocket);
    let next: FleetSocket;
    try {
      const origin = globalThis.location?.origin || "http://127.0.0.1";
      const target = deps.client.socket("/v1/fleet/events", origin);
      next = open(target.url, target.protocols);
    } catch {
      socketUp = false;
      scheduleReconnect();
      return;
    }
    socket = next;
    next.onopen = () => {
      socketUp = true;
      backoff = SOCKET_BACKOFF_MS;
      cancel?.();
      void refresh();
    };
    next.onmessage = (message) => {
      try {
        applyLive(JSON.parse(String(message.data)));
      } catch {
        /* a malformed frame is ignored; the next resync heals the tab */
      }
    };
    next.onerror = () => { socketUp = false; };
    next.onclose = () => {
      socketUp = false;
      socket = null;
      if (!destroyed) {
        cancel?.();
        cancel = later(() => void refresh(), FLEET_POLL_MS);
        scheduleReconnect();
      }
    };
  }

  function scheduleReconnect(): void {
    reconnect?.();
    const wait = backoff;
    backoff = Math.min(backoff * 2, SOCKET_BACKOFF_MAX_MS);
    reconnect = later(() => openSocket(), wait);
  }

  void refresh();
  openSocket();
  return {
    element,
    refresh,
    destroy: () => {
      destroyed = true;
      cancel?.();
      reconnect?.();
      socketUp = false;
      socket?.close();
    },
  };
}
