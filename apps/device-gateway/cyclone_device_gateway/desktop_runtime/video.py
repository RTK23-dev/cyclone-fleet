from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import json
import queue
import struct
import threading
import time
from typing import Any

from ..adb.screenshot import is_png
from ..media import MediaEvent, MediaState, ScrcpyMediaBackend
from .models import DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES, VIDEO_PROTOCOL_VERSION, now_ms

try:
    from PIL import Image
except Exception:
    Image = None

_PACKET_HEADER = struct.Struct("!QII")
_PACKET_FLAG_CONFIG = 0x80000000
_PACKET_FLAG_KEYFRAME = 0x40000000
_PACKET_FLAG_MEDIA = 0x20000000
_PACKET_SEQUENCE_MASK = 0x1FFFFFFF
KEEPALIVE_INTERVAL_S = 2.0
CAPTURE_OUTAGE_BACKOFF_S = 2.0
DEGRADED_FOCUS_FPS = 2
DEGRADED_THUMBNAIL_FPS = 1
JPEG_INIT_TIMEOUT_S = 20.0
JPEG_STALE_TIMEOUT_S = 15.0
JPEG_SCREENCAP_TIMEOUT_S = JPEG_INIT_TIMEOUT_S
JPEG_PRIMARY_BACKEND = "adb-screenshot"
LAN_SHARE_BACKEND = "lan-share"
LAN_RECHECK_S = 5.0


@dataclass(frozen=True)
class StreamMessage:
    kind: str
    data: str | bytes


class VideoFleetLimiter:
    def __init__(self, max_sources: int = 12, max_focus: int = 2):
        self.max_sources = max(1, min(max_sources, 16))
        self.max_focus = max(1, min(max_focus, self.max_sources))
        self._lock = threading.Lock()
        self._sources = 0
        self._focus = 0

    def acquire(self, profile: str) -> tuple[bool, bool]:
        with self._lock:
            if self._sources >= self.max_sources:
                return False, False
            self._sources += 1
            focus = profile == "focus" and self._focus < self.max_focus
            if focus:
                self._focus += 1
            return True, focus

    def release(self, profile: str, focus_used: bool) -> None:
        with self._lock:
            self._sources = max(0, self._sources - 1)
            if profile == "focus" and focus_used:
                self._focus = max(0, self._focus - 1)

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return {
                "sources": self._sources,
                "focus": self._focus,
                "maxSources": self.max_sources,
                "maxFocus": self.max_focus,
            }


class VideoStreamController:
    """USB H.264 live view with a bounded screenshot fallback when media is unavailable."""

    def __init__(
        self,
        session,
        limiter: VideoFleetLimiter,
        diagnostic=None,
        media_backend: ScrcpyMediaBackend | None = None,
        *,
        jpeg_first: bool = False,
        lan_share: Any | None = None,
    ):
        # Wi-Fi screen share (AnyDesk-style): an object with available() -> bool and open() -> client|None for this
        # phone. USB H.264 is primary; an explicitly started share can rescue a failed USB encoder.
        self.lan_share = lan_share
        self.session = session
        self.limiter = limiter
        self.jpeg_first = jpeg_first
        self._lock = threading.RLock()
        self._subscribers: dict[str, set[queue.Queue]] = {"thumbnail": set(), "focus": set()}
        self._threads: dict[str, threading.Thread] = {}
        self._stops: dict[str, threading.Event] = {}
        self._last_safe_frame: bytes | None = None
        self._sequence = 0
        self._diagnostic = diagnostic
        self._frames_by_profile: dict[str, int] = {"thumbnail": 0, "focus": 0}
        self._failures_by_profile: dict[str, int] = {"thumbnail": 0, "focus": 0}
        self._last_event = "idle"
        self._last_frame_meta: tuple[bytes, str, int | None, int | None] | None = None
        self.media_backend = media_backend or ScrcpyMediaBackend(
            diagnostic=lambda stage, details: self._mark(stage, details)
        )

    def _primary_backend(self) -> str:
        return JPEG_PRIMARY_BACKEND if self.jpeg_first else "scrcpy-v4.0"

    def subscribe(self, profile: str) -> queue.Queue:
        if profile not in VIDEO_PROFILES:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown video profile.")
        # Keep configuration and the next keyframe available while a local viewer briefly stalls.
        # Old frames are still discarded by _broadcast, so this is never an unbounded latency buffer.
        q: queue.Queue = queue.Queue(maxsize=16)
        with self._lock:
            self._subscribers[profile].add(q)
            thread = self._threads.get(profile)
            if thread is None or not thread.is_alive():
                self._start_producer_locked(profile)
            else:
                # A late viewer has no decoder configuration or keyframe. Restart once
                # for all current viewers rather than feed it mid-GOP delta packets.
                # This also covers a reload whose new socket beats the old close frame.
                self._stops[profile].set()
            # If the last viewer just left, its producer is still shutting down. Its
            # finally block starts the replacement, after releasing the old media session.
        self._mark("server.stream.subscribed", {"profile": profile, "transport": "websocket"})
        return q

    def _start_producer_locked(self, profile: str) -> None:
        stop = threading.Event()
        self._stops[profile] = stop
        thread = threading.Thread(
            target=self._producer,
            args=(profile, stop),
            name=f"cyclone-video-{self.session.device_id}-{profile}",
            daemon=True,
        )
        self._threads[profile] = thread
        thread.start()

    def unsubscribe(self, profile: str, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.get(profile, set()).discard(q)
            if not self._subscribers.get(profile):
                stop = self._stops.get(profile)
                if stop:
                    stop.set()
        self._mark("server.stream.unsubscribed", {"profile": profile, "transport": "websocket"})

    def stop_all(self) -> None:
        with self._lock:
            stops = tuple(self._stops.values())
            threads = tuple(self._threads.values())
            for profile, subscribers in self._subscribers.items():
                self._broadcast(profile, StreamMessage("close", ""))
                subscribers.clear()
            for stop in stops:
                stop.set()
        try:
            self.media_backend.stop(self.session.device_id)
        except Exception:
            pass
        for thread in threads:
            if thread.is_alive():
                thread.join(timeout=2.5)

    def subscriber_count(self) -> int:
        with self._lock:
            return sum(len(items) for items in self._subscribers.values())

    def diagnostics(self) -> dict[str, Any]:
        try:
            media = self.media_backend.status(self.session.device_id)
            probe = self.media_backend.probe(self.session)
        except Exception as exc:
            media = {"backend": "scrcpy-v4.0", "sessionCount": 0, "sessions": []}
            probe = {
                "backend": "scrcpy-v4.0",
                "artifactVerified": False,
                "artifactError": exc.__class__.__name__,
            }
        with self._lock:
            return {
                "subscriberCount": sum(len(items) for items in self._subscribers.values()),
                "subscribersByProfile": {key: len(value) for key, value in self._subscribers.items()},
                "activeProfiles": sorted(key for key, thread in self._threads.items() if thread.is_alive()),
                "framesByProfile": dict(self._frames_by_profile),
                "failuresByProfile": dict(self._failures_by_profile),
                "lastEvent": self._last_event,
                "lastFrameAvailable": self._last_frame_meta is not None,
                "sequence": self._sequence,
                "fleetLimiter": self.limiter.snapshot(),
                "primaryBackend": self._primary_backend(),
                "jpegFirst": self.jpeg_first,
                "initTimeoutSeconds": JPEG_INIT_TIMEOUT_S if self.jpeg_first else None,
                "staleTimeoutSeconds": JPEG_STALE_TIMEOUT_S if self.jpeg_first else None,
                "mediaProbe": probe,
                "media": media,
            }

    def snapshot(self, *, fresh: bool = False) -> dict[str, Any]:
        """Return one bounded image for evidence/degraded preview, never the primary live path."""
        with self._lock:
            meta = None if fresh else self._last_frame_meta
            sequence = self._sequence
        if meta is None:
            try:
                safe = self.media_backend.latest_safe_snapshot(self.session)
                png = safe.data
                if not is_png(png):
                    raise ValueError("screencap returned a non-PNG payload")
                encoded, codec, width, height = _encode_frame(
                    png,
                    VIDEO_PROFILES["focus"].max_long_edge,
                )
            except Exception as exc:
                self._mark(
                    "server.stream.snapshot_failed",
                    {"errorClass": exc.__class__.__name__, "retryable": True},
                )
                raise DesktopRuntimeError(
                    RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
                    "Phone frame capture failed.",
                    retryable=True,
                ) from exc
            with self._lock:
                self._last_safe_frame = encoded
                self._last_frame_meta = (encoded, codec, width, height)
                sequence = self._sequence
            self._mark("server.stream.snapshot", {"source": "adb-screenshot", "cached": False})
        else:
            encoded, codec, width, height = meta
            self._mark("server.stream.snapshot", {"source": "adb-screenshot", "cached": True})
        return {
            "data": encoded,
            "codec": codec,
            "width": width,
            "height": height,
            "timestamp_ms": now_ms(),
            "sequence": sequence,
        }

    def _producer(self, profile: str, stop: threading.Event) -> None:
        self._mark("server.producer.start", {"profile": profile, "primaryBackend": self._primary_backend()})
        allowed, focus_allowed = self.limiter.acquire(profile)
        if not allowed:
            self._mark("server.producer.capacity", {"profile": profile, "code": "STREAM_CAPACITY"})
            self._broadcast(
                profile,
                StreamMessage(
                    "text",
                    json.dumps(
                        {"type": "stream.error", "code": "STREAM_CAPACITY", "retryable": True},
                        separators=(",", ":"),
                    ),
                ),
            )
            return
        try:
            if self.lan_share is not None and self.jpeg_first:
                # Prefer the phone's own Wi-Fi share; fall back to ADB screenshots and switch back as soon as it shares.
                while not stop.is_set():
                    if self._produce_lan(profile, stop):
                        continue
                    self._produce_jpeg(profile, stop, until=self._lan_ready)
                return
            if self.jpeg_first:
                self._produce_jpeg(profile, stop)
            else:
                try:
                    self._produce_scrcpy(profile, stop)
                    if stop.is_set():
                        return
                except Exception as exc:
                    self._mark(
                        "server.media.scrcpy_unavailable",
                        {
                            "profile": profile,
                            "errorClass": exc.__class__.__name__,
                            "retryable": True,
                        },
                    )
                if not stop.is_set():
                    # Do not strand a disconnected viewer. An owner-requested LAN share can
                    # still replace the emergency screenshot preview after USB media fails.
                    while not stop.is_set():
                        if self._produce_lan(profile, stop):
                            continue
                        self._produce_jpeg(profile, stop, until=self._lan_ready, fallback=True)
        finally:
            # _produce_scrcpy unsubscribes its own media session. Stopping by device here used
            # to kill a newly started focus stream when Glass closed the thumbnail stream.
            self._mark("server.producer.stop", {"profile": profile})
            self.limiter.release(profile, focus_allowed)
            with self._lock:
                if self._stops.get(profile) is stop:
                    self._threads.pop(profile, None)
                    self._stops.pop(profile, None)
                    if stop.is_set() and self._subscribers.get(profile):
                        self._start_producer_locked(profile)

    def _produce_scrcpy(self, profile: str, stop: threading.Event) -> None:
        media = self.media_backend.start(self.session, profile)
        events = media.subscribe()
        # Wait for scrcpy's real session metadata. A provisional init could reset the browser's
        # decoder when the first configuration packet was already in flight.
        init_sent = False
        seen_frame = False
        last_keepalive = time.monotonic()
        try:
            while not stop.is_set():
                try:
                    event: MediaEvent = events.get(timeout=0.5)
                except queue.Empty:
                    if seen_frame:
                        last_keepalive = self._maybe_keepalive(profile, last_keepalive)
                    continue
                if event.kind == "session":
                    width = _safe_int(event.data.get("width"))
                    height = _safe_int(event.data.get("height"))
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            self._init_json(
                                profile,
                                "video/avc",
                                "scrcpy-v4.0",
                                width=width,
                                height=height,
                                session_id=str(event.data.get("sessionId") or media.session_id),
                            ),
                        ),
                    )
                    init_sent = True
                    seen_frame = False
                    last_keepalive = time.monotonic()
                    self._mark(
                        "server.stream.init",
                        {"profile": profile, "source": "scrcpy-v4.0", "width": width, "height": height},
                    )
                    continue
                if event.kind == "packet":
                    payload = event.data.get("payload")
                    if not isinstance(payload, (bytes, bytearray)):
                        continue
                    config = event.data.get("config") is True
                    keyframe = event.data.get("keyframe") is True
                    pts_us = _safe_int(event.data.get("ptsUs")) or 0
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "binary",
                            self._packet(bytes(payload), pts_us=pts_us, config=config, keyframe=keyframe),
                        ),
                    )
                    if not config:
                        seen_frame = True
                        last_keepalive = time.monotonic()
                        with self._lock:
                            self._frames_by_profile[profile] += 1
                            first = self._frames_by_profile[profile] == 1
                        if first:
                            self._mark(
                                "server.frame.first",
                                {"profile": profile, "source": "scrcpy-v4.0"},
                            )
                    continue
                if event.kind != "state":
                    continue
                state = str(event.data.get("state") or "")
                if state == MediaState.SLEEPING.value:
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            json.dumps({"type": "screen.state", "state": "SLEEPING"}, separators=(",", ":")),
                        ),
                    )
                elif state == MediaState.RECONNECTING.value:
                    seen_frame = False
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            json.dumps(
                                {
                                    "type": "stream.error",
                                    "code": str(event.data.get("code") or "SCRCPY_RECONNECTING"),
                                    "retryable": True,
                                },
                                separators=(",", ":"),
                            ),
                        ),
                    )
                elif state == MediaState.UNAVAILABLE.value:
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            json.dumps(
                                {
                                    "type": "stream.error",
                                    "code": str(event.data.get("code") or "SCRCPY_STREAM_UNAVAILABLE"),
                                    "retryable": True,
                                },
                                separators=(",", ":"),
                            ),
                        ),
                    )
                    raise RuntimeError("scrcpy media backend exhausted reconnect attempts")
                elif state == MediaState.LIVE.value and not init_sent:
                    status = media.status()
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            self._init_json(
                                profile,
                                "video/avc",
                                "scrcpy-v4.0",
                                width=status.get("width"),
                                height=status.get("height"),
                                session_id=media.session_id,
                            ),
                        ),
                    )
                    init_sent = True
        finally:
            media.unsubscribe(events)

    def _lan_ready(self) -> bool:
        try:
            return bool(self.lan_share is not None and self.lan_share.available())
        except Exception:
            return False

    def _produce_lan(self, profile: str, stop: threading.Event) -> bool:
        """Stream the phone's Wi-Fi share. False when it is not sharing or unreachable (caller falls back)."""
        try:
            client = self.lan_share.open() if self.lan_share is not None else None
        except Exception:
            client = None
        if client is None:
            return False
        codec = "image/jpeg"
        width, height = self._target_dimensions(VIDEO_PROFILES[profile].max_long_edge)
        self._broadcast(profile, StreamMessage("text", self._init_json(profile, codec, LAN_SHARE_BACKEND, fallback=False, width=width, height=height)))
        self._mark("server.stream.init", {"profile": profile, "source": LAN_SHARE_BACKEND})
        last_keepalive = time.monotonic()
        frames = 0
        try:
            for kind, payload in client.records():
                if stop.is_set():
                    break
                if kind == 1 and payload[:2] == b"\xff\xd8":
                    self._last_safe_frame = payload
                    self._last_frame_meta = (payload, codec, None, None)
                    self._broadcast(profile, StreamMessage("binary", self._packet(payload, pts_us=now_ms() * 1000)))
                    last_keepalive = time.monotonic()
                    frames += 1
                    if frames == 1:
                        self._mark("server.frame.first", {"profile": profile, "source": LAN_SHARE_BACKEND})
                    with self._lock:
                        self._frames_by_profile[profile] += 1
                else:
                    last_keepalive = self._maybe_keepalive(profile, last_keepalive)
        except Exception as exc:
            self._mark("server.lan.ended", {"profile": profile, "errorClass": exc.__class__.__name__, "retryable": True})
        finally:
            try:
                client.close()
            except Exception:
                pass
        return True

    def _produce_jpeg(self, profile: str, stop: threading.Event, until: Any | None = None, *, fallback: bool = False) -> None:
        codec = _image_codec()
        width, height = self._target_dimensions(VIDEO_PROFILES[profile].max_long_edge)
        self._broadcast(
            profile,
            StreamMessage(
                "text",
                self._init_json(
                    profile,
                    codec,
                    JPEG_PRIMARY_BACKEND,
                    fallback=fallback,
                    width=width,
                    height=height,
                ),
            ),
        )
        self._mark(
            "server.stream.init",
            {"profile": profile, "source": JPEG_PRIMARY_BACKEND, "jpegFirst": True},
        )
        target_fps = DEGRADED_FOCUS_FPS if profile == "focus" else DEGRADED_THUMBNAIL_FPS
        self._produce_images(profile, stop, target_fps=target_fps, until=until)

    def _produce_degraded(self, profile: str, stop: threading.Event) -> None:
        # Retained name for older tests/callers; JPEG is the primary physical preview path.
        self._produce_jpeg(profile, stop)

    def _produce_images(self, profile: str, stop: threading.Event, target_fps: int, until: Any | None = None) -> None:
        interval = 1.0 / max(1, target_fps)
        next_until_check = time.monotonic() + LAN_RECHECK_S
        sleeping_sent = False
        consecutive_failures = 0
        outage_active = False
        last_keepalive = time.monotonic()
        while not stop.is_set():
            started = time.monotonic()
            if until is not None and started >= next_until_check:
                next_until_check = started + LAN_RECHECK_S
                if until():
                    return
            if not self.session.screen_awake:
                if not sleeping_sent:
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            json.dumps({"type": "screen.state", "state": "SLEEPING"}, separators=(",", ":")),
                        ),
                    )
                    if self._last_safe_frame is not None:
                        self._broadcast(
                            profile,
                            StreamMessage(
                                "binary",
                                self._packet(self._last_safe_frame, pts_us=now_ms() * 1000),
                            ),
                        )
                    sleeping_sent = True
                    last_keepalive = time.monotonic()
                else:
                    last_keepalive = self._maybe_keepalive(profile, last_keepalive)
                stop.wait(min(0.5, interval))
                continue
            if sleeping_sent:
                self._broadcast(
                    profile,
                    StreamMessage(
                        "text",
                        json.dumps({"type": "screen.state", "state": "AWAKE"}, separators=(",", ":")),
                    ),
                )
                sleeping_sent = False
                last_keepalive = time.monotonic()
            try:
                png = self.session.adb.exec_out("screencap", "-p", timeout=JPEG_SCREENCAP_TIMEOUT_S)
                if not is_png(png):
                    raise ValueError("screencap returned a non-PNG payload")
                encoded, codec, width, height = _encode_frame(
                    png,
                    VIDEO_PROFILES[profile].max_long_edge,
                )
                self._last_safe_frame = encoded
                self._last_frame_meta = (encoded, codec, width, height)
                consecutive_failures = 0
                outage_active = False
                self._broadcast(
                    profile,
                    StreamMessage(
                        "binary",
                        self._packet(encoded, pts_us=now_ms() * 1000),
                    ),
                )
                last_keepalive = time.monotonic()
                with self._lock:
                    self._frames_by_profile[profile] += 1
                    first = self._frames_by_profile[profile] == 1
                if first:
                    self._mark(
                        "server.frame.first",
                        {"profile": profile, "source": JPEG_PRIMARY_BACKEND},
                    )
            except Exception as exc:
                consecutive_failures += 1
                with self._lock:
                    self._failures_by_profile[profile] += 1
                if consecutive_failures == 1:
                    self._mark(
                        "server.frame.capture_failed",
                        {"profile": profile, "errorClass": exc.__class__.__name__, "retryable": True},
                    )
                if consecutive_failures >= 3 and not outage_active:
                    outage_active = True
                    self._mark(
                        "server.frame.capture_exhausted",
                        {"profile": profile, "code": "FRAME_CAPTURE_FAILED", "retryable": True},
                    )
                    self._broadcast(
                        profile,
                        StreamMessage(
                            "text",
                            json.dumps(
                                {
                                    "type": "stream.error",
                                    "code": "FRAME_CAPTURE_FAILED",
                                    "retryable": True,
                                },
                                separators=(",", ":"),
                            ),
                        ),
                    )
                last_keepalive = self._maybe_keepalive(profile, last_keepalive)
            wait = interval - (time.monotonic() - started)
            if consecutive_failures >= 3:
                wait = max(wait, CAPTURE_OUTAGE_BACKOFF_S)
            stop.wait(max(0.0, wait))

    def _maybe_keepalive(self, profile: str, last_keepalive: float) -> float:
        now = time.monotonic()
        if now - last_keepalive < KEEPALIVE_INTERVAL_S:
            return last_keepalive
        self._broadcast(
            profile,
            StreamMessage(
                "text",
                json.dumps({"type": "stream.keepalive", "timestampMs": now_ms()}, separators=(",", ":")),
            ),
        )
        return now

    def _packet(
        self,
        payload: bytes,
        *,
        pts_us: int,
        config: bool = False,
        keyframe: bool = False,
    ) -> bytes:
        with self._lock:
            self._sequence = (self._sequence + 1) & _PACKET_SEQUENCE_MASK
            sequence = self._sequence
        flags_sequence = sequence
        if config:
            flags_sequence |= _PACKET_FLAG_CONFIG
        else:
            flags_sequence |= _PACKET_FLAG_MEDIA
        if keyframe:
            flags_sequence |= _PACKET_FLAG_KEYFRAME
        return _PACKET_HEADER.pack(max(0, pts_us), flags_sequence, len(payload)) + payload

    def _broadcast(self, profile: str, message: StreamMessage) -> None:
        with self._lock:
            subscribers = tuple(self._subscribers.get(profile, ()))
        for q in subscribers:
            try:
                q.put_nowait(message)
            except queue.Full:
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
                try:
                    q.put_nowait(message)
                except queue.Full:
                    pass

    def _mark(self, stage: str, details: dict[str, Any] | None = None) -> None:
        with self._lock:
            self._last_event = stage
        if self._diagnostic is None:
            return
        try:
            self._diagnostic(stage, details or {})
        except Exception:
            pass

    def _target_dimensions(self, max_long_edge: int) -> tuple[int, int]:
        width = self.session.display_width or 1080
        height = self.session.display_height or 1920
        longest = max(width, height)
        if longest <= max_long_edge:
            return _even(width), _even(height)
        scale = max_long_edge / longest
        return _even(max(2, int(width * scale))), _even(max(2, int(height * scale)))

    def _init_json(
        self,
        profile: str,
        codec: str,
        backend: str,
        *,
        fallback: bool = False,
        width: int | None = None,
        height: int | None = None,
        session_id: str | None = None,
    ) -> str:
        spec = VIDEO_PROFILES[profile]
        is_h264 = codec == "video/avc"
        return json.dumps(
            {
                "type": "stream.init",
                "protocol": VIDEO_PROTOCOL_VERSION,
                "profile": profile,
                "codec": codec,
                "frameFormat": "annex-b-access-unit" if is_h264 else "image-frame",
                "binaryHeader": "u64be pts_us + u32be flags_sequence + u32be payload_length",
                "flags": {
                    "config": "0x80000000",
                    "keyframe": "0x40000000",
                    "media": "0x20000000",
                    "sequenceMask": "0x1fffffff",
                },
                "timestampClock": "monotonic-media-us" if is_h264 else "unix-us",
                "targetFps": (
                    DEGRADED_FOCUS_FPS if (not is_h264) and profile == "focus"
                    else DEGRADED_THUMBNAIL_FPS if not is_h264
                    else 30 if profile == "focus" and is_h264
                    else 8 if profile == "thumbnail" and is_h264
                    else spec.target_fps
                ),
                "maxLongEdge": spec.max_long_edge,
                "backend": backend,
                "fallback": fallback,
                "degraded": fallback,
                "width": width,
                "height": height,
                "sessionId": session_id,
                "resolutionChanges": "new stream.init is emitted from scrcpy session metadata",
                "reconnect": "gateway media session owns encoder reconnect; UI owns WebSocket reconnect only",
            },
            separators=(",", ":"),
        )


def _safe_int(value: Any) -> int | None:
    return value if isinstance(value, int) else None


def _even(value: int) -> int:
    return value if value % 2 == 0 else value - 1


def _encode_frame(
    png: bytes,
    max_long_edge: int,
) -> tuple[bytes, str, int | None, int | None]:
    if Image is None:
        return png, "image/png", None, None
    with Image.open(BytesIO(png)) as image:
        image = image.convert("RGB")
        width, height = image.size
        longest = max(width, height)
        if longest > max_long_edge:
            scale = max_long_edge / longest
            image = image.resize((max(1, int(width * scale)), max(1, int(height * scale))))
        out = BytesIO()
        image.save(out, format="JPEG", quality=62, optimize=False)
        return out.getvalue(), "image/jpeg", image.width, image.height


def _image_codec() -> str:
    return "image/jpeg" if Image is not None else "image/png"
