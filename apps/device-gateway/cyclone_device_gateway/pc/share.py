"""Share to ChatGPT: a cloudflared quick tunnel in front of this gateway, so ChatGPT Actions can reach Cloud Control at
`https://<name>.trycloudflare.com/cloud`. Cloud Control checks its own session tokens; every other gateway route still
needs the bearer. The share stops when the owner presses Stop or the gateway stops."""
from __future__ import annotations

import json
import re
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable, IO

from .common import CREATE_NO_WINDOW, PcFeatureError, install_root, is_windows, resources_dir

_HOST = re.compile(r"https://([a-z0-9-]{1,120}\.trycloudflare\.com)\b")


def find_host(line: str) -> str | None:
    match = _HOST.search(line)
    return match.group(1) if match else None


def cloudflared_path() -> Path | None:
    for candidate in (resources_dir() / "live-phone" / "cloudflared.exe", install_root() / "live-phone" / "cloudflared.exe"):
        if candidate.is_file():
            return candidate
    return None


Spawner = Callable[[list[str]], "subprocess.Popen[bytes]"]


def _spawn(argv: list[str]) -> "subprocess.Popen[bytes]":
    return subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                            creationflags=CREATE_NO_WINDOW)


class ShareService:
    def __init__(self, port: int, spawn: Spawner = _spawn, exe: Callable[[], Path | None] = cloudflared_path,
                 windows: Callable[[], bool] = is_windows, status_file: Path | None = None,
                 wait_seconds: float = 30.0, sleep: Callable[[float], None] = time.sleep):
        self._port = port
        self._spawn = spawn
        self._exe = exe
        self._windows = windows
        self._status_file = status_file
        self._wait = wait_seconds
        self._sleep = sleep
        self._lock = threading.Lock()
        self._child: subprocess.Popen[bytes] | None = None
        self._url: str | None = None

    @property
    def local_base(self) -> str:
        return f"http://127.0.0.1:{self._port}/cloud"

    def _payload(self, running: bool, message: str) -> dict[str, Any]:
        url = self._url if running else None
        return {"ok": running and bool(url), "running": running, "url": url or "", "localBase": self.local_base,
                "message": message}

    def _write_status(self, running: bool) -> None:
        path = self._status_file or install_root() / "chatgpt-attach" / "share.json"
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"url": self._url or "", "localPort": self._port, "running": running,
                                        "localBase": self.local_base}), encoding="utf-8")
        except OSError:
            pass

    def _alive(self) -> bool:
        if self._child is not None and self._child.poll() is None:
            return True
        self._child, self._url = None, None
        return False

    def status(self) -> dict[str, Any]:
        with self._lock:
            if not self._alive():
                return self._payload(False, "Share to ChatGPT is off.")
            return self._payload(True, "ChatGPT can reach Cloud Control over HTTPS." if self._url else "Share to ChatGPT is starting.")

    def _read(self, child: "subprocess.Popen[bytes]", stream: IO[bytes]) -> None:
        for raw in iter(stream.readline, b""):
            host = find_host(raw.decode("utf-8", errors="replace"))
            if host:
                with self._lock:
                    if self._child is child:
                        self._url = f"https://{host}/cloud"
                        self._write_status(True)

    def start(self) -> dict[str, Any]:
        if not self._windows():
            raise PcFeatureError("Share to ChatGPT runs on Windows.", "UNSUPPORTED_PLATFORM", 501)
        with self._lock:
            if self._alive() and self._url:
                return self._payload(True, "ChatGPT can reach Cloud Control over HTTPS.")
            self._stop_locked()
            exe = self._exe()
            if exe is None:
                raise PcFeatureError("Share to ChatGPT needs the bundled cloudflared. Reinstall Cyclone.", "PACK_MISSING", 503)
            child = self._spawn([str(exe), "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{self._port}",
                                 "--protocol", "http2"])
            self._child = child
            if child.stderr is not None:
                threading.Thread(target=self._read, args=(child, child.stderr), name="cyclone-share", daemon=True).start()
        deadline = time.monotonic() + self._wait
        while time.monotonic() < deadline:
            with self._lock:
                if not self._alive():
                    self._write_status(False)
                    raise PcFeatureError("The HTTPS share stopped before it was ready. Try again.", "SHARE_FAILED", 502)
                if self._url:
                    return self._payload(True, "ChatGPT can reach Cloud Control over HTTPS.")
            self._sleep(0.25)
        return self.status()

    def _stop_locked(self) -> None:
        child, self._child, self._url = self._child, None, None
        if child is not None and child.poll() is None:
            child.kill()
            try:
                child.wait(5)
            except subprocess.TimeoutExpired:
                pass

    def stop(self) -> dict[str, Any]:
        with self._lock:
            self._stop_locked()
            self._write_status(False)
        return self._payload(False, "Share to ChatGPT stopped.")
