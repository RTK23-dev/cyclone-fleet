from __future__ import annotations

from typing import Any


def _install() -> None:
    """Keep cyclone-page-text-v1 / cyclone-page-summary-v1 objects on Page Cards."""
    from . import compact as compact_mod

    orig = compact_mod.compact_observation
    if getattr(orig, "_cyclone_v1_page_wrap", False):
        return

    def compact_observation(payload: Any, control_limit: int = compact_mod.PAGE_CARD_CANDIDATE_LIMIT, *, goal: str = "") -> dict[str, Any]:
        card = orig(payload, control_limit, goal=goal)
        if not isinstance(card, dict):
            return card
        envelope, data = compact_mod._unwrap_observation(payload)
        page = data.get("page") if isinstance(data.get("page"), dict) else {}
        raw_text = compact_mod._first(
            page, "pageText", "page_text", "cyclone-page-text-v1",
            default=compact_mod._first(data, "pageText", "page_text", "cyclone-page-text-v1"),
        )
        raw_summary = compact_mod._first(
            page, "pageSummary", "page_summary", "cyclone-page-summary-v1",
            default=compact_mod._first(data, "pageSummary", "page_summary", "cyclone-page-summary-v1"),
        )
        page_text = _page_context_text(compact_mod, raw_text)
        page_summary = _page_context_summary(compact_mod, raw_summary)
        if page_text:
            card["pageText"] = page_text
        if page_summary:
            card["pageSummary"] = page_summary
        return card

    compact_observation._cyclone_v1_page_wrap = True  # type: ignore[attr-defined]
    compact_mod.compact_observation = compact_observation


def _page_text_from_v1(compact_mod: Any, value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    protocol = str(value.get("protocol") or "")
    lines = value.get("lines")
    if protocol not in {"", "cyclone-page-text-v1"} and not isinstance(lines, list):
        return None
    if not isinstance(lines, list):
        return compact_mod._bounded_text(compact_mod._first(value, "text", "content", "value"), compact_mod.PAGE_CARD_TEXT_LIMIT)
    parts: list[str] = []
    for item in lines[:80]:
        piece = compact_mod._bounded_text(item.get("text") if isinstance(item, dict) else item, compact_mod.PAGE_CARD_TEXT_LIMIT)
        if piece:
            parts.append(piece)
    return compact_mod._bounded_text(parts, compact_mod.PAGE_CARD_TEXT_LIMIT)


def _page_summary_from_v1(compact_mod: Any, value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    protocol = str(value.get("protocol") or "")
    if protocol not in {"", "cyclone-page-summary-v1"} and "contentNote" not in value and "title" not in value:
        return None
    parts: list[str] = []
    for key in ("title", "contentNote"):
        piece = compact_mod._bounded_text(value.get(key), compact_mod.PAGE_CARD_SUMMARY_LIMIT)
        if piece:
            parts.append(piece)
    for key in ("headings", "buttons", "tabs"):
        items = value.get(key)
        if isinstance(items, list):
            parts.extend(str(item) for item in items[:8] if item not in (None, ""))
    return compact_mod._bounded_text(parts, compact_mod.PAGE_CARD_SUMMARY_LIMIT)


def _page_context_text(compact_mod: Any, value: Any) -> str | None:
    if isinstance(value, dict):
        flattened = _page_text_from_v1(compact_mod, value)
        if flattened:
            return flattened[: compact_mod.PAGE_CARD_TEXT_LIMIT]
        return compact_mod._bounded_text(compact_mod._first(value, "text", "content", "value"), compact_mod.PAGE_CARD_TEXT_LIMIT)
    return compact_mod._bounded_text(value, compact_mod.PAGE_CARD_TEXT_LIMIT)


def _page_context_summary(compact_mod: Any, value: Any) -> str | None:
    if isinstance(value, dict):
        flattened = _page_summary_from_v1(compact_mod, value)
        if flattened:
            return flattened[: compact_mod.PAGE_CARD_SUMMARY_LIMIT]
        return compact_mod._bounded_text(compact_mod._first(value, "text", "summary", "title", "contentNote"), compact_mod.PAGE_CARD_SUMMARY_LIMIT)
    return compact_mod._bounded_text(value, compact_mod.PAGE_CARD_SUMMARY_LIMIT)


_install()
