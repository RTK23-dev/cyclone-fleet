"""Multi-phone command guard (alpha.95 port of the alpha.59 fleet patch).

The fleet layer sits ABOVE the Command Center: it turns one sentence into one Command Center task per phone and tracks
them as a mission. It must stay that thin:
- it holds no model key and makes no model call (each phone's own Mind does the thinking, with that phone's own key);
- it never answers an approval (approving stays a person's act, in Glass, through /v1/cc/approvals/{id}/answer);
- every /v1/fleet mission route needs the gateway bearer;
- agent MCP servers never start, cancel or stop missions (they must not reach this layer any more than /v1/cc/).
"""
from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime"
FLEET_FILES = ("fleet_command.py", "fleet_orchestrator.py", "fleet_api.py", "scenes.py")
MCP_DIRS = [ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp"]
MISSION_ROUTES = ("/v1/fleet/command", "/v1/fleet/dispatch", "/v1/fleet/missions", "/v1/fleet/stop-all", "/v1/fleet/scenes", "/v1/fleet/overview")


def code_lines(path: Path):
    """(line number, text) of real code: comments and docstrings may name what the code must never do."""
    source = path.read_text(encoding="utf-8")
    docstring_lines: set[int] = set()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                docstring_lines.update(range(first.lineno, (first.end_lineno or first.lineno) + 1))
    for number, line in enumerate(source.splitlines(), start=1):
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and number not in docstring_lines:
            yield number, line


class FleetGuard(unittest.TestCase):
    def test_the_fleet_layer_holds_no_model_key_and_makes_no_model_call(self):
        pattern = re.compile(r"openrouter|anthropic|openai|generativelanguage|chat/completions|api[_-]?key|x-api-key|\bllm\b", re.I)
        for name in FLEET_FILES:
            for number, line in code_lines(RUNTIME / name):
                self.assertIsNone(pattern.search(line), f"{name}:{number}: the PC never holds a model key or calls a model: {line.strip()}")

    def test_the_fleet_layer_never_answers_an_approval(self):
        pattern = re.compile(r"\.answer\(|cc_answer\(|answer_approval|@router\.(post|put|delete)\(.*approvals", re.I)
        for name in ("fleet_orchestrator.py", "fleet_api.py", "scenes.py", "fleet_command.py"):
            for number, line in code_lines(RUNTIME / name):
                if "cc_answer" in line and "stop" in line:
                    continue
                self.assertIsNone(pattern.search(line), f"{name}:{number}: approving stays a person's act in Glass: {line.strip()}")

    def test_the_orchestrator_only_cancels_through_the_command_center(self):
        text = (RUNTIME / "fleet_orchestrator.py").read_text(encoding="utf-8")
        self.assertIn("self._cc.cancel_task(", text)
        self.assertNotIn("_contract", text, "the orchestrator never talks to a phone's contract directly")

    def test_every_fleet_route_needs_the_bearer(self):
        api = (RUNTIME / "fleet_api.py").read_text(encoding="utf-8")
        routes = [line.strip() for line in api.splitlines() if re.match(r"\s*@router\.(get|post|put|delete)\(", line)]
        self.assertGreaterEqual(len(routes), 14, "fleet routes not found")
        open_routes = [line for line in routes if "dependencies=[Depends(auth)]" not in line]
        self.assertEqual(open_routes, [], f"unauthenticated fleet route: {open_routes}")

    def test_agent_mcp_servers_never_reach_the_mission_routes(self):
        for folder in MCP_DIRS:
            if not folder.exists():
                continue
            for path in folder.rglob("*.py"):
                text = path.read_text(encoding="utf-8", errors="ignore")
                for route in MISSION_ROUTES:
                    self.assertNotIn(route, text, f"{path}: agent MCP must not start, watch or stop fleet missions")

    def test_glass_fleet_code_has_no_inner_html_and_no_approval_answering(self):
        for name in ("services/fleet.ts", "pages/fleetView.ts"):
            path = ROOT / "apps/glass/src" / name
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("innerHTML", text)
            self.assertNotIn("command.answer", text, f"{name}: a mission only points at the Approvals card; it never answers for the owner")
            self.assertNotIn("/approvals/", text)


if __name__ == "__main__":
    unittest.main()
