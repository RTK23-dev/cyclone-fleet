import test from "node:test";
import assert from "node:assert/strict";
import {
  KEEPALIVE_TYPE,
  MAX_SOFT_FRAME_FAILURES,
  STALE_FRAME_TIMEOUT_MS,
  STREAM_FIRST_FRAME_TIMEOUT_MS,
  STREAM_HANDSHAKE_TIMEOUT_MS,
  STREAM_RECOVERY_TIMEOUT_MS,
  resolveImageCodec,
  streamCloseIsTerminal,
  streamErrorIsRecoverable,
} from "../.test-dist/video/webcodecsH264Decoder.js";
import { FALLBACK_WS_RETRY_MS, LivePhoneController } from "../.test-dist/video/livePhoneController.js";
import { cacheBustedFrameUrl } from "../.test-dist/video/fallbackFrameDecoder.js";
import { operatorInputEnabled, operatorInputPausedMessage } from "../.test-dist/core/operatorInput.js";

test("fallback previews preserve data and blob URLs while HTTP snapshots bypass stale caches", () => {
  assert.equal(cacheBustedFrameUrl("data:image/svg+xml,phone", 42), "data:image/svg+xml,phone");
  assert.equal(cacheBustedFrameUrl("blob:http://127.0.0.1/frame", 42), "blob:http://127.0.0.1/frame");
  assert.equal(cacheBustedFrameUrl("http://127.0.0.1/frame?profile=focus", 42), "http://127.0.0.1/frame?profile=focus&t=42");
});

test("recoverable stream errors keep the socket contract while terminal closes stay terminal", () => {
  assert.equal(streamErrorIsRecoverable({ type: "stream.error", retryable: true }), true);
  assert.equal(streamErrorIsRecoverable({ type: "stream.error", retryable: false }), false);
  assert.equal(streamErrorIsRecoverable({ type: "stream.init" }), false);
  assert.equal(streamCloseIsTerminal(4401), true);
  assert.equal(streamCloseIsTerminal(1006), false);
});

test("stream health deadlines bound recoveries and stale live frames", () => {
  assert.equal(KEEPALIVE_TYPE, "stream.keepalive");
  assert.equal(STREAM_RECOVERY_TIMEOUT_MS, 20000);
  assert.equal(STALE_FRAME_TIMEOUT_MS, 15000);
  assert.equal(STREAM_HANDSHAKE_TIMEOUT_MS, 20000);
  assert.equal(STREAM_FIRST_FRAME_TIMEOUT_MS, 20000);
  assert.equal(MAX_SOFT_FRAME_FAILURES, 3);
});

test("operator input stays available while JPEG preview is warming or reconnecting", () => {
  assert.equal(operatorInputEnabled("CONNECTING", "READY"), true);
  assert.equal(operatorInputEnabled("RECONNECTING", "READY"), true);
  assert.equal(operatorInputEnabled("LIVE", "READY"), true);
  assert.equal(operatorInputEnabled("STREAM_ERROR", "READY"), true);
  assert.equal(operatorInputEnabled("UNAVAILABLE", "READY"), false);
  assert.equal(operatorInputEnabled("LIVE", "SLEEPING"), false);
  assert.equal(operatorInputPausedMessage("UNAVAILABLE"), "Input paused — stream down");
});

test("JPEG and img stream inits are accepted without assuming video/avc", () => {
  const jpeg = Uint8Array.from([0xFF, 0xD8, 0xFF, 0xE0, 0, 1]);
  assert.equal(resolveImageCodec("image/jpeg", jpeg.buffer), "image/jpeg");
  assert.equal(resolveImageCodec("img", jpeg.buffer), "image/jpeg");
  assert.equal(resolveImageCodec("", jpeg.buffer), "image/jpeg");
  assert.equal(resolveImageCodec("video/avc", jpeg.buffer), null);
});

test("unavailable websocket switches to the snapshot fallback and later retries the ws", async () => {
  const originalWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    const events = [];
    const image = { hidden: false, src: "", onload: null, onerror: null };
    const target = { container: {}, canvas: { hidden: false }, fallbackImage: image };
    const service = {
      mode: "real",
      sendControl: async () => ({ ok: true, deviceId: "phone" }),
      reportStreamDiagnostic: async () => {},
      getVideoUrl: () => "ws://127.0.0.1/video",
      getVideoProtocols: () => [],
      getFallbackFrameUrl: () => "http://127.0.0.1:8765/v1/devices/phone/stream/snapshot?profile=focus",
    };
    const device = {
      id: "phone",
      name: "Pixel",
      state: "READY",
      paired: true,
      connectionLabel: "Ready",
      lastSeenEpochMs: 0,
      video: { mode: "SCREENSHOT", width: 1080, height: 2400, rotationDegrees: 0 },
      capabilities: { keyboard: true, clipboard: true, clipboardSync: false, reconnect: true },
    };
    const realFactory = (input) => ({
      start() { input.callbacks.onState("UNAVAILABLE"); },
      stop() {},
    });
    const controller = new LivePhoneController(service, device, "focus", target, () => {}, (event) => events.push(event), realFactory);
    controller.start();
    await Promise.resolve();
    assert.ok(events.some((event) => event.code === "FALLBACK_PREVIEW"), "fallback preview should activate");
    assert.match(image.src, /stream\/snapshot\?profile=focus/);
    assert.equal(FALLBACK_WS_RETRY_MS, 30000);
    controller.stop();
  } finally {
    globalThis.window = originalWindow;
  }
});

test("specific stream codes are not overwritten with FRAME_RENDER_ERROR", async () => {
  const originalWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    const events = [];
    const target = { container: {}, canvas: { hidden: false }, fallbackImage: { hidden: true } };
    const service = {
      mode: "real",
      sendControl: async () => ({ ok: true, deviceId: "phone" }),
      reportStreamDiagnostic: async () => {},
      getVideoUrl: () => "ws://127.0.0.1/video",
      getVideoProtocols: () => [],
      getFallbackFrameUrl: () => "",
    };
    const device = {
      id: "phone",
      name: "Pixel",
      state: "READY",
      paired: true,
      connectionLabel: "Ready",
      lastSeenEpochMs: 0,
      video: { mode: "SCREENSHOT", width: 1080, height: 2400, rotationDegrees: 0 },
      capabilities: { keyboard: true, clipboard: true, clipboardSync: false, reconnect: true },
    };
    const realFactory = (input) => ({
      start() {
        input.callbacks.onDiagnostic({ stage: "client.stream.timeout", code: "STREAM_INIT_TIMEOUT", retryable: true });
        input.callbacks.onError(new Error("STREAM_INIT_TIMEOUT"));
        input.callbacks.onState("STREAM_ERROR");
      },
      stop() {},
    });
    const controller = new LivePhoneController(service, device, "focus", target, () => {}, (event) => events.push(event), realFactory);
    controller.start();
    await Promise.resolve();
    const codes = events.map((event) => event.code).filter(Boolean);
    assert.ok(codes.includes("STREAM_INIT_TIMEOUT"));
    assert.equal(codes.includes("FRAME_RENDER_ERROR"), false);
    controller.stop();
  } finally {
    globalThis.window = originalWindow;
  }
});
