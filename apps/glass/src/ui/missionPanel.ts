/**
 * Mapping mission control (plan 22 §4.3): the start sheet (whose account, how long), the live board header while the
 * phone maps, and the report when it ends. Glass only asks and shows: the phone runs the pass, enforces the budget
 * and refuses every door that could pay, send, delete, change a setting or touch security.
 */
import { MISSION_BUDGETS, type MappingIdentity, type MappingJobView, type MappingMission, type MissionBudget } from "../services/atlasClient.js";
import type { PhoneModels } from "../services/dictionary.js";
import { el } from "./dom.js";
import { actionButton, chip, type Tone } from "./components.js";
import { icon } from "./icons.js";

export const IDENTITY_COPY: Record<MappingIdentity, { title: string; body: string; short: string }> = {
  own: {
    title: "My account — look only",
    short: "My account · look only",
    body: "Walks what you can already see with the account signed in on the phone. Never signs in or out and never opens account or security pages.",
  },
  test: {
    title: "Test account",
    short: "Test account",
    body: "Also maps sign-in, sign-up and onboarding screens. Sign the test account in on the phone; passwords only ever go through the Secrets Card.",
  },
};

export const MAPPING_PROMISE =
  "Never sends, posts, pays, deletes, or changes settings or security. Tabs and menus first, then deeper. You can take the phone back at any moment.";

export interface StartSheetOptions {
  appLabel: string;
  initial?: Partial<MappingMission>;
  /** The phone's models for the picker (plan 36 §9); the phone's current model is the default. */
  models?: Promise<PhoneModels | null> | PhoneModels | null;
  onStart(mission: MappingMission): void;
  onCancel(): void;
}

/** A modal sheet over the page. Returns the overlay element; the caller appends and removes it. */
export function startSheet(options: StartSheetOptions): HTMLElement {
  let identity: MappingIdentity = options.initial?.identity ?? "own";
  let budget: MissionBudget = options.initial?.budget ?? "10m";
  let model: string = options.initial?.model ?? "phone";
  const overlay = el("div", "sheet-overlay");
  const sheet = el("div", "sheet mission-sheet");
  sheet.setAttribute("role", "dialog");
  sheet.setAttribute("aria-modal", "true");
  sheet.setAttribute("aria-label", `Map ${options.appLabel}`);
  overlay.append(sheet);

  const title = el("h2", "sheet-title", `Map ${options.appLabel}`);
  const lead = el("p", "muted", "Cyclone walks the app on the phone and draws what it finds on this board as it goes.");

  const identities = el("div", "choice-grid");
  const identityCards = (Object.keys(IDENTITY_COPY) as MappingIdentity[]).map((id) => {
    const cardNode = el("button", "choice-card") as HTMLButtonElement;
    cardNode.type = "button";
    cardNode.dataset.identity = id;
    cardNode.append(el("span", "choice-title", IDENTITY_COPY[id].title), el("span", "choice-body", IDENTITY_COPY[id].body));
    cardNode.addEventListener("click", () => {
      identity = id;
      paint();
    });
    identities.append(cardNode);
    return cardNode;
  });

  const budgets = el("div", "budget-chips");
  const budgetButtons = (Object.keys(MISSION_BUDGETS) as MissionBudget[]).map((id) => {
    const node = el("button", "budget-chip") as HTMLButtonElement;
    node.type = "button";
    node.dataset.budget = id;
    node.append(el("span", "budget-label", MISSION_BUDGETS[id].label), el("span", "budget-sub", `up to ${MISSION_BUDGETS[id].maxNewScreens} new places`));
    node.addEventListener("click", () => {
      budget = id;
      paint();
    });
    budgets.append(node);
    return node;
  });

  const promise = el("div", "mission-promise");
  promise.append(icon("shield"), el("span", undefined, MAPPING_PROMISE));
  const testNote = el("p", "mission-note");

  const cancel = actionButton("Cancel", { variant: "ghost" });
  cancel.addEventListener("click", () => options.onCancel());
  const start = actionButton("Start mapping", { icon: "play", variant: "primary" });
  start.classList.add("mission-start");
  start.addEventListener("click", () => options.onStart({ identity, budget, model }));

  const modelSelect = el("select", "model-select") as HTMLSelectElement;
  modelSelect.setAttribute("aria-label", "Model");
  const fillModels = (models: PhoneModels | null): void => {
    const options = [{ value: "phone", text: models?.active ? `Phone's model · ${models.active.label}` : "Phone's model" }];
    for (const m of models?.models ?? []) if (m.id !== models?.active?.id) options.push({ value: m.id, text: m.label });
    if (model !== "phone" && !options.some((o) => o.value === model)) options.push({ value: model, text: model });
    modelSelect.replaceChildren(...options.map((o) => {
      const node = el("option", undefined, o.text) as HTMLOptionElement;
      node.value = o.value;
      return node;
    }));
    modelSelect.value = model;
  };
  fillModels(null);
  if (options.models) void Promise.resolve(options.models).then(fillModels).catch(() => undefined);
  modelSelect.addEventListener("change", () => { model = modelSelect.value || "phone"; });
  const modelNote = el("p", "muted mission-note", "It decides what new groups in the app are, like Followers or Close friends. Your key stays on the phone.");
  const actions = el("div", "sheet-actions");
  actions.append(cancel, start);

  const paint = (): void => {
    for (const node of identityCards) node.classList.toggle("selected", node.dataset.identity === identity);
    for (const node of budgetButtons) node.classList.toggle("selected", node.dataset.budget === budget);
    testNote.textContent = identity === "test"
      ? "Before you start: sign the test account in on the phone, or let Cyclone stop at the sign-in screen and ask you for the password through the Secrets Card."
      : "If the app shows a sign-in screen, the pass ends there and says so; it never signs you in or out.";
  };
  paint();

  sheet.append(
    title, lead,
    el("h3", "sheet-section", "Whose account"), identities,
    el("h3", "sheet-section", "How long"), budgets,
    el("h3", "sheet-section", "Model"), modelSelect, modelNote,
    promise, testNote, actions,
  );
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay) options.onCancel();
  });
  return overlay;
}

export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

export interface MissionEvent {
  at: number;
  text: string;
  kind: "place" | "door" | "state";
}

export interface LiveInfo {
  appLabel: string;
  now: number;
  here: string | null;
  hereZone: string | null;
  events: MissionEvent[];
}

function stateCopy(job: MappingJobView): { label: string; tone: Tone; note: string | null } {
  switch (job.state) {
    case "running":
      return { label: "Mapping", tone: "accent", note: null };
    case "paused":
      return { label: "Paused", tone: "warning", note: job.boundary === "danger" ? "Paused at a door it may not open." : "Paused. Resume when you are ready." };
    case "needs-secret":
      return { label: "Waiting for you", tone: "warning", note: "Sign the test account in on the phone: the Secrets Card is open there. Cyclone continues by itself." };
    case "human-control":
      return { label: "You have the phone", tone: "warning", note: "Cyclone paused because you took over. Give the phone back to continue." };
    default:
      return { label: job.state, tone: "neutral", note: null };
  }
}

/** The live header while the phone maps: time against the budget, counters, where it is, what it just found. */
export function missionLive(job: MappingJobView, info: LiveInfo): HTMLElement {
  const box = el("section", "mission-panel live");
  const head = el("div", "mission-head");
  const titles = el("div", "mission-titles");
  titles.append(el("div", "inspector-kicker", "Mapping mission"), el("h2", "mission-title", `Mapping ${info.appLabel}`));
  const chips = el("div", "mission-chips");
  const state = stateCopy(job);
  chips.append(chip(state.label, state.tone));
  if (job.identity) chips.append(chip(IDENTITY_COPY[job.identity].short, job.identity === "test" ? "accent" : "neutral"));
  head.append(titles, chips);
  box.append(head);

  const elapsed = job.startedAtEpochMs ? info.now - job.startedAtEpochMs : 0;
  const budget = job.maxElapsedMs ?? 0;
  const time = el("div", "mission-time");
  time.append(el("span", "mission-clock", formatClock(elapsed)), el("span", "muted", budget ? `of ${formatClock(budget)}` : ""));
  const bar = el("span", "conf-bar mission-bar");
  const fill = el("span", "conf-fill high");
  fill.style.width = `${budget ? Math.min(100, Math.round((elapsed / budget) * 100)) : 0}%`;
  bar.append(fill);
  time.append(bar);
  box.append(time);

  const stats = el("div", "mission-stats");
  const stat = (value: number, label: string): HTMLElement => {
    const node = el("div", "mission-stat");
    node.append(el("span", "mission-stat-value", String(value)), el("span", "mission-stat-label", label));
    return node;
  };
  stats.append(stat(job.newScreens, "new places"), stat(job.verifiedMutations, "verified moves"), stat(job.attemptedDoors, "doors tried"));
  box.append(stats);

  if (info.here) {
    const here = el("div", "mission-here");
    here.append(el("span", "muted", "Now at "), el("strong", undefined, info.here), el("span", "muted", info.hereZone ? ` · ${info.hereZone}` : ""));
    box.append(here);
  }
  if (state.note) box.append(el("p", "mission-note warn", state.note));
  if (info.events.length) {
    const list = el("ol", "mission-timeline");
    for (const event of info.events.slice(0, 6)) {
      const item = el("li", `mission-event ${event.kind}`);
      item.append(el("span", "mission-event-time", formatClock(event.at - (job.startedAtEpochMs ?? event.at))), el("span", undefined, event.text));
      list.append(item);
    }
    box.append(list);
  }
  return box;
}

export interface ReportInfo {
  appLabel: string;
  before: { places: number; doors: number };
  after: { places: number; doors: number; zones: number; blocked: number; unconfirmed: number; scenariosKnown: number };
  onViewMap(): void;
  onMapAgain(): void;
  onMapDeeper(): void;
}

/** Plain words for the phone's "could not read the screen" codes (OBSERVATION_MISSING_<cause>). */
export function failureHint(code: string | null): string {
  if (!code || !/^(AFTER_)?OBSERVATION_MISSING/.test(code)) return "";
  if (code.endsWith("SCREEN_KEPT_CHANGING")) return " The app's screen kept moving while Cyclone read it, even after waiting. Let the app finish loading, close any playing video or animation, then Map again.";
  if (code.endsWith("ACCESSIBILITY_OFF")) return " Cyclone's accessibility service is off on the phone. Turn it back on in Settings, then Map again.";
  if (code.endsWith("DISPLAY_GONE")) return " The screen Cyclone was mapping on closed. Map again.";
  if (code.endsWith("TIMEOUT")) return " The phone took too long to answer. Check it is awake and connected, then Map again.";
  return " Cyclone could not read the phone's screen.";
}

export function reportReason(job: MappingJobView): { text: string; tone: Tone } {
  if (job.state === "stopped") return { text: "You stopped the pass.", tone: "neutral" };
  if (job.state === "failed") return { text: `The pass stopped with an error (${job.failureCode ?? "unknown"}).${failureHint(job.failureCode)} Nothing was changed on the phone.`, tone: "danger" };
  if (job.boundary === "authentication") return { text: "Ended at a sign-in screen: a look-only pass never signs in. Map with a test account to cover sign-in screens.", tone: "warning" };
  if (job.atlasStatus === "mapped") return { text: "Finished: every reachable safe door was walked.", tone: "success" };
  return { text: "Finished for now: the time or the budget was used, or nothing new was left within reach. Map again or go deeper.", tone: "accent" };
}

/** The end-of-mission report: what changed on the map and why the pass ended. */
export function missionReport(job: MappingJobView, info: ReportInfo): HTMLElement {
  const box = el("section", "mission-panel report");
  const head = el("div", "mission-head");
  const titles = el("div", "mission-titles");
  titles.append(el("div", "inspector-kicker", "Mapping report"), el("h2", "mission-title", info.appLabel));
  const reason = reportReason(job);
  const chips = el("div", "mission-chips");
  chips.append(chip(job.state === "completed" ? "Done" : job.state === "stopped" ? "Stopped" : "Stopped with an error", reason.tone));
  if (job.identity) chips.append(chip(IDENTITY_COPY[job.identity].short, "neutral"));
  head.append(titles, chips);
  box.append(head, el("p", `mission-reason tone-${reason.tone}`, reason.text));

  const grid = el("div", "mission-stats report-stats");
  const delta = (label: string, before: number, after: number): HTMLElement => {
    const node = el("div", "mission-stat");
    const gained = after - before;
    node.append(el("span", "mission-stat-value", String(after)), el("span", "mission-stat-label", label),
      el("span", `mission-delta${gained > 0 ? " up" : ""}`, gained > 0 ? `+${gained}` : "no change"));
    return node;
  };
  const plain = (label: string, value: string, extra?: string): HTMLElement => {
    const node = el("div", "mission-stat");
    node.append(el("span", "mission-stat-value", value), el("span", "mission-stat-label", label));
    if (extra) node.append(el("span", "mission-delta", extra));
    return node;
  };
  grid.append(
    delta("places", info.before.places, info.after.places),
    delta("doors", info.before.doors, info.after.doors),
    plain("zones", String(info.after.zones)),
    plain("scenarios", `${info.after.scenariosKnown} / 3`),
    plain("blocked doors", String(info.after.blocked), "refused, never opened"),
    plain("unconfirmed", String(info.after.unconfirmed)),
  );
  box.append(grid);

  const actions = el("div", "mission-actions");
  const view = actionButton("View map", { icon: "map" });
  view.addEventListener("click", info.onViewMap);
  const again = actionButton("Map again", { icon: "refresh" });
  again.addEventListener("click", info.onMapAgain);
  const deeper = actionButton("Map deeper · 30 min", { icon: "play", variant: "primary" });
  deeper.addEventListener("click", info.onMapDeeper);
  actions.append(view, again, deeper);
  box.append(actions);
  return box;
}
