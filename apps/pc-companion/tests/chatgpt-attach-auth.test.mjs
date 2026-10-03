import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CUSTOM_GPT_SETUP_HINTS } from "../.test-dist/core/chatgptAttach.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pack = path.join(root, "src-tauri", "resources", "chatgpt-attach");

test("Custom GPT setup does not require rotating editor auth every Sync", () => {
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((line) => /Authentication = None/i.test(line)));
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((line) => /X-Cyclone-Session-Token/.test(line)));

  const instructions = fs.readFileSync(path.join(pack, "CUSTOM_GPT_INSTRUCTIONS.md"), "utf8");
  assert.match(instructions, /Authentication.*None/i);
  assert.match(instructions, /X-Cyclone-Session-Token/);
  assert.match(instructions, /SESSION_TOKEN/);
  assert.match(instructions, /Never echo `SESSION_TOKEN`/);
});

test("Action schema takes the short-lived session token as an explicit protected-call header", () => {
  const openapi = fs.readFileSync(path.join(pack, "openapi-cloud-control.yaml"), "utf8");
  assert.match(openapi, /name: X-Cyclone-Session-Token/);
  assert.match(openapi, /in: header/);
  assert.match(openapi, /required: true/);
  assert.doesNotMatch(openapi, /BearerAuth/);
  assert.doesNotMatch(openapi, /operationId: createSession/);
  assert.match(openapi, /operationId: getDeviceStatus/);
  assert.match(openapi, /operationId: observe/);
  assert.match(openapi, /operationId: tap/);
});

test("connection guide keeps gateway and VMOS secrets out of GPT setup", () => {
  const connect = fs.readFileSync(path.join(pack, "CONNECT.md"), "utf8");
  assert.match(connect, /Authentication.*None/i);
  assert.match(connect, /X-Cyclone-Session-Token/);
  assert.match(connect, /Do not store SSH Connect Keys, VMOS AccessKeys, the local Cyclone gateway bearer/);
});
