/**
 * App → Abilities (plan 36 §10): "What you can do in Instagram". A search box over the phone's ability index, each
 * ability with its path in the app's own words, where it came from and how sure the manual is; the self-quiz report
 * with Map deeper; the Lab scores; and Export manual. Built with DOM APIs only; every text is set as text.
 */
import { effectLabel, quizLine, type Ability, type AppManualView } from "../services/manual.js";
import { actionButton, card, chip, emptyState, statTile, type Tone } from "./components.js";
import { el } from "./dom.js";

export interface AbilitiesHandlers {
  search(query: string): void;
  mapDeeper(): void;
  tryIt(ability: Ability): void;
  exportManual(): void;
}

const pct = (v: number | null): string => (v == null ? "—" : `${Math.round(v * 100)}%`);
const tone = (v: number | null, good = 0.8, fair = 0.5): Tone => (v == null ? "neutral" : v >= good ? "success" : v >= fair ? "warning" : "danger");

export function abilitiesView(view: AppManualView, query: string, handlers: AbilitiesHandlers, note?: string): HTMLElement {
  const root = el("div", "abilities");
  if (!view.abilities.length) {
    root.append(emptyState({
      icon: "map",
      title: "Nothing to do here yet",
      body: "Map the app first. Cyclone lists what you can do in it from the places, menus and tabs a pass finds, in the app's own words.",
    }));
    return root;
  }

  const stats = el("div", "stats stats-4 manual-stats");
  stats.append(
    statTile("Things you can do", String(view.scores.abilities)),
    statTile("Map quality", pct(view.scores.map), tone(view.scores.map)),
    statTile("Self-quiz", pct(view.scores.quiz), tone(view.scores.quiz)),
    statTile("Walks that arrived", pct(view.scores.walks), tone(view.scores.walks, 0.9, 0.7)),
  );
  root.append(stats);

  const report = card("manual-report");
  const line = quizLine(view.quiz);
  report.append(el("h2", "card-title", line ?? "No self-quiz yet"));
  report.append(el("p", "muted", line
    ? "After a pass the model writes goals a person might have in this app and the manual tries to answer each one on its own."
    : "The quiz runs after the next mapping pass, with the model you pick for it."));
  const gaps = view.quiz?.goals.filter((g) => !g.abilityId) ?? [];
  if (gaps.length) {
    const list = el("ul", "quiz-gaps");
    for (const gap of gaps.slice(0, 10)) list.append(el("li", undefined, gap.goal));
    report.append(el("h3", "inspector-section", "Still to explore"), list);
  }
  const actions = el("div", "manual-actions");
  const deeper = actionButton("Map deeper · 30 min", { icon: "play", variant: gaps.length ? "primary" : "secondary" });
  deeper.addEventListener("click", () => handlers.mapDeeper());
  const exporter = actionButton("Export manual", { icon: "download" });
  exporter.addEventListener("click", () => handlers.exportManual());
  actions.append(deeper, exporter);
  report.append(actions, el("p", "muted table-note", "Map deeper tries first the menus and tabs whose words fit the goals above. It is still look only."));
  root.append(report);

  const form = el("form", "ability-search");
  const input = el("input", "input") as HTMLInputElement;
  input.type = "search";
  input.placeholder = `Try: ${view.abilities.find((a) => a.kind === "switch")?.name ?? "open settings"}`;
  input.value = query;
  input.maxLength = 200;
  input.setAttribute("aria-label", `What you can do in ${view.appLabel}`);
  const go = actionButton("Find", { icon: "search" });
  go.type = "submit";
  form.append(input, go);
  form.addEventListener("submit", (event) => {
    event.preventDefault?.();
    handlers.search(input.value);
  });
  root.append(el("h2", "section-title", `What you can do in ${view.appLabel || "this app"}`), form);
  if (note) root.append(el("p", "muted", note));

  const byId = new Map(view.abilities.map((a) => [a.id, a]));
  const hits = view.hits.map((h) => ({ ability: byId.get(h.id)!, score: h.score })).filter((h) => h.ability);
  if (view.query) {
    if (!hits.length) root.append(el("p", "muted", `Nothing in the manual fits “${view.query}” yet.`));
    else {
      const best = el("div", "ability-list ability-hits");
      hits.forEach((hit, i) => best.append(abilityRow(hit.ability, handlers, hit.score, i === 0 && view.clear)));
      root.append(best);
    }
  }
  const list = el("div", "ability-list");
  const shown = view.query ? view.abilities.filter((a) => !hits.some((h) => h.ability.id === a.id)) : view.abilities;
  for (const ability of shown.slice(0, 200)) list.append(abilityRow(ability, handlers));
  if (view.query) root.append(el("h3", "inspector-section", "Everything else"));
  root.append(list);
  if (shown.length > 200 || view.truncated) root.append(el("p", "muted table-note", "Showing the first 200. Search to find the rest."));
  return root;
}

function abilityRow(ability: Ability, handlers: AbilitiesHandlers, fit?: number, clear = false): HTMLElement {
  const row = card(`ability-row ability-${ability.kind}`);
  row.dataset.abilityId = ability.id;
  const head = el("div", "ability-head");
  head.append(el("strong", "ability-name", ability.name));
  head.append(chip(ability.provenance === "walked" ? "Walked" : "Mapped", ability.provenance === "walked" ? "success" : "neutral"));
  head.append(chip(effectLabel(ability), ability.effect === "asks" ? "warning" : ability.effect === "choose" ? "accent" : "neutral"));
  if (fit != null) head.append(chip(`fit ${Math.round(fit * 100)}%`, clear ? "success" : "neutral"));
  if (clear) head.append(chip("Clear match", "success"));
  row.append(head);
  if (ability.path.length) {
    const way = el("ol", "ability-path");
    ability.path.forEach((step, i) => {
      if (i > 0) way.append(el("li", "skill-arrow", "›"));
      way.append(el("li", `ability-step${i === ability.path.length - 1 ? " last" : ""}`, step));
    });
    row.append(way);
  }
  const facts = el("div", "ability-facts muted");
  facts.append(el("span", undefined, `${Math.round(ability.confidence * 100)}% sure`));
  if (ability.note) facts.append(el("span", undefined, ability.note));
  if (ability.say.length > 1) facts.append(el("span", undefined, `also: ${ability.say.slice(1, 4).join(" · ")}`));
  row.append(facts);
  const actions = el("div", "ability-actions");
  const tryIt = actionButton("Try it on the phone", { icon: "play" });
  tryIt.addEventListener("click", () => handlers.tryIt(ability));
  actions.append(tryIt);
  row.append(actions);
  return row;
}

/** The goal a "Try it" sends to the phone: the Mind walks the safe part and stops before any choice. */
export function tryItGoal(ability: Ability, appLabel: string): string {
  const stop = ability.pick ? ` Stop before choosing “${ability.pick}”.` : " Stop there.";
  return `In ${appLabel}, go to: ${ability.name} (${ability.path.join(" › ")}).${stop} Do not type, send, pay, delete or change anything.`;
}
