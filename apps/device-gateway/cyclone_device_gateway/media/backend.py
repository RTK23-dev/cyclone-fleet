from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
import queue
import secrets
import socket
import subprocess
import threading
import time
from typing import Any, Callable

from .artifact import ScrcpyArtifact, resolve_scrcpy_artifact
from .protocol import (
    CodecEvent,
    MediaPacket,
    SCRCPY_CODEC_H264,
    ScrcpyProtocolError,
    ScrcpyVideoPacketParser,
    SessionEvent,
)

_CONNECT_TIMEOUT_S = 3.0
_RECONNECT_BACKOFF_S = (0.25, 0.5, 1.0, 2.0)
_MAX_EVENT_QUEUE = 12


class MediaState(StrEnum):
    STOPPED = "STOPPED"
    STARTING = "STARTING"
    WAITING_KEYFRAME = "WAITING_KEYFRAME"
    LIVE = "LIVE"
    SLEEPING = "SLEEPING"
    RECONNECTING = "RECONNECTING"
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class MediaProfile:
    name: str
    max_long_edge: int
    target_fps: int
    bitrate_bps: int

    @classmethod
    def named(cls, name: str) -> "MediaProfile":
        if name == "thumbnail":
            return cls(name, 540, 8, 1_000_000)
        if name == "focus":
            return cls(name, 1920, 30, 12_000_000)
        raise ValueError(f"Unknown media profile {name!r}")


@dataclass(frozen=True)
class MediaEvent:
    kind: str
    data: dict[str, Any]


@dataclass(frozen=True)
class SafeSnapshot:
    data: bytes
    codec: str
    width: int | None
    height: int | None
    timestamp_ms: int


def _now_ms() -> int:
    return int(time.time() * 1000)


def _safe_text(exc: Exception) -> str:
    return str(exc).replace("\r", " ").replace("\n", " ").strip()[:240] or exc.__class__.__name__


def _reserve_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class ScrcpyMediaSession:
    """One failure-isolated scrcpy H.264 session for one device/profile."""

    def __init__(
        self,
        device: Any,
        profile: MediaProfile,
        artifact: ScrcpyArtifact,
        diagnostic: Callable[[str, dict[str, Any]], None] | None = None,
    ):
        self.device = device
        self.profile = profile
        self.artifact = artifact
        self.diagnostic = diagnostic
        self.session_id = secrets.token_hex(12)
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._subscribers: set[queue.Queue] = set()
        self._state = MediaState.STOPPED
        self._frames = 0
        self._packets = 0
        self._config_packets = 0
        self._dropped_events = 0
        self._restarts = 0
        self._last_error: str | None = None
        self._width: int | None = None
        self._height: int | None = None
        self._last_pts_us: int | None = None
        self._last_config_event: MediaEvent | None = None
        self._started_at_ms: int | None = None
        self._first_frame_at_ms: int | None = None
        self._active_port: int | None = None
        self._active_scid: int | None = None

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=_MAX_EVENT_QUEUE)
        with self._lock:
            self._subscribers.add(q)
            self._seed_subscriber(q)
            if self._thread is None or not self._thread.is_alive():
                self._stop.clear()
                self._thread = threading.Thread(
                    target=self._run,
                    name=f"cyclone-scrcpy-{getattr(self.device, 'device_id', 'device')}-{self.profile.name}",
                    daemon=True,
                )
                self._thread.start()
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._subscribers.discard(q)
            should_stop = not self._subscribers
        if should_stop:
            self.stop()

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            thread = self._thread
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=2.0)

    def status(self) -> dict[str, Any]:
        with self._lock:
            first_frame_ms = None
            if self._started_at_ms is not None and self._first_frame_at_ms is not None:
                first_frame_ms = self._first_frame_at_ms - self._started_at_ms
            return {
                "sessionId": self.session_id,
                "deviceId": getattr(self.device, "device_id", None),
                "profile": self.profile.name,
                "state": self._state.value,
                "codec": "video/avc",
                "backend": "scrcpy-v4.0",
                "width": self._width,
                "height": self._height,
                "packets": self._packets,
                "frames": self._frames,
                "configPackets": self._config_packets,
                "droppedEvents": self._dropped_events,
                "restartCount": self._restarts,
                "lastPtsUs": self._last_pts_us,
                "firstFrameMs": first_frame_ms,
                "lastError": self._last_error,
                "localPort": self._active_port,
                "scid": f"{self._active_scid:08x}" if self._active_scid is not None else None,
            }

    def _seed_subscriber(self, q: queue.Queue) -> None:
        q.put_nowait(MediaEvent("state", {"state": self._state.value}))
        if self._width and self._height:
            q.put_nowait(MediaEvent("session", {
                "sessionId": self.session_id,
                "width": self._width,
                "height": self._height,
                "clientResized": False,
            }))
            if self._last_config_event is not None:
                q.put_nowait(self._last_config_event)

    def _set_state(self, state: MediaState, **details: Any) -> None:
        with self._lock:
            self._state = state
        payload = {"state": state.value, "sessionId": self.session_id, **details}
        self._broadcast(MediaEvent("state", payload))
        self._mark(f"media.state.{state.value.lower()}", payload)

    def _broadcast(self, event: MediaEvent) -> None:
        with self._lock:
            subscribers = tuple(self._subscribers)
        for q in subscribers:
            try:
                q.put_nowait(event)
            except queue.Full:
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
                try:
                    q.put_nowait(event)
                except queue.Full:
                    pass
                with self._lock:
                    self._dropped_events += 1

    def _mark(self, stage: str, details: dict[str, Any] | None = None) -> None:
        if self.diagnostic is None:
            return
        try:
            self.diagnostic(stage, details or {})
        except Exception:
            pass

    def _run(self) -> None:
        with self._lock:
            self._started_at_ms = _now_ms()
        self._set_state(MediaState.STARTING)
        attempt = 0
        try:
            while not self._stop.is_set():
                if not getattr(self.device, "screen_awake", True):
                    self._set_state(MediaState.SLEEPING)
                    while not self._stop.wait(0.25) and not getattr(self.device, "screen_awake", True):
                        pass
                    if self._stop.is_set():
                        return
                    self._set_state(MediaState.RECONNECTING, reason="SCREEN_WAKE")
                try:
                    self._run_once()
                    if not self._stop.is_set():
                        raise RuntimeError("scrcpy stream ended unexpectedly")
                    return
                except Exception as exc:
                    if self._stop.is_set():
                        return
                    self._last_error = _safe_text(exc)
                    self._mark("media.session.failed", {
                        "errorClass": exc.__class__.__name__,
                        "error": self._last_error,
                        "attempt": attempt,
                        "retryable": True,
                    })
                    if attempt >= len(_RECONNECT_BACKOFF_S):
                        self._set_state(
                            MediaState.UNAVAILABLE,
                            code="SCRCPY_STREAM_UNAVAILABLE",
                            errorClass=exc.__class__.__name__,
                        )
                        return
                    delay = _RECONNECT_BACKOFF_S[attempt]
                    attempt += 1
                    with self._lock:
                        self._restarts += 1
                    self._set_state(
                        MediaState.RECONNECTING,
                        code="SCRCPY_RETRY",
                        attempt=attempt,
                        retryAfterMs=int(delay * 1000),
                    )
                    if self._stop.wait(delay):
                        return
        finally:
            self._set_state(MediaState.STOPPED)

    def _run_once(self) -> None:
        self.artifact.verify()
        scid = secrets.randbelow(0x7FFFFFFF)
        port = _reserve_port()
        socket_name = f"scrcpy_{scid:08x}"
        remote_server = f"/data/local/tmp/cyclone-scrcpy-server-{scid:08x}.jar"
        adb = self.device.adb
        process: subprocess.Popen | None = None
        stream_socket: socket.socket | None = None
        with self._lock:
            self._active_port = port
            self._active_scid = scid
        try:
            # scrcpy deletes its server JAR during cleanup. A unique path prevents one
            # session from removing the JAR another concurrent/restarting session needs.
            adb.run(["push", str(self.artifact.path), remote_server], timeout=20)
            adb.run(["forward", f"tcp:{port}", f"localabstract:{socket_name}"], timeout=5)
            args = [
                "shell",
                f"CLASSPATH={remote_server}",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                self.artifact.version,
                f"scid={scid:08x}",
                "log_level=warn",
                "video=true",
                "audio=false",
                "control=false",
                "video_codec=h264",
                f"video_bit_rate={self.profile.bitrate_bps}",
                f"max_size={self.profile.max_long_edge}",
                f"max_fps={self.profile.target_fps}",
                "tunnel_forward=true",
                "send_device_meta=false",
                # The forward-tunnel dummy byte proves that ADB reached the Android
                # localabstract listener. A local TCP connect may succeed before that listener
                # exists, which otherwise looks like an immediate empty video stream.
                "send_dummy_byte=true",
                "send_stream_meta=true",
                "send_frame_meta=true",
                "cleanup=true",
                "power_on=false",
            ]
            process = adb.start_process(args, stdout=subprocess.PIPE)
            stream_socket = self._connect(port, process)
            stream_socket.settimeout(1.0)
            parser = ScrcpyVideoPacketParser()
            self._consume(stream_socket, parser, process)
        finally:
            if stream_socket is not None:
                try:
                    stream_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream_socket.close()
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1.0)
                except Exception:
                    process.kill()
            try:
                adb.remove_forward(port)
            except Exception:
                pass
            try:
                adb.run(["shell", "rm", "-f", remote_server], timeout=3)
            except Exception:
                pass
            with self._lock:
                self._active_port = None
                self._active_scid = None

    def _connect(self, port: int, process: subprocess.Popen) -> socket.socket:
        deadline = time.monotonic() + _CONNECT_TIMEOUT_S
        last_error: Exception | None = None
        while time.monotonic() < deadline and not self._stop.is_set():
            if process.poll() is not None:
                raise RuntimeError(f"scrcpy server exited before socket connection ({process.returncode})")
            try:
                candidate = socket.create_connection(("127.0.0.1", port), timeout=0.3)
                try:
                    candidate.settimeout(0.3)
                    dummy = candidate.recv(1)
                    if dummy != b"\x00":
                        raise RuntimeError("scrcpy forward tunnel did not return its ready byte")
                    return candidate
                except Exception:
                    candidate.close()
                    raise
            except (OSError, RuntimeError) as exc:
                last_error = exc
                time.sleep(0.05)
        raise RuntimeError(f"scrcpy video socket did not open: {_safe_text(last_error or RuntimeError('timeout'))}")

    def _consume(
        self,
        stream_socket: socket.socket,
        parser: ScrcpyVideoPacketParser,
        process: subprocess.Popen,
    ) -> None:
        codec_ok = False
        while not self._stop.is_set():
            if process.poll() is not None:
                raise RuntimeError(f"scrcpy server exited ({process.returncode})")
            try:
                chunk = stream_socket.recv(64 * 1024)
            except socket.timeout:
                continue
            if not chunk:
                returncode = process.poll()
                if returncode is None:
                    try:
                        returncode = process.wait(timeout=0.2)
                    except subprocess.TimeoutExpired:
                        process.terminate()
                        try:
                            returncode = process.wait(timeout=1.0)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            returncode = process.wait(timeout=1.0)
                stderr = ""
                stdout = ""
                if returncode is not None and process.stderr is not None:
                    try:
                        stderr = process.stderr.read(4096).decode("utf-8", "replace").strip()
                    except Exception:
                        stderr = ""
                if returncode is not None and process.stdout is not None:
                    try:
                        stdout = process.stdout.read(4096).decode("utf-8", "replace").strip()
                    except Exception:
                        stdout = ""
                server_output = stderr or stdout
                detail = f"; server={server_output[:320]}" if server_output else ""
                raise RuntimeError(f"scrcpy video socket closed (exit={returncode}){detail}")
            for event in parser.feed(chunk):
                if isinstance(event, CodecEvent):
                    if event.codec_id != SCRCPY_CODEC_H264:
                        raise ScrcpyProtocolError(f"Expected H.264 codec, got {event.codec}")
                    codec_ok = True
                    self._broadcast(MediaEvent("codec", {
                        "sessionId": self.session_id,
                        "codec": "video/avc",
                        "codecId": event.codec_id,
                    }))
                    continue
                if not codec_ok:
                    raise ScrcpyProtocolError("scrcpy media arrived before codec metadata")
                if isinstance(event, SessionEvent):
                    with self._lock:
                        self._width, self._height = event.width, event.height
                        self._last_config_event = None
                    self._set_state(MediaState.WAITING_KEYFRAME)
                    self._broadcast(MediaEvent("session", {
                        "sessionId": self.session_id,
                        "width": event.width,
                        "height": event.height,
                        "clientResized": event.client_resized,
                    }))
                    continue
                if isinstance(event, MediaPacket):
                    with self._lock:
                        self._packets += 1
                        self._last_pts_us = event.pts_us
                        if event.config:
                            self._config_packets += 1
                        else:
                            self._frames += 1
                    packet_event = MediaEvent("packet", {
                        "sessionId": self.session_id,
                        "ptsUs": event.pts_us,
                        "config": event.config,
                        "keyframe": event.keyframe,
                        "payload": event.payload,
                    })
                    if event.config:
                        with self._lock:
                            self._last_config_event = packet_event
                    elif event.keyframe:
                        # A viewer can join after the first SPS/PPS or drop a packet during a
                        # brief stall. Re-send codec configuration before each random-access frame.
                        with self._lock:
                            config_event = self._last_config_event
                        if config_event is not None:
                            self._broadcast(config_event)
                    self._broadcast(packet_event)
                    if event.keyframe:
                        with self._lock:
                            if self._first_frame_at_ms is None:
                                self._first_frame_at_ms = _now_ms()
                        self._set_state(MediaState.LIVE)

    def __repr__(self) -> str:
        return (
            f"ScrcpyMediaSession(device={getattr(self.device, 'device_id', None)!r}, "
            f"profile={self.profile.name!r}, state={self._state.value!r})"
        )


class ScrcpyMediaBackend:
    """Gateway-owned media backend; media depends only on ADB authorization + pinned scrcpy."""

    def __init__(
        self,
        *,
        artifact_path: str | None = None,
        diagnostic: Callable[[str, dict[str, Any]], None] | None = None,
    ):
        self.artifact_path = artifact_path
        self.diagnostic = diagnostic
        self._lock = threading.RLock()
        self._sessions: dict[tuple[str, str], ScrcpyMediaSession] = {}

    def probe(self, device: Any) -> dict[str, Any]:
        adb_state = getattr(getattr(device, "adb_device", None), "state", "device")
        artifact_ok = False
        artifact_error = None
        try:
            resolve_scrcpy_artifact(self.artifact_path)
            artifact_ok = True
        except Exception as exc:
            artifact_error = _safe_text(exc)
        return {
            "deviceId": getattr(device, "device_id", None),
            "adbAuthorized": adb_state == "device",
            "artifactVerified": artifact_ok,
            "artifactError": artifact_error,
            "codec": "video/avc",
            "backend": "scrcpy-v4.0",
        }

    def start(self, device: Any, profile: str | MediaProfile) -> ScrcpyMediaSession:
        spec = MediaProfile.named(profile) if isinstance(profile, str) else profile
        device_id = str(getattr(device, "device_id"))
        key = (device_id, spec.name)
        with self._lock:
            existing = self._sessions.get(key)
            if existing is not None and existing.status()["state"] != MediaState.STOPPED.value:
                return existing
            artifact = resolve_scrcpy_artifact(self.artifact_path)
            session = ScrcpyMediaSession(
                device,
                spec,
                artifact,
                self.diagnostic,
            )
            self._sessions[key] = session
            return session

    def stop(self, device: Any | str) -> None:
        device_id = device if isinstance(device, str) else str(getattr(device, "device_id"))
        with self._lock:
            items = [(key, session) for key, session in self._sessions.items() if key[0] == device_id]
        for key, session in items:
            session.stop()
            with self._lock:
                self._sessions.pop(key, None)

    def shutdown(self) -> None:
        with self._lock:
            sessions = tuple(self._sessions.values())
            self._sessions.clear()
        for session in sessions:
            session.stop()

    def status(self, device: Any | str | None = None) -> dict[str, Any]:
        device_id = None if device is None else (
            device if isinstance(device, str) else str(getattr(device, "device_id"))
        )
        with self._lock:
            sessions = [
                session for (did, _), session in self._sessions.items()
                if device_id is None or did == device_id
            ]
        return {
            "backend": "scrcpy-v4.0",
            "sessionCount": len(sessions),
            "sessions": [session.status() for session in sessions],
        }

    def latest_safe_snapshot(self, device: Any) -> SafeSnapshot:
        data = device.adb.exec_out("screencap", "-p", timeout=6)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("ADB screencap returned a non-PNG payload")
        width = height = None
        if len(data) >= 24 and data[12:16] == b"IHDR":
            width = int.from_bytes(data[16:20], "big")
            height = int.from_bytes(data[20:24], "big")
        return SafeSnapshot(data, "image/png", width, height, _now_ms())
