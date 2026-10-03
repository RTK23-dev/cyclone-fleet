"""Shared pieces for the PC features: where the bundled packs live, running their PowerShell scripts, and redaction."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from ..tooling_seam import one_install_dir

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class PcFeatureError(Exception):
    """A PC feature could not do what was asked; the message is safe to show the owner."""

    def __init__(self, message: str, code: str = "PC_FEATURE_FAILED", status: int = 409):
        super().__init__(message)
        self.code = code
        self.status = status


def is_windows() -> bool:
    return os.name == "nt"


def resources_dir() -> Path:
    """The bundled packs (mcp-tunnel, chatgpt-attach, live-phone): next to the frozen runtime once installed (the
    same layout Cyclone One used), else the repository's copy."""
    explicit = os.getenv("CYCLONE_PC_RESOURCES", "").strip()
    if explicit:
        return Path(explicit).expanduser()
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[4] / "apps" / "pc-companion" / "src-tauri" / "resources"


def install_root() -> Path:
    return one_install_dir()


def decode_output(data: bytes) -> str:
    """PowerShell may answer in UTF-16 LE (with or without BOM) or UTF-8."""
    if data.startswith(b"\xff\xfe"):
        return data[2:].decode("utf-16-le", errors="replace")
    if len(data) >= 4 and data[1] == 0 and data[0] != 0 and data[3] == 0:
        return data.decode("utf-16-le", errors="replace")
    return data.decode("utf-8", errors="replace")


def redact(text: str, secrets: Sequence[str | None], min_length: int = 4) -> str:
    for secret in secrets:
        if secret and len(secret) >= min_length:
            text = text.replace(secret, "***")
    return text


def extract_json(stdout: str) -> dict[str, Any]:
    """The last JSON object a script printed (scripts may log lines before it)."""
    trimmed = stdout.strip()
    if not trimmed:
        raise PcFeatureError("The script produced no answer.")
    candidates = [trimmed] + [line.strip() for line in reversed(trimmed.splitlines()) if line.strip().startswith("{")]
    start, end = trimmed.find("{"), trimmed.rfind("}")
    if 0 <= start < end:
        candidates.append(trimmed[start:end + 1])
    for candidate in candidates:
        try:
            value = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(value, dict):
            return value
    raise PcFeatureError("The script's answer was not JSON.")


@dataclass
class ScriptResult:
    returncode: int
    stdout: str
    stderr: str


Runner = Callable[[Sequence[str], Mapping[str, str], Path, float], ScriptResult]


def run_process(argv: Sequence[str], env: Mapping[str, str], cwd: Path, timeout: float) -> ScriptResult:
    try:
        completed = subprocess.run(list(argv), cwd=str(cwd), env={**os.environ, **env}, capture_output=True,
                                   stdin=subprocess.DEVNULL, timeout=timeout, creationflags=CREATE_NO_WINDOW)
    except subprocess.TimeoutExpired as exc:
        raise PcFeatureError("The script took too long and was stopped.", "TIMEOUT", 504) from exc
    except OSError as exc:
        raise PcFeatureError(f"The script could not start ({type(exc).__name__}).") from exc
    return ScriptResult(completed.returncode, decode_output(completed.stdout or b""), decode_output(completed.stderr or b""))


def powershell_argv(script: Path, args: Sequence[str]) -> list[str]:
    return ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", str(script), *args]


def script_json(result: ScriptResult, name: str, secrets: Sequence[str | None]) -> dict[str, Any]:
    stdout = redact(result.stdout, secrets, 16)
    stderr = redact(result.stderr, secrets, 16)
    try:
        return extract_json(stdout)
    except PcFeatureError as exc:
        detail = (stderr.strip() or stdout.strip())[:400]
        if result.returncode == 0:
            raise PcFeatureError(f"{exc}: {detail}" if detail else str(exc)) from None
        raise PcFeatureError(f"{name} failed: {detail}" if detail else f"{name} failed") from None
