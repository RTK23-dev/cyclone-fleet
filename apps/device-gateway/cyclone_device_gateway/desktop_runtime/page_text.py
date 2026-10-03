from __future__ import annotations

from typing import Any

from .models import DesktopRuntimeError, RuntimeErrorCode

PAGE_TEXT_CHAR_LIMIT = 900
PAGE_SUMMARY_CHAR_LIMIT = 500
_REDACTED = "<redacted>"

# Agent D contract (tools/codex-phone-mcp compact.py): `_bounded_text` returns None for dicts, so
# real cyclone-page-text-v1 / cyclone-page-summary-v1 objects were silently dropped to pageText=null
# on phone.observe / phone_locate. Compact Desktop payloads MUST emit non-empty strings at pageText
# and pageSummary. Keep flattening lines[].text and contentNote if Agent D restores object handling.


def _bounded_plain(value: str, limit: int) -> str:
    text = " ".join(str(value).split())
    if text == _REDACTED:
        return ""
    return text[:limit]


def _control_labels(observation: dict[str, Any]) -> list[str]:
    labels: list[str] = []
    page_context = observation.get("pageContext")
    pools: list[Any] = [
        observation.get("semanticControls"),
        observation.get("supplementalControls"),
        page_context.get("controls") if isinstance(page_context, dict) else None,
    ]
    for pool in pools:
        if not isinstance(pool, list):
            continue
        for item in pool:
            if not isinstance(item, dict):
                continue
            label = str(item.get("label") or item.get("semanticName") or item.get("text") or "").strip()
            if label and label != _REDACTED and label not in labels:
                labels.append(label)
            if len(labels) >= 40:
                return labels
    return labels


def _line_texts(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    parts: list[str] = []
    for item in value[:160]:
        if isinstance(item, dict):
            text = str(item.get("text") or "").strip()
        else:
            text = str(item).strip() if item not in (None, "") else ""
        if text and text != _REDACTED:
            parts.append(text)
    return parts


def _flatten_from_controls(observation: dict[str, Any], limit: int) -> str:
    page_context = observation.get("pageContext") if isinstance(observation.get("pageContext"), dict) else {}
    title = str(observation.get("pageTitle") or observation.get("title") or page_context.get("title") or "").strip()
    labels = _control_labels(observation)
    parts = [title] if title and title != _REDACTED else []
    parts.extend(labels)
    return _bounded_plain(" ".join(parts), limit)


def _flatten_page_text(value: Any, observation: dict[str, Any]) -> str:
    if isinstance(value, str):
        plain = _bounded_plain(value, PAGE_TEXT_CHAR_LIMIT)
        return plain or _flatten_from_controls(observation, PAGE_TEXT_CHAR_LIMIT)
    if isinstance(value, dict):
        direct = value.get("text")
        if isinstance(direct, str) and direct.strip() and direct.strip() != _REDACTED:
            return _bounded_plain(direct, PAGE_TEXT_CHAR_LIMIT)
        joined = _bounded_plain(" ".join(_line_texts(value.get("lines"))), PAGE_TEXT_CHAR_LIMIT)
        if joined:
            return joined
    return _flatten_from_controls(observation, PAGE_TEXT_CHAR_LIMIT)


def _flatten_page_summary(value: Any, observation: dict[str, Any], page_text: str) -> str:
    if isinstance(value, str):
        plain = _bounded_plain(value, PAGE_SUMMARY_CHAR_LIMIT)
        return plain or _bounded_plain(page_text, PAGE_SUMMARY_CHAR_LIMIT)
    if isinstance(value, dict):
        parts: list[str] = []
        title = str(value.get("title") or observation.get("pageTitle") or "").strip()
        if title and title != _REDACTED:
            parts.append(title)
        for key in ("text", "contentNote"):
            extra = value.get(key)
            if isinstance(extra, str) and extra.strip() and extra.strip() != _REDACTED and extra.strip() != title:
                parts.append(extra.strip())
        for key in ("headings", "buttons", "tabs", "switches"):
            items = value.get(key)
            if not isinstance(items, list):
                continue
            labels = [str(item).strip() for item in items if str(item).strip() and str(item).strip() != _REDACTED]
            if labels:
                parts.append(f"{key}: {', '.join(labels[:8])}")
        joined = _bounded_plain(". ".join(parts), PAGE_SUMMARY_CHAR_LIMIT)
        if joined:
            return joined
    fallback = _flatten_from_controls(observation, PAGE_SUMMARY_CHAR_LIMIT)
    return fallback or _bounded_plain(page_text, PAGE_SUMMARY_CHAR_LIMIT)


def _require_compact_page_context(page_text: str, page_summary: str) -> None:
    if page_text and page_summary:
        return
    raise DesktopRuntimeError(
        RuntimeErrorCode.AGENT_CONTEXT_TRUNCATION,
        "Compact observation is missing pageText/pageSummary. Re-observe; do not continue with a silent empty page card.",
    )


def _compact_observation(observation: dict[str, Any]) -> dict[str, Any]:
    """Keep the bounded page card while excluding raw accessibility payloads.

    pageText/pageSummary become MCP-safe bounded strings. Structured cyclone-page-text-v1 and
    cyclone-page-summary-v1 objects are retained as pageTextCard/pageSummaryCard. Missing upstream
    context fails as AGENT_CONTEXT_TRUNCATION instead of silent nulls.
    """
    compact = dict(observation)
    compact.pop("rawAccessibility", None)
    compact.pop("raw_accessibility", None)
    compact.pop("rawTree", None)
    compact.pop("accessibilityTree", None)
    for key in ("semanticControls", "supplementalControls"):
        controls = compact.get(key)
        if isinstance(controls, list):
            compact[key] = controls[:40]
    page_context = compact.get("pageContext")
    if isinstance(page_context, dict):
        page_context = dict(page_context)
        controls = page_context.get("controls")
        if isinstance(controls, list):
            page_context["controls"] = controls[:40]
        compact["pageContext"] = page_context
    raw_page_text = compact.get("pageText")
    raw_page_summary = compact.get("pageSummary")
    page_text = _flatten_page_text(raw_page_text, compact)
    page_summary = _flatten_page_summary(raw_page_summary, compact, page_text)
    _require_compact_page_context(page_text, page_summary)
    if isinstance(raw_page_text, dict):
        compact["pageTextCard"] = raw_page_text
    if isinstance(raw_page_summary, dict):
        compact["pageSummaryCard"] = raw_page_summary
    compact["pageText"] = page_text
    compact["pageSummary"] = page_summary
    compact["compact"] = {
        "rawTreeExcluded": True,
        "controlLimit": 40,
        "pageTextPreserved": True,
        "pageSummaryPreserved": True,
        "pageTextProtocol": "cyclone-page-text-v1",
        "pageSummaryProtocol": "cyclone-page-summary-v1",
    }
    return compact
