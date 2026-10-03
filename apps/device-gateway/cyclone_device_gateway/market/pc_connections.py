"""PC connections for the Marketplace: which AI agents on this PC reach Cyclone over MCP, and connecting them.

This runs the same Cyclone agent-MCP connector Cyclone One's Connections page uses, with fixed arguments only:
`status --probe-gateway`, `connect <host> --verify` for a fixed set of hosts, and `copy-config generic`. There is no
generic process route; the host is an enum, never user text.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable, Sequence

HOSTS: dict[str, tuple[str, str]] = {
    "codex": ("Codex", "OpenAI's coding agent on this PC."),
    "grok": ("Grok", "Grok desktop and CLI on this PC."),
    "cursor": ("Cursor", "The Cursor editor's agent."),
    "opencode": ("OpenCode", "OpenCode, including DeepSeek setups."),
    "copilot": ("Copilot", "GitHub Copilot CLI."),
    "generic": ("Any MCP client", "Claude Code, Windsurf or any client that takes an MCP server config."),
}
STATES = {"CONNECTED": "connected", "CONFIGURED": "configured", "DETECTED": "detected", "FAILED": "attention", "UNKNOWN": "not_found"}

Runner = Callable[[Sequence[str], float], tuple[int, str]]


def _run(argv: Sequence[str], timeout: float) -> tuple[int, str]:
    completed = subprocess.run(list(argv), capture_output=True, timeout=timeout, text=True,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return completed.returncode, completed.stdout


def connector_command() -> list[str] | None:
    """The agent-MCP connector: an explicit path, the Cyclone One sidecar next to this runtime, or the dev module."""
    explicit = os.getenv("CYCLONE_AGENT_MCP_EXE", "").strip()
    if explicit and Path(explicit).is_file():
        return [explicit]
    here = Path(sys.executable).resolve().parent
    for name in ("CycloneAgentMCP.exe", "CycloneAgentMCP"):
        if (here / name).is_file():
            return [str(here / name)]
    if importlib.util.find_spec("cyclone_agent_mcp") is not None:
        return [sys.executable, "-m", "cyclone_agent_mcp"]
    return None


class PcConnections:
    def __init__(self, command: Callable[[], list[str] | None] = connector_command, run: Runner = _run):
        self._command = command
        self._run = run

    def status(self) -> dict[str, Any]:
        base = self._command()
        cards = [{"id": host, "name": name, "description": about, "state": "unknown", "detected": False, "configured": False}
                 for host, (name, about) in HOSTS.items()]
        if base is None:
            return {"available": False, "reason": "The Cyclone agent connector is not installed next to this gateway. Reinstall Cyclone (it ships CycloneAgentMCP.exe next to the runtime).",
                    "connections": cards}
        try:
            code, out = self._run([*base, "status", "--probe-gateway"], 30.0)
            payload = json.loads(out) if code == 0 else None
        except (OSError, subprocess.SubprocessError, ValueError):
            payload = None
        if not isinstance(payload, dict):
            return {"available": False, "reason": "The Cyclone agent connector did not answer.", "connections": cards}
        adapters = ((payload.get("ai") or {}).get("adapters") or {}) if isinstance(payload.get("ai"), dict) else {}
        for card in cards:
            adapter = adapters.get(card["id"]) if isinstance(adapters, dict) else None
            if isinstance(adapter, dict):
                card["state"] = STATES.get(str(adapter.get("state") or "UNKNOWN").upper(), "not_found")
                card["detected"] = adapter.get("detected") is True
                card["configured"] = adapter.get("configured") is True
            if card["id"] == "generic" and card["state"] in {"not_found", "unknown"}:
                card["state"] = "ready" if (payload.get("generic_mcp") == "READY") else "attention"
        return {"available": True, "reason": None, "connections": cards}

    def connect(self, host: str) -> dict[str, Any]:
        if host not in HOSTS:
            raise ValueError("Unknown connection.")
        base = self._command()
        if base is None:
            raise RuntimeError("The Cyclone agent connector is not installed next to this gateway. Reinstall Cyclone (it ships CycloneAgentMCP.exe next to the runtime).")
        argv = [*base, "copy-config", "generic"] if host == "generic" else [*base, "connect", host, "--verify"]
        code, out = self._run(argv, 60.0)
        if host == "generic":
            return {"id": host, "ok": code == 0, "message": "Add this MCP server config to your client.", "config": out[:4000] if code == 0 else None}
        try:
            result = json.loads(out)
        except ValueError:
            result = {}
        ok = code == 0 and (result.get("verification") or {}).get("ok") is not False
        return {"id": host, "ok": ok, "message": str(result.get("message") or ("Connected." if ok else "The connection needs attention."))[:300],
                "config": None}
