/**
 * Phone care under a phone on the Devices page (alpha 87): one line that says whether Cyclone on the phone is
 * current and healthy, at most one button (Update phone / Try again), and — behind "Details" — the evidence a
 * developer needs: versions, why Android ended Cyclone, the freezes and the code they happened in, the update's
 * error code and the diagnostics folder. Nothing is decided here; the gateway's answer is shown as it is.
 */
import { copyText } from "../ui/clipboard.js";
import { actionButton, keyValue } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import type { GatewayClient } from "../services/gateway.js";
import { actionLabel, exitKindLabel, phoneCareApi, type CareDecisions, type PhoneCare } from "../services/phoneCare.js";

export interface PhoneCareDeps {
  now(): number;
  /** Schedules the next look; returns a cancel function. */
  later(fn: () => void, ms: number): () => void;
}

const defaultDeps: PhoneCareDeps = {
  now: () => Date.now(),
  later: (fn, ms) => {
    const id = setTimeout(fn, ms);
    // Under Node (tests, tooling) a pending look must not keep the process alive; browsers have no unref.
    (id as unknown as { unref?: () => void }).unref?.();
    return () => clearTimeout(id);
  },
};

/** While an update runs, look often; otherwise now and then (new stops and freezes arrive on their own). */
export const WORKING_POLL_MS = 2_000;
export const IDLE_POLL_MS = 60_000;

export interface PhoneCareView {
  element: HTMLElement;
  refresh(): Promise<void>;
  /** Starts the phone update (the connection line's "Install Cyclone" uses it too). */
  update(): Promise<void>;
  destroy(): void;
}

export function createPhoneCareView(client: GatewayClient, deviceId: string, deps: PhoneCareDeps = defaultDeps): PhoneCareView {
  const element = el("div", "care");
  element.hidden = true;
  let care: PhoneCare | null = null;
  let open = false;
  let busy = false;
  let destroyed = false;
  let cancel: (() => void) | null = null;

  const schedule = (): void => {
    cancel?.();
    if (destroyed) return;
    cancel = deps.later(() => void refresh(), care?.status === "working" ? WORKING_POLL_MS : IDLE_POLL_MS);
  };

  async function refresh(): Promise<void> {
    try {
      care = await phoneCareApi.get(client, deviceId);
    } catch {
      // An older runtime without phone care, or the phone just left: show nothing rather than a second error.
      care = null;
    }
    if (destroyed) return;
    render();
    schedule();
  }

  async function update(): Promise<void> {
    busy = true;
    render();
    try {
      care = await phoneCareApi.update(client, deviceId);
    } catch (error) {
      care = care && { ...care, status: "problem", headline: "The update didn't start", detail: error instanceof Error ? error.message : String(error), action: { kind: "update", label: "Try again" } };
    }
    busy = false;
    if (destroyed) return;
    render();
    schedule();
  }

  function render(): void {
    if (!care || care.status === "offline" || !care.headline) {
      element.hidden = true;
      element.replaceChildren();
      return;
    }
    element.hidden = false;
    element.className = `care care-${care.status}`;
    element.setAttribute("role", "status");

    const text = el("div", "care-text");
    text.append(el("span", "care-headline", care.headline));
    const when = care.atMs ? `${relativeTime(care.atMs, deps.now())} · ` : "";
    if (care.detail) text.append(el("span", "care-detail", `${when}${care.detail}`));
    if (care.hint) text.append(el("span", "care-hint", care.hint));

    const row = el("div", "care-row");
    const dot = el("span", care.status === "working" ? "care-dot spinner" : "care-dot");
    dot.setAttribute("aria-hidden", "true");
    row.append(dot, text);
    if (care.action && care.status !== "working") {
      const go = actionButton(busy ? "Starting…" : care.action.label, { variant: "primary" });
      go.disabled = busy;
      go.addEventListener("click", () => void update());
      row.append(go);
    }
    const toggle = actionButton(open ? "Hide details" : "Details", { variant: "ghost" });
    toggle.classList.add("care-toggle");
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => {
      open = !open;
      render();
    });
    row.append(toggle);
    setChildren(element, row, ...(open ? [details(care)] : []));
  }

  function details(value: PhoneCare): HTMLElement {
    const box = el("div", "care-details");
    const d = value.details;
    const rows: Array<[string, string | Node]> = [
      ["Phone app", d.phoneVersion ? `${d.phoneVersion}${d.phoneCode ? ` (${d.phoneCode})` : ""}` : "Not installed or unknown"],
      ["This PC", d.pcVersion ?? "Unknown"],
      ["Health checked", d.healthCollectedAtMs ? relativeTime(d.healthCollectedAtMs, deps.now()) : "Not yet"],
      ["Freezes today", d.freezesToday ? `${d.freezesToday} · longest ${(d.longestFreezeMs / 1000).toFixed(1)} s` : "None"],
    ];
    if (d.update) {
      rows.push(["Last update", [d.update.from, d.update.target].filter(Boolean).join(" → ") + ` · ${d.update.state}${d.update.errorCode ? ` · ${d.update.errorCode}` : ""}`]);
    }
    if (d.diagnosticsPath) {
      const path = el("span", "care-path");
      const copy = actionButton("Copy", { variant: "ghost" });
      copy.addEventListener("click", () => void copyText(d.diagnosticsPath ?? ""));
      path.append(el("code", undefined, d.diagnosticsPath), copy);
      rows.push(["Diagnostics", path]);
    }
    box.append(keyValue(rows));
    if (d.decisions && d.decisions.lessons > 0) {
      box.append(el("h4", "care-subtitle", "Instant decisions"));
      box.append(keyValue(decisionRows(d.decisions)));
    }
    if (d.update?.errorDetail) box.append(el("pre", "care-frames", d.update.errorDetail));

    if (d.exits.length) {
      box.append(el("h4", "care-subtitle", "Recent stops"));
      const stops = el("ul", "care-list");
      for (const exit of d.exits.slice(0, 5)) {
        const li = el("li", exit.unexpected ? "care-item care-item-unexpected" : "care-item");
        li.append(el("span", "care-item-title", `${exitKindLabel(exit.kind)} · ${relativeTime(exit.atMs, deps.now())}`));
        if (exit.description) li.append(el("span", "muted", exit.description));
        if (exit.mainThread.length) li.append(el("pre", "care-frames", exit.mainThread.join("\n")));
        stops.append(li);
      }
      box.append(stops);
    }
    if (d.stalls.length) {
      box.append(el("h4", "care-subtitle", "Freezes"));
      const freezes = el("ul", "care-list");
      for (const stall of d.stalls.slice(0, 5)) {
        const li = el("li", "care-item");
        li.append(el("span", "care-item-title", `${(stall.durationMs / 1000).toFixed(1)} s · ${relativeTime(stall.startedAtMs, deps.now())}`));
        if (stall.suspect) li.append(el("code", "care-suspect", stall.suspect));
        if (stall.frames.length) li.append(el("pre", "care-frames", stall.frames.join("\n")));
        freezes.append(li);
      }
      box.append(freezes);
    }
    return box;
  }

  void refresh();
  return {
    element,
    refresh,
    update,
    destroy() {
      destroyed = true;
      cancel?.();
    },
  };
}

const pct = (value: number | null): string => (value == null ? "–" : `${Math.round(value * 100)}%`);
const secs = (ms: number | null): string => (ms == null ? "–" : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`);

/** How this phone decides requests, in a few rows for developers (alpha 89). */
function decisionRows(d: CareDecisions): Array<[string, string]> {
  const rows: Array<[string, string]> = [
    ["Decided on the phone", `${pct(d.onPhoneShare)} of ${d.lessons} requests`],
    [d.provider ? `${d.provider} answers` : "Decision answers", `${secs(d.decisionsP50)} median · ${secs(d.decisionsP95)} slowest 5%`],
    ["Instant worked", `${pct(d.instantVerified)} of ${d.instantChecked}`],
    ["Phone model agrees", d.shadowChecked ? `${pct(d.shadowAgreement)} of ${d.shadowChecked} checked with ${d.provider ?? "the decider"}` : "Still learning"],
    ["Earned actions", d.earned.length ? d.earned.map(actionLabel).join(", ") : "None yet"],
  ];
  return rows;
}
