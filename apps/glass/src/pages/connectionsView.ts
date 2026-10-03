/**
 * Command Center → Connections (plan 33, C3): MCP servers such as Higgsfield. The owner adds a server, signs in with
 * OAuth in a new browser tab (the sign-in stays on this PC; Glass never sees a token), allows the tools Cyclone may
 * call, sets a daily cap and when to ask first, and sees the calls and the files they made.
 *
 * Plan 34: an API description (OpenAPI / Swagger) becomes a connection too, and any connector can be exported as a card
 * (no keys) and imported again. A few curated cards ship with Glass.
 *
 * Also the steps editor that task and routine forms use: up to five connection calls, each able to use an earlier
 * step's result as {step1.field}, then a phone posts the file, the results are kept, or a phone uses them.
 */
import type { GlassContext } from "../app.js";
import {
  LOCAL_CARD,
  basicAuth,
  classLabel,
  command,
  ruleLabel,
  sizeLabel,
  stepRefs,
  thenLabel,
  toolArguments,
  type ApprovalRule,
  type CcArtifact,
  type CcCall,
  type CcConnection,
  type CcTool,
  type Plan,
  type StepSpec,
  type Then,
  type ToolClass,
  type MrzStatus,
} from "../services/command.js";
import { CURATED } from "../services/cards.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState, segmented } from "../ui/components.js";
import { relativeTime } from "../ui/format.js";

const POLL_MS = 5_000;

export interface ConnectionsView {
  element: HTMLElement;
  destroy(): void;
}

export function createConnectionsView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void): ConnectionsView {
  const element = el("div", "cc-connections");
  const addBox = card("cc-card");
  const list = el("div", "cc-connection-list");
  const activity = el("div", "cc-connection-activity");
  const mrzBox = card("cc-card");
  let mrz: MrzStatus | null = null;
  let mrzBusy = false;
  let connections: CcConnection[] = [];
  let higgsfield = "https://mcp.higgsfield.ai/mcp";
  let destroyed = false;
  // Settings the owner is editing are kept across polls, per connection.
  const drafts = new Map<string, { allowed: Set<string>; dailyCap: string; approval: ApprovalRule; rules: Record<string, ApprovalRule>; classes: Record<string, ToolClass> }>();
  // "Try it" inputs and what came back survive the list's refresh (it redraws every few seconds).
  const tries = new Map<string, { values: Record<string, string>; out: string }>();

  const act = async (label: string, action: () => Promise<unknown>): Promise<boolean> => {
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
  };

  // ------------------------------------------------------------------ add: an address, or a config for a program on this PC

  let mode: "address" | "config" | "api" = "address";
  const name = el("input", "cc-input");
  name.setAttribute("aria-label", "Name");
  name.placeholder = "Higgsfield";
  const url = el("input", "cc-input");
  url.setAttribute("aria-label", "Server address");
  url.placeholder = "https://mcp.example.com/mcp";
  const config = el("textarea", "cc-input cc-goal");
  config.setAttribute("aria-label", "Server config");
  config.placeholder = '{ "mcpServers": { "files": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem@2025.8.21", "C:\\\\Notes"] } } }';
  const specUrl = el("input", "cc-input");
  specUrl.setAttribute("aria-label", "Description address");
  specUrl.placeholder = "https://api.example.com/openapi.json";
  const specText = el("textarea", "cc-input cc-goal");
  specText.setAttribute("aria-label", "API description");
  specText.placeholder = "Or paste the OpenAPI / Swagger description (JSON or YAML)";
  const baseUrl = el("input", "cc-input");
  baseUrl.setAttribute("aria-label", "API address");
  baseUrl.placeholder = "Only if the description does not say, e.g. https://api.example.com/v1";
  const preset = actionButton("Use Higgsfield", { variant: "ghost" });
  preset.addEventListener("click", () => {
    name.value = "Higgsfield";
    url.value = higgsfield;
  });
  const add = actionButton("Add connection", { variant: "primary", icon: "plug" });
  add.addEventListener("click", () => {
    if (mode === "api") {
      const address = String(specUrl.value ?? "").trim();
      const text = String(specText.value ?? "").trim();
      if (!address && !text) return say("Paste the description's address, or the description itself.", "error");
      if (address && text) return say("Use the address or the pasted description, not both.", "error");
      if (address && /[?&](key|\w*token|api[_-]?key)=/i.test(address)) return say("Leave keys out of the address. Cyclone asks for the key next, and keeps it sealed.", "error");
      const body: { specUrl: string } | { specText: string } = address ? { specUrl: address } : { specText: text };
      const extra: { baseUrl?: string; name?: string } = {};
      if (String(baseUrl.value ?? "").trim()) extra.baseUrl = String(baseUrl.value).trim();
      if (String(name.value ?? "").trim()) extra.name = String(name.value).trim();
      void act("Reading the API description", () => command.addConnection(ctx.client, { ...body, ...extra })).then((ok) => {
        if (ok) {
          specUrl.value = "";
          specText.value = "";
          baseUrl.value = "";
          name.value = "";
        }
      });
      return;
    }
    if (mode === "config") {
      const text = String(config.value ?? "").trim();
      if (!text) return say("Paste the server's config from its README.", "error");
      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        return say("That is not JSON. Paste the config block from the server's README.", "error");
      }
      void act("Reading the config", () => command.addConnection(ctx.client, { config: parsed })).then((ok) => {
        if (ok) config.value = "";
      });
      return;
    }
    const address = String(url.value ?? "").trim();
    if (!address) return say("Paste the server's address.", "error");
    if (/[?&](key|token|api[_-]?key)=/i.test(address)) return say("Leave keys out of the address. Cyclone asks for the key next, and keeps it sealed.", "error");
    void act("Connecting", () => command.addConnection(ctx.client, { name: String(name.value ?? "").trim() || "Connection", url: address })).then((ok) => {
      if (ok) {
        name.value = "";
        url.value = "";
      }
    });
  });
  const addBody = el("div", "cc-add-body");
  const modes = segmented<"address" | "config" | "api">([{ id: "address", label: "Server address" }, { id: "config", label: "Program on this PC" },
    { id: "api", label: "API description" }], mode, (id) => {
    mode = id;
    modes.set(id);
    drawAdd();
  });
  function drawAdd(): void {
    const actions = el("div", "cc-actions");
    name.placeholder = mode === "api" ? "Taken from the description" : "Higgsfield";
    if (mode === "address") {
      const grid = el("div", "cc-grid");
      grid.append(labelled("Name", name), labelled("Server address (MCP)", url));
      actions.append(add, preset);
      setChildren(addBody, el("p", "cc-hint", "Paste its address. Cyclone works out how to reach it and how to sign in: on the server's own page, or with a key you paste. Keys and sign-ins stay on this PC, sealed for your Windows user."), grid, actions);
    } else if (mode === "api") {
      const grid = el("div", "cc-grid");
      grid.append(labelled("Name (optional)", name), labelled("Description address (OpenAPI or Swagger)", specUrl), labelled("API address (optional)", baseUrl));
      actions.append(add);
      setChildren(addBody, el("p", "cc-hint", "For a service without an MCP server: give its OpenAPI or Swagger description. Each operation becomes a tool. Cyclone sends the requests itself, only to the API's own https address. Reading (GET) tools can run without asking; everything else asks you first until you decide otherwise."),
        grid, labelled("Or paste the description", specText), actions);
    } else {
      actions.append(add);
      setChildren(addBody, el("p", "cc-hint", "Paste the server's config (the mcpServers JSON from its README). Nothing runs until you say so."), labelled("Server config", config), actions);
    }
  }
  drawAdd();
  addBox.append(el("h2", "card-title", "Add a connection"), modes.element, addBody);

  // ------------------------------------------------------------------ cards: import one, or start from a curated one

  const cardsBox = card("cc-card");
  const cardText = el("textarea", "cc-input cc-goal");
  cardText.setAttribute("aria-label", "Connector card");
  cardText.placeholder = "Paste a connector card (the .json file someone exported), or pick the file";
  const cardFile = el("input", "cc-input");
  cardFile.type = "file";
  cardFile.accept = ".json,application/json";
  cardFile.setAttribute("aria-label", "Card file");
  cardFile.addEventListener("change", () => {
    const file = cardFile.files?.[0];
    if (!file) return;
    if (file.size > 6 * 1024 * 1024) return say("That card is larger than 6 MB.", "error");
    void file.text().then((text) => { cardText.value = text; }).catch(() => say("The file could not be read.", "error"));
  });
  const importButton = actionButton("Import the card", { variant: "primary", icon: "plug" });
  importButton.addEventListener("click", () => {
    const text = String(cardText.value ?? "").trim();
    if (!text) return say("Paste a card or pick its file first.", "error");
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return say("That is not a card (it is not JSON).", "error");
    }
    if (!parsed || typeof parsed !== "object" || (parsed as Record<string, unknown>).cyclone !== "connector-card") return say("That is not a Cyclone connector card.", "error");
    void act("Importing the card", () => command.importCard(ctx.client, parsed as Record<string, unknown>)).then((ok) => {
      if (ok) cardText.value = "";
    });
  });
  const curatedList = el("div", "cc-curated");
  for (const item of CURATED) {
    const row = el("div", "cc-row cc-curated-row");
    const use = actionButton("Add", { variant: "secondary" });
    use.setAttribute("aria-label", `Add ${item.title}`);
    use.addEventListener("click", () => void act(`Adding ${item.title}`, () => command.importCard(ctx.client, item.card)));
    const text = el("div", "cc-curated-text");
    text.append(el("strong", undefined, item.title), el("span", "cc-sub", item.needs));
    row.append(text, ...(item.verified ? [] : [chip("not yet checked live", "neutral")]), use);
    curatedList.append(row);
  }
  const importRow = el("div", "cc-actions");
  importRow.append(importButton);
  cardsBox.append(el("h2", "card-title", "Connector cards"),
    el("p", "cc-hint", "A card is a connector someone set up, without their keys. Import it, then sign in or paste your own key. Only tools that match the card are switched on, and tools that change things ask you every time."),
    curatedList, labelled("Import a card", cardText), cardFile, importRow);
  function renderMrz(): void {
    const title = el("h2", "card-title", "MRZ Studio · Employee ID");
    const info = el("p", "cc-hint", mrz?.detail || "This Glass runtime does not support Studio discovery yet. Update Cyclone, or paste Studio's Glass JSON below.");
    const how = el("p", "cc-hint", "Two ways to connect: let Cyclone find Studio on this PC, or paste its saved Glass JSON below. Discovery keeps checking while the runtime is open, even after this tab closes. Your approved connection stays through Glass updates.");
    const states = el("div", "cc-actions");
    if (mrz) {
      states.append(chip(mrz.state === "found" ? "Studio found" : mrz.state === "looking" ? "Looking for Studio" : mrz.state === "offline" ? "Studio offline" : "Needs attention", mrz.state === "found" ? "success" : "warning"));
      if (mrz.health) for (const [label, good] of [["API", mrz.health.api], ["Worker", mrz.health.worker], ["Photoshop", mrz.health.photoshop], ["Template", mrz.health.template]] as const) {
        states.append(chip(`${label}: ${good ? "ready" : "not ready"}`, good ? "success" : "warning"));
      }
      if (mrz.health?.dryRun) states.append(chip("Dry run · placeholder output", "warning"));
      if (mrz.connectionId) states.append(chip(mrz.connectionStatus === "ready" ? "MCP connected" : mrz.connectionStatus === "needs_approval" ? "Program approval needed" : "MCP needs attention", mrz.connectionStatus === "ready" ? "success" : "warning"));
    }
    const actions = el("div", "cc-actions");
    const check = actionButton("Check for Studio", { variant: "secondary" });
    check.disabled = mrzBusy || !mrz;
    check.addEventListener("click", () => void mrzAction("Checking for Studio", () => command.checkMrz(ctx.client)));
    const connect = actionButton(mrz?.connectionId ? "Reconnect MRZ Studio" : "Connect MRZ Studio", { variant: "primary", icon: "plug" });
    connect.disabled = mrzBusy || !mrz || mrz.state !== "found" || mrz.settingsChanged;
    connect.addEventListener("click", () => void mrzAction("Connecting MRZ Studio", async () => {
      if (mrz?.connectionId) return command.refreshConnection(ctx.client, mrz.connectionId);
      return command.connectMrz(ctx.client);
    }));
    const studio = el("a", "btn btn-ghost", "Open Studio settings");
    studio.href = `${mrz?.uiBase || "http://127.0.0.1:5173"}/settings/mcp`;
    studio.target = "_blank";
    studio.rel = "noopener noreferrer";
    const paste = actionButton("Use pasted Glass JSON", { variant: "ghost" });
    paste.addEventListener("click", () => {
      mode = "config"; modes.set(mode); drawAdd();
      if (mrz?.recipe) config.value = JSON.stringify(mrz.recipe, null, 2);
      config.focus();
    });
    actions.append(connect, check, studio, paste);
    const approval = el("p", "cc-hint", "Finding Studio never runs a program. Approve its pinned program below, then choose the tools Cyclone may use. A changed program needs approval again. The current fallback MCP uses Studio on port 8787.");
    setChildren(mrzBox, title, how, info, states, actions, approval);
    if (mrz?.settingsChanged) mrzBox.append(el("p", "cc-hint", "Studio's recipe changed. After jobs finish, remove the old MRZ connection below, then reconnect to review the new recipe. Existing permissions are never silently replaced."));
    if (mrz?.checkedAt) mrzBox.append(el("p", "cc-sub", `Last checked ${relativeTime(mrz.checkedAt)} · every 30 seconds`));
  }
  async function mrzAction(label: string, action: () => Promise<unknown>): Promise<void> {
    if (mrzBusy) return;
    mrzBusy = true; renderMrz();
    try { await act(label, action); }
    finally { mrzBusy = false; if (!destroyed) renderMrz(); }
  }
  renderMrz();
  element.append(mrzBox, addBox, cardsBox, list, activity);

  // ------------------------------------------------------------------ connections

  function draftOf(c: CcConnection) {
    let draft = drafts.get(c.id);
    if (!draft) {
      draft = { allowed: new Set(c.tools.filter((t) => t.allowed).map((t) => t.name)), dailyCap: String(c.dailyCap), approval: c.approval,
        rules: Object.fromEntries(c.tools.filter((t) => t.class === "change").map((t) => [t.name, t.rule])) as Record<string, ApprovalRule>,
        classes: {} as Record<string, ToolClass> };
      drafts.set(c.id, draft);
    }
    return draft;
  }

  const STATUS: Record<CcConnection["status"], [string, "success" | "warning" | "danger" | "neutral"]> = {
    ready: ["Ready", "success"], needs_sign_in: ["Sign in needed", "warning"], needs_key: ["Key needed", "warning"],
    needs_client: ["Client ID needed", "warning"], needs_approval: ["Waiting for your OK", "warning"], error: ["Not working", "danger"], new: ["New", "neutral"],
  };

  function connectionCard(c: CcConnection): HTMLElement {
    const box = card("cc-card cc-connection");
    const head = el("div", "cc-row");
    const [label, tone] = STATUS[c.status];
    head.append(el("strong", undefined, c.name), chip(label, tone));
    if (c.kind === "local") head.append(chip(c.running ? "On this PC · running" : "On this PC", "accent"), el("span", "muted", c.launch?.pinned ?? ""));
    else if (c.kind === "api") head.append(chip("API", "accent"), el("span", "muted", `${c.api?.title ?? ""}${c.api?.version ? ` ${c.api.version}` : ""} · ${c.url}`));
    else head.append(el("span", "muted", c.url), ...(c.transport === "sse" ? [chip("older transport", "neutral")] : []));
    if (c.fromCard) head.append(chip("From a card: its tools switch on once they are listed", "neutral"));
    box.append(head);
    if (c.probe.length) {
      const steps = el("ul", "cc-probe");
      for (const step of c.probe) steps.append(el("li", step.ok ? "cc-probe-ok" : "cc-probe-no", `${step.ok ? "✓" : "•"} ${step.step}${step.detail ? `: ${step.detail}` : ""}`));
      box.append(steps);
    } else if (c.detail) box.append(el("p", "cc-hint", c.detail));

    if (c.status === "needs_approval" && c.launch) box.append(setupCard(c));
    if (c.status === "needs_key") box.append(keyForm(c));
    if (c.status === "needs_client") box.append(clientForm(c));

    const actions = el("div", "cc-actions");
    if (c.auth === "oauth" && !c.signedIn && c.status !== "needs_client") {
      const signIn = actionButton("Sign in", { variant: "primary", icon: "user" });
      signIn.addEventListener("click", () => void act("Opening the sign-in page", async () => {
        const target = await command.signIn(ctx.client, c.id);
        if (!/^https:\/\//.test(target) && !/^http:\/\/(127\.0\.0\.1|localhost)[:/]/.test(target)) throw new Error("The sign-in address is not https.");
        const opened = typeof globalThis.open === "function" ? globalThis.open(target, "_blank", "noopener,noreferrer") : null;
        say(opened === null ? "Sign in on the page that opened. Come back here when it says you are signed in." : "Sign in on the new tab, then come back.");
      }));
      actions.append(signIn);
    }
    const refresh = actionButton("Check again", { variant: "ghost" });
    refresh.addEventListener("click", () => void act("Checking", () => command.refreshConnection(ctx.client, c.id)));
    actions.append(refresh);
    if (c.status === "ready" || c.tools.length) {
      const exportCard = actionButton("Export card", { variant: "ghost", icon: "download" });
      exportCard.addEventListener("click", () => void saveCard(c));
      actions.append(exportCard);
    }
    if (c.signedIn) {
      const out = actionButton(c.auth === "header" || c.auth === "query" ? "Forget the key" : "Sign out", { variant: "ghost" });
      out.addEventListener("click", () => void act("Signing out", () => command.signOut(ctx.client, c.id)));
      actions.append(out);
    }
    if (c.kind === "local") {
      const log = el("pre", "cc-log");
      const show = actionButton("Show its log", { variant: "ghost" });
      show.addEventListener("click", () => void command.logs(ctx.client, c.id).then((lines) => {
        log.textContent = lines.length ? lines.slice(-40).join("\n") : "Nothing written yet.";
      }).catch((err: Error) => say(err.message, "error")));
      actions.append(show);
      box.append(log);
    }
    const remove = actionButton("Remove", { variant: "ghost" });
    remove.addEventListener("click", () => void act("Removing", () => command.removeConnection(ctx.client, c.id)));
    actions.append(remove);
    box.append(actions);
    if (c.tools.length) box.append(toolsSection(c));
    return box;
  }

  /** Plan 34 §3.1: one short card before a program first runs on this PC. */
  function setupCard(c: CcConnection): HTMLElement {
    const launch = c.launch!;
    const box = el("div", "cc-setup");
    box.append(el("h3", "cc-subtitle", LOCAL_CARD.title), el("code", "cc-command", launch.display));
    if (launch.previous) box.append(el("p", "cc-hint", `Changed. Before it was: ${launch.previous}`));
    box.append(el("p", undefined, LOCAL_CARD.body), el("p", "cc-strong", LOCAL_CARD.trust));
    const envInputs = launch.envKeys.map((key) => {
      const input = el("input", "cc-input");
      input.type = "password";
      input.autocomplete = "off";
      input.setAttribute("aria-label", key);
      input.placeholder = c.envSet.includes(key) ? "Saved (type to change)" : "Value";
      return [key, input] as const;
    });
    if (envInputs.length) {
      const grid = el("div", "cc-grid");
      grid.append(...envInputs.map(([key, input]) => labelled(`${key} (kept sealed on this PC)`, input)));
      box.append(grid);
    }
    const run = actionButton("Run it", { variant: "primary", icon: "play" });
    run.addEventListener("click", () => void act("Starting it", async () => {
      const values: Record<string, string> = {};
      for (const [key, input] of envInputs) {
        const value = String(input.value ?? "");
        if (value) values[key] = value;
        input.value = "";
      }
      if (Object.keys(values).length) await command.setEnv(ctx.client, c.id, values);
      await command.approveLocal(ctx.client, c.id, launch.hash);
    }));
    const cancel = actionButton("Cancel", { variant: "ghost" });
    cancel.addEventListener("click", () => void act("Removing", () => command.removeConnection(ctx.client, c.id)));
    const row = el("div", "cc-actions");
    row.append(run, cancel);
    box.append(row);
    return box;
  }

  function keyForm(c: CcConnection): HTMLElement {
    const scheme = c.api?.scheme;
    if (scheme?.type === "query") return queryKeyForm(c, c.keyQuery ?? scheme.name ?? "api_key");
    if (scheme?.type === "basic") return basicForm(c);
    const box = el("div", "cc-setup");
    const header = el("input", "cc-input");
    header.setAttribute("aria-label", "Header name");
    header.value = c.keyHeader ?? "Authorization";
    const value = el("input", "cc-input");
    value.type = "password";
    value.autocomplete = "off";
    value.setAttribute("aria-label", "Key");
    const bearer = el("input");
    bearer.type = "checkbox";
    bearer.checked = (c.keyHeader ?? "Authorization") === "Authorization";
    bearer.setAttribute("aria-label", "Send it as Bearer");
    const bearerLabel = el("label", "cc-check");
    bearerLabel.append(bearer, el("span", undefined, "Send it as “Bearer <key>”"));
    const save = actionButton("Save the key", { variant: "primary", icon: "lock" });
    save.addEventListener("click", () => {
      const key = String(value.value ?? "").trim();
      if (!key) return say("Paste the key first.", "error");
      const full = bearer.checked && !/^bearer\s/i.test(key) ? `Bearer ${key}` : key;
      value.value = "";
      void act("Saving the key", () => command.setKey(ctx.client, c.id, String(header.value || "Authorization").trim(), full));
    });
    const grid = el("div", "cc-grid");
    grid.append(labelled("Header name (from the service's docs)", header), labelled("Key", value));
    const row = el("div", "cc-actions");
    row.append(save);
    box.append(el("p", "cc-hint", `The key is kept sealed on this PC and only sent to ${c.kind === "api" ? "this API's address" : "this server"}. Glass does not keep it.`), grid, bearerLabel, row);
    return box;
  }

  /** An API that takes its key in the address (?name=key), as its description says. */
  function queryKeyForm(c: CcConnection, param: string): HTMLElement {
    const box = el("div", "cc-setup");
    const value = el("input", "cc-input");
    value.type = "password";
    value.autocomplete = "off";
    value.setAttribute("aria-label", "Key");
    const save = actionButton("Save the key", { variant: "primary", icon: "lock" });
    save.addEventListener("click", () => {
      const key = String(value.value ?? "").trim();
      if (!key) return say("Paste the key first.", "error");
      value.value = "";
      void act("Saving the key", () => command.setQueryKey(ctx.client, c.id, param, key));
    });
    const row = el("div", "cc-actions");
    row.append(save);
    box.append(el("p", "cc-hint", `This API takes its key in the address (${param}=…). The key is kept sealed on this PC and added only to calls to this API. Glass does not keep it.`),
      labelled("Key", value), row);
    return box;
  }

  /** HTTP basic sign-in: a user name and password, kept sealed on this PC as one header value. */
  function basicForm(c: CcConnection): HTMLElement {
    const box = el("div", "cc-setup");
    const user = el("input", "cc-input");
    user.autocomplete = "off";
    user.setAttribute("aria-label", "User name");
    const pass = el("input", "cc-input");
    pass.type = "password";
    pass.autocomplete = "off";
    pass.setAttribute("aria-label", "Password for the API");
    const save = actionButton("Save", { variant: "primary", icon: "lock" });
    save.addEventListener("click", () => {
      const name = String(user.value ?? "").trim();
      const secret = String(pass.value ?? "");
      if (!name || !secret) return say("Type the user name and the password.", "error");
      pass.value = "";
      void act("Saving the sign-in", () => command.setKey(ctx.client, c.id, "Authorization", basicAuth(name, secret)));
    });
    const grid = el("div", "cc-grid");
    grid.append(labelled("User name", user), labelled("Password", pass));
    const row = el("div", "cc-actions");
    row.append(save);
    box.append(el("p", "cc-hint", "This API signs in with a user name and password (HTTP basic). They are kept sealed on this PC and sent only to this API. Glass does not keep them."), grid, row);
    return box;
  }

  async function saveCard(c: CcConnection): Promise<void> {
    try {
      const exported = await command.exportCard(ctx.client, c.id);
      const blob = new Blob([JSON.stringify(exported, null, 2)], { type: "application/json" });
      const href = URL.createObjectURL(blob);
      const link = el("a");
      link.href = href;
      link.download = `${c.name.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "") || "connector"}.cyclone-card.json`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(href), 10_000);
      say(`Exported ${c.name}'s card. It holds no keys or sign-ins.`);
    } catch (err) {
      say((err as Error).message || "The card could not be exported.", "error");
    }
  }

  function clientForm(c: CcConnection): HTMLElement {
    const box = el("div", "cc-setup");
    const redirect = `${globalThis.location?.origin ?? "http://127.0.0.1"}/v1/cc/connections/oauth/callback`;
    const id = el("input", "cc-input");
    id.setAttribute("aria-label", "Client ID");
    const secret = el("input", "cc-input");
    secret.type = "password";
    secret.autocomplete = "off";
    secret.setAttribute("aria-label", "Client secret");
    const save = actionButton("Save and sign in", { variant: "primary" });
    save.addEventListener("click", () => {
      const clientId = String(id.value ?? "").trim();
      if (!clientId) return say("Paste the client ID.", "error");
      const clientSecret = String(secret.value ?? "").trim() || undefined;
      secret.value = "";
      void act("Saving the client", () => command.setClient(ctx.client, c.id, clientId, clientSecret));
    });
    const grid = el("div", "cc-grid");
    grid.append(labelled("Client ID", id), labelled("Client secret (if it gave one)", secret));
    const row = el("div", "cc-actions");
    row.append(save);
    box.append(el("p", "cc-hint", "This service wants you to register Cyclone as an app yourself. In its developer settings, create an app with this redirect address, then paste its client ID here:"),
      el("code", "cc-command", redirect), grid, row);
    return box;
  }

  function toolsSection(c: CcConnection): HTMLElement {
    const draft = draftOf(c);
    const wrap = el("div", "cc-tools-wrap");
    wrap.append(el("h3", "cc-subtitle", "Tools Cyclone may use"), el("p", "cc-hint", "Nothing is on until you tick it. Tasks and routines can use only these."));
    const reads = c.tools.filter((t) => t.class === "read");
    if (reads.some((t) => !draft.allowed.has(t.name))) {
      const allowReads = actionButton(`Allow all reads (${reads.length})`, { variant: "secondary" });
      allowReads.addEventListener("click", () => {
        for (const t of reads) draft.allowed.add(t.name);
        void act("Allowing the reads", () => command.connectionSettings(ctx.client, c.id, { allowReads: true })).then(() => drafts.delete(c.id));
      });
      wrap.append(allowReads);
    }
    for (const cls of ["read", "change", "sensitive"] as const) {
      const group = c.tools.filter((t) => t.class === cls);
      if (!group.length) continue;
      const box = el("div", `cc-tools cc-tools-${cls}`);
      box.append(el("h4", "cc-group-title", classLabel(cls)));
      for (const tool of group) box.append(toolRow(c, tool, draft));
      wrap.append(box);
    }
    const cap = el("input", "cc-input");
    cap.type = "number";
    cap.min = "0";
    cap.max = "1000";
    cap.value = draft.dailyCap;
    cap.setAttribute("aria-label", "Calls a day");
    cap.addEventListener("input", () => {
      draft.dailyCap = String(cap.value);
    });
    const rule = el("select", "cc-input");
    rule.setAttribute("aria-label", "When to ask");
    for (const r of ["always", "over_cap", "cap"] as const) rule.append(option(r, ruleLabel(r)));
    rule.value = draft.approval;
    rule.addEventListener("change", () => {
      draft.approval = rule.value as ApprovalRule;
    });
    const save = actionButton("Save rules", { variant: "primary" });
    save.addEventListener("click", () => {
      const dailyCap = Math.round(Number(draft.dailyCap));
      if (!Number.isFinite(dailyCap) || dailyCap < 0 || dailyCap > 1000) return say("Calls a day is 0 to 1000.", "error");
      const rules = Object.fromEntries(Object.entries(draft.rules).filter(([name]) => (draft.classes[name] ?? c.tools.find((t) => t.name === name)?.class) === "change"));
      const body = { allowed: [...draft.allowed], rules, dailyCap, approval: draft.approval, ...(Object.keys(draft.classes).length ? { classes: draft.classes } : {}) };
      void act("Saving the rules", () => command.connectionSettings(ctx.client, c.id, body)).then((ok) => {
        if (ok) drafts.delete(c.id);
      });
    });
    const rules = el("div", "cc-grid");
    rules.append(labelled(`Calls a day (${c.usedToday} used today)`, cap), labelled("New changing tools: when to ask", rule));
    const saveRow = el("div", "cc-actions");
    saveRow.append(save);
    wrap.append(rules, saveRow);
    return wrap;
  }

  function toolRow(c: CcConnection, tool: CcTool, draft: ReturnType<typeof draftOf>): HTMLElement {
    const row = el("div", "cc-tool-row");
    const input = el("input");
    input.type = "checkbox";
    input.value = tool.name;
    input.checked = draft.allowed.has(tool.name);
    input.addEventListener("change", () => {
      if (input.checked) draft.allowed.add(tool.name);
      else draft.allowed.delete(tool.name);
    });
    const label = el("label", "cc-check cc-tool");
    label.append(input, el("span", undefined, tool.title || tool.name), el("span", "cc-sub", tool.description.slice(0, 160)));
    row.append(label);
    if (tool.changed) {
      row.append(chip("Changed — look before you allow it again", "warning"));
      if (tool.previous) row.append(el("p", "cc-hint", `Before: ${tool.previous.description.slice(0, 200)}`), el("p", "cc-hint", `Now: ${tool.description.slice(0, 200)}`));
    }
    if (tool.baseClass !== "sensitive") {
      // The owner's reading of the tool: a POST search that only reads, or a GET they want asked about.
      const counts = el("select", "cc-input cc-rule");
      counts.setAttribute("aria-label", `${tool.name} counts as`);
      for (const cls of ["read", "change", "sensitive"] as const) counts.append(option(cls, { read: "Counts as a read", change: "Counts as a change", sensitive: "Always ask me" }[cls]));
      counts.value = draft.classes[tool.name] ?? tool.class;
      counts.addEventListener("change", () => {
        draft.classes[tool.name] = counts.value as ToolClass;
      });
      row.append(counts);
      if (tool.overridden) row.append(chip(`You moved this (Cyclone reads it as ${tool.baseClass === "read" ? "a read" : "a change"})`, "neutral"));
    }
    if (tool.class === "change") {
      const rule = el("select", "cc-input cc-rule");
      rule.setAttribute("aria-label", `When to ask for ${tool.name}`);
      for (const r of ["always", "over_cap", "cap"] as const) rule.append(option(r, ruleLabel(r)));
      rule.value = draft.rules[tool.name] ?? tool.rule;
      rule.addEventListener("change", () => {
        draft.rules[tool.name] = rule.value as ApprovalRule;
      });
      row.append(rule);
    }
    if (tool.class === "read" && tool.allowed) row.append(tryIt(c, tool));
    return row;
  }

  /** Run one reading tool now and show what came back. */
  function tryIt(c: CcConnection, tool: CcTool): HTMLElement {
    const wrap = el("div", "cc-try");
    const key = `${c.id}:${tool.name}`;
    const kept = tries.get(key) ?? { values: {}, out: "" };
    tries.set(key, kept);
    const inputs = tool.fields.map((field) => {
      const input = el("input", "cc-input");
      input.setAttribute("aria-label", `${tool.name} ${field.name}`);
      input.placeholder = field.name + (field.required ? "" : " (optional)");
      input.value = kept.values[field.name] ?? "";
      input.addEventListener("input", () => {
        kept.values[field.name] = String(input.value ?? "");
      });
      return [field.name, input] as const;
    });
    const out = el("pre", "cc-result");
    out.textContent = kept.out;
    const show = (text: string) => {
      kept.out = text;
      // The list may have been redrawn while the call ran: write to the row that is on screen now.
      const live = Array.from(list.querySelectorAll?.("pre") ?? []).find((p) => p.getAttribute("data-try") === key) as HTMLElement | undefined;
      (live ?? out).textContent = text;
    };
    out.setAttribute("data-try", key);
    const run = actionButton("Try it", { variant: "ghost", icon: "play" });
    run.addEventListener("click", async () => {
      try {
        const values: Record<string, string> = {};
        for (const [name, input] of inputs) values[name] = String(input.value ?? "");
        kept.values = { ...values };
        show("Running…");
        let call = await command.tryTool(ctx.client, c.id, tool.name, toolArguments(tool, values));
        for (let i = 0; i < 60 && (call.state === "running" || call.state === "waiting"); i += 1) {
          if (call.state === "waiting") break;
          await new Promise((resolve) => setTimeout(resolve, 1_000));
          call = await command.getCall(ctx.client, call.id);
        }
        show(call.state === "waiting" ? "Waiting for your OK in Approvals." : call.result !== null ? JSON.stringify(call.result, null, 2).slice(0, 4_000) : call.summary);
      } catch (err) {
        show((err as Error).message);
      }
    });
    wrap.append(...inputs.map(([, input]) => input), run, out);
    return wrap;
  }

  function renderList(): void {
    if (!connections.length) {
      setChildren(list, emptyState({ icon: "plug", title: "No connections yet", body: "Add Higgsfield, any MCP server you use, or a server program on this PC." }));
      return;
    }
    // Do not redraw under a field the owner is typing in (a button that was just pressed does not count).
    const active = globalThis.document?.activeElement as Element | null | undefined;
    if (active && ["INPUT", "SELECT", "TEXTAREA"].includes(active.tagName) && list.contains?.(active)) return;
    setChildren(list, ...connections.map(connectionCard));
  }

  // ------------------------------------------------------------------ activity

  function renderActivity(calls: CcCall[], artifacts: CcArtifact[]): void {
    const callBox = card("cc-card");
    callBox.append(el("h2", "card-title", "Recent calls"));
    if (!calls.length) callBox.append(el("p", "muted", "No calls yet."));
    for (const call of calls.slice(0, 12)) {
      const name = connections.find((c) => c.id === call.connectionId)?.name ?? "Removed connection";
      const tone = call.state === "done" ? "success" : call.state === "waiting" ? "warning" : call.state === "running" ? "accent" : call.state === "failed" ? "danger" : "neutral";
      const row = el("div", "cc-row");
      row.append(
        chip({ waiting: "Waiting for your OK", running: "Running", done: "Done", failed: "Failed", declined: "Declined", refused: "Refused" }[call.state], tone),
        el("strong", undefined, `${name} · ${call.tool}`),
        el("span", "muted", `${relativeTime(call.createdAt)}${call.summary ? ` · ${call.summary.slice(0, 140)}` : ""}`),
      );
      callBox.append(row);
    }
    const fileBox = card("cc-card");
    fileBox.append(el("h2", "card-title", "Files made"), el("p", "cc-hint", "Kept on this PC by their SHA-256. A task that posts one sends it to the phone's gallery first."));
    if (!artifacts.length) fileBox.append(el("p", "muted", "No files yet."));
    for (const artifact of artifacts.slice(0, 24)) {
      const row = el("div", "cc-row cc-artifact");
      const download = actionButton("Download", { variant: "ghost" });
      download.addEventListener("click", () => void saveArtifact(artifact));
      row.append(
        chip(artifact.mime.split("/")[0] || "file", "accent"),
        el("strong", undefined, artifact.name),
        el("span", "muted", `${sizeLabel(artifact.size)} · ${relativeTime(artifact.createdAt)}${artifact.prompt ? ` · “${artifact.prompt.slice(0, 100)}”` : ""}`),
        download,
      );
      fileBox.append(row);
    }
    setChildren(activity, callBox, fileBox);
  }

  async function saveArtifact(artifact: CcArtifact): Promise<void> {
    try {
      const response = await ctx.client.authorizedFetch(`/v1/cc/artifacts/${encodeURIComponent(artifact.id)}/file`);
      if (!response.ok) throw new Error(`The file could not be downloaded (${response.status}).`);
      const blob = await response.blob();
      const href = URL.createObjectURL(blob);
      const link = el("a");
      link.href = href;
      link.download = artifact.name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(href), 10_000);
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  async function load(): Promise<void> {
    try {
      const [listed, calls, artifacts] = await Promise.all([command.connections(ctx.client), command.calls(ctx.client), command.artifacts(ctx.client)]);
      if (destroyed) return;
      connections = listed.connections;
      higgsfield = listed.higgsfield;
      mrz = listed.mrz;
      renderMrz();
      renderList();
      renderActivity(calls, artifacts);
    } catch (err) {
      if (!destroyed) say((err as Error).message || "Connections are not reachable.", "error");
    }
  }

  void load();
  const timer = setInterval(() => void load(), POLL_MS);
  return {
    element,
    destroy() {
      destroyed = true;
      clearInterval(timer);
    },
  };
}

// ---------------------------------------------------------------------------------------------- the steps editor

export interface MakeEditor {
  element: HTMLElement;
  /** The plan, or null when "use connections first" is off. Throws a sentence for the owner when a field is wrong. */
  read(): Plan | null;
  /** The plan's "then" (post, keep or phone), or null when it is off. */
  then(): Then | null;
  /** Load (or reload) the ready connections. */
  refresh(): Promise<void>;
}

const MAX_STEPS = 5;

interface StepBlock {
  element: HTMLElement;
  connection: HTMLSelectElement;
  tool: HTMLSelectElement;
  poll: HTMLSelectElement;
  inputs: Array<{ field: CcTool["fields"][number]; control: HTMLInputElement | HTMLSelectElement }>;
}

/** Up to five connection calls before (or instead of) the phone. Step 1's controls keep their C3 names. */
export function createMakeEditor(ctx: GlassContext): MakeEditor {
  const element = el("div", "cc-make");
  const on = el("input");
  on.type = "checkbox";
  on.setAttribute("aria-label", "Use connections first");
  const onLabel = el("label", "cc-check");
  onLabel.append(on, el("span", undefined, "First use connections: make a file (a video from Higgsfield, say), or fetch data and pass it on"));
  const panel = el("div", "cc-make-panel");
  const then = el("select", "cc-input");
  then.setAttribute("aria-label", "Then");
  for (const value of ["post", "keep", "phone"] as const) then.append(option(value, thenLabel(value)));
  const stepsBox = el("div", "cc-steps");
  const addStep = actionButton("Add a step", { variant: "ghost" });
  let connections: CcConnection[] = [];
  let blocks: StepBlock[] = [];

  const prefix = (n: number): string => (n === 1 ? "" : `Step ${n} `);

  function block(n: number): StepBlock {
    const box = el("div", "cc-step");
    const connection = el("select", "cc-input");
    connection.setAttribute("aria-label", n === 1 ? "Connection" : `Step ${n} connection`);
    const tool = el("select", "cc-input");
    tool.setAttribute("aria-label", n === 1 ? "Tool" : `Step ${n} tool`);
    const poll = el("select", "cc-input");
    poll.setAttribute("aria-label", n === 1 ? "Check the result with" : `Step ${n} check the result with`);
    const fields = el("div", "cc-grid");
    const self: StepBlock = { element: box, connection, tool, poll, inputs: [] };
    const current = () => connections.find((c) => c.id === connection.value);
    const currentTool = () => current()?.tools.find((t) => t.name === tool.value);
    const drawFields = () => {
      const t = currentTool();
      const paired = t?.pollTool && current()?.tools.some((x) => x.name === t.pollTool && x.allowed) ? t.pollTool : "";
      poll.value = paired;
      self.inputs = (t?.fields ?? []).map((field) => {
        let control: HTMLInputElement | HTMLSelectElement;
        if (field.enum.length && n === 1) {
          control = el("select", "cc-input");
          if (!field.required) control.append(option("", "Default"));
          for (const value of field.enum) control.append(option(String(value), String(value)));
          if (field.default !== null) control.value = String(field.default);
        } else {
          control = el("input", "cc-input");
          // A later step's field may hold {step1.field}, so it is text; step 1's numbers stay numbers.
          control.type = n === 1 && (field.type === "integer" || field.type === "number") ? "number" : "text";
          const hint = field.enum.length ? field.enum.slice(0, 4).join(" / ") : field.default !== null ? String(field.default) : "";
          if (hint) control.placeholder = hint;
          else if (n > 1) control.placeholder = "A value, or {step1.field}";
        }
        control.setAttribute("aria-label", `${prefix(n)}${field.name}`);
        return { field, control };
      });
      setChildren(fields, ...self.inputs.map(({ field, control }) => labelled(`${field.name}${field.required ? "" : " (optional)"}`, control)));
    };
    const drawTools = () => {
      const c = current();
      const allowed = c ? c.tools.filter((t) => t.allowed) : [];
      tool.replaceChildren(...allowed.map((t) => option(t.name, t.title || t.name)));
      tool.value = allowed[0]?.name ?? "";
      poll.replaceChildren(option("", "The first answer is the result"), ...allowed.map((t) => option(t.name, `Then check with ${t.title || t.name}`)));
      drawFields();
    };
    connection.replaceChildren(...connections.map((c) => option(c.id, c.name)));
    connection.value = connections[0]?.id ?? "";
    connection.addEventListener("change", drawTools);
    tool.addEventListener("change", drawFields);
    drawTools();
    const grid = el("div", "cc-grid");
    grid.append(labelled("Connection", connection), labelled("Tool", tool), labelled("Result", poll));
    const head = el("div", "cc-row");
    head.append(el("strong", undefined, `Step ${n}`));
    if (n > 1) {
      const remove = actionButton("Remove this step", { variant: "ghost" });
      remove.addEventListener("click", () => {
        blocks = blocks.slice(0, n - 1);
        draw();
      });
      head.append(remove);
    }
    box.append(head, grid, fields);
    return self;
  }

  addStep.addEventListener("click", () => {
    if (blocks.length >= MAX_STEPS) return;
    blocks.push(block(blocks.length + 1));
    draw();
  });

  const draw = () => {
    if (!on.checked) {
      panel.replaceChildren();
      return;
    }
    if (!blocks.length) blocks = [block(1)];
    setChildren(stepsBox, ...blocks.map((b) => b.element));
    const tail = el("div", "cc-actions");
    if (blocks.length < MAX_STEPS && connections.length) tail.append(addStep);
    setChildren(panel, stepsBox, tail, labelled("Then", then),
      el("p", "cc-hint", "A later step, or the goal, can use an earlier result: {step1.orders.0.id} is the id of the first order step 1 brought back. A phone gets results as quoted data, never as instructions."));
    if (!connections.length) panel.append(el("p", "cc-hint", "No ready connection with allowed tools. Add one in Command Center → Connections."));
  };
  on.addEventListener("change", draw);
  element.append(onLabel, panel);

  return {
    element,
    then: () => (on.checked ? (then.value as Then) || "post" : null),
    read(): Plan | null {
      if (!on.checked) return null;
      const steps: StepSpec[] = blocks.map((b, i) => {
        const c = connections.find((x) => x.id === b.connection.value);
        const t = c?.tools.find((x) => x.name === b.tool.value);
        if (!c || !t) throw new Error(`Step ${i + 1}: pick a connection and one of its allowed tools.`);
        const values: Record<string, string> = {};
        for (const { field, control } of b.inputs) values[field.name] = String(control.value ?? "");
        let args: Record<string, string | number | boolean>;
        try {
          args = toolArguments(t, values, i + 1);
        } catch (err) {
          throw new Error(blocks.length > 1 ? `Step ${i + 1}: ${(err as Error).message}` : (err as Error).message);
        }
        return { connectionId: c.id, tool: t.name, arguments: args, pollTool: String(b.poll.value || "") || null };
      });
      const chosen = (then.value as Then) || "post";
      if (steps.length === 1 && chosen !== "phone") return { make: { ...steps[0], then: chosen } };
      return { steps, then: chosen };
    },
    async refresh(): Promise<void> {
      try {
        connections = (await command.connections(ctx.client)).connections.filter((c) => c.status === "ready" && c.allowed.length);
      } catch {
        connections = [];
      }
      blocks = [];
      draw();
    },
  };
}

/** The goal's {stepN…} references, checked against the plan in the form. Throws a sentence. */
export function checkGoalRefs(goal: string, plan: Plan | null): void {
  const refs = stepRefs(goal);
  if (!refs.length) return;
  const count = !plan ? 0 : "make" in plan ? 1 : plan.steps.length;
  const then = !plan ? null : "make" in plan ? plan.make.then : plan.then;
  if (!count || then === "keep") throw new Error("{step…} in the goal needs steps whose results go to a phone.");
  if (refs.some((n) => n > count)) throw new Error(`The goal names a step after the last one (there are ${count}).`);
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
