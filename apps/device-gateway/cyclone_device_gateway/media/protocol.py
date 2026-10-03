from __future__ import annotations

from dataclasses import dataclass
import struct

SCRCPY_CODEC_H264 = 0x68323634
SCRCPY_CODEC_H265 = 0x68323635
SCRCPY_CODEC_AV1 = 0x00617631

SESSION_FLAG = 1 << 63
CONFIG_FLAG = 1 << 62
KEY_FRAME_FLAG = 1 << 61
PTS_MASK = KEY_FRAME_FLAG - 1

MAX_MEDIA_PACKET_BYTES = 16 * 1024 * 1024


class ScrcpyProtocolError(RuntimeError):
    pass


@dataclass(frozen=True)
class CodecEvent:
    codec_id: int

    @property
    def codec(self) -> str:
        return {
            SCRCPY_CODEC_H264: "h264",
            SCRCPY_CODEC_H265: "h265",
            SCRCPY_CODEC_AV1: "av1",
        }.get(self.codec_id, f"unknown:{self.codec_id:#x}")


@dataclass(frozen=True)
class SessionEvent:
    width: int
    height: int
    client_resized: bool = False


@dataclass(frozen=True)
class MediaPacket:
    payload: bytes
    pts_us: int | None
    config: bool
    keyframe: bool


ScrcpyEvent = CodecEvent | SessionEvent | MediaPacket


class ScrcpyVideoPacketParser:
    """Incremental parser for the pinned scrcpy 4.0 video socket protocol.

    Cyclone disables device metadata and the dummy byte, but keeps stream and frame metadata.
    Therefore the stream begins with a u32 codec id, followed by 12-byte session/media headers.
    """

    def __init__(self, *, max_packet_bytes: int = MAX_MEDIA_PACKET_BYTES):
        self.max_packet_bytes = max_packet_bytes
        self._buffer = bytearray()
        self._codec_seen = False
        self._pending_header: tuple[int, bool, bool] | None = None

    def feed(self, data: bytes | bytearray | memoryview) -> list[ScrcpyEvent]:
        if data:
            self._buffer.extend(data)
        out: list[ScrcpyEvent] = []

        if not self._codec_seen:
            if len(self._buffer) < 4:
                return out
            codec_id = struct.unpack_from(">I", self._buffer, 0)[0]
            del self._buffer[:4]
            self._codec_seen = True
            out.append(CodecEvent(codec_id))

        while True:
            if self._pending_header is not None:
                packet_size, config, keyframe = self._pending_header
                if len(self._buffer) < packet_size:
                    break
                payload = bytes(self._buffer[:packet_size])
                del self._buffer[:packet_size]
                pts_flags = getattr(self, "_pending_pts_flags")
                pts_us = None if config else pts_flags & PTS_MASK
                out.append(MediaPacket(payload, pts_us, config, keyframe))
                self._pending_header = None
                del self._pending_pts_flags
                continue

            if len(self._buffer) < 12:
                break

            first_u64 = struct.unpack_from(">Q", self._buffer, 0)[0]
            if first_u64 & SESSION_FLAG:
                flags, width, height = struct.unpack_from(">III", self._buffer, 0)
                del self._buffer[:12]
                if width <= 0 or height <= 0 or width > 32768 or height > 32768:
                    raise ScrcpyProtocolError(f"Invalid scrcpy session dimensions {width}x{height}")
                out.append(SessionEvent(width, height, bool(flags & 1)))
                continue

            pts_flags, packet_size = struct.unpack_from(">QI", self._buffer, 0)
            del self._buffer[:12]
            if packet_size <= 0 or packet_size > self.max_packet_bytes:
                raise ScrcpyProtocolError(f"Invalid scrcpy media packet size {packet_size}")
            config = bool(pts_flags & CONFIG_FLAG)
            keyframe = bool(pts_flags & KEY_FRAME_FLAG) and not config
            self._pending_pts_flags = pts_flags
            self._pending_header = (packet_size, config, keyframe)

        return out

    def finish(self) -> None:
        if self._pending_header is not None or self._buffer:
            raise ScrcpyProtocolError("Truncated scrcpy video stream")

    @property
    def buffered_bytes(self) -> int:
        return len(self._buffer)


def split_annex_b_nalus(data: bytes) -> list[bytes]:
    """Return NAL units (including NAL header byte) from Annex-B H.264 bytes."""
    starts: list[tuple[int, int]] = []
    i = 0
    size = len(data)
    while i + 3 <= size:
        if data[i:i + 4] == b"\x00\x00\x00\x01":
            starts.append((i, 4))
            i += 4
            continue
        if data[i:i + 3] == b"\x00\x00\x01":
            starts.append((i, 3))
            i += 3
            continue
        i += 1
    nalus: list[bytes] = []
    for index, (start, prefix) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else size
        if end > start + prefix:
            nalus.append(data[start + prefix:end])
    return nalus


def h264_packet_is_keyframe(data: bytes) -> bool:
    return any(nalu and (nalu[0] & 0x1F) == 5 for nalu in split_annex_b_nalus(data))
