from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .client import ADBClient, ADBError, ADBDevice
from .device import CYCLONE_PACKAGE
from ..cyclone_bridge.client import (
    BridgeDisconnectedError,
    BridgeOperationError,
    BridgeProtocolError,
)


@dataclass(frozen=True)
class BridgeReadiness:
    device: ADBDevice
    bridge_status: dict[str, Any]
    forward_recreated: bool


class BridgeReadinessError(RuntimeError):
    def __init__(self, code: str, safe_message: str):
        super().__init__(safe_message)
        self.code = code
        self.safe_message = safe_message


class BridgeConnectionManager:
    """Maintains the one fixed ADB -> Android localabstract bridge.

    This class is connection plumbing only. It exposes no general ADB command API and has no
    action authority. Every recovery operation is fixed to the selected device, Cyclone package,
    tcp:8766 and localabstract:cyclone_gateway.
    """

    def __init__(self, adb: ADBClient, bridge, *, local_port: int = 8766, requested_serial: str | None = None):
        self.adb = adb
        self.bridge = bridge
        self.local_port = local_port
        self.requested_serial = requested_serial
        self.last_serial: str | None = requested_serial

    def ensure_ready(self, *, require_accessibility: bool = True) -> BridgeReadiness:
        try:
            device = self.adb.select_device(self.requested_serial or self.last_serial)
        except ADBError as exc:
            raise BridgeReadinessError("DEVICE_DISCONNECTED", str(exc)) from exc
        self.last_serial = device.serial

        try:
            package_path = self.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=5).strip()
        except ADBError as exc:
            raise BridgeReadinessError(
                "DEVICE_DISCONNECTED",
                "The selected phone disconnected while checking the Cyclone APK.",
            ) from exc
        if not package_path.startswith("package:"):
            raise BridgeReadinessError(
                "CAPABILITY_UNAVAILABLE",
                f"Cyclone APK ({CYCLONE_PACKAGE}) is not installed on the selected phone.",
            )

        recreated = False
        try:
            try:
                recreated = bool(self.adb.ensure_bridge_forward(self.local_port))
            except (AttributeError, NotImplementedError):
                self.adb.forward_bridge(self.local_port)
                recreated = True
        except ADBError as exc:
            raise BridgeReadinessError(
                "DEVICE_DISCONNECTED",
                "ADB could not create the Cyclone USB bridge forward.",
            ) from exc

        try:
            status = self.bridge.request("bridge.status", {})
        except BridgeOperationError as exc:
            if exc.code == "AUTH_REJECTED":
                raise BridgeReadinessError(
                    "AUTH_REJECTED",
                    "The Android session token does not match the token currently shown by Cyclone.",
                ) from exc
            if exc.code in {"CAPABILITY_UNAVAILABLE", "STALE_OBSERVATION", "POLICY_DENIED", "PROTOCOL_MISMATCH"}:
                raise BridgeReadinessError(exc.code, f"Android bridge rejected readiness with {exc.code}.") from exc
            raise BridgeReadinessError("PROTOCOL_MISMATCH", "Android bridge returned an unsupported readiness error.") from exc
        except BridgeProtocolError as exc:
            raise BridgeReadinessError("PROTOCOL_MISMATCH", "Android bridge protocol is incompatible with this PC gateway.") from exc
        except BridgeDisconnectedError as exc:
            raise BridgeReadinessError(
                "DEVICE_DISCONNECTED",
                "Cyclone Android Gateway is not reachable. Enable Full PC + Codex Gateway on the phone.",
            ) from exc
        except OSError as exc:
            raise BridgeReadinessError("DEVICE_DISCONNECTED", "Cyclone Android Gateway transport disconnected.") from exc

        if not isinstance(status, dict):
            raise BridgeReadinessError("PROTOCOL_MISMATCH", "Android bridge status was not a JSON object.")
        required = ("gatewayEnabled", "socketListening", "accessibilityConnected")
        if any(not isinstance(status.get(field), bool) for field in required):
            raise BridgeReadinessError("PROTOCOL_MISMATCH", "Android bridge status is missing V3 readiness fields.")
        if not status["gatewayEnabled"] or not status["socketListening"]:
            raise BridgeReadinessError(
                "CAPABILITY_UNAVAILABLE",
                "Full PC + Codex Gateway is off on the phone. Enable it in Cyclone AI.",
            )
        if require_accessibility and not status["accessibilityConnected"]:
            raise BridgeReadinessError(
                "CAPABILITY_UNAVAILABLE",
                "Cyclone Accessibility is off. Enable the Cyclone accessibility service in Android Settings.",
            )
        return BridgeReadiness(device=device, bridge_status=status, forward_recreated=recreated)


class ManagedCycloneBridge:
    """Reconnect-aware facade with the same request interface as CycloneBridgeClient."""

    def __init__(self, raw_bridge, connection: BridgeConnectionManager):
        self.raw_bridge = raw_bridge
        self.connection = connection

    def request(self, op: str, args: dict | None = None) -> dict:
        try:
            readiness = self.connection.ensure_ready(
                require_accessibility=op not in {"bridge.status", "debug.snapshot"}
            )
        except BridgeReadinessError as exc:
            if exc.code == "DEVICE_DISCONNECTED":
                raise BridgeDisconnectedError(exc.safe_message) from exc
            raise BridgeOperationError(exc.code) from exc
        if op == "bridge.status":
            return readiness.bridge_status
        return self.raw_bridge.request(op, args or {})
