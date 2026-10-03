from __future__ import annotations

__all__ = [
    "DEFAULT_GATEWAY_URL",
    "token_path",
    "save_connection",
    "load_connection",
    "save_token",
    "load_token",
]

try:
    from cyclone_device_gateway.tooling_seam import (
        DEFAULT_GATEWAY_URL,
        load_connection,
        load_token,
        save_connection,
        save_token,
        token_path,
    )
except ImportError:
    import ctypes
    from ctypes import wintypes
    import json
    import os
    from pathlib import Path
    from urllib.parse import urlparse

    CRYPTPROTECT_UI_FORBIDDEN = 0x1
    DEFAULT_GATEWAY_URL = "http://127.0.0.1:8765"

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]

    def token_path() -> Path:
        root = Path(os.getenv("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "Cyclone One" / "runtime"
        root.mkdir(parents=True, exist_ok=True)
        return root / "gateway-token.dpapi"

    def _legacy_token_path() -> Path:
        return Path(os.getenv("LOCALAPPDATA") or Path.home()) / "Cyclone" / "pc-companion" / "gateway-token.dpapi"

    def save_connection(token: str, url: str) -> None:
        value = token.strip()
        if not value:
            return
        safe_url = _safe_loopback_url(url) or DEFAULT_GATEWAY_URL
        if os.name != "nt":
            return
        payload = json.dumps({"version": 1, "token": value, "url": safe_url}, separators=(",", ":")).encode("utf-8")
        encrypted = _protect(payload)
        path = token_path()
        temporary = path.with_suffix(".tmp")
        temporary.write_bytes(encrypted)
        temporary.replace(path)

    def _load_token_file(path: Path) -> dict[str, str] | None:
        if not path.is_file():
            return None
        try:
            raw = _unprotect(path.read_bytes()).decode("utf-8").strip()
        except Exception:
            return None
        if not raw:
            return None
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            # Backward compatibility with the first beta, which encrypted only the token.
            return {"token": raw, "url": DEFAULT_GATEWAY_URL}
        if not isinstance(payload, dict):
            return None
        token = str(payload.get("token") or "").strip()
        url = _safe_loopback_url(str(payload.get("url") or ""))
        if not token or not url:
            return None
        return {"token": token, "url": url}

    def load_connection() -> dict[str, str] | None:
        env_token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        env_url = _safe_loopback_url(os.getenv("CYCLONE_DEVICE_GATEWAY_URL", ""))
        if env_token:
            return {"token": env_token, "url": env_url or DEFAULT_GATEWAY_URL}
        if os.name != "nt":
            return None
        for path in (token_path(), _legacy_token_path()):
            loaded = _load_token_file(path)
            if loaded:
                return loaded
        return None

    def save_token(token: str) -> None:
        save_connection(token, os.getenv("CYCLONE_DEVICE_GATEWAY_URL", DEFAULT_GATEWAY_URL))

    def load_token() -> str | None:
        connection = load_connection()
        return connection.get("token") if connection else None

    def _safe_loopback_url(value: str) -> str | None:
        text = value.strip()
        if not text:
            return None
        try:
            parsed = urlparse(text)
            if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port is None:
                return None
        except ValueError:
            return None
        return f"http://127.0.0.1:{parsed.port}"

    def _blob(data: bytes):
        buffer = ctypes.create_string_buffer(data)
        blob = DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
        return buffer, blob

    def _protect(data: bytes) -> bytes:
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
