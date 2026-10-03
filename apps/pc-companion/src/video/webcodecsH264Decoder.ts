import type { VideoRenderer, VideoRendererFactoryInput } from "./decoder.js";

const HEADER_BYTES = 16;
const FLAG_CONFIG = 0x80000000;
const FLAG_KEYFRAME = 0x40000000;
const FLAG_MEDIA = 0x20000000;
const SEQUENCE_MASK = 0x1fffffff;
const MAX_DECODE_QUEUE = 4;
const MAX_PENDING_PACKETS = 4;

export const MAX_STREAM_RECONNECT_ATTEMPTS = 6;
/** Physical JPEG/screencap warmup was observed around 6s; do not hard-fail the 8s H.264 handshake. */
export const STREAM_HANDSHAKE_TIMEOUT_MS = 20_000;
export const STREAM_FIRST_FRAME_TIMEOUT_MS = 20_000;
export const STREAM_RECOVERY_TIMEOUT_MS = 20_000;
export const STALE_FRAME_TIMEOUT_MS = 15_000;
export const MAX_SOFT_FRAME_FAILURES = 3;
export const KEEPALIVE_TYPE = "stream.keepalive";

export interface CycloneVideoPacket {
  ptsUs: number;
  sequence: number;
  config: boolean;
  keyframe: boolean;
  media: boolean;
  payload: Uint8Array;
}

export function streamCloseIsTerminal(code: number): boolean {
  return code === 4400 || code === 4401 || code === 4404;
}

export function streamErrorIsRecoverable(message: Record<string, unknown>): boolean {
  return message.type === "stream.error" && message.retryable === true;
}

export function parseCyclonePacket(buffer: ArrayBuffer): CycloneVideoPacket {
  if (buffer.byteLength < HEADER_BYTES) throw new Error("Cyclone video packet is truncated");
  const view = new DataView(buffer);
  const ptsHi = view.getUint32(0, false);
  const ptsLo = view.getUint32(4, false);
  const ptsUs = ptsHi * 0x1_0000_0000 + ptsLo;
  if (!Number.isSafeInteger(ptsUs)) throw new Error("Cyclone video PTS exceeds JavaScript integer precision");
  const flagsSequence = view.getUint32(8, false);
  const payloadLength = view.getUint32(12, false);
  if (payloadLength <= 0 || HEADER_BYTES + payloadLength !== buffer.byteLength) {
    throw new Error("Invalid Cyclone video payload length");
  }
  return {
    ptsUs,
    sequence: flagsSequence & SEQUENCE_MASK,
    config: (flagsSequence & FLAG_CONFIG) !== 0,
    keyframe: (flagsSequence & FLAG_KEYFRAME) !== 0,
    media: (flagsSequence & FLAG_MEDIA) !== 0,
    payload: new Uint8Array(buffer, HEADER_BYTES, payloadLength),
  };
}

export function h264CodecFromAnnexB(data: Uint8Array): string | null {
  for (const nalu of annexBNalus(data)) {
    if (nalu.byteLength < 4 || (nalu[0] & 0x1f) !== 7) continue;
    const profile = nalu[1];
    const compatibility = nalu[2];
    const level = nalu[3];
    return `avc1.${hexByte(profile)}${hexByte(compatibility)}${hexByte(level)}`;
  }
  return null;
}

export function concatBytes(left: Uint8Array, right: Uint8Array): Uint8Array {
  const out = new Uint8Array(left.byteLength + right.byteLength);
  out.set(left, 0);
  out.set(right, left.byteLength);
  return out;
}

function annexBNalus(data: Uint8Array): Uint8Array[] {
  const starts: Array<{ offset: number; prefix: number }> = [];
  for (let i = 0; i + 3 < data.byteLength; i += 1) {
    if (data[i] !== 0 || data[i + 1] !== 0) continue;
    if (data[i + 2] === 1) {
      starts.push({ offset: i, prefix: 3 });
      i += 2;
    } else if (i + 3 < data.byteLength && data[i + 2] === 0 && data[i + 3] === 1) {
      starts.push({ offset: i, prefix: 4 });
      i += 3;
    }
  }
  const out: Uint8Array[] = [];
  for (let index = 0; index < starts.length; index += 1) {
    const start = starts[index];
    const end = index + 1 < starts.length ? starts[index + 1].offset : data.byteLength;
    const naluStart = start.offset + start.prefix;
    if (naluStart < end) out.push(data.subarray(naluStart, end));
  }
  return out;
}

function hexByte(value: number): string {
  return value.toString(16).padStart(2, "0").toUpperCase();
}

/** Real H.264 renderer for the Cyclone V3.3 media plane. */
export class WebCodecsH264Renderer implements VideoRenderer {
  private socket: WebSocket | null = null;
  private stopped = false;
  private reconnectTimer: number | null = null;
  private reconnectAttempt = 0;
  private codec = "";
  private streamSessionId: string | null = null;
  private healthTimer: number | null = null;
  private frameReported = false;
  private live = false;
  private decoder: VideoDecoder | null = null;
  private decoderGeneration = 0;
  private decoderReady = false;
  private configuring = false;
  private configBytes: Uint8Array | null = null;
  private waitingForKeyframe = true;
  private pendingPackets: CycloneVideoPacket[] = [];
  private imageDrawGeneration = 0;
  private width = 0;
  private height = 0;
  private consecutiveFrameFailures = 0;

  constructor(private readonly input: VideoRendererFactoryInput) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.decoderGeneration += 1;
    this.imageDrawGeneration += 1;
    this.clearReconnectTimer();
    this.clearHealthTimeout();
    this.resetDecoder();
    this.socket?.close();
    this.socket = null;
  }

  private open(): void {
    if (this.stopped) return;
    this.live = false;
    this.input.callbacks.onState(this.reconnectAttempt === 0 ? "CONNECTING" : "RECONNECTING");
    try {
      const socket = new WebSocket(this.input.streamUrl, this.input.streamProtocols);
      this.frameReported = false;
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      socket.onopen = () => {
        this.report({ stage: "client.ws.open", attempt: this.reconnectAttempt });
        this.armHealthTimeout(STREAM_HANDSHAKE_TIMEOUT_MS, "STREAM_INIT_TIMEOUT");
      };
      socket.onmessage = (event) => this.handleMessage(event.data, socket);
      socket.onerror = () => {
        this.report({
          stage: "client.ws.error",
          code: "WEBSOCKET_ERROR",
          attempt: this.reconnectAttempt,
          retryable: true,
        });
        this.failConnection(new Error("Phone stream unavailable"), socket, "WEBSOCKET_ERROR");
      };
      socket.onclose = (event) => {
        this.clearHealthTimeout();
        if (this.stopped || this.socket !== socket) return;
        this.socket = null;
        this.report({
          stage: "client.ws.close",
          code: closeCodeName(event.code),
          closeCode: event.code,
          attempt: this.reconnectAttempt,
          retryable: !streamCloseIsTerminal(event.code),
        });
        if (streamCloseIsTerminal(event.code)) {
          this.resetDecoder();
          this.input.callbacks.onState("UNAVAILABLE");
          return;
        }
        this.resetDecoder();
        this.scheduleReconnect();
      };
    } catch (error) {
      this.fail(error);
      this.scheduleReconnect();
    }
  }

  private handleMessage(data: string | ArrayBuffer | Blob, socket: WebSocket): void {
    try {
      if (typeof data === "string") {
        this.handleTextMessage(JSON.parse(data) as Record<string, unknown>, socket);
        return;
      }
      if (data instanceof Blob) {
        void data.arrayBuffer()
          .then((buffer) => this.handlePacket(buffer, socket))
          .catch((error) => this.failConnection(error, socket, "FRAME_DECODE_FAILED"));
        return;
      }
      void this.handlePacket(data, socket)
        .catch((error) => this.failConnection(error, socket, "FRAME_DECODE_FAILED"));
    } catch (error) {
      this.failConnection(error, socket, "STREAM_MESSAGE_INVALID");
    }
  }

  private handleTextMessage(message: Record<string, unknown>, socket: WebSocket): void {
    if (message.type === "stream.init" && typeof message.codec === "string") {
      const newSessionId = typeof message.sessionId === "string" ? message.sessionId : null;
      const sessionChanged = newSessionId !== this.streamSessionId;
      this.streamSessionId = newSessionId;
      this.codec = normalizeStreamCodec(message.codec);
      this.width = numberOrZero(message.width);
      this.height = numberOrZero(message.height);
      this.live = false;
      this.frameReported = false;
      this.waitingForKeyframe = true;
      this.pendingPackets = [];
      this.configBytes = null;
      this.decoderReady = false;
      this.configuring = false;
      if (sessionChanged || this.decoder) this.resetDecoder();
      this.report({
        stage: "client.stream.init",
        code: safeCode(message.backend),
        attempt: this.reconnectAttempt,
      });
      if (this.codec === "video/avc" && typeof VideoDecoder === "undefined") {
        this.report({ stage: "client.codec.unsupported", code: "WEBCODECS_UNAVAILABLE", retryable: false });
        this.input.callbacks.onState("UNAVAILABLE");
        try { socket.close(4001, "WEBCODECS_UNAVAILABLE"); } catch { /* browser owns close */ }
        return;
      }
      this.armHealthTimeout(STREAM_FIRST_FRAME_TIMEOUT_MS, "FIRST_FRAME_TIMEOUT");
      return;
    }
    if (message.type === "screen.state" && message.state === "SLEEPING") {
      this.clearHealthTimeout();
      this.reconnectAttempt = 0;
      this.live = false;
      this.report({ stage: "client.screen.sleeping", code: "PHONE_SCREEN_OFF" });
      this.input.callbacks.onState("SLEEPING");
      return;
    }
    if (message.type === "screen.state" && message.state === "AWAKE") {
      this.live = false;
      this.waitingForKeyframe = true;
      this.report({ stage: "client.screen.awake", code: "PHONE_SCREEN_ON" });
      this.armHealthTimeout(STREAM_FIRST_FRAME_TIMEOUT_MS, "FIRST_FRAME_TIMEOUT");
      return;
    }
    if (message.type === "stream.error") {
      const code = safeCode(message.code) ?? "STREAM_ERROR";
      const retryable = message.retryable === true;
      this.report({ stage: "client.stream.error", code, retryable });
      if (retryable) {
        this.live = false;
        this.input.callbacks.onState("RECONNECTING");
        this.armHealthTimeout(STREAM_RECOVERY_TIMEOUT_MS, "STREAM_RECOVERY_TIMEOUT");
      } else {
        this.clearHealthTimeout();
        this.live = false;
        this.input.callbacks.onState("UNAVAILABLE");
      }
      return;
    }
    if (message.type === KEEPALIVE_TYPE) {
      this.report({ stage: "client.stream.keepalive", attempt: this.reconnectAttempt });
      if (!this.live) this.armHealthTimeout(STREAM_RECOVERY_TIMEOUT_MS, "STREAM_RECOVERY_TIMEOUT");
    }
  }

  private async handlePacket(buffer: ArrayBuffer, socket: WebSocket): Promise<void> {
    if (this.stopped || this.socket !== socket) return;
    const imageCodec = resolveImageCodec(this.codec, buffer);
    if (imageCodec) {
      this.codec = imageCodec;
      await this.drawImagePacket(buffer);
      return;
    }
    if (this.codec !== "video/avc") {
      this.report({ stage: "client.codec.unsupported", code: "UNSUPPORTED_VIDEO_CODEC", retryable: false });
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    const packet = parseCyclonePacket(buffer);
    if (packet.config) {
      this.configBytes = packet.payload.slice();
      this.waitingForKeyframe = true;
      await this.configureDecoderIfPossible();
      return;
    }
    if (!packet.media) throw new Error("H.264 packet missing media flag");
    if (this.waitingForKeyframe && !packet.keyframe) {
      this.report({ stage: "client.decode.drop", code: "WAITING_KEYFRAME", retryable: true });
      return;
    }
    if (!this.decoderReady) {
      if (this.pendingPackets.length >= MAX_PENDING_PACKETS) this.pendingPackets.shift();
      this.pendingPackets.push(clonePacket(packet));
      await this.configureDecoderIfPossible();
      return;
    }
    this.decodePacket(packet);
  }

  private async configureDecoderIfPossible(): Promise<void> {
    if (this.decoderReady || this.configuring || !this.configBytes || this.stopped) return;
    const configBytes = this.configBytes;
    const codec = h264CodecFromAnnexB(configBytes);
    if (!codec) throw new Error("H.264 configuration does not contain an SPS");
    this.configuring = true;
    try {
      const config: VideoDecoderConfig = {
        codec,
        codedWidth: this.width || undefined,
        codedHeight: this.height || undefined,
        hardwareAcceleration: "prefer-hardware",
        optimizeForLatency: true,
      };
      const support = await VideoDecoder.isConfigSupported(config);
      if (!support.supported || this.stopped || this.configBytes !== configBytes) {
        if (!support.supported) {
          this.report({ stage: "client.codec.unsupported", code: "H264_WEBCODECS_UNSUPPORTED", retryable: false });
          this.input.callbacks.onState("UNAVAILABLE");
        }
        return;
      }
      const generation = ++this.decoderGeneration;
      const decoder = new VideoDecoder({
        output: (frame) => this.renderFrame(frame, generation),
        error: (error) => this.onDecoderError(error, generation),
      });
      decoder.configure(support.config ?? config);
      this.decoder = decoder;
      this.decoderReady = true;
      this.report({ stage: "client.decoder.configured", code: codec });
      const pending = this.pendingPackets;
      this.pendingPackets = [];
      for (const packet of pending) {
        if (!this.decoderReady || this.stopped) break;
        if (this.waitingForKeyframe && !packet.keyframe) continue;
        this.decodePacket(packet);
      }
    } finally {
      this.configuring = false;
    }
  }

  private decodePacket(packet: CycloneVideoPacket): void {
    const decoder = this.decoder;
    if (!decoder || !this.decoderReady || decoder.state !== "configured") return;
    if (decoder.decodeQueueSize >= MAX_DECODE_QUEUE && !packet.keyframe) {
      this.report({ stage: "client.decode.drop", code: "DECODE_QUEUE_BOUND", retryable: true });
      return;
    }
    let data = packet.payload;
    if (packet.keyframe) {
      this.waitingForKeyframe = false;
      if (this.configBytes) data = concatBytes(this.configBytes, packet.payload);
    }
    const chunk = new EncodedVideoChunk({
      type: packet.keyframe ? "key" : "delta",
      timestamp: packet.ptsUs,
      data,
    });
    decoder.decode(chunk);
  }

  private renderFrame(frame: VideoFrame, generation: number): void {
    try {
      if (this.stopped || generation !== this.decoderGeneration) return;
      const canvas = this.input.target.canvas;
      const width = frame.displayWidth || frame.codedWidth || this.width;
      const height = frame.displayHeight || frame.codedHeight || this.height;
      if (width <= 0 || height <= 0) return;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      const context = canvas.getContext("2d", { alpha: false });
      context?.drawImage(frame, 0, 0, width, height);
      canvas.hidden = false;
      this.input.target.fallbackImage.hidden = true;
      this.input.callbacks.onState("LIVE");
      this.live = true;
      this.reconnectAttempt = 0;
      this.armHealthTimeout(STALE_FRAME_TIMEOUT_MS, "FRAME_STALE");
      if (!this.frameReported) {
        this.frameReported = true;
        this.report({ stage: "client.frame.rendered", code: "H264_FRAME_OK" });
      }
    } finally {
      frame.close();
    }
  }

  private onDecoderError(error: DOMException, generation: number): void {
    if (this.stopped || generation !== this.decoderGeneration) return;
    this.report({ stage: "client.decoder.failed", code: "H264_DECODER_ERROR", retryable: true });
    this.resetDecoder();
    this.waitingForKeyframe = true;
    this.fail(error, "H264_DECODER_ERROR");
    try { this.socket?.close(4001, "H264_DECODER_ERROR"); } catch { /* browser owns close */ }
  }

  private async drawImagePacket(buffer: ArrayBuffer): Promise<void> {
    const payload = imageBytesFromPacket(buffer);
    const generation = ++this.imageDrawGeneration;
    // BlobPart requires an ArrayBuffer-backed view. Copy the bounded JPEG payload so
    // TypeScript cannot widen its backing store to SharedArrayBuffer.
    const imageBytes = new Uint8Array(payload.byteLength);
    imageBytes.set(payload);
    let bitmap: ImageBitmap;
    try {
      bitmap = await createImageBitmap(new Blob([imageBytes.buffer as ArrayBuffer], { type: this.codec || "image/jpeg" }));
    } catch (error) {
      this.onSoftFrameFailure(error, "FRAME_DECODE_FAILED");
      return;
    }
    try {
      if (this.stopped || generation !== this.imageDrawGeneration) return;
      const canvas = this.input.target.canvas;
      if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
      }
      const context = canvas.getContext("2d", { alpha: false });
      context?.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height);
      canvas.hidden = false;
      this.input.target.fallbackImage.hidden = true;
      this.input.callbacks.onState("LIVE");
      this.live = true;
      this.reconnectAttempt = 0;
      this.consecutiveFrameFailures = 0;
      this.armHealthTimeout(STALE_FRAME_TIMEOUT_MS, "FRAME_STALE");
      if (!this.frameReported) {
        this.frameReported = true;
        this.report({ stage: "client.frame.rendered", code: "JPEG_FRAME_OK" });
      }
    } finally {
      bitmap.close();
    }
  }

  private onSoftFrameFailure(error: unknown, code: string): void {
    this.consecutiveFrameFailures += 1;
    this.report({
      stage: "client.frame.soft_drop",
      code,
      attempt: this.consecutiveFrameFailures,
      retryable: true,
    });
    if (this.live && this.consecutiveFrameFailures < MAX_SOFT_FRAME_FAILURES) return;
    this.fail(error instanceof Error ? error : new Error(code), code);
    try { this.socket?.close(4001, code.slice(0, 120)); } catch { /* browser owns socket shutdown */ }
  }

  private resetDecoder(): void {
    this.decoderGeneration += 1;
    const decoder = this.decoder;
    this.decoder = null;
    this.decoderReady = false;
    this.configuring = false;
    this.pendingPackets = [];
    this.waitingForKeyframe = true;
    if (!decoder) return;
    try {
      if (decoder.state !== "closed") decoder.close();
    } catch {
      // Decoder may already be invalidated by the browser.
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer != null) return;
    if (this.reconnectAttempt >= MAX_STREAM_RECONNECT_ATTEMPTS) {
      this.report({
        stage: "client.reconnect.exhausted",
        code: "RECONNECT_LIMIT",
        attempt: this.reconnectAttempt,
        retryable: true,
      });
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    this.input.callbacks.onState("RECONNECTING");
    const delay = Math.min(5000, 500 * 2 ** Math.min(this.reconnectAttempt, 3));
    this.reconnectAttempt += 1;
    this.report({
      stage: "client.reconnect.scheduled",
      code: "RETRY_BACKOFF",
      attempt: this.reconnectAttempt,
      retryable: true,
    });
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer != null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  private fail(error: unknown, code?: string): void {
    const named = code ?? (error instanceof Error && /^[A-Z][A-Z0-9_.-]{2,80}$/.test(error.message) ? error.message : undefined);
    this.input.callbacks.onError(named ? new Error(named) : error);
    this.input.callbacks.onState("STREAM_ERROR");
  }

  private failConnection(error: unknown, socket: WebSocket, code: string): void {
    if (this.stopped || this.socket !== socket) return;
    this.report({
      stage: "client.render.failed",
      code,
      attempt: this.reconnectAttempt,
      retryable: true,
    });
    this.fail(error, code);
    try { socket.close(4001, code.slice(0, 120)); } catch { /* browser owns socket shutdown */ }
  }

  private armHealthTimeout(delayMs: number, code: string): void {
    this.clearHealthTimeout();
    this.healthTimer = window.setTimeout(() => {
      this.healthTimer = null;
      if (this.stopped) return;
      this.report({
        stage: "client.stream.timeout",
        code,
        attempt: this.reconnectAttempt,
        retryable: true,
      });
      this.input.callbacks.onState("STREAM_ERROR");
      try { this.socket?.close(4000, code); } catch { /* browser owns socket shutdown */ }
    }, delayMs);
  }

  private clearHealthTimeout(): void {
    if (this.healthTimer != null) window.clearTimeout(this.healthTimer);
    this.healthTimer = null;
  }

  private report(event: Parameters<VideoRendererFactoryInput["callbacks"]["onDiagnostic"]>[0]): void {
    this.input.callbacks.onDiagnostic(event);
  }
}

function clonePacket(packet: CycloneVideoPacket): CycloneVideoPacket {
  return { ...packet, payload: packet.payload.slice() };
}

function numberOrZero(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.trunc(value) : 0;
}

function closeCodeName(code: number): string {
  if (code === 4400) return "INVALID_STREAM_REQUEST";
  if (code === 4401) return "STREAM_AUTH_REJECTED";
  if (code === 4404) return "STREAM_DEVICE_UNAVAILABLE";
  if (code === 1000) return "NORMAL_CLOSE";
  if (code === 1006) return "ABNORMAL_CLOSE";
  if (code === 4000) return "CLIENT_HEALTH_TIMEOUT";
  if (code === 4001) return "CLIENT_RETRY_REQUESTED";
  return `WEBSOCKET_CLOSE_${Math.max(0, Math.min(9999, Math.trunc(code)))}`;
}

function safeCode(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.toUpperCase().replace(/[^A-Z0-9_.-]+/g, "_").slice(0, 80);
  return normalized || undefined;
}

function normalizeStreamCodec(codec: string): string {
  const named = normalizeImageCodec(codec);
  return named ?? codec;
}

function normalizeImageCodec(codec: string): string | null {
  const value = codec.trim().toLowerCase();
  if (!value) return null;
  if (value === "img" || value === "jpeg" || value === "jpg" || value === "screenshot" || value === "image/jpg") return "image/jpeg";
  if (value.startsWith("image/")) return value;
  return null;
}

export function resolveImageCodec(codec: string, buffer: ArrayBuffer): string | null {
  const named = normalizeImageCodec(codec);
  if (named) return named;
  if (codec && codec !== "img") return null;
  if (looksLikeJpeg(new Uint8Array(buffer))) return "image/jpeg";
  try {
    if (looksLikeJpeg(parseCyclonePacket(buffer).payload)) return "image/jpeg";
  } catch {
    // Binary may be a raw JPEG without a Cyclone envelope.
  }
  return null;
}

function looksLikeJpeg(bytes: Uint8Array): boolean {
  return bytes.length >= 3 && bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF;
}

function imageBytesFromPacket(buffer: ArrayBuffer): Uint8Array {
  try {
    const packet = parseCyclonePacket(buffer);
    const imageBytes = new Uint8Array(packet.payload.byteLength);
    imageBytes.set(packet.payload);
    return imageBytes;
  } catch {
    const imageBytes = new Uint8Array(buffer.byteLength);
    imageBytes.set(new Uint8Array(buffer));
    return imageBytes;
  }
}
