import test from "node:test";
import assert from "node:assert/strict";

import {
  REMOTE_MCP_FOREGROUND_SESSION_ID,
  UNIVERSAL_CLOUD_AGENT_PROMPT,
  remoteMcpConnectorInstructions,
} from "../.test-dist/core/cloudAgent.js";

test("universal cloud agent prompt teaches the foreground navigation contract", () => {
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /phone_devices/);
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /phone_observe/);
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /phone_act/);
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /phone_locate/);
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, new RegExp(`session_id="${REMOTE_MCP_FOREGROUND_SESSION_ID}"`));
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /verify/i);
  assert.match(UNIVERSAL_CLOUD_AGENT_PROMPT, /View only/);
});

test("connector instructions contain the remote URL but never invent or embed a bearer secret", () => {
  const url = "https://example.trycloudflare.com/mcp";
  const instructions = remoteMcpConnectorInstructions(url);
  assert.match(instructions, new RegExp(url.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(instructions, /Authentication: Bearer token/);
  assert.match(instructions, /Copy token/);
  assert.doesNotMatch(instructions, /GATEWAY_BEARER_TOKEN=/);
});

test("connector instructions remain usable before a tunnel URL exists", () => {
  const instructions = remoteMcpConnectorInstructions(null);
  assert.match(instructions, /copy the MCP URL from Cyclone One/);
});
