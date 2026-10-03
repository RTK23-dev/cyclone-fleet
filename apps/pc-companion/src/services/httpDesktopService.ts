import { invoke } from "@tauri-apps/api/core";
import type {
  ConnectionDiagnosticBundle,
  ConnectorActionResult,
  ConnectorCard,
  ControlResult,
  DesktopDevice,
  DesktopRuntimeStatus,
  DesktopService,
  DeviceControlAction,
  DeviceSessionList,
  DeviceSessionResult,
  PairBeginResult,
  PairConfirmResult,
  PairQrConfirmResult,
  StreamDiagnosticEvent,
  StreamProfile,
  TrustStatusResult,
  FleetBatchOperation,
  FleetBatchTask,
  FleetGroup,
  FleetWorkspace,
  FleetWsEvent,
  Layer2Operation,
  Layer2Status,
  McpTunnelMode,
  McpTunnelSmokeResult,
  McpTunnelStatus,
  McpTunnelToken,
  ChatgptAttachConfig,
  ChatgptAttachResources,
  ChatgptSyncResult,
} from "./types.js";
import {
  emptyShareStatus,
  gatewayPortFromBase,
  localCloudControlBase,
  parseMobileChip,
  resolveControlApi,
  type ChatgptPadStatus,
  type ChatgptShareStatus,
} from "../core/chatgptAttach.js";
import { bindLayer2Status } from "../core/layer2.js";
import { normalizeTunnelStatus } from "../core/mcpTunnel.js";
import { isDefaultForegroundSession, parseFleetWsEvent, readExactSessionSnapshotHeaders } from "../core/sessionTiles.js";
import { applyDeviceMobileVersion } from "./glassRuntime.js";

export interface HttpDesktopServiceOptions {
  httpBaseUrl?: string;
  wsBaseUrl?: string;
  token: string;
}

type ConnectorStatusPayload = {
  codex?: string;
  deepseek_harness?: string;
  generic_mcp?: string;
  ai?: {
    state?: string;
    server_ready?: boolean;
    adapters?: Record<string, {
      id?: string;
      state?: string;
      detected?: boolean;
      configured?: boolean;
      server_ready?: boolean;
      detail?: string;
      config_path?: string | null;
    }>;
  };
  phone?: {
    state?: string;
    reachable?: boolean;
    ready_device_count?: number;
    device_count?: number;
  };
  details?: {
    codex?: {
      state?: string;
      detected?: boolean;
      configured?: boolean;
      config_path?: string;
      server_ready?: boolean;
      approval_mode?: string;
    };
    gateway?: {
      state?: string;
      reachable?: boolean;
      ready_device_count?: number;
      device_count?: number;
    };
    mcp?: {
      server?: string;
      tool_count?: number;
      transport?: string;
    };
    adapters?: Record<string, {
      id?: string;
      state?: string;
      detected?: boolean;
      configured?: boolean;
      detail?: string;
      config_path?: string | null;
    }>;
  };
};

type ConnectorActionPayload = {
  changed?: boolean;
  restart_required?: boolean;
  message?: string;
  path?: string;
  verification?: {
    ok?: boolean;
    tools?: string[];
    gateway?: {
      reachable?: boolean;
      ready_device_count?: number;
    };
  };
};

type RuntimeSelfTest = {
  ok: boolean;
  runtimeInstanceId: string;
  runtimePort: number;
  sessionBinding: string;
};

type CloudControlDeviceStatus = {
  deviceId: string;
  sessionId?: string;
  adb?: string;
  mobileInstalled?: boolean;
  mobileRunning?: boolean;
  gatewayReady?: boolean;
  trustReady?: boolean;
  message?: string;
};

/** The single real-backend adapter used by the Cyclone PC Companion UI. */
export class HttpDesktopService implements DesktopService {
  readonly mode = "real" as const;
  private readonly httpBase: string;
  private readonly wsBase: string;
  private readonly token: string;

  constructor(options: HttpDesktopServiceOptions) {
    this.httpBase = stripSlash(options.httpBaseUrl ?? "http://127.0.0.1:8765");
    this.wsBase = stripSlash(options.wsBaseUrl ?? this.httpBase.replace(/^http/, "ws"));
    this.token = options.token;
  }

  get glassGateway(): { httpBase: string; getBearer: () => string } {
    return {
      httpBase: this.httpBase,
      getBearer: () => this.token,
    };
  }

  async waitUntilReady(timeoutMs = 12_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    let lastError: unknown = null;
    while (Date.now() < deadline) {
      try {
        const status = await this.getRuntimeStatus();
        if (status.backendReachable) {
          await this.verifySessionBinding(Math.min(3_000, Math.max(500, deadline - Date.now())));
          return;
        }
      } catch (error) {
        lastError = error;
      }
      await sleep(180);
    }
    if (lastError instanceof Error) throw lastError;
    throw new Error("Cyclone local Gateway did not become ready in time");
  }

  listDevices(): Promise<DesktopDevice[]> {
    return this.request<{ devices: DesktopDevice[] }>("/v1/fleet").then((value) =>
      (value.devices ?? []).map((device) => applyDeviceMobileVersion(device)),
    );
  }

  scanDevices(): Promise<DesktopDevice[]> {
    return this.request<{ devices: DesktopDevice[] }>("/v1/fleet/scan", { method: "POST" }).then((value) =>
      (value.devices ?? []).map((device) => applyDeviceMobileVersion(device)),
    );
  }

  getFleetWorkspace(): Promise<FleetWorkspace> {
    return this.request("/v1/fleet/workspace");
  }

  saveFleetGroup(groupId: string, name: string, deviceIds: string[]): Promise<FleetGroup> {
    return this.request(`/v1/fleet/groups/${encodeURIComponent(groupId)}`, {
      method: "POST", body: JSON.stringify({ name, device_ids: deviceIds }),
    });
  }

  async deleteFleetGroup(groupId: string): Promise<void> {
    await this.request(`/v1/fleet/groups/${encodeURIComponent(groupId)}/delete`, { method: "POST" });
  }

  setFleetSelection(deviceIds: string[]): Promise<string[]> {
    return this.request<{ selectedDeviceIds: string[] }>("/v1/fleet/selection", {
      method: "POST", body: JSON.stringify({ device_ids: deviceIds }),
    }).then((value) => value.selectedDeviceIds);
  }

  submitFleetBatch(deviceIds: string[], operation: FleetBatchOperation, params: Record<string, unknown> = {}): Promise<FleetBatchTask> {
    return this.request("/v1/fleet/batches", {
      method: "POST", body: JSON.stringify({ device_ids: deviceIds, operation, params }),
    });
  }

  getFleetBatch(batchId: string): Promise<FleetBatchTask> {
    return this.request(`/v1/fleet/batches/${encodeURIComponent(batchId)}`);
  }

  cancelFleetBatch(batchId: string): Promise<FleetBatchTask> {
    return this.request(`/v1/fleet/batches/${encodeURIComponent(batchId)}/cancel`, { method: "POST" });
  }

  watchFleet(onChange: (event?: FleetWsEvent) => void): () => void {
    let disposed = false;
    let authRejected = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | null = null;

    const connect = () => {
      if (disposed || authRejected) return;
      try {
        socket = new WebSocket(`${this.wsBase}/v1/fleet/events`, this.getVideoProtocols());
      } catch {
        scheduleReconnect();
        return;
      }
      socket.addEventListener("message", (message) => {
        if (disposed) return;
        const parsed = parseFleetWsEvent(typeof message.data === "string" ? message.data : undefined);
        onChange(parsed ?? undefined);
      });
      socket.addEventListener("error", () => {
        try { socket?.close(); } catch { /* noop */ }
      });
      socket.addEventListener("close", (event) => {
        if (event.code === 4401) {
          authRejected = true;
          return;
        }
        scheduleReconnect();
      });
    };

    const scheduleReconnect = () => {
      if (disposed || authRejected || reconnectTimer != null) return;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, 2000);
    };

    connect();
    return () => {
      disposed = true;
      if (reconnectTimer != null) window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
      try { socket?.close(); } catch { /* noop */ }
      socket = null;
    };
  }

  listLayer2Workspaces(deviceId: string): Promise<Layer2Status> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/workspaces`)
      .then((value) => bindLayer2Status(deviceId, value));
  }

  layer2Workspace(deviceId: string, operation: Layer2Operation, params: Record<string, unknown> = {}): Promise<Layer2Status> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/workspaces`, {
      method: "POST",
      body: JSON.stringify({ operation, params }),
    }).then((value) => bindLayer2Status(deviceId, value));
  }

  trustStatus(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust`);
  }

  trustBegin(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/begin`, { method: "POST" });
  }

  trustComplete(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/complete`, { method: "POST" });
  }

  trustRotate(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/rotate`, { method: "POST" });
  }

  trustRevoke(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/revoke`, { method: "POST" });
  }

  pairBegin(deviceId: string): Promise<PairBeginResult> {
    return this.request<Record<string, unknown>>(`/v1/devices/${encodeURIComponent(deviceId)}/pair/begin`, { method: "POST" })
      .then((value) => ({
        pairingId: String(value.pairingId ?? ""),
        expiresAtEpochMs: Number(value.expiresAtEpochMs ?? value.expiresAtMs ?? Date.now() + 60_000),
        qrPayload: typeof value.qrPayload === "string" ? value.qrPayload : null,
        qrAvailable: value.qrAvailable === true,
        diagnosticsActive: value.diagnosticsActive === true,
        diagnosticsPath: typeof value.diagnosticsPath === "string" ? value.diagnosticsPath : null,
        diagnosticsMode: typeof value.diagnosticsMode === "string" ? value.diagnosticsMode : null,
      }));
  }

  async pairQrConfirm(deviceId: string, pairingId: string): Promise<PairQrConfirmResult> {
    try {
      const value = await this.request<{ paired?: boolean; pending?: boolean; device?: DesktopDevice }>(
        `/v1/devices/${encodeURIComponent(deviceId)}/pair/qr/complete`,
        { method: "POST", body: JSON.stringify({ pairing_id: pairingId }) },
      );
      if (value.pending === true) return { ok: false, pending: true };
      const device = applyDeviceMobileVersion(
        value.device ?? (await this.listDevices()).find((candidate) => candidate.id === deviceId),
      );
      return value.paired === true && device
        ? { ok: true, device }
        : { ok: false, pending: false, reason: "UNAVAILABLE" };
    } catch (error) {
      const codeValue = error instanceof DesktopHttpError ? error.code : "";
      if (codeValue === "PAIRING_EXPIRED") return { ok: false, pending: false, reason: "EXPIRED" };
      if (codeValue === "PAIRING_REPLAY" || codeValue === "PAIRING_SESSION_MISMATCH") {
        return { ok: false, pending: false, reason: "STALE_CODE" };
      }
      const message = error instanceof DesktopHttpError ? error.message : undefined;
      return { ok: false, pending: false, reason: "UNAVAILABLE", message };
    }
  }

  async pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    try {
      const value = await this.request<{ device?: DesktopDevice }>(`/v1/devices/${encodeURIComponent(deviceId)}/pair/complete`, {
        method: "POST",
        body: JSON.stringify({ pairing_id: pairingId, code: code.trim().toUpperCase() }),
      });
      const device = applyDeviceMobileVersion(
        value.device ?? (await this.listDevices()).find((candidate) => candidate.id === deviceId),
      );
      return device ? { ok: true, device } : { ok: false, reason: "UNAVAILABLE" };
    } catch (error) {
      const codeValue = error instanceof DesktopHttpError ? error.code : "";
      if (codeValue === "PAIRING_EXPIRED") return { ok: false, reason: "EXPIRED" };
      if (codeValue === "PAIRING_REPLAY" || codeValue === "PAIRING_SESSION_MISMATCH") return { ok: false, reason: "STALE_CODE" };
      if (codeValue === "PAIRING_CODE_REJECTED" || codeValue === "PAIRING_ATTEMPTS_EXCEEDED") return { ok: false, reason: "INVALID_CODE" };
      const message = error instanceof DesktopHttpError ? error.message : undefined;
      return { ok: false, reason: "UNAVAILABLE", message };
    }
  }

  async sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult> {
    if (action.type === "clipboard_sync") {
      return { ok: !action.enabled, deviceId, verification: action.enabled ? "PC_TO_PHONE_ONLY" : "DISABLED" };
    }
    if (action.type === "clipboard_paste") {
      const result = await this.request<{ updated?: boolean }>(`/v1/devices/${encodeURIComponent(deviceId)}/clipboard`, {
        method: "POST",
        body: JSON.stringify({ text: action.text }),
      });
      return { ok: result.updated === true, deviceId, verification: "clipboard-redacted" };
    }
    if (action.type === "disconnect") {
      await this.trustRevoke(deviceId);
      return { ok: true, deviceId, verification: "trust-revoked" };
    }
    if (action.type === "reconnect") {
      return { ok: true, deviceId, verification: "automatic-usb-reconnect" };
    }

    let body: Record<string, unknown>;
    if (action.type === "wake") body = { kind: "wake" };
    else if (action.type === "tap") body = { kind: "tap", x: action.x, y: action.y };
    else if (action.type === "swipe") body = {
      kind: "swipe",
      x1: action.x1,
      y1: action.y1,
      x2: action.x2,
      y2: action.y2,
      duration_ms: action.durationMs,
    };
    else if (action.type === "scroll") body = { kind: action.direction === "UP" ? "scroll_up" : "scroll_down" };
    else if (action.type === "text") body = { kind: "text", text: action.text };
    else if (action.type === "key" && action.key === "BACK") body = { kind: "back" };
    else if (action.type === "key" && action.key === "HOME") body = { kind: "home" };
    else if (action.type === "key" && action.key === "ENTER") body = { kind: "text", text: "\n" };
    else if (action.type === "yield_ai") body = { kind: "yield_ai" };
    else if (action.type === "take_human") body = { kind: "take_human" };
    else return { ok: false, deviceId, verification: "KEY_UNAVAILABLE" };

    if (action.type === "yield_ai" || action.type === "take_human") {
      const sessionId = action.sessionId?.trim();
      if (sessionId) body.sessionId = sessionId;
    }

    return this.postControl(deviceId, body);
  }

  async sendSessionControl(deviceId: string, sessionId: string, kind: "yield_ai" | "take_human"): Promise<ControlResult> {
    return this.postControl(deviceId, { kind, sessionId: sessionId.trim() });
  }

  listDeviceSessions(deviceId: string): Promise<DeviceSessionList> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/sessions`);
  }

  startDeviceSession(deviceId: string, packageName: string): Promise<DeviceSessionResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/sessions`, {
      method: "POST",
      body: JSON.stringify({ package: packageName }),
    });
  }

  pauseDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.request(this.sessionPath(deviceId, sessionId, "pause"), { method: "POST" });
  }

  resumeDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.request(this.sessionPath(deviceId, sessionId, "resume"), { method: "POST" });
  }

  handoffDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.request(this.sessionPath(deviceId, sessionId, "handoff"), { method: "POST" });
  }

  stopDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.request(this.sessionPath(deviceId, sessionId, "stop"), { method: "POST" });
  }

  async snapshotDeviceSession(deviceId: string, sessionId: string): Promise<{ url: string; displayId: number; sessionId?: string }> {
    if (isDefaultForegroundSession(sessionId)) {
      throw new Error("Cyclone refused an unproven background preview");
    }
    const response = await this.requestRaw(this.sessionPath(deviceId, sessionId, "snapshot"), {
      headers: { Accept: "image/png" },
    });
    const { displayId } = readExactSessionSnapshotHeaders(response.headers);
    const headerSessionId = response.headers.get("X-Cyclone-Session-Id")?.trim() || undefined;
    const blob = await response.blob();
    if (blob.type && blob.type !== "image/png") throw new Error("Background preview is not a PNG frame");
    return { url: URL.createObjectURL(blob), displayId, sessionId: headerSessionId };
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.wsBase}/v1/devices/${encodeURIComponent(deviceId)}/video?profile=${profile}`;
  }

  getVideoProtocols(): string[] {
    return ["cyclone-v1", `cyclone-token.${this.token}`];
  }

  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.httpBase}/v1/devices/${encodeURIComponent(deviceId)}/stream/snapshot?profile=${profile}`;
  }

  async reportStreamDiagnostic(deviceId: string, event: StreamDiagnosticEvent): Promise<void> {
    try {
      await this.request(`/v1/devices/${encodeURIComponent(deviceId)}/diagnostics/stream-event`, {
        method: "POST",
        body: JSON.stringify({
          stage: event.stage,
          code: event.code,
          attempt: event.attempt,
          close_code: event.closeCode,
          retryable: event.retryable,
        }),
      });
    } catch {
      // Diagnostics must never destabilize the stream they observe.
    }
  }

  createConnectionDiagnosticBundle(deviceId: string): Promise<ConnectionDiagnosticBundle> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/diagnostics/bundle`, { method: "POST" });
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    try {
      const status = await invoke<ConnectorStatusPayload>("connector_status");
      const adapters = status.ai?.adapters ?? status.details?.adapters ?? {};
      const gatewayDetails = status.details?.gateway;
      const mcpDetails = status.details?.mcp;
      const phoneState = status.phone?.state;
      const names: Record<string, [string, string]> = {
        grok: ["Grok", "Connect Grok on this PC to Cyclone phone control."],
        codex: ["Codex", "Connect Codex on this PC to Cyclone phone control."],
        cursor: ["Cursor", "Connect Cursor on this PC to Cyclone phone control."],
        opencode: ["OpenCode", "Connect OpenCode on this PC to Cyclone phone control."],
        copilot: ["Copilot", "Connect Copilot on this PC to Cyclone phone control."],
        generic: ["Generic MCP", "Connect any compatible local MCP client."],
      };
      const cards = Object.keys(names).map((id) => {
        const [name, description] = names[id];
        const adapter = adapters[id] ?? adapters[id === "generic" ? "generic" : id];
        const card = connectorFromAi(id, name, description, adapter?.state);
        card.detected = adapter?.detected;
        card.configured = adapter?.configured;
        card.configPath = adapter?.config_path ?? undefined;
        card.aiState = (adapter?.state as ConnectorCard["aiState"]) ?? card.aiState;
        card.gatewayState = gatewayDetails?.state;
        card.gatewayReachable = gatewayDetails?.reachable;
        card.readyDeviceCount = gatewayDetails?.ready_device_count;
        card.deviceCount = gatewayDetails?.device_count;
        card.toolCount = mcpDetails?.tool_count;
        card.transport = mcpDetails?.transport;
        card.phoneState = phoneState as ConnectorCard["phoneState"];
        if (id === "codex") card.approvalMode = status.details?.codex?.approval_mode;
        return card;
      });
      if (!cards.some((item) => item.id === "opencode")) {
        cards.push(connector("deepseek-mcp", "DeepSeek / MCP harness", "Use Cyclone from OpenCode or another DeepSeek-powered MCP harness.", status.deepseek_harness));
      }
      return cards;
    } catch {
      return [
        connectorFromAi("grok", "Grok", "Connect Grok on this PC to Cyclone phone control.", "UNKNOWN"),
        connectorFromAi("codex", "Codex", "Connect Codex on this PC to Cyclone phone control.", "UNKNOWN"),
        connectorFromAi("cursor", "Cursor", "Connect Cursor on this PC to Cyclone phone control.", "UNKNOWN"),
        connectorFromAi("opencode", "OpenCode", "Connect OpenCode on this PC to Cyclone phone control.", "UNKNOWN"),
        connectorFromAi("copilot", "Copilot", "Connect Copilot on this PC to Cyclone phone control.", "UNKNOWN"),
        connectorFromAi("generic", "Generic MCP", "Connect any compatible local MCP client.", "DETECTED"),
      ];
    }
  }

  async runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<ConnectorActionResult> {
    const value = await invoke<ConnectorActionPayload>("connector_action", { connectorId, action });
    const gateway = value.verification?.gateway;
    return {
      ok: value.verification?.ok !== false,
      changed: value.changed,
      restartRequired: value.restart_required,
      message: value.message ?? "Cyclone connection updated.",
      path: value.path,
      readyDeviceCount: gateway?.ready_device_count,
      toolCount: value.verification?.tools?.length,
    };
  }

  getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return this.request("/v1/diagnostics/status");
  }

  async getMcpTunnelStatus(): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_status"));
  }

  async startMcpTunnel(mode?: McpTunnelMode): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_start", { mode: mode ?? null }));
  }

  async stopMcpTunnel(): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_stop"));
  }

  async restartMcpTunnel(): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_restart", { mode: null }));
  }

  async rotateMcpTunnelToken(): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_rotate_token"));
  }

  async setMcpTunnelMode(mode: McpTunnelMode): Promise<McpTunnelStatus> {
    return normalizeTunnelStatus(await invoke<McpTunnelStatus>("mcp_tunnel_set_mode", { mode }));
  }

  copyMcpTunnelToken(): Promise<McpTunnelToken> {
    return invoke<McpTunnelToken>("mcp_tunnel_token");
  }

  async smokeMcpTunnel(): Promise<McpTunnelSmokeResult> {
    const result = await invoke<McpTunnelSmokeResult>("mcp_tunnel_smoke");
    return {
      ok: result.ok !== false,
      checks: Array.isArray(result.checks) ? result.checks : [],
      message: result.message || (result.ok !== false ? "SMOKE PASSED" : "SMOKE FAILED"),
    };
  }

  openMcpTunnelDocs(): Promise<string> {
    return invoke<string>("mcp_tunnel_open_docs");
  }

  loadChatgptAttachConfig(): Promise<ChatgptAttachConfig> {
    return invoke<ChatgptAttachConfig>("chatgpt_attach_load");
  }

  saveChatgptAttachConfig(config: ChatgptAttachConfig): Promise<ChatgptAttachConfig> {
    return invoke<ChatgptAttachConfig>("chatgpt_attach_save", { config });
  }

  async syncChatgptAttachFleet(): Promise<ChatgptSyncResult> {
    const raw = await invoke<ChatgptSyncResult>("chatgpt_attach_sync");
    const sourcePads = Array.isArray(raw.pads) ? raw.pads : [];
    const pads: ChatgptPadStatus[] = [];

    for (const pad of sourcePads) {
      if (!pad.ok) {
        pads.push({ ...pad, mobile: parseMobileChip(pad.mobile) });
        continue;
      }

      let minted: { sessionId: string; sessionToken: string; deviceId?: string; source?: string } | null = null;
      let mintError: unknown = null;
      for (let attempt = 0; attempt < 3 && !minted; attempt += 1) {
        try {
          await this.scanDevices();
          minted = await this.request<{ sessionId: string; sessionToken: string; deviceId?: string; source?: string }>(
            "/cloud/v1/sessions",
            {
              method: "POST",
              body: JSON.stringify({
                deviceId: pad.deviceId || undefined,
                serial: pad.serial || undefined,
                ttlSeconds: 7200,
              }),
            },
          );
        } catch (error) {
          mintError = error;
          if (attempt < 2) await sleep(300 * (attempt + 1));
        }
      }

      if (!minted?.sessionId || !minted.sessionToken || !(minted.deviceId || pad.deviceId)) {
        pads.push({
          ...pad,
          ok: false,
          deviceId: pad.deviceId || "",
          sessionId: "",
          sessionToken: "",
          sessionSource: "local-stub",
          mobile: parseMobileChip(pad.mobile),
          error: cloudSessionFailure(mintError),
        });
        continue;
      }

      const deviceId = minted.deviceId || pad.deviceId;
      let status: CloudControlDeviceStatus;
      try {
        status = await this.request<CloudControlDeviceStatus>(
          `/cloud/v1/devices/${encodeURIComponent(deviceId)}/status?sessionId=${encodeURIComponent(minted.sessionId)}`,
        );
      } catch (error) {
        pads.push({
          ...pad,
          ok: false,
          deviceId,
          sessionId: "",
          sessionToken: "",
          sessionSource: "local-stub",
          mobile: parseMobileChip(pad.mobile),
          error: `Cloud AI readiness check failed. ${safeErrorMessage(error)}`,
        });
        continue;
      }

      const mobile = status.mobileRunning ? "running" : status.mobileInstalled ? "installed" : "missing";
      const ready = status.adb === "device"
        && status.mobileRunning === true
        && status.gatewayReady === true
        && status.trustReady === true;
      pads.push({
        ...pad,
        ok: ready,
        deviceId,
        adb: status.adb === "device" ? "device" : status.adb === "offline" ? "offline" : status.adb === "unauthorized" ? "unauthorized" : "missing",
        mobile,
        sessionId: ready ? minted.sessionId : "",
        sessionToken: ready ? minted.sessionToken : "",
        sessionSource: ready ? "control-api" : "local-stub",
        gatewayReady: status.gatewayReady,
        trustReady: status.trustReady,
        error: ready ? undefined : cloudReadinessHint(status),
      });
    }

    const share = await this.chatgptShareStatus().catch(() => emptyShareStatus(this.cloudControlLocalBase()));
    const probed = await this.probeCloudControl();
    const controlApi = resolveControlApi({
      configured: raw.controlApi,
      shareUrl: share.url,
      localBase: probed.localBase || this.cloudControlLocalBase(),
      fallbackPort: gatewayPortFromBase(this.httpBase),
    });
    return {
      ok: pads.some((pad) => pad.ok && pad.sessionSource === "control-api"),
      controlApi,
      generatedAt: raw.generatedAt,
      pads,
      adbPath: raw.adbPath,
      message: raw.message,
    };
  }

  copyChatgptHandoff(markdown: string): Promise<{ ok: boolean; markdown?: string }> {
    return invoke<{ ok: boolean; markdown?: string }>("chatgpt_attach_copy", { markdown });
  }

  saveChatgptHandoff(markdown: string): Promise<string> {
    return invoke<string>("chatgpt_attach_save_handoff", { markdown });
  }

  chatgptAttachResources(): Promise<ChatgptAttachResources> {
    return invoke<ChatgptAttachResources>("chatgpt_attach_resources");
  }

  cloudControlLocalBase(): string {
    return localCloudControlBase(this.httpBase);
  }

  async probeCloudControl(base?: string): Promise<{ ok: boolean; localBase: string }> {
    const localBase = localCloudControlBase(base || this.httpBase);
    try {
      const health = await this.request<{ ok?: boolean; localBase?: string }>("/cloud/v1/health");
      return { ok: health.ok === true, localBase: health.localBase || localBase };
    } catch {
      return { ok: false, localBase };
    }
  }

  async chatgptShareStatus(): Promise<ChatgptShareStatus> {
    try {
      return normalizeShare(await invoke<ChatgptShareStatus>("chatgpt_attach_share_status"), this.cloudControlLocalBase());
    } catch {
      return emptyShareStatus(this.cloudControlLocalBase());
    }
  }

  async chatgptShareStart(): Promise<ChatgptShareStatus> {
    const status = await invoke<ChatgptShareStatus>("chatgpt_attach_share_start", {
      localPort: gatewayPortFromBase(this.httpBase),
    });
    return normalizeShare(status, this.cloudControlLocalBase());
  }

  chatgptShareStop(): Promise<ChatgptShareStatus> {
    return invoke<ChatgptShareStatus>("chatgpt_attach_share_stop").then((status) =>
      normalizeShare(status, this.cloudControlLocalBase()),
    );
  }

  private async verifySessionBinding(timeoutMs: number): Promise<void> {
    const httpValue = await this.request<RuntimeSelfTest>("/v1/runtime/self-test");
    const wsValue = await new Promise<RuntimeSelfTest>((resolve, reject) => {
      let settled = false;
      const socket = new WebSocket(`${this.wsBase}/v1/runtime/self-test/ws`, this.getVideoProtocols());
      const timer = window.setTimeout(() => {
        if (settled) return;
        settled = true;
        try { socket.close(); } catch { /* noop */ }
        reject(new Error("Cyclone WebSocket session self-test timed out"));
      }, Math.max(250, timeoutMs));
      const finish = (value: RuntimeSelfTest | Error) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timer);
        try { socket.close(); } catch { /* noop */ }
        if (value instanceof Error) reject(value); else resolve(value);
      };
      socket.addEventListener("message", (event) => {
        try {
          finish(JSON.parse(String(event.data)) as RuntimeSelfTest);
        } catch {
          finish(new Error("Cyclone WebSocket session self-test returned invalid data"));
        }
      });
      socket.addEventListener("close", (event) => {
        if (!settled && event.code === 4401) finish(new Error("Cyclone WebSocket session authentication was rejected"));
      });
      socket.addEventListener("error", () => finish(new Error("Cyclone WebSocket session self-test failed")));
    });
    if (
      !httpValue.ok
      || !wsValue.ok
      || httpValue.sessionBinding !== wsValue.sessionBinding
      || httpValue.runtimeInstanceId !== wsValue.runtimeInstanceId
      || httpValue.runtimePort !== wsValue.runtimePort
    ) {
      throw new Error("Cyclone local HTTP and WebSocket clients are attached to different runtime sessions");
    }
  }

  private sessionPath(deviceId: string, sessionId: string, suffix = ""): string {
    const base = `/v1/devices/${encodeURIComponent(deviceId)}/sessions/${encodeURIComponent(sessionId)}`;
    return suffix ? `${base}/${suffix}` : base;
  }

  private async postControl(deviceId: string, body: Record<string, unknown>): Promise<ControlResult> {
    try {
      const result = await this.request<{ ok?: boolean; status?: string; inputOwner?: string }>(
        `/v1/devices/${encodeURIComponent(deviceId)}/control`,
        { method: "POST", body: JSON.stringify(body) },
      );
      return {
        ok: result.ok === true,
        deviceId,
        verification: result.status ?? "android-result",
        inputOwner: result.inputOwner,
      };
    } catch (error) {
      if (error instanceof DesktopHttpError && (error.code === "PHONE_LOCKED" || error.code === "HUMAN_HAS_CONTROL")) {
        return { ok: false, deviceId, verification: error.code };
      }
      throw error;
    }
  }

  private async requestRaw(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`${this.httpBase}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${this.token}`,
        ...(init.headers ?? {}),
      },
      cache: "no-store",
    });
    if (!response.ok) {
      let code = "HTTP_ERROR";
      let detail = "Cyclone backend request failed";
      try {
        const body = await response.json() as { detail?: { code?: string; message?: string } };
        code = body.detail?.code ?? code;
        detail = body.detail?.message ?? detail;
      } catch { /* safe fallback */ }
      throw new DesktopHttpError(response.status, code, detail);
    }
    return response;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.requestRaw(path, init);
    return (await response.json()) as T;
  }
}

class DesktopHttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

function cloudSessionFailure(error: unknown): string {
  if (error instanceof DesktopHttpError && error.code === "DEVICE_NOT_FOUND") {
    return "Cloud AI session could not bind this VMOS ADB device. Keep VMOS ADB on, then Sync fleet again; Cyclone will rescan the Gateway automatically.";
  }
  return `Cloud AI session could not be created. ${safeErrorMessage(error)}`;
}

function cloudReadinessHint(status: CloudControlDeviceStatus): string {
  if (status.adb !== "device") return "VMOS ADB is not ready. Check Turn on ADB in VMOS, then Sync fleet again.";
  if (!status.mobileInstalled) return "Cyclone Mobile is missing on this VMOS pad. Install/start Cyclone Mobile, then Sync fleet again.";
  if (!status.mobileRunning) return "Cyclone Mobile is installed but not running on this VMOS pad. Start it, then Sync fleet again.";
  if (!status.gatewayReady) return "Cyclone Mobile is not paired with this Cyclone One. Open Mobile -> PC Gateway & QR pairing, pair once, then Sync fleet again.";
  if (!status.trustReady) return "Cyclone Mobile trust is not ready. Finish PC Gateway pairing/trust on the phone, then Sync fleet again.";
  return status.message || "Cloud AI session is not ready. Re-run Sync fleet.";
}

function safeErrorMessage(error: unknown): string {
  if (error instanceof DesktopHttpError) return `${error.code}: ${error.message}`.slice(0, 180);
  if (error instanceof Error) return error.message.slice(0, 180);
  return "Re-run Sync fleet and follow the pad status hint.";
}

function connector(id: ConnectorCard["id"], name: string, description: string, raw?: string): ConnectorCard {
  return connectorFromAi(id, name, description, raw);
}

function connectorFromAi(id: ConnectorCard["id"], name: string, description: string, raw?: string): ConnectorCard {
  const aiState = normalizeAiState(raw);
  const state: ConnectorCard["state"] = aiState === "CONNECTED" || aiState === "CONFIGURED"
    ? "CONNECTED"
    : aiState === "FAILED"
      ? "NEEDS_ATTENTION"
      : aiState === "UNKNOWN"
        ? "NOT_INSTALLED"
        : "READY_TO_CONNECT";
  return {
    id,
    name,
    description,
    state,
    aiState,
    actionLabel: state === "CONNECTED" ? "Connected" : state === "NOT_INSTALLED" ? "Install" : state === "NEEDS_ATTENTION" ? "Configure" : "Connect",
  };
}

function normalizeAiState(raw?: string): NonNullable<ConnectorCard["aiState"]> {
  if (raw === "CONNECTED" || raw === "CONFIGURED" || raw === "DETECTED" || raw === "FAILED" || raw === "UNKNOWN") return raw;
  if (raw === "READY") return "CONNECTED";
  if (raw === "NOT_INSTALLED") return "UNKNOWN";
  if (raw === "ATTENTION") return "DETECTED";
  return "UNKNOWN";
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}

function normalizeShare(status: ChatgptShareStatus | null | undefined, localBase: string): ChatgptShareStatus {
  return {
    ok: Boolean(status?.ok),
    running: Boolean(status?.running),
    url: status?.url || "",
    localBase: status?.localBase || localBase,
    message: status?.message,
  };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
