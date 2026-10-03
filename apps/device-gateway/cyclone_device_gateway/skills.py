"""Thin PC adapter for skill compile/run/match.

This module does not own a skill store. Durable drafts live in Android
AutomationStore via SkillCompiler.compile. The PC gateway only forwards
bounded payloads over the existing Cyclone bridge.
"""
from __future__ import annotations

from typing import Any

STORE_CLASS = "AutomationStore"
COMPILER = "SkillCompiler.compile"
COMPILE_OP = "skill.compile"
RUN_OP = "skill.run"
MATCH_OP = "skill.match"


def compile_args(payload: dict[str, Any]) -> dict[str, Any]:
    steps = payload.get("steps") if isinstance(payload.get("steps"), list) else []
    params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
    return {
        "goal": str(payload.get("goal") or "")[:1000],
        "app": str(payload.get("app") or "")[:240],
        "pageKey": str(payload.get("pageKey") or "")[:240],
        "status": "draft",
        "enabled": False,
        "storeClass": STORE_CLASS,
        "compiler": COMPILER,
        "steps": steps[:40],
        "params": params,
        "source": "PC_CODEX",
    }


def run_args(skill_id: str, *, dry_run: bool, params: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "skillId": str(skill_id)[:160],
        "dryRun": bool(dry_run),
        "params": params if isinstance(params, dict) else {},
        "source": "PC_CODEX",
    }


def match_args(goal: str, page_key: str = "") -> dict[str, Any]:
    return {"goal": str(goal)[:1000], "pageKey": str(page_key)[:240]}


def wrap_compile_result(result: Any) -> dict[str, Any]:
    body = result if isinstance(result, dict) else {"raw": result}
    skill = body.get("skill") if isinstance(body.get("skill"), dict) else body
    return {
        "ok": True,
        "status": "draft",
        "enabled": False,
        "storeClass": str(skill.get("storeClass") or body.get("storeClass") or STORE_CLASS),
        "compiler": COMPILER,
        "skill": skill,
    }
