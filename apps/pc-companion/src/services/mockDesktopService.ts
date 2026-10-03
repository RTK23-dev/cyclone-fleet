import type {
  ConnectionDiagnosticBundle,
  ConnectorActionResult,
  ConnectorCard,
  ControlResult,
  DesktopDevice,
  DesktopRuntimeStatus,
  DesktopService,
  DeviceControlAction,
  DeviceSessionDescriptor,
  DeviceSessionList,
  DeviceSessionResult,
  FleetWsEvent,
  Layer2Operation,
  Layer2Status,
  Layer2Workspace,
  PairBeginResult,
  PairConfirmResult,
  PairQrConfirmResult,
  StreamDiagnosticEvent,
  StreamProfile,
  McpTunnelMode,
  McpTunnelSmokeResult,
  McpTunnelStatus,
  McpTunnelToken,
  ChatgptAttachConfig,
  ChatgptAttachResources,
  ChatgptSyncResult,
} from "./types.js";
import {
  emptyChatgptAttachConfig,
  emptyShareStatus,
  localCloudControlBase,
  localStubSession,
  publicControlApi,
  publicShareControlApi,
  type ChatgptShareStatus,
} from "../core/chatgptAttach.js";
import { bindLayer2Status, LAYER2_PROTOCOL } from "../core/layer2.js";
import { stoppedTunnelStatus } from "../core/mcpTunnel.js";
import { bindSessionTile, DEFAULT_FOREGROUND_SESSION_ID, isDefaultForegroundSession, jpegFocusTarget, readExactSessionSnapshotHeaders } from "../core/sessionTiles.js";

const MOCK_CODE = "NOVA";

export class MockDesktopService implements DesktopService {
  readonly mode = "mock" as const;
  private devices: DesktopDevice[];
  private pairings = new Map<string, PairBeginResult>();
  private pairingSequence = 0;
  private sessionSequence = 0;
  private sessions = new Map<string, DeviceSessionDescriptor[]>();
  private layer2 = new Map<string, MockLayer2State>();
  private fleetListeners: Array<(event?: FleetWsEvent) => void> = [];
  private tunnel: McpTunnelStatus = stoppedTunnelStatus(
    "Mock Settings terminal. Packaged Cyclone One starts the real HTTPS tunnel from here.",
  );
  private chatgptConfig: ChatgptAttachConfig = emptyChatgptAttachConfig();
  private chatgptSecrets = new Map<string, string>();
  private chatgptShare: ChatgptShareStatus = emptyShareStatus("http://127.0.0.1:8765/cloud");

  constructor(deviceCount = 4) {
    this.devices = createMockDevices(deviceCount);
    for (const device of this.devices) {
      if (!device.paired) continue;
      this.sessions.set(device.id, seedMockSessions(device.id));
      this.layer2.set(device.id, seedMockLayer2());
    }
  }

  async listDevices(): Promise<DesktopDevice[]> { return this.devices.map(copyDevice); }
  async scanDevices(): Promise<DesktopDevice[]> { return this.listDevices(); }
  watchFleet(onChange: (event?: FleetWsEvent) => void): () => void {
    this.fleetListeners.push(onChange);
    return () => {
      this.fleetListeners = this.fleetListeners.filter((listener) => listener !== onChange);
    };
  }

  emitFleetEvent(event: FleetWsEvent): void {
    for (const listener of this.fleetListeners) listener(event);
  }

  async pairBegin(deviceId: string): Promise<PairBeginResult> {
    const device = this.requireDevice(deviceId);
    device.state = "PAIRING";
    const pairingId = `mock-${deviceId}-${++this.pairingSequence}`;
    const result = {
      pairingId,
      expiresAtEpochMs: Date.now() + 60_000,
      qrAvailable: true,
      qrPayload: `cyclone://pair?challenge=${encodeURIComponent(pairingId)}&nonce=mock-nonce-abcdefghijklmnop`,
      preflight: {
        appRunning: true,
        accessibilityEnabled: true,
        accessibilityServiceConfigured: true,
      },
    };
    this.pairings.set(deviceId, result);
    return result;
  }

  async pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    const pairing = this.pairings.get(deviceId);
    if (!pairing || pairing.pairingId !== pairingId) return { ok: false, reason: "STALE_CODE" };
    if (Date.now() >= pairing.expiresAtEpochMs) return { ok: false, reason: "EXPIRED" };
    if (code.toUpperCase() !== MOCK_CODE) return { ok: false, reason: "INVALID_CODE" };
    const device = this.requireDevice(deviceId);
    device.paired = true;
    device.state = "READY";
    device.connectionLabel = "Ready";
    this.pairings.delete(deviceId);
    return { ok: true, device: copyDevice(device) };
  }

  async sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult> {
    const device = this.requireDevice(deviceId);
    if (!device.paired) return { ok: false, deviceId };
    if (device.state === "DISCONNECTED" && action.type !== "reconnect") return { ok: false, deviceId };
    if (action.type === "wake") { device.state = "READY"; device.connectionLabel = "Ready"; }
    if (action.type === "disconnect") { device.state = "DISCONNECTED"; device.connectionLabel = "Reconnecting"; }
    else if (action.type === "reconnect") {
      device.state = "READY";
      device.connectionLabel = "Ready";
      device.connectionHealth = healthyConnectionHealth();
    }
    else if (action.type === "clipboard_sync") device.capabilities.clipboardSync = action.enabled;
    else if (action.type === "yield_ai" || action.type === "take_human") {
      const sessionId = action.sessionId?.trim() || undefined;
      return this.applyInputOwner(device, action.type === "yield_ai" ? "AI" : "HUMAN", sessionId);
    }
    return { ok: true, deviceId, verification: `mock-${action.type}` };
  }

  async sendSessionControl(deviceId: string, sessionId: string, kind: "yield_ai" | "take_human"): Promise<ControlResult> {
    const device = this.requireDevice(deviceId);
    if (!device.paired) return { ok: false, deviceId };
    if (device.state === "DISCONNECTED") return { ok: false, deviceId };
    const id = sessionId.trim();
    if (!id) throw new Error("Mock session not found");
    return this.applyInputOwner(device, kind === "yield_ai" ? "AI" : "HUMAN", id);
  }

  async listDeviceSessions(deviceId: string): Promise<DeviceSessionList> {
    this.requireDevice(deviceId);
    return { protocol: "cyclone-one-session/1", deviceId, sessions: this.deviceSessions(deviceId).map(copySession) };
  }

  async startDeviceSession(deviceId: string, packageName: string): Promise<DeviceSessionResult> {
    this.requireDevice(deviceId);
    const session: DeviceSessionDescriptor = {
      sessionId: `workspace-${++this.sessionSequence}`,
      displayId: 2 + this.sessionSequence,
      targetPackage: packageName,
      backend: "Session Kernel VD",
      inputOwner: "AI",
      state: "RUNNING",
      executable: true,
      frameHealthy: true,
    };
    this.deviceSessions(deviceId).push(session);
    this.emitSessionFabric("session.added", deviceId, session);
    return { protocol: "cyclone-one-session/1", deviceId, session: copySession(session) };
  }

  async pauseDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    // Pause is lifecycle-only: it never rewrites inputOwner, including on a locked phone.
    return this.mutateSession(deviceId, sessionId, (session) => { session.state = "PAUSED"; });
  }

  async resumeDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.mutateSession(deviceId, sessionId, (session) => { session.state = "RUNNING"; });
  }

  async handoffDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.mutateSession(deviceId, sessionId, (session) => { session.inputOwner = "HUMAN"; });
  }

  async stopDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    const result = this.mutateSession(deviceId, sessionId, (session) => { session.state = "STOPPED"; });
    this.sessions.set(deviceId, this.deviceSessions(deviceId).filter((session) => session.sessionId !== sessionId));
    this.emitSessionFabric("session.removed", deviceId, result.session);
    return result;
  }

  async listLayer2Workspaces(deviceId: string): Promise<Layer2Status> {
    this.requireDevice(deviceId);
    return this.layer2Status(deviceId);
  }

  async layer2Workspace(deviceId: string, operation: Layer2Operation, params: Record<string, unknown> = {}): Promise<Layer2Status> {
    this.requireDevice(deviceId);
    const state = this.layer2State(deviceId);
    if (operation === "list") return this.layer2Status(deviceId);
    if (operation === "pause") {
      pauseLayer2(state);
      return this.layer2Status(deviceId);
    }
    if (operation === "release") {
      if (state.gated) throw new Error("GATE: resolve review before clearing selection");
      pauseLayer2(state);
      state.selected = null;
      state.armed = [];
      state.goals = {};
      return this.layer2Status(deviceId);
    }
    if (operation === "switch") return this.switchLayer2(deviceId, params);
    if (operation === "register") {
      if (state.gated) throw new Error("GATE: review required");
      const workspace = workspaceFromParams(params);
      if (state.selected === workspace.id) throw new Error("Pause and clear selection before editing this workspace");
      const existing = state.workspaces.findIndex((row) => row.id === workspace.id);
      if (existing >= 0) state.workspaces[existing] = workspace;
      else state.workspaces.push(workspace);
      return this.layer2Status(deviceId);
    }
    if (operation === "arm") {
      if (state.gated) throw new Error("GATE: review required");
      const id = stringParam(params, "id");
      if (!state.workspaces.some((row) => row.id === id)) throw new Error("Unknown workspace");
      if (!state.armed.includes(id)) state.armed.push(id);
      const goal = typeof params.goal === "string" ? params.goal.trim().slice(0, 500) : "";
      if (goal) state.goals[id] = goal;
      return this.layer2Status(deviceId);
    }
    if (operation === "next") {
      if (state.gated) {
        pauseLayer2(state);
        throw new Error("GATE: queue paused");
      }
      const id = state.armed[0];
      if (!id) throw new Error("QUEUE_EMPTY: arm a workspace job first");
      state.armed = state.armed.filter((item) => item !== id);
      const switched = this.switchLayer2(deviceId, { id });
      state.armed.push(id);
      return this.layer2Status(deviceId, {
        workspaceId: switched.workspaceId,
        workspaceGeneration: switched.workspaceGeneration ?? state.workspaceGeneration,
        verified: switched.verified,
        next: switched.next,
      });
    }
    throw new Error("Unknown workspace operation");
  }

  async snapshotDeviceSession(deviceId: string, sessionId: string): Promise<{ url: string; displayId: number; sessionId?: string }> {
    const session = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    if (!session) throw new Error("Mock session not found");
    if (isDefaultForegroundSession(session.sessionId) || session.displayId == null || session.displayId <= 0) {
      throw new Error("Cyclone refused an unproven background preview");
    }
    const focus = jpegFocusTarget(bindSessionTile(deviceId, session));
    const headers = {
      get(name: string): string | null {
        if (name === "X-Cyclone-Foreground-Substitution") return "false";
        if (name === "X-Cyclone-Display-Id") return String(focus.displayId);
        if (name === "X-Cyclone-Session-Id") return focus.sessionId;
        return null;
      },
    };
    const { displayId } = readExactSessionSnapshotHeaders(headers);
    return {
      url: mockFrameDataUrl(deviceId, "focus", { sessionId: focus.sessionId, displayId }),
      displayId,
      sessionId: focus.sessionId,
    };
  }

  addMockSession(deviceId: string, session: DeviceSessionDescriptor): DeviceSessionDescriptor {
    this.requireDevice(deviceId);
    const next = copySession(session);
    this.deviceSessions(deviceId).push(next);
    this.emitSessionFabric("session.added", deviceId, next);
    return copySession(next);
  }

  removeMockSession(deviceId: string, sessionId: string): void {
    const existing = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    this.sessions.set(deviceId, this.deviceSessions(deviceId).filter((item) => item.sessionId !== sessionId));
    if (existing) this.emitSessionFabric("session.removed", deviceId, existing);
    else this.emitFleetEvent({ event: "session.removed", deviceId, sessionId });
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string { return `mock://video/${encodeURIComponent(deviceId)}?profile=${profile}`; }
  getVideoProtocols(): string[] { return []; }
  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string { return mockFrameDataUrl(deviceId, profile); }
  async reportStreamDiagnostic(_deviceId: string, _event: StreamDiagnosticEvent): Promise<void> {}
  async createConnectionDiagnosticBundle(deviceId: string): Promise<ConnectionDiagnosticBundle> {
    return { ok: true, deviceId, path: "mock-connection-diagnostics.zip", createdAtEpochMs: Date.now() };
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    return [
      {
        id: "codex", name: "Codex", description: "Use Cyclone phones from Codex.", state: "READY_TO_CONNECT", actionLabel: "Connect",
        aiState: "DETECTED", detected: true, configured: false, gatewayState: "READY", gatewayReachable: true,
        readyDeviceCount: 3, deviceCount: 4, toolCount: 14, transport: "stdio", approvalMode: "writes", phoneState: "READY",
      },
      { id: "grok", name: "Grok", description: "Connect Grok on this PC.", state: "CONNECTED", aiState: "CONNECTED", detected: true, configured: true },
      { id: "cursor", name: "Cursor", description: "Connect Cursor on this PC.", state: "READY_TO_CONNECT", aiState: "DETECTED", detected: true, configured: false },
      { id: "opencode", name: "OpenCode", description: "Connect OpenCode on this PC.", state: "CONNECTED", aiState: "CONNECTED", detected: true, configured: true },
      { id: "copilot", name: "Copilot", description: "Connect Copilot on this PC.", state: "NOT_INSTALLED", aiState: "UNKNOWN", detected: false },
      { id: "generic", name: "Generic MCP", description: "Use a compatible MCP client.", state: "READY_TO_CONNECT", aiState: "DETECTED", detected: true },
    ];
  }
  async runConnectorAction(_connectorId: string, _action: "connect" | "install" | "repair"): Promise<ConnectorActionResult> {
    return {
      ok: true,
      changed: true,
      restartRequired: true,
      message: "Codex is connected to Cyclone with 3 ready phones. Restart Codex once, then use the Cyclone phone tools.",
      readyDeviceCount: 3,
      toolCount: 14,
    };
  }

  async pairQrConfirm(deviceId: string, pairingId: string): Promise<PairQrConfirmResult> {
    const pairing = this.pairings.get(deviceId);
    if (!pairing || pairing.pairingId !== pairingId) return { ok: false, pending: false, reason: "STALE_CODE" };
    return { ok: false, pending: true };
  }
  async getMcpTunnelStatus(): Promise<McpTunnelStatus> { return { ...this.tunnel }; }
  async startMcpTunnel(mode?: McpTunnelMode): Promise<McpTunnelStatus> {
    this.tunnel = {
      ...this.tunnel,
      ok: true,
      state: "running",
      mode: mode ?? this.tunnel.mode,
      tokenLast4: this.tunnel.tokenLast4 ?? "mock",
      publicUrl: "https://mock-cyclone.trycloudflare.com",
      mcpUrl: "https://mock-cyclone.trycloudflare.com/mcp",
      healthUrl: "https://mock-cyclone.trycloudflare.com/health",
      gatewayAlive: true,
      cloudflaredAlive: true,
      healthOk: true,
      message: "Mock tunnel running. Packaged Cyclone One uses a real cloudflared hostname.",
    };
    return { ...this.tunnel };
  }
  async stopMcpTunnel(): Promise<McpTunnelStatus> {
    this.tunnel = stoppedTunnelStatus("Mock tunnel stopped.");
    return { ...this.tunnel };
  }
  async restartMcpTunnel(): Promise<McpTunnelStatus> {
    const mode = this.tunnel.mode;
    await this.stopMcpTunnel();
    return this.startMcpTunnel(mode);
  }
  async rotateMcpTunnelToken(): Promise<McpTunnelStatus> {
    this.tunnel = { ...this.tunnel, tokenLast4: "rot4", rotated: true, message: "Mock bearer rotated (last4 rot4)." };
    return { ...this.tunnel };
  }
  async setMcpTunnelMode(mode: McpTunnelMode): Promise<McpTunnelStatus> {
    this.tunnel = { ...this.tunnel, mode };
    return { ...this.tunnel };
  }
  async copyMcpTunnelToken(): Promise<McpTunnelToken> {
    return { token: "mock-tunnel-token-not-for-production", last4: "tion" };
  }
  async smokeMcpTunnel(): Promise<McpTunnelSmokeResult> {
    return {
      ok: true,
      message: "SMOKE PASSED (mock)",
      checks: [
        { name: "GET /health 200", ok: true, detail: "status=200" },
        { name: "POST /mcp without auth -> 401", ok: true, detail: "got 401" },
        { name: "initialize 200 with bearer", ok: true, detail: "status=200" },
      ],
    };
  }
  async openMcpTunnelDocs(): Promise<string> {
    return "mock://connector-setup";
  }

  async loadChatgptAttachConfig(): Promise<ChatgptAttachConfig> {
    return publicChatgptConfig(this.chatgptConfig, this.chatgptSecrets);
  }

  async saveChatgptAttachConfig(config: ChatgptAttachConfig): Promise<ChatgptAttachConfig> {
    const pads = config.pads.map((pad, index) => {
      const id = pad.id || `pad-${index + 1}`;
      if (pad.connectKey) this.chatgptSecrets.set(id, pad.connectKey);
      return {
        id,
        label: pad.label || id,
        sshHost: pad.sshHost,
        sshPort: pad.sshPort || 1824,
        sshUser: pad.sshUser || "s",
        localAdbPort: pad.localAdbPort || 63670 + index,
        remoteAdbSpec: pad.remoteAdbSpec || "localhost:1",
        hasConnectKey: Boolean(pad.connectKey || this.chatgptSecrets.get(id)),
      };
    });
    this.chatgptConfig = {
      controlApiBase: config.controlApiBase || "",
      defaultGoal: config.defaultGoal || this.chatgptConfig.defaultGoal,
      hasVmosApiKey: Boolean(config.vmosApiKey) || this.chatgptConfig.hasVmosApiKey,
      pads,
    };
    return this.loadChatgptAttachConfig();
  }

  async syncChatgptAttachFleet(): Promise<ChatgptSyncResult> {
    const pads = this.chatgptConfig.pads.map((pad) => {
      const session = localStubSession();
      return {
        id: pad.id,
        label: pad.label,
        ok: true,
        deviceId: `dev_${pad.id}`,
        serial: `localhost:${pad.localAdbPort}`,
        adb: "device" as const,
        mobile: "running" as const,
        sessionId: session.sessionId,
        sessionToken: session.sessionToken,
        sessionSource: "control-api" as const,
      };
    });
    return {
      ok: pads.length > 0,
      controlApi: publicControlApi(
        this.chatgptConfig.controlApiBase || this.chatgptShare.url || this.cloudControlLocalBase(),
        8765,
      ),
      generatedAt: new Date().toISOString(),
      pads,
      message: pads.length ? "Mock fleet synced." : "Add a pad first.",
    };
  }

  async copyChatgptHandoff(markdown: string): Promise<{ ok: boolean; markdown?: string }> {
    return { ok: true, markdown };
  }

  async saveChatgptHandoff(_markdown: string): Promise<string> {
    return "mock://FLEET_HANDOFF.md";
  }

  async chatgptAttachResources(): Promise<ChatgptAttachResources> {
    return {
      openapi: "openapi: 3.1.0\ninfo:\n  title: Cyclone Cloud Control API\nservers:\n  - url: https://CONTROL_API_HOST_PLACEHOLDER\n",
      instructions: "You control VMOS phones through Actions only.",
      exampleFleet: "{\"pads\":[]}",
    };
  }

  cloudControlLocalBase(): string {
    return localCloudControlBase("http://127.0.0.1:8765");
  }

  async probeCloudControl(base?: string): Promise<{ ok: boolean; localBase: string }> {
    return { ok: true, localBase: localCloudControlBase(base) || this.cloudControlLocalBase() };
  }

  async chatgptShareStatus(): Promise<ChatgptShareStatus> {
    return { ...this.chatgptShare };
  }

  async chatgptShareStart(): Promise<ChatgptShareStatus> {
    this.chatgptShare = {
      ok: true,
      running: true,
      url: publicShareControlApi("https://mock-share.trycloudflare.com"),
      localBase: this.cloudControlLocalBase(),
      message: "ChatGPT can reach Cloud Control over HTTPS.",
    };
    return this.chatgptShareStatus();
  }

  async chatgptShareStop(): Promise<ChatgptShareStatus> {
    this.chatgptShare = emptyShareStatus(this.cloudControlLocalBase());
    return this.chatgptShareStatus();
  }

  async getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return {
      backendReachable: true,
      deviceCount: this.devices.length,
      pairedDeviceCount: this.devices.filter((device) => device.paired).length,
      recoveryActive: this.devices.some((device) => device.state === "DISCONNECTED"),
      message: "Mock development backend",
      discovery: {
        adbPath: "mock-adb",
        adbAvailable: true,
        rawAdbDeviceCount: this.devices.length,
        authorizedAdbDeviceCount: this.devices.length,
        fleetDeviceCount: this.devices.length,
        reconnectingDeviceCount: this.devices.filter((device) => device.state === "DISCONNECTED").length,
        attentionDeviceCount: this.devices.filter((device) => device.state === "ATTENTION").length,
        maxReconnectAttempts: 5,
        reconnectBackoffSeconds: [1, 2, 4, 8, 15],
        trackerActive: true,
        lastScanSource: "mock",
      },
    };
  }
  private requireDevice(deviceId: string): DesktopDevice {
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    if (!device) throw new Error("Mock device not found");
    return device;
  }

  private applyInputOwner(device: DesktopDevice, owner: "AI" | "HUMAN", sessionId?: string): ControlResult {
    if (device.state === "SLEEPING") return { ok: false, deviceId: device.id, verification: "PHONE_LOCKED" };
    if (sessionId) {
      const session = this.deviceSessions(device.id).find((item) => item.sessionId === sessionId);
      if (!session) throw new Error("Mock session not found");
      if (!isDefaultForegroundSession(sessionId) && (session.displayId == null || session.displayId <= 0)) {
        throw new Error("Named VD session is missing a proven Android display");
      }
      session.inputOwner = owner;
      if (isDefaultForegroundSession(sessionId)) device.inputOwner = owner;
    } else {
      device.inputOwner = owner;
    }
    return {
      ok: true,
      deviceId: device.id,
      verification: owner === "AI" ? "AI_HAS_CONTROL" : "HUMAN_HAS_CONTROL",
      inputOwner: owner,
    };
  }

  private emitSessionFabric(
    event: "session.added" | "session.removed",
    deviceId: string,
    session: Pick<DeviceSessionDescriptor, "sessionId" | "displayId" | "inputOwner">,
  ): void {
    const payload = {
      event,
      deviceId,
      sessionId: session.sessionId,
      displayId: session.displayId,
      inputOwner: session.inputOwner === "AI" ? "AI" : "HUMAN",
    };
    this.emitFleetEvent(payload);
  }

  private deviceSessions(deviceId: string): DeviceSessionDescriptor[] {
    const existing = this.sessions.get(deviceId);
    if (existing) return existing;
    const created: DeviceSessionDescriptor[] = [];
    this.sessions.set(deviceId, created);
    return created;
  }

  private mutateSession(deviceId: string, sessionId: string, mutate: (session: DeviceSessionDescriptor) => void): DeviceSessionResult {
    this.requireDevice(deviceId);
    if (isDefaultForegroundSession(sessionId)) throw new Error("Foreground session lifecycle stays on the human display");
    const session = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    if (!session) throw new Error("Mock session not found");
    mutate(session);
    return { protocol: "cyclone-one-session/1", deviceId, session: copySession(session) };
  }

  private layer2State(deviceId: string): MockLayer2State {
    const existing = this.layer2.get(deviceId);
    if (existing) return existing;
    const created = emptyMockLayer2();
    this.layer2.set(deviceId, created);
    return created;
  }

  private switchLayer2(deviceId: string, params: Record<string, unknown>): Layer2Status {
    const state = this.layer2State(deviceId);
    if (state.gated) throw new Error("GATE: resolve the pending human review before switching");
    const id = stringParam(params, "id");
    const target = state.workspaces.find((row) => row.id === id);
    if (!target) throw new Error("Unknown workspace");
    if (target.displayId !== 0) throw new Error("Layer 2 supports display 0 only");
    pauseLayer2(state);
    state.selected = id;
    state.holder = id;
    for (const row of state.workspaces) {
      row.state = row.id === id ? "running" : row.state === "running" ? "paused" : row.state;
    }
    return this.layer2Status(deviceId, {
      workspaceId: id,
      workspaceGeneration: state.workspaceGeneration,
      verified: true,
      next: "phone.observe; include workspaceId and workspaceGeneration on every mutation",
    });
  }

  private layer2Status(deviceId: string, extra: Partial<Layer2Status> = {}): Layer2Status {
    const state = this.layer2State(deviceId);
    return bindLayer2Status(deviceId, {
      protocol: LAYER2_PROTOCOL,
      deviceId,
      sessionId: DEFAULT_FOREGROUND_SESSION_ID,
      displayId: 0,
      plane: "layer2",
      workspaces: state.workspaces.map((row) => ({ ...row, displayId: 0 })),
      holder: state.holder,
      lockOwner: state.holder,
      workspaceGeneration: state.workspaceGeneration,
      armed: [...state.armed],
      goals: { ...state.goals },
      gated: state.gated,
      root: state.root,
      ...extra,
    });
  }
}

export function createMockDevices(count: number): DesktopDevice[] {
  const states: DesktopDevice["state"][] = ["READY", "READY", "SLEEPING", "UNPAIRED", "READY", "DISCONNECTED"];
  return Array.from({ length: Math.max(0, count) }, (_, index) => {
    const state = states[index % states.length];
    const paired = state !== "UNPAIRED" && state !== "PAIRING";
    return {
      id: `phone-${index + 1}`, name: index === 0 ? "My phone" : `Phone ${index + 1}`, model: `Cyclone device ${index + 1}`,
      state, paired,
      connectionLabel: state === "READY" ? "Ready" : state === "SLEEPING" ? "Sleeping" : state === "UNPAIRED" ? "Not paired" : "Reconnecting",
      lastSeenEpochMs: Date.now() - index * 1000,
      video: { mode: "SCREENSHOT", width: 1080, height: 2400, rotationDegrees: 0 },
      capabilities: { keyboard: paired, clipboard: paired && index % 4 !== 3, clipboardSync: false, reconnect: true },
      connectionHealth: state === "DISCONNECTED"
        ? {
            bridgeReachable: false,
            lastHeartbeatEpochMs: Date.now() - 40_000,
            reconnectAttempts: 2,
            maxReconnectAttempts: 5,
            nextRetryEpochMs: Date.now() + 4_000,
            lastError: "Simulated USB bridge drop",
            errorClass: "BridgeDisconnectedError",
          }
        : state === "ATTENTION"
          ? {
              bridgeReachable: false,
              lastHeartbeatEpochMs: Date.now() - 120_000,
              reconnectAttempts: 5,
              maxReconnectAttempts: 5,
              nextRetryEpochMs: null,
              lastError: "Bridge retries exhausted",
              errorClass: "BridgeDisconnectedError",
            }
          : healthyConnectionHealth(),
      lastFrameUrl: mockFrameDataUrl(`phone-${index + 1}`, "thumbnail"),
      inputOwner: "AI",
    };
  });
}

function healthyConnectionHealth() {
  return {
    bridgeReachable: true,
    lastHeartbeatEpochMs: Date.now(),
    reconnectAttempts: 0,
    maxReconnectAttempts: 5,
    nextRetryEpochMs: null,
    lastError: null,
    errorClass: null,
  };
}

function copyDevice(device: DesktopDevice): DesktopDevice { return { ...device, video: { ...device.video }, capabilities: { ...device.capabilities } }; }

function copySession(session: DeviceSessionDescriptor): DeviceSessionDescriptor { return { ...session }; }

interface MockLayer2State {
  workspaces: Layer2Workspace[];
  holder: string | null;
  selected: string | null;
  workspaceGeneration: number;
  armed: string[];
  goals: Record<string, string>;
  gated: boolean;
  root: string;
}

function seedMockLayer2(): MockLayer2State {
  return {
    workspaces: [
      { id: "profile-a", label: "Profile A", appPackage: "com.android.chrome", androidUserId: 0, displayId: 0, state: "running" },
      { id: "profile-b", label: "Profile B", appPackage: "com.google.android.apps.maps", androidUserId: 10, displayId: 0, state: "idle" },
    ],
    holder: "profile-a",
    selected: "profile-a",
    workspaceGeneration: 1,
    armed: ["profile-a"],
    goals: { "profile-a": "Keep Chrome in the foreground" },
    gated: false,
    root: "Unknown",
  };
}

function emptyMockLayer2(): MockLayer2State {
  return {
    workspaces: [],
    holder: null,
    selected: null,
    workspaceGeneration: 0,
    armed: [],
    goals: {},
    gated: false,
    root: "Unknown",
  };
}

function pauseLayer2(state: MockLayer2State): void {
  state.holder = null;
  state.workspaceGeneration += 1;
  if (state.selected) {
    const selected = state.workspaces.find((row) => row.id === state.selected);
    if (selected) selected.state = "paused";
  }
}

function workspaceFromParams(params: Record<string, unknown>): Layer2Workspace {
  const displayId = params.displayId;
  if (typeof displayId === "number" && displayId !== 0) throw new Error("Layer 2 supports display 0 only");
  const id = stringParam(params, "id");
  const label = stringParam(params, "label");
  const appPackage = stringParam(params, "appPackage");
  const androidUserId = typeof params.androidUserId === "number" && Number.isInteger(params.androidUserId) && params.androidUserId >= 0
    ? params.androidUserId
    : 0;
  return { id, label, appPackage, androidUserId, displayId: 0, state: "idle" };
}

function stringParam(params: Record<string, unknown>, key: string): string {
  const value = params[key];
  if (typeof value !== "string" || !value.trim()) throw new Error(`Missing workspace ${key}`);
  return value.trim();
}

function seedMockSessions(deviceId: string): DeviceSessionDescriptor[] {
  return [
    {
      sessionId: DEFAULT_FOREGROUND_SESSION_ID,
      displayId: 0,
      backend: "default-foreground",
      inputOwner: "HUMAN",
      state: "FOREGROUND",
      executable: true,
      frameHealthy: true,
    },
    {
      sessionId: `workspace-${deviceId}`,
      displayId: 2,
      targetPackage: "com.android.chrome",
      backend: "Session Kernel VD",
      inputOwner: "AI",
      state: "RUNNING",
      executable: true,
      frameHealthy: true,
    },
  ];
}

function mockFrameDataUrl(
  deviceId: string,
  profile: StreamProfile,
  identity?: { sessionId: string; displayId: number },
): string {
  const seed = Number(deviceId.replace(/\D/g, "")) || 1;
  const hueA = 248 + (seed * 11) % 35;
  const hueB = 270 + (seed * 7) % 45;
  const label = identity
    ? `session ${identity.sessionId} display ${identity.displayId}`
    : profile === "focus" ? "Cyclone live phone" : "Cyclone";
  const comment = identity
    ? `<!-- cyclone-session:${identity.sessionId} display:${identity.displayId} -->`
    : "";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="540" height="1200" viewBox="0 0 540 1200">${comment}<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="hsl(${hueA} 42% 16%)"/><stop offset="1" stop-color="hsl(${hueB} 52% 8%)"/></linearGradient></defs><rect width="540" height="1200" fill="url(#g)"/><rect x="28" y="70" width="484" height="120" rx="28" fill="rgba(255,255,255,.08)"/><rect x="28" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.07)"/><rect x="284" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.05)"/><rect x="28" y="474" width="484" height="190" rx="36" fill="rgba(255,255,255,.06)"/><rect x="28" y="692" width="484" height="320" rx="36" fill="rgba(255,255,255,.045)"/><circle cx="54" cy="1136" r="22" fill="#8b5cf6"/><text x="88" y="1146" fill="white" opacity=".82" font-family="system-ui" font-size="30">${label}</text></svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function publicChatgptConfig(config: ChatgptAttachConfig, secrets: Map<string, string>): ChatgptAttachConfig {
  return {
    controlApiBase: config.controlApiBase,
    defaultGoal: config.defaultGoal,
    hasVmosApiKey: config.hasVmosApiKey,
    pads: config.pads.map((pad) => ({
      ...pad,
      connectKey: undefined,
      hasConnectKey: Boolean(pad.hasConnectKey || secrets.get(pad.id)),
    })),
  };
}
