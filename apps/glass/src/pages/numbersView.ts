/**
 * Command Center → Numbers (alpha.102): every number Cyclone can receive codes on, in one place.
 *
 * - **At a glance:** how many numbers, how many are ready, which need attention, which rentals end soon, and which
 *   aren't used by an account yet.
 * - **Where each number's texts arrive:** a phone in the fleet (its SIM, or a number confirmed on it), a forwarder
 *   plugin, or a rented number (provider, end date, and the plugin that forwards it).
 * - **The owner manages:** label, pause, assign to one account, add a rented or forwarded number, remove one.
 *   A phone's own numbers come from the phone.
 *
 * Numbers only: no text, sender or code is ever shown here. Nothing is decided here; the gateway's answer is shown.
 */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, segmented, statTile, type Tone } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import {
  formatNumber,
  numbersApi,
  originLabel,
  STATE_LABEL,
  type NewNumber,
  type NumberOrigin,
  type NumbersOverview,
  type NumbersPhone,
  type OwnedNumber,
} from "../services/numbers.js";

type Filter = "all" | NumberOrigin | "attention";
const POLL_MS = 15_000;
const DAY = 86_400_000;

export interface NumbersDeps {
  now(): number;
  later(fn: () => void, ms: number): () => void;
}

const defaultDeps: NumbersDeps = {
  now: () => Date.now(),
  later: (fn, ms) => {
    const id = setTimeout(fn, ms);
    (id as unknown as { unref?: () => void }).unref?.();
    return () => clearTimeout(id);
  },
};

export interface NumbersView {
  element: HTMLElement;
  refresh(fresh?: boolean): Promise<void>;
  destroy(): void;
}

const STATE_TONE: Record<OwnedNumber["state"], Tone> = {
  ready: "success", paused: "neutral", expired: "danger", offline: "warning", missing: "warning", codes_off: "warning",
  no_source: "warning", source_off: "warning", manual: "neutral",
};

function option(value: string, label: string): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  return node;
}

function input(label: string, placeholder = "", type = "text"): HTMLInputElement {
  const node = el("input", "cc-input");
  node.type = type;
  node.placeholder = placeholder;
  node.setAttribute("aria-label", label);
  return node;
}

function field(label: string, control: HTMLElement): HTMLElement {
  const wrap = el("label", "nm-field");
  wrap.append(el("span", "nm-field-label", label), control);
  return wrap;
}

export function createNumbersView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void, deps: NumbersDeps = defaultDeps): NumbersView {
  const element = el("div", "nm");
  const summary = el("div", "nm-summary");
  const phones = el("div", "nm-phones");
  const toolbar = el("div", "nm-toolbar");
  const listBox = el("div", "nm-list");
  const addBox = card("cc-card nm-add");
  let data: NumbersOverview | null = null;
  let error: { code?: string; message: string } | null = null;
  let filter: Filter = "all";
  let adding = false;
  let editing: string | null = null;
  let destroyed = false;
  let cancel: (() => void) | null = null;

  const filters = segmented<Filter>([
    { id: "all", label: "All" },
    { id: "phone", label: "Phones" },
    { id: "plugin", label: "Forwarded" },
    { id: "rental", label: "Rented" },
    { id: "attention", label: "Needs you" },
  ], filter, (id) => {
    filter = id;
    renderList();
  });
  const refreshButton = actionButton("Check phones now", { variant: "ghost" });
  refreshButton.addEventListener("click", () => void refresh(true));
  const addButton = actionButton("Add a number", { variant: "primary" });
  addButton.addEventListener("click", () => {
    adding = !adding;
    renderAdd();
  });
  toolbar.append(filters.element, el("span", "nm-spacer"), refreshButton, addButton);
  element.append(summary, phones, toolbar, addBox, listBox);

  const schedule = (): void => {
    cancel?.();
    if (!destroyed) cancel = deps.later(() => void refresh(), POLL_MS);
  };

  async function refresh(fresh = false): Promise<void> {
    try {
      data = await numbersApi.get(ctx.client, fresh);
      error = null;
    } catch (err) {
      error = { code: (err as { code?: string }).code, message: (err as Error).message || "The numbers couldn't be read." };
    }
    if (destroyed) return;
    render();
    schedule();
  }

  async function act(label: string, action: () => Promise<unknown>): Promise<boolean> {
    try {
      await action();
      say(`${label}: done.`, "ok");
      await refresh();
      return true;
    } catch (err) {
      say((err as Error).message || `${label} failed.`, "error");
      return false;
    }
  }

  function render(): void {
    if (!data && !error) {
      setChildren(listBox, loadingState("Reading your numbers…"));
      return;
    }
    if (!data) {
      setChildren(summary);
      setChildren(phones);
      setChildren(listBox, errorState("The numbers couldn't be read", error ?? { message: "Unknown error" }, () => void refresh(true)));
      return;
    }
    renderSummary(data);
    renderPhones(data.phones);
    renderAdd();
    renderList();
  }

  function renderSummary(d: NumbersOverview): void {
    const s = d.summary;
    setChildren(summary,
      statTile("Numbers", String(s.total)),
      statTile("Ready for codes", String(s.ready), s.ready ? "success" : "neutral"),
      statTile("Need you", String(s.attention), s.attention ? "warning" : "neutral"),
      statTile("Rentals ending in 7 days", String(s.expiringSoon), s.expiringSoon ? "warning" : "neutral"),
      statTile("Not used by an account", String(s.unassigned)),
    );
    filters.set(filter, { all: s.total, phone: s.byOrigin.phone, plugin: s.byOrigin.plugin, rental: s.byOrigin.rental, attention: s.attention });
  }

  /** Phones in the fleet whose numbers can't be read yet, each with the one thing to do about it. */
  function renderPhones(list: NumbersPhone[]): void {
    const needing = list.filter((p) => p.state !== "ready");
    if (!needing.length) {
      setChildren(phones);
      return;
    }
    const box = card("cc-card nm-phone-card");
    box.append(el("h3", "nm-h", "Phones to set up"));
    for (const phone of needing) {
      const row = el("div", "nm-phone");
      row.append(el("strong", undefined, phone.name), chip(phone.state === "offline" ? "Offline" : "Setup", phone.state === "offline" ? "neutral" : "warning"),
        el("span", "nm-muted", phone.hint));
      box.append(row);
    }
    setChildren(phones, box);
  }

  function visible(d: NumbersOverview): OwnedNumber[] {
    if (filter === "all") return d.numbers;
    if (filter === "attention") return d.numbers.filter((n) => n.state !== "ready" && n.state !== "paused");
    return d.numbers.filter((n) => n.origin === filter);
  }

  function renderList(): void {
    if (!data) return;
    const rows = visible(data);
    if (!data.numbers.length) {
      setChildren(listBox, emptyState({
        icon: "phone",
        title: "No numbers yet",
        body: "Turn on Codes on a phone (Settings → Permissions → Codes) and its number appears here, or add a number you rent or forward.",
      }));
      return;
    }
    if (!rows.length) {
      setChildren(listBox, emptyState({ title: "Nothing here", body: "No number matches this filter." }));
      return;
    }
    const table = el("table", "cc-table nm-table");
    const head = el("tr");
    for (const title of ["Number", "Where texts arrive", "State", "Account", ""]) head.append(el("th", undefined, title));
    table.append(el("thead"), el("tbody"));
    table.querySelector("thead")?.append(head);
    const body = table.querySelector("tbody")!;
    for (const n of rows) body.append(...numberRows(n));
    setChildren(listBox, table);
  }

  function numberRows(n: OwnedNumber): HTMLElement[] {
    const tr = el("tr", `nm-row nm-${n.state}`);
    tr.dataset.id = n.id;
    const who = el("td");
    who.append(el("div", "nm-number", formatNumber(n.number)));
    if (n.label) who.append(el("div", "nm-muted", n.label));
    const where = el("td");
    where.append(el("div", undefined, originLabel(n)));
    if (n.expiresAt) {
      const left = n.expiresAt - deps.now();
      where.append(el("div", n.expiresSoon || left <= 0 ? "nm-warn" : "nm-muted",
        left <= 0 ? "Rental ended" : `Rented until ${new Date(n.expiresAt).toLocaleDateString()} (${Math.ceil(left / DAY)} days)`));
    }
    if (n.origin === "phone" && n.seenAt) where.append(el("div", "nm-muted", `Seen ${relativeTime(n.seenAt, deps.now())}`));
    const state = el("td");
    state.append(chip(STATE_LABEL[n.state], STATE_TONE[n.state]));
    if (n.why) state.append(el("div", "nm-muted", n.why));
    const account = el("td");
    account.append(accountSelect(n));
    const actions = el("td", "nm-actions");
    const edit = actionButton(editing === n.id ? "Close" : "Edit", { variant: "ghost" });
    edit.addEventListener("click", () => {
      editing = editing === n.id ? null : n.id;
      renderList();
    });
    const pause = actionButton(n.paused ? "Resume" : "Pause", { variant: "ghost" });
    pause.addEventListener("click", () => void act(n.paused ? "Resumed" : "Paused", () => numbersApi.update(ctx.client, n.id, { paused: !n.paused })));
    actions.append(edit, pause);
    tr.append(who, where, state, account, actions);
    if (editing !== n.id) return [tr];
    const editRow = el("tr", "nm-edit-row");
    const cell = el("td");
    cell.colSpan = 5;
    cell.append(editor(n));
    editRow.append(cell);
    return [tr, editRow];
  }

  function accountSelect(n: OwnedNumber): HTMLSelectElement {
    const select = el("select", "cc-input nm-account");
    select.setAttribute("aria-label", `Account for ${n.number}`);
    select.append(option("", "No account"));
    const taken = new Set((data?.numbers ?? []).filter((x) => x.id !== n.id && x.account).map((x) => x.account!.id));
    for (const a of data?.accounts ?? []) {
      const node = option(a.id, `${a.handle} · ${a.service}${taken.has(a.id) ? " (has a number)" : ""}`);
      node.disabled = taken.has(a.id);
      select.append(node);
    }
    select.value = n.account?.id ?? "";
    select.addEventListener("change", () => {
      void act(select.value ? "Assigned" : "Unassigned", () => numbersApi.update(ctx.client, n.id, { accountId: select.value || null }));
    });
    return select;
  }

  function editor(n: OwnedNumber): HTMLElement {
    const box = el("div", "nm-editor");
    const label = input("Label", "Food niche, client name…");
    label.value = n.label;
    const save = actionButton("Save", { variant: "primary" });
    box.append(field("Label", label));
    const change: () => Parameters<typeof numbersApi.update>[2] = () => ({ label: label.value });
    let extra: () => Record<string, unknown> = () => ({});
    if (n.origin === "rental" || n.origin === "plugin") {
      const source = pluginSelect(n.source.ref ?? "");
      box.append(field("Plugin that forwards its texts", source));
      let provider: HTMLInputElement | null = null;
      let until: HTMLInputElement | null = null;
      if (n.origin === "rental") {
        provider = input("Provider", "Who you rent it from");
        provider.value = n.source.provider ?? "";
        until = input("Rented until", "", "date");
        if (n.expiresAt) until.value = new Date(n.expiresAt).toISOString().slice(0, 10);
        box.append(field("Provider", provider), field("Rented until", until));
      }
      extra = () => ({
        source: source.value,
        ...(provider ? { provider: provider.value } : {}),
        ...(until ? { expiresAt: until.value ? Date.parse(`${until.value}T23:59:00`) : null } : {}),
      });
    }
    save.addEventListener("click", () => {
      void act("Saved", () => numbersApi.update(ctx.client, n.id, { ...change(), ...extra() })).then((ok) => {
        if (ok) {
          editing = null;
          renderList();
        }
      });
    });
    const row = el("div", "cc-row");
    row.append(save);
    if (n.origin !== "phone") {
      const remove = actionButton("Remove", { variant: "danger" });
      remove.addEventListener("click", () => {
        if (remove.dataset.confirm !== "1") {
          remove.dataset.confirm = "1";
          remove.replaceChildren(el("span", "btn-label", "Remove: are you sure?"));
          return;
        }
        void act("Removed", () => numbersApi.remove(ctx.client, n.id));
      });
      row.append(remove);
    } else {
      row.append(el("span", "nm-muted", "A phone's own number is managed on the phone (Settings → Codes)."));
    }
    box.append(row);
    return box;
  }

  function pluginSelect(current: string): HTMLSelectElement {
    const select = el("select", "cc-input");
    select.setAttribute("aria-label", "Plugin");
    select.append(option("", data?.plugins.length ? "None yet" : "No forwarder plugin added (Ports → Add plugin)"));
    for (const p of data?.plugins ?? []) select.append(option(p.name, `${p.title}${p.status === "active" ? "" : ` (${p.status})`}`));
    if (current && !(data?.plugins ?? []).some((p) => p.name === current)) select.append(option(current, `${current} (not added)`));
    select.value = current;
    return select;
  }

  function renderAdd(): void {
    addBox.hidden = !adding;
    addButton.replaceChildren(el("span", "btn-label", adding ? "Close" : "Add a number"));
    if (!adding) {
      addBox.replaceChildren();
      return;
    }
    if (addBox.childNodes.length) return; // keep what the owner is typing across polls
    let kind: Exclude<NumberOrigin, "phone"> = "rental";
    const kinds = segmented<Exclude<NumberOrigin, "phone">>([
      { id: "rental", label: "Rented number" },
      { id: "plugin", label: "Forwarded by a plugin" },
      { id: "other", label: "Just track it" },
    ], "rental", (id) => {
      kind = id;
      kinds.set(id);
      showFields();
    });
    const number = input("Number", "+44 7700 900123", "tel");
    const label = input("Label", "Food niche, client name…");
    const provider = input("Provider", "Who you rent it from");
    const until = input("Rented until", "", "date");
    const source = pluginSelect("");
    const account = el("select", "cc-input");
    account.setAttribute("aria-label", "Account");
    account.append(option("", "No account yet"));
    const taken = new Set((data?.numbers ?? []).filter((x) => x.account).map((x) => x.account!.id));
    for (const a of data?.accounts ?? []) if (!taken.has(a.id)) account.append(option(a.id, `${a.handle} · ${a.service}`));
    const providerField = field("Provider", provider);
    const untilField = field("Rented until", until);
    const sourceField = field("Plugin that forwards its texts", source);
    const showFields = (): void => {
      providerField.hidden = kind !== "rental";
      untilField.hidden = kind !== "rental";
      sourceField.hidden = kind === "other";
    };
    const note = el("p", "nm-muted",
      "Rent numbers with your provider; Cyclone keeps track of them, which account uses each, and when each rental ends. " +
      "A rented number's codes reach Cyclone through the plugin that forwards its texts.");
    const save = actionButton("Add number", { variant: "primary" });
    save.addEventListener("click", () => {
      const body: NewNumber = { number: number.value, origin: kind, label: label.value };
      if (kind !== "other" && source.value) body.source = source.value;
      if (kind === "rental") {
        body.provider = provider.value;
        if (until.value) body.expiresAt = Date.parse(`${until.value}T23:59:00`);
      }
      if (account.value) body.accountId = account.value;
      void act("Number added", () => numbersApi.add(ctx.client, body)).then((ok) => {
        if (ok) {
          adding = false;
          addBox.replaceChildren();
          renderAdd();
        }
      });
    });
    const grid = el("div", "nm-grid");
    grid.append(field("Number", number), field("Label", label), providerField, untilField, sourceField, field("Account", account));
    addBox.append(el("h3", "nm-h", "Add a number"), kinds.element, note, grid, save);
    showFields();
  }

  render();
  void refresh(true);

  return {
    element,
    refresh,
    destroy(): void {
      destroyed = true;
      cancel?.();
    },
  };
}
