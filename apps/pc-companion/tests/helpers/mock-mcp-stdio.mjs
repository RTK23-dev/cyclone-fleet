#!/usr/bin/env node
"use strict";

let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  for (;;) {
    const newline = buffer.indexOf("\n");
    if (newline < 0) break;
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      continue;
    }
    if (!Object.prototype.hasOwnProperty.call(message, "id")) continue;
    if (message.method === "initialize") {
      reply(message.id, {
        protocolVersion: "2025-03-26",
        capabilities: { tools: {} },
        serverInfo: { name: "cyclone-phone", version: "test" },
        instructions: "full tool list including phone_act",
      });
    } else if (message.method === "tools/list") {
      reply(message.id, {
        tools: [
          { name: "phone_status", description: "status" },
          { name: "phone_observe", description: "observe" },
          { name: "phone_act", description: "act" },
        ],
      });
    } else if (message.method === "tools/call") {
      reply(message.id, { content: [{ type: "text", text: "acted" }] });
    } else {
      process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "unknown" } })}\n`);
    }
  }
});

function reply(id, result) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}
