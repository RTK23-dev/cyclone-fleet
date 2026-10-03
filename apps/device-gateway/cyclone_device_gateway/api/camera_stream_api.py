"""Native-sensor camera fan-out for Cyclone One 1.5.5.

A source Android phone is encoded once by Cyclone's pinned scrcpy server. The same H.264
packet stream is then fanned out to at most five Cyclone Mobile viewers over per-device ADB
reverse tunnels. The source camera sensor aspect ratio is an invariant: no server or viewer is
allowed to crop or stretch it to a display-shaped ratio.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import queue
import re
import secrets
import socket
import subprocess
import threading
import time
from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from ..media.artifact import resolve_scrcpy_artifact
from ..media.backend import MediaEvent, MediaProfile, MediaState, ScrcpyMediaSession
from ..media.protocol import ScrcpyVideoPacketParser

MAX_CAMERA_VIEWERS = 5
PHONE_REVERSE_PORT = 17881
MIN_CAMERA_ANDROID_SDK = 31  # scrcpy camera capture requires Android 12+
MIN_VIEWER_VERSION_CODE = 102  # Cyclone Mobile 4.4.2
_VIEWER_COMPONENT = "com.cyclone.mobile/.stream.CameraStreamViewerActivity"
_WS_SEND_TIMEOUT_SECONDS = 1.5
_SOURCE_READY_TIMEOUT_SECONDS = 8.0
_VIEWER_TOKEN_HEADER = "x-cyclone-viewer-token"
_VIEWER_TARGET_HEADER = "x-cyclone-viewer-target"


def _reserve_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class CameraStartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_device_id: str = Field(min_length=1, max_length=160)
    target_device_ids: list[str] = Field(min_length=1, max_length=MAX_CAMERA_VIEWERS)
    facing: Literal["back", "front"] = "back"
    quality: Literal["high", "native"] = "high"
    fps: Literal[30, 60] = 30


@dataclass(frozen=True)
class CameraSpec:
    facing: str
    quality: str
    max_long_edge: int | None
    fps: int
    bitrate_bps: int

    @classmethod
    def from_body(cls, body: CameraStartBody) -> "CameraSpec":
        # The resolution policy and the bitrate are intentionally separate. "Native" means no
        # max-size constraint: scrcpy chooses the greatest declared size matching sensor aspect.
        # "High" remains a bandwidth-safe 1920-long-edge mode without changing that aspect.
        if body.quality == "native":
            return cls(body.facing, body.quality, None, body.fps, 48_000_000 if body.fps == 60 else 32_000_000)
        return cls(body.facing, body.quality, 1920, body.fps, 26_000_000 if body.fps == 60 else 18_000_000)

    @property
    def resolution_policy(self) -> str:
        return "sensor-greatest" if self.max_long_edge is None else f"sensor-aspect-up-to-{self.max_long_edge}"


class _CameraDeviceProxy:
    """Delegate a fleet device while preventing display sleep from pausing physical camera capture."""

    def __init__(self, device: Any):
        self._device = device

    @property
    def screen_awake(self) -> bool:
        # Scrcpy's shared screen-media session normally sleeps with display 0. Camera2 capture is a
        # different source and must not stop just because the source phone display times out.
        return True

    def __getattr__(self, name: str) -> Any:
        return getattr(self._device, name)


class CameraScrcpySession(ScrcpyMediaSession):
    """Failure-isolated scrcpy Camera2 session with late/lagging-viewer bootstrap recovery."""

    def __init__(self, device: Any, spec: CameraSpec, diagnostic=None):
        profile = MediaProfile(
            name="camera-native",
            max_long_edge=spec.max_long_edge or 0,
            target_fps=spec.fps,
            bitrate_bps=spec.bitrate_bps,
        )
        super().__init__(_CameraDeviceProxy(device), profile, resolve_scrcpy_artifact(), diagnostic)
        self.camera_spec = spec
        self._cached_config: MediaEvent | None = None
        self._cached_keyframe: MediaEvent | None = None

    def start_capture(self) -> None:
        """Start camera capture before any viewer subscribes, without creating a fake subscriber."""
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._run,
                name=f"cyclone-camera-{getattr(self.device, 'device_id', 'device')}",
                daemon=True,
            )
            self._thread.start()

    def subscribe(self) -> queue.Queue:
        subscriber = super().subscribe()
        with self._lock:
            bootstrap = tuple(event for event in (self._cached_config, self._cached_keyframe) if event is not None)
        # A late viewer must receive decoder configuration before its first decodable keyframe.
        for event in bootstrap:
            try:
                subscriber.put_nowait(event)
            except queue.Full:
                self._resync_subscriber(subscriber, event)
                break
        return subscriber

    def _broadcast(self, event: MediaEvent) -> None:
        # The generic media queue is intentionally tiny for low latency. For H.264 camera fan-out,
        # blindly evicting its oldest packet can remove SPS/PPS or the only keyframe and strand one
        # viewer on a black frame. Cache those boundaries, then explicitly resync only the lagging
        # subscriber whenever its queue fills.
        with self._lock:
            if event.kind == "packet":
                if event.data.get("config"):
                    self._cached_config = event
                    self._cached_keyframe = None
                elif event.data.get("keyframe"):
                    self._cached_keyframe = event
            subscribers = tuple(self._subscribers)
        for subscriber in subscribers:
            try:
                subscriber.put_nowait(event)
            except queue.Full:
                self._resync_subscriber(subscriber, event)
                with self._lock:
                    self._dropped_events += 1

    def _resync_subscriber(self, subscriber: queue.Queue, current: MediaEvent) -> None:
        while True:
            try:
                subscriber.get_nowait()
            except queue.Empty:
                break
        with self._lock:
            # Re-seed state + dimensions first. The Android decoder must know the source dimensions
            # before it can configure MediaCodec for the binary bootstrap that follows.
            self._seed_subscriber(subscriber)
            config = self._cached_config
            keyframe = self._cached_keyframe
        for event in (config, keyframe):
            if event is not None:
                try:
                    subscriber.put_nowait(event)
                except queue.Full:
                    return
        if current.kind != "packet":
            try:
                subscriber.put_nowait(current)
            except queue.Full:
                pass
            return
        if current is config or current is keyframe:
            return
        # Interframes are useful only after a valid keyframe for the current config. If that
        # boundary does not exist yet, drop this packet and wait for the next keyframe.
        if keyframe is not None:
            try:
                subscriber.put_nowait(current)
            except queue.Full:
                pass

    def status(self) -> dict[str, Any]:
        value = super().status()
        value.update({
            "source": "camera",
            "cameraFacing": self.camera_spec.facing,
            "quality": self.camera_spec.quality,
            "preserveSourceAspect": True,
            "requestedAspect": "sensor",
            "resolutionPolicy": self.camera_spec.resolution_policy,
            "maxLongEdge": self.camera_spec.max_long_edge,
            "targetFps": self.camera_spec.fps,
            "bitrateBps": self.camera_spec.bitrate_bps,
        })
        width = value.get("width")
        height = value.get("height")
        if isinstance(width, int) and isinstance(height, int) and width > 0 and height > 0:
            value["aspectRatio"] = width / height
        return value

    def _run_once(self) -> None:
        self.artifact.verify()
        scid = secrets.randbelow(0x7FFFFFFF)
        port = _reserve_port()
        socket_name = f"scrcpy_{scid:08x}"
        remote_server = f"/data/local/tmp/cyclone-camera-scrcpy-{scid:08x}.jar"
        adb = self.device.adb
        process: subprocess.Popen | None = None
        stream_socket: socket.socket | None = None
        with self._lock:
            self._active_port = port
            self._active_scid = scid
            self._cached_config = None
            self._cached_keyframe = None
        try:
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
                "video_source=camera",
                f"camera_facing={self.camera_spec.facing}",
                # This is the key invariant. scrcpy selects a declared camera size whose ratio
                # matches the physical sensor (+/- its documented tolerance).
                "camera_ar=sensor",
                f"camera_fps={self.camera_spec.fps}",
                "video_codec=h264",
                f"video_bit_rate={self.camera_spec.bitrate_bps}",
            ]
            if self.camera_spec.max_long_edge is not None:
                args.append(f"max_size={self.camera_spec.max_long_edge}")
            args += [
                "tunnel_forward=true",
                "send_device_meta=false",
                "send_dummy_byte=true",
                "send_stream_meta=true",
                "send_frame_meta=true",
                "cleanup=true",
                "power_on=false",
            ]
            process = adb.start_process(args, stdout=subprocess.PIPE)
            stream_socket = self._connect(port, process)
            stream_socket.settimeout(1.0)
            self._consume(stream_socket, ScrcpyVideoPacketParser(), process)
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


class CameraStreamManager:
    def __init__(self, runtime: Any):
        self.runtime = runtime
        self._lock = threading.RLock()
        self._session: CameraScrcpySession | None = None
        self._source_device_id: str | None = None
        self._target_device_ids: list[str] = []
        self._target_tokens: dict[str, str] = {}
        self._connected: set[str] = set()
        self._gateway_port: int | None = None
        self._launch_failures: list[dict[str, str]] = []

    def start(self, body: CameraStartBody, gateway_port: int) -> dict[str, Any]:
        targets = list(dict.fromkeys(body.target_device_ids))
        if len(targets) > MAX_CAMERA_VIEWERS:
            raise ValueError(f"A camera stream supports at most {MAX_CAMERA_VIEWERS} viewers.")
        if body.source_device_id in targets:
            raise ValueError("The source phone cannot also be a viewer.")

        # Source validity is global: without a usable source camera no stream can exist.
        source = self.runtime.fleet.get(body.source_device_id)
        self._require_adb_ready(source, "Source phone")
        self._require_camera_android(source)

        # Receiver validity is per-phone. A five-phone request must not be rejected because one
        # receiver is temporarily unauthorized or still on the previous APK. Good receivers proceed
        # and the incompatible receiver is surfaced as a degraded, named failure in Settings.
        target_sessions: list[Any] = []
        failures: list[dict[str, str]] = []
        for device_id in targets:
            try:
                target = self.runtime.fleet.get(device_id)
                self._require_adb_ready(target, f"Target phone {target.device_id}")
                self._require_viewer_version(target)
                target_sessions.append(target)
            except Exception as exc:
                failures.append({"deviceId": device_id, "error": _safe_error(exc)})

        if not target_sessions:
            summary = failures[0]["error"] if failures else "No compatible receiving phone is available."
            raise ValueError(f"No receiving phone is ready for camera streaming. {summary}")

        # Only replace an existing stream after source + at least one receiver pass preflight. An
        # invalid new selection therefore cannot tear down a healthy stream unnecessarily.
        self.stop()
        session = CameraScrcpySession(
            source,
            CameraSpec.from_body(body),
            diagnostic=lambda stage, details: self.runtime.live_diagnostics.mark(
                body.source_device_id, stage, details=details
            ),
        )
        eligible_ids = [target.device_id for target in target_sessions]
        tokens = {device_id: secrets.token_urlsafe(24) for device_id in eligible_ids}
        # Install provisional state before camera startup so status reflects exactly which request is
        # being validated, but do not open any receiving phone until the camera emits stream metadata.
        with self._lock:
            self._session = session
            self._source_device_id = body.source_device_id
            self._target_device_ids = list(eligible_ids)
            self._target_tokens = dict(tokens)
            self._connected.clear()
            self._gateway_port = gateway_port
            self._launch_failures = list(failures)

        try:
            session.start_capture()
            self._await_source_ready(session)
        except Exception:
            self.stop()
            raise

        successful: list[str] = []
        for target in target_sessions:
            try:
                self._launch_target(target, session.session_id, tokens[target.device_id], gateway_port)
                successful.append(target.device_id)
            except Exception as exc:
                failures.append({"deviceId": target.device_id, "error": _safe_error(exc)})
                self._remove_reverse(target)

        if not successful:
            self.stop()
            summary = failures[-1]["error"] if failures else "No viewer activity could be launched."
            raise ValueError(f"No receiving phone could start the camera viewer. {summary}")

        successful_tokens = {device_id: tokens[device_id] for device_id in successful}
        with self._lock:
            self._target_device_ids = successful
            self._target_tokens = successful_tokens
            self._connected.intersection_update(successful)
            self._launch_failures = failures
        return self.status()

    @staticmethod
    def _await_source_ready(session: CameraScrcpySession) -> None:
        deadline = time.monotonic() + _SOURCE_READY_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            status = session.status()
            state = str(status.get("state") or "")
            if state in {MediaState.WAITING_KEYFRAME.value, MediaState.LIVE.value}:
                return
            last_error = str(status.get("lastError") or "").strip()
            if state == MediaState.UNAVAILABLE.value or (state == MediaState.STOPPED.value and last_error):
                detail = last_error or "The selected camera mode is unavailable."
                raise ValueError(f"Source camera could not start. {detail}")
            time.sleep(0.05)
        raise ValueError("Source camera did not become ready. Try 30 fps or High quality on this phone.")

    @staticmethod
    def _require_adb_ready(device: Any, label: str) -> None:
        if str(getattr(getattr(device, "adb_device", None), "state", "")) != "device":
            raise ValueError(f"{label} is not ADB-ready.")

    @staticmethod
    def _require_camera_android(source: Any) -> None:
        raw = str(source.adb.shell("getprop", "ro.build.version.sdk", timeout=5)).strip()
        try:
            sdk = int(raw)
        except ValueError as exc:
            raise ValueError("Could not verify the source phone Android version.") from exc
        if sdk < MIN_CAMERA_ANDROID_SDK:
            raise ValueError("Camera streaming requires Android 12 or newer on the source phone.")

    @staticmethod
    def _require_viewer_version(target: Any) -> None:
        package = str(target.adb.shell("dumpsys", "package", "com.cyclone.mobile", timeout=8))
        match = re.search(r"\bversionCode=(\d+)", package)
        if not match:
            raise ValueError(f"Target phone {target.device_id} needs Cyclone Mobile 4.4.2 or newer.")
        if int(match.group(1)) < MIN_VIEWER_VERSION_CODE:
            raise ValueError(f"Target phone {target.device_id} needs Cyclone Mobile 4.4.2 or newer.")

    def _launch_target(self, target: Any, session_id: str, viewer_token: str, gateway_port: int) -> None:
        adb = target.adb
        self._remove_reverse(target)
        adb.run(["reverse", f"tcp:{PHONE_REVERSE_PORT}", f"tcp:{gateway_port}"], timeout=5)
        # Keep ephemeral credentials out of the URL. Uvicorn may access-log WebSocket paths; tokens
        # therefore travel as Android intent extras and then authenticated WebSocket headers only.
        stream_url = f"ws://127.0.0.1:{PHONE_REVERSE_PORT}/v1/camera-stream/ws/{session_id}"
        output = str(adb.run([
            "shell", "am", "start",
            "-n", _VIEWER_COMPONENT,
            "--es", "cyclone_stream_url", stream_url,
            "--es", "cyclone_stream_token", viewer_token,
            "--es", "cyclone_stream_target", target.device_id,
        ], timeout=8))
        lowered = output.lower()
        if "error type" in lowered or "does not exist" in lowered or "unable to resolve" in lowered:
            raise RuntimeError("Cyclone Mobile camera viewer could not be opened on this phone.")

    @staticmethod
    def _remove_reverse(target: Any) -> None:
        try:
            target.adb.run(["reverse", "--remove", f"tcp:{PHONE_REVERSE_PORT}"], timeout=3)
        except Exception:
            pass

    def stop(self) -> dict[str, Any]:
        with self._lock:
            session = self._session
            target_ids = tuple(self._target_device_ids)
            self._session = None
            self._source_device_id = None
            self._target_device_ids = []
            self._target_tokens = {}
            self._connected.clear()
            self._gateway_port = None
            self._launch_failures = []
        if session is not None:
            session.stop()
        for device_id in target_ids:
            try:
                self._remove_reverse(self.runtime.fleet.get(device_id))
            except Exception:
                pass
        return self.status()

    def status(self) -> dict[str, Any]:
        with self._lock:
            session = self._session
            targets = list(self._target_device_ids)
            connected = sorted(self._connected)
            source = self._source_device_id
            failures = [dict(item) for item in self._launch_failures]
        stream_status = session.status() if session is not None else None
        stream_live = bool(stream_status and stream_status.get("state") == MediaState.LIVE.value)
        connection_degraded = stream_live and len(connected) < len(targets)
        return {
            "ok": True,
            "active": session is not None,
            "sourceDeviceId": source,
            "targetDeviceIds": targets,
            "requestedViewerCount": len(targets) + len(failures),
            "viewerCount": len(connected),
            "connectedViewerIds": connected,
            "disconnectedViewerIds": sorted(set(targets) - set(connected)) if stream_live else [],
            "maxViewers": MAX_CAMERA_VIEWERS,
            "preserveSourceAspect": True,
            "launchFailures": failures,
            "degraded": bool(failures) or connection_degraded,
            "stream": stream_status,
        }

    def subscribe(self, session_id: str, target_id: str, token: str) -> tuple[CameraScrcpySession, queue.Queue]:
        with self._lock:
            session = self._session
            expected = self._target_tokens.get(target_id)
            if session is None or session.session_id != session_id:
                raise LookupError("Camera stream is no longer active.")
            if target_id not in self._target_device_ids or not expected or not secrets.compare_digest(expected, token):
                raise PermissionError("Viewer token is invalid.")
            self._connected.add(target_id)
        return session, session.subscribe()

    def unsubscribe(self, session: CameraScrcpySession, target_id: str, subscriber: queue.Queue) -> None:
        session.unsubscribe(subscriber)
        with self._lock:
            self._connected.discard(target_id)


def create_camera_stream_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    manager = getattr(runtime, "camera_streaming", None)
    if manager is None:
        manager = CameraStreamManager(runtime)
        runtime.camera_streaming = manager

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/camera-stream/status", dependencies=[Depends(auth)])
    def status() -> dict[str, Any]:
        return manager.status()

    @router.post("/v1/camera-stream/start", dependencies=[Depends(auth)])
    def start(body: CameraStartBody, request: Request) -> dict[str, Any]:
        port = request.url.port
        if port is None:
            raise HTTPException(status_code=503, detail={"code": "CAMERA_STREAM_PORT_UNKNOWN", "message": "Gateway port is unavailable."})
        try:
            return manager.start(body, int(port))
        except (ValueError, LookupError, DesktopRuntimeError) as exc:
            raise HTTPException(status_code=409, detail={"code": "CAMERA_STREAM_START_FAILED", "message": str(exc)}) from exc
        except Exception as exc:
            raise HTTPException(status_code=503, detail={"code": "CAMERA_STREAM_START_FAILED", "message": _safe_error(exc)}) from exc

    @router.post("/v1/camera-stream/stop", dependencies=[Depends(auth)])
    def stop() -> dict[str, Any]:
        return manager.stop()

    @router.websocket("/v1/camera-stream/ws/{session_id}")
    async def viewer_socket(websocket: WebSocket, session_id: str) -> None:
        # Authentication is header-based so Uvicorn access logs never contain the ephemeral secret.
        target = (websocket.headers.get(_VIEWER_TARGET_HEADER) or "").strip()
        token_value = (websocket.headers.get(_VIEWER_TOKEN_HEADER) or "").strip()
        if not target or len(target) > 160 or len(token_value) < 16 or len(token_value) > 128:
            await websocket.close(code=4403)
            return
        try:
            session, subscriber = manager.subscribe(session_id, target, token_value)
        except PermissionError:
            await websocket.close(code=4403)
            return
        except LookupError:
            await websocket.close(code=4404)
            return

        await websocket.accept()
        try:
            await websocket.send_json({
                "type": "hello",
                "protocol": "cyclone-camera-h264-v1",
                "preserveSourceAspect": True,
                "maxViewers": MAX_CAMERA_VIEWERS,
            })
            while True:
                try:
                    event: MediaEvent = await asyncio.to_thread(subscriber.get, True, 1.0)
                except queue.Empty:
                    continue
                if event.kind == "packet":
                    payload = event.data.get("payload")
                    if not isinstance(payload, (bytes, bytearray)):
                        continue
                    flags = (1 if event.data.get("config") else 0) | (2 if event.data.get("keyframe") else 0)
                    pts_us = int(event.data.get("ptsUs") or 0)
                    header = bytes([flags]) + pts_us.to_bytes(8, "big", signed=True)
                    await asyncio.wait_for(
                        websocket.send_bytes(header + bytes(payload)),
                        timeout=_WS_SEND_TIMEOUT_SECONDS,
                    )
                    continue
                await asyncio.wait_for(
                    websocket.send_json({"type": event.kind, **{k: v for k, v in event.data.items() if k != "payload"}}),
                    timeout=_WS_SEND_TIMEOUT_SECONDS,
                )
                if event.kind == "state" and event.data.get("state") in {MediaState.STOPPED.value, MediaState.UNAVAILABLE.value}:
                    await websocket.close(code=1000)
                    return
        except WebSocketDisconnect:
            pass
        except asyncio.TimeoutError:
            # A single stalled phone must never build latency or block the other four viewers.
            try:
                await websocket.close(code=1013, reason="viewer too slow")
            except Exception:
                pass
        finally:
            manager.unsubscribe(session, target, subscriber)

    return router


def _safe_error(exc: Exception) -> str:
    return str(exc).replace("\r", " ").replace("\n", " ").strip()[:240] or exc.__class__.__name__
