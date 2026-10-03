/**
 * Glass shell: sidebar, phone picker, one mounted page. Owns the device list and the route; pages own their data.
 * Glass has no intelligence: everything below reads from or commands the phone through the local gateway.
 */
import { isCommandTab, modeOf, parseRoute, routeHref, sectionOf, type AppTab, type Mode, type Route } from "./core/router.js";
import { deviceReadiness, listDevices, pickDevice, type GlassDevice, type NotReadyReason } from "./services/devices.js";
import { GatewayError, type GatewayClient } from "./services/gateway.js";
import { el, setChildren } from "./ui/dom.js";
import { icon, type IconName } from "./ui/icons.js";
import type { GlassPage } from "./pages/page.js";
import { createAppsPage } from "./pages/appsPage.js";
import { createPhonePage } from "./pages/phonePage.js";
import { createRunsPage } from "./pages/runsPage.js";
import { createRunPage } from "./pages/runPage.js";
import { createSettingsPage } from "./pages/settingsPage.js";
import { createHomePage } from "./pages/homePage.js";
import { createDevicesPage } from "./pages/devicesPage.js";
import { createAppKnowledgePage } from "./pages/appKnowledgePage.js";
import { createKnowledgePage } from "./pages/knowledgePage.js";
import { createLabPage } from "./pages/labPage.js";
import { createMarketPage } from "./pages/marketPage.js";
import { createRemotePage } from "./pages/remotePage.js";
import { createAttachPage } from "./pages/attachPage.js";
import { createCommandPage } from "./pages/commandPage.js";
import { createPortsPage } from "./pages/portsPage.js";
import { welcome } from "./services/pc.js";
import { createWelcomeCard } from "./ui/welcomeCard.js";
import { commandLogo, cycloneLogo } from "./ui/logos.js";
import { createWorkspaceSidebar, type WorkspaceSidebar } from "./workspace/sidebar.js";
import { createWorkspaceHome } from "./workspace/home.js";
import { createPageView } from "./workspace/pageView.js";
import { createTrashPage } from "./workspace/trash.js";
import { createAiPanel, type AiPanel } from "./workspace/aiPanel.js";
import { createAiSettings } from "./workspace/aiSettings.js";
import { workspaceBus } from "./workspace/directory.js";

export const DEVICE_STORAGE_KEY = "cyclone.glass.device.v1";
const DEVICE_REFRESH_MS = 5_000;

export interface GlassContext {
  client: GatewayClient;
  version: string;
  devices: GlassDevice[];
  device: GlassDevice | null;
  /** Last device-list failure, for honest empty states. */
  devicesError: GatewayError | null;
  navigate(route: Route): void;
  selectDevice(id: string): void;
  refreshDevices(): Promise<void>;
}

export interface GlassAppOptions {
  root: HTMLElement;
  client: GatewayClient;
  version: string;
  location: { hash: string };
  storage: Pick<Storage, "getItem" | "setItem"> | null;
  setHash(hash: string): void;
  onHashChange(listener: () => void): () => void;
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
}

type PageFactory = (ctx: GlassContext, route: Route) => GlassPage;

const PAGES: Record<Route["name"], PageFactory> = {
  home: (ctx) => createHomePage(ctx),
  command: (ctx, route) => {
    const r = route as Extract<Route, { name: "command" }>;
    if (r.tab === "page" && r.pageId) return createPageView(ctx, r.pageId);
    if (r.tab === "trash") return createTrashPage(ctx);
    if (r.tab === "ai") return createAiSettings(ctx);
    if (r.tab === "ports") return createPortsPage(ctx, r.view ?? "plugins");
    if (isCommandTab(r.tab)) return createCommandPage(ctx, r.tab, { workspace: true });
    return createWorkspaceHome(ctx);
  },
  apps: (ctx, route) => createAppsPage(ctx, route),
  app: (ctx, route) =>
    route.name === "app" && route.tab !== "map"
      ? createAppKnowledgePage(ctx, route as Extract<Route, { name: "app" }> & { tab: Exclude<AppTab, "map"> })
      : createAppsPage(ctx, route),
  runs: (ctx) => createRunsPage(ctx),
  run: (ctx, route) => createRunPage(ctx, route as Extract<Route, { name: "run" }>),
  phone: (ctx) => createPhonePage(ctx),
  devices: (ctx) => createDevicesPage(ctx),
  knowledge: (ctx) => createKnowledgePage(ctx),
  lab: (ctx, route) => createLabPage(ctx, route as Extract<Route, { name: "lab" }>),
  market: (ctx) => createMarketPage(ctx),
  remote: (ctx) => createRemotePage(ctx),
  attach: (ctx) => createAttachPage(ctx),
  settings: (ctx) => createSettingsPage(ctx),
};

const NAV: Array<{ section: "home" | "apps" | "runs" | "phone" | "devices" | "knowledge" | "lab" | "market"; label: string; icon: IconName; route: Route }> = [
  { section: "home", label: "Home", icon: "home", route: { name: "home" } },
  { section: "devices", label: "Devices", icon: "plug", route: { name: "devices" } },
  { section: "apps", label: "Apps", icon: "apps", route: { name: "apps" } },
  { section: "runs", label: "Runs", icon: "runs", route: { name: "runs" } },
  { section: "lab", label: "Lab", icon: "flask", route: { name: "lab" } },
  { section: "market", label: "Marketplace", icon: "store", route: { name: "market" } },
  { section: "knowledge", label: "Knowledge", icon: "book", route: { name: "knowledge" } },
  { section: "phone", label: "Phone", icon: "phone", route: { name: "phone" } },
];

export class GlassApp {
  private readonly options: GlassAppOptions;
  private readonly main = el("main", "glass-main");
  private readonly nav = el("nav", "sidebar-nav");
  private readonly footerNav = el("nav", "sidebar-nav sidebar-nav-bottom");
  private readonly picker = el("div", "device-picker");
  /** The top-left switcher: the current face's logo in front, the other one behind it. */
  private readonly brand = el("button", "brand brand-switch");
  private glassSidebar: HTMLElement | null = null;
  private workspace: WorkspaceSidebar | null = null;
  private aiPanel: AiPanel | null = null;
  private mode: Mode = "glass";
  private readonly last: Record<Mode, Route> = { glass: { name: "home" }, command: { name: "command", tab: "home" } };
  private unlistenKeys: (() => void) | null = null;
  private route: Route;
  private page: GlassPage | null = null;
  private pageKey = "";
  private devices: GlassDevice[] = [];
  private devicesError: GatewayError | null = null;
  private deviceId: string | null;
  private timer: unknown = null;
  private unlistenHash: (() => void) | null = null;
  private refreshing: Promise<void> | null = null;
  private firstLoad = true;
  private readonly hadRoute: boolean;

  constructor(options: GlassAppOptions) {
    this.options = options;
    this.route = parseRoute(options.location.hash);
    this.hadRoute = /^#\/[a-z]/.test(options.location.hash);
    this.deviceId = readStorage(options.storage, DEVICE_STORAGE_KEY);
  }

  async start(): Promise<void> {
    this.renderShell();
    this.unlistenHash = this.options.onHashChange(() => this.onHashChange());
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === "k" && this.mode === "command") {
        event.preventDefault();
        this.workspace?.find();
      }
      if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === "j" && this.mode === "command") {
        event.preventDefault();
        if (this.aiPanel?.isOpen()) this.aiPanel.close();
        else this.showAi(this.route.name === "command" && this.route.tab === "page" ? this.route.pageId ?? null : null);
      }
    };
    workspaceBus.handleAskAi((pageId) => this.showAi(pageId));
    globalThis.document?.addEventListener?.("keydown", onKey as EventListener);
    this.unlistenKeys = () => globalThis.document?.removeEventListener?.("keydown", onKey as EventListener);
    await this.refreshDevices();
    this.timer = this.options.setInterval(() => void this.refreshDevices(), DEVICE_REFRESH_MS);
    // Plan 31: the run/stop card, once, the first time Glass opens on this PC.
    const seen = await welcome.seen(this.options.client).catch(() => true);
    if (!seen) this.showWelcome(true);
  }

  private welcomeCard: HTMLElement | null = null;

  showWelcome(first: boolean): void {
    if (this.welcomeCard) return;
    const card = createWelcomeCard(() => {
      card.remove();
      this.welcomeCard = null;
      if (first) void welcome.markSeen(this.options.client).catch(() => undefined);
    });
    this.welcomeCard = card;
    this.options.root.append(card);
    (card.querySelector(".welcome-close") as HTMLElement | null)?.focus?.();
  }

  stop(): void {
    if (this.timer !== null) this.options.clearInterval(this.timer);
    this.timer = null;
    this.unlistenHash?.();
    this.unlistenKeys?.();
    this.workspace?.destroy();
    this.workspace = null;
    workspaceBus.handleAskAi(null);
    this.aiPanel?.destroy();
    this.aiPanel = null;
    this.page?.destroy();
    this.page = null;
  }

  refreshDevices(): Promise<void> {
    this.refreshing ??= this.loadDevices().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async loadDevices(): Promise<void> {
    const before = this.deviceSignature();
    try {
      this.devices = await listDevices(this.options.client);
      this.devicesError = null;
    } catch (error) {
      this.devices = [];
      this.devicesError = error instanceof GatewayError ? error : new GatewayError("GATEWAY_UNREACHABLE", String(error), 0, true);
    }
    const chosen = pickDevice(this.devices, this.deviceId);
    this.deviceId = chosen?.id ?? this.deviceId;
    if (this.firstLoad) {
      this.firstLoad = false;
      // Like WhatsApp Web: with nothing connected yet, open on Devices instead of an empty dashboard.
      if (!this.hadRoute && !this.devices.some((device) => deviceReadiness(device).ready)) {
        this.options.setHash(routeHref({ name: "devices" }));
        this.route = { name: "devices" };
      }
    }
    this.renderPicker();
    if (this.page?.update && this.pageKey === this.routeKey()) {
      this.page.update(this.context());
      return;
    }
    // Re-mount only when something a page depends on changed; polling must not reset a board mid-pan.
    if (this.deviceSignature() !== before || !this.page) this.renderPage(true);
  }

  private routeKey(): string {
    return `${this.route.name}:${this.route.name === "app" ? `${this.route.placeId}/${this.route.tab}/${this.route.route?.join(",") ?? ""}` : this.route.name === "run" ? this.route.runId : this.route.name === "lab" ? this.route.experimentId ?? "" : this.route.name === "command" ? `${this.route.tab}/${this.route.pageId ?? ""}/${this.route.view ?? ""}` : ""}`;
  }

  private deviceSignature(): string {
    const device = this.currentDevice();
    const ready = device ? JSON.stringify(deviceReadiness(device)) : "none";
    return `${device?.id ?? ""}|${ready}|${device?.mobileVersion ?? ""}|${this.devices.length}|${this.devicesError?.code ?? ""}`;
  }

  private currentDevice(): GlassDevice | null {
    return this.devices.find((device) => device.id === this.deviceId) ?? null;
  }

  private context(): GlassContext {
    return {
      client: this.options.client,
      version: this.options.version,
      devices: this.devices,
      device: this.currentDevice(),
      devicesError: this.devicesError,
      navigate: (route) => this.options.setHash(routeHref(route)),
      selectDevice: (id) => this.selectDevice(id),
      refreshDevices: () => this.refreshDevices(),
    };
  }

  private selectDevice(id: string): void {
    if (id === this.deviceId) return;
    this.deviceId = id;
    writeStorage(this.options.storage, DEVICE_STORAGE_KEY, id);
    this.renderPicker();
    this.renderPage(true);
  }

  private onHashChange(): void {
    this.route = parseRoute(this.options.location.hash);
    this.renderPage(false);
  }

  /** The switcher shows the face you are on in front. Pressing it goes to the other face, where you left it. */
  private renderBrand(): void {
    const command = this.mode === "command";
    const stack = el("span", "brand-stack");
    const back = el("span", "brand-back");
    back.append(command ? cycloneLogo() : commandLogo());
    const front = el("span", "brand-front");
    front.append(command ? commandLogo() : cycloneLogo());
    stack.append(back, front);
    const names = el("span", "brand-names");
    const sub = el("span", "brand-version", command ? "Your Cyclone workspace" : `v${this.options.version.split(" ")[0]}`);
    sub.title = `Cyclone Glass ${this.options.version}`;
    names.append(el("span", "brand-name", command ? "Command Center" : "Cyclone Glass"), sub);
    this.brand.type = "button";
    this.brand.dataset.mode = this.mode;
    const other = command ? "Cyclone Glass (phones, apps, runs and settings)" : "the Command Center (pages, tasks, routines and plans)";
    this.brand.setAttribute("aria-label", `${command ? "Command Center" : "Cyclone Glass"}. Switch to ${other}`);
    this.brand.title = `Switch to ${command ? "Cyclone Glass" : "the Command Center"}`;
    setChildren(this.brand, stack, names, el("span", "brand-swap", "⇄"));
  }

  private switchMode(): void {
    const target = this.mode === "command" ? this.last.glass : this.last.command;
    this.options.setHash(routeHref(target));
  }

  /** Ask AI: the panel beside the workspace, about one page or the whole workspace. */
  private showAi(pageId: string | null): void {
    if (this.mode !== "command") return;
    this.aiPanel ??= createAiPanel(() => this.context());
    if (!this.aiPanel.element.parentNode) this.options.root.append(this.aiPanel.element);
    this.aiPanel.open(pageId);
  }

  /** Swap the sidebar when the face changes: the Glass nav, or the Command Center's workspace sidebar. */
  private applyMode(): void {
    const mode = modeOf(this.route);
    this.last[mode] = this.route;
    if (mode === this.mode && (mode === "glass" ? this.glassSidebar?.parentNode : this.workspace)) {
      this.workspace?.setRoute(this.route);
      return;
    }
    this.mode = mode;
    this.renderBrand();
    let sidebar: HTMLElement;
    if (mode === "command") {
      this.workspace ??= createWorkspaceSidebar(() => this.context(), this.brand, this.options.root);
      this.workspace.element.insertBefore(this.brand, this.workspace.element.firstChild ?? null);
      this.workspace.setRoute(this.route);
      sidebar = this.workspace.element;
    } else {
      this.glassSidebar?.insertBefore(this.brand, this.glassSidebar.firstChild ?? null);
      sidebar = this.glassSidebar!;
    }
    this.options.root.className = `glass-app mode-${mode}`;
    if (mode !== "command") this.aiPanel?.close();
    if (!this.aiPanel?.isOpen()) this.options.root.classList.remove("ai-docked");
    const panel = mode === "command" && this.aiPanel ? [this.aiPanel.element] : [];
    setChildren(this.options.root, sidebar, this.main, ...panel, ...(this.welcomeCard ? [this.welcomeCard] : []));
  }

  private renderShell(): void {
    const sidebar = el("aside", "glass-sidebar");
    this.glassSidebar = sidebar;
    this.brand.addEventListener("click", () => this.switchMode());
    this.mode = modeOf(this.route) === "glass" ? "command" : "glass"; // forces the first applyMode to draw
    const brand = this.brand;

    for (const item of NAV) this.nav.append(this.navItem(item.section, item.label, item.icon, item.route));
    this.footerNav.append(
      this.navItem("remote", "Remote MCP", "send", { name: "remote" }),
      this.navItem("attach", "ChatGPT Attach", "chat", { name: "attach" }),
      this.navItem("settings", "Settings", "settings", { name: "settings" }),
    );
    const help = el("button", "nav-item nav-help");
    help.type = "button";
    help.append(icon("help"), el("span", "nav-label", "Start and stop"));
    help.addEventListener("click", () => this.showWelcome(false));
    this.footerNav.append(help);

    const note = el("p", "sidebar-note", "Cyclone thinks on the phone. Glass shows what it knows and did.");
    sidebar.append(brand, this.picker, this.nav, el("div", "sidebar-spacer"), this.footerNav, note);
    this.renderPicker();
    this.applyMode();
  }

  private navItem(section: string, label: string, name: IconName, route: Route): HTMLAnchorElement {
    const item = el("a", "nav-item");
    item.href = routeHref(route);
    item.dataset.section = section;
    item.append(icon(name), el("span", "nav-label", label));
    return item;
  }

  private renderPicker(): void {
    const label = el("label", "picker-label", "Phone");
    if (!this.devices.length) {
      const empty = el("div", "picker-empty", this.devicesError ? "Gateway unreachable" : "No phone connected");
      setChildren(this.picker, label, empty);
      return;
    }
    const select = el("select", "picker-select");
    select.setAttribute("aria-label", "Phone");
    for (const device of this.devices) {
      const option = el("option", undefined, device.name);
      option.value = device.id;
      option.selected = device.id === this.deviceId;
      select.append(option);
    }
    select.addEventListener("change", () => this.selectDevice(select.value));
    const device = this.currentDevice();
    const status = el("div", "picker-status");
    if (device) {
      const readiness = deviceReadiness(device);
      status.append(el("span", `status-dot ${readiness.ready ? "dot-ok" : "dot-warn"}`));
      status.append(el("span", undefined, readiness.ready ? `Cyclone ${device.mobileVersion}` : shortReason(readiness.reason)));
    }
    setChildren(this.picker, label, select, status);
  }

  private renderPage(force: boolean): void {
    this.applyMode();
    const key = this.routeKey();
    if (!force && key === this.pageKey && this.page) return;
    this.page?.destroy();
    this.pageKey = key;
    this.page = PAGES[this.route.name](this.context(), this.route);
    setChildren(this.main, this.page.element);
    const section = sectionOf(this.route);
    for (const item of [...this.nav.children, ...this.footerNav.children] as HTMLElement[]) {
      item.classList.toggle("active", item.dataset.section === section);
      if (item.dataset.section === section) item.setAttribute("aria-current", "page");
      else item.removeAttribute("aria-current");
    }
  }
}

function shortReason(reason: NotReadyReason): string {
  switch (reason) {
    case "disconnected":
      return "Not connected";
    case "unpaired":
      return "Not connected to Glass";
    case "connecting":
      return "Reconnecting";
    case "needs-update":
      return "Update Cyclone";
    case "version-unknown":
      return "Waiting for Cyclone";
  }
}

function readStorage(storage: GlassAppOptions["storage"], key: string): string | null {
  try {
    return storage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

function writeStorage(storage: GlassAppOptions["storage"], key: string, value: string): void {
  try {
    storage?.setItem(key, value);
  } catch {
    /* per-tab convenience only */
  }
}
