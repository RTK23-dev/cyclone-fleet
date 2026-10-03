from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .compact import compact_observation
from .gateway import GatewayClient
from .protocol import classify_failure


@dataclass
class AcceptanceReport:
    device: Any = None
    runs: list[dict[str, Any]] = field(default_factory=list)
    passed: bool = False


class AcceptanceHarness:
    def __init__(self, gateway: Any):
        self.gateway = gateway

    def run(self, execute: bool = False) -> AcceptanceReport:
        report = AcceptanceReport()
        report.device = self.gateway.status()
        first = self._settings_apps_run(execute=execute, label="first")
        second = self._settings_apps_run(execute=execute, label="repeat")
        report.runs = [first, second]
        report.passed = bool(first.get("passed") and second.get("passed"))
        return report

    def _settings_apps_run(self, execute: bool, label: str) -> dict[str, Any]:
        started = time.perf_counter()
        metrics = {
            "label": label,
            "actions": 0,
            "failedActions": 0,
            "uiSearches": 0,
            "screenshots": 0,
            "knownRouteHints": 0,
            "brainHints": 0,
            "pages": [],
        }
        obs = compact_observation(self.gateway.observe())
        self._observe_metrics(metrics, obs)
        if not execute:
            metrics.update({
                "passed": True,
                "dryRun": True,
                "latencyMs": int((time.perf_counter() - started) * 1000),
            })
            return metrics

        if not self._act(metrics, "phone.open_app", {"package": "com.android.settings"}, "Open Android Settings"):
            return self._failed(metrics, started, "Opening Android Settings failed")

        obs = compact_observation(self.gateway.observe())
        self._observe_metrics(metrics, obs)
        candidate = _find_control(obs, "apps")
        if candidate is None:
            metrics["uiSearches"] += 1
            candidate = _first_candidate(self.gateway.ui_search("Apps"))
        if candidate is None:
            metrics["failedActions"] += 1
            metrics["debug"] = self.gateway.debug_bundle(
                "Apps",
                "Open Android Settings and navigate to Apps",
            )
            return self._failed(metrics, started, "Apps target was not found")

        if not self._act(
            metrics,
            "phone.click",
            {"selector": _selector_for(candidate)},
            "Open Apps in Android Settings",
        ):
            metrics["debug"] = self.gateway.debug_bundle(
                "Apps",
                "Click the Apps control in Android Settings",
            )
            return self._failed(metrics, started, "Apps action returned failure")

        obs = compact_observation(self.gateway.observe())
        self._observe_metrics(metrics, obs)
        apps_ok = (
            "app" in str(obs.get("title") or "").lower()
            or "app" in str(obs.get("pageKey") or "").lower()
        )

        if not self._act(metrics, "phone.home", {}, "Return Home"):
            return self._failed(metrics, started, "Home action returned failure")

        home = compact_observation(self.gateway.observe())
        self._observe_metrics(metrics, home)
        metrics.update({
            "passed": bool(apps_ok),
            "latencyMs": int((time.perf_counter() - started) * 1000),
        })
        if not apps_ok:
            metrics["failureReason"] = "Apps page verification did not match title/PageKey"
        return metrics

    def _act(self, metrics: dict[str, Any], tool: str, params: dict[str, Any], goal: str) -> bool:
        metrics["actions"] += 1
        try:
            result = self.gateway.action(tool, params, goal)
        except Exception as exc:
            metrics["failedActions"] += 1
            metrics["lastActionFailure"] = {"exception": str(exc), "tool": tool}
            return False
        if _action_failed(result):
            metrics["failedActions"] += 1
            metrics["lastActionFailure"] = {
                "tool": tool,
                "result": result,
            }
            return False
        return True

    @staticmethod
    def _failed(metrics: dict[str, Any], started: float, reason: str) -> dict[str, Any]:
        metrics.update({
            "passed": False,
            "failureReason": reason,
            "latencyMs": int((time.perf_counter() - started) * 1000),
        })
        return metrics

    @staticmethod
    def _observe_metrics(metrics: dict[str, Any], obs: dict[str, Any]) -> None:
        page = obs.get("pageKey")
        if page:
            metrics["pages"].append(page)
        if obs.get("knownRouteHints"):
            metrics["knownRouteHints"] += 1
        if obs.get("brainRecall"):
            metrics["brainHints"] += 1


class MockGateway:
    def __init__(self):
        fixture_dir = Path(__file__).resolve().parent.parent / "tests" / "fixtures"
        self.fixtures = {
            name: json.loads((fixture_dir / f"{name}.json").read_text(encoding="utf-8"))
            for name in ("launcher", "settings", "settings_apps")
        }
        self.state = "launcher"
        self.visits = 0

    def status(self) -> Any:
        return {
            "device": {"model": "Pixel 8", "serial": "MOCKPIXEL8"},
            "adbReady": True,
            "bridgeConnected": True,
            "accessibilityReady": True,
            "gatewayEnabled": True,
        }

    def observe(self, **_: Any) -> Any:
        value = json.loads(json.dumps(self.fixtures[self.state]))
        if self.visits > 0:
            value.setdefault("knownRoutes", ["HOME → Settings → Apps"])
            value.setdefault("brainRecall", "Verified Settings > Apps route from prior run")
        return value

    def ui_search(self, query: str) -> Any:
        if query.lower() == "apps" and self.state == "settings":
            return {"candidates": [{"id": "apps", "label": "Apps", "resourceId": "android:id/title"}]}
        return {"candidates": []}

    def action(self, tool: str, params: dict[str, Any], goal: str) -> Any:
        if tool == "phone.open_app":
            self.state = "settings"
        elif tool == "phone.click" and self.state == "settings":
            self.state = "settings_apps"
        elif tool == "phone.home":
            self.state = "launcher"
            self.visits += 1
        return {"ok": True, "tool": tool, "goal": goal}

    def debug_bundle(self, expected: str, goal: str) -> Any:
        return {"stage": "AGENT_CONTEXT_TRUNCATION", "expected": expected, "goal": goal}


def _action_failed(result: Any) -> bool:
    return classify_failure(result) is not None


def _find_control(obs: dict[str, Any], needle: str) -> dict[str, Any] | None:
    needle = needle.lower()
    for control in obs.get("controls") or []:
        if needle in str(control.get("label") or "").lower():
            return control
    return None


def _first_candidate(search: Any) -> dict[str, Any] | None:
    if isinstance(search, dict):
        items = search.get("candidates") or search.get("results") or []
        if isinstance(items, list) and items and isinstance(items[0], dict):
            return items[0]
    return None


def _selector_for(candidate: dict[str, Any]) -> dict[str, Any]:
    selector = candidate.get("selector") if isinstance(candidate.get("selector"), dict) else {}
    if candidate.get("resourceId"):
        selector.setdefault("resourceId", candidate["resourceId"])
    if candidate.get("label"):
        selector.setdefault("text", candidate["label"])
    if candidate.get("id"):
        selector.setdefault("elementId", candidate["id"])
    return selector


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cyclone Pixel 8 Codex acceptance harness")
    parser.add_argument("--mock", action="store_true", help="Use bundled Pixel/Settings fixtures")
    parser.add_argument("--live", action="store_true", help="Use the real PC Device Gateway")
    parser.add_argument("--execute", action="store_true", help="Actually execute the harmless Settings > Apps > Home route")
    parser.add_argument("--report", default=".runtime/codex-phone/acceptance.json")
    args = parser.parse_args(argv)
    if args.live and args.mock:
        parser.error("choose --mock or --live, not both")
    gateway = GatewayClient() if args.live else MockGateway()
    report = AcceptanceHarness(gateway).run(execute=args.execute or args.mock)
    path = Path(args.report)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report.__dict__, indent=2), encoding="utf-8")
    print(json.dumps(report.__dict__, indent=2))
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
