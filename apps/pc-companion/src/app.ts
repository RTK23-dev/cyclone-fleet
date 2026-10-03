import {
  canPreserveFocusedPage,
  initialCompanionState,
  phoneSupportsGlassAtlas,
  reduceCompanionState,
  type AppRoute,
  type CompanionState,
} from "./core/fleet.js";
import { TopologyRefreshGate } from "./core/topologyRefresh.js";
import type { DesktopDevice, DesktopService, FleetWsEvent } from "./services/types.js";
import {
  createGlassRuntime,
  GLASS_OPERATOR_REQUEST_REASON,
  readDeviceMobileVersion,
  resolveGlassSessionId,
  type GlassRuntime,
  type GlassSessionPlane,
} from "./services/glassRuntime.js";
import { DEFAULT_FOREGROUND_SESSION_ID, isSessionFabricEvent } from "./core/sessionTiles.js";
import { createChatgptAttachPage } from "./pages/chatgptAttachPage.js";
import { createConnectionsPage } from "./pages/connectionsPage.js";
import { createAutomationsPage } from "./pages/automationsPage.js";
import { createAskPage } from "./pages/askPage.js";
import { createFleetPage } from "./pages/fleetPage.js";
import { createFocusedPhonePage } from "./pages/focusedPhonePage.js";
import { createHomePage } from "./pages/homePage.js";
import { createMapsPage } from "./pages/mapsPage.js";
import { createSettingsPage } from "./pages/settingsPage.js";
import { createVaultPage } from "./pages/vaultPage.js";
import { PairingModal } from "./ui/pairingModal.js";
import { button, el } from "./ui/dom.js";

interface PageHandle {
  element: HTMLElement;
  destroy(): void;
  updateDevice?(device: DesktopDevice): void;
  applyFleetEvent?(event: FleetWsEvent): void;
}

export class CyclonePcCompanionApp {
  private state: CompanionState = initialCompanionState();
  private currentPage: PageHandle | null = null;
  private pollTimer: number | null = null;
  private eventRefreshTimer: number | null = null;
  private fleetUnsubscribe: (() => void) | null = null;
  private pairingModal: PairingModal | null = null;
  private deviceSignature = "";
  private gatewayError: string | null = null;
  private readonly topologyRefresh = new TopologyRefreshGate();
  private readonly content = el("main", "app-content");
  private readonly navButtons = new Map<AppRoute, HTMLButtonElement>();
  private topbarStatus: HTMLElement | null = null;
  private notificationPanel: HTMLElement | null = null;
  private notificationBadge: HTMLElement | null = null;
  private profileMenu: HTMLDetailsElement | null = null;

  constructor(private readonly root: HTMLElement, private readonly service: DesktopService) {}

  async start(): Promise<void> {
    this.renderShell();
    await this.refreshDevices(true);
    // Normal updates are pushed from ADB's topology event stream. The 20 second list refresh is
    // only a very cheap UI recovery net and does not itself execute ADB commands.
    this.fleetUnsubscribe = this.service.watchFleet((event) => {
      if (event && isSessionFabricEvent(event) && this.currentPage?.applyFleetEvent) {
        this.currentPage.applyFleetEvent(event);
        return;
      }
      this.scheduleEventRefresh();
    });
    this.pollTimer = window.setInterval(() => void this.refreshDevices(false), 20_000);
  }

  destroy(): void {
    if (this.pollTimer != null) window.clearInterval(this.pollTimer);
    if (this.eventRefreshTimer != null) window.clearTimeout(this.eventRefreshTimer);
    this.pollTimer = null;
    this.eventRefreshTimer = null;
    this.fleetUnsubscribe?.();
    this.fleetUnsubscribe = null;
    this.currentPage?.destroy();
    this.currentPage = null;
    this.pairingModal?.close();
    this.pairingModal = null;
    this.root.replaceChildren();
  }

  private scheduleEventRefresh(): void {
    if (this.eventRefreshTimer != null) return;
    this.eventRefreshTimer = window.setTimeout(() => {
      this.eventRefreshTimer = null;
      void this.refreshDevices(false);
    }, 100);
  }

  private renderShell(): void {
    const shell = el("div", "app-shell");
    const topbar = el("header", "app-topbar");
    const brand = el("div", "brand");
    brand.append(el("div", "cyclone-mark"), el("div", "brand-name", "Cyclone Glass"));
    const nav = el("nav", "primary-nav");
    nav.setAttribute("aria-label", "Cyclone Glass");

    const entries: Array<[Exclude<AppRoute, "focused">, string, string]> = [
      ["home", "⌂", "Home"],
      ["fleet", "▣", "Control"],
      ["ask", "?", "Ask"],
      ["maps", "▦", "Maps"],
      ["vault", "◉", "Vault"],
      ["automations", "↻", "Tasks"],
      ["connections", "◇", "Connections"],
      ["chatgpt", "✦", "ChatGPT"],
    ];
    for (const [route, symbol, label] of entries) {
      const item = button("", "nav-button");
      if (symbol) item.append(el("span", "nav-icon", symbol));
      item.append(el("span", "nav-label", label));
      item.addEventListener("click", () => this.navigate(route));
      nav.append(item);
      this.navButtons.set(route, item);
    }

    const actions = el("div", "topbar-actions");
    this.topbarStatus = el("div", "topbar-status");
    this.topbarStatus.append(
      el("span", `backend-dot ${this.service.mode}`),
      el("span", "topbar-status-copy", this.service.mode === "mock" ? "Mock workspace" : "Local companion"),
    );

    const notifications = el("details", "topbar-menu notification-menu") as HTMLDetailsElement;
    const notificationButton = el("summary", "topbar-icon-button");
    notificationButton.setAttribute("aria-label", "Notifications");
    notificationButton.append(el("span", "bell-icon"));
    this.notificationBadge = el("span", "notification-badge");
    notificationButton.append(this.notificationBadge);
    this.notificationPanel = el("div", "topbar-popover notification-panel");
    notifications.append(notificationButton, this.notificationPanel);

    const profile = el("details", "topbar-menu profile-menu") as HTMLDetailsElement;
    this.profileMenu = profile;
    const profileButton = el("summary", "profile-button");
    profileButton.setAttribute("aria-label", "Cyclone profile and settings");
    profileButton.append(el("span", "profile-avatar", "C"), el("span", "profile-name", "Cyclone"), el("span", "profile-chevron", "⌄"));
    const profilePanel = el("div", "topbar-popover profile-panel");
    const connectionItem = button("Connections", "profile-menu-item");
    connectionItem.addEventListener("click", () => this.navigate("connections"));
    const settingsItem = button("Settings & diagnostics", "profile-menu-item");
    settingsItem.addEventListener("click", () => this.navigate("settings"));
    profilePanel.append(
      el("div", "profile-panel-heading", "Workspace"),
      connectionItem,
      settingsItem,
      el("div", "profile-version", `Cyclone Glass · v${__CYCLONE_PC_VERSION__}`),
    );
    profile.append(profileButton, profilePanel);
    actions.append(this.topbarStatus, notifications, profile);
    topbar.append(brand, nav, actions);
    shell.append(topbar, this.content);
    this.root.replaceChildren(shell);
    this.updateTopbarStatus();
    this.renderPage();
  }

  private async refreshDevices(forceRender: boolean): Promise<void> {
    if (!this.topologyRefresh.begin(forceRender)) return;
    try {
      const devices = await this.service.listDevices();
      this.gatewayError = null;
      this.applyDevices(devices, forceRender);
    } catch (error) {
      this.gatewayError = friendlyGatewayError(error);
      this.updateTopbarStatus();
      // Keep last known inventory and a mounted healthy focused stream. A discovery failure is
      // evidence about the Gateway, not evidence that every phone vanished.
      if (forceRender && this.state.route !== "focused") this.renderPage();
    } finally {
      const nextForceRender = this.topologyRefresh.finish();
      if (nextForceRender != null) void this.refreshDevices(nextForceRender);
    }
  }

  private async scanForPhones(): Promise<number> {
    try {
      const devices = await this.service.scanDevices();
      this.gatewayError = null;
      this.applyDevices(devices, true);
      return devices.length;
    } catch (error) {
      this.gatewayError = friendlyGatewayError(error);
      this.updateTopbarStatus();
      throw error;
    }
  }

  private applyDevices(devices: DesktopDevice[], forceRender: boolean): void {
    const signature = devices.map(deviceSignature).join("|");
    const changed = signature !== this.deviceSignature;
    const preserveFocusedPage = this.currentPage != null && canPreserveFocusedPage(this.state, devices);
    this.deviceSignature = signature;
    this.state = reduceCompanionState(this.state, { type: "devices_updated", devices });
    this.updateTopbarStatus();
    // Fleet heartbeat/topology updates must not tear down a healthy focused stream. The stream
    // controller owns its own recovery and remains mounted until the device disappears or the
    // user navigates away.
    if ((forceRender || changed) && !preserveFocusedPage) this.renderPage();
    if (changed && preserveFocusedPage && this.state.focusedDeviceId) {
      const focused = devices.find((device) => device.id === this.state.focusedDeviceId);
      if (focused) this.currentPage?.updateDevice?.(focused);
    }
  }

  private navigate(route: Exclude<AppRoute, "focused">): void {
    if (this.profileMenu) this.profileMenu.open = false;
    this.state = reduceCompanionState(this.state, { type: "navigate", route });
    this.renderPage();
  }

  private focusDevice(device: DesktopDevice): void {
    this.state = reduceCompanionState(this.state, { type: "focus_device", deviceId: device.id });
    this.renderPage();
  }

  private backToFleet(): void {
    this.state = reduceCompanionState(this.state, { type: "back_to_fleet" });
    this.renderPage();
  }

  private openPairing(device: DesktopDevice): void {
    if (this.pairingModal) return;
    const modal = new PairingModal(
      this.service,
      device,
      () => void this.refreshDevices(true),
      () => { this.pairingModal = null; },
    );
    this.pairingModal = modal;
    void modal.open().then((element) => document.body.append(element)).catch(() => {
      this.pairingModal = null;
    });
  }

  private renderPage(): void {
    this.currentPage?.destroy();
    this.currentPage = null;
    this.updateNavState();

    if (this.state.route === "focused") {
      const device = this.state.devices.find((candidate) => candidate.id === this.state.focusedDeviceId);
      if (device) {
        this.currentPage = createFocusedPhonePage(
          this.service,
          device,
          () => this.backToFleet(),
          () => this.navigate("settings"),
          (target) => this.openPairing(target),
          (sessionId) => {
            this.state = reduceCompanionState(this.state, { type: "focus_session", sessionId });
          },
        );
      } else {
        this.state = reduceCompanionState(this.state, { type: "back_to_fleet" });
      }
    }
    if (!this.currentPage && this.state.route === "home") {
      this.currentPage = createHomePage(
        this.state.devices,
        () => this.navigate("fleet"),
        () => this.navigate("automations"),
        () => this.navigate("connections"),
        () => this.navigate("chatgpt"),
      );
    }
    if (!this.currentPage && this.state.route === "ask") {
      const { version, sessionId, sessionPlane, demo, mapping, runtime } = this.glassContext();
      this.currentPage = createAskPage({
        devices: this.state.devices,
        mobileVersion: version ?? undefined,
        onOpenControl: () => this.navigate("fleet"),
        sessionId,
        sessionPlane,
        previewSnapshots: demo,
        // The same local, paired gateway client that commands mapping also sends Ask goals.
        ask: mapping && runtime ? runtime.atlas : undefined,
      });
    }
    if (!this.currentPage && this.state.route === "maps") {
      const { version, sessionId, sessionPlane, demo, loadSource, mapping } = this.glassContext();
      this.currentPage = createMapsPage({
        phoneVersion: version,
        loadSource,
        demo,
        sessionId,
        sessionPlane,
        mapping,
        onOpenControl: () => this.navigate("fleet"),
      });
    }
    if (!this.currentPage && this.state.route === "vault") {
      const { version, demo, loadSlots, onRequestSlot } = this.glassContext();
      this.currentPage = createVaultPage({
        devices: this.state.devices,
        mobileVersion: version,
        loadSlots,
        onRequestSlot,
        previewSlots: demo,
      });
    }
    if (!this.currentPage && this.state.route === "automations") {
      this.currentPage = createAutomationsPage(this.state.devices, (device) => {
        if (device) this.focusDevice(device); else this.navigate("fleet");
      }, this.service);
    }
    if (!this.currentPage && this.state.route === "connections") {
      this.currentPage = createConnectionsPage(this.service);
    }
    if (!this.currentPage && this.state.route === "chatgpt") {
      this.currentPage = createChatgptAttachPage(this.service);
    }
    if (!this.currentPage && this.state.route === "settings") {
      this.currentPage = createSettingsPage(this.service, this.state.devices);
    }
    if (!this.currentPage && this.state.route === "fleet") {
      this.currentPage = createFleetPage(
        this.service,
        this.state.devices,
        (device) => this.focusDevice(device),
        (device) => this.openPairing(device),
        () => this.scanForPhones(),
        () => this.navigate("settings"),
        { offline: this.gatewayError != null, message: this.gatewayError ?? undefined },
      );
    }
    if (!this.currentPage) {
      this.state = reduceCompanionState(this.state, { type: "navigate", route: "home" });
      this.currentPage = createHomePage(
        this.state.devices,
        () => this.navigate("fleet"),
        () => this.navigate("automations"),
        () => this.navigate("connections"),
        () => this.navigate("chatgpt"),
      );
    }
    this.content.replaceChildren(this.currentPage.element);
  }

  private glassContext(): {
    device: DesktopDevice | undefined;
    version: string | null;
    sessionId: string;
    sessionPlane: GlassSessionPlane;
    demo: boolean;
    runtime: GlassRuntime | null;
    loadSource: (() => Promise<import("./maps/mockAtlas.js").MapsDataSource>) | undefined;
    loadSlots: (() => Promise<import("./core/fleet.js").GlassVaultSlot[]>) | undefined;
    onRequestSlot: ((slotId: string) => void) | undefined;
    mapping: import("./maps/mappingWatcher.js").MappingOps | undefined;
  } {
    const device = selectGlassDevice(this.state.devices, this.state.focusedDeviceId);
    const version = device ? (readDeviceMobileVersion(device) ?? null) : null;
    const sessionId = resolveGlassSessionId(this.state.focusedSessionId);
    const sessionPlane: GlassSessionPlane = sessionId !== DEFAULT_FOREGROUND_SESSION_ID ? "session_kernel_vd" : "foreground";
    const demo = this.service.mode === "mock";
    const gateway = this.service.glassGateway;
    const httpBase = String(gateway?.httpBase ?? "").trim();
    const bearer = gateway && typeof gateway.getBearer === "function" ? String(gateway.getBearer() ?? "").trim() : "";
    const atlasReady = version != null && phoneSupportsGlassAtlas(version);
    const runtime = device && gateway && httpBase && bearer
      ? createGlassRuntime({
          httpBase,
          getBearer: gateway.getBearer,
          getDeviceId: () => device.id,
          getSessionId: () => sessionId,
          getPhoneVersion: () => version,
        })
      : null;
    const live = Boolean(runtime && atlasReady && sessionId);
    return {
      device,
      version,
      sessionId,
      sessionPlane,
      demo,
      runtime,
      loadSource: live && runtime ? () => loadMapsSourceBoth(runtime) : undefined,
      // Mapping is a local operator act on this PC's paired phone; the phone walks the app.
      mapping: live && runtime ? runtime.atlas : undefined,
      loadSlots: live && runtime ? () => loadVaultSlotsLive(runtime) : undefined,
      onRequestSlot: live && runtime ? (slotId) => requestVaultSlot(runtime, slotId) : undefined,
    };
  }

  private updateNavState(): void {
    const activeRoute = this.state.route === "focused" ? "fleet" : this.state.route;
    for (const [route, node] of this.navButtons) {
      const active = route === activeRoute;
      node.classList.toggle("active", active);
      node.setAttribute("aria-current", active ? "page" : "false");
    }
    this.profileMenu?.classList.toggle("active", activeRoute === "connections" || activeRoute === "settings");
  }

  private updateTopbarStatus(): void {
    if (!this.topbarStatus || !this.notificationPanel || !this.notificationBadge) return;
    const ready = this.state.devices.filter((device) => device.state === "READY").length;
    const attention = this.state.devices.filter((device) => ["DISCONNECTED", "ATTENTION", "UNAUTHORIZED"].includes(device.state));
    const copy = this.topbarStatus.querySelector<HTMLElement>(".topbar-status-copy");
    if (copy) copy.textContent = this.gatewayError
      ? "Gateway needs attention"
      : this.state.devices.length === 0
      ? "No phones"
      : `${ready}/${this.state.devices.length} ready`;
    const alertCount = attention.length + (this.gatewayError ? 1 : 0);
    this.notificationBadge.textContent = alertCount ? String(alertCount) : "";
    this.notificationBadge.hidden = alertCount === 0;
    this.notificationPanel.replaceChildren(el("div", "popover-heading", "Notifications"));
    if (this.gatewayError) {
      const gateway = el("button", "notification-item") as HTMLButtonElement;
      gateway.type = "button";
      gateway.append(el("span", "notification-device", "Local Gateway"), el("span", "notification-copy", this.gatewayError));
      gateway.addEventListener("click", () => this.navigate("settings"));
      this.notificationPanel.append(gateway);
    }
    if (attention.length === 0) {
      this.notificationPanel.append(
        el("div", "notification-item positive", "All connected phones look healthy."),
        el("div", "notification-time", "Cyclone keeps watching connection health."),
      );
      return;
    }
    for (const device of attention.slice(0, 5)) {
      const item = el("button", "notification-item") as HTMLButtonElement;
      item.type = "button";
      item.append(el("span", "notification-device", device.name), el("span", "notification-copy", device.connectionLabel || device.state));
      item.addEventListener("click", () => device.paired ? this.focusDevice(device) : this.navigate("connections"));
      this.notificationPanel.append(item);
    }
  }
}

function deviceSignature(device: DesktopDevice): string {
  return [
    device.id,
    device.name,
    device.state,
    device.paired,
    device.connectionLabel,
    device.video.mode,
    device.video.width,
    device.video.height,
    device.video.rotationDegrees,
    device.capabilities.clipboard,
    device.capabilities.keyboard,
    JSON.stringify(device.connectionHealth ?? {}),
    JSON.stringify(device.planes ?? {}),
    JSON.stringify(device.readiness ?? {}),
    JSON.stringify(device.operatorHealth ?? {}),
    device.mobileVersion ?? "",
  ].join(":");
}

function friendlyGatewayError(error: unknown): string {
  const message = error instanceof Error ? error.message.trim() : "";
  if (/401|403|token|session/i.test(message)) return "Local session verification failed. Reopen PC Companion to start a fresh protected session.";
  if (/fetch|network|offline|gateway|connect/i.test(message)) return "The local Gateway sidecar is not responding. Retry discovery, then reopen PC Companion if needed.";
  return message ? message.slice(0, 180) : "Cyclone could not refresh local phone inventory.";
}

function selectGlassDevice(devices: DesktopDevice[], focusedDeviceId: string | null): DesktopDevice | undefined {
  if (focusedDeviceId) {
    const focused = devices.find((candidate) => candidate.id === focusedDeviceId);
    if (focused) return focused;
  }
  return devices.find((candidate) => candidate.state === "READY" && candidate.paired)
    ?? devices.find((candidate) => candidate.paired)
    ?? devices[0];
}

async function loadMapsSourceBoth(runtime: GlassRuntime): Promise<import("./maps/mockAtlas.js").MapsDataSource> {
  const [live, mapping] = await Promise.all([
    runtime.loadMapsSource("live"),
    runtime.loadMapsSource("mapping"),
  ]);
  return {
    listSummaries: (persona) => (persona === "mapping" ? mapping : live).listSummaries(persona),
    getDocument: (placeId, persona) => (persona === "mapping" ? mapping : live).getDocument(placeId, persona),
  };
}

async function loadVaultSlotsLive(runtime: GlassRuntime): Promise<import("./core/fleet.js").GlassVaultSlot[]> {
  const catalog = await runtime.atlas.places();
  const slots: import("./core/fleet.js").GlassVaultSlot[] = [];
  const seen = new Set<string>();
  for (const summary of catalog.places ?? []) {
    const placeId = summary?.place?.placeId;
    if (!placeId || seen.has(placeId)) continue;
    seen.add(placeId);
    const placeLabel = summary.place.label || placeId;
    slots.push(...await runtime.loadVaultSlots(placeId, "live", placeLabel));
  }
  return slots;
}

function requestVaultSlot(runtime: GlassRuntime, slotId: string): void {
  const parsed = parseVaultSlotId(slotId);
  if (!parsed) return;
  void runtime.requestSecret(parsed.placeId, "live", parsed.slot, GLASS_OPERATOR_REQUEST_REASON).catch(() => {
    /* swallow — never log payloads, bearers, or slot values */
  });
}

function parseVaultSlotId(slotId: string): { placeId: string; slot: string } | null {
  const raw = String(slotId ?? "").trim();
  const separator = raw.lastIndexOf(":");
  if (separator <= 0 || separator === raw.length - 1) return null;
  return { placeId: raw.slice(0, separator), slot: raw.slice(separator + 1) };
}
