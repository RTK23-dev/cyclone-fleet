import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  CHATGPT_ATTACH_TITLE,
  CUSTOM_GPT_SETUP_HINTS,
  HANDOFF_PUBLIC_FIELDS,
  SECRET_FIELD_NAMES,
  assertHandoffSafe,
  buildAttachBlock,
  buildFleetHandoff,
  cloudSessionReady,
  collectSecrets,
  bindOpenApiServer,
  connectionChecklist,
  emptyChatgptAttachConfig,
  handoffContainsForbidden,
  isEphemeralShareControlApi,
  isPlaceholderControlApi,
  isPublicControlApi,
  newPadDraft,
  parseVmosConnectCommand,
  publicControlApi,
  publicShareControlApi,
  resolveControlApi,
} from "../.test-dist/core/chatgptAttach.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

const secretPad = {
  id: "pad-1",
  label: "pad-1",
  ok: true,
  deviceId: "dev_vmos_pad1",
  serial: "localhost:63670",
  adb: "device",
  mobile: "running",
  sessionId: "sess-public",
  sessionToken: "tok-public-not-a-transport-secret",
  sessionSource: "control-api",
};

test("tab copy is ChatGPT Attach and lists Custom GPT setup hints", () => {
  assert.equal(CHATGPT_ATTACH_TITLE, "ChatGPT Attach");
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((item) => /OpenAPI/i.test(item)));
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((item) => /SESSION_TOKEN/.test(item)));
});

test("handoff fields are the public attach contract only", () => {
  assert.deepEqual([...HANDOFF_PUBLIC_FIELDS], [
    "DEVICE_ID", "SESSION_ID", "SESSION_TOKEN", "CONTROL_API", "MOBILE", "ADB", "GOAL", "NOTES",
  ]);
  assert.ok(SECRET_FIELD_NAMES.includes("connectKey"));
  assert.ok(SECRET_FIELD_NAMES.includes("accessKey"));
});

test("fleet handoff never includes SSH/VMOS secrets or serials", () => {
  const config = emptyChatgptAttachConfig();
  config.pads = [newPadDraft()];
  config.pads[0].connectKey = "SUPER-CONNECT-KEY-DO-NOT-LEAK";
  config.vmosApiKey = "VMOSACCESSKEY123456";
  const result = {
    ok: true,
    controlApi: "https://control.example/cloud",
    generatedAt: "2026-09-12 12:00:00",
    pads: [secretPad],
  };
  const text = buildFleetHandoff(result, "Open Settings", collectSecrets(config));
  assert.match(text, /CYCLONE_VMOS_ATTACH_v1/);
  assert.match(text, /DEVICE_ID: dev_vmos_pad1/);
  assert.match(text, /SESSION_TOKEN: tok-public-not-a-transport-secret/);
  assert.doesNotMatch(text, /SERIAL:/);
  assert.doesNotMatch(text, /connectKey/i);
  assert.doesNotMatch(text, /AccessKey\s*:/i);
  assert.doesNotMatch(text, /SUPER-CONNECT-KEY-DO-NOT-LEAK/);
  assert.doesNotMatch(text, /VMOSACCESSKEY123456/);
  assert.equal(handoffContainsForbidden(text, collectSecrets(config)).length, 0);
  assert.doesNotThrow(() => assertHandoffSafe(text, collectSecrets(config)));
});

test("failed pads stay in the summary without attach blocks or secrets", () => {
  const text = buildFleetHandoff({
    ok: false,
    controlApi: "",
    generatedAt: "now",
    pads: [{
      id: "pad-2",
      label: "pad-2",
      ok: false,
      deviceId: "",
      adb: "failed",
      mobile: "unknown",
      sessionId: "",
      sessionToken: "",
      sessionSource: "local-stub",
      error: "connectKey=should-strip ssh failed",
    }],
  }, "Wait");
  assert.match(text, /NOT READY — \[redacted\]/);
  assert.doesNotMatch(text, /should-strip/);
  assert.doesNotMatch(text, /CYCLONE_VMOS_ATTACH_v1/);
});

test("local stub sessions fail closed and never enter a ChatGPT handoff", () => {
  const fake = {
    ...secretPad,
    sessionSource: "local-stub",
    sessionId: "fake-session",
    sessionToken: "fake-token",
  };
  assert.equal(cloudSessionReady(fake), false);
  const text = buildFleetHandoff({
    ok: true,
    controlApi: "https://control.example/cloud",
    generatedAt: "now",
    pads: [fake],
  }, "Observe");
  assert.match(text, /Pads ready: 0 \/ 1/);
  assert.doesNotMatch(text, /fake-session/);
  assert.doesNotMatch(text, /fake-token/);
  assert.doesNotMatch(text, /CYCLONE_VMOS_ATTACH_v1/);
});

test("attach block uses only the public field set", () => {
  const block = buildAttachBlock(secretPad, "https://control.example/cloud", "Observe");
  for (const field of HANDOFF_PUBLIC_FIELDS) assert.match(block, new RegExp(`${field}:`));
  assert.doesNotMatch(block, /LABEL:/);
  assert.doesNotMatch(block, /SERIAL:/);
});

test("VMOS Connect command fills SSH and ADB tunnel fields without a secret", () => {
  const parsed = parseVmosConnectCommand(
    "ssh -oStrictHostKeyChecking=accept-new s@192.0.2.10 -p 1824 -L 63670:localhost:1 -Nf",
  );
  assert.deepEqual(parsed, {
    sshHost: "192.0.2.10",
    sshPort: 1824,
    sshUser: "s",
    localAdbPort: 63670,
    remoteAdbSpec: "localhost:1",
    serial: "localhost:63670",
  });
  assert.equal(parseVmosConnectCommand("adb connect localhost:63670"), null);
  assert.equal(parseVmosConnectCommand("ssh s@192.0.2.10 -p 1824"), null);
});

test("public control API falls back to loopback cloud stub then placeholder", () => {
  assert.equal(publicControlApi(" https://mine.example/cloud/ "), "https://mine.example/cloud");
  assert.equal(publicControlApi("", 8791), "http://127.0.0.1:8791/cloud");
  assert.equal(publicControlApi(""), "https://CONTROL_API_HOST_PLACEHOLDER");
});

test("public CONTROL_API rejects localhost while accepting HTTPS share/custom hosts", () => {
  assert.equal(isPublicControlApi("http://127.0.0.1:8765/cloud"), false);
  assert.equal(isPublicControlApi("https://localhost/cloud"), false);
  assert.equal(isPublicControlApi("https://abc.trycloudflare.com/cloud"), true);
  assert.equal(isPublicControlApi("https://control.example/cloud"), true);
  assert.equal(isEphemeralShareControlApi("https://abc.trycloudflare.com/cloud"), true);
  assert.equal(isEphemeralShareControlApi("https://control.example/cloud"), false);
});

test("resolveControlApi prefers stable custom, but live share beats stale trycloudflare", () => {
  assert.equal(resolveControlApi({
    configured: "https://named.example/cloud",
    shareUrl: "https://ephemeral.trycloudflare.com",
    localBase: "http://127.0.0.1:8765/cloud",
  }), "https://named.example/cloud");
  assert.equal(resolveControlApi({
    configured: "https://old-share.trycloudflare.com/cloud",
    shareUrl: "https://fresh-share.trycloudflare.com",
    localBase: "http://127.0.0.1:8765/cloud",
  }), "https://fresh-share.trycloudflare.com/cloud");
  assert.equal(resolveControlApi({
    configured: "",
    shareUrl: "https://ephemeral.trycloudflare.com/mcp",
    localBase: "http://127.0.0.1:8765",
  }), "https://ephemeral.trycloudflare.com/cloud");
  assert.equal(resolveControlApi({
    configured: "https://CONTROL_API_HOST_PLACEHOLDER",
    localBase: "http://127.0.0.1:8791",
  }), "http://127.0.0.1:8791/cloud");
  assert.equal(publicShareControlApi("https://abc.trycloudflare.com"), "https://abc.trycloudflare.com/cloud");
  assert.equal(isPlaceholderControlApi(""), true);
  assert.equal(isPlaceholderControlApi("http://127.0.0.1:8765/cloud"), false);
});

test("connection ready checklist covers transport, real Cloud AI session, public CONTROL_API, handoff", () => {
  const items = connectionChecklist({
    pads: [secretPad],
    controlApi: "https://control.example/cloud",
    controlApiReachable: true,
    handoffCopied: true,
  });
  assert.deepEqual(items.map((item) => item.id), ["adb", "mobile", "session", "control", "handoff"]);
  assert.ok(items.every((item) => item.ok));
  const localApi = connectionChecklist({
    pads: [secretPad],
    controlApi: "http://127.0.0.1:8765/cloud",
    controlApiReachable: true,
  });
  assert.equal(localApi.find((item) => item.id === "control")?.ok, false);
  const fakeSession = connectionChecklist({
    pads: [{ ...secretPad, sessionSource: "local-stub" }],
    controlApi: "https://control.example/cloud",
    controlApiReachable: true,
  });
  assert.equal(fakeSession.find((item) => item.id === "session")?.ok, false);
  const empty = connectionChecklist({});
  assert.ok(empty.every((item) => !item.ok));
});

test("OpenAPI server URL is rewritten to the live CONTROL_API", () => {
  const bound = bindOpenApiServer(
    "servers:\n  - url: https://CONTROL_API_HOST_PLACEHOLDER\n",
    "https://abc.trycloudflare.com/cloud",
  );
  assert.match(bound, /https:\/\/abc\.trycloudflare\.com\/cloud/);
  assert.doesNotMatch(bound, /CONTROL_API_HOST_PLACEHOLDER/);
});

test("bundled Custom GPT pack has OpenAPI Actions and hardened VMOS tunnel bootstrap", () => {
  const pack = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src-tauri/resources/chatgpt-attach");
  const openapi = fs.readFileSync(path.join(pack, "openapi-cloud-control.yaml"), "utf8");
  const instructions = fs.readFileSync(path.join(pack, "CUSTOM_GPT_INSTRUCTIONS.md"), "utf8");
  const sync = fs.readFileSync(path.join(pack, "scripts", "Sync-VmosFleet.ps1"), "utf8");
  const connect = fs.readFileSync(path.join(pack, "CONNECT.md"), "utf8");
  assert.match(openapi, /operationId: observe/);
  assert.match(openapi, /operationId: tap/);
  assert.match(openapi, /PhoneToolExecutor/);
  assert.doesNotMatch(openapi, /simulateTouch/);
  assert.match(instructions, /CYCLONE_VMOS_ATTACH_v1/);
  assert.doesNotMatch(instructions, /AccessKey:/);
  assert.match(sync, /\$mobilePid\s*=/);
  assert.doesNotMatch(sync, /\$pid\s*=/i);
  assert.match(sync, /ExitOnForwardFailure=yes/);
  assert.match(sync, /PreferredAuthentications=keyboard-interactive,password/);
  assert.match(sync, /finally\s*\{/i);
  assert.match(sync, /Remove-Item -Force \$keyFile, \$askPass/);
  assert.doesNotMatch(sync, /'-Nf'/);
  assert.match(connect, /Share to ChatGPT/);
  assert.match(connect, /Bearer/);
});

test("mock service can save pads, sync, and copy a safe handoff", async () => {
  const service = new MockDesktopService(1);
  const saved = await service.saveChatgptAttachConfig({
    controlApiBase: "https://control.example/cloud",
    defaultGoal: "Prove observe",
    pads: [{ ...newPadDraft(), connectKey: "PAD-SECRET-KEY" }],
  });
  assert.equal(saved.pads[0].hasConnectKey, true);
  assert.equal(saved.pads[0].connectKey, undefined);
  const synced = await service.syncChatgptAttachFleet();
  assert.equal(synced.ok, true);
  assert.equal(synced.pads[0].ok, true);
  assert.equal(synced.controlApi, "https://control.example/cloud");
  assert.equal(cloudSessionReady(synced.pads[0]), true);
  const local = await service.probeCloudControl();
  assert.equal(local.ok, true);
  assert.match(local.localBase, /127\.0\.0\.1:8765\/cloud/);
  const shared = await service.chatgptShareStart();
  assert.equal(shared.ok, true);
  assert.match(shared.url, /trycloudflare\.com\/cloud$/);
  const markdown = buildFleetHandoff({ ...synced, controlApi: shared.url }, saved.defaultGoal, ["PAD-SECRET-KEY"]);
  const copied = await service.copyChatgptHandoff(markdown);
  assert.equal(copied.ok, true);
  assert.doesNotMatch(copied.markdown, /PAD-SECRET-KEY/);
  assert.match(markdown, /CONTROL_API: https:\/\/mock-share\.trycloudflare\.com\/cloud/);
});