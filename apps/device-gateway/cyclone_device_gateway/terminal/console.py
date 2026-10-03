"""The Cyclone One runtime is a windowed program. When `cyclone` starts it from a terminal, borrow that terminal for
output and questions, and stop cleanly when the terminal window is closed."""

from __future__ import annotations

import os
import sys
import threading
from typing import Callable

_handler_ref = None


def attach_parent_console() -> bool:
    if os.name != "nt":
        return sys.stdout is not None and sys.stdout.isatty()
    import ctypes

    kernel32 = ctypes.windll.kernel32
    if not kernel32.GetConsoleWindow():
        if not kernel32.AttachConsole(-1):  # ATTACH_PARENT_PROCESS
            return False
    try:
        sys.stdout = open("CONOUT$", "w", buffering=1, encoding="utf-8", errors="replace")  # noqa: SIM115
        sys.stderr = sys.stdout
        sys.stdin = open("CONIN$", "r", encoding="utf-8", errors="replace")  # noqa: SIM115
    except OSError:
        return False
    return True


def on_terminal_close(stop: threading.Event, cleanup: Callable[[], None]) -> None:
    """Ctrl+C stops the loop; closing the terminal runs cleanup at once (Windows allows only a few seconds)."""
    global _handler_ref
    if os.name != "nt":
        return
    import ctypes
    from ctypes import wintypes

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.DWORD)
    def handler(ctrl_type: int) -> bool:
        stop.set()
        if ctrl_type in (2, 5, 6):  # CLOSE, LOGOFF, SHUTDOWN
            try:
                cleanup()
            finally:
                os._exit(0)
        return True

    _handler_ref = handler
    ctypes.windll.kernel32.SetConsoleCtrlHandler(handler, True)
