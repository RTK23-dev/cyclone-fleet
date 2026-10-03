import test from "node:test";
import assert from "node:assert/strict";
import {
  concatBytes,
  h264CodecFromAnnexB,
  parseCyclonePacket,
} from "../.test-dist/video/webcodecsH264Decoder.js";

function envelope(ptsUs, flagsSequence, payload) {
  const buffer = new ArrayBuffer(16 + payload.length);
  const view = new DataView(buffer);
  const hi = Math.floor(ptsUs / 0x1_0000_0000);
  const lo = ptsUs - hi * 0x1_0000_0000;
  view.setUint32(0, hi, false);
  view.setUint32(4, lo, false);
  view.setUint32(8, flagsSequence >>> 0, false);
  view.setUint32(12, payload.length, false);
  new Uint8Array(buffer, 16).set(payload);
  return buffer;
}

test("Cyclone H.264 packet envelope preserves pts flags sequence and payload", () => {
  const payload = Uint8Array.from([0, 0, 0, 1, 0x65, 0x88]);
  const parsed = parseCyclonePacket(envelope(123456, 0x6000002a, payload));
  assert.equal(parsed.ptsUs, 123456);
  assert.equal(parsed.sequence, 42);
  assert.equal(parsed.media, true);
  assert.equal(parsed.keyframe, true);
  assert.equal(parsed.config, false);
  assert.deepEqual([...parsed.payload], [...payload]);
});

test("decoder derives WebCodecs avc1 profile from Annex-B SPS", () => {
  const config = Uint8Array.from([
    0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1f,
    0, 0, 0, 1, 0x68, 0xeb, 0xec, 0xb2,
  ]);
  assert.equal(h264CodecFromAnnexB(config), "avc1.64001F");
});

test("keyframe can be prefixed with codec configuration without base64 or image conversion", () => {
  const config = Uint8Array.from([0, 0, 0, 1, 0x67, 1]);
  const key = Uint8Array.from([0, 0, 0, 1, 0x65, 2]);
  const joined = concatBytes(config, key);
  assert.equal(joined.length, config.length + key.length);
  assert.deepEqual([...joined.subarray(0, config.length)], [...config]);
  assert.deepEqual([...joined.subarray(config.length)], [...key]);
});

test("malformed payload lengths fail closed", () => {
  const payload = Uint8Array.from([1, 2, 3]);
  const buffer = envelope(1, 0x20000001, payload);
  new DataView(buffer).setUint32(12, 99, false);
  assert.throws(() => parseCyclonePacket(buffer), /payload length/);
});
