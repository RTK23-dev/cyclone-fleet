#!/usr/bin/env python3
"""Cyclone Glass guard: Glass shows and commands; it never thinks.

Fails when apps/glass/src grows intelligence or unsafe habits:
- model/provider calls or keys (OpenRouter, Anthropic, OpenAI, Gemini, chat completions, API keys, "LLM")
- tokens in localStorage (the session lives in memory + sessionStorage for one tab)
- Tauri or other desktop-only runtimes (Glass is a local website)
- innerHTML / eval / new Function (all UI is built with DOM APIs)
- runtime npm dependencies (Glass ships as static files; dev tooling only)
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASS = ROOT / "apps" / "glass"

RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("model or provider call", re.compile(r"openrouter|anthropic|openai|generativelanguage|chat/completions|\bllm\b|gpt-\d|claude-|gemini", re.I)),
    # Assignments and headers only: the secret deny-lists legitimately name "api_key" to block it.
    ("API key handling", re.compile(r"\b(api[_-]?key|apikey)\s*[:=]|x-api-key", re.I)),
    ("localStorage", re.compile(r"\blocalStorage\b")),
    ("desktop runtime", re.compile(r"@tauri-apps|__TAURI__|electron", re.I)),
    ("innerHTML/eval", re.compile(r"\.innerHTML\b|\.outerHTML\s*=|\beval\s*\(|new\s+Function\s*\(")),
)

COMMENT = re.compile(r"^\s*(//|/\*|\*)")


def scan(root: Path = GLASS) -> list[str]:
    errors: list[str] = []
    src = root / "src"
    if not src.is_dir():
        return [f"missing {src}"]
    for path in sorted(src.rglob("*")):
        if path.suffix not in {".ts", ".js", ".css", ".html"} or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if COMMENT.match(line):
                continue  # documentation may name what Glass must never do
            for label, pattern in RULES:
                if pattern.search(line):
                    errors.append(f"{path.relative_to(root)}:{line_no}: {label}: {line.strip()[:120]}")
    package = json.loads((root / "package.json").read_text(encoding="utf-8"))
    if package.get("dependencies"):
        errors.append(f"package.json: runtime dependencies are not allowed in Glass: {sorted(package['dependencies'])}")
    return errors


def main() -> int:
    errors = scan()
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        return 1
    print("Cyclone Glass guard: no intelligence, no localStorage, no desktop runtime, no innerHTML/eval, no runtime deps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
