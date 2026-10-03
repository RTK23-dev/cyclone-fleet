from __future__ import annotations

from dataclasses import asdict
import importlib.util
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
from typing import Any

from .adb.client import ADBClient, ADBError, ADBDevice
from .adb.device import CYCLONE_PACKAGE
from .cyclone_bridge.client import (
    BridgeDisconnectedError,
    BridgeOperationError,
    BridgeProtocolError,
    CycloneBridgeClient,
)
from .tooling_seam import (
    CURSOR_SERVER_KEY,
    PRODUCT_LEGACY_COMPANION,
    PRODUCT_ONE,
    apply_gateway_env,
    cursor_mcp_path,
    detect_legacy_companion,
    session_secret_persisted,
)


READY = "READY"
MISSING = "MISSING"
CONNECTED = "CONNECTED"
UNAUTHORIZED = "UNAUTHORIZED"
OFF = "OFF"
BROKEN = "BROKEN"
ERROR = "ERROR"
TOKEN_MISMATCH = "TOKEN MISMATCH"
DEGRADED = "DEGRADED"


class BridgeDoctor:
    def __init__(self, env: dict[str, str] | None = None, *, adb: ADBClient | None = None, bridge_factory=None):
        self.env = dict(os.environ if env is None else env)
        self.adb = adb or ADBClient(
            self.env.get("ADB_PATH", "adb"),
            self.env.get("CYCLONE_DEVICE_SERIAL") or None,
        )
        self.bridge_factory = bridge_factory or CycloneBridgeClient

    def run(self) -> dict[str, Any]:
        apply_gateway_env(self.env)
        checks: dict[str, dict[str, Any]] = {}
        selected: ADBDevice | None = None
        bridge_status: dict[str, Any] | None = None

        try:
            adb_ready = self.adb.available()
        except Exception:
            adb_ready = False
        checks["ADB"] = self._check(READY if adb_ready else MISSING, "Android Platform Tools detected" if adb_ready else "Install Android Platform Tools and add adb to PATH")

        devices: list[ADBDevice] = []
        if adb_ready:
            try:
                devices = self.adb.devices()
            except ADBError:
                devices = []
        requested = self.env.get("CYCLONE_DEVICE_SERIAL") or None
        if devices:
            try:
                selected = self.adb.select_device(requested)
                checks["Phone"] = self._check(CONNECTED, self._device_label(selected))
            except ADBError as exc:
                status = UNAUTHORIZED if any(device.state == "unauthorized" for device in devices) else ERROR
                checks["Phone"] = self._check(status, str(exc))
        else:
            checks["Phone"] = self._check(ERROR if adb_ready else MISSING, "No ADB phone detected")

        apk_found = False
        if selected is not None:
            try:
                apk_found = self.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=5).strip().startswith("package:")
            except Exception:
                apk_found = False
        checks["Cyclone APK"] = self._check(READY if apk_found else MISSING, CYCLONE_PACKAGE if apk_found else f"{CYCLONE_PACKAGE} not found on selected phone")

        forward_ready = False
        if selected is not None and apk_found:
            try:
                self.adb.ensure_bridge_forward(8766)
                forward_ready = any(
                    serial == selected.serial and local == "tcp:8766" and remote == "localabstract:cyclone_gateway"
                    for serial, local, remote in self.adb.forward_mappings()
                )
            except Exception:
                forward_ready = False
        checks["ADB Forward"] = self._check(READY if forward_ready else BROKEN, "tcp:8766 -> localabstract:cyclone_gateway" if forward_ready else "Could not create the fixed Cyclone bridge forward")

        android_token = (self.env.get("CYCLONE_ANDROID_BRIDGE_TOKEN") or "").strip()
        if not android_token:
            checks["Android Gateway"] = self._check(OFF, "Enable Full PC + Codex Gateway on the phone, then copy its session token")
            checks["Accessibility"] = self._check(OFF, "Unknown until Android Gateway is authenticated")
            checks["Authentication"] = self._check(ERROR, "Android session token is not present in this process environment")
        elif forward_ready:
            bridge = self.bridge_factory("127.0.0.1", 8766, android_token, auto_forward=False)
            try:
                bridge_status = bridge.request("bridge.status", {})
                enabled = bool(bridge_status.get("gatewayEnabled"))
                listening = bool(bridge_status.get("socketListening"))
                accessibility = bool(bridge_status.get("accessibilityConnected"))
                checks["Android Gateway"] = self._check(READY if enabled and listening else OFF, "Localabstract gateway authenticated" if enabled and listening else "Enable Full PC + Codex Gateway in Cyclone AI")
                checks["Accessibility"] = self._check(READY if accessibility else OFF, "Cyclone accessibility service connected" if accessibility else "Enable Cyclone Accessibility in Android Settings")
                checks["Authentication"] = self._check(READY, "Android session token accepted")
            except BridgeOperationError as exc:
                if exc.code == "AUTH_REJECTED":
                    checks["Android Gateway"] = self._check(OFF, "Gateway reachable but session authentication failed")
                    checks["Accessibility"] = self._check(OFF, "Unknown until authentication succeeds")
                    checks["Authentication"] = self._check(TOKEN_MISMATCH, "Copy the current phone session token and restart the PC gateway")
                elif exc.code == "PROTOCOL_MISMATCH":
                    checks["Android Gateway"] = self._check(ERROR, "Android Gateway protocol mismatch")
                    checks["Accessibility"] = self._check(OFF, "Unknown due to protocol mismatch")
                    checks["Authentication"] = self._check(READY, "Token accepted before protocol rejection")
                else:
                    checks["Android Gateway"] = self._check(OFF, f"Gateway rejected readiness: {exc.code}")
                    checks["Accessibility"] = self._check(OFF, "Unknown")
                    checks["Authentication"] = self._check(READY, "Bridge returned an authenticated error")
            except BridgeProtocolError:
                checks["Android Gateway"] = self._check(ERROR, "Android Gateway returned an incompatible protocol response")
                checks["Accessibility"] = self._check(OFF, "Unknown")
                checks["Authentication"] = self._check(ERROR, "Protocol mismatch prevented authentication confirmation")
            except BridgeDisconnectedError:
                checks["Android Gateway"] = self._check(OFF, "Enable Full PC + Codex Gateway in Cyclone AI")
                checks["Accessibility"] = self._check(OFF, "Unknown while Android Gateway is unreachable")
                checks["Authentication"] = self._check(ERROR, "Could not reach Android Gateway")
        else:
            checks["Android Gateway"] = self._check(OFF, "ADB forward is not ready")
            checks["Accessibility"] = self._check(OFF, "Unknown while ADB forward is broken")
            checks["Authentication"] = self._check(ERROR, "Cannot authenticate until ADB forward is ready")

        capabilities_ready = bool(
            bridge_status
            and isinstance(bridge_status.get("capabilities"), dict)
            and bridge_status["capabilities"].get("phoneTools")
        )
        checks["Capabilities"] = self._check(READY if capabilities_ready else DEGRADED, "Android typed capability inventory present" if capabilities_ready else "Capability inventory unavailable until the Android Gateway is ready")

        pc_gateway_status, pc_gateway_detail = self._pc_gateway_check()
        checks["PC Gateway"] = self._check(pc_gateway_status, pc_gateway_detail)
        pc_bearer_status, pc_bearer_detail = self._pc_bearer_check()
        checks["PC Bearer"] = self._check(pc_bearer_status, pc_bearer_detail)
        install_status, install_detail = self._install_path_check()
        checks["Install Path"] = self._check(install_status, install_detail)
        cursor_status, cursor_detail = self._cursor_mcp_check()
        checks["Cursor MCP"] = self._check(cursor_status, cursor_detail)

        mcp_ready = importlib.util.find_spec("cyclone_phone_mcp") is not None
        checks["MCP"] = self._check(READY if mcp_ready else ERROR, "cyclone_phone_mcp is installed" if mcp_ready else "Install tools/codex-phone-mcp into the bridge virtual environment")
        checks["MCP Session"] = self._check(
            READY,
            "MCP observe/act/locate/search/inspect/screenshot/skill_run/group_act require session_id. "
            "Pass session_id=default-foreground for the live human display (display 0). "
            "Named Session Kernel VD needs display_id > 0. "
            "Layer 2 is default-foreground / display 0, not a VD tile.",
        )

        bad = {MISSING, UNAUTHORIZED, OFF, BROKEN, ERROR, TOKEN_MISMATCH}
        overall = READY if all(item["status"] not in bad for item in checks.values()) else DEGRADED
        persisted = self._session_secret_persisted()
        return {
            "schema": "cyclone.bridge.doctor.v1",
            "product": PRODUCT_ONE,
            "overall": overall,
            "checks": checks,
            "device": asdict(selected) if selected is not None else None,
            "sessionSecretPersisted": persisted,
            "security": {
                "pc_http_loopback_only": True,
                "android_transport": "adb-forwarded-localabstract",
                "generic_shell_exposed": False,
                "arbitrary_adb_exposed": False,
                "tokens_in_output": False,
            },
        }

    def _pc_gateway_check(self) -> tuple[str, str]:
        apply_gateway_env(self.env)
        token = (self.env.get("CYCLONE_DEVICE_GATEWAY_TOKEN") or "").strip()
        if not token:
            return ERROR, "PC Gateway token is not configured for this process"
        url = (self.env.get("CYCLONE_DEVICE_GATEWAY_URL") or "http://127.0.0.1:8765").rstrip("/") + "/v1/capabilities"
        if not (url.startswith("http://127.0.0.1:") or url.startswith("http://localhost:")):
            return ERROR, "PC Gateway URL must remain loopback-only"
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=1.5) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if payload.get("protocol_version") != "cyclone.gateway.capability.v1":
                return ERROR, "PC Gateway capability protocol mismatch"
            return READY, "Loopback HTTP gateway authenticated"
        except urllib.error.HTTPError as exc:
            return (TOKEN_MISMATCH if exc.code in {401, 403} else ERROR), "PC Gateway authentication failed" if exc.code in {401, 403} else f"PC Gateway HTTP {exc.code}"
        except Exception:
            return ERROR, "PC Gateway is not running on the configured loopback URL"

    def _pc_bearer_check(self) -> tuple[str, str]:
        persisted = self._session_secret_persisted()
        env_token = bool((self.env.get("CYCLONE_DEVICE_GATEWAY_TOKEN") or "").strip())
        if persisted:
            return READY, "PC gateway bearer is persisted; sessionSecretPersisted=true"
        if env_token:
            return READY, "PC gateway bearer is present in this process; start Cyclone One to persist it"
        return ERROR, "PC gateway bearer is not persisted; start Cyclone One once"

    @staticmethod
    def _install_path_check() -> tuple[str, str]:
        try:
            legacy = detect_legacy_companion()
        except Exception:
            return DEGRADED, "Could not inspect Cyclone One install path"
        detail = str(legacy.get("detail") or "")
        if legacy.get("warn"):
            return DEGRADED, detail
        return READY, detail

    def _cursor_mcp_check(self) -> tuple[str, str]:
        path = self._cursor_mcp_file()
        if not path.is_file():
            return DEGRADED, "Cursor mcp.json not written yet; start Cyclone One"
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            return DEGRADED, "Cursor mcp.json is unreadable; start Cyclone One"
        servers = payload.get("mcpServers") if isinstance(payload, dict) else None
        if not isinstance(servers, dict) or CURSOR_SERVER_KEY not in servers:
            return DEGRADED, "Cursor mcp.json is missing mcpServers.cyclone-phone; start Cyclone One"
        server = servers.get(CURSOR_SERVER_KEY)
        if isinstance(server, str):
            command = server
        elif isinstance(server, dict):
            command = str(server.get("command") or "")
        else:
            command = ""
        if PRODUCT_LEGACY_COMPANION.lower() in command.replace("/", "\\").lower():
            return ERROR, "Cursor mcp.json still points at Cyclone PC Companion"
        return READY, "Cursor mcp.json points at Cyclone One"

    def _cursor_mcp_file(self) -> Path:
        override = (self.env.get("CYCLONE_CURSOR_MCP_PATH") or "").strip()
        if override:
            return Path(override).expanduser()
        return cursor_mcp_path()

    @staticmethod
    def _session_secret_persisted() -> bool:
        try:
            return bool(session_secret_persisted())
        except Exception:
            return False

    @staticmethod
    def _device_label(device: ADBDevice) -> str:
        model = f" ({device.model})" if device.model else ""
        return f"{device.serial}{model}"

    @staticmethod
    def _check(status: str, detail: str) -> dict[str, str]:
        return {"status": status, "detail": detail}


def format_human(report: dict[str, Any]) -> str:
    lines = [f"Cyclone Bridge Doctor: {report['overall']}", ""]
    for name in (
        "ADB", "Phone", "Cyclone APK", "Android Gateway", "Accessibility", "ADB Forward",
        "PC Gateway", "PC Bearer", "Install Path", "Cursor MCP", "Authentication", "Capabilities", "MCP",
        "MCP Session",
    ):
        item = report["checks"][name]
        lines.append(f"{name:<20} {item['status']}")
        if item.get("detail"):
            lines.append(f"  {item['detail']}")
    lines.append("")
    lines.append("Tokens are never printed by doctor.")
    return "\n".join(lines)
