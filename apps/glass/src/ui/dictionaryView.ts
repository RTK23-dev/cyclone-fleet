/**
 * The Dictionary tab (plan 36 §7): the app's own groups of things, under the fixed core kinds, with where each lives,
 * why a waiting one waits, the organizer's audit and health, and what an agent reads. The owner renames, merges,
 * undoes, locks, rejects, confirms and moves sets here; the phone applies the organizer's rules to every change.
 */
import { statusLabel, waitingLine, whereLine, type AppDictionaryView, type DictAction, type DictSet } from "../services/dictionary.js";
import { el } from "./dom.js";
import { actionButton, chip, emptyState, statTile, type Tone } from "./components.js";

export interface DictionaryHandlers {
  edit(action: DictAction, id: string, extra?: { into?: string; label?: string; parentId?: string | null; kind?: string }): void;
}

export interface DictionaryViewState {
  showHidden: boolean;
  renaming: string | null;
}

const TONE: Record<DictSet["status"], Tone> = {
  candidate: "warning",
  confirmed: "success",
  locked: "accent",
  rejected: "neutral",
  merged: "neutral",
  retired: "neutral",
};

const HIDDEN = new Set<DictSet["status"]>(["rejected", "merged", "retired"]);

export function dictionaryView(view: AppDictionaryView, state: DictionaryViewState, handlers: DictionaryHandlers, repaint: () => void): HTMLElement {
  const root = el("div", "dictionary");
  const byId = new Map(view.sets.map((s) => [s.id, s]));
  const active = view.sets.filter((s) => s.status === "confirmed" || s.status === "locked");
  const waiting = view.sets.filter((s) => s.status === "candidate");
  if (!view.sets.length && !view.screens.length && !view.review.length) {
    root.append(emptyState({
      icon: "map",
      title: "No groups yet",
      body: "Map this app and Cyclone learns its groups of things, like Followers, Close friends or Message requests, in the app's own words. It never keeps who is in them.",
    }));
    return root;
  }

  if (view.review.length) root.append(reviewCard(view, handlers));

  const stats = el("div", "stats stats-4");
  stats.append(
    statTile("Groups", String(active.length), "success"),
    statTile("Waiting", String(waiting.length), waiting.length ? "warning" : "neutral"),
    statTile("Passes", String(view.passes)),
    statTile("JEV (watching)", view.jev.answered ? `${view.jev.agreed}/${view.jev.answered}` : "—", "accent"),
  );
  root.append(stats, el("p", "muted table-note",
    "Groups are named in the app's own words and found by where the app shows them. A group joins only when the app names it, it has its own place on screen and it was seen twice; anything unclear waits. Cyclone never keeps who is in a group."));

  const tools = el("div", "dictionary-tools");
  const toggle = actionButton(state.showHidden ? "Hide merged and rejected" : "Show merged and rejected", { variant: "ghost" });
  toggle.addEventListener("click", () => { state.showHidden = !state.showHidden; repaint(); });
  tools.append(toggle);
  root.append(tools);

  const kindLabel = new Map(view.coreKinds.map((k) => [k.wire, k.label]));
  const shown = view.sets.filter((s) => state.showHidden || !HIDDEN.has(s.status));
  const kinds = [...new Set(shown.map((s) => s.kind))];
  for (const kind of kinds) {
    const section = el("section", "dictionary-kind");
    section.dataset.kind = kind;
    section.append(el("h3", "sheet-section", kindLabel.get(kind) ?? kind));
    const inKind = shown.filter((s) => s.kind === kind);
    const roots = inKind.filter((s) => !s.parentId || !inKind.some((p) => p.id === s.parentId));
    const list = el("ul", "dictionary-tree");
    const add = (set: DictSet, depth: number): void => {
      list.append(row(set, depth, view, byId, state, handlers, repaint));
      for (const child of inKind.filter((c) => c.parentId === set.id)) add(child, depth + 1);
    };
    roots.forEach((r) => add(r, 0));
    section.append(list);
    root.append(section);
  }

  const named = view.screens.filter((c) => c.name);
  if (named.length) {
    const places = el("section", "dictionary-places");
    places.append(el("h3", "sheet-section", "Places"));
    const nameOf = new Map(view.screens.map((c) => [c.roomKey, c.name]));
    const setName = new Map(view.sets.map((s) => [s.id, s.shownName]));
    const ul = el("ul", "dictionary-place-list");
    for (const card of named.slice(0, 60)) {
      const li = el("li", "dictionary-place");
      li.dataset.room = card.roomKey;
      const head = el("div", "dictionary-head");
      head.append(el("strong", undefined, card.name ?? ""));
      if (card.panelOf) head.append(chip(`panel over ${nameOf.get(card.panelOf) ?? "a screen"}`, "accent"));
      if (card.category) head.append(chip(`${card.category} selected`, "neutral"));
      li.append(head);
      if (card.items.length) li.append(el("p", "dictionary-where", `${card.panelOf ? "Offers" : "Buttons"}: ${card.items.join(" · ")}`));
      const tags = card.sets.map((id) => setName.get(id)).filter((n): n is string => Boolean(n));
      if (tags.length) li.append(el("p", "muted dictionary-meta", `Groups here: ${tags.join(", ")}`));
      ul.append(li);
    }
    places.append(ul, el("p", "muted table-note", "Names are the app's own words. A panel is what opens over a screen, like the menu behind “+”; Cyclone reads what it offers and closes it again."));
    root.append(places);
  }

  const health = view.health;
  const issues: string[] = [];
  const name = (id: string): string => byId.get(id)?.shownName ?? id;
  health.nearDuplicates.forEach(([a, b]) => issues.push(`“${name(a)}” and “${name(b)}” look alike: merge them if they are the same group.`));
  health.orphans.forEach((id) => issues.push(`“${name(id)}” sits under a group that is gone.`));
  health.tooWide.forEach((id) => issues.push(`“${name(id)}” has many sub-groups; check whether some are the same.`));
  health.staleCandidates.forEach((id) => issues.push(`“${name(id)}” has waited over two weeks.`));
  if (health.notSeenInVersion.length && view.currentVersion) issues.push(`${health.notSeenInVersion.length} group${health.notSeenInVersion.length === 1 ? " was" : "s were"} not seen in version ${view.currentVersion} yet. Map again to check.`);
  const healthBox = el("section", "dictionary-health");
  healthBox.append(el("h3", "sheet-section", "Needs a look"));
  if (issues.length) {
    const ul = el("ul", "dictionary-issues");
    issues.slice(0, 12).forEach((text) => ul.append(el("li", undefined, text)));
    healthBox.append(ul);
  } else healthBox.append(el("p", "muted", "Nothing: no near-duplicates, orphans or old waiting groups."));
  root.append(healthBox);

  const audit = el("section", "dictionary-audit");
  audit.append(el("h3", "sheet-section", "What changed"));
  const ul = el("ul", "dictionary-log");
  view.audit.slice(0, 20).forEach((e) => ul.append(el("li", undefined, `${new Date(e.at).toLocaleString()} · ${e.action} · ${e.detail} · by ${e.by}`)));
  audit.append(ul, el("p", "muted table-note", `JEV watches the organizer's questions and never decides: ${view.jev.summary}`));
  root.append(audit);

  if (view.glossary) {
    const glossary = el("details", "dictionary-glossary");
    glossary.append(el("summary", undefined, "What an agent reads"), el("pre", "code-block", view.glossary));
    root.append(glossary);
  }
  return root;
}

/** "App word or yours?": downloaded names a probe proved to be a category, kept only on the phone until answered. */
function reviewCard(view: AppDictionaryView, handlers: DictionaryHandlers): HTMLElement {
  const box = el("section", "dictionary-review");
  box.append(el("h3", "sheet-section", `Part of ${view.appLabel || "the app"}?`));
  for (const item of view.review) {
    const row = el("div", "dictionary-review-item");
    row.dataset.reviewId = item.id;
    const where = `a tab${item.screenTitle ? ` on “${item.screenTitle}”` : ""}${item.siblings.length ? ` next to ${item.siblings.map((s) => `“${s}”`).join(", ")}` : ""}`;
    row.append(el("p", undefined, `“${item.name}” is ${where}. Is it a word from ${view.appLabel || "the app"}, or a name you made?`));
    const actions = el("div", "dictionary-actions");
    const yes = actionButton("App word", { variant: "primary" });
    yes.addEventListener("click", () => handlers.edit("app_word", item.id));
    const mine = actionButton("Mine", { variant: "ghost" });
    mine.addEventListener("click", () => handlers.edit("mine", item.id));
    actions.append(yes, mine);
    row.append(actions);
    box.append(row);
  }
  box.append(el("p", "muted table-note", "Kept only on the phone until you answer. “Mine” is never stored, only a code so Cyclone doesn't ask again."));
  return box;
}

function row(set: DictSet, depth: number, view: AppDictionaryView, byId: Map<string, DictSet>, state: DictionaryViewState, handlers: DictionaryHandlers, repaint: () => void): HTMLElement {
  const item = el("li", `dictionary-set status-${set.status}`);
  item.dataset.setId = set.id;
  item.style.marginLeft = `${depth * 20}px`;
  const head = el("div", "dictionary-head");
  head.append(el("strong", "dictionary-name", set.shownName), chip(statusLabel(set, byId), TONE[set.status]));
  if (set.shownName !== set.name) head.append(el("span", "muted", `app calls it “${set.name}”`));
  if (set.nameProof === "vocabulary") head.append(chip("from a downloaded menu", "neutral"));
  item.append(head);
  const where = whereLine(set);
  if (where) item.append(el("p", "dictionary-where", where));
  const why = waitingLine(set);
  if (why) item.append(el("p", "dictionary-why", `Waiting: ${why}`));
  item.append(el("p", "muted dictionary-meta", `${set.id} · seen ${set.observations}× on ${set.days} day${set.days === 1 ? "" : "s"}${set.aliases.length ? ` · also called ${set.aliases.map((a) => `“${a}”`).join(", ")}` : ""}`));

  const actions = el("div", "dictionary-actions");
  const button = (label: string, action: () => void, variant: "ghost" | "secondary" | "danger" = "ghost"): void => {
    const b = actionButton(label, { variant });
    b.dataset.action = label;
    b.addEventListener("click", action);
    actions.append(b);
  };
  const others = view.sets.filter((s) => s.id !== set.id && (s.status === "confirmed" || s.status === "locked"));
  if (set.status === "merged") {
    button("Undo merge", () => handlers.edit("unmerge", set.id));
  } else if (set.status !== "rejected") {
    if (set.status === "candidate") button("Confirm", () => handlers.edit("confirm", set.id), "secondary");
    if (set.status === "locked") button("Unlock", () => handlers.edit("unlock", set.id));
    else button("Lock", () => handlers.edit("lock", set.id));
    button("Rename", () => { state.renaming = set.id; repaint(); });
    if (set.status !== "locked" && others.length) {
      const merge = el("select", "dictionary-merge") as HTMLSelectElement;
      merge.setAttribute("aria-label", `Merge ${set.shownName} into`);
      merge.append(option("", "Same as…"), ...others.map((o) => option(o.id, o.path)));
      merge.addEventListener("change", () => { if (merge.value) handlers.edit("merge", set.id, { into: merge.value }); });
      actions.append(merge);
    }
    const move = el("select", "dictionary-move") as HTMLSelectElement;
    move.setAttribute("aria-label", `Move ${set.shownName} under`);
    move.append(option("", "Under…"), option("-", "No parent (top level)"), ...others.filter((o) => o.kind === set.kind).map((o) => option(o.id, o.path)));
    move.addEventListener("change", () => {
      if (!move.value) return;
      handlers.edit("move", set.id, { parentId: move.value === "-" ? null : move.value });
    });
    actions.append(move);
    if (set.status !== "locked") button("Reject", () => handlers.edit("reject", set.id), "danger");
  } else {
    button("Confirm", () => handlers.edit("confirm", set.id), "secondary");
  }
  item.append(actions);

  if (state.renaming === set.id) {
    const form = el("form", "dictionary-rename");
    const input = el("input", "input") as HTMLInputElement;
    input.value = set.shownName;
    input.maxLength = 60;
    input.setAttribute("aria-label", "Name shown");
    const save = actionButton("Save name", { variant: "primary" });
    save.type = "submit";
    const cancel = actionButton("Cancel", { variant: "ghost" });
    cancel.addEventListener("click", () => { state.renaming = null; repaint(); });
    form.append(input, save, cancel);
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const label = input.value.trim();
      state.renaming = null;
      if (label) handlers.edit("rename", set.id, { label });
      else repaint();
    });
    item.append(form);
  }
  return item;
}

function option(value: string, text: string): HTMLOptionElement {
  const node = el("option", undefined, text) as HTMLOptionElement;
  node.value = value;
  return node;
}
