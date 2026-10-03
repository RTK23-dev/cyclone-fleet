#!/usr/bin/env python3
"""Fail CI if release-signing material or inline signing secrets enter source control."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN_SUFFIXES = (".keystore", ".keystore.b64", ".jks", ".p12", ".pfx")
GRADLE_SECRET = re.compile(r"^\s*(?:storePassword|keyPassword)\s*=", re.MULTILINE)


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [ROOT / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def violations() -> list[str]:
    problems: list[str] = []
    for path in tracked_files():
        relative = path.relative_to(ROOT).as_posix()
        lower = relative.lower()
        if lower.endswith(FORBIDDEN_SUFFIXES):
            problems.append(f"tracked signing/key artifact: {relative}")
            continue
        if path.suffix in {".gradle", ".kts"} and path.is_file():
            text = path.read_text(encoding="utf-8", errors="replace")
            if GRADLE_SECRET.search(text):
                problems.append(f"inline signing password assignment: {relative}")
    return problems


def main() -> int:
    problems = violations()
    if problems:
        print("Repository security guard failed:")
        for problem in problems:
            print(f"- {problem}")
        print("Use protected CI environment secrets for signing; never commit key material.")
        return 1
    print("Repository security guard passed: no tracked signing material or inline signing passwords.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
