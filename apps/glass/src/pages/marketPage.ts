/**
 * Marketplace: everything Cyclone can use, like a store. Recipes live on the phone (added, run and removed there, via
 * the gateway); connections are the phone's AI and this PC's MCP agents. Adding saves inputs; nothing runs until Run.
 */
import type { GlassContext } from "../app.js";
import { GatewayError } from "../services/gateway.js";
import {
  addListing,
  connectPc,
  getMarket,
  getPcConnections,
  pcStateLabel,
  removeListing,
  runListing,
  searchListings,
  type MarketCatalog,
  type MarketListing,
  type PcConnections,
} from "../services/market.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, pageHeader, searchInput } from "../ui/components.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

export function createMarketPage(ctx: GlassContext): GlassPage {
  let current = ctx;
  const element = el("div", "page page-market");
  const installedChip = el("button", "market-installed");
  installedChip.type = "button";
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Marketplace", "Recipes Cyclone can run on your phone, and the AI and agents it connects to.", [installedChip, refresh]));
  const search = searchInput("Search recipes and connections", (value) => {
    query = value;
    render();
  });
  const body = el("div", "market-body");
  const overlay = el("div", "market-overlay");
  element.append(search, body, overlay);

  let query = "";
  let showInstalled = false;
  let catalog: MarketCatalog | null = null;
  let catalogError: GatewayError | Error | null = null;
  let pc: PcConnections | null = null;
  let note = "";

  installedChip.addEventListener("click", () => {
    showInstalled = !showInstalled;
    render();
  });

  const load = async (): Promise<void> => {
    setChildren(body, loadingState("Loading the marketplace…"));
    const device = current.device;
    const gate = deviceGate(current);
    const [market, connections] = await Promise.allSettled([
      !gate && device ? getMarket(current.client, device.id) : Promise.resolve(null),
      getPcConnections(current.client),
    ]);
    catalog = market.status === "fulfilled" ? market.value : null;
    catalogError = market.status === "rejected" ? (market.reason as Error) : null;
    pc = connections.status === "fulfilled" ? connections.value : null;
    render();
  };
  refresh.addEventListener("click", () => void load());

  function render(): void {
    const count = catalog?.installedCount ?? 0;
    installedChip.textContent = showInstalled ? "← Back to the store" : `${count} installed ›`;
    const sections: HTMLElement[] = [];
    if (note) sections.push(el("p", "market-note", note));
    const gate = deviceGate(current);
    if (catalog) {
      const listings = catalog.listings;
      if (showInstalled) {
        const mine = listings.filter((l) => l.added);
        sections.push(section("Installed", mine.length ? grid(mine) : emptyState({ icon: "apps", title: "Nothing added yet", body: "Add a recipe and it appears here." })));
      } else if (query.trim()) {
        const found = searchListings(listings, query);
        sections.push(section("Results", found.length ? grid(found) : el("p", "market-muted", "No matches.")));
      } else {
        sections.push(section("Featured", featured(listings.filter((l) => l.featured).slice(0, 4))));
        const byId = new Map(listings.map((l) => [l.id, l]));
        const forYou = catalog.suggestions.flatMap((s) => (byId.get(s.id) ? [{ listing: byId.get(s.id)!, reason: s.reason }] : [])).slice(0, 6);
        if (forYou.length) sections.push(section("For you", grid(forYou.map((f) => f.listing), new Map(forYou.map((f) => [f.listing.id, f.reason])))));
        sections.push(section("From Cyclone", grid(listings.filter((l) => l.publisher.id === "cyclone"))));
      }
    } else if (gate) {
      sections.push(section("Recipes", gate));
    } else if (catalogError) {
      sections.push(section("Recipes", errorState("Could not load the phone's marketplace", toError(catalogError), () => void load())));
    }
    if (!showInstalled && !query.trim()) sections.push(connectionsSection());
    setChildren(body, ...sections);
  }

  function section(title: string, content: HTMLElement): HTMLElement {
    const node = el("section", "market-section");
    node.append(el("h2", "market-section-title", title), content);
    return node;
  }

  function glyph(text: string, size: "lg" | "md"): HTMLElement {
    return el("span", `market-glyph market-glyph-${size}`, text);
  }

  function featured(listings: MarketListing[]): HTMLElement {
    const row = el("div", "market-featured");
    for (const listing of listings) {
      const tile = el("button", "market-feature");
      tile.type = "button";
      tile.dataset.id = listing.id;
      tile.append(glyph(listing.glyph, "lg"), el("span", "market-feature-by", `${listing.publisher.name}'s`), el("span", "market-feature-name", listing.name));
      tile.addEventListener("click", () => open(listing));
      row.append(tile);
    }
    return row;
  }

  function grid(listings: MarketListing[], reasons = new Map<string, string>()): HTMLElement {
    const list = el("div", "market-grid");
    for (const listing of listings) {
      const row = el("div", "market-row");
      row.dataset.id = listing.id;
      const text = el("div", "market-row-text");
      text.append(el("span", "market-row-name", listing.name), el("span", "market-row-sub", reasons.get(listing.id) ?? listing.summary));
      const action = actionButton(listing.added ? "Open" : "Add", { variant: listing.added ? "ghost" : "secondary" });
      action.classList.add("market-add");
      action.addEventListener("click", () => open(listing));
      row.append(glyph(listing.glyph, "md"), text, action);
      list.append(row);
    }
    return list;
  }

  function connectionsSection(): HTMLElement {
    const box = el("div", "market-connections");
    for (const connection of catalog?.connections ?? []) {
      const tone = connection.state === "connected" ? "success" : connection.state === "off" ? "neutral" : "warning";
      box.append(connectionRow(connection.glyph, connection.name, connection.detail,
        chip(connection.state === "connected" ? "Connected" : connection.state === "off" ? "Off" : "Set up on the phone", tone), null));
    }
    if (pc) {
      if (!pc.available && pc.reason) box.append(el("p", "market-muted", pc.reason));
      for (const connection of pc.connections) {
        const state = pcStateLabel(connection.state);
        let action: HTMLButtonElement | null = null;
        // Connecting needs the agent installed on this PC; "not found" has nothing to connect yet.
        if (pc.available && !["connected", "not_found", "unknown"].includes(connection.state)) {
          action = actionButton(connection.id === "generic" ? "Get config" : connection.state === "attention" ? "Repair" : "Connect", { variant: "secondary" });
          const button = action;
          action.addEventListener("click", async () => {
            button.disabled = true;
            try {
              const result = await connectPc(current.client, connection.id);
              note = result.config ? `${result.message}\n${result.config}` : result.message;
            } catch (error) {
              note = toError(error).message;
            }
            await load();
          });
        }
        box.append(connectionRow("⌘", connection.name, `${connection.description} · MCP on this PC`, chip(state.label, state.tone), action));
      }
    }
    return section("Connections", box);
  }

  function connectionRow(symbol: string, name: string, detail: string, status: HTMLElement, action: HTMLElement | null): HTMLElement {
    const row = el("div", "market-connection");
    const text = el("div", "market-row-text");
    text.append(el("span", "market-row-name", name), el("span", "market-row-sub", detail));
    row.append(glyph(symbol, "md"), text, status);
    if (action) row.append(action);
    return row;
  }

  function open(listing: MarketListing): void {
    const device = current.device;
    const panel = card("market-sheet");
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", listing.name);
    const close = actionButton("Close", { variant: "ghost" });
    close.addEventListener("click", () => setChildren(overlay));
    const head = el("div", "market-sheet-head");
    const title = el("div", "market-row-text");
    title.append(el("span", "market-sheet-name", listing.name),
      el("span", "market-row-sub", `by ${listing.publisher.name}${listing.publisher.verified ? " ✓" : ""} · ${listing.category}`));
    head.append(glyph(listing.glyph, "lg"), title, close);
    panel.append(head, el("p", "market-sheet-summary", listing.summary));
    const list = (heading: string, lines: string[]) => {
      const node = el("div", "market-disclosure");
      node.append(el("div", "market-disclosure-title", heading));
      const ul = el("ul");
      for (const line of lines) ul.append(el("li", undefined, line));
      node.append(ul);
      return node;
    };
    panel.append(list("What it does", listing.does));
    if (listing.apps.length) panel.append(list("Apps it uses", listing.apps));
    panel.append(list("Asks you first", listing.asksFirst.length ? listing.asksFirst : ["Nothing: it does not send, delete or pay."]));
    panel.append(el("p", "market-goal", `“${listing.goal}”`));
    const values: Record<string, string> = {};
    for (const input of listing.inputs) {
      const field = el("label", "market-field");
      field.append(el("span", "market-field-label", input.label));
      const initial = listing.savedInputs?.[input.name] ?? input.default;
      values[input.name] = initial;
      if (input.kind === "choice") {
        const select = el("select", "market-input");
        for (const choice of input.choices) {
          const option = el("option", undefined, choice);
          option.value = choice;
          select.append(option);
        }
        select.value = initial;
        select.addEventListener("change", () => (values[input.name] = select.value));
        field.append(select);
      } else {
        const text = el("input", "market-input");
        text.value = initial;
        text.dataset.input = input.name;
        text.maxLength = 200;
        text.addEventListener("input", () => (values[input.name] = text.value));
        field.append(text);
      }
      panel.append(field);
    }
    const message = el("p", "market-message");
    const actions = el("div", "market-sheet-actions");
    const act = (label: string, variant: "primary" | "secondary" | "ghost", fn: () => Promise<unknown>, done: string) => {
      const button = actionButton(label, { variant });
      button.addEventListener("click", async () => {
        if (!device) return;
        button.disabled = true;
        try {
          await fn();
          note = done;
          setChildren(overlay);
          await load();
        } catch (error) {
          message.textContent = toError(error).message;
          button.disabled = false;
        }
      });
      return button;
    };
    if (!device) message.textContent = "Connect a phone in Devices to add or run recipes.";
    else if (!listing.added) {
      actions.append(act("Add to phone", "primary", () => addListing(current.client, device.id, listing.id, values), `${listing.name} was added to the phone.`));
    } else {
      actions.append(
        act("Remove", "ghost", () => removeListing(current.client, device.id, listing.id), `${listing.name} was removed.`),
        act("Save", "secondary", () => addListing(current.client, device.id, listing.id, values), `${listing.name} saved.`),
        act("Run on phone", "primary", async () => {
          await addListing(current.client, device.id, listing.id, values);
          await runListing(current.client, device.id, listing.id, values);
        }, `${listing.name} is running on the phone. Follow it on Phone or in Runs.`),
      );
    }
    panel.append(message, actions);
    setChildren(overlay, panel);
  }

  void load();
  return {
    element,
    update(next) {
      const deviceChanged = next.device?.id !== current.device?.id;
      current = next;
      if (deviceChanged) void load();
    },
    destroy() {},
  };
}

function toError(error: unknown): { code?: string; message: string } {
  if (error instanceof GatewayError) return { code: error.code, message: error.message };
  return { message: error instanceof Error ? error.message : "Something went wrong." };
}
