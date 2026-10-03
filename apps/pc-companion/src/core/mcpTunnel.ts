export type McpTunnelState = "running" | "stopped" | "degraded";
export type McpTunnelMode = "readonly" | "full";

export interface McpTunnelSmokeCheck {
  name: string;
  ok: boolean;
  detail: string;
}

export interface McpTunnelSmokeResult {
  ok: boolean;
  checks: McpTunnelSmokeCheck[];
  message: string;
}

export interface McpTunnelStatus {
  ok: boolean;
  state: McpTunnelState;
  mode: McpTunnelMode;
  tokenLast4: string | null;
  publicUrl: string | null;
  mcpUrl: string | null;
  healthUrl: string | null;
  localMcpUrl: string;
  localHealthUrl: string;
  gatewayAlive: boolean;
  cloudflaredAlive: boolean;
  healthOk: boolean;
  mcpBinaryOk?: boolean;
  mcpBinary?: string | null;
  nodeOk?: boolean;
  cloudflaredOk?: boolean;
  installPath: string;
  grokStdioUntouched: boolean;
  message: string;
  error?: string | null;
  rotated?: boolean;
}

export interface McpTunnelToken {
  token: string;
  last4: string;
}

export const MCP_TUNNEL_TITLE = "Remote MCP (ChatGPT / Grok chat)";
export const MCP_TUNNEL_SUBTITLE =
  "Start the public HTTPS bridge for ChatGPT and grok.com chat. Local Grok Build and Cursor keep using stdio MCP.";
export const MCP_TUNNEL_FULL_WARNING =
  "Full mode exposes mutating phone tools (including phone_act) to anyone who holds the bearer token. Readonly is the Phase 1 default.";
export const MCP_TUNNEL_STDIO_NOTE =
  "Do not paste this public URL into ~/.grok/config.toml or Cursor mcp.json. Those stay on CycloneAgentMCP.exe serve over stdio.";
export const MCP_TUNNEL_CONNECTOR_CHECKLIST = [
  "Start the tunnel and wait until status is Running.",
  "Copy the MCP URL (…/mcp) and Copy token. Quick tunnels mint a new hostname on every Start/Restart.",
  "ChatGPT web → Developer mode → Apps / Connectors → Remote MCP. Paste the URL. Auth = Bearer token. Never “No authentication”.",
  "grok.com/connectors → same URL + bearer. xAI rejects localhost.",
  "Leave ~/.grok/config.toml and Cursor mcp.json on local stdio.",
];

export const LOCAL_MCP_URL = "http://127.0.0.1:8787/mcp";
export const LOCAL_HEALTH_URL = "http://127.0.0.1:8787/health";

export function tokenLast4(token: string | null | undefined): string | null {
  if (!token || token.length < 4) return null;
  return token.slice(-4);
}

export function mcpUrlFromPublic(publicUrl: string | null | undefined): string | null {
  if (!publicUrl) return null;
  const trimmed = publicUrl.trim().replace(/\/+$/, "");
  if (!trimmed) return null;
  return trimmed.endsWith("/mcp") ? trimmed : `${trimmed}/mcp`;
}

export function healthUrlFromPublic(publicUrl: string | null | undefined): string | null {
  if (!publicUrl) return null;
  const trimmed = publicUrl.trim().replace(/\/+$/, "").replace(/\/mcp$/, "");
  if (!trimmed) return null;
  return `${trimmed}/health`;
}

export function classifyTunnelState(flags: {
  gatewayAlive: boolean;
  cloudflaredAlive: boolean;
  healthOk: boolean;
  hasPublicUrl: boolean;
}): McpTunnelState {
  if (flags.gatewayAlive && flags.healthOk && flags.cloudflaredAlive && flags.hasPublicUrl) {
    return "running";
  }
  if (flags.gatewayAlive || flags.cloudflaredAlive || flags.healthOk || flags.hasPublicUrl) {
    return "degraded";
  }
  return "stopped";
}

export function friendlyTunnelState(state: McpTunnelState): string {
  if (state === "running") return "Running";
  if (state === "degraded") return "Degraded";
  return "Stopped";
}

export function redactTunnelText(text: string, token?: string | null): string {
  if (!token || token.length < 16) return text;
  return text.split(token).join("***");
}

export function normalizeTunnelStatus(raw: Partial<McpTunnelStatus> | null | undefined): McpTunnelStatus {
  const publicUrl = raw?.publicUrl ?? null;
  const gatewayAlive = Boolean(raw?.gatewayAlive);
  const cloudflaredAlive = Boolean(raw?.cloudflaredAlive);
  const healthOk = Boolean(raw?.healthOk);
  const state = raw?.state && ["running", "stopped", "degraded"].includes(raw.state)
    ? raw.state
    : classifyTunnelState({
        gatewayAlive,
        cloudflaredAlive,
        healthOk,
        hasPublicUrl: Boolean(publicUrl),
      });
  const mode: McpTunnelMode = raw?.mode === "full" ? "full" : "readonly";
  return {
    ok: raw?.ok !== false,
    state,
    mode,
    tokenLast4: raw?.tokenLast4 ?? null,
    publicUrl,
    mcpUrl: raw?.mcpUrl ?? mcpUrlFromPublic(publicUrl),
    healthUrl: raw?.healthUrl ?? healthUrlFromPublic(publicUrl),
    localMcpUrl: raw?.localMcpUrl || LOCAL_MCP_URL,
    localHealthUrl: raw?.localHealthUrl || LOCAL_HEALTH_URL,
    gatewayAlive,
    cloudflaredAlive,
    healthOk,
    mcpBinaryOk: raw?.mcpBinaryOk,
    mcpBinary: raw?.mcpBinary ?? null,
    nodeOk: raw?.nodeOk,
    cloudflaredOk: raw?.cloudflaredOk,
    installPath: raw?.installPath || "%LOCALAPPDATA%\\Cyclone One\\mcp-tunnel",
    grokStdioUntouched: raw?.grokStdioUntouched !== false,
    message: raw?.message || raw?.error || "Tunnel status is unavailable.",
    error: raw?.error ?? null,
    rotated: raw?.rotated,
  };
}

export function stoppedTunnelStatus(message = "Tunnel is stopped."): McpTunnelStatus {
  return normalizeTunnelStatus({
    ok: true,
    state: "stopped",
    mode: "readonly",
    grokStdioUntouched: true,
    message,
  });
}

export function formatSmokeLog(result: McpTunnelSmokeResult, token?: string | null): string {
  const lines = result.checks.map((check) => {
    const mark = check.ok ? "PASS" : "FAIL";
    const detail = check.detail ? ` — ${check.detail}` : "";
    return `${mark} ${check.name}${detail}`;
  });
  lines.push(result.message);
  return redactTunnelText(lines.join("\n"), token);
}
