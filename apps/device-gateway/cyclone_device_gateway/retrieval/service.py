from __future__ import annotations

import re
from typing import Any

from ..state.store import StateStore


def _semantic_controls(semantic: dict[str, Any]) -> list[dict[str, Any]]:
    for key in ("semanticControls", "controls", "semantic_controls", "elements", "nodes"):
        value = semantic.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]

    page_context = semantic.get("pageContext")
    if isinstance(page_context, dict):
        controls = page_context.get("controls")
        if isinstance(controls, list):
            return [item for item in controls if isinstance(item, dict)]
    return []


def _raw_accessibility_nodes(semantic: dict[str, Any]) -> list[dict[str, Any]]:
    raw = semantic.get("rawAccessibility")
    if not isinstance(raw, dict):
        return []
    nodes = raw.get("nodes")
    if not isinstance(nodes, list):
        return []
    return [item for item in nodes if isinstance(item, dict)]


def _text(control: dict[str, Any]) -> str:
    return " ".join(
        str(control.get(key, ""))
        for key in (
            "text",
            "label",
            "semanticName",
            "content_desc",
            "contentDescription",
            "resource_id",
            "resourceId",
            "role",
            "class",
        )
    ).lower()


def _element_id(control: dict[str, Any]) -> str | None:
    value = control.get("elementId") or control.get("element_id") or control.get("id")
    return str(value) if value not in (None, "") else None


def _normalize_semantic(control: dict[str, Any]) -> dict[str, Any]:
    out = dict(control)
    element_id = _element_id(out)
    if element_id:
        out.setdefault("id", element_id)
        out.setdefault("elementId", element_id)
    out["source"] = "CYCLONE_ACCESSIBILITY"
    return out


def _normalize_raw(node: dict[str, Any], observation_id: str | None) -> dict[str, Any]:
    out = dict(node)
    raw_id = str(out.get("id") or "")
    if observation_id and raw_id:
        element_id = f"raw:{observation_id}:{raw_id}"
        out["id"] = element_id
        out["elementId"] = element_id
        out["rawNodeId"] = raw_id
    out["source"] = "CYCLONE_ACCESSIBILITY_RAW"
    return out


def rank_control(control: dict[str, Any], goal: str | None = None) -> float:
    score = 0.0
    haystack = _text(control)
    if goal:
        terms = [term for term in re.findall(r"\w+", goal.lower()) if len(term) > 1]
        score += 10 * sum(term in haystack for term in terms)
    if control.get("clickable") or control.get("editable") or control.get("scrollable") or control.get("interactive"):
        score += 5
    if control.get("resource_id") or control.get("resourceId"):
        score += 4
    if control.get("role") or control.get("class"):
        score += 3
    if control.get("visible", control.get("visibleToUser", True)):
        score += 2
    if control.get("verified") or control.get("learned"):
        score += 2
    return score


class RetrievalService:
    def __init__(self, store: StateStore):
        self.store = store

    def _current_parts(self) -> tuple[dict[str, Any] | None, dict[str, Any], str | None]:
        current = self.store.current_observation()
        semantic = current.get("semantic", {}) if current else {}
        observation_id = semantic.get("observationId") if isinstance(semantic, dict) else None
        return current, semantic if isinstance(semantic, dict) else {}, str(observation_id) if observation_id else None

    def search_ui(self, query: str, limit: int = 20) -> list[dict]:
        current, semantic, observation_id = self._current_parts()
        if not current:
            return []
        normalized_query = query.lower().strip()
        if not normalized_query:
            return []

        matches: list[dict[str, Any]] = []
        for control in _semantic_controls(semantic):
            if normalized_query in _text(control):
                matches.append(_normalize_semantic(control))
        for node in _raw_accessibility_nodes(semantic):
            if normalized_query in _text(node):
                matches.append(_normalize_raw(node, observation_id))
        for node in (current.get("uiautomator") or {}).get("nodes", []):
            if isinstance(node, dict) and normalized_query in _text(node):
                matches.append(dict(node, source="UIAUTOMATOR"))

        return sorted(matches, key=lambda item: rank_control(item, query), reverse=True)[:limit]

    def get_element(self, element_id: str) -> dict | None:
        current, semantic, observation_id = self._current_parts()
        if not current:
            return None

        for control in _semantic_controls(semantic):
            if _element_id(control) == element_id:
                return _normalize_semantic(control)

        for node in _raw_accessibility_nodes(semantic):
            normalized = _normalize_raw(node, observation_id)
            if _element_id(normalized) == element_id:
                return normalized

        for node in (current.get("uiautomator") or {}).get("nodes", []):
            if isinstance(node, dict) and str(node.get("id")) == element_id:
                return dict(node, source="UIAUTOMATOR")
        return None

    def get_controls(self, goal: str | None = None, limit: int = 36) -> list[dict]:
        current, semantic, _ = self._current_parts()
        if not current:
            return []
        controls = [_normalize_semantic(control) for control in _semantic_controls(semantic)]
        return sorted(controls, key=lambda item: rank_control(item, goal), reverse=True)[:limit]

    def get_page_context(self, mode: str = "compact", goal: str | None = None) -> dict | None:
        current, semantic, _ = self._current_parts()
        if not current:
            return None
        if mode == "full":
            return current
        if mode != "compact":
            raise ValueError("mode must be compact or full")

        raw_debug = current.get("raw") or {}
        page_debug = raw_debug.get("page_debug") if isinstance(raw_debug, dict) else None
        funnel = page_debug.get("funnel", {}) if isinstance(page_debug, dict) else {}

        source_names = ["CYCLONE_ACCESSIBILITY"]
        if _raw_accessibility_nodes(semantic):
            source_names.append("CYCLONE_ACCESSIBILITY_RAW")
        if current.get("uiautomator"):
            source_names.append("UIAUTOMATOR")

        page_key = semantic.get("pageKey") or current.get("page_key")
        package_name = semantic.get("package") or current.get("package")
        activity = semantic.get("activity") or current.get("activity")
        title = semantic.get("pageTitle")
        screen = semantic.get("display") or {}

        return {
            "id": current["id"],
            "observationId": semantic.get("observationId") or current["id"],
            "pageKey": page_key,
            "page_key": page_key,
            "package": package_name,
            "activity": activity,
            "title": title,
            "screen": screen,
            "screenshot": current.get("screenshot"),
            "controls": self.get_controls(goal, 36),
            "counts": {
                "raw": semantic.get("rawNodeCount"),
                "semantic": semantic.get("controlCount", len(_semantic_controls(semantic))),
                "agent": funnel.get("agentPayloadControlCount") if isinstance(funnel, dict) else None,
            },
            "sources": source_names,
            "provenance": {
                "canonical": "CYCLONE_ACCESSIBILITY",
                "independentWitness": "UIAUTOMATOR" if current.get("uiautomator") else None,
                "merged": False,
            },
        }

    def compare_sources(self, query: str) -> dict:
        current, semantic, observation_id = self._current_parts()
        if not current:
            return {"query": query, "sources": {}}
        normalized_query = query.lower().strip()

        cyclone = [
            _normalize_semantic(control)
            for control in _semantic_controls(semantic)
            if normalized_query in _text(control)
        ]
        raw = [
            _normalize_raw(node, observation_id)
            for node in _raw_accessibility_nodes(semantic)
            if normalized_query in _text(node)
        ]
        uia = [
            dict(node, source="UIAUTOMATOR")
            for node in (current.get("uiautomator") or {}).get("nodes", [])
            if isinstance(node, dict) and normalized_query in _text(node)
        ]
        return {
            "query": query,
            "sources": {
                "CYCLONE_ACCESSIBILITY": cyclone,
                "CYCLONE_ACCESSIBILITY_RAW": raw,
                "UIAUTOMATOR": uia,
            },
            "merged": False,
        }
