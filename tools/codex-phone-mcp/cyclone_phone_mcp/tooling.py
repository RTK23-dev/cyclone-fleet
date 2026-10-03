"""Bootstrap Cyclone One gateway env for local MCP attach.

Prefers ``cyclone_device_gateway.tooling_seam`` when installed. Otherwise loads
the token-free locator plus DPAPI / runtime-file bearer from Cyclone One
runtime (legacy Companion token as fallback). Never prints tokens.
"""

from __future__ import annotations

import ctypes
import json
import os
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

CRYPTPROTECT_UI_FORBIDDEN = 0x1
DEFAULT_GATEWAY_URL = "http://127.0.0.1:8765"
DEFAULT_GATEWAY_PORT = 8765
PRODUCT_ONE = "Cyclone One"

if os.name == "nt":
    from ctypes import wintypes

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]
else:
    DATA_BLOB = None  # type: ignore[misc, assignment]


def _isolated_tooling_root() -> bool:
    return bool(os.getenv("CYCLONE_TOOLING_TEST_ROOT", "").strip())


def _uses_dpapi_token() -> bool:
    """Windows DPAPI bearer file unless an isolated tooling test root is set."""
    return os.name == "nt" and not _isolated_tooling_root()


def _token_filename() -> str:
    return "gateway-token.dpapi" if _uses_dpapi_token() else "gateway-token.json"


def _local_app_data() -> Path:
    override = os.getenv("CYCLONE_TOOLING_TEST_ROOT", "").strip()
    if override:
        root = Path(override).expanduser()
        root.mkdir(parents=True, exist_ok=True)
        return root
    raw = os.getenv("LOCALAPPDATA", "").strip()
    if raw:
        return Path(raw)
    return Path.home() / "AppData" / "Local"


def _one_runtime_dir() -> Path:
    configured = os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", "").strip()
    if configured:
        path = Path(configured).expanduser()
        path.mkdir(parents=True, exist_ok=True)
        return path
    path = _local_app_data() / PRODUCT_ONE / "runtime"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _token_path() -> Path:
    return _one_runtime_dir() / _token_filename()


def _legacy_token_path() -> Path:
    return _local_app_data() / "Cyclone" / "pc-companion" / _token_filename()


def _locator_path() -> Path:
    return _one_runtime_dir() / "gateway-locator.json"


def _safe_loopback_url(value: str) -> str | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        parsed = urlparse(text)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port is None:
            return None
    except ValueError:
        return None
    return f"http://127.0.0.1:{parsed.port}"


def _port_from_url(url: str) -> int | None:
    try:
        return urlparse(url).port
    except ValueError:
        return None


def _blob(data: bytes):
    buffer = ctypes.create_string_buffer(data)
    blob = DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    return buffer, blob


def _protect(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptProtectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)


def _unprotect(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)


def _load_secret_file(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        raw = path.read_bytes()
        text = (_unprotect(raw) if _uses_dpapi_token() and path.suffix == ".dpapi" else raw).decode("utf-8").strip()
    except Exception:
        return None
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return {"token": text, "url": DEFAULT_GATEWAY_URL}
    if not isinstance(payload, dict):
        return None
    token = str(payload.get("token") or "").strip()
    url = _safe_loopback_url(str(payload.get("url") or ""))
    if not token:
        return None
    result: dict[str, Any] = {"token": token, "url": url or DEFAULT_GATEWAY_URL}
    if payload.get("port") is not None:
        result["port"] = payload["port"]
    return result


def _local_load_locator() -> dict[str, Any] | None:
    path = _locator_path()
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    url = _safe_loopback_url(str(payload.get("url") or ""))
    if not url:
        return None
    public = dict(payload)
    public.pop("token", None)
    public["url"] = url
    public["port"] = int(payload.get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT)
    public["sessionSecretPersisted"] = bool(payload.get("sessionSecretPersisted", True))
    return public


def _local_load_connection(*, include_env: bool = True) -> dict[str, str] | None:
    if include_env:
        env_token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        env_url = _safe_loopback_url(os.getenv("CYCLONE_DEVICE_GATEWAY_URL", ""))
        if env_token:
            locator = _local_load_locator() or {}
            url = env_url or str(locator.get("url") or DEFAULT_GATEWAY_URL)
            return {
                "token": env_token,
                "url": url,
                "port": str(locator.get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT),
                "runtime": str(locator.get("runtime") or _one_runtime_dir()),
            }
    for path in (_token_path(), _legacy_token_path()):
        loaded = _load_secret_file(path)
        if loaded:
            locator = _local_load_locator() or {}
            url = loaded.get("url") or locator.get("url") or DEFAULT_GATEWAY_URL
            return {
                "token": loaded["token"],
                "url": str(url),
                "port": str(loaded.get("port") or locator.get("port") or _port_from_url(str(url)) or DEFAULT_GATEWAY_PORT),
                "runtime": str(locator.get("runtime") or _one_runtime_dir()),
            }
    return None


def _local_apply_gateway_env(env: dict[str, str] | None = None) -> dict[str, str]:
    """Inject CYCLONE_DEVICE_GATEWAY_{TOKEN,URL,PORT,RUNTIME} when missing."""
    target = os.environ if env is None else env
    connection = _local_load_connection(include_env=False)
    locator = _local_load_locator() or {}
    token = (target.get("CYCLONE_DEVICE_GATEWAY_TOKEN") or "").strip()
    if not token and connection:
        token = connection["token"]
        target["CYCLONE_DEVICE_GATEWAY_TOKEN"] = token
    url = (target.get("CYCLONE_DEVICE_GATEWAY_URL") or "").strip()
    if not url:
        url = str((connection or {}).get("url") or locator.get("url") or DEFAULT_GATEWAY_URL)
        target["CYCLONE_DEVICE_GATEWAY_URL"] = url
    port = (target.get("CYCLONE_DEVICE_GATEWAY_PORT") or "").strip()
    if not port:
        target["CYCLONE_DEVICE_GATEWAY_PORT"] = str(
            locator.get("port") or (connection or {}).get("port") or _port_from_url(url) or DEFAULT_GATEWAY_PORT
        )
    runtime = (target.get("CYCLONE_DEVICE_GATEWAY_RUNTIME") or "").strip()
    if not runtime:
        target["CYCLONE_DEVICE_GATEWAY_RUNTIME"] = str(locator.get("runtime") or _one_runtime_dir())
    persisted = bool(token or (connection and connection.get("token")))
    return {
        "CYCLONE_DEVICE_GATEWAY_URL": target.get("CYCLONE_DEVICE_GATEWAY_URL", ""),
        "CYCLONE_DEVICE_GATEWAY_PORT": target.get("CYCLONE_DEVICE_GATEWAY_PORT", ""),
        "CYCLONE_DEVICE_GATEWAY_RUNTIME": target.get("CYCLONE_DEVICE_GATEWAY_RUNTIME", ""),
        "sessionSecretPersisted": "true" if persisted else "false",
    }


try:
    from cyclone_device_gateway.tooling_seam import apply_gateway_env, load_connection, load_locator
except ImportError:
    apply_gateway_env = _local_apply_gateway_env
    load_connection = _local_load_connection
    load_locator = _local_load_locator
