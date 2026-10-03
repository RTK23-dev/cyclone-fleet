"""Glass in its own app window (Chrome or Edge --app) that closes when the terminal that opened it closes."""

from __future__ import annotations

import os
import subprocess
import webbrowser
from pathlib import Path
from typing import Callable


def browser_candidates(env: dict[str, str] | None = None) -> list[Path]:
    env = env if env is not None else dict(os.environ)
    roots = [env.get("ProgramFiles", ""), env.get("ProgramFiles(x86)", ""), env.get("LOCALAPPDATA", "")]
    relative = [Path("Google/Chrome/Application/chrome.exe"), Path("Microsoft/Edge/Application/msedge.exe")]
    return [Path(root) / rel for rel in relative for root in roots if root]


def find_browser(exists: Callable[[Path], bool] = Path.is_file, env: dict[str, str] | None = None) -> Path | None:
    return next((path for path in browser_candidates(env) if exists(path)), None)


def app_window_args(browser: Path, url: str, profile_dir: Path) -> list[str]:
    # A dedicated profile makes this process own the window (it does not hand off to an open browser), so ending
    # this process closes Glass; the profile also keeps Glass apart from the owner's everyday browsing.
    return [
        str(browser), f"--app={url}", f"--user-data-dir={profile_dir}", "--no-first-run",
        "--no-default-browser-check", "--disable-features=Translate", "--window-size=1440,920",
    ]


class GlassWindow:
    def __init__(self, process: subprocess.Popen | None):
        self.process = process
        self._job = None

    @property
    def owned(self) -> bool:
        return self.process is not None

    def alive(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def close(self) -> None:
        if self.alive():
            try:
                self.process.terminate()
            except OSError:
                pass


def open_glass_window(url: str, profile_dir: Path, *, browser: Path | None = None,
                      opener: Callable[[str], bool] = webbrowser.open) -> GlassWindow:
    browser = browser or (find_browser() if os.name == "nt" else None)
    if browser is None:
        opener(url)
        return GlassWindow(None)
    profile_dir.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(app_window_args(browser, url, profile_dir), stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    window = GlassWindow(process)
    if os.name == "nt":
        window._job = _kill_with_us(process)
    return window


def _kill_with_us(process: subprocess.Popen):
    """Put the window in a job object that Windows kills when this process ends, even if it is killed or the
    terminal is closed. Returns the job handle; it must stay open for our lifetime."""
    import ctypes
    from ctypes import wintypes

    class IO_COUNTERS(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in (
            "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

    class BASIC(ctypes.Structure):
        _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                    ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                    ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]

    class EXTENDED(ctypes.Structure):
        _fields_ = [("BasicLimitInformation", BASIC), ("IoInfo", IO_COUNTERS),
                    ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                    ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateJobObjectW.restype = wintypes.HANDLE
    job = kernel32.CreateJobObjectW(None, None)
    if not job:
        return None
    info = EXTENDED()
    info.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    kernel32.SetInformationJobObject(wintypes.HANDLE(job), 9, ctypes.byref(info), ctypes.sizeof(info))
    kernel32.AssignProcessToJobObject(wintypes.HANDLE(job), wintypes.HANDLE(int(process._handle)))  # noqa: SLF001
    return job
