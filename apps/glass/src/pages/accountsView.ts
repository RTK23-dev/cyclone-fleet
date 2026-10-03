/**
 * Accounts, rebuilt (plan 43 T5 + T6): pick a phone, see its installed apps, open an app to see the accounts in it and
 * its sign-up. Glass only manages: the phone walks the sign-up once (a mapping task), keeps the map, and asks the owner
 * for every value and before the control that creates the account. The map becomes a table: one row per account to
 * create next, one column per sign-up field. Passwords are never a column; the phone makes them in the vault.
 */
import type { GlassContext } from "../app.js";
import { loadApps, type PhoneApp } from "../services/apps.js";
import { basisLabel, command, type CcAccount, type CcTask, type OwnerBasis } from "../services/command.js";
import {
  BASIS_CHOICES, CHECK_LABEL, KIND_LABEL, RECIPE, lastMappingTry, mappingDetail, SIGNUP_STATE_LABEL, SIGNUP_STATE_TONE, accountAppRows, mapSummary, signupApi, tableColumns,
  type AccountAppRow, type SignupMap,
} from "../services/signup.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, searchInput } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import { createTableBlock, type TableBlockView } from "../workspace/tableView.js";
import { createAccountsPanel, type CreateAccountsPanel } from "./createAccounts.js";
import { createProfilesBar } from "./profilesBar.js";

const POLL_MS = 10_000;

export interface AccountsView {
  element: HTMLElement;
  /** New accounts from the Command Center's poll. */
  refresh(): void;
  update(devices: GlassContext["devices"]): void;
  destroy(): void;
}

export function createAccountsView(ctx: GlassContext, accounts: () => CcAccount[], say: (text: string, tone?: "ok" | "error") => void): AccountsView {
  const element = el("div", "ac");
  const phones = el("div", "ac-phones");
  phones.setAttribute("role", "tablist");
  const note = el("p", "ac-note");
  const list = el("div", "ac-apps");
  const results = el("div", "ac-list");
  const detail = el("div", "ac-detail");
  const layout = el("div", "ac-layout");
  let devices = ctx.devices;
  let deviceId: string | null = ctx.device?.id ?? devices[0]?.id ?? null;
  let apps: PhoneApp[] = [];
  let maps: SignupMap[] = [];
  let tasks: CcTask[] = [];
  let query = "";
  let open: string | null = null;
  let basis: OwnerBasis = "mine";
  let state: "loading" | "ready" | "error" = "loading";
  let failure: Error | null = null;
  let destroyed = false;
  let seq = 0;
  let tableBlock: TableBlockView | null = null;
  let showTable = false;
  /** One Create accounts panel per sign-up table, kept while its passwords are being sent. */
  const panels = new Map<string, CreateAccountsPanel>();
  /** Plan 43 T4: the chosen profile's apps (null = the whole phone, as Profile A sees it). */
  let profilePackages: Set<string> | null = null;
  const profileBar = createProfilesBar(ctx, say, (_profile, packages) => {
    profilePackages = packages;
    if (open && packages && !packages.has(open)) {
      open = null;
      closeTable();
    }
    if (state === "ready") {
      renderList();
      renderDetail();
    }
  });

  list.append(searchInput("Search apps", (value) => {
    query = value;
    renderList();
  }), results);
  layout.append(list, detail);
  element.append(phones, profileBar.element, note, layout);

  /** The phone's apps, or the chosen profile's (apps the catalog doesn't know yet are listed by package). */
  const visibleApps = (): PhoneApp[] => {
    if (!profilePackages) return apps;
    const known = apps.filter((a) => a.packageName && profilePackages!.has(a.packageName));
    const seen = new Set(known.map((a) => a.packageName));
    const extra = [...profilePackages].filter((p) => !seen.has(p)).map((p): PhoneApp => ({
      placeId: `package:${p}`, kind: "package", label: p, packageName: p, origin: null, installed: true, installedVersion: null,
      mapStatus: "unmapped", rooms: 0, doors: 0, lastVerifiedAt: null, needsRemap: false, personas: [], mappedVersions: [], scenarios: null,
    }));
    return [...known, ...extra];
  };
  const rows = (): AccountAppRow[] => (deviceId ? accountAppRows(visibleApps(), maps, accounts(), tasks, deviceId, query) : []);
  const openRow = (): AccountAppRow | null => {
    if (!open || !deviceId) return null;
    return accountAppRows(visibleApps(), maps, accounts(), tasks, deviceId).find((r) => r.packageName === open) ?? null;
  };

  function renderPhones(): void {
    phones.replaceChildren();
    for (const device of devices) {
      const button = el("button", device.id === deviceId ? "ac-phone active" : "ac-phone");
      button.type = "button";
      button.setAttribute("role", "tab");
      button.setAttribute("aria-selected", String(device.id === deviceId));
      button.append(el("strong", undefined, device.name), el("span", "muted", device.sessionReady ? "Connected" : device.connectionLabel || device.state));
      button.addEventListener("click", () => {
        if (device.id === deviceId) return;
        deviceId = device.id;
        open = null;
        closeTable();
        void load(true);
      });
      phones.append(button);
    }
  }

  async function load(full: boolean): Promise<void> {
    renderPhones();
    if (full) void profileBar.load(deviceId);
    if (!deviceId) {
      state = "ready";
      render();
      return;
    }
    const mine = ++seq;
    const device = deviceId;
    if (full) {
      state = "loading";
      render();
    }
    try {
      const [catalog, signups, all] = await Promise.all([
        full || !apps.length ? loadApps(ctx.client, device).then((c) => c.apps) : Promise.resolve(apps),
        signupApi.maps(ctx.client, device),
        command.tasks(ctx.client),
      ]);
      if (destroyed || mine !== seq) return;
      apps = catalog;
      maps = signups.maps;
      tasks = all;
      note.textContent = signups.note ?? "";
      note.hidden = !signups.note;
      state = "ready";
      failure = null;
    } catch (err) {
      if (destroyed || mine !== seq) return;
      failure = err as Error;
      state = apps.length ? "ready" : "error";
      if (state === "ready") say(failure.message || "The phone could not be reached.", "error");
    }
    render();
  }

  function render(): void {
    if (!devices.length) {
      setChildren(layout, emptyState({ icon: "phone", title: "No phone connected", body: "Connect a phone. Its apps and the accounts in them show up here." }));
      return;
    }
    if (layout.firstChild !== list) setChildren(layout, list, detail);
    if (state === "loading") {
      setChildren(results, loadingState("Reading the phone's apps…"));
      setChildren(detail);
      return;
    }
    if (state === "error") {
      setChildren(results, errorState("The phone's apps are not reachable", failure ?? new Error("Unknown error"), () => void load(true)));
      setChildren(detail);
      return;
    }
    renderList();
    renderDetail();
  }

  function renderList(): void {
    const all = rows();
    if (!all.length) {
      setChildren(results, el("p", "muted", query ? "No app matches." : "No installed apps reported yet."));
      return;
    }
    results.replaceChildren();
    for (const row of all) {
      const item = el("button", row.packageName === open ? "ac-app active" : "ac-app");
      item.type = "button";
      item.dataset.package = row.packageName;
      const text = el("span", "ac-app-text");
      text.append(el("strong", undefined, row.app.label), el("span", "muted", row.packageName));
      const facts = el("span", "ac-app-facts");
      if (row.accounts.length) facts.append(chip(`${row.accounts.length} ${row.accounts.length === 1 ? "account" : "accounts"}`, "accent"));
      if (row.state !== "none") facts.append(chip(SIGNUP_STATE_LABEL[row.state], SIGNUP_STATE_TONE[row.state]));
      item.append(text, facts);
      item.addEventListener("click", () => {
        open = row.packageName;
        closeTable();
        renderList();
        renderDetail();
      });
      results.append(item);
    }
  }

  function renderDetail(): void {
    const row = openRow();
    if (!row) {
      if (!tableBlock) setChildren(detail, emptyState({ icon: "user", title: "Pick an app", body: "See the accounts in it, map how its sign-up works, and line up the next accounts to create." }));
      return;
    }
    const head = el("div", "ac-head");
    const title = el("div");
    title.append(el("h2", "ac-title", row.app.label), el("p", "muted", `${row.packageName}${row.app.installedVersion?.versionName ? ` · ${row.app.installedVersion.versionName}` : ""}`));
    head.append(title, chip(SIGNUP_STATE_LABEL[row.state], SIGNUP_STATE_TONE[row.state]));
    const parts: HTMLElement[] = [head, accountsCard(row), signupCard(row)];
    if (row.map && showTable && row.map.tableId) parts.push(tableHost(row.map.tableId));
    setChildren(detail, ...parts);
  }

  function accountsCard(row: AccountAppRow): HTMLElement {
    const box = card("ac-card");
    box.append(el("h3", "card-title", "Accounts in this app"));
    if (!row.accounts.length) {
      box.append(el("p", "muted", `None yet. Add one you own with New account (app ${row.packageName}), or map the sign-up to let Cyclone create them.`));
      return box;
    }
    const table = el("table", "cc-table");
    const headRow = el("tr");
    for (const label of ["Account", "Whose", "State"]) headRow.append(el("th", undefined, label));
    table.append(headRow);
    for (const a of row.accounts) {
      const tr = el("tr");
      const state = el("td");
      state.append(a.locked ? chip("In use", "accent") : a.status === "paused" ? chip("Paused", "neutral") : chip(a.lastOutcome === "failed" ? "Last task failed" : "Ready", a.lastOutcome === "failed" ? "warning" : "success"));
      tr.append(el("td", undefined, a.handle), el("td", undefined, basisLabel(a.ownerBasis)), state);
      table.append(tr);
    }
    box.append(table);
    return box;
  }

  function signupCard(row: AccountAppRow): HTMLElement {
    const box = card("ac-card");
    box.append(el("h3", "card-title", "Sign-up"));
    if (row.task) {
      const task = row.task;
      box.append(el("p", undefined, `Mapping the ${row.app.label} sign-up: ${mappingDetail(task)}`),
        el("p", "cc-hint", "It asks you for this first account's details, hands any code, CAPTCHA or ID check to you, and asks before it creates the account. Answer in Inbox or on the phone."));
      const inbox = actionButton("Open Inbox", { variant: "secondary", icon: "bell" });
      inbox.addEventListener("click", () => ctx.navigate({ name: "command", tab: "approvals" }));
      const cancel = actionButton("Cancel mapping", { variant: "ghost", icon: "stop" });
      cancel.addEventListener("click", async () => {
        cancel.disabled = true;
        try {
          await command.cancelTask(ctx.client, task.id);
          say(`Stopped mapping ${row.app.label}. You can start it again.`);
        } catch (err) {
          say((err as Error).message || "The mapping could not be cancelled.", "error");
        }
        await load(false);
      });
      const actions = el("div", "cc-actions");
      actions.append(inbox, cancel);
      box.append(actions);
      return box;
    }
    const lastTry = deviceId ? lastMappingTry(tasks, deviceId, row.packageName) : null;
    if (!row.map && lastTry) {
      const why = lastTry.cause || lastTry.run?.summary;
      box.append(el("p", "cc-hint ac-last-try", lastTry.status === "succeeded"
        ? `The last run finished without saving a map${why ? ` (${why})` : ""}. Map it again; answer the phone's questions so it can walk every page.`
        : `Last try ${lastTry.status === "cancelled" ? "was cancelled" : "failed"}${why ? `: ${why}` : "."}`));
    }
    if (!row.map) {
      box.append(el("p", undefined, `Cyclone walks the ${row.app.label} sign-up once on this phone, creating your first account there, and remembers every page: the fields, their formats and the steps only you can do. After that, new accounts are rows in a table.`));
      box.append(mapControls(row, "Map the sign-up"));
      return box;
    }
    const map = row.map;
    box.append(el("p", undefined, `${mapSummary(map)} · mapped ${relativeTime(map.mappedAt)}${map.appVersion ? ` on ${map.appVersion}` : ""}`));
    if (!map.complete) box.append(el("p", "cc-hint", "The mapping stopped before the account was created, so the last pages may be missing. Map it again to finish it."));
    const pages = el("ol", "ac-pages");
    for (const page of map.pages) {
      const li = el("li", "ac-page");
      const top = el("div", "ac-page-top");
      top.append(el("strong", undefined, page.title));
      if (page.check) top.append(chip(`You: ${CHECK_LABEL[page.check]}`, "warning"));
      li.append(top);
      if (page.fields.length) {
        const fields = el("div", "ac-fields");
        for (const f of page.fields) {
          const tag = el("span", f.kind === "password" ? "ac-field ac-field-vault" : "ac-field");
          tag.textContent = `${f.label} · ${KIND_LABEL[f.kind]}${f.required ? "" : " · optional"}${f.choices.length ? ` · ${f.choices.length} choices` : ""}`;
          if (f.hint) tag.title = f.hint;
          fields.append(tag);
        }
        li.append(fields);
      }
      li.append(el("span", "muted", `Then: ${page.continueLabel}`));
      pages.append(li);
    }
    box.append(pages);
    if (map.finalLabel) box.append(el("p", "cc-hint", `Creates the account with “${map.finalLabel}”. The phone always asks you first.`));
    const columns = tableColumns(map);
    const actions = el("div", "cc-actions");
    const makeTable = actionButton(map.tableId ? (showTable ? "Hide the sign-up table" : "Show the sign-up table") : "Make the sign-up table", { variant: "primary", icon: "layers" });
    makeTable.disabled = !columns.length && !map.tableId;
    makeTable.addEventListener("click", async () => {
      if (!deviceId) return;
      if (map.tableId) {
        showTable = !showTable;
        if (!showTable) closeTable();
        renderDetail();
        return;
      }
      makeTable.disabled = true;
      try {
        const table = await signupApi.table(ctx.client, deviceId, map.packageName);
        map.tableId = table.id;
        showTable = true;
        say(`Made “${table.title}”: one column per sign-up field. Add a row for each account to create next.`);
      } catch (err) {
        say((err as Error).message || "The table could not be made.", "error");
      }
      renderDetail();
    });
    const forget = actionButton("Forget this map", { variant: "ghost" });
    forget.addEventListener("click", async () => {
      if (!deviceId) return;
      try {
        await signupApi.forget(ctx.client, deviceId, map.packageName);
        maps = maps.filter((m) => m.packageName !== map.packageName);
        closeTable();
        say(`Forgot the ${map.app} sign-up map. Its table stays.`);
      } catch (err) {
        say((err as Error).message || "The phone could not forget it.", "error");
      }
      renderList();
      renderDetail();
    });
    actions.append(makeTable, forget);
    box.append(actions);
    if (columns.length) box.append(el("p", "cc-hint", `Table columns: ${columns.join(", ")}, plus Whose account, Phone, Cyclone account, Notes and Status.`));
    return box;
  }

  function mapControls(row: AccountAppRow, label: string): HTMLElement {
    const wrap = el("div", "ac-map");
    const whose = el("select", "cc-input");
    whose.setAttribute("aria-label", "Whose first account");
    for (const b of BASIS_CHOICES) {
      const option = el("option", undefined, b.label);
      option.value = b.id;
      option.selected = b.id === basis;
      whose.append(option);
    }
    whose.addEventListener("change", () => {
      basis = whose.value as OwnerBasis;
    });
    const start = actionButton(label, { variant: "primary", icon: "map" });
    start.addEventListener("click", async () => {
      if (!deviceId) return;
      start.disabled = true;
      try {
        await signupApi.map(ctx.client, { deviceId, package: row.packageName, app: row.app.label, ownerBasis: basis });
        say(`Started mapping the ${row.app.label} sign-up on the phone. It will ask you for the details.`);
        await load(false);
      } catch (err) {
        say((err as Error).message || "The mapping task could not start.", "error");
        start.disabled = false;
      }
    });
    const field = el("label", "cc-field");
    field.append(el("span", "cc-field-label", "Whose account is the first one"), whose);
    wrap.append(field, start, el("p", "cc-hint", "Only accounts you own or manage. Cyclone never solves a verification: codes, CAPTCHAs and ID checks come to you."));
    return wrap;
  }

  function tableHost(tableId: string): HTMLElement {
    const host = el("div", "ac-table");
    tableBlock ??= createTableBlock(ctx, { id: "b_signup", type: "table", tableId, viewId: null }, () => undefined);
    let panel = panels.get(tableId);
    if (!panel) {
      panel = createAccountsPanel(ctx, tableId, say);
      panels.set(tableId, panel);
    }
    host.append(panel.element, tableBlock.element);
    return host;
  }

  function closeTable(): void {
    showTable = false;
    tableBlock?.destroy();
    tableBlock = null;
  }

  renderPhones();
  void load(true);
  const timer = setInterval(() => {
    if (tasks.some((t) => t.recipe?.startsWith(RECIPE))) void load(false);
  }, POLL_MS);

  return {
    element,
    refresh() {
      if (state !== "ready" || showTable) return;
      renderList();
      renderDetail();
    },
    update(next) {
      devices = next;
      if (!deviceId || !devices.some((d) => d.id === deviceId)) {
        deviceId = devices[0]?.id ?? null;
        open = null;
        closeTable();
        void load(true);
        return;
      }
      renderPhones();
    },
    destroy() {
      destroyed = true;
      clearInterval(timer);
      closeTable();
      profileBar.destroy();
      for (const panel of panels.values()) panel.destroy();
    },
  };
}
