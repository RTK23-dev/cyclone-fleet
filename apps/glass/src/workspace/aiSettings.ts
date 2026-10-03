/**
 * AI settings (plan 33 §7): the provider key (saved once, never shown again), the model with its prices, spending
 * limits, privacy, how much the AI may do by itself, the owner's standing instructions, and what its tools can and
 * cannot do. Everything is kept by the runtime; this screen only shows it and sends the owner's choices.
 */
import type { GlassContext } from "../app.js";
import {
  aiApi, budgetShare, contextLabel, filterModels, formatUsd, priceLabel, type AiModel, type AiStatus, type Autonomy,
} from "../services/ai.js";
import { el, setChildren } from "../ui/dom.js";
import { emptyState, loadingState } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import type { GlassPage } from "../pages/page.js";
import { workspaceBus } from "./directory.js";

const KIND_TITLE = { read: "Reads", workspace: "Edits pages and cards", phone: "Proposes phone work (you apply it)" } as const;

export function createAiSettings(ctx: GlassContext): GlassPage {
  const element = el("div", "page ws-db ai-settings");
  let status: AiStatus | null = null;
  let models: AiModel[] = [];
  let everything = false;
  let query = "";
  let freeOnly = false;
  let message = "";
  let tone: "ok" | "warn" = "ok";
  let destroyed = false;

  const say = (text: string, kind: "ok" | "warn" = "ok") => {
    message = text;
    tone = kind;
    draw();
  };

  async function run(action: () => Promise<AiStatus>, done: string): Promise<void> {
    try {
      status = await action();
      say(done);
    } catch (err) {
      say((err as Error).message, "warn");
    }
  }

  function section(title: string, hint: string, ...children: Array<Node | null>): HTMLElement {
    const box = el("section", "ai-section");
    box.append(el("h2", "ai-section-title", title));
    if (hint) box.append(el("p", "ai-muted", hint));
    for (const child of children) if (child) box.append(child);
    return box;
  }

  function keySection(s: AiStatus): HTMLElement {
    const state = el("p", `ai-key-state ${s.keySaved ? "ai-key-ok" : ""}`,
      s.keySaved ? `✓ Key saved${s.keySavedAt ? ` ${relativeTime(s.keySavedAt)}` : ""}${s.keyKept ? "" : " (kept until Cyclone restarts)"}` : "No key yet");
    const form = el("form", "ai-key-form");
    const field = el("input", "ai-key-input");
    field.type = "password";
    field.autocomplete = "off";
    field.spellcheck = false;
    field.placeholder = s.keySaved ? "Paste a new key to replace it" : "Paste your key";
    field.setAttribute("aria-label", `${s.provider.name} key`);
    const save = el("button", "btn btn-primary", s.keySaved ? "Replace key" : "Save and check");
    save.type = "submit";
    form.append(field, save);
    form.addEventListener("submit", (e: Event) => {
      e.preventDefault();
      const value = String(field.value ?? "").trim();
      field.value = "";
      if (!value) return;
      void run(async () => {
        const saved = await aiApi.saveKey(ctx.client, value);
        models = (await aiApi.models(ctx.client, { all: everything, refresh: true }).catch(() => ({ models: [] as AiModel[] }))).models;
        return saved;
      }, "The key works and is saved on this PC.").then(() => {
        const c = status?.check;
        if (c?.remainingUsd !== undefined) say(`The key works and is saved on this PC. Credit left: ${formatUsd(c.remainingUsd)}.`);
      });
    });
    const row = el("div", "ai-row");
    if (s.keySaved) {
      const test = el("button", "btn btn-ghost btn-small", "Test the key");
      test.type = "button";
      test.addEventListener("click", () => void run(() => aiApi.testKey(ctx.client), "The key works."));
      const forget = el("button", "btn btn-ghost btn-small", "Forget the key");
      forget.type = "button";
      forget.addEventListener("click", () => {
        if (globalThis.confirm && !globalThis.confirm("Forget the key? The AI stops until you add one again.")) return;
        void run(() => aiApi.forgetKey(ctx.client), "The key is forgotten.");
      });
      row.append(test, forget);
    }
    if (s.provider.keysUrl) {
      const get = el("a", "ai-link", `Get a key from ${s.provider.name} ↗`);
      get.href = s.provider.keysUrl;
      get.target = "_blank";
      get.rel = "noopener noreferrer";
      row.append(get);
    }
    return section(`${s.provider.name} key`,
      `The AI runs on your own ${s.provider.name} account. The key is kept by Cyclone on this PC (protected by Windows) and is never shown again, logged, or given to a model.`,
      state, form, row);
  }

  function modelSection(s: AiStatus): HTMLElement {
    if (!s.keySaved) return section("Model", "Save a key first; then pick from every model it can use.");
    const tools = el("div", "ai-row");
    const search = el("input", "ai-search");
    search.type = "search";
    search.placeholder = "Search models";
    search.value = query;
    search.setAttribute("aria-label", "Search models");
    search.addEventListener("input", () => {
      query = String(search.value ?? "");
      drawList();
    });
    const free = el("label", "ai-toggle");
    const freeBox = el("input");
    freeBox.type = "checkbox";
    freeBox.checked = freeOnly;
    freeBox.addEventListener("change", () => { freeOnly = Boolean(freeBox.checked); drawList(); });
    free.append(freeBox, el("span", undefined, "Free only"));
    const all = el("label", "ai-toggle");
    const allBox = el("input");
    allBox.type = "checkbox";
    allBox.checked = everything;
    allBox.addEventListener("change", () => {
      everything = Boolean(allBox.checked);
      void aiApi.models(ctx.client, { all: everything }).then((r) => { models = r.models; drawList(); }).catch((err: Error) => say(err.message, "warn"));
    });
    all.append(allBox, el("span", undefined, "Show models without tools"));
    const refresh = el("button", "btn btn-ghost btn-small", "Refresh list");
    refresh.type = "button";
    refresh.addEventListener("click", () => void aiApi.models(ctx.client, { all: everything, refresh: true }).then((r) => { models = r.models; drawList(); })
      .catch((err: Error) => say(err.message, "warn")));
    tools.append(search, free, all, refresh);
    const listBox = el("div", "ai-models");
    listBox.setAttribute("role", "radiogroup");
    listBox.setAttribute("aria-label", "Model");
    function drawList(): void {
      const shown = filterModels(models, query, { freeOnly });
      const current = s.model;
      const rows = shown.slice(0, 200).map((m) => {
        const row = el("label", `ai-model-row${m.id === current ? " ai-model-on" : ""}${m.tools ? "" : " ai-model-off"}`);
        const radio = el("input");
        radio.type = "radio";
        radio.name = "ai-model";
        radio.value = m.id;
        radio.checked = m.id === current;
        radio.disabled = !m.tools;
        radio.setAttribute("aria-label", m.name);
        radio.addEventListener("change", () => void run(() => aiApi.saveSettings(ctx.client, { model: m.id }), `${m.name} is the default model.`));
        const text = el("span", "ai-model-text");
        text.append(el("span", "ai-model-name", m.name), el("span", "ai-model-id", m.id));
        const meta = el("span", "ai-model-meta");
        meta.append(el("span", m.free ? "ai-free" : undefined, priceLabel(m)));
        if (contextLabel(m)) meta.append(el("span", undefined, contextLabel(m)));
        meta.append(el("span", m.tools ? "ai-tools-yes" : "ai-tools-no", m.tools ? "uses tools" : "no tools"));
        row.append(radio, text, meta);
        return row;
      });
      setChildren(listBox, ...(rows.length ? rows : [el("p", "ai-muted", models.length ? "No model matches." : "The model list could not be loaded yet.")]),
        shown.length > 200 ? el("p", "ai-muted", `Showing 200 of ${shown.length}. Search to narrow it down.`) : null);
    }
    drawList();
    const chosen = models.find((m) => m.id === s.model);
    return section("Model", "Only models that use tools can read your workspace and make proposals. Each conversation can pick another model too.",
      el("p", "ai-current", s.model ? `Default: ${chosen?.name ?? s.model}` : "No model picked yet."), tools, listBox);
  }

  function spendingSection(s: AiStatus): HTMLElement {
    const bar = (label: string, spent: number, cap: number) => {
      const wrap = el("div", "ai-budget");
      const track = el("div", "ai-budget-track");
      const fill = el("div", "ai-budget-fill");
      fill.style.width = `${Math.round(budgetShare(spent, cap) * 100)}%`;
      track.append(fill);
      wrap.append(el("div", "ai-budget-label", `${label}: ${formatUsd(spent)} of ${formatUsd(cap)}`), track);
      return wrap;
    };
    const form = el("form", "ai-caps");
    const cap = (label: string, value: number) => {
      const wrap = el("label", "ai-cap");
      const field = el("input");
      field.type = "number";
      field.min = "0.05";
      field.step = "0.5";
      field.value = String(value);
      field.setAttribute("aria-label", label);
      wrap.append(el("span", undefined, label), field);
      return { wrap, field };
    };
    const daily = cap("Daily limit ($)", s.dailyCapUsd);
    const monthly = cap("Monthly limit ($)", s.monthlyCapUsd);
    const save = el("button", "btn btn-small", "Save limits");
    save.type = "submit";
    form.append(daily.wrap, monthly.wrap, save);
    form.addEventListener("submit", (e: Event) => {
      e.preventDefault();
      void run(() => aiApi.saveSettings(ctx.client, { dailyCapUsd: Number(daily.field.value), monthlyCapUsd: Number(monthly.field.value) }), "Limits saved.");
    });
    return section("Spending", "The AI stops when a limit is reached, and asks you to raise it. Costs are what the provider reports for each call.",
      bar("Today", s.spentTodayUsd, s.dailyCapUsd), bar("This month", s.spentMonthUsd, s.monthlyCapUsd), form);
  }

  function choicesSection(s: AiStatus): HTMLElement {
    const box = el("div", "ai-choices");
    const radio = (value: Autonomy, title: string, hint: string) => {
      const row = el("label", `ai-choice${s.autonomy === value ? " ai-choice-on" : ""}`);
      const input = el("input");
      input.type = "radio";
      input.name = "ai-autonomy";
      input.checked = s.autonomy === value;
      input.addEventListener("change", () => void run(() => aiApi.saveSettings(ctx.client, { autonomy: value }), "Saved."));
      const text = el("span");
      text.append(el("strong", undefined, title), el("span", "ai-muted", hint));
      row.append(input, text);
      return row;
    };
    box.append(
      radio("propose", "Ask me before every change (recommended)", "Page edits, cards, tasks and routines each arrive as a card you apply or discard."),
      radio("workspace", "Let it edit pages and cards", "It writes in your pages and plan boards directly. Tasks and routines still wait for you."),
    );
    const privacy = el("label", "ai-toggle ai-privacy");
    const privateBox = el("input");
    privateBox.type = "checkbox";
    privateBox.checked = s.privateOnly;
    privateBox.addEventListener("change", () => void run(() => aiApi.saveSettings(ctx.client, { privateOnly: Boolean(privateBox.checked) }), "Saved."));
    privacy.append(privateBox, el("span", undefined, "Private providers only: never send to providers that may keep or train on what you send"));
    return section("What it may do", "", box, privacy);
  }

  function instructionsSection(s: AiStatus): HTMLElement {
    const form = el("form", "ai-instructions");
    const text = el("textarea", "ai-instructions-text");
    text.rows = 5;
    text.value = s.instructions;
    text.placeholder = "For example: Plan in weeks starting Monday. Keep cards short. Our shop is Corner Shop; posts go out at 18:00.";
    text.setAttribute("aria-label", "Standing instructions");
    const save = el("button", "btn btn-small", "Save instructions");
    save.type = "submit";
    form.append(text, save);
    form.addEventListener("submit", (e: Event) => {
      e.preventDefault();
      void run(() => aiApi.saveSettings(ctx.client, { instructions: String(text.value ?? "") }), "Instructions saved.");
    });
    return section("Standing instructions", "The AI reads these at the start of every conversation. Never put passwords or codes here.", form);
  }

  function toolsSection(s: AiStatus): HTMLElement {
    const grid = el("div", "ai-tools");
    for (const kind of ["read", "workspace", "phone"] as const) {
      const col = el("div", "ai-tools-col");
      col.append(el("h3", undefined, KIND_TITLE[kind]));
      const ul = el("ul");
      for (const t of s.tools.filter((x) => x.kind === kind)) ul.append(el("li", undefined, t.description));
      col.append(ul);
      grid.append(col);
    }
    const never = el("p", "ai-never",
      "It can never: approve anything, delete anything, see passwords or codes, add connections, change accounts, or control a phone directly. Phones still ask you before they send, pay, delete or sign in.");
    return section("What the AI can do", "", grid, never);
  }

  function draw(): void {
    if (destroyed) return;
    if (!status) return setChildren(element, loadingState("Loading AI settings…"));
    const s = status;
    const header = el("div", "ws-db-head");
    const title = el("div");
    title.append(el("h1", "ws-db-title", "✨ AI"), el("p", "ws-db-sub", "Your project manager: it reads the workspace, plans, writes pages and proposes tasks."));
    const ask = el("button", "btn btn-primary", "Ask AI");
    ask.type = "button";
    ask.disabled = !s.keySaved || !s.model;
    ask.addEventListener("click", () => workspaceBus.askAi(null));
    header.append(title, ask);
    setChildren(element, header,
      message ? el("p", `ai-message ai-message-${tone}`, message) : null,
      keySection(s), modelSection(s), spendingSection(s), choicesSection(s), instructionsSection(s), toolsSection(s));
  }

  void (async () => {
    try {
      status = await aiApi.status(ctx.client);
      if (status.keySaved) models = (await aiApi.models(ctx.client).catch(() => ({ models: [] as AiModel[] }))).models;
      draw();
    } catch (err) {
      if (!destroyed) setChildren(element, emptyState({ icon: "alert", tone: "danger", title: "AI settings could not load", body: (err as Error).message }));
    }
  })();
  draw();
  return { element, destroy() { destroyed = true; } };
}
