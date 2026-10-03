from __future__ import annotations

import re
from typing import Any

SENSITIVE_KEYS = {
    "password", "passwd", "passcode", "otp", "token", "api_key", "apikey", "secret",
    "authorization", "cookie", "typed_value", "text_value", "input_value",
}

PAGE_CARD_CANDIDATE_LIMIT = 12
SNAPSHOT_HOST_LIMIT = 80
PAGE_CARD_TEXT_LIMIT = 900
PAGE_CARD_SUMMARY_LIMIT = 500
SEARCH_RESULT_LIMIT = 10
_GOAL_STOP_WORDS = frozenset({
    "a", "an", "and", "app", "button", "click", "for", "go", "in", "into", "me", "my",
    "of", "on", "open", "page", "screen", "tap", "the", "this", "to", "with",
})
_TYPE_GOAL_HINTS = frozenset({
    "composer", "edit", "editable", "edittext", "enter", "fill", "input", "task",
    "text", "textbox", "type", "write",
})


def _is_sensitive_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_")
    return normalized in SENSITIVE_KEYS or any(part in normalized for part in ("password", "secret", "api_key", "otp"))


def redact(value: Any, parent_key: str = "") -> Any:
    if _is_sensitive_key(parent_key):
        return "<redacted>"
    if isinstance(value, dict):
        return {k: redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, parent_key) for v in value]
    return value


def _first(mapping: dict[str, Any], *keys: str, default: Any = None) -> Any:
    for key in keys:
        if key in mapping and mapping[key] not in (None, ""):
            return mapping[key]
    return default


def _bounded_text(value: Any, limit: int) -> str | None:
    if isinstance(value, str):
        text = re.sub(r"\s+", " ", value).strip()
    elif isinstance(value, (int, float, bool)):
        text = str(value)
    elif isinstance(value, list):
        parts = [_bounded_text(item, limit) for item in value[:20]]
        text = " ".join(part for part in parts if part)
    else:
        return None
    if not text:
        return None
    return text[:limit]


def _bounded_scalar(value: Any, limit: int = 160) -> str | None:
    return _bounded_text(value, limit)


def _bounded_index(value: Any) -> int | None:
    if isinstance(value, bool) or value in (None, ""):
        return None
    try:
        index = int(value)
    except (TypeError, ValueError):
        return None
    return index if index >= 1 else None


def _tokens(value: str) -> list[str]:
    return [
        token for token in re.findall(r"[a-z0-9]+", value.lower())
        if token not in _GOAL_STOP_WORDS
    ][:16]


def _candidate(item: Any) -> dict[str, Any] | None:
    if not isinstance(item, dict):
        return None
    candidate = {
        "elementId": _bounded_scalar(_first(item, "elementId", "element_id", "id"), 240),
        "elementIndex": _bounded_index(_first(item, "elementIndex", "element_index")),
        "label": _bounded_scalar(_first(item, "label", "semanticName", "text", "contentDescription"), 180),
        "role": _bounded_scalar(_first(item, "role", "className", "class"), 80),
        "resourceId": _bounded_scalar(_first(item, "resourceId", "resource_id"), 180),
        "bounds": _bounded_bounds(item.get("bounds")),
        "actions": _bounded_actions(_first(item, "androidActions", "actions", default=[])),
        "clickable": item.get("clickable") if isinstance(item.get("clickable"), bool) else None,
        "editable": item.get("editable") if isinstance(item.get("editable"), bool) else None,
        "focused": item.get("focused") if isinstance(item.get("focused"), bool) else None,
        "selected": item.get("selected") if isinstance(item.get("selected"), bool) else None,
        "checked": item.get("checked") if isinstance(item.get("checked"), bool) else None,
        "scrollable": item.get("scrollable") if isinstance(item.get("scrollable"), bool) else None,
    }
    if isinstance(item.get("risk"), str):
        candidate["risk"] = item["risk"][:48]
    return {key: value for key, value in candidate.items() if value not in (None, "", [], {})}


def _bounded_actions(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(action)[:48] for action in value[:6] if isinstance(action, (str, int))]


def _bounded_bounds(value: Any) -> dict[str, int] | list[int] | None:
    if isinstance(value, dict):
        result = {
            key: int(item) for key, item in value.items()
            if key in {"left", "top", "right", "bottom"} and isinstance(item, (int, float))
        }
        return result or None
    if isinstance(value, list) and len(value) == 4 and all(isinstance(item, (int, float)) for item in value):
        return [int(item) for item in value]
    return None


def _source_controls(data: dict[str, Any], page: dict[str, Any]) -> list[Any]:
    controls = page.get("controls") or data.get("controls") or data.get("semanticControls") or []
    return controls if isinstance(controls, list) else []


def _is_password_candidate(candidate: dict[str, Any]) -> bool:
    searchable = " ".join(
        str(candidate.get(key) or "") for key in ("label", "resourceId", "role")
    ).lower()
    return any(token in searchable for token in ("password", "passwd", "passcode"))


def _select_current_candidates(candidates: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    """Keep editables/focused hosts in the bounded Page Card, then clickable hosts."""
    limit = max(1, min(limit, PAGE_CARD_CANDIDATE_LIMIT))
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()

    def add(item: dict[str, Any]) -> None:
        if len(selected) >= limit:
            return
        element_id = str(item.get("elementId") or "")
        key = element_id or f"anon:{id(item)}"
        if key in seen:
            return
        seen.add(key)
        selected.append(item)

    for item in candidates:
        if item.get("editable") is True and item.get("focused") is True and not _is_password_candidate(item):
            add(item)
    for item in candidates:
        if item.get("editable") is True and not _is_password_candidate(item):
            add(item)
    for item in candidates:
        if item.get("focused") is True and not _is_password_candidate(item):
            add(item)
    for item in candidates:
        if item.get("clickable") is True:
            add(item)
    for item in candidates:
        add(item)
        if len(selected) >= limit:
            break
    return selected


def _rank_candidates(candidates: list[dict[str, Any]], goal: str, limit: int) -> list[dict[str, Any]]:
    goal_tokens = _tokens(goal)
    if not goal_tokens:
        return []
    ranked: list[tuple[int, int, dict[str, Any], list[str]]] = []
    phrase = goal.lower().strip()
    type_goal = any(hint in phrase or hint in goal_tokens for hint in _TYPE_GOAL_HINTS)
    for index, candidate in enumerate(candidates):
        searchable = " ".join(
            str(candidate.get(key) or "") for key in ("label", "resourceId", "role")
        ).lower()
        matches = [token for token in goal_tokens if token in searchable]
        score = len(matches) * 10
        if phrase and phrase in searchable:
            score += 20
        if type_goal and candidate.get("editable") is True:
            score += 25
        if type_goal and candidate.get("focused") is True:
            score += 8
        if candidate.get("clickable") is True:
            score += 1
        if score:
            ranked.append((score, -index, candidate, matches[:4]))
    ranked.sort(reverse=True, key=lambda entry: (entry[0], entry[1]))
    result: list[dict[str, Any]] = []
    for score, _, candidate, matches in ranked[:limit]:
        item = dict(candidate)
        item["goalMatch"] = matches
        item["goalScore"] = score
        result.append(item)
    return result


def _unwrap_observation(payload: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(payload, dict):
        return {}, {}
    if isinstance(payload.get("observation"), dict):
        return payload, payload["observation"]
    result = payload.get("result")
    if isinstance(result, dict):
        return payload, result
    return payload, payload


def _observation_id(payload: dict[str, Any], data: dict[str, Any], page: dict[str, Any]) -> str | None:
    witness = payload.get("witness") if isinstance(payload.get("witness"), dict) else {}
    return _bounded_scalar(_first(
        witness,
        "observation_id",
        "observationId",
        default=_first(data, "observationId", "observation_id", default=_first(page, "observationId", "observation_id")),
    ), 240)


def _location(page: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
    source_location = _first(page, "location", default=_first(data, "location"))
    if isinstance(source_location, dict):
        location_value: Any = {
            key: _bounded_scalar(source_location.get(key), 160)
            for key in ("name", "path", "route", "url")
            if _bounded_scalar(source_location.get(key), 160)
        }
    else:
        location_value = _bounded_scalar(source_location, 240)
    return {
        "location": location_value,
        "package": _bounded_scalar(_first(page, "package", "packageName", default=_first(data, "package", "packageName")), 180),
        "activity": _bounded_scalar(_first(page, "activity", default=data.get("activity")), 180),
        "title": _bounded_scalar(_first(page, "title", default=data.get("title")), 200),
        "pageKey": _bounded_scalar(_first(page, "pageKey", "page_key", default=_first(data, "pageKey", "page_key")), 200),
    }


def _counts(data: dict[str, Any], controls: list[Any]) -> dict[str, Any]:
    counts = data.get("counts") if isinstance(data.get("counts"), dict) else {}
    return {
        "raw": _first(counts, "raw", "rawNodeCount", default=_first(data, "rawNodeCount", "raw_count")),
        "semantic": _first(counts, "semantic", "semanticControlCount", default=_first(data, "semanticControlCount", "semantic_count", default=len(controls))),
        "agent": _first(counts, "agent", "agentControlCount", default=_first(data, "agentControlCount", "agent_count")),
    }



def _is_actionable_host(candidate: dict[str, Any]) -> bool:
    role = str(candidate.get("role") or "").lower()
    return (
        candidate.get("clickable") is True
        or candidate.get("editable") is True
        or candidate.get("checkable") is True
        or role in {"button", "tab", "row", "textbox", "switch", "checkbox", "edit_text"}
    )


def build_snapshot(candidates: list[dict[str, Any]], limit: int = SNAPSHOT_HOST_LIMIT) -> tuple[str, dict[str, dict[str, Any]]]:
    """Playwright-MCP style accessibility snapshot of actionable hosts."""
    hosts: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in candidates:
        if not _is_actionable_host(item):
            continue
        key = str(item.get("elementId") or id(item))
        if key in seen:
            continue
        seen.add(key)
        hosts.append(item)
        if len(hosts) >= max(1, min(limit, SNAPSHOT_HOST_LIMIT)):
            break
    lines: list[str] = []
    refs: dict[str, dict[str, Any]] = {}
    for index, host in enumerate(hosts, start=1):
        ref = f"e{index}"
        name = str(host.get("label") or host.get("resourceId") or "").replace('"', "'")
        role = str(host.get("role") or "generic")
        selected = " [selected]" if host.get("selected") is True else ""
        lines.append(f'- {role} "{name}" [ref={ref}]{selected}')
        refs[ref] = {
            "elementId": host.get("elementId"),
            "elementIndex": host.get("elementIndex"),
            "role": role,
            "name": name,
            "selected": host.get("selected") is True,
            "clickable": host.get("clickable") is True,
            "editable": host.get("editable") is True,
        }
    return "\n".join(lines), refs


def compact_observation(payload: Any, control_limit: int = PAGE_CARD_CANDIDATE_LIMIT, *, goal: str = "") -> dict[str, Any]:
    """Create a bounded, action-safe Page Card from flexible gateway observations.

    The card intentionally exposes candidates rather than a raw accessibility tree. Candidate IDs
    are scoped to this observation and are invalid after any mutating action.
    """
    envelope, data = _unwrap_observation(payload)
    if not data:
        return {"kind": "page_card", "raw": redact(payload)}
    page = data.get("page") if isinstance(data.get("page"), dict) else {}
    controls = _source_controls(data, page)
    candidates = [candidate for item in controls if (candidate := _candidate(item))]
    current = _select_current_candidates(candidates, max(1, min(control_limit, PAGE_CARD_CANDIDATE_LIMIT)))
    location = _location(page, data)
    # Android V3.5 exposes page-scoped, verified route evidence as ``nextHopHints``. Retain
    # it alongside older gateway aliases so the PC agent sees the same bounded guidance on both
    # legacy and device-scoped paths.
    routes = data.get("nextHopHints") or data.get("knownRoutes") or data.get("known_routes") or data.get("routeHints") or []
    if not isinstance(routes, list):
        routes = [routes] if routes else []
    brain = _bounded_text(data.get("brainRecall") or data.get("brain_recall") or data.get("brainSummary"), 360)
    # Device-scoped observations keep the bounded artifact on the outer operation envelope.
    # Prefer page-local metadata, then preserve that envelope artifact instead of dropping it.
    screenshot = (
        data.get("screenshot") or data.get("screenshotRef") or data.get("screenshot_ref")
        or envelope.get("screenshot") or envelope.get("screenshotRef") or envelope.get("screenshot_ref")
    )
    witness = envelope.get("witness") if isinstance(envelope.get("witness"), dict) else {}
    page_text = _bounded_text(_first(page, "pageText", "page_text", default=_first(data, "pageText", "page_text")), PAGE_CARD_TEXT_LIMIT)
    page_summary = _bounded_text(_first(page, "pageSummary", "page_summary", default=_first(data, "pageSummary", "page_summary")), PAGE_CARD_SUMMARY_LIMIT)
    observation_id = _observation_id(envelope, data, page)

    session_id = _bounded_scalar(
        _first(envelope, "sessionId", "session_id", default=_first(data, "sessionId", "session_id")),
        120,
    )
    display_id = _first(envelope, "displayId", "display_id", default=_first(data, "displayId", "display_id"))
    if isinstance(display_id, bool) or not isinstance(display_id, (int, float)) or float(display_id) != int(display_id):
        display_id = None
    elif display_id is not None:
        display_id = int(display_id)
    card = {
        "kind": "page_card",
        "correlationId": _bounded_scalar(_first(envelope, "correlation_id", "correlationId"), 120),
        "observationScope": {
            "id": observation_id,
            "validUntil": "the next mutating action",
            "elementIdRule": "Use only elementId or elementIndex values from this current Page Card or search result; re-observe after every mutation.",
        },
        "location": {key: value for key, value in location.items() if value not in (None, "", {})},
        "pageText": page_text,
        "pageSummary": page_summary,
        "counts": _counts(data, controls),
        "candidates": {
            "current": current,
            "goalRanked": _rank_candidates(candidates, goal, PAGE_CARD_CANDIDATE_LIMIT),
        },
        "knownRouteHints": redact(routes[:4]),
        "brainRecall": redact(brain),
        "screenshot": redact(screenshot),
        "truncated": {
            "rawTreeExcluded": True,
            "sourceCandidateCount": len(candidates),
            "currentCandidateLimit": PAGE_CARD_CANDIDATE_LIMIT,
            "snapshotHostLimit": SNAPSHOT_HOST_LIMIT,
        },
        "perceptionMode": _bounded_scalar(_first(data, "perceptionMode", "perception_mode", default="a11y"), 40) or "a11y",
        "treeUseful": bool(data.get("treeUseful", data.get("tree_useful", len(current) > 0))),
    }
    if session_id:
        card["sessionId"] = session_id
        card["observationScope"]["sessionId"] = session_id
    if display_id is not None:
        card["displayId"] = display_id
        card["observationScope"]["displayId"] = display_id
    # Stable aliases retain compatibility for existing MCP clients while directing new callers to
    # Page Card fields above.
    snapshot_text, ref_map = build_snapshot(candidates)
    card["snapshot"] = snapshot_text
    card["refs"] = ref_map
    card.update({
        "witness": redact(witness),
        "package": location.get("package"),
        "activity": location.get("activity"),
        "pageKey": location.get("pageKey"),
        "title": location.get("title"),
        "controls": current,
    })
    return redact(card)


def compact_search(payload: Any, *, query: str, goal: str = "", limit: int = SEARCH_RESULT_LIMIT) -> dict[str, Any]:
    """Bound semantic search output and keep its IDs visibly observation-scoped."""
    if not isinstance(payload, dict):
        return {"kind": "semantic_search", "query": _bounded_text(query, 240), "raw": redact(payload)}
    source: Any = payload.get("results") or payload.get("candidates") or payload.get("controls") or payload.get("hits") or []
    if isinstance(source, dict):
        source = [source]
    if not isinstance(source, list):
        source = []
    candidates = [candidate for item in source if (candidate := _candidate(item))]
    ranked = _rank_candidates(candidates, goal or query, limit)
    results = ranked or candidates[:limit]
    witness = payload.get("witness") if isinstance(payload.get("witness"), dict) else {}
    return redact({
        "kind": "semantic_search",
        "query": _bounded_text(query, 240),
        "goal": _bounded_text(goal, 240),
        "observationScope": {
            "id": _bounded_scalar(_first(witness, "observation_id", "observationId", default=_first(payload, "observationId", "observation_id")), 240),
            "elementIdRule": "IDs are current-observation scoped. Inspect or act now; never reuse after a mutation.",
        },
        "results": results,
        "truncated": {"rawTreeExcluded": True, "sourceResultCount": len(candidates), "resultLimit": limit},
    })


def compact_element(payload: Any, *, element_id: str) -> dict[str, Any]:
    """Return one inspected element without exposing its raw backing tree."""
    if not isinstance(payload, dict):
        return {"kind": "element_card", "elementId": element_id, "raw": redact(payload)}
    element = _candidate(payload) or {}
    element["elementId"] = element.get("elementId") or element_id
    return redact({
        "kind": "element_card",
        "element": element,
        "elementId": element["elementId"],
        "elementIdRule": "This ID is scoped to the current observation. Re-observe after every mutation.",
    })


def page_changed(before: dict[str, Any] | None, after: dict[str, Any] | None) -> bool | None:
    if not isinstance(before, dict) or not isinstance(after, dict):
        return None
    before_location = before.get("location") if isinstance(before.get("location"), dict) else {}
    after_location = after.get("location") if isinstance(after.get("location"), dict) else {}
    keys = ("package", "activity", "pageKey", "title", "location")
    return any(before_location.get(key) != after_location.get(key) for key in keys)


def page_delta(before: dict[str, Any] | None, after: dict[str, Any] | None, changed: bool | None) -> str:
    if after is None:
        return "After-state observation was unavailable."
    if before is None:
        return "No prior Page Card was available."
    before_location = before.get("location") if isinstance(before.get("location"), dict) else {}
    after_location = after.get("location") if isinstance(after.get("location"), dict) else {}
    if changed:
        old = before_location.get("title") or before_location.get("pageKey") or "previous page"
        new = after_location.get("title") or after_location.get("pageKey") or "current page"
        return f"Page changed: {old} → {new}."
    before_ids = {item.get("elementId") for item in before.get("candidates", {}).get("current", []) if isinstance(item, dict)}
    after_ids = {item.get("elementId") for item in after.get("candidates", {}).get("current", []) if isinstance(item, dict)}
    if before_ids != after_ids:
        return "Page location is unchanged; current candidates changed."
    return "No location or current-candidate change was observed."
