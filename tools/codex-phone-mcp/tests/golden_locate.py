"""Helpers for V4 slice 5 golden locate contract tests.

Fixtures are synthetic page cards. They are not Pixel 8 captures.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterator

from cyclone_phone_mcp.compact import compact_observation

AGENT_CONTEXT_TRUNCATION = "AGENT_CONTEXT_TRUNCATION"
TOP_HITS = 3
GOLDEN_DIR = Path(__file__).resolve().parent / "fixtures" / "golden"
REQUIRED_PAGE_FIELDS = (
    "package",
    "activity",
    "pageKey",
    "title",
    "pageText",
    "pageSummary",
    "controls",
    "counts",
)
SENSITIVE_SUBSTRINGS = (
    "password",
    "passwd",
    "passcode",
    "otp",
    "secret",
    "api_key",
    "apikey",
)


def golden_paths() -> list[Path]:
    paths = sorted(GOLDEN_DIR.glob("*.json"))
    return [path for path in paths if path.name != "README.md"]


def load_golden(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise AssertionError(f"{path.name} is not a JSON object")
    return payload


def iter_goldens() -> Iterator[tuple[Path, dict[str, Any]]]:
    for path in golden_paths():
        yield path, load_golden(path)


def observation(golden: dict[str, Any]) -> dict[str, Any]:
    data = golden.get("observation")
    if isinstance(data, dict):
        return data
    return golden


def compact_golden(golden: dict[str, Any], *, goal: str | None = None) -> dict[str, Any]:
    locate_goal = goal if goal is not None else str(golden.get("goal") or "")
    return compact_observation(golden, goal=locate_goal)


def truncation_failure(detail: str) -> AssertionError:
    return AssertionError(f"{AGENT_CONTEXT_TRUNCATION}: {detail}")


def assert_page_text_survived(card: dict[str, Any], source: dict[str, Any], *, page_name: str) -> None:
    expected_text = source.get("pageText")
    expected_summary = source.get("pageSummary")
    if not isinstance(expected_text, str) or not expected_text.strip():
        raise truncation_failure(f"{page_name} fixture pageText missing before compact")
    if not isinstance(expected_summary, str) or not expected_summary.strip():
        raise truncation_failure(f"{page_name} fixture pageSummary missing before compact")
    got_text = card.get("pageText")
    got_summary = card.get("pageSummary")
    if not isinstance(got_text, str) or not got_text.strip():
        raise truncation_failure(f"{page_name} compact silently dropped pageText")
    if not isinstance(got_summary, str) or not got_summary.strip():
        raise truncation_failure(f"{page_name} compact silently dropped pageSummary")
    if expected_text.strip()[:40] not in got_text:
        raise truncation_failure(f"{page_name} compact truncated pageText before useful content")
    if expected_summary.strip()[:24] not in got_summary:
        raise truncation_failure(f"{page_name} compact truncated pageSummary before useful content")


def _searchable_hit(hit: dict[str, Any]) -> str:
    return " ".join(
        str(hit.get(key) or "")
        for key in ("elementId", "label", "resourceId", "role", "contentDescription")
    ).lower()


def hit_matches_expected(hit: dict[str, Any], expected: dict[str, Any]) -> bool:
    if not isinstance(hit, dict) or not isinstance(expected, dict):
        return False
    expected_id = str(expected.get("elementId") or expected.get("id") or "").strip()
    if expected_id and str(hit.get("elementId") or "") == expected_id:
        return True
    searchable = _searchable_hit(hit)
    for key in ("label", "resourceId", "contentDescription"):
        value = expected.get(key)
        if isinstance(value, str) and value.strip() and value.strip().lower() in searchable:
            return True
    return False


def locate_rank(hits: list[Any], expected: dict[str, Any]) -> int | None:
    for index, hit in enumerate(hits):
        if isinstance(hit, dict) and hit_matches_expected(hit, expected):
            return index
    return None


def assert_no_plaintext_secrets(payload: Any, *, page_name: str) -> None:
    if isinstance(payload, dict):
        for key, value in payload.items():
            lowered = str(key).lower().replace("-", "_")
            if any(part in lowered for part in SENSITIVE_SUBSTRINGS):
                if value not in (None, "", "<redacted>"):
                    raise AssertionError(
                        f"{page_name} must not store plaintext secret key {key!r}"
                    )
            assert_no_plaintext_secrets(value, page_name=page_name)
    elif isinstance(payload, list):
        for item in payload:
            assert_no_plaintext_secrets(item, page_name=page_name)
    elif isinstance(payload, str):
        lowered = payload.lower()
        if "password" in lowered and "no plaintext" not in lowered:
            # Allow the honest fixture note; reject leftover credential copy.
            if any(token in lowered for token in ("hunter2", "p@ss", "123456", "secret123")):
                raise AssertionError(f"{page_name} looks like it embeds a password")


def assert_no_raw_tree(source: dict[str, Any], *, page_name: str) -> None:
    if "nodes" in source or "rawTree" in source or "accessibilityTree" in source:
        raise AssertionError(f"{page_name} must not ship a raw accessibility tree")
    controls = source.get("controls")
    if isinstance(controls, list) and len(controls) > 40:
        raise AssertionError(f"{page_name} looks like a raw dump ({len(controls)} controls)")
    counts = source.get("counts") if isinstance(source.get("counts"), dict) else {}
    raw = counts.get("raw")
    if isinstance(raw, int) and raw >= 2500:
        raise AssertionError(f"{page_name} claims a 2500-node raw tree")
