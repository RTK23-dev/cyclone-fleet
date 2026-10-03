from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

from .compact import redact


class SessionRecorder:
    def __init__(self, report_dir: str | None = None, task: str = "Codex phone session"):
        self.session_id = str(uuid.uuid4())
        self.started_at = time.time()
        self.task = task
        self.events: list[dict[str, Any]] = []
        self.report_dir = Path(report_dir or os.getenv("CYCLONE_PHONE_REPORT_DIR", ".runtime/codex-phone/reports"))

    def record(self, tool: str, args: dict[str, Any], result: Any, ok: bool, elapsed_ms: int) -> None:
        safe_args = dict(args)
        if tool == "phone_act" and safe_args.get("tool") == "phone.type":
            safe_args["params"] = {"typed_value_redacted": True}
        self.events.append({
            "timestamp": time.time(),
            "tool": tool,
            "args": redact(safe_args),
            "ok": ok,
            "elapsedMs": elapsed_ms,
            "resultSummary": _summary(result),
        })
        self.write()

    def snapshot(self) -> dict[str, Any]:
        pages = []
        actions = 0
        failures = 0
        searches = 0
        screenshots = 0
        debug_bundles = 0
        known_routes = 0
        brain_hints = 0
        start_page = None
        end_page = None
        for event in self.events:
            tool = event["tool"]
            summary = event.get("resultSummary") or {}
            page_key = summary.get("pageKey") if isinstance(summary, dict) else None
            if page_key:
                pages.append(page_key)
                start_page = start_page or page_key
                end_page = page_key
            if tool == "phone_act":
                actions += 1
                if not event["ok"]:
                    failures += 1
            elif tool == "phone_ui_search":
                searches += 1
            elif tool == "phone_screenshot":
                screenshots += 1
            elif tool == "phone_debug_bundle":
                debug_bundles += 1
            if isinstance(summary, dict):
                known_routes += int(bool(summary.get("knownRouteHints")))
                brain_hints += int(bool(summary.get("brainRecall")))
        return {
            "sessionId": self.session_id,
            "task": self.task,
            "startedAt": self.started_at,
            "durationMs": int((time.time() - self.started_at) * 1000),
            "startPageKey": start_page,
            "endPageKey": end_page,
            "pagesVisited": pages,
            "actions": actions,
            "successfulActions": actions - failures,
            "failedActions": failures,
            "uiSearches": searches,
            "screenshotsUsed": screenshots,
            "debugBundlesUsed": debug_bundles,
            "knownRouteHintsObserved": known_routes,
            "brainHintsObserved": brain_hints,
            "events": self.events,
        }

    def write(self) -> Path:
        self.report_dir.mkdir(parents=True, exist_ok=True)
        path = self.report_dir / f"{self.session_id}.json"
        path.write_text(json.dumps(self.snapshot(), indent=2, ensure_ascii=False), encoding="utf-8")
        return path


def _summary(result: Any) -> Any:
    if not isinstance(result, dict):
        return redact(result)
    keys = (
        "pageKey", "title", "package", "ok", "stage", "failureClassification",
        "knownRouteHints", "brainRecall", "screenshot", "sessionId", "active",
        "correlation_id", "correlationId", "witness", "before", "after",
        "transport", "execution", "verification", "error",
    )
    summary = {key: result.get(key) for key in keys if key in result}
    if isinstance(result.get("gateway"), dict):
        # GatewayError is wrapped by PhoneTools; keep its safe typed witnesses and reason.
        summary["gateway"] = _summary(result["gateway"])
    if not summary and isinstance(result.get("result"), dict):
        return _summary(result["result"])
    return redact(summary or {"keys": sorted(result.keys())[:20]})
