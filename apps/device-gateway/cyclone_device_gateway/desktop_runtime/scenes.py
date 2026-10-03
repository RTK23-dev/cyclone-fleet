"""Saved multi-device routines ("scenes").

A scene is a named, reusable multi-device command: "Morning check" always
means "Work Phone: check email for anything urgent" + "Tablet: check the
calendar for today". Running it re-splits are not needed - it's already
split, once, when you saved it.

Steps are stored by *nickname*, not raw device_id. Nicknames are the
durable, user-owned reference (see workspace.py); device_id can change if a
phone is factory reset and re-paired, but the person's name for "my tablet"
doesn't. Running a scene resolves nickname -> current device_id at run time
and fails clearly, per-step, if that nickname isn't currently paired to
anything - it does not silently skip a step or guess a substitute device.
"""
from __future__ import annotations

import json
import os
import re
import threading
from pathlib import Path
from typing import Any

_SCENE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,47}$")


class SceneError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class SceneStore:
    SCHEMA_VERSION = 1

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._scenes: dict[str, dict[str, Any]] = {}
        self._load()

    def public(self) -> dict[str, Any]:
        with self._lock:
            return {
                "schemaVersion": self.SCHEMA_VERSION,
                "scenes": [dict(self._scenes[key]) for key in sorted(self._scenes)],
            }

    def put_scene(self, scene_id: str, name: str, steps: list[dict[str, str]]) -> dict[str, Any]:
        if not _SCENE_ID.fullmatch(scene_id):
            raise SceneError("INVALID_REQUEST", "sceneId must contain only lowercase letters, numbers, dash, or underscore")
        clean_name = name.strip()
        if not clean_name or len(clean_name) > 80:
            raise SceneError("INVALID_REQUEST", "scene name must be 1..80 characters")
        if not isinstance(steps, list) or not (1 <= len(steps) <= 16):
            raise SceneError("INVALID_REQUEST", "a scene needs 1..16 steps")
        clean_steps: list[dict[str, str]] = []
        seen_nicknames: set[str] = set()
        for step in steps:
            if not isinstance(step, dict):
                raise SceneError("INVALID_REQUEST", "each step must be an object")
            nickname = str(step.get("nickname") or "").strip()
            goal = str(step.get("goal") or "").strip()
            if not nickname or len(nickname) > 40:
                raise SceneError("INVALID_REQUEST", "each step needs a device nickname (1..40 chars)")
            if not goal or len(goal) > 2000:
                raise SceneError("INVALID_REQUEST", "each step needs a non-empty goal (max 2000 chars)")
            key = nickname.casefold()
            if key in seen_nicknames:
                raise SceneError("INVALID_REQUEST", f"scene has two steps for the same device: {nickname!r}")
            seen_nicknames.add(key)
            clean_steps.append({"nickname": nickname, "goal": goal})
        scene = {"sceneId": scene_id, "name": clean_name, "steps": clean_steps}
        with self._lock:
            self._scenes[scene_id] = scene
            self._persist()
        return dict(scene)

    def delete_scene(self, scene_id: str) -> None:
        with self._lock:
            self._scenes.pop(scene_id, None)
            self._persist()

    def get_scene(self, scene_id: str) -> dict[str, Any]:
        with self._lock:
            scene = self._scenes.get(scene_id)
        if scene is None:
            raise SceneError("NOT_FOUND", f"No scene with id {scene_id}.")
        return dict(scene)

    def list_scenes(self) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(self._scenes[key]) for key in sorted(self._scenes)]

    def resolve_for_dispatch(
        self, scene_id: str, resolve_nickname: Any,
    ) -> tuple[list[dict[str, str]], list[str]]:
        """Turn a scene's nickname-keyed steps into device_id-keyed dispatch
        requests. Returns (resolved, missing_nicknames) - missing nicknames
        are reported, never silently dropped, so a stale scene doesn't
        quietly run on fewer devices than the person expects."""
        scene = self.get_scene(scene_id)
        resolved: list[dict[str, str]] = []
        missing: list[str] = []
        for step in scene["steps"]:
            device_id = resolve_nickname(step["nickname"])
            if device_id is None:
                missing.append(step["nickname"])
                continue
            resolved.append({"deviceId": device_id, "goal": step["goal"]})
        return resolved, missing

    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if payload.get("schemaVersion") != self.SCHEMA_VERSION:
                return
            for scene in payload.get("scenes", []):
                scene_id = str(scene.get("sceneId") or "")
                if not _SCENE_ID.fullmatch(scene_id):
                    continue
                steps = []
                for step in scene.get("steps", []):
                    nickname = str(step.get("nickname") or "").strip()
                    goal = str(step.get("goal") or "").strip()
                    if nickname and goal:
                        steps.append({"nickname": nickname[:40], "goal": goal[:2000]})
                if steps:
                    self._scenes[scene_id] = {
                        "sceneId": scene_id, "name": str(scene.get("name") or scene_id)[:80], "steps": steps,
                    }
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            self._scenes = {}

    def _persist(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(self.public(), indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temporary, self.path)
