from __future__ import annotations

from dataclasses import dataclass
import ipaddress
from pathlib import Path
import os
import shutil
import sys
from urllib.parse import urlparse


BUNDLED_PLATFORM_TOOLS_DIR = "android-platform-tools"


def _bundled_adb_candidates() -> list[Path]:
    candidates: list[Path] = []

    # PyInstaller sidecars expose their actual executable path through sys.executable. Tauri
    # installs the Android Platform-Tools resource beside CyclonePCRuntime.exe, so this path also
    # works for silent/custom installer acceptance directories and does not depend on PATH.
    try:
        executable_root = Path(sys.executable).resolve().parent
        candidates.append(executable_root / BUNDLED_PLATFORM_TOOLS_DIR / "adb.exe")
    except (OSError, RuntimeError):
        pass

    # Normal per-user Cyclone One install location. Keep this fallback for development launchers
    # that execute the gateway through Python rather than the frozen runtime sidecar.
    local_app_data = os.getenv("LOCALAPPDATA", "").strip()
    if local_app_data:
        candidates.append(
            Path(local_app_data) / "Cyclone One" / BUNDLED_PLATFORM_TOOLS_DIR / "adb.exe"
        )

    return candidates


def resolve_adb_path() -> str:
    """Resolve adb without requiring Android Studio or a user PATH edit.

    Precedence is explicit operator override -> Cyclone One bundled Platform-Tools -> existing
    system/SDK installation -> plain `adb` fallback. This makes the shipped One installer
    self-contained while keeping development overrides possible.
    """
    configured = os.getenv("ADB_PATH", "").strip()
    if configured:
        return configured

    for candidate in _bundled_adb_candidates():
        if candidate.is_file():
            return str(candidate)

    discovered = shutil.which("adb")
    if discovered:
        return discovered

    candidates: list[Path] = []
    for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.getenv(env_name, "").strip()
        if root:
            candidates.append(Path(root) / "platform-tools" / "adb.exe")

    local_app_data = os.getenv("LOCALAPPDATA", "").strip()
    if local_app_data:
        local = Path(local_app_data)
        candidates.extend(
            [
                local / "Android" / "Sdk" / "platform-tools" / "adb.exe",
                local / "Android" / "platform-tools" / "adb.exe",
            ]
        )

    user_profile = os.getenv("USERPROFILE", "").strip()
    if user_profile:
        candidates.append(Path(user_profile) / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe")

    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)

    return "adb"


def resolve_gateway_port() -> int:
    explicit = os.getenv("CYCLONE_DEVICE_GATEWAY_PORT", "").strip()
    if explicit:
        try:
            port = int(explicit)
        except ValueError as exc:
            raise RuntimeError("CYCLONE_DEVICE_GATEWAY_PORT must be an integer") from exc
        if not 1 <= port <= 65535:
            raise RuntimeError("CYCLONE_DEVICE_GATEWAY_PORT is out of range")
        return port

    configured_url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "").strip()
    if configured_url:
        try:
            parsed = urlparse(configured_url)
            if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"} and parsed.port:
                return parsed.port
        except ValueError:
            pass

    return 8765


@dataclass(frozen=True)
class Settings:
    token: str
    device_serial: str | None
    adb_path: str
    runtime_dir: Path
    host: str = "127.0.0.1"
    port: int = 8765
    bridge_host: str = "127.0.0.1"
    bridge_port: int = 8766
    bridge_token: str = ""

    def __post_init__(self) -> None:
        for field_name, value in (("host", self.host), ("bridge_host", self.bridge_host)):
            if value == "localhost":
                continue
            try:
                is_loopback = ipaddress.ip_address(value).is_loopback
            except ValueError as exc:
                raise ValueError(f"{field_name} must be a loopback address") from exc
            if not is_loopback:
                raise ValueError(f"{field_name} must be a loopback address")
        if not 1 <= int(self.port) <= 65535:
            raise ValueError("port must be in range 1..65535")

    @classmethod
    def from_env(cls) -> "Settings":
        token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        if not token:
            from cyclone_device_gateway.tooling_seam import apply_gateway_env

            apply_gateway_env()
            token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        if not token:
            raise RuntimeError("CYCLONE_DEVICE_GATEWAY_TOKEN is required")

        bridge_token = os.getenv("CYCLONE_ANDROID_BRIDGE_TOKEN", "").strip()
        pairing_bootstrap = os.getenv("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "").strip().lower() in {
            "1",
            "true",
            "yes",
        }
        if not bridge_token and not pairing_bootstrap:
            raise RuntimeError("CYCLONE_ANDROID_BRIDGE_TOKEN is required")

        runtime_dir = Path(
            os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")
        ).expanduser().resolve()

        return cls(
            token=token,
            device_serial=os.getenv("CYCLONE_DEVICE_SERIAL") or None,
            adb_path=resolve_adb_path(),
            runtime_dir=runtime_dir,
            port=resolve_gateway_port(),
            bridge_token=bridge_token,
        )
