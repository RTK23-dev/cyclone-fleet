import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  classifyTunnelState,
  formatSmokeLog,
  healthUrlFromPublic,
  MCP_TUNNEL_CONNECTOR_CHECKLIST,
  MCP_TUNNEL_STDIO_NOTE,
  MCP_TUNNEL_TITLE,
  mcpUrlFromPublic,
  normalizeTunnelStatus,
  redactTunnelText,
  tokenLast4,
} from "../.test-dist/core/mcpTunnel.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

const packRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../src-tauri/resources/mcp-tunnel",
);

test("remote MCP Settings copy names ChatGPT/Grok chat and keeps local stdio", () => {
  assert.equal(MCP_TUNNEL_TITLE, "Remote MCP (ChatGPT / Grok chat)");
  assert.match(MCP_TUNNEL_STDIO_NOTE, /~\/\.grok\/config\.toml/);
  assert.match(MCP_TUNNEL_STDIO_NOTE, /stdio/);
  assert.ok(MCP_TUNNEL_CONNECTOR_CHECKLIST.length >= 4);
  assert.match(MCP_TUNNEL_CONNECTOR_CHECKLIST.join(" "), /Bearer/);
  assert.match(MCP_TUNNEL_CONNECTOR_CHECKLIST.join(" "), /Never .*authentication/);
});

test("token last-4 never returns the full bearer", () => {
  const token = "abcdefghijklmnopqrstuvwxyz012345";
  assert.equal(tokenLast4(token), "2345");
  assert.notEqual(tokenLast4(token), token);
  assert.equal(tokenLast4("abc"), null);
});

test("public MCP and health URLs are derived without leaking tokens", () => {
  assert.equal(mcpUrlFromPublic("https://words.trycloudflare.com"), "https://words.trycloudflare.com/mcp");
  assert.equal(mcpUrlFromPublic("https://words.trycloudflare.com/mcp"), "https://words.trycloudflare.com/mcp");
  assert.equal(healthUrlFromPublic("https://words.trycloudflare.com/mcp"), "https://words.trycloudflare.com/health");
  assert.equal(redactTunnelText("Authorization: Bearer secret-token-value-1234", "secret-token-value-1234"), "Authorization: Bearer ***");
});

test("tunnel state is fail-closed: health alone is degraded, not running", () => {
  assert.equal(classifyTunnelState({ gatewayAlive: false, cloudflaredAlive: false, healthOk: false, hasPublicUrl: false }), "stopped");
  assert.equal(classifyTunnelState({ gatewayAlive: true, cloudflaredAlive: false, healthOk: true, hasPublicUrl: false }), "degraded");
  assert.equal(classifyTunnelState({ gatewayAlive: true, cloudflaredAlive: true, healthOk: true, hasPublicUrl: true }), "running");
});

test("normalizeTunnelStatus defaults to readonly and loopback MCP", () => {
  const status = normalizeTunnelStatus({ state: "running", publicUrl: "https://host.trycloudflare.com", tokenLast4: "ab12" });
  assert.equal(status.mode, "readonly");
  assert.equal(status.mcpUrl, "https://host.trycloudflare.com/mcp");
  assert.equal(status.healthUrl, "https://host.trycloudflare.com/health");
  assert.equal(status.grokStdioUntouched, true);
  assert.doesNotMatch(JSON.stringify(status), /GATEWAY_BEARER_TOKEN=/);
});

test("smoke log formatter never reprints the full bearer", () => {
  const token = "abcdefghijklmnopqrstuvwxyz012345";
  const log = formatSmokeLog({
    ok: true,
    message: "SMOKE PASSED",
    checks: [{ name: "initialize 200 with bearer", ok: true, detail: `Authorization: Bearer ${token}` }],
  }, token);
  assert.match(log, /PASS initialize 200 with bearer/);
  assert.doesNotMatch(log, new RegExp(token));
  assert.match(log, /\*\*\*/);
});

test("mock Settings service can start, smoke, copy last-4, and stop", async () => {
  const service = new MockDesktopService(1);
  const started = await service.startMcpTunnel("readonly");
  assert.equal(started.state, "running");
  assert.equal(started.mcpUrl.endsWith("/mcp"), true);
  const smoke = await service.smokeMcpTunnel();
  assert.equal(smoke.ok, true);
  assert.ok(smoke.checks.some((check) => check.name.includes("401")));
  const secret = await service.copyMcpTunnelToken();
  assert.equal(secret.last4, secret.token.slice(-4));
  assert.ok(secret.token.length > 8);
  const stopped = await service.stopMcpTunnel();
  assert.equal(stopped.state, "stopped");
});

test("Settings page keeps existing diagnostic cards and adds the remote MCP terminal", () => {
  const page = fs.readFileSync(
    path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/pages/settingsPage.ts"),
    "utf8",
  );
  for (const title of [
    "PC Companion",
    "Install path",
    "Phones",
    "MCP live display",
    "ADB connection",
    "Auto-detect",
    "Bridge recovery",
    "Live USB crash monitor",
    "Connection debug flow",
    "Privacy",
  ]) {
    assert.match(page, new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.match(page, /MCP_TUNNEL_TITLE/);
  assert.match(page, /createRemoteMcpCard/);
  assert.match(page, /Copy token/);
  assert.match(page, /Start tunnel/);
  assert.match(page, /Smoke/);
  assert.equal(MCP_TUNNEL_TITLE, "Remote MCP (ChatGPT / Grok chat)");
});

test("bundled tunnel pack is fail-closed and prefers Cyclone One", () => {
  const server = fs.readFileSync(path.join(packRoot, "gateway", "server.js"), "utf8");
  const start = fs.readFileSync(path.join(packRoot, "scripts", "start-tunnel.ps1"), "utf8");
  const example = fs.readFileSync(path.join(packRoot, "config", "gateway.env.example"), "utf8");
  assert.match(server, /Cyclone One/);
  assert.match(server, /refusing to bind/);
  assert.match(server, /Authorization: Bearer/);
  assert.match(example, /GATEWAY_MODE=readonly/);
  assert.match(start, /Does not touch ~\/\.grok\/config\.toml/);
  assert.doesNotMatch(start, /Write-Host \$token\b/);
  assert.match(start, /tokenLast4|last4/);
  assert.ok(!fs.existsSync(path.join(packRoot, "config", "gateway.env")), "live gateway.env must not be committed");
});
