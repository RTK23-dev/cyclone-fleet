from __future__ import annotations

from dataclasses import asdict, dataclass
import ipaddress
import re
import time
from typing import Literal

from .client import ADBClient, ADBDevice, ADBError


TransportMode = Literal["usb", "wifi", "vmos"]
_PAIR_CODE = re.compile(r"^[0-9]{6}$")
_HOSTNAME = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")


class TransportOnboardingError(RuntimeError):
    """Safe operator-facing transport failure.

    Messages deliberately exclude ADB stdout/stderr for wireless pairing so a pairing code can
    never be reflected into Cyclone logs, diagnostics, or UI errors.
    """

    def __init__(self, code: str, safe_message: str):
        super().__init__(safe_message)
        self.code = code
        self.safe_message = safe_message


@dataclass(frozen=True)
class TransportDevice:
    serial: str
    state: str
    model: str | None
    ready: bool
    action: str

    def public(self) -> dict[str, object]:
        return asdict(self)


def normalize_adb_endpoint(raw: str) -> str:
    """Validate and canonicalize one ADB host:port endpoint without invoking a shell."""
    value = raw.strip()
    if not value or len(value) > 320:
        raise TransportOnboardingError("INVALID_ENDPOINT", "Enter an ADB address in host:port form.")
    if any(ch.isspace() for ch in value) or "://" in value or "/" in value or "@" in value or value.startswith("-"):
        raise TransportOnboardingError("INVALID_ENDPOINT", "Enter an ADB address in host:port form.")

    if value.startswith("["):
        match = re.fullmatch(r"\[([^\]]+)\]:(\d{1,5})", value)
        if not match:
            raise TransportOnboardingError("INVALID_ENDPOINT", "Use [IPv6-address]:port for IPv6 ADB endpoints.")
        host, port_text = match.groups()
        try:
            parsed = ipaddress.ip_address(host)
        except ValueError as exc:
            raise TransportOnboardingError("INVALID_ENDPOINT", "The IPv6 ADB address is invalid.") from exc
        if parsed.version != 6:
            raise TransportOnboardingError("INVALID_ENDPOINT", "Bracketed ADB endpoints are reserved for IPv6 addresses.")
        host_out = parsed.compressed
        canonical = f"[{host_out}]:{int(port_text)}"
    else:
        host, separator, port_text = value.rpartition(":")
        if not separator or not host or ":" in host or not port_text.isdigit():
            raise TransportOnboardingError("INVALID_ENDPOINT", "Enter an ADB address in host:port form.")
        try:
            parsed = ipaddress.ip_address(host)
            host_out = str(parsed)
        except ValueError:
            if not _HOSTNAME.fullmatch(host) or ".." in host:
                raise TransportOnboardingError("INVALID_ENDPOINT", "The ADB host name is invalid.")
            labels = host.split(".")
            if any(not label or label.startswith("-") or label.endswith("-") or len(label) > 63 for label in labels):
                raise TransportOnboardingError("INVALID_ENDPOINT", "The ADB host name is invalid.")
            host_out = host.lower()
        canonical = f"{host_out}:{int(port_text)}"

    port = int(port_text)
    if not 1 <= port <= 65535:
        raise TransportOnboardingError("INVALID_ENDPOINT", "The ADB port must be between 1 and 65535.")
    return canonical


def validate_pairing_code(code: str) -> str:
    value = code.strip()
    if not _PAIR_CODE.fullmatch(value):
        raise TransportOnboardingError("INVALID_PAIRING_CODE", "Enter the 6-digit Android Wireless debugging pairing code.")
    return value


def _device_public(device: ADBDevice) -> TransportDevice:
    state = device.state.lower()
    if state == "device":
        action = "READY"
    elif state == "unauthorized":
        action = "Approve the debugging prompt on the phone, then scan again."
    elif state == "offline":
        action = "The ADB transport is offline. Reconnect it, then scan again."
    else:
        action = f"ADB reports {state or 'unknown'}; reconnect the transport, then scan again."
    return TransportDevice(
        serial=device.serial,
        state=state,
        model=device.model,
        ready=state == "device",
        action=action,
    )


class ADBTransportOnboarding:
    """Narrow transport onboarding used by Cyclone One setup tooling.

    This surface intentionally supports only inventory, Android Wireless Debugging pair/connect,
    remote ADB connect (VMOS), and remote disconnect. It accepts no arbitrary ADB or shell command.
    """

    def __init__(self, adb: ADBClient, *, settle_seconds: float = 5.0, poll_seconds: float = 0.20):
        self.adb = adb
        self.settle_seconds = max(0.0, settle_seconds)
        self.poll_seconds = max(0.01, poll_seconds)

    def usb_status(self) -> dict[str, object]:
        try:
            devices = self.adb.devices()
        except ADBError as exc:
            raise TransportOnboardingError("ADB_UNAVAILABLE", "Cyclone One could not start its bundled ADB runtime.") from exc
        public = [_device_public(device).public() for device in devices]
        ready = sum(1 for device in public if device["ready"] is True)
        unauthorized = sum(1 for device in public if device["state"] == "unauthorized")
        if ready:
            next_step = "Cyclone One can discover the ready phone now."
        elif unauthorized:
            next_step = "Unlock the phone and approve USB debugging for this PC, then scan again."
        elif public:
            next_step = "ADB sees a phone, but its transport is not ready yet. Reconnect it, then scan again."
        else:
            next_step = "Connect USB, enable Developer options > USB debugging, and approve this PC."
        return {
            "mode": "usb",
            "ok": ready > 0,
            "readyDeviceCount": ready,
            "deviceCount": len(public),
            "devices": public,
            "next": next_step,
        }

    def pair_wireless(self, pair_endpoint: str, pairing_code: str, connect_endpoint: str) -> dict[str, object]:
        pair_target = normalize_adb_endpoint(pair_endpoint)
        connect_target = normalize_adb_endpoint(connect_endpoint)
        if pair_target == connect_target:
            raise TransportOnboardingError(
                "PAIR_CONNECT_ENDPOINTS_MATCH",
                "Android Wireless debugging uses a pairing address and a separate device address. Enter both values from Android Settings.",
            )
        code = validate_pairing_code(pairing_code)
        try:
            # Keep the code only in this local argv list. Never return ADB pair output or include it in an exception.
            self.adb.run(["pair", pair_target, code], timeout=20, use_serial=False)
        except ADBError as exc:
            raise TransportOnboardingError(
                "WIRELESS_PAIR_FAILED",
                "Android Wireless debugging pairing failed. Refresh the 6-digit code and pairing address, then try again.",
            ) from exc
        return self.connect(connect_target, mode="wifi")

    def connect(self, endpoint: str, *, mode: Literal["wifi", "vmos"] = "vmos") -> dict[str, object]:
        target = normalize_adb_endpoint(endpoint)
        try:
            self.adb.run(["connect", target], timeout=15, use_serial=False)
        except ADBError as exc:
            raise TransportOnboardingError(
                "ADB_CONNECT_FAILED",
                "Cyclone could not connect to that ADB address. Confirm the address is active and reachable, then try again.",
            ) from exc
        device = self._wait_for_target(target)
        public = _device_public(device).public() if device is not None else None
        if public is None:
            raise TransportOnboardingError(
                "ADB_CONNECT_NOT_VISIBLE",
                "ADB accepted the connection request but the phone did not appear in device inventory. Re-open debugging on the phone/provider and retry.",
            )
        return {
            "mode": mode,
            "ok": public["ready"] is True,
            "endpoint": target,
            "device": public,
            "next": "Cyclone One can discover this phone now." if public["ready"] is True else public["action"],
        }

    def disconnect(self, endpoint: str) -> dict[str, object]:
        target = normalize_adb_endpoint(endpoint)
        try:
            self.adb.run(["disconnect", target], timeout=10, use_serial=False)
        except ADBError as exc:
            raise TransportOnboardingError("ADB_DISCONNECT_FAILED", "Cyclone could not disconnect that ADB address.") from exc
        return {"ok": True, "endpoint": target, "state": "DISCONNECTED"}

    def _wait_for_target(self, target: str) -> ADBDevice | None:
        deadline = time.monotonic() + self.settle_seconds
        while True:
            try:
                devices = self.adb.devices()
            except ADBError as exc:
                raise TransportOnboardingError("ADB_UNAVAILABLE", "Cyclone One lost its bundled ADB runtime while checking the phone.") from exc
            matching = next((device for device in devices if device.serial.lower() == target.lower()), None)
            if matching is not None:
                return matching
            if time.monotonic() >= deadline:
                return None
            time.sleep(self.poll_seconds)
