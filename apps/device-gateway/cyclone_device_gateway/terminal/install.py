"""Install the `cyclone` command once: a small cyclone.cmd on the user's PATH that starts the Cyclone runtime.

Run by install.ps1 (plan 31, web-only). Only the current user's PATH is touched, never the machine's. It also copies,
once and without overwriting, the runtime data the retired Cyclone One window kept elsewhere, so pairing carries over.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path, PureWindowsPath

UPDATE_EXIT_CODE = 10
PACKAGE_NAME = "Cyclone-PC.zip"
# Where the Cyclone One (Tauri) window ran its runtime: %LOCALAPPDATA%\<bundle identifier>\runtime.
LEGACY_WINDOW_RUNTIME = ("com.cyclone.pccompanion", "runtime")


def shim_text(runtime_exe: Path, updates_dir: Path) -> str:
    # cyclone.cmd is a Windows file whatever builds it: always Windows separators.
    runtime_exe = PureWindowsPath(str(runtime_exe))
    package = PureWindowsPath(str(updates_dir)) / PACKAGE_NAME
    script = runtime_exe.parent / "install.ps1"
    return "\r\n".join([
        "@echo off",
        "rem Cyclone terminal command, written by the Cyclone installer. Type: cyclone",
        "setlocal",
        f'set "CYCLONE_RUNTIME={runtime_exe}"',
        'if not exist "%CYCLONE_RUNTIME%" (',
        "  echo Cyclone is not installed where this command expects it. Install it again, then open a new terminal.",
        "  exit /b 1",
        ")",
        '"%CYCLONE_RUNTIME%" terminal %*',
        'set "CYCLONE_EXIT=%ERRORLEVEL%"',
        f'if not "%CYCLONE_EXIT%"=="{UPDATE_EXIT_CODE}" exit /b %CYCLONE_EXIT%',
        # The runtime has exited with a verified package waiting. `(goto) 2>nul` ends this batch file first, so the
        # installer may replace cyclone.cmd while PowerShell runs the rest of the line; it starts cyclone again.
        f'(goto) 2>nul & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "{script}" -Zip "{package}" -Relaunch',
        "",
    ])


def migrate_window_runtime(local_app_data: Path, target: Path) -> list[str]:
    """Copy what the retired window's runtime kept (pairing, token, locator) into the terminal runtime, once: an entry
    that already exists in the target is never overwritten. Returns the names copied."""
    import shutil

    source = local_app_data.joinpath(*LEGACY_WINDOW_RUNTIME)
    if not source.is_dir():
        return []
    target.mkdir(parents=True, exist_ok=True)
    copied = []
    for entry in sorted(source.iterdir()):
        destination = target / entry.name
        if destination.exists() or entry.name.lower() in {"diagnostics", "logs"}:
            continue
        if entry.is_dir():
            shutil.copytree(entry, destination)
        else:
            shutil.copy2(entry, destination)
        copied.append(entry.name)
    return copied


def _norm(entry: str) -> str:
    return os.path.normcase(os.path.expandvars(entry.strip().strip('"')).rstrip("\\/"))


def path_with(current: str, directory: str) -> str | None:
    """PATH with `directory` appended, or None when it is already there."""
    entries = [e for e in current.split(";") if e.strip()]
    if any(_norm(e) == _norm(directory) for e in entries):
        return None
    return ";".join(entries + [directory])


def path_without(current: str, directory: str) -> str | None:
    entries = [e for e in current.split(";") if e.strip()]
    kept = [e for e in entries if _norm(e) != _norm(directory)]
    return None if len(kept) == len(entries) else ";".join(kept)


def default_bin_dir() -> Path:
    from ..tooling_seam import one_install_dir

    return one_install_dir() / "bin"


def install(runtime_exe: Path, bin_dir: Path | None = None, *, remove: bool = False) -> str:
    bin_dir = bin_dir or default_bin_dir()
    shim = bin_dir / "cyclone.cmd"
    if remove:
        shim.unlink(missing_ok=True)
        _update_user_path(lambda current: path_without(current, str(bin_dir)))
        return f"Removed the cyclone command ({shim})."
    bin_dir.mkdir(parents=True, exist_ok=True)
    text = shim_text(runtime_exe, bin_dir.parent / "updates")
    if not shim.is_file() or shim.read_text(encoding="ascii", errors="replace") != text:
        shim.write_text(text, encoding="ascii", errors="replace")
    changed = _update_user_path(lambda current: path_with(current, str(bin_dir)))
    from ..tooling_seam import local_app_data, one_runtime_dir

    moved = migrate_window_runtime(local_app_data(), one_runtime_dir())
    note = f" Kept your pairing from Cyclone One ({len(moved)} item{'s' if len(moved) != 1 else ''})." if moved else ""
    return f"Installed {shim}." + note + (" Open a new terminal and type: cyclone" if changed else "")


def _update_user_path(edit) -> bool:
    if os.name != "nt":
        return False
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment", 0, winreg.KEY_READ | winreg.KEY_WRITE) as key:
        try:
            current, kind = winreg.QueryValueEx(key, "Path")
        except FileNotFoundError:
            current, kind = "", winreg.REG_EXPAND_SZ
        updated = edit(str(current))
        if updated is None:
            return False
        winreg.SetValueEx(key, "Path", 0, kind if kind in (winreg.REG_SZ, winreg.REG_EXPAND_SZ) else winreg.REG_EXPAND_SZ, updated)
    _broadcast_environment_change()
    return True


def _broadcast_environment_change() -> None:
    """New terminals pick up the PATH change without signing out."""
    import ctypes
    from ctypes import wintypes

    result = wintypes.DWORD()
    ctypes.windll.user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0, "Environment", 0x0002, 5000, ctypes.byref(result))


def runtime_executable() -> Path:
    return Path(sys.executable).resolve()
