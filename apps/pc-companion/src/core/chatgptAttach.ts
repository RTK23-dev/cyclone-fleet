export const CHATGPT_ATTACH_TITLE = "ChatGPT Attach";
export const CHATGPT_ATTACH_SUBTITLE =
  "Sync VMOS Cloud pads over ADB and export one ChatGPT handoff. SSH Connect Keys never leave this PC.";

export const DEFAULT_CHAT_GOAL = "Observe assigned phones and wait for the next instruction.";
export const DEFAULT_CONTROL_API_PLACEHOLDER = "https://CONTROL_API_HOST_PLACEHOLDER";
export const LOCAL_CLOUD_CONTROL_PATH = "/cloud";

export const HANDOFF_PUBLIC_FIELDS = [
  "DEVICE_ID",
  "SESSION_ID",
  "SESSION_TOKEN",
  "CONTROL_API",
  "MOBILE",
  "ADB",
  "GOAL",
  "NOTES",
] as const;

export const SECRET_FIELD_NAMES = [
  "connectKey",
  "connect_key",
  "accessKey",
  "access_key",
  "secretAccessKey",
  "secret_access_key",
  "vmosApiKey",
  "vmos_api_key",
  "controlApiKey",
  "sshPassword",
  "password",
  "privateKey",
  "askpass",
] as const;

export const CUSTOM_GPT_SETUP_HINTS = [
  "Create a Custom GPT and paste the bundled driver instructions.",
  "Actions -> Create -> paste the bundled OpenAPI schema (Copy OpenAPI binds the live CONTROL_API).",
  "Action Authentication = None. The schema sends the pasted SESSION_TOKEN in X-Cyclone-Session-Token on each protected call.",
  "Click Share to ChatGPT for an HTTPS trycloudflare CONTROL_API. ChatGPT Actions cannot reach localhost.",
  "Paste the one-file handoff into the chat. Never paste SSH Connect Keys or VMOS AccessKeys.",
];

export type AdbChip = "device" | "offline" | "unauthorized" | "missing" | "failed";
export type MobileChip = "running" | "installed" | "missing" | "unknown";
export type SessionSource = "control-api" | "local-stub";

export interface ChatgptPadConfig {
  id: string;
  label: string;
  sshHost: string;
  sshPort: number;
  sshUser: string;
  localAdbPort: number;
  remoteAdbSpec: string;
  serial?: string;
  connectKey?: string;
  hasConnectKey?: boolean;
}

export interface ParsedVmosConnectCommand {
  sshHost: string;
  sshPort: number;
  sshUser: string;
  localAdbPort: number;
  remoteAdbSpec: string;
  serial: string;
}

export interface ChatgptAttachConfig {
  controlApiBase: string;
  defaultGoal: string;
  vmosApiKey?: string;
  hasVmosApiKey?: boolean;
  pads: ChatgptPadConfig[];
}

export interface ChatgptPadStatus {
  id: string;
  label: string;
  ok: boolean;
  deviceId: string;
  serial?: string;
  adb: AdbChip;
  mobile: MobileChip;
  sessionId: string;
  sessionToken: string;
  sessionSource: SessionSource;
  gatewayReady?: boolean;
  trustReady?: boolean;
  error?: string;
}

export interface ChatgptSyncResult {
  ok: boolean;
  controlApi: string;
  generatedAt: string;
  pads: ChatgptPadStatus[];
  adbPath?: string;
  message?: string;
}

export interface ChatgptAttachResources {
  openapi: string;
  instructions: string;
  exampleFleet: string;
}

export interface ChatgptShareStatus {
  ok: boolean;
  running: boolean;
  url: string;
  localBase: string;
  message?: string;
}

export interface ConnectionCheckItem {
  id: "adb" | "mobile" | "session" | "control" | "handoff";
  label: string;
  ok: boolean;
}

export function emptyChatgptAttachConfig(): ChatgptAttachConfig {
  return {
    controlApiBase: "",
    defaultGoal: DEFAULT_CHAT_GOAL,
    pads: [],
  };
}

export function newPadDraft(existing: ChatgptPadConfig[] = []): ChatgptPadConfig {
  const index = existing.length + 1;
  const used = new Set(existing.map((pad) => pad.localAdbPort));
  let port = 63670;
  while (used.has(port)) port += 1;
  return {
    id: `pad-${index}`,
    label: `pad-${index}`,
    sshHost: "",
    sshPort: 1824,
    sshUser: "s",
    localAdbPort: port,
    remoteAdbSpec: "localhost:1",
    connectKey: "",
    hasConnectKey: false,
  };
}

/** Parse the non-secret VMOS "Connect command" into the local pad fields. */
export function parseVmosConnectCommand(command: string): ParsedVmosConnectCommand | null {
  const text = (command || "").replace(/[\r\n]+/g, " ").replace(/\s+/g, " ").trim();
  if (!text || !/(^|\s)ssh(?:\.exe)?(?=\s|$)/i.test(text)) return null;

  const targetMatches = [...text.matchAll(/(?:^|\s)([A-Za-z0-9._-]+)@(\[[0-9A-Fa-f:]+\]|[A-Za-z0-9.-]+)(?=\s|$)/g)];
  const target = targetMatches.at(-1);
  if (!target) return null;

  const portMatch = text.match(/(?:^|\s)-p\s+(\d{1,5})(?=\s|$)/i)
    ?? text.match(/(?:^|\s)-p(\d{1,5})(?=\s|$)/i);
  const forwardMatch = text.match(/(?:^|\s)-L\s+(\d{1,5}):([^\s:]+):(\d{1,5})(?=\s|$)/i)
    ?? text.match(/(?:^|\s)-L(\d{1,5}):([^\s:]+):(\d{1,5})(?=\s|$)/i);
  if (!portMatch || !forwardMatch) return null;

  const sshPort = Number(portMatch[1]);
  const localAdbPort = Number(forwardMatch[1]);
  const remotePort = Number(forwardMatch[3]);
  if (!validPort(sshPort) || !validPort(localAdbPort) || !validPort(remotePort)) return null;

  return {
    sshUser: target[1],
    sshHost: target[2].replace(/^\[|\]$/g, ""),
    sshPort,
    localAdbPort,
    remoteAdbSpec: `${forwardMatch[2]}:${remotePort}`,
    serial: `localhost:${localAdbPort}`,
  };
}

export function publicControlApi(base: string | null | undefined, fallbackPort?: number): string {
  const trimmed = (base || "").trim().replace(/\/+$/, "");
  if (trimmed) return trimmed;
  if (fallbackPort && fallbackPort > 0) return `http://127.0.0.1:${fallbackPort}${LOCAL_CLOUD_CONTROL_PATH}`;
  return DEFAULT_CONTROL_API_PLACEHOLDER;
}

export function isPlaceholderControlApi(base: string | null | undefined): boolean {
  const trimmed = (base || "").trim();
  return !trimmed || /CONTROL_API_HOST_PLACEHOLDER/i.test(trimmed);
}

export function isEphemeralShareControlApi(base: string | null | undefined): boolean {
  try {
    const url = new URL((base || "").trim());
    return /(^|\.)trycloudflare\.com$/i.test(url.hostname);
  } catch {
    return false;
  }
}

/** True only for an externally addressable HTTPS control URL suitable for ChatGPT Actions. */
export function isPublicControlApi(base: string | null | undefined): boolean {
  if (isPlaceholderControlApi(base)) return false;
  try {
    const url = new URL((base || "").trim());
    const host = url.hostname.toLowerCase();
    if (url.protocol !== "https:") return false;
    if (host === "localhost" || host === "127.0.0.1" || host === "::1" || host.endsWith(".localhost")) return false;
    return Boolean(host);
  } catch {
    return false;
  }
}

export function localCloudControlBase(httpBase: string | null | undefined): string {
  const trimmed = (httpBase || "").trim().replace(/\/+$/, "");
  if (!trimmed) return "";
  if (/(^|\/)cloud$/i.test(trimmed)) return trimmed;
  return `${trimmed}${LOCAL_CLOUD_CONTROL_PATH}`;
}

export function publicShareControlApi(tunnelUrl: string | null | undefined): string {
  const trimmed = (tunnelUrl || "").trim().replace(/\/+$/, "");
  if (!trimmed) return "";
  const origin = trimmed.replace(/\/(cloud|mcp)$/i, "");
  return `${origin}${LOCAL_CLOUD_CONTROL_PATH}`;
}

export function resolveControlApi(options: {
  configured?: string | null;
  shareUrl?: string | null;
  localBase?: string | null;
  fallbackPort?: number;
}): string {
  const configured = (options.configured || "").trim().replace(/\/+$/, "");
  const share = publicShareControlApi(options.shareUrl);
  // trycloudflare URLs are ephemeral. A newly running share must beat a stale saved share URL.
  if (share && (!configured || isPlaceholderControlApi(configured) || isEphemeralShareControlApi(configured))) return share;
  if (configured && !isPlaceholderControlApi(configured)) return configured;
  if (share) return share;
  const local = localCloudControlBase(options.localBase);
  if (local) return local;
  return publicControlApi("", options.fallbackPort);
}

export function gatewayPortFromBase(httpBase: string | null | undefined): number {
  try {
    const port = Number(new URL(httpBase || "").port);
    return port > 0 ? port : 8765;
  } catch {
    return 8765;
  }
}

export function emptyShareStatus(localBase = ""): ChatgptShareStatus {
  return {
    ok: false,
    running: false,
    url: "",
    localBase,
    message: "Share to ChatGPT is stopped.",
  };
}

/** A pad is exportable only when the Gateway minted a real Cloud Control session. */
export function cloudSessionReady(pad: ChatgptPadStatus | null | undefined): boolean {
  return Boolean(
    pad?.ok
      && pad.adb === "device"
      && pad.mobile === "running"
      && pad.sessionSource === "control-api"
      && pad.deviceId
      && pad.sessionId
      && pad.sessionToken,
  );
}

export function connectionChecklist(input: {
  pads?: ChatgptPadStatus[];
  controlApi?: string;
  controlApiReachable?: boolean;
  handoffCopied?: boolean;
}): ConnectionCheckItem[] {
  const pads = input.pads || [];
  return [
    { id: "adb", label: "ADB device", ok: pads.some((pad) => pad.adb === "device") },
    { id: "mobile", label: "Cyclone Mobile running", ok: pads.some((pad) => pad.mobile === "running") },
    { id: "session", label: "Cloud AI session", ok: pads.some((pad) => cloudSessionReady(pad)) },
    { id: "control", label: "Public CONTROL_API", ok: Boolean(input.controlApiReachable) && isPublicControlApi(input.controlApi) },
    { id: "handoff", label: "Handoff copied", ok: Boolean(input.handoffCopied) },
  ];
}

export function bindOpenApiServer(openapi: string, controlApi: string): string {
  if (!openapi || isPlaceholderControlApi(controlApi)) return openapi;
  return openapi.replace(/https:\/\/CONTROL_API_HOST_PLACEHOLDER/g, controlApi.trim().replace(/\/+$/, ""));
}

export function mobileChip(installed: boolean, running: boolean): MobileChip {
  if (running) return "running";
  if (installed) return "installed";
  return "missing";
}

export function parseMobileChip(value: string | null | undefined): MobileChip {
  const text = (value || "").toLowerCase();
  if (text.includes("pid") || text === "running") return "running";
  if (text.includes("installed")) return "installed";
  if (text.includes("missing")) return "missing";
  return "unknown";
}

export function localStubSession(): { sessionId: string; sessionToken: string; sessionSource: SessionSource } {
  return {
    sessionId: randomHex(16),
    sessionToken: randomHex(20),
    sessionSource: "local-stub",
  };
}

export function collectSecrets(config: ChatgptAttachConfig, extra: string[] = []): string[] {
  const secrets = extra.filter((item) => item && item.length >= 6);
  for (const pad of config.pads) {
    if (pad.connectKey && pad.connectKey.length >= 4) secrets.push(pad.connectKey);
  }
  if (config.vmosApiKey && config.vmosApiKey.length >= 4) secrets.push(config.vmosApiKey);
  return [...new Set(secrets)];
}

export function handoffContainsForbidden(text: string, secrets: string[] = []): string[] {
  const hits: string[] = [];
  for (const name of SECRET_FIELD_NAMES) {
    if (new RegExp(`(?:^|\\n)\\s*${name}\\s*[:=]`, "i").test(text)) hits.push(name);
  }
  for (const secret of secrets) {
    if (secret && secret.length >= 4 && text.includes(secret)) hits.push("literal-secret");
  }
  return [...new Set(hits)];
}

export function assertHandoffSafe(text: string, secrets: string[] = []): void {
  const hits = handoffContainsForbidden(text, secrets);
  if (hits.length) {
    throw new Error(`Handoff leaked forbidden material: ${hits.join(", ")}`);
  }
}

export function buildAttachBlock(pad: ChatgptPadStatus, controlApi: string, goal: string): string {
  const notes = padNotes(pad);
  return [
    "=== CYCLONE_VMOS_ATTACH_v1 ===",
    `DEVICE_ID: ${pad.deviceId}`,
    `SESSION_ID: ${pad.sessionId}`,
    `SESSION_TOKEN: ${pad.sessionToken}`,
    `CONTROL_API: ${controlApi}`,
    `MOBILE: ${pad.mobile}`,
    `ADB: ${pad.adb}`,
    `GOAL: ${goal}`,
    `NOTES: ${notes}`,
    "=== END_ATTACH ===",
  ].join("\n");
}

export function buildFleetHandoff(
  result: ChatgptSyncResult,
  goal: string,
  secrets: string[] = [],
): string {
  const controlApi = publicControlApi(result.controlApi);
  const ready = result.pads.filter((pad) => cloudSessionReady(pad));
  const lines = [
    "# Cyclone VMOS Fleet Handoff",
    "",
    `Generated: ${result.generatedAt}`,
    `Pads ready: ${ready.length} / ${result.pads.length}`,
    `CONTROL_API: ${controlApi}`,
    `DEFAULT_GOAL: ${goal}`,
    "",
    "## Driver instructions (for ChatGPT Custom GPT)",
    "1. You control these phones via Actions only (observe → act → observe).",
    "2. Pick DEVICE_ID / SESSION_ID / SESSION_TOKEN from the attach blocks below.",
    "3. Pass SESSION_TOKEN as X-Cyclone-Session-Token on every protected Action call.",
    "4. Never ask for SSH Connect Keys or VMOS API credentials.",
    "5. If ADB/mobile NOTES say missing/offline, tell the operator to re-run Sync fleet in Cyclone One.",
    "",
    "## Fleet summary",
  ];
  for (const pad of result.pads) {
    if (cloudSessionReady(pad)) {
      lines.push(`- ${pad.label}: DEVICE_ID=${pad.deviceId} ADB=${pad.adb} MOBILE=${pad.mobile} SESSION=${pad.sessionSource}`);
    } else {
      lines.push(`- ${pad.label}: NOT READY — ${sanitizeError(pad.error)}`);
    }
  }
  lines.push("", "## Attach blocks (paste into ChatGPT)");
  for (const pad of ready) {
    lines.push("", buildAttachBlock(pad, controlApi, goal));
  }
  const text = `${lines.join("\n")}\n`;
  assertHandoffSafe(text, secrets);
  return text;
}

export function padNotes(pad: ChatgptPadStatus): string {
  const notes: string[] = [];
  if (pad.mobile === "missing") notes.push("Cyclone Mobile missing.");
  if (pad.mobile === "installed") notes.push("Cyclone Mobile installed but not running.");
  if (pad.adb !== "device") notes.push(`ADB ${pad.adb}.`);
  if (pad.sessionSource === "local-stub") {
    notes.push("Cloud AI session unavailable; re-run Sync fleet after fixing the Gateway connection.");
  }
  return notes.length ? notes.join(" ") : "none";
}

export function sanitizeError(message: string | undefined): string {
  if (!message) return "Cloud AI session is not ready. Re-run Sync fleet and follow the pad hint.";
  let text = message;
  for (const name of SECRET_FIELD_NAMES) {
    text = text.replace(new RegExp(`${name}\\s*[:=]\\s*\\S+`, "ig"), "[redacted]");
  }
  return text.slice(0, 220);
}

export function readyCount(result: ChatgptSyncResult | null | undefined): number {
  return result?.pads.filter((pad) => cloudSessionReady(pad)).length ?? 0;
}

function validPort(value: number): boolean {
  return Number.isInteger(value) && value > 0 && value <= 65535;
}

function randomHex(bytes: number): string {
  const alphabet = "0123456789abcdef";
  let out = "";
  for (let i = 0; i < bytes * 2; i += 1) out += alphabet[Math.floor(Math.random() * 16)];
  return out;
}
