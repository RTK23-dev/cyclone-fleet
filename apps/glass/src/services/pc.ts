/**
 * Plan 31: this PC's Remote MCP tunnel, ChatGPT Attach (VMOS) and the run/stop card, through the gateway's fixed
 * `/v1/pc/*` routes. Glass never holds a saved key: the gateway answers only whether one is set.
 */
import type { GatewayClient } from "./gateway.js";
import {
  cloudSessionReady,
  gatewayPortFromBase,
  localCloudControlBase,
  parseMobileChip,
  resolveControlApi,
  type ChatgptAttachConfig,
  type ChatgptAttachResources,
  type ChatgptPadStatus,
  type ChatgptShareStatus,
  type ChatgptSyncResult,
} from "../core/chatgptAttach.js";

// ---- Remote MCP ---------------------------------------------------------------------------------------------------

export type TunnelMode = "readonly" | "full";
export type TunnelState = "running" | "degraded" | "stopped" | "starting" | "unknown";

export interface TunnelStatus {
  state: TunnelState;
  mode: TunnelMode;
  publicUrl: string | null;
  mcpUrl: string | null;
  tokenLast4: string | null;
  healthOk: boolean;
  message: string;
  localMcpUrl: string;
}

const TUNNEL_STATES = new Set<TunnelState>(["running", "degraded", "stopped", "starting"]);

function str(value: unknown): string | null {
  return typeof value === "string" && value ? value.slice(0, 500) : null;
}

export function parseTunnel(raw: unknown): TunnelStatus {
  const record = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const state = typeof record.state === "string" && TUNNEL_STATES.has(record.state as TunnelState) ? (record.state as TunnelState) : "unknown";
  return {
    state,
    mode: record.mode === "full" ? "full" : "readonly",
    publicUrl: str(record.publicUrl),
    mcpUrl: str(record.mcpUrl),
    tokenLast4: typeof record.tokenLast4 === "string" ? record.tokenLast4.slice(-4) : null,
    healthOk: record.healthOk === true,
    message: str(record.message) ?? "",
    localMcpUrl: str(record.localMcpUrl) ?? "http://127.0.0.1:8787/mcp",
  };
}

export const tunnel = {
  status: (c: GatewayClient) => c.get<unknown>("/v1/pc/tunnel").then(parseTunnel),
  start: (c: GatewayClient, mode: TunnelMode) => c.post<unknown>("/v1/pc/tunnel/start", { mode }).then(parseTunnel),
  stop: (c: GatewayClient) => c.post<unknown>("/v1/pc/tunnel/stop").then(parseTunnel),
  rotate: (c: GatewayClient) => c.post<unknown>("/v1/pc/tunnel/rotate").then(parseTunnel),
  setMode: (c: GatewayClient, mode: TunnelMode) => c.post<unknown>("/v1/pc/tunnel/mode", { mode }).then(parseTunnel),
  smoke: (c: GatewayClient) => c.post<Record<string, unknown>>("/v1/pc/tunnel/smoke"),
  /** Only when the owner presses Show token: they paste it into ChatGPT's connector. Never stored. */
  token: (c: GatewayClient) => c.get<{ token: string; last4: string }>("/v1/pc/tunnel/token"),
  docs: (c: GatewayClient) => c.get<{ markdown: string }>("/v1/pc/tunnel/docs").then((d) => String(d?.markdown ?? "")),
};

// ---- ChatGPT Attach -----------------------------------------------------------------------------------------------

export const attach = {
  load: (c: GatewayClient) => c.get<ChatgptAttachConfig>("/v1/pc/attach/fleet"),
  /** Secret fields left empty keep the saved keys. */
  save: (c: GatewayClient, config: ChatgptAttachConfig) => c.put<ChatgptAttachConfig>("/v1/pc/attach/fleet", config),
  resources: (c: GatewayClient) => c.get<ChatgptAttachResources>("/v1/pc/attach/resources"),
  saveHandoff: (c: GatewayClient, markdown: string) => c.post<{ path: string }>("/v1/pc/attach/handoff", { markdown }),
  checkHandoff: (c: GatewayClient, markdown: string) => c.post<{ ok: boolean }>("/v1/pc/attach/handoff/check", { markdown }),
  shareStatus: (c: GatewayClient) => c.get<ChatgptShareStatus>("/v1/pc/attach/share"),
  shareStart: (c: GatewayClient) => c.post<ChatgptShareStatus>("/v1/pc/attach/share/start"),
  shareStop: (c: GatewayClient) => c.post<ChatgptShareStatus>("/v1/pc/attach/share/stop"),
};

interface CloudDeviceStatus {
  adb?: string;
  mobileInstalled?: boolean;
  mobileRunning?: boolean;
  gatewayReady?: boolean;
  trustReady?: boolean;
  message?: string;
}

export function readinessHint(status: CloudDeviceStatus): string {
  if (status.adb !== "device") return "VMOS ADB is not ready. Turn on ADB in VMOS, then Sync fleet again.";
  if (!status.mobileInstalled) return "Cyclone Mobile is missing on this pad. Install and start it, then Sync fleet again.";
  if (!status.mobileRunning) return "Cyclone Mobile is installed but not running on this pad. Start it, then Sync fleet again.";
  if (!status.gatewayReady) return "Cyclone Mobile is not paired with this PC. Pair it in Devices, then Sync fleet again.";
  if (!status.trustReady) return "Cyclone Mobile trust is not ready. Finish pairing on the phone, then Sync fleet again.";
  return (status.message || "The cloud session is not ready. Sync fleet again.").slice(0, 220);
}

function problem(error: unknown): string {
  const record = error as { code?: string; message?: string } | null;
  if (record?.code === "DEVICE_NOT_FOUND") return "The cloud session could not find this VMOS device. Keep VMOS ADB on and Sync fleet again.";
  return `The cloud session could not be created. ${String(record?.message ?? "Sync fleet again.").slice(0, 160)}`;
}

type Wait = (ms: number) => Promise<void>;
const realWait: Wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Sync the fleet: the gateway runs the ADB script, then each ready pad gets a Cloud Control session (the same steps
 * the Cyclone One window took). A pad is exportable only with a real session.
 */
export async function syncFleet(c: GatewayClient, origin: string, wait: Wait = realWait): Promise<ChatgptSyncResult> {
  const raw = await c.post<{ pads?: unknown; controlApi?: string; generatedAt?: string; adbPath?: string; message?: string }>("/v1/pc/attach/sync");
  const source = Array.isArray(raw?.pads) ? (raw.pads as ChatgptPadStatus[]) : [];
  const pads: ChatgptPadStatus[] = [];
  for (const pad of source) {
    const base: ChatgptPadStatus = { ...pad, sessionId: "", sessionToken: "", sessionSource: "local-stub", mobile: parseMobileChip(pad.mobile) };
    if (!pad.ok) {
      pads.push({ ...base, ok: false });
      continue;
    }
    let minted: { sessionId?: string; sessionToken?: string; deviceId?: string } | null = null;
    let mintError: unknown = null;
    for (let attempt = 0; attempt < 3 && !minted; attempt += 1) {
      try {
        await c.post("/v1/fleet/scan");
        minted = await c.post("/cloud/v1/sessions", { deviceId: pad.deviceId || undefined, serial: pad.serial || undefined, ttlSeconds: 7200 });
      } catch (error) {
        mintError = error;
        if (attempt < 2) await wait(300 * (attempt + 1));
      }
    }
    const deviceId = minted?.deviceId || pad.deviceId;
    if (!minted?.sessionId || !minted.sessionToken || !deviceId) {
      pads.push({ ...base, ok: false, error: problem(mintError) });
      continue;
    }
    let status: CloudDeviceStatus;
    try {
      status = await c.get<CloudDeviceStatus>(`/cloud/v1/devices/${encodeURIComponent(deviceId)}/status?sessionId=${encodeURIComponent(minted.sessionId)}`);
    } catch (error) {
      pads.push({ ...base, ok: false, deviceId, error: `The cloud readiness check failed. ${String((error as Error)?.message ?? "").slice(0, 160)}` });
      continue;
    }
    const ready = status.adb === "device" && status.mobileRunning === true && status.gatewayReady === true && status.trustReady === true;
    pads.push({
      ...base,
      ok: ready,
      deviceId,
      adb: status.adb === "device" ? "device" : status.adb === "offline" ? "offline" : status.adb === "unauthorized" ? "unauthorized" : "missing",
      mobile: status.mobileRunning ? "running" : status.mobileInstalled ? "installed" : "missing",
      sessionId: ready ? minted.sessionId : "",
      sessionToken: ready ? minted.sessionToken : "",
      sessionSource: ready ? "control-api" : "local-stub",
      gatewayReady: status.gatewayReady,
      trustReady: status.trustReady,
      error: ready ? undefined : readinessHint(status),
    });
  }
  const share = await attach.shareStatus(c).catch(() => null);
  const localBase = localCloudControlBase(c.baseUrl || origin);
  return {
    ok: pads.some((pad) => cloudSessionReady(pad)),
    controlApi: resolveControlApi({ configured: raw?.controlApi, shareUrl: share?.url, localBase, fallbackPort: gatewayPortFromBase(c.baseUrl || origin) }),
    generatedAt: raw?.generatedAt || new Date().toISOString(),
    pads,
    adbPath: raw?.adbPath,
    message: raw?.message,
  };
}

// ---- The run/stop card --------------------------------------------------------------------------------------------

export const welcome = {
  seen: (c: GatewayClient) => c.get<{ seen?: unknown }>("/v1/pc/welcome").then((r) => r?.seen === true),
  markSeen: (c: GatewayClient) => c.post<{ seen: boolean }>("/v1/pc/welcome/seen"),
};

/** The card's words, kept here so they are tested apart from the DOM. */
export const RUN_STOP_CARD = {
  title: "Cyclone is running on this PC",
  rows: [
    ["Stop", "Close the terminal window where you typed cyclone, or press Ctrl+C in it."],
    ["Start again", "Open a terminal (Command Prompt or PowerShell) and type: cyclone"],
    ["Update", "Type: cyclone update"],
    ["Keep it open", "Codex, ChatGPT and Grok reach your phone only while that window is open."],
  ] as Array<[string, string]>,
};
