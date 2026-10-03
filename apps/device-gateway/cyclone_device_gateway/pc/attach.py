"""ChatGPT Attach (VMOS cloud phones): the fleet's settings, protected with DPAPI; the ADB sync script; the saved
handoff; the bundled Custom GPT resources. VMOS AccessKeys and SSH Connect Keys never leave this module: reads return
only whether a key is set, sync output is redacted, and a handoff containing a key is refused. Same file and format as
Cyclone One (`%LOCALAPPDATA%\\Cyclone One\\chatgpt-attach\\fleet.dpapi`), so saved fleets carry over."""
from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Callable

from .. import tooling_seam
from .common import PcFeatureError, Runner, install_root, is_windows, powershell_argv, resources_dir, run_process, script_json

DEFAULT_GOAL = "Observe assigned phones and wait for the next instruction."
MAX_PADS = 50
FORBIDDEN_IN_HANDOFF = ("connectkey:", "accesskey:", "secretaccesskey:", "vmosapikey:")
_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_HOST = re.compile(r"^[A-Za-z0-9.:\-]{0,253}$")
_SPEC = re.compile(r"^[A-Za-z0-9.:\-]{1,64}$")


def _text(value: Any, limit: int) -> str:
    return str(value).strip()[:limit] if isinstance(value, (str, int)) else ""


def _port(value: Any, default: int) -> int:
    try:
        port = int(value)
    except (TypeError, ValueError):
        return default
    return port if 1 <= port <= 65535 else default


def clean_pad(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PcFeatureError("Each pad must be an object.", "INVALID_REQUEST", 422)
    pad_id = _text(raw.get("id"), 64)
    if not _ID.match(pad_id):
        raise PcFeatureError("A pad id may use letters, digits, dot, dash and underscore.", "INVALID_REQUEST", 422)
    host = _text(raw.get("sshHost"), 253)
    if not _HOST.match(host):
        raise PcFeatureError(f"Pad {pad_id}: the SSH host looks wrong.", "INVALID_REQUEST", 422)
    spec = _text(raw.get("remoteAdbSpec"), 64) or "localhost:1"
    if not _SPEC.match(spec):
        raise PcFeatureError(f"Pad {pad_id}: the remote ADB address looks wrong.", "INVALID_REQUEST", 422)
    pad = {
        "id": pad_id,
        "label": _text(raw.get("label"), 80) or pad_id,
        "sshHost": host,
        "sshPort": _port(raw.get("sshPort"), 1824),
        "sshUser": _text(raw.get("sshUser"), 64) or "s",
        "localAdbPort": _port(raw.get("localAdbPort"), 63670),
        "remoteAdbSpec": spec,
        "serial": _text(raw.get("serial"), 80) or None,
    }
    key = raw.get("connectKey")
    if isinstance(key, str) and key.strip():
        pad["connectKey"] = key.strip()[:4096]
    return pad


def clean_config(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PcFeatureError("The fleet must be an object.", "INVALID_REQUEST", 422)
    pads = raw.get("pads") or []
    if not isinstance(pads, list) or len(pads) > MAX_PADS:
        raise PcFeatureError(f"Up to {MAX_PADS} pads.", "INVALID_REQUEST", 422)
    cleaned = [clean_pad(pad) for pad in pads]
    if len({pad["id"] for pad in cleaned}) != len(cleaned):
        raise PcFeatureError("Two pads have the same id.", "INVALID_REQUEST", 422)
    base = _text(raw.get("controlApiBase"), 300)
    if base and not base.startswith(("https://", "http://127.0.0.1", "http://localhost")):
        raise PcFeatureError("CONTROL_API must be https:// (or this PC).", "INVALID_REQUEST", 422)
    config: dict[str, Any] = {"controlApiBase": base, "defaultGoal": _text(raw.get("defaultGoal"), 500) or DEFAULT_GOAL,
                              "pads": cleaned}
    key = raw.get("vmosApiKey")
    if isinstance(key, str) and key.strip():
        config["vmosApiKey"] = key.strip()[:4096]
    return config


def merge_secrets(previous: dict[str, Any] | None, incoming: dict[str, Any]) -> dict[str, Any]:
    """An empty secret field keeps the saved one: Glass never gets secrets back, so it cannot resend them."""
    previous = previous or {}
    if not incoming.get("vmosApiKey") and previous.get("vmosApiKey"):
        incoming["vmosApiKey"] = previous["vmosApiKey"]
    old = {pad.get("id"): pad for pad in previous.get("pads") or [] if isinstance(pad, dict)}
    for pad in incoming["pads"]:
        if not pad.get("connectKey") and old.get(pad["id"], {}).get("connectKey"):
            pad["connectKey"] = old[pad["id"]]["connectKey"]
    return incoming


def public_config(config: dict[str, Any]) -> dict[str, Any]:
    out = {"controlApiBase": config.get("controlApiBase", ""), "defaultGoal": config.get("defaultGoal") or DEFAULT_GOAL,
           "hasVmosApiKey": bool(config.get("vmosApiKey")), "pads": []}
    for pad in config.get("pads") or []:
        item = {k: v for k, v in pad.items() if k != "connectKey"}
        item["hasConnectKey"] = bool(pad.get("connectKey"))
        out["pads"].append(item)
    return out


def secrets_of(config: dict[str, Any]) -> list[str]:
    values = [config.get("vmosApiKey")] + [pad.get("connectKey") for pad in config.get("pads") or []]
    return [value for value in values if isinstance(value, str) and value]


def reject_secrets(markdown: str, secrets: list[str]) -> None:
    lowered = markdown.lower()
    if any(needle in lowered for needle in FORBIDDEN_IN_HANDOFF) or any(len(s) >= 4 and s in markdown for s in secrets):
        raise PcFeatureError("Refusing a handoff that contains a key. Keys never leave this PC.", "SECRET_IN_HANDOFF", 422)


class AttachService:
    def __init__(self, root: Path | None = None, pack: Path | None = None, run: Runner = run_process,
                 windows: Callable[[], bool] = is_windows,
                 protect: Callable[[bytes], bytes] = tooling_seam._protect,
                 unprotect: Callable[[bytes], bytes] = tooling_seam._unprotect,
                 adb: Callable[[], str] | None = None):
        self._root = root
        self._pack = pack
        self._run = run
        self._windows = windows
        self._protect = protect
        self._unprotect = unprotect
        self._adb = adb or self._default_adb

    @property
    def root(self) -> Path:
        return self._root or install_root() / "chatgpt-attach"

    def _pack_dir(self) -> Path:
        pack = self._pack or resources_dir() / "chatgpt-attach"
        if not (pack / "scripts" / "Sync-VmosFleet.ps1").is_file():
            raise PcFeatureError("The ChatGPT Attach pack is missing from this install. Reinstall Cyclone.", "PACK_MISSING", 503)
        return pack

    @staticmethod
    def _default_adb() -> str:
        bundled = install_root() / "android-platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        return str(bundled) if bundled.is_file() else "adb"

    def _load(self) -> dict[str, Any] | None:
        path = self.root / "fleet.dpapi"
        if not path.is_file():
            return None
        try:
            payload = json.loads(self._unprotect(path.read_bytes()).decode("utf-8"))
        except Exception as exc:
            raise PcFeatureError("The saved fleet could not be unlocked on this Windows account.", "SECRETS_LOCKED") from exc
        return payload if isinstance(payload, dict) else None

    def _save(self, config: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        path = self.root / "fleet.dpapi"
        path.write_bytes(self._protect(json.dumps(config).encode("utf-8")))
        if os.name != "nt":
            path.chmod(0o600)

    def load(self) -> dict[str, Any]:
        return public_config(self._load() or {"controlApiBase": "", "defaultGoal": DEFAULT_GOAL, "pads": []})

    def save(self, raw: Any) -> dict[str, Any]:
        merged = merge_secrets(self._load(), clean_config(raw))
        self._save(merged)
        return public_config(merged)

    def sync(self) -> dict[str, Any]:
        if not self._windows():
            raise PcFeatureError("VMOS sync runs on Windows.", "UNSUPPORTED_PLATFORM", 501)
        config = self._load()
        if not config or not config.get("pads"):
            raise PcFeatureError("Save at least one VMOS pad before syncing.", "INVALID_REQUEST", 422)
        pack = self._pack_dir()
        runtime = self.root / "runtime"
        runtime.mkdir(parents=True, exist_ok=True)
        fleet_file = runtime / "fleet.ephemeral.json"
        fleet_file.write_text(json.dumps(config), encoding="utf-8")
        try:
            result = self._run(powershell_argv(pack / "scripts" / "Sync-VmosFleet.ps1",
                                               ["-Json", "-AdbPath", self._adb(), "-FleetFile", str(fleet_file)]),
                               {}, pack, 180)
        finally:
            fleet_file.unlink(missing_ok=True)
        secrets = secrets_of(config)
        # Keys are short sometimes: redact every one of them, then parse.
        value = script_json(type(result)(result.returncode, _redact_all(result.stdout, secrets), _redact_all(result.stderr, secrets)),
                            "Sync-VmosFleet.ps1", [])
        value.setdefault("controlApi", config.get("controlApiBase", ""))
        pads = value.get("pads")
        value["pads"] = [pads] if isinstance(pads, dict) else pads if isinstance(pads, list) else []
        return value

    def save_handoff(self, markdown: Any) -> dict[str, str]:
        if not isinstance(markdown, str) or not markdown.strip() or len(markdown) > 200_000:
            raise PcFeatureError("The handoff is empty or too long.", "INVALID_REQUEST", 422)
        reject_secrets(markdown, secrets_of(self._load() or {}))
        self.root.mkdir(parents=True, exist_ok=True)
        path = self.root / "FLEET_HANDOFF.md"
        path.write_text(markdown, encoding="utf-8")
        return {"path": str(path)}

    def check_handoff(self, markdown: str) -> None:
        reject_secrets(markdown, secrets_of(self._load() or {}))

    def resources(self) -> dict[str, str]:
        pack = self._pack_dir()
        out = {}
        for key, name in (("openapi", "openapi-cloud-control.yaml"), ("instructions", "CUSTOM_GPT_INSTRUCTIONS.md"),
                          ("exampleFleet", "vmos.fleet.example.json")):
            try:
                out[key] = (pack / name).read_text(encoding="utf-8")
            except OSError as exc:
                raise PcFeatureError(f"The ChatGPT Attach pack is missing {name}.", "PACK_MISSING", 503) from exc
        return out


def _redact_all(text: str, secrets: list[str]) -> str:
    for secret in sorted(secrets, key=len, reverse=True):
        if len(secret) >= 4:
            text = text.replace(secret, "***")
    return text
