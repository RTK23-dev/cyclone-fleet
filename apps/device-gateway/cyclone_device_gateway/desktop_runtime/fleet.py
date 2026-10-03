from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, replace
import hashlib
import re
import secrets
import socket
import subprocess
import threading
import time
from typing import Any, Callable

from ..adb.client import ADBClient, ADBDevice, ADBError
from ..adb.device import CYCLONE_PACKAGE
from ..cyclone_bridge.client import CycloneBridgeClient
from .events import FleetEventBroker
from .models import (
    DesktopRuntimeError,
    DeviceFleetState,
    FleetEventType,
    MAX_FLEET_DEVICES,
    RuntimeErrorCode,
    deterministic_device_id,
    now_ms,
)

_SIZE_RE = re.compile(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", re.IGNORECASE)
_MOBILE_VERSION_RE = re.compile(r"^[0-9]+(?:\.[0-9]+){1,3}(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?$")

MAX_RECONNECT_ATTEMPTS = 5
RECONNECT_BACKOFF_SECONDS = (1, 2, 4, 8, 15)


@dataclass
class RememberedSession:
    device_id: str
    credential: str | None = None
    local_port: int | None = None


@dataclass
class DeviceSession:
    device_id: str
    serial: str
    adb_device: ADBDevice
    adb: ADBClient
    local_port: int
    usb_session_id: str
    state: DeviceFleetState
    credential: str | None = None
    screen_awake: bool = True
    display_width: int | None = None
    display_height: int | None = None
    last_seen_ms: int = field(default_factory=now_ms)
    last_safe_error: str | None = None
    pending_pairing: Any = None
    video: Any = None
    bridge_ok: bool | None = None
    last_heartbeat_ms: int | None = None
    reconnect_attempts: int = 0
    next_reconnect_at_ms: int = 0
    bridge_last_error: str | None = None
    bridge_error_class: str | None = None
    bridge_gateway_enabled: bool | None = None
    bridge_socket_listening: bool | None = None
    mobile_version: str | None = None
    accessibility_connected: bool | None = None
    source: str = "USB"
    provider: str | None = None
    provider_instance_id: str | None = None
    input_owner: str = "HUMAN"
    # Alpha 88: the phone's own control owner (AGENT/HUMAN) from its last status, and whether Cyclone's process runs.
    phone_controller: str | None = None
    app_running: bool | None = None
    fleet_health: dict[str, Any] | None = None

    def public(self) -> dict[str, Any]:
        suffix = self.serial[-4:] if len(self.serial) >= 4 else self.serial
        model = self.adb_device.model or self.adb_device.device or "Android phone"
        width = self.display_width or 1080
        height = self.display_height or 2400
        paired = self.credential is not None
        state = self.state.value
        # Alpha 91: a phone whose Cyclone Accessibility is off can't see or act; it is never reported as READY.
        if self.state == DeviceFleetState.READY and self.accessibility_connected is False:
            state = DeviceFleetState.ATTENTION.value
        reconnecting_label = "Reconnecting"
        if self.reconnect_attempts:
            reconnecting_label = f"Reconnecting · attempt {self.reconnect_attempts} of {MAX_RECONNECT_ATTEMPTS}"
        connection_label = {
            DeviceFleetState.READY: "Ready",
            DeviceFleetState.SLEEPING: "Sleeping",
            DeviceFleetState.UNPAIRED: "Not paired",
            DeviceFleetState.PAIRING: "Pairing",
            DeviceFleetState.UNAUTHORIZED: "Authorize USB debugging",
            DeviceFleetState.ATTENTION: "Needs attention",
            DeviceFleetState.DISCONNECTED: reconnecting_label,
        }.get(DeviceFleetState(state), state.replace("_", " ").title())
        public = {
            "deviceId": self.device_id,
            "id": self.device_id,
            "state": state,
            "name": model,
            "model": self.adb_device.model,
            "manufacturer": None,
            "product": self.adb_device.product,
            "device": self.adb_device.device,
            "serialSuffix": suffix,
            "paired": paired,
            "pairing": self.pending_pairing is not None,
            "screen": "AWAKE" if self.screen_awake else "SLEEPING",
            "screenState": "AWAKE" if self.screen_awake else "SLEEPING",
            "display": {"width": width, "height": height},
            "displayWidth": width,
            "displayHeight": height,
            "lastSeenMs": self.last_seen_ms,
            "lastSeenEpochMs": self.last_seen_ms,
            "lastSafeError": self.last_safe_error,
            "source": self.source,
            "provider": self.provider,
            "providerInstanceId": self.provider_instance_id,
            "transport": {
                "kind": self.source,
                "endpoint": "loopback" if self.source == "VIRTUAL" else {"LAN": "lan", "CLOUD": "cloud"}.get(self.source, "usb"),
            },
            "connectionLabel": connection_label,
            "appRunning": self.app_running,
            "health": self.fleet_health,
            "inputOwner": self.input_owner,
            "connectionHealth": {
                "bridgeReachable": self.bridge_ok,
                "gatewayEnabled": self.bridge_gateway_enabled,
                "socketListening": self.bridge_socket_listening,
                "accessibilityConnected": self.accessibility_connected,
                "lastHeartbeatEpochMs": self.last_heartbeat_ms,
                "reconnectAttempts": self.reconnect_attempts,
                "maxReconnectAttempts": MAX_RECONNECT_ATTEMPTS,
                "nextRetryEpochMs": self.next_reconnect_at_ms or None,
                "lastError": self.bridge_last_error,
                "errorClass": self.bridge_error_class,
            },
            "video": {
                "mode": "SCREENSHOT",
                "width": width,
                "height": height,
                "rotationDegrees": 0,
                "codec": "image/jpeg",
            },
            "capabilities": {
                "manualControl": paired,
                "keyboard": paired,
                "clipboard": paired,
                "clipboardSync": False,
                "reconnect": True,
                "clipboardMode": "PC_TO_PHONE" if paired else "PAIRING_REQUIRED",
                "video": ["thumbnail", "focus"],
            },
        }
        # Only an authenticated phone status can supply this version. Glass uses it to
        # enable the live Atlas, so never infer 5.x from PC release metadata.
        if self.credential and self.mobile_version and self.state not in {
            DeviceFleetState.UNAUTHORIZED, DeviceFleetState.DISCONNECTED,
        }:
            public["mobileVersion"] = self.mobile_version
        return public

    def bridge(self, token: str | None = None, *, auto_forward: bool = False) -> CycloneBridgeClient:
        return CycloneBridgeClient(
            host="127.0.0.1",
            port=self.local_port,
            token=self.credential if token is None else token,
            adb=self.adb,
            auto_forward=auto_forward,
        )


class DeviceFleetManager:
    """Bounded, failure-isolated inventory of USB-connected Android devices.

    Normal discovery is event-driven through `adb track-devices -l`. A low-frequency fallback scan
    remains active so Cyclone recovers from a dead tracker or unusual Windows/ADB behavior. Manual
    scans use the exact same canonical `adb devices -l` path.
    """

    def __init__(
        self,
        *,
        adb_path: str = "adb",
        inventory_adb: ADBClient | None = None,
        adb_factory: Callable[[str], ADBClient] | None = None,
        poll_seconds: float = 20.0,
        max_devices: int = MAX_FLEET_DEVICES,
        max_workers: int = 8,
        event_broker: FleetEventBroker | None = None,
    ):
        self.adb_path = adb_path
        self.inventory_adb = inventory_adb or ADBClient(adb_path, None)
        self.adb_factory = adb_factory or (lambda serial: ADBClient(adb_path, serial))
        self.poll_seconds = max(5.0, min(float(poll_seconds), 30.0))
        self.max_devices = max(1, min(int(max_devices), 32))
        self.max_workers = max(1, min(int(max_workers), self.max_devices, 8))
        self.events = event_broker or FleetEventBroker()
        self._lock = threading.RLock()
        self._refresh_lock = threading.Lock()
        self._sessions: dict[str, DeviceSession] = {}
        self._serial_to_device: dict[str, str] = {}
        self._remembered: dict[str, RememberedSession] = {}
        self._fallback_thread: threading.Thread | None = None
        self._track_thread: threading.Thread | None = None
        self._tracker_process: subprocess.Popen | None = None
        self._tracker_restart_delay_seconds = 2.0
        self._stop = threading.Event()
        self._video_factory: Callable[[DeviceSession], Any] | None = None
        self._tracker_active = False
        self._tracker_restarts = 0
        self._last_tracker_error: str | None = None
        self._last_scan_at_ms: int | None = None
        self._last_scan_duration_ms: int | None = None
        self._last_scan_source = "never"
        self._last_adb_error: str | None = None
        self._last_raw_device_count = 0
        self._last_authorized_device_count = 0
        self._source_resolver: Callable[[str], dict[str, str] | None] | None = None

    def set_source_resolver(self, resolver: Callable[[str], dict[str, str] | None]) -> None:
        self._source_resolver = resolver
        with self._lock:
            for session in self._sessions.values():
                self._apply_source_metadata(session)

    def set_video_factory(self, factory: Callable[[DeviceSession], Any]) -> None:
        self._video_factory = factory
        with self._lock:
            for session in self._sessions.values():
                if session.video is None:
                    session.video = factory(session)

    def start(self) -> None:
        with self._lock:
            if self._fallback_thread and self._fallback_thread.is_alive():
                return
            self._stop.clear()
            self._fallback_thread = threading.Thread(
                target=self._fallback_loop,
                name="cyclone-device-fleet-fallback",
                daemon=True,
            )
            self._track_thread = threading.Thread(
                target=self._track_loop,
                name="cyclone-device-fleet-adb-events",
                daemon=True,
            )
            self._fallback_thread.start()
            self._track_thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._stop_tracker()
        for thread in (self._track_thread, self._fallback_thread):
            if thread and thread.is_alive():
                thread.join(timeout=3.0)
        with self._lock:
            sessions = tuple(self._sessions.values())
        for session in sessions:
            self._cleanup_session(session)

    def _fallback_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.refresh_once(source="startup" if self._last_scan_at_ms is None else "fallback")
            except Exception:
                pass
            wait_for = self.poll_seconds
            with self._lock:
                now = now_ms()
                pending_retries = [
                    session.next_reconnect_at_ms
                    for session in self._sessions.values()
                    if session.next_reconnect_at_ms > now
                ]
            if pending_retries:
                delay_seconds = max(0.5, (min(pending_retries) - now) / 1000.0)
                wait_for = max(0.5, min(delay_seconds, self.poll_seconds))
            self._stop.wait(wait_for)

    def _track_loop(self) -> None:
        if not hasattr(self.inventory_adb, "start_track_devices"):
            with self._lock:
                self._last_tracker_error = "ADB event tracking is unavailable; fallback scanning is active."
            return
        while not self._stop.is_set():
            process = None
            try:
                process = self.inventory_adb.start_track_devices()
                with self._lock:
                    self._tracker_process = process
                    self._tracker_active = True
                    self._last_tracker_error = None
                stdout = process.stdout
                if stdout is None:
                    raise ADBError("ADB device tracker has no output stream")

                while not self._stop.is_set():
                    line = stdout.readline()
                    if line == b"":
                        break
                    try:
                        self.refresh_once(source="adb-event")
                    except Exception:
                        pass
            except Exception as exc:
                with self._lock:
                    self._last_tracker_error = self._safe_error(exc)
            finally:
                with self._lock:
                    self._tracker_active = False
                    if self._tracker_process is process:
                        self._tracker_process = None
                if process is not None:
                    try:
                        process.terminate()
                    except Exception:
                        pass
            if self._stop.wait(self._tracker_restart_delay_seconds):
                break
            with self._lock:
                self._tracker_restarts += 1

    def _stop_tracker(self) -> None:
        with self._lock:
            process = self._tracker_process
            self._tracker_process = None
            self._tracker_active = False
        if process is None:
            return
        try:
            process.terminate()
            process.wait(timeout=1.0)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass

    def list_public(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._sessions[key].public() for key in sorted(self._sessions)]

    def diagnostics(self) -> dict[str, Any]:
        with self._lock:
            reconnecting = sum(
                1 for session in self._sessions.values()
                if session.state == DeviceFleetState.DISCONNECTED
            )
            attention = sum(
                1 for session in self._sessions.values()
                if session.state == DeviceFleetState.ATTENTION
            )
            bridge_errors = {
                session.device_id: {
                    "error": session.bridge_last_error,
                    "errorClass": session.bridge_error_class,
                    "attempts": session.reconnect_attempts,
                    "nextRetryEpochMs": session.next_reconnect_at_ms or None,
                }
                for session in self._sessions.values()
                if session.bridge_last_error is not None
            }
            return {
                "adbPath": self.adb_path,
                "adbAvailable": self._last_scan_at_ms is not None and self._last_adb_error is None,
                "rawAdbDeviceCount": self._last_raw_device_count,
                "authorizedAdbDeviceCount": self._last_authorized_device_count,
                "fleetDeviceCount": len(self._sessions),
                "reconnectingDeviceCount": reconnecting,
                "attentionDeviceCount": attention,
                "maxReconnectAttempts": MAX_RECONNECT_ATTEMPTS,
                "reconnectBackoffSeconds": list(RECONNECT_BACKOFF_SECONDS),
                "bridgeErrors": bridge_errors,
                "trackerActive": self._tracker_active,
                "trackerRestarts": self._tracker_restarts,
                "trackerError": self._last_tracker_error,
                "fallbackIntervalSeconds": self.poll_seconds,
                "lastScanAtEpochMs": self._last_scan_at_ms,
                "lastScanDurationMs": self._last_scan_duration_ms,
                "lastScanSource": self._last_scan_source,
                "lastScanError": self._last_adb_error,
            }

    def get(self, device_id: str) -> DeviceSession:
        with self._lock:
            session = self._sessions.get(device_id)
        if session is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Device is not connected.", retryable=True)
        return session

    def find_by_serial(self, serial: str) -> DeviceSession | None:
        needle = (serial or "").strip()
        if not needle:
            return None
        with self._lock:
            device_id = self._serial_to_device.get(needle)
            return self._sessions.get(device_id) if device_id else None

    def refresh_once(self, *, source: str = "manual") -> list[dict[str, Any]]:
        started = time.perf_counter()
        with self._refresh_lock:
            try:
                devices = self.inventory_adb.devices()
            except ADBError as exc:
                with self._lock:
                    self._last_scan_at_ms = now_ms()
                    self._last_scan_duration_ms = int((time.perf_counter() - started) * 1000)
                    self._last_scan_source = source
                    self._last_adb_error = self._safe_error(exc)
                    self._last_raw_device_count = 0
                    self._last_authorized_device_count = 0
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, str(exc), retryable=True) from exc

            devices = devices[: self.max_devices]
            with self._lock:
                self._last_scan_at_ms = now_ms()
                self._last_scan_source = source
                self._last_adb_error = None
                self._last_raw_device_count = len(devices)
                self._last_authorized_device_count = sum(1 for item in devices if item.state == "device")

            seen_serials = {d.serial for d in devices}
            with self._lock:
                known_serials = set(self._serial_to_device)
            for serial in known_serials - seen_serials:
                self._remove_serial(serial)

            refresh_targets: list[DeviceSession] = []
            now = now_ms()
            for device in devices:
                session = self._upsert(device)
                if device.state == "unauthorized":
                    self._set_state(session, DeviceFleetState.UNAUTHORIZED, "Authorize USB debugging on the phone.")
                elif device.state != "device":
                    self._set_state(session, DeviceFleetState.ATTENTION, f"ADB state is {device.state}.")
                else:
                    # Deterministic reconnect backoff: automatic ADB-event and fallback refreshes
                    # respect the bounded retry window, while an explicit manual scan retries now.
                    if (
                        source != "manual"
                        and session.credential
                        and session.next_reconnect_at_ms > now
                    ):
                        continue
                    refresh_targets.append(session)

            if refresh_targets:
                with ThreadPoolExecutor(max_workers=self.max_workers, thread_name_prefix="cyclone-fleet") as pool:
                    futures = {pool.submit(self._refresh_authorized, session): session for session in refresh_targets}
                    for future in as_completed(futures):
                        session = futures[future]
                        try:
                            future.result()
                        except Exception:
                            self._set_state(session, DeviceFleetState.ATTENTION, "Device health check failed safely.")

            with self._lock:
                self._last_scan_duration_ms = int((time.perf_counter() - started) * 1000)
            return self.list_public()

    def _upsert(self, device: ADBDevice) -> DeviceSession:
        with self._lock:
            existing_id = self._serial_to_device.get(device.serial)
            if existing_id and existing_id in self._sessions:
                session = self._sessions[existing_id]
                session.adb_device = device
                session.last_seen_ms = now_ms()
                return session

            remembered = self._remembered.get(device.serial)
            device_id = remembered.device_id if remembered else deterministic_device_id(device.serial)
            local_port = remembered.local_port if remembered and remembered.local_port is not None else self._allocate_port(device_id)
            session = DeviceSession(
                device_id=device_id,
                serial=device.serial,
                adb_device=device,
                adb=self.adb_factory(device.serial),
                local_port=local_port,
                usb_session_id=secrets.token_urlsafe(24),
                state=DeviceFleetState.UNAUTHORIZED if device.state == "unauthorized" else DeviceFleetState.UNPAIRED,
                credential=remembered.credential if remembered else None,
            )
            self._apply_source_metadata(session)
            if self._video_factory:
                session.video = self._video_factory(session)
            self._sessions[device_id] = session
            self._serial_to_device[device.serial] = device_id
            self._remembered[device.serial] = RememberedSession(device_id, session.credential, local_port)
        self.events.publish(FleetEventType.DEVICE_ADDED, device_id, device=session.public())
        return session

    def _allocate_port(self, device_id: str) -> int:
        seed = int(hashlib.sha256(device_id.encode()).hexdigest()[:8], 16)
        base, span = 18000, 1000
        used = {s.local_port for s in self._sessions.values()}
        used.update(r.local_port for r in self._remembered.values() if r.local_port is not None)
        forwarded_ports: set[int] = set()
        try:
            for _, local, _ in self.inventory_adb.forward_mappings():
                if local.startswith("tcp:"):
                    forwarded_ports.add(int(local.split(":", 1)[1]))
        except Exception:
            pass
        for offset in range(span):
            port = base + ((seed + offset) % span)
            if port not in used and port not in forwarded_ports and self._loopback_port_available(port):
                return port
        raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "No free Cyclone device bridge port is available.")

    def _apply_source_metadata(self, session: DeviceSession) -> None:
        metadata = self._source_resolver(session.serial) if self._source_resolver is not None else None
        if metadata:
            session.source = str(metadata.get("source") or "VIRTUAL")
            session.provider = metadata.get("provider")
            session.provider_instance_id = metadata.get("instanceId")
        elif session.serial.startswith("emulator-"):
            session.source = "VIRTUAL"
            session.provider = "external-emulator"
        elif ":" in session.serial:
            session.source = "LAN"
        else:
            session.source = "USB"

    @staticmethod
    def _loopback_port_available(port: int) -> bool:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False
        finally:
            sock.close()

    def _remove_serial(self, serial: str) -> None:
        with self._lock:
            device_id = self._serial_to_device.pop(serial, None)
            session = self._sessions.get(device_id) if device_id else None
        if session is None:
            return
        self._remembered[serial] = RememberedSession(session.device_id, session.credential, session.local_port)
        self._cleanup_session(session)
        # adb no longer lists the phone: never keep reporting its last "device" state as USB-authorized (alpha 88).
        session.adb_device = replace(session.adb_device, state="absent")
        session.bridge_ok = False
        session.next_reconnect_at_ms = 0
        session.bridge_error_class = "ADB_DISCONNECTED"
        session.bridge_last_error = "Android device is offline. Cyclone will reconnect when it returns."
        self._set_state(session, DeviceFleetState.DISCONNECTED, session.bridge_last_error)

    def _cleanup_session(self, session: DeviceSession) -> None:
        if session.video is not None:
            try:
                session.video.stop_all()
            except Exception:
                pass
        try:
            if hasattr(session.adb, "remove_forward"):
                session.adb.remove_forward(session.local_port)
        except Exception:
            pass

    def _refresh_authorized(self, session: DeviceSession) -> None:
        try:
            self._ensure_bridge_forward(session)
            if not self._package_present(session):
                session.mobile_version = None
                self._mark_bridge_unhealthy(session, None, "Cyclone mobile app is not installed on this phone.")
                self._set_state(session, DeviceFleetState.ATTENTION, "Install the Cyclone mobile app on this phone.")
                return
            session.screen_awake = self._screen_awake(session)
            self._refresh_display(session)
            if session.credential:
                self._heartbeat(session)
            session.last_seen_ms = now_ms()
            self._mark_bridge_healthy(session)
            target = DeviceFleetState.SLEEPING if not session.screen_awake and session.credential else (
                DeviceFleetState.READY if session.credential else DeviceFleetState.UNPAIRED
            )
            self._set_state(session, target, None)
        except Exception as exc:
            self._handle_bridge_failure(session, exc)

    def _heartbeat(self, session: DeviceSession) -> None:
        """One authenticated read-only bridge.status heartbeat.

        Pairing and desktop video use short, bounded ADB-forwarded connections rather than a
        permanently open phone socket. The heartbeat keeps the Android-side session indicator
        truthful and detects a rotated/revoked phone credential before control is attempted. It
        does not create a second authority or execute an action.
        """
        status = session.bridge().request(
            "bridge.status",
            {},
            request_id=f"desktop-heartbeat-{secrets.token_urlsafe(12)}",
        )
        self.record_bridge_status(session, status)
        if status.get("gatewayEnabled") is not True or status.get("socketListening") is not True:
            raise BridgeHealthError(
                "BRIDGE_GATEWAY_DISABLED",
                "Cyclone Mobile gateway is not ready on this phone.",
            )
        session.last_heartbeat_ms = now_ms()

    def record_bridge_status(self, session: DeviceSession, status: dict[str, Any] | None) -> None:
        """Persist only bounded bridge health facts; never persist tokens or bridge payloads."""
        value = status if isinstance(status, dict) else {}
        with self._lock:
            session.bridge_gateway_enabled = _optional_bool(value.get("gatewayEnabled"))
            session.bridge_socket_listening = _optional_bool(value.get("socketListening"))
            session.accessibility_connected = _optional_bool(value.get("accessibilityConnected"))
            # The phone owns who is in control. When the owner takes over on the phone, the PC mirrors it and any
            # AI control granted from the PC ends; the PC never overrides the phone (alpha 88).
            controller = value.get("controllerOwner")
            if controller in {"AGENT", "HUMAN"}:
                session.phone_controller = controller
                if controller == "HUMAN":
                    session.input_owner = "HUMAN"
            if value:
                session.app_running = True
            health = value.get("fleetHealth")
            if isinstance(health, dict):
                permissions = health.get("permissions") if isinstance(health.get("permissions"), dict) else {}
                session.fleet_health = {
                    "version": 1,
                    "batteryPercent": health.get("batteryPercent") if isinstance(health.get("batteryPercent"), int) else None,
                    "charging": bool(health.get("charging")),
                    "network": str(health.get("network") or "unknown")[:24],
                    "freeStorageMb": health.get("freeStorageMb") if isinstance(health.get("freeStorageMb"), int) else None,
                    "os": str(health.get("os") or "")[:40],
                    "model": str(health.get("model") or "")[:40],
                    "permissions": {
                        key: bool(permissions.get(key))
                        for key in ("accessibility", "camera", "notifications")
                        if key in permissions
                    },
                }
            version = value.get("appVersion")
            session.mobile_version = (
                version if isinstance(version, str) and len(version) <= 64 and _MOBILE_VERSION_RE.fullmatch(version) else None
            )

    def _mark_bridge_healthy(self, session: DeviceSession) -> None:
        with self._lock:
            session.bridge_ok = True
            session.reconnect_attempts = 0
            session.next_reconnect_at_ms = 0
            session.bridge_last_error = None
            session.bridge_error_class = None

    def _mark_bridge_unhealthy(
        self,
        session: DeviceSession,
        error_class: str | None,
        error: str | None,
    ) -> None:
        with self._lock:
            session.bridge_ok = False
            session.mobile_version = None
            session.bridge_error_class = error_class
            session.bridge_last_error = error

    def _handle_bridge_failure(self, session: DeviceSession, exc: Exception) -> None:
        safe_error = self._safe_error(exc)
        reason_code = str(getattr(exc, "reason_code", "") or getattr(exc, "code", "") or exc.__class__.__name__).upper()
        if reason_code in {"AUTH_REJECTED", "TRUST_EXPIRED", "TRUST_REVOKED", "AUTH_SIGNATURE_INVALID"}:
            # The phone rejected the per-device bridge credential.  Do not keep retrying a stale
            # sidecar token or accidentally point it at another phone.  PCTrustCoordinator may
            # still restore a signed session from its separate trust record.
            self.invalidate_credential(session, "TOKEN_SESSION_MISMATCH", safe_error)
            self._set_state(session, DeviceFleetState.UNPAIRED, "Allow this PC on the phone again.")
            return
        self._mark_bridge_unhealthy(session, reason_code, safe_error)
        with self._lock:
            attempts = session.reconnect_attempts
            can_retry = session.credential and attempts < MAX_RECONNECT_ATTEMPTS
            if can_retry:
                session.reconnect_attempts = attempts + 1
                backoff = RECONNECT_BACKOFF_SECONDS[
                    min(session.reconnect_attempts - 1, len(RECONNECT_BACKOFF_SECONDS) - 1)
                ]
                session.next_reconnect_at_ms = now_ms() + int(backoff * 1000)
        if can_retry:
            self._set_state(
                session,
                DeviceFleetState.DISCONNECTED,
                f"Cyclone USB bridge is reconnecting: {safe_error}",
            )
            return
        exhausted = (
            f" after {MAX_RECONNECT_ATTEMPTS} attempts"
            if session.reconnect_attempts >= MAX_RECONNECT_ATTEMPTS
            else ""
        )
        self._set_state(
            session,
            DeviceFleetState.ATTENTION,
            f"Cyclone USB bridge is not ready{exhausted}: {safe_error}",
        )

    def _ensure_bridge_forward(self, session: DeviceSession) -> None:
        """Repair only provably stale per-device forwards, never steal a live device port."""
        try:
            session.adb.ensure_bridge_forward(session.local_port)
            return
        except ADBError:
            if not self._reclaim_stale_forward(session):
                raise
        session.adb.ensure_bridge_forward(session.local_port)

    def _reclaim_stale_forward(self, session: DeviceSession) -> bool:
        try:
            mappings = self.inventory_adb.forward_mappings()
            connected = {item.serial for item in self.inventory_adb.devices()}
        except Exception:
            return False
        local = f"tcp:{session.local_port}"
        owners = [serial for serial, mapped_local, _ in mappings if mapped_local == local and serial != session.serial]
        if not owners or any(serial in connected for serial in owners):
            return False
        remover = getattr(self.inventory_adb, "remove_stale_forward", None)
        if not callable(remover):
            return False
        try:
            remover(session.local_port)
            return True
        except Exception:
            return False

    def mark_screen_awake(self, session: DeviceSession) -> None:
        """Record a successful, paired, fixed-purpose wake without waiting for fallback polling."""
        session.screen_awake = True
        session.last_seen_ms = now_ms()
        if session.credential:
            self._mark_bridge_healthy(session)
            self._set_state(session, DeviceFleetState.READY, None)
        self.events.publish(
            FleetEventType.SCREEN_STATE_CHANGED,
            session.device_id,
            state="AWAKE",
            device=session.public(),
        )

    def _package_present(self, session: DeviceSession) -> bool:
        try:
            output = session.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=4)
            return "package:" in output
        except Exception:
            return False

    def _screen_awake(self, session: DeviceSession) -> bool:
        try:
            output = session.adb.shell("dumpsys", "power", timeout=4).lower()
            return "minteractive=true" in output or "wakefulness=awake" in output or "display power: state=on" in output
        except Exception:
            return True

    def _refresh_display(self, session: DeviceSession) -> None:
        try:
            output = session.adb.shell("wm", "size", timeout=4)
            matches = _SIZE_RE.findall(output)
            if matches:
                width, height = matches[-1]
                session.display_width, session.display_height = int(width), int(height)
        except Exception:
            pass

    def remember_credential(self, session: DeviceSession, credential: str | None) -> None:
        with self._lock:
            # Re-confirming the same credential (trust restore, refresh) must not forget the version the
            # phone already reported over it; only a new or cleared credential starts from unknown.
            if credential != session.credential:
                session.mobile_version = None
            session.credential = credential
            if credential:
                session.bridge_ok = None
                session.reconnect_attempts = 0
                session.next_reconnect_at_ms = 0
                session.bridge_last_error = None
                session.bridge_error_class = None
            remembered = self._remembered.setdefault(session.serial, RememberedSession(session.device_id, None, session.local_port))
            remembered.credential = credential
            remembered.local_port = session.local_port

    def invalidate_credential(self, session: DeviceSession, reason_code: str, message: str) -> None:
        """Fail closed on rejected per-device credentials without touching trust material."""
        with self._lock:
            session.credential = None
            session.mobile_version = None
            session.bridge_ok = False
            session.bridge_error_class = reason_code
            session.bridge_last_error = message[:240]
            session.reconnect_attempts = 0
            session.next_reconnect_at_ms = 0
            remembered = self._remembered.setdefault(
                session.serial,
                RememberedSession(session.device_id, None, session.local_port),
            )
            remembered.credential = None
            remembered.local_port = session.local_port

    def set_pairing(self, session: DeviceSession, pending: Any) -> None:
        with self._lock:
            session.pending_pairing = pending
        if pending is not None:
            self._set_state(session, DeviceFleetState.PAIRING, None)
        elif session.credential:
            self._set_state(session, DeviceFleetState.SLEEPING if not session.screen_awake else DeviceFleetState.READY, None)
        else:
            self._set_state(session, DeviceFleetState.UNPAIRED, None)
        self.events.publish(FleetEventType.PAIRING_CHANGED, session.device_id, pairing=pending is not None)

    def _set_state(self, session: DeviceSession, state: DeviceFleetState, error: str | None) -> None:
        changed = session.state != state
        session.state = state
        session.last_safe_error = error
        if changed:
            self.events.publish(FleetEventType.STATE_CHANGED, session.device_id, state=state.value, device=session.public())

    @staticmethod
    def _safe_error(error: Exception) -> str:
        value = str(error).strip().replace("\r", " ").replace("\n", " ")
        return value[:240] or error.__class__.__name__


class BridgeHealthError(RuntimeError):
    def __init__(self, reason_code: str, message: str):
        super().__init__(message)
        self.reason_code = reason_code


def _optional_bool(value: Any) -> bool | None:
    return value if isinstance(value, bool) else None
