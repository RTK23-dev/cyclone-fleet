from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
import subprocess
import threading
import time

from pathlib import Path
import sys

from cyclone_device_gateway.cli import main
from cyclone_device_gateway.tooling_seam import apply_gateway_env, persist_runtime_bearer

_STILL_ACTIVE = 259
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
_CREATE_NO_WINDOW = 0x08000000


def _windows_kernel32():
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    return kernel32


def _parent_alive(pid: int) -> bool:
    if pid <= 0:
        return True
    if os.name == "nt":
        kernel32 = _windows_kernel32()
        handle = kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == _STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _terminate_own_process_tree() -> None:
    if os.name == "nt":
        # Terminate the frozen runtime and every helper it owns (notably adb track-devices).
        # The previous os._exit() path skipped cleanup and could leave a visible orphaned ADB
        # console after the Companion window closed.
        try:
            subprocess.Popen(
                ["taskkill", "/F", "/T", "/PID", str(os.getpid())],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=_CREATE_NO_WINDOW,
            )
        except Exception:
            os._exit(0)
        return
    os._exit(0)


def _watch_parent(pid: int) -> None:
    while True:
        time.sleep(2.0)
        if not _parent_alive(pid):
            _terminate_own_process_tree()
            return


def _start_parent_watch() -> None:
    raw = os.getenv("CYCLONE_PC_PARENT_PID", "").strip()
    if not raw:
        return
    try:
        pid = int(raw)
    except ValueError:
        return
    threading.Thread(target=_watch_parent, args=(pid,), name="cyclone-parent-watch", daemon=True).start()


if __name__ == "__main__":
    # Plan 44: ssh runs this runtime as its password helper for a cloud phone's tunnel; print the key and leave.
    from cyclone_device_gateway.cloud_fleet.tunnel import askpass_main
    if askpass_main():
        raise SystemExit(0)
    if sys.argv[1:2] in (["terminal"], ["install-cli"]):
        # The `cyclone` terminal command and the installer hook: no live-phone link or bearer persistence here;
        # Cyclone One's own runtime owns those. The terminal starts what it needs itself.
        raise SystemExit(main())
    token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
    url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:8765").strip()
    if token:
        mcp_executable = None
        if getattr(sys, "frozen", False):
            sibling = Path(sys.executable).with_name("CycloneAgentMCP.exe")
            if sibling.is_file():
                mcp_executable = sibling
        persist_runtime_bearer(token, url, write_cursor=True, mcp_executable=mcp_executable)
    else:
        apply_gateway_env()
    from cyclone_phone_mcp.live_phone_ipc import start as start_live_phone
    start_live_phone()
    _start_parent_watch()
    raise SystemExit(main())
