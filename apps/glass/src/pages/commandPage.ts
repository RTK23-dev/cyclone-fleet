/**
 * Command Center (plan 33, C0): one place for the owner's accounts, tasks, routines, results and approvals across
 * every connected phone. Glass renders and commands; the gateway assigns tasks, and phones run them as ordinary Mind
 * missions with their own GATE. Approvals are answered here only for the exact request the phone showed.
 *
 * No secret is typed on this page: accounts are metadata, and the phone asks for passwords and codes itself.
 */
import type { GlassContext } from "../app.js";
import type { CommandTab } from "../core/router.js";
import {
  WEEKDAYS,
  basisLabel,
  command,
  fillRecipe,
  looksSecret,
  taskStatusLabel,
  taskStatusTone,
  twofaLabel,
  type CcAccount,
  type CcApproval,
  type CcOverview,
  type CcResult,
  type CcRoutine,
  type CcTask,
  type Plan,
  type Schedule,
} from "../services/command.js";
import { getMarket, type MarketListing } from "../services/market.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, pageHeader, segmented, statTile } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";
import type { GlassPage } from "./page.js";
import { createVaultView, type VaultView } from "./vaultView.js";
import { createAccountsView, type AccountsView } from "./accountsView.js";
import { vaultApi } from "../services/vault.js";
import { checkGoalRefs, createConnectionsView, createMakeEditor, type ConnectionsView } from "./connectionsView.js";
import { createNumbersView, type NumbersView } from "./numbersView.js";
import { TASK_GROUPS, renderRows, taskRow } from "../workspace/views.js";
import { createFleetView, type FleetView } from "./fleetView.js";

const POLL_MS = 5_000;
const TABS: Array<{ id: CommandTab; label: string }> = [
  { id: "approvals", label: "Approvals" },
  { id: "tasks", label: "Tasks" },
  { id: "routines", label: "Routines" },
  { id: "results", label: "Results" },
  { id: "accounts", label: "Accounts" },
  { id: "numbers", label: "Numbers" },
  { id: "vault", label: "Vault" },
  { id: "connections", label: "Connections" },
  { id: "fleet", label: "Multi-phone" },
];

interface Data {
  overview: CcOverview | null;
  accounts: CcAccount[];
  tasks: CcTask[];
  routines: CcRoutine[];
  results: CcResult[];
  approvals: CcApproval[];
}

/** Titles for the workspace (plan 33, C5): each database is its own clean screen, reached from the sidebar. */
const INFO: Record<CommandTab, [string, string, string | null]> = {
  approvals: ["Inbox", "What your phones and connections are waiting for you to decide.", null],
  tasks: ["Tasks", "Work a phone does once: now, at a time, or after connection steps.", "New task"],
  routines: ["Routines", "Tasks on a schedule. A missed time is skipped, never replayed.", "New routine"],
  results: ["Results", "Every run, what happened and why.", null],
  accounts: ["Accounts", "Your phones, their apps and the accounts in each. Map an app's sign-up once; new accounts become table rows.", "New account"],
  numbers: ["Numbers", "Every number Cyclone can receive codes on: your phones' SIMs, forwarded and rented numbers, and the account each one is for.", null],
  vault: ["Vault", "Passwords sealed in your browser. This PC keeps only ciphertext.", null],
  connections: ["Connections", "MCP servers, APIs and programs Cyclone may call for your tasks.", null],
  fleet: ["Multi-phone", "One sentence, several phones. Each phone does its own part. You approve what matters.", null],
};

export function createCommandPage(ctx: GlassContext, tab: CommandTab, options: { workspace?: boolean } = {}): GlassPage {
  const workspace = options.workspace === true;
  const element = el("div", workspace ? "page page-command ws-db" : "page page-command");
  const stats = el("div", "cc-stats");
  const notice = el("p", "cc-notice");
  const form = el("div", "cc-form-area");
  const body = el("div", "cc-body");
  const data: Data = { overview: null, accounts: [], tasks: [], routines: [], results: [], approvals: [] };
  let destroyed = false;
  let loaded = false;
  let error: Error | null = null;
  let devices = ctx.devices;
  let fleetView: FleetView | null = null;

  const tabs = segmented<CommandTab>(TABS, tab, (id) => ctx.navigate({ name: "command", tab: id }));
  let taskLayout: "table" | "board" | "calendar" = "table";
  let month = new Date();
  if (workspace) {
    const [title, subtitle, newLabel] = INFO[tab];
    const actions: HTMLElement[] = [];
    if (newLabel) {
      const toggle = actionButton(newLabel, { variant: "primary" });
      form.hidden = true;
      toggle.addEventListener("click", () => {
        form.hidden = !form.hidden;
        toggle.classList.toggle("active", !form.hidden);
      });
      actions.push(toggle);
    }
    element.append(pageHeader(title, subtitle, actions), notice, form);
    if (tab === "tasks") {
      const layouts = segmented<"table" | "board" | "calendar">([{ id: "table", label: "Table" }, { id: "board", label: "Board" }, { id: "calendar", label: "Calendar" }], taskLayout, (id) => {
        taskLayout = id;
        layouts.set(id);
        renderBody();
      });
      layouts.element.classList.add("ws-db-layouts");
      element.append(layouts.element);
    }
    element.append(body);
  } else {
    element.append(
      pageHeader("Command Center", "Your accounts, tasks and routines on every phone. Phones do the work; you approve what matters."),
      stats,
      tabs.element,
      notice,
      form,
      body,
    );
  }

  const say = (text: string, tone: "ok" | "error" = "ok") => {
    notice.textContent = text;
    notice.className = `cc-notice cc-notice-${tone}`;
  };

  const deviceName = (id: string | null): string => {
    if (!id) return "Any ready phone";
    return devices.find((d) => d.id === id)?.name ?? id;
  };
  const accountName = (id: string | null): string => {
    if (!id) return "—";
    const account = data.accounts.find((a) => a.id === id);
    return account ? `${account.handle} · ${account.service}` : "Removed account";
  };

  async function act(label: string, action: () => Promise<unknown>): Promise<boolean> {
    say(`${label}…`);
    try {
      await action();
      say(`${label}: done.`);
      await load();
      return true;
    } catch (err) {
      say((err as Error).message || `${label} failed.`, "error");
      return false;
    }
  }

  async function load(): Promise<void> {
    try {
      const [overview, accounts, approvals, main] = await Promise.all([
        command.overview(ctx.client),
        command.accounts(ctx.client),
        command.approvals(ctx.client),
        tab === "tasks" ? command.tasks(ctx.client) : tab === "routines" ? command.routines(ctx.client) : tab === "results" ? command.results(ctx.client) : Promise.resolve(null),
      ]);
      data.overview = overview;
      data.accounts = accounts;
      data.approvals = approvals;
      if (tab === "tasks") data.tasks = main as CcTask[];
      if (tab === "routines") data.routines = main as CcRoutine[];
      if (tab === "results") data.results = main as CcResult[];
      error = null;
    } catch (err) {
      error = err as Error;
    }
    const firstLoad = !loaded;
    loaded = true;
    if (destroyed) return;
    renderStats();
    if (firstLoad) renderForm();
    renderBody();
  }

  function renderStats(): void {
    const o = data.overview;
    tabs.set(tab, { approvals: data.approvals.length });
    if (!o) {
      stats.replaceChildren();
      return;
    }
    setChildren(
      stats,
      statTile("Needs you", String(o.approvals), o.approvals ? "warning" : "neutral"),
      statTile("Running", String(o.running), o.running ? "accent" : "neutral"),
      statTile("Open tasks", String(o.openTasks)),
      statTile("Routines", o.routinesPaused ? `${o.routines} (${o.routinesPaused} paused)` : String(o.routines)),
      statTile("Done today", String(o.succeeded24h), "success"),
      statTile("Failed today", String(o.failed24h), o.failed24h ? "danger" : "neutral"),
    );
  }

  let vaultView: VaultView | null = null;
  let connectionsView: ConnectionsView | null = null;
  let numbersView: NumbersView | null = null;
  let accountsBrowser: AccountsView | null = null;
  const accountsHost = el("div", "ac-all");

  function renderBody(): void {
    // The vault manages itself (its own loading, locking and forms); polling never re-renders it.
    if (tab === "vault") {
      vaultView ??= createVaultView(ctx, () => data.accounts, say);
      if (body.firstChild !== vaultView.element) setChildren(body, vaultView.element);
      return;
    }
    if (tab === "numbers") {
      numbersView ??= createNumbersView(ctx, say);
      if (body.firstChild !== numbersView.element) setChildren(body, numbersView.element);
      return;
    }
    if (tab === "connections") {
      connectionsView ??= createConnectionsView(ctx, say);
      if (body.firstChild !== connectionsView.element) setChildren(body, connectionsView.element);
      return;
    }
    if (tab === "fleet") {
      fleetView ??= createFleetView({ client: ctx.client, navigate: (route) => ctx.navigate(route) });
      if (body.firstChild !== fleetView.element) setChildren(body, fleetView.element);
      return;
    }
    // Plan 43 (T5 + T6): phone → apps → accounts manages itself; the poll only redraws the list of all accounts.
    if (tab === "accounts") {
      accountsBrowser ??= createAccountsView(ctx, () => data.accounts, say);
      if (body.firstChild !== accountsBrowser.element) setChildren(body, accountsBrowser.element, accountsHost);
      if (!loaded) setChildren(accountsHost, loadingState("Loading accounts…"));
      else if (error) setChildren(accountsHost, errorState("The Command Center is not reachable", error, () => void load()));
      else {
        setChildren(accountsHost, el("h2", "ac-all-title", "All accounts"), accountsView());
        accountsBrowser.refresh();
      }
      return;
    }
    if (!loaded) {
      setChildren(body, loadingState("Loading the Command Center…"));
      return;
    }
    if (error) {
      setChildren(body, errorState("The Command Center is not reachable", error, () => void load()));
      return;
    }
    if (tab === "approvals") setChildren(body, approvalsView());
    else if (tab === "tasks") setChildren(body, tasksView());
    else if (tab === "routines") setChildren(body, routinesView());
    else if (tab === "results") setChildren(body, resultsView());
    else setChildren(body, accountsView());
  }

  // Forms are drawn once, so polling never wipes what the owner is typing.
  function renderForm(): void {
    if (tab === "tasks") setChildren(form, taskForm());
    else if (tab === "routines") setChildren(form, routineForm());
    else if (tab === "accounts") setChildren(form, accountForm());
    else form.replaceChildren();
  }

  // ------------------------------------------------------------------ approvals

  function approvalsView(): HTMLElement {
    if (!data.approvals.length) {
      return emptyState({ icon: "bell", title: "Nothing needs you", body: "When a phone asks a question or wants your approval, it shows up here and on the phone." });
    }
    const wrap = el("div", "cc-approvals");
    for (const approval of data.approvals) wrap.append(approvalCard(approval));
    return wrap;
  }

  function approvalCard(a: CcApproval): HTMLElement {
    const box = card("cc-approval");
    const head = el("div", "cc-row");
    head.append(
      chip({ approval: "Approve?", question: "Question", values: "Details", secret: "Secure input", handover: "Your turn", spend: "Use a connection?", login: "Unlock the vault" }[a.kind],
        a.kind === "approval" || a.kind === "spend" ? "warning" : "accent"),
      el("strong", undefined, a.title),
      el("span", "muted", `${a.kind === "spend" ? "This PC" : deviceName(a.deviceId || null)} · ${relativeTime(a.createdAt)}`),
    );
    box.append(head, el("p", "cc-approval-text", a.text));
    if (a.spend) {
      const spend = el("dl", "cc-send");
      spend.append(el("dt", undefined, "Connection"), el("dd", undefined, a.spend.connection), el("dt", undefined, "Tool"), el("dd", undefined, a.spend.tool));
      for (const [key, value] of Object.entries(a.spend.arguments)) spend.append(el("dt", undefined, key), el("dd", "cc-send-text", String(value)));
      box.append(spend);
    }
    if (a.kind === "login") {
      const open = actionButton("Open the vault", { variant: "primary", icon: "lock" });
      open.addEventListener("click", () => ctx.navigate({ name: "command", tab: "vault" }));
      const row = el("div", "cc-actions");
      row.append(open);
      box.append(el("p", "cc-hint", "The run waits. When the vault is unlocked in Glass, it seals the password for this run only, and this clears itself."), row);
      return box;
    }
    if (a.send) {
      const send = el("dl", "cc-send");
      send.append(el("dt", undefined, "Message"), el("dd", "cc-send-text", a.send.text), el("dt", undefined, "To"), el("dd", undefined, a.send.recipient), el("dt", undefined, "In"), el("dd", undefined, a.send.app));
      box.append(send);
    }
    const actions = el("div", "cc-actions");
    const answer = (label: string, body: Parameters<typeof command.answer>[2]) =>
      act(label, async () => {
        const result = await command.answer(ctx.client, a.id, body);
        if (!result?.handled) throw new Error(result?.detail || "The phone did not take that answer.");
      });
    if (!a.answerHere) {
      box.append(el("p", "cc-hint", a.kind === "secret" ? "Type this on the phone. Secrets never go through the PC." : "Take the phone to continue."));
      return box;
    }
    if (a.kind === "spend") {
      const approve = actionButton("Approve the call", { variant: "primary" });
      approve.addEventListener("click", () => void answer("Approving", { action: "approve" }));
      const decline = actionButton("Decline", { variant: "ghost" });
      decline.addEventListener("click", () => void answer("Declining", { action: "decline" }));
      actions.append(approve, decline);
    } else if (a.kind === "approval") {
      if (a.approvableHere) {
        const approve = actionButton(a.gate === "send" ? "Approve and send" : "Approve", { variant: "primary" });
        approve.addEventListener("click", () => void answer("Approving", { action: "approve" }));
        actions.append(approve);
      } else {
        box.append(el("p", "cc-hint", "Approve this one on the phone: part of it is hidden here."));
      }
      const decline = actionButton("Decline", { variant: "ghost" });
      decline.addEventListener("click", () => void answer("Declining", { action: "decline" }));
      actions.append(decline);
    } else if (a.kind === "question") {
      for (const choice of a.choices) {
        const button = actionButton(choice);
        button.addEventListener("click", () => void answer("Answering", { action: "reply", text: choice }));
        actions.append(button);
      }
      const input = el("input", "cc-input");
      input.placeholder = "Your answer";
      input.setAttribute("aria-label", "Your answer");
      const send = actionButton("Send", { variant: "primary" });
      send.addEventListener("click", () => {
        const text = String(input.value ?? "").trim();
        if (!text) return say("Type an answer first.", "error");
        if (looksSecret(text)) return say("That looks like a secret. Type secrets on the phone.", "error");
        void answer("Answering", { action: "reply", text });
      });
      const skip = actionButton("Not now", { variant: "ghost" });
      skip.addEventListener("click", () => void answer("Skipping", { action: "decline" }));
      actions.append(input, send, skip);
    } else if (a.kind === "values") {
      const inputs = a.fields.map((field) => {
        const input = el("input", "cc-input");
        input.placeholder = field.label;
        input.setAttribute("aria-label", field.label);
        return [field.label, input] as const;
      });
      const send = actionButton("Send details", { variant: "primary" });
      send.addEventListener("click", () => {
        const values: Record<string, string> = {};
        for (const [label, input] of inputs) {
          const value = String(input.value ?? "").trim();
          if (value) values[label] = value;
        }
        if (!Object.keys(values).length) return say("Fill in at least one detail.", "error");
        if (Object.entries(values).some(([k, v]) => looksSecret(`${k}: ${v}`))) return say("Secrets are typed on the phone.", "error");
        void answer("Sending details", { action: "fill", values });
      });
      const skip = actionButton("Not now", { variant: "ghost" });
      skip.addEventListener("click", () => void answer("Skipping", { action: "decline" }));
      actions.append(...inputs.map(([, input]) => input), send, skip);
    }
    box.append(actions);
    return box;
  }

  // ------------------------------------------------------------------ tasks

  function phoneSelect(label: string, allowAny = true): HTMLSelectElement {
    const select = el("select", "cc-input");
    select.setAttribute("aria-label", label);
    if (allowAny) select.append(option("", "Any ready phone"));
    for (const device of devices) select.append(option(device.id, device.name));
    return select;
  }

  function accountSelect(): HTMLSelectElement {
    const select = el("select", "cc-input");
    select.setAttribute("aria-label", "Account");
    select.append(option("", "No account"));
    for (const account of data.accounts) select.append(option(account.id, `${account.handle} · ${account.service}`));
    return select;
  }

  function taskForm(): HTMLElement {
    const box = card("cc-card");
    box.append(el("h2", "card-title", "New task"));
    const title = field("Title", el("input", "cc-input"));
    const goal = el("textarea", "cc-input cc-goal");
    goal.placeholder = "What should the phone do? For example: Post today's product photo to @mybrand with the caption from Notes.";
    const phone = phoneSelect("Phone");
    const account = accountSelect();
    // C2: sign in with a vault login of this account. Only ids and kinds are read here; the password stays sealed.
    const vaultPick = el("select", "cc-input");
    vaultPick.setAttribute("aria-label", "Sign in with");
    vaultPick.append(option("", "Nothing from the vault"));
    const refreshVaultPick = async () => {
      vaultPick.replaceChildren(option("", "Nothing from the vault"));
      if (!account.value) return;
      try {
        const vault = await vaultApi.get(ctx.client);
        for (const item of vault.items.filter((i) => i.accountId === account.value && (i.kind === "login" || i.kind === "totp"))) {
          vaultPick.append(option(item.id, `${item.kind === "login" ? "Vault login" : "Vault authenticator"}, saved ${relativeTime(item.updatedAt ?? 0)}`));
        }
      } catch {
        /* no vault yet */
      }
    };
    account.addEventListener("change", () => void refreshVaultPick());
    const at = el("input", "cc-input");
    at.type = "datetime-local";
    at.setAttribute("aria-label", "Start at");
    const recipes = el("select", "cc-input");
    recipes.setAttribute("aria-label", "Recipe");
    recipes.append(option("", "Write my own"));
    let listings: MarketListing[] = [];
    const loadRecipes = actionButton("Load recipes", { icon: "store", variant: "ghost" });
    loadRecipes.addEventListener("click", async () => {
      const source = String(phone.value || ctx.device?.id || devices[0]?.id || "");
      if (!source) return say("Connect a phone to load its recipes.", "error");
      try {
        listings = (await getMarket(ctx.client, source)).listings.filter((l) => l.kind === "recipe");
        recipes.replaceChildren(option("", "Write my own"), ...listings.map((l) => option(l.id, l.name)));
        say(listings.length ? `${listings.length} recipes from ${deviceName(source)}.` : "That phone has no recipes yet.");
      } catch (err) {
        say((err as Error).message, "error");
      }
    });
    recipes.addEventListener("change", () => {
      const listing = listings.find((l) => l.id === recipes.value);
      if (!listing) return;
      const inputs: Record<string, string> = {};
      for (const input of listing.inputs) inputs[input.name] = listing.savedInputs?.[input.name] ?? input.default;
      goal.value = fillRecipe(listing.goal, inputs);
      if (!String(title.input.value ?? "").trim()) title.input.value = listing.name;
    });
    const maker = createMakeEditor(ctx);
    void maker.refresh();
    const create = actionButton("Create task", { variant: "primary", icon: "play" });
    create.addEventListener("click", () => {
      const text = String(goal.value ?? "").trim();
      let plan;
      try {
        plan = maker.read();
        checkGoalRefs(text, plan);
      } catch (err) {
        return say((err as Error).message, "error");
      }
      const then = maker.then();
      if (!text && then !== "keep") return say("Write what the phone should do.", "error");
      if (looksSecret(text)) return say("Leave passwords and codes out. The phone asks for them.", "error");
      const body: Record<string, unknown> = { requestId: requestId() };
      if (text) body.goal = text;
      if (plan) Object.assign(body, plan);
      if (plan && then === "keep" && !String(title.input.value ?? "").trim()) body.title = planTitle(plan);
      const name = String(title.input.value ?? "").trim();
      if (name) body.title = name;
      if (phone.value) body.deviceId = phone.value;
      if (account.value) body.accountId = account.value;
      if (vaultPick.value) {
        if (!phone.value) return say("A task that signs in with the vault needs one chosen phone.", "error");
        body.vaultItemId = vaultPick.value;
      }
      if (recipes.value) body.recipe = recipes.value;
      if (at.value) {
        const due = new Date(String(at.value)).getTime();
        if (Number.isFinite(due)) body.dueAt = due;
      }
      void act("Creating the task", () => command.createTask(ctx.client, body)).then((ok) => {
        if (ok) {
          goal.value = "";
          title.input.value = "";
        }
      });
    });
    const grid = el("div", "cc-grid");
    grid.append(title.wrap, labelled("Phone", phone), labelled("Account", account), labelled("Sign in with", vaultPick), labelled("Start at (empty = now)", at), labelled("Recipe", recipes));
    const actions = el("div", "cc-actions");
    actions.append(create, loadRecipes);
    box.append(grid, maker.element, labelled("Goal", goal), actions);
    return box;
  }

  function tasksView(): HTMLElement {
    if (!data.tasks.length) return emptyState({ icon: "runs", title: "No tasks yet", body: workspace ? "Press New task, or send a card from a page's plan to a phone." : "Create a task above. It runs on the phone you pick, or on any ready phone." });
    if (workspace && taskLayout !== "table") {
      return renderRows(data.tasks.map((t) => taskRow({ ...ctx, devices }, t)), taskLayout, TASK_GROUPS, () => month, (m) => {
        month = m;
        renderBody();
      });
    }
    const table = el("table", "cc-table");
    table.append(headRow("Task", "Phone", "Account", "Status", "Detail", ""));
    for (const t of data.tasks) {
      const row = el("tr");
      const open = ["scheduled", "making", "waiting_device", "running", "needs_you"].includes(t.status);
      const due = t.dueAt && t.status === "scheduled" ? `Starts ${new Date(t.dueAt).toLocaleString()}` : "";
      const detail = open ? t.cause || due || t.run?.summary || "" : t.run?.summary || t.cause;
      const cancel = el("td");
      if (open) {
        const button = actionButton("Cancel", { variant: "ghost" });
        button.addEventListener("click", () => void act("Cancelling", () => command.cancelTask(ctx.client, t.id)));
        cancel.append(button);
      }
      row.append(
        cell(t.title, [t.routineId ? "From a routine" : t.recipe ? `Recipe ${t.recipe}` : "", t.vaultItemId ? `Signs in with the vault${t.leases[0] ? ` · password ${t.leases[0].state}` : ""}` : "",
          t.make ? planDetail(t) : ""].filter(Boolean).join(" · ") || undefined),
        cell(deviceName(t.run?.deviceId ?? t.deviceId)),
        cell(accountName(t.accountId)),
        chipCell(taskStatusLabel(t.status), taskStatusTone(t.status)),
        cell(detail),
        cancel,
      );
      table.append(row);
    }
    return table;
  }

  // ------------------------------------------------------------------ routines

  function routineForm(): HTMLElement {
    const box = card("cc-card");
    box.append(el("h2", "card-title", "New routine"));
    const title = field("Title", el("input", "cc-input"));
    const goal = el("textarea", "cc-input cc-goal");
    goal.placeholder = "What should happen each time? For example: Check the shop inbox and answer delivery questions.";
    const account = accountSelect();
    const phones = el("div", "cc-checks");
    const boxes = devices.map((device) => {
      const input = el("input");
      input.type = "checkbox";
      input.value = device.id;
      const label = el("label", "cc-check");
      label.append(input, el("span", undefined, device.name));
      phones.append(label);
      return input;
    });
    if (!devices.length) phones.append(el("span", "muted", "No phone connected: the routine runs on any ready phone."));
    let kind: Schedule["kind"] = "daily";
    const when = el("div", "cc-when");
    const time = el("input", "cc-input");
    time.type = "time";
    time.value = "09:00";
    time.setAttribute("aria-label", "Time");
    const days = WEEKDAYS.map((name, index) => {
      const input = el("input");
      input.type = "checkbox";
      input.checked = index < 5;
      input.value = String(index + 1);
      const label = el("label", "cc-check");
      label.append(input, el("span", undefined, name));
      return { input, label };
    });
    const minutes = el("input", "cc-input");
    minutes.type = "number";
    minutes.min = "15";
    minutes.value = "60";
    minutes.setAttribute("aria-label", "Minutes");
    const drawWhen = () => {
      const dayRow = el("div", "cc-checks");
      dayRow.append(...days.map((d) => d.label));
      if (kind === "daily") setChildren(when, labelled("Time", time), dayRow);
      else setChildren(when, labelled("Every (minutes, 15 or more)", minutes));
    };
    const kinds = segmented<Schedule["kind"]>([{ id: "daily", label: "At a time" }, { id: "every", label: "Every few minutes" }], kind, (id) => {
      kind = id;
      kinds.set(id);
      drawWhen();
    });
    drawWhen();
    // C3 milestone: sign in with a vault login, and prepare the next runs' passwords ahead so they run while Glass is closed.
    const vaultPick = el("select", "cc-input");
    vaultPick.setAttribute("aria-label", "Sign in with");
    vaultPick.append(option("", "Nothing from the vault"));
    account.addEventListener("change", async () => {
      vaultPick.replaceChildren(option("", "Nothing from the vault"));
      if (!account.value) return;
      try {
        const vault = await vaultApi.get(ctx.client);
        for (const item of vault.items.filter((i) => i.accountId === account.value && (i.kind === "login" || i.kind === "totp"))) {
          vaultPick.append(option(item.id, item.kind === "login" ? "Vault login" : "Vault authenticator"));
        }
      } catch {
        /* no vault yet */
      }
    });
    const ahead = el("select", "cc-input");
    ahead.setAttribute("aria-label", "Prepare ahead");
    for (const [n, label] of [[0, "Only while Glass is open"], [1, "The next run"], [3, "The next 3 runs"], [7, "The next 7 runs"]] as const) ahead.append(option(String(n), label));
    const maker = createMakeEditor(ctx);
    void maker.refresh();
    const create = actionButton("Create routine", { variant: "primary", icon: "clock" });
    create.addEventListener("click", () => {
      const text = String(goal.value ?? "").trim();
      const name = String(title.input.value ?? "").trim();
      let plan;
      try {
        plan = maker.read();
        checkGoalRefs(text, plan);
      } catch (err) {
        return say((err as Error).message, "error");
      }
      if (!name || (!text && maker.then() !== "keep")) return say("A routine needs a title and a goal.", "error");
      if (looksSecret(text)) return say("Leave passwords and codes out. The phone asks for them.", "error");
      const schedule: Schedule = kind === "daily"
        ? { kind: "daily", time: String(time.value || "09:00"), days: days.filter((d) => d.input.checked).map((d) => Number(d.input.value)) }
        : { kind: "every", minutes: Math.round(Number(minutes.value)) };
      if (schedule.kind === "daily" && !schedule.days.length) return say("Pick at least one day.", "error");
      const body: Record<string, unknown> = {
        title: name, goal: text || (plan ? planTitle(plan) : ""), schedule, deviceIds: boxes.filter((b) => b.checked).map((b) => String(b.value)),
      };
      if (account.value) body.accountId = account.value;
      if (plan) Object.assign(body, plan);
      if (vaultPick.value) {
        if ((body.deviceIds as string[]).length !== 1) return say("A routine that signs in with the vault runs on one chosen phone.", "error");
        body.vaultItemId = vaultPick.value;
        body.preauth = Number(ahead.value);
      }
      void act("Creating the routine", () => command.saveRoutine(ctx.client, body)).then((ok) => {
        if (ok) {
          goal.value = "";
          title.input.value = "";
        }
      });
    });
    const grid = el("div", "cc-grid");
    grid.append(title.wrap, labelled("Account", account), labelled("Sign in with", vaultPick), labelled("Seal the password ahead for", ahead));
    const actions = el("div", "cc-actions");
    actions.append(create);
    box.append(grid, el("p", "cc-hint", "Sealed ahead, each run's password opens only on its phone, for that run, until 30 minutes after it is due. The vault must be unlocked in Glass once to seal them."),
      maker.element, labelled("Goal", goal), labelled("Phones (one task each; none = any ready phone)", phones), labelled("When", group(kinds.element, when)), actions);
    return box;
  }

  function routinesView(): HTMLElement {
    const wrap = el("div");
    const allPaused = data.routines.length > 0 && data.routines.every((r) => r.paused);
    const toggleAll = actionButton(allPaused ? "Resume all" : "Pause all", { variant: "secondary", icon: allPaused ? "play" : "pause" });
    toggleAll.addEventListener("click", () => void act(allPaused ? "Resuming all" : "Pausing all", () => command.pauseAll(ctx.client, !allPaused)));
    if (!data.routines.length) {
      return emptyState({ icon: "clock", title: "No routines yet", body: "A routine creates a task on a schedule. A missed time is skipped, never replayed." });
    }
    const bar = el("div", "cc-actions");
    bar.append(toggleAll);
    const table = el("table", "cc-table");
    table.append(headRow("Routine", "When", "Phones", "Account", "Next", "Runs", ""));
    for (const r of data.routines) {
      const actions = el("td", "cc-row");
      const run = actionButton("Run now", { variant: "ghost", icon: "play" });
      run.addEventListener("click", () => void act("Starting", () => command.runRoutine(ctx.client, r.id)));
      const pause = actionButton(r.paused ? "Resume" : "Pause", { variant: "ghost" });
      pause.addEventListener("click", () => void act(r.paused ? "Resuming" : "Pausing", () => command.saveRoutine(ctx.client, { paused: !r.paused }, r.id)));
      const remove = actionButton("Delete", { variant: "ghost" });
      remove.addEventListener("click", () => void act("Deleting", () => command.deleteRoutine(ctx.client, r.id)));
      actions.append(run, pause, remove);
      const row = el("tr");
      row.append(
        cell(r.title, [r.goal.slice(0, 120), r.make ? (r.make.steps.length > 1 ? `${r.make.steps.length} steps: ${r.make.steps.map((s) => s.tool).join(" → ")}` : `Makes with ${r.make.tool}`) : "",
          r.vaultItemId ? (r.preauth ? `${r.prepared.filter((p) => p.ready).length} of ${r.prepared.length} runs sealed ahead` : "Signs in while Glass is open") : ""].filter(Boolean).join(" · ")),
        cell(r.scheduleLabel),
        cell(r.deviceIds.length ? r.deviceIds.map((d) => deviceName(d)).join(", ") : "Any ready phone"),
        cell(accountName(r.accountId)),
        r.paused ? chipCell("Paused", "neutral") : cell(r.nextRunAt ? new Date(r.nextRunAt).toLocaleString() : "—"),
        cell(`${r.succeeded} done · ${r.failed} failed`),
        actions,
      );
      table.append(row);
    }
    wrap.append(bar, table);
    return wrap;
  }

  // ------------------------------------------------------------------ results

  function resultsView(): HTMLElement {
    if (!data.results.length) return emptyState({ icon: "runs", title: "No results yet", body: "Every task a phone runs is listed here with how it ended." });
    const table = el("table", "cc-table");
    table.append(headRow("When", "Task", "Phone", "Outcome", "What happened", "Steps", "Time", "Cost"));
    for (const r of data.results) {
      const row = el("tr");
      row.append(
        cell(relativeTime(r.startedAt)),
        cell(r.title),
        cell(deviceName(r.deviceId)),
        chipCell({ running: "Running", succeeded: "Done", failed: "Failed", cancelled: "Cancelled" }[r.status], taskStatusTone(r.status)),
        cell(r.summary || r.cause),
        cell(String(r.turns)),
        cell(r.endedAt ? `${Math.max(1, Math.round((r.endedAt - r.startedAt) / 1000))} s` : "…"),
        cell(r.costUsd ? `$${r.costUsd.toFixed(3)}` : "—"),
      );
      table.append(row);
    }
    return table;
  }

  // ------------------------------------------------------------------ accounts

  function accountForm(): HTMLElement {
    const box = card("cc-card");
    box.append(
      el("h2", "card-title", "Add an account you own"),
      el("p", "cc-hint", "Only details here, never a password. Keep the password in the Vault tab, encrypted, linked to this account."),
    );
    const service = field("App package or website", el("input", "cc-input"));
    service.input.placeholder = "com.instagram.android or example.com";
    const handle = field("Account name or address", el("input", "cc-input"));
    const basis = el("select", "cc-input");
    basis.setAttribute("aria-label", "Whose account");
    for (const b of ["mine", "company", "client"] as const) basis.append(option(b, basisLabel(b)));
    const twofa = el("select", "cc-input");
    twofa.setAttribute("aria-label", "Two-step sign-in");
    for (const t of ["none", "totp", "passkey", "sms", "email", "app"] as const) twofa.append(option(t, twofaLabel(t)));
    const notes = field("Notes (optional, no secrets)", el("input", "cc-input"));
    const phones = el("div", "cc-checks");
    const boxes = devices.map((device) => {
      const input = el("input");
      input.type = "checkbox";
      input.value = device.id;
      const label = el("label", "cc-check");
      label.append(input, el("span", undefined, device.name));
      phones.append(label);
      return input;
    });
    const add = actionButton("Add account", { variant: "primary", icon: "user" });
    add.addEventListener("click", () => {
      const body = {
        service: String(service.input.value ?? "").trim(),
        handle: String(handle.input.value ?? "").trim(),
        ownerBasis: String(basis.value || "mine"),
        twofa: String(twofa.value || "none"),
        notes: String(notes.input.value ?? "").trim(),
        allowedDevices: boxes.filter((b) => b.checked).map((b) => String(b.value)),
      };
      if (!body.service || !body.handle) return say("An account needs its app or website and its name.", "error");
      if (looksSecret(body.notes)) return say("Notes may not hold secrets.", "error");
      void act("Adding the account", () => command.saveAccount(ctx.client, body)).then((ok) => {
        if (ok) {
          service.input.value = "";
          handle.input.value = "";
          notes.input.value = "";
        }
      });
    });
    const grid = el("div", "cc-grid");
    grid.append(service.wrap, handle.wrap, labelled("Whose account", basis), labelled("Two-step sign-in", twofa), notes.wrap);
    const actions = el("div", "cc-actions");
    actions.append(add);
    box.append(grid, labelled("Phones that may use it (none = any)", phones), actions);
    return box;
  }

  function accountsView(): HTMLElement {
    if (!data.accounts.length) return emptyState({ icon: "user", title: "No accounts yet", body: "Add the accounts you own so tasks can say which one to use. One phone uses an account at a time." });
    const table = el("table", "cc-table");
    table.append(headRow("Account", "Service", "Whose", "Two-step", "Phones", "Vault", "State", ""));
    for (const a of data.accounts) {
      const actions = el("td", "cc-row");
      const pause = actionButton(a.status === "paused" ? "Resume" : "Pause", { variant: "ghost" });
      pause.addEventListener("click", () => void act("Saving", () => command.saveAccount(ctx.client, { status: a.status === "paused" ? "active" : "paused" }, a.id)));
      const remove = actionButton("Remove", { variant: "ghost" });
      remove.addEventListener("click", () => void act("Removing", () => command.deleteAccount(ctx.client, a.id)));
      actions.append(pause, remove);
      const row = el("tr");
      row.append(
        cell(a.handle, a.notes || undefined),
        cell(a.service),
        cell(basisLabel(a.ownerBasis)),
        cell(twofaLabel(a.twofa)),
        cell(a.allowedDevices.length ? a.allowedDevices.map((d) => deviceName(d)).join(", ") : "Any"),
        cell(a.vaultItems ? `${a.vaultItems} encrypted` : "—"),
        a.locked ? chipCell("In use", "accent") : a.status === "paused" ? chipCell("Paused", "neutral") : chipCell(a.lastOutcome === "failed" ? "Last task failed" : "Ready", a.lastOutcome === "failed" ? "warning" : "success"),
        actions,
      );
      table.append(row);
    }
    return table;
  }

  // ------------------------------------------------------------------ lifecycle

  renderBody();
  void load();
  const timer = setInterval(() => void load(), POLL_MS);

  return {
    element,
    destroy() {
      vaultView?.destroy();
      connectionsView?.destroy();
      fleetView?.destroy();
      numbersView?.destroy();
      accountsBrowser?.destroy();
      destroyed = true;
      clearInterval(timer);
    },
    update(next) {
      devices = next.devices;
      accountsBrowser?.update(next.devices);
    },
  };
}

function option(value: string, label: string): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  return node;
}

function labelled(label: string, control: HTMLElement): HTMLElement {
  const wrap = el("label", "cc-field");
  wrap.append(el("span", "cc-field-label", label), control);
  return wrap;
}

function field<T extends HTMLInputElement>(label: string, input: T): { wrap: HTMLElement; input: T } {
  input.setAttribute("aria-label", label);
  return { wrap: labelled(label, input), input };
}

function group(...children: HTMLElement[]): HTMLElement {
  const wrap = el("div", "cc-group");
  wrap.append(...children);
  return wrap;
}

function headRow(...labels: string[]): HTMLElement {
  const row = el("tr");
  for (const label of labels) row.append(el("th", undefined, label));
  return row;
}

function cell(text: string, sub?: string): HTMLElement {
  const td = el("td");
  td.append(el("span", undefined, text));
  if (sub) td.append(el("span", "cc-sub", sub));
  return td;
}

function chipCell(label: string, tone: "neutral" | "accent" | "success" | "warning" | "danger"): HTMLElement {
  const td = el("td");
  td.append(chip(label, tone));
  return td;
}

function requestId(): string {
  const bytes = new Uint8Array(12);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** A default title for steps that only keep what comes back. */
function planTitle(plan: Plan): string {
  return "make" in plan ? `Make with ${plan.make.tool}` : `Run ${plan.steps.length} connection steps`;
}

/** What a task's connection steps did so far, in one line. */
function planDetail(t: CcTask): string {
  const make = t.make!;
  const then = { post: " · then posts", keep: "", phone: " · then a phone uses the results" }[make.then];
  if (make.steps.length <= 1) return `Makes with ${make.tool}${t.artifact ? ` · ${t.artifact.name}` : t.call ? ` · ${t.call.state}` : ""}${then}`;
  const done = Math.min(t.stepAt, make.steps.length);
  const last = t.calls[t.calls.length - 1];
  return `${make.steps.map((s) => s.tool).join(" → ")} · ${done} of ${make.steps.length} done${last && done < make.steps.length ? ` · step ${last.step + 1} ${last.state}` : ""}${then}`;
}
