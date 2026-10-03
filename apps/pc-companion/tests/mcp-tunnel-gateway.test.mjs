import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const serverJs = path.join(root, "src-tauri/resources/mcp-tunnel/gateway/server.js");
const mockMcp = path.join(root, "tests/helpers/mock-mcp-stdio.mjs");

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close((error) => (error ? reject(error) : resolve(port)));
    });
    server.on("error", reject);
  });
}

async function waitForHealth(base, token) {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${base}/health`);
      if (response.ok) {
        const body = await response.json();
        if (body.ok === true) return body;
      }
    } catch {
      /* retry */
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("gateway health did not come up");
}

function startGateway(port, extraEnv = {}) {
  const logDir = fs.mkdtempSync(path.join(os.tmpdir(), "cyclone-mcp-gw-"));
  const child = spawn(process.execPath, [serverJs], {
    cwd: path.dirname(path.dirname(serverJs)),
    env: {
      ...process.env,
      GATEWAY_HOST: "127.0.0.1",
      GATEWAY_PORT: String(port),
      GATEWAY_BEARER_TOKEN: "test-gateway-token-1234567890",
      GATEWAY_MODE: "readonly",
      MCP_COMMAND: process.execPath,
      MCP_ARGS: mockMcp,
      GATEWAY_LOG_FILE: path.join(logDir, "gateway.err"),
      GATEWAY_INIT_TIMEOUT_MS: "4000",
      GATEWAY_TOOL_TIMEOUT_MS: "4000",
      ...extraEnv,
    },
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  return { child, logDir };
}

test("auth gateway requires bearer, initializes, and hides phone_act in readonly", async (t) => {
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const token = "test-gateway-token-1234567890";
  const { child } = startGateway(port);
  t.after(() => {
    child.kill();
  });
  const health = await waitForHealth(base, token);
  assert.equal(health.ok, true);
  assert.equal(health.mode, "readonly");
  assert.doesNotMatch(JSON.stringify(health), new RegExp(token));

  const anon = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
  });
  assert.equal(anon.status, 401);

  const init = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      Accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "one-settings-test", version: "1.1.0" },
      },
    }),
  });
  assert.equal(init.status, 200);
  const session = init.headers.get("mcp-session-id");
  assert.ok(session);
  const initBody = await init.text();
  assert.match(initBody, /cyclone-phone/);
  assert.match(initBody, /not available on this endpoint/);

  const listed = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      Accept: "application/json, text/event-stream",
      "Mcp-Session-Id": session,
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} }),
  });
  assert.equal(listed.status, 200);
  const tools = await listed.json();
  const names = (tools.result?.tools ?? []).map((tool) => tool.name);
  assert.ok(names.includes("phone_status"));
  assert.ok(!names.includes("phone_act"));

  const act = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      Accept: "application/json, text/event-stream",
      "Mcp-Session-Id": session,
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "phone_act", arguments: {} } }),
  });
  assert.equal(act.status, 200);
  const blocked = await act.json();
  assert.match(blocked.error?.message ?? "", /blocked by gateway readonly allowlist/);
});

test("auth gateway refuses a non-loopback bind", async () => {
  const child = spawn(process.execPath, [serverJs], {
    cwd: path.dirname(path.dirname(serverJs)),
    env: {
      ...process.env,
      GATEWAY_HOST: "0.0.0.0",
      GATEWAY_PORT: "18787",
      GATEWAY_BEARER_TOKEN: "test-gateway-token-1234567890",
      MCP_COMMAND: process.execPath,
      MCP_ARGS: mockMcp,
      GATEWAY_LOG_FILE: path.join(os.tmpdir(), "cyclone-mcp-refuse.log"),
    },
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  const code = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill();
      resolve(-1);
    }, 5000);
    child.on("exit", (value) => {
      clearTimeout(timer);
      resolve(value ?? -1);
    });
  });
  assert.equal(code, 2);
});
