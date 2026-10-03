from __future__ import annotations

import ast
from pathlib import Path

from cyclone_agent_mcp.gateway import DeviceSummary, GatewayClient, GatewayError
from cyclone_agent_mcp.tool_catalog import FORBIDDEN_TOOL_FRAGMENTS, TOOL_CONTRACTS, TOOL_NAMES
from cyclone_agent_mcp.tools import PhoneTools


class SelectionGateway(GatewayClient):
    def __init__(self, devices):
        self.devices = devices

    def list_devices(self):
        return list(self.devices)


class FakeToolsGateway:
    def __init__(self, devices):
        self.devices = devices
        self.calls = []

    def list_devices(self):
        return list(self.devices)

    def status(self, device_id=None):
        self.calls.append(("status", device_id))
        return {"device_id": device_id or self.devices[0].device_id, "state": "READY"}

    def capabilities(self, device_id=None, refresh=False):
        self.calls.append(("capabilities", device_id, refresh))
        return {"protocol_version": "cyclone.gateway.capability.v1", "capabilities": []}

    def observe(self, device_id=None, include_screenshot=False, mode="compact", **kwargs):
        call = ("observe", device_id, include_screenshot, mode)
        if kwargs:
            call = (*call, kwargs)
        self.calls.append(call)
        return {"device_id": device_id or self.devices[0].device_id, "mode": mode}

    def ui_search(self, query, device_id=None, **kwargs): return {"query": query, "device_id": device_id}
    def ui_element(self, element_id, device_id=None, **kwargs): return {"element_id": element_id, "device_id": device_id}
    def current_page(self, device_id=None): return {"device_id": device_id}
    def page_history(self, device_id=None): return {"device_id": device_id}
    def action(self, tool, params, goal, device_id=None, **kwargs): return {"ok": True, "tool": tool, "device_id": device_id, "params": params, **kwargs}
    def debug_bundle(self, device_id=None, expected="", goal=""): return {"device_id": device_id}
    def teach_start(self, device_id=None, goal=""): return {"device_id": device_id}
    def teach_status(self, device_id=None): return {"device_id": device_id}
    def teach_stop(self, device_id=None, compile_for_review=True): return {"device_id": device_id}


def test_phone_list_is_first_class_tool():
    assert TOOL_NAMES[0] == "phone_list"


def test_single_ready_device_auto_selects():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY")])
    assert gateway.select_device().device_id == "phone-a"


def test_explicit_device_selection():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    assert gateway.select_device("phone-b").device_id == "phone-b"


def test_multi_device_ambiguity_is_explicit_and_safe():
    gateway = SelectionGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    try:
        gateway.select_device()
        raise AssertionError("selection should fail")
    except GatewayError as exc:
        assert exc.body["error"]["code"] == "DEVICE_SELECTION_REQUIRED"
        assert exc.body["available_devices"] == [
            {"device_id": "phone-a", "state": "READY"},
            {"device_id": "phone-b", "state": "READY"},
        ]


def test_phone_tools_forward_execution_session_identity():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    tools.call("phone_observe", {"device_id": "phone-a", "session_id": "workspace-a", "display_id": 7})
    assert gateway.calls[-1][0] == "observe"
    assert gateway.calls[-1][-1]["session_id"] == "workspace-a"
    assert gateway.calls[-1][-1]["display_id"] == 7
    acted = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.home",
        "params": {},
        "goal": "Go home",
        "session_id": "workspace-a",
        "display_id": 7,
    })
    assert acted["session_id"] == "workspace-a"
    assert acted["display_id"] == 7


def test_phone_tools_forward_explicit_device_id():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    tools = PhoneTools(gateway=gateway)
    assert tools.call("phone_status", {"device_id": "phone-b"})["device_id"] == "phone-b"
    assert gateway.calls[-1] == ("status", "phone-b")


def test_phone_type_requires_intent_acknowledgement():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    result = tools.call("phone_act", {
        "tool": "phone.type", "params": {"value": "secret"}, "goal": "type", "device_id": "phone-a",
        "session_id": "default-foreground",
    })
    assert result["error"]["code"] == "INVALID_REQUEST"
    assert "secret" not in str(result)


def test_missing_session_id_is_rejected_and_not_forwarded():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    observed = tools.call("phone_observe", {"device_id": "phone-a"})
    assert observed["errorClass"] == "SESSION_REQUIRED"
    assert observed["error"]["code"] == "SESSION_REQUIRED"
    assert gateway.calls == []
    acted = tools.call("phone_act", {
        "device_id": "phone-a", "tool": "phone.home", "params": {}, "goal": "Go home",
    })
    assert acted["errorClass"] == "SESSION_REQUIRED"
    assert gateway.calls == []
    located = tools.call("phone_locate", {"device_id": "phone-a", "goal": "Open Apps"})
    assert located["errorClass"] == "SESSION_REQUIRED"
    assert gateway.calls == []


def test_default_foreground_session_forwards_display_zero():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    tools.call("phone_observe", {"device_id": "phone-a", "session_id": "default-foreground"})
    assert gateway.calls[-1][-1]["session_id"] == "default-foreground"
    assert gateway.calls[-1][-1]["display_id"] == 0
    acted = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.home",
        "params": {},
        "goal": "Go home",
        "session_id": "default-foreground",
    })
    assert acted["session_id"] == "default-foreground"
    assert acted["display_id"] == 0


def test_every_phone_scoped_server_function_has_device_id_and_no_escape_hatch():
    server_path = Path(__file__).parents[1] / "cyclone_agent_mcp" / "server.py"
    tree = ast.parse(server_path.read_text(encoding="utf-8"))
    functions = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name in TOOL_NAMES}
    assert set(functions) == set(TOOL_NAMES)
    for contract in TOOL_CONTRACTS:
        args = [arg.arg for arg in functions[contract.name].args.args]
        if contract.phone_scoped:
            assert "device_id" in args, contract.name
        else:
            assert (
                contract.name
                in {
                    "phone_list",
                    "phone_group_act",
                    "phone_virtual_list",
                    "phone_virtual_create",
                    "phone_virtual_start",
                    "phone_virtual_stop",
                    # Lab catalog, reports and stop act on experiments (each already bound to one phone).
                    "phone_lab_missions",
                    "phone_lab_report",
                    "phone_lab_stop",
                }
            ) and "device_id" not in args
    lowered = " ".join(TOOL_NAMES).lower()
    assert all(fragment not in lowered for fragment in FORBIDDEN_TOOL_FRAGMENTS)


def test_server_action_schema_has_only_typed_phone_actions():
    server_path = Path(__file__).parents[1] / "cyclone_agent_mcp" / "server.py"
    source = server_path.read_text(encoding="utf-8")
    assert "adb shell" not in source.lower()
    assert "powershell" in source.lower()
    assert "subprocess." not in source
    assert '"phone.click"' in source and '"phone.type"' in source


def test_group_action_requires_explicit_unique_targets_and_observes_each_first():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY"), DeviceSummary("phone-b", "READY")])
    tools = PhoneTools(gateway=gateway)
    result = tools.call("phone_group_act", {
        "device_ids": ["phone-a", "phone-b"],
        "tool": "phone.home",
        "params": {},
        "goal": "Return selected test devices home",
        "session_id": "default-foreground",
    })
    assert result["ok"] is True
    assert result["selected_device_ids"] == ["phone-a", "phone-b"]
    identity = {"session_id": "default-foreground", "display_id": 0}
    assert gateway.calls == [
        ("observe", "phone-a", False, "compact", identity),
        ("observe", "phone-b", False, "compact", identity),
    ]
    duplicate = tools.call("phone_group_act", {
        "device_ids": ["phone-a", "phone-a"], "tool": "phone.home", "params": {}, "goal": "x",
    })
    assert duplicate["error"]["code"] == "INVALID_REQUEST"


def test_open_app_requires_params_package_not_aliases():
    from cyclone_phone_mcp.tools import (
        OPEN_APP_DISPLAY_NAME,
        OPEN_APP_INVALID_PACKAGE,
        OPEN_APP_MISSING_PACKAGE,
        OPEN_APP_PACKAGE_NAME,
    )

    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    accepted = tools.phone_act({
        "device_id": "phone-a",
        "tool": "phone.open_app",
        "params": {"package": "com.android.chrome"},
        "goal": "Open Chrome",
        "session_id": "default-foreground",
    })
    assert accepted["ok"] is True
    assert accepted["params"]["package"] == "com.android.chrome"
    cases = (
        ({"packageName": "com.android.chrome"}, OPEN_APP_PACKAGE_NAME),
        ({"name": "Chrome"}, OPEN_APP_DISPLAY_NAME),
        ({"appName": "Chrome"}, OPEN_APP_DISPLAY_NAME),
        ({}, OPEN_APP_MISSING_PACKAGE),
        ({"package": "Chrome"}, OPEN_APP_INVALID_PACKAGE),
    )
    for params, message in cases:
        result = tools.call("phone_act", {
            "device_id": "phone-a",
            "tool": "phone.open_app",
            "params": params,
            "goal": "Open Chrome",
            "session_id": "default-foreground",
        })
        assert result["error"]["code"] == "INVALID_REQUEST"
        assert result["error"]["message"] == message


def test_command_shaped_params_and_batch_typing_are_rejected():
    gateway = FakeToolsGateway([DeviceSummary("phone-a", "READY")])
    tools = PhoneTools(gateway=gateway)
    injected = tools.call("phone_act", {
        "device_id": "phone-a",
        "tool": "phone.click",
        "params": {"selector": {"text": "Apps"}, "command": "whoami"},
        "goal": "Open Apps",
        "session_id": "default-foreground",
    })
    assert injected["error"]["code"] == "INVALID_REQUEST"
    typed = tools.call("phone_group_act", {
        "device_ids": ["phone-a"], "tool": "phone.type", "params": {"value": "x"}, "goal": "type",
    })
    assert typed["error"]["code"] == "INVALID_REQUEST"


class _LabGateway:
    def __init__(self):
        self.calls = []

    def lab_start(self, *args):
        self.calls.append(("start", args))
        return {"id": "exp-20260925-120000-abcd", "status": "running"}

    def lab_experiment(self, experiment_id):
        self.calls.append(("get", experiment_id))
        return {"experiment": {"id": experiment_id}, "arms": {}, "insights": ["x"], "trials": [
            {"missionId": "nav.home", "variant": "A", "verdict": "pass"},
            {"missionId": "clock.timer.5", "variant": "A", "verdict": "fail", "category": "false_success", "cause": "said done",
             "phone": {"summary": "Timer set", "traceId": "ai-run-1", "metrics": {"errorTail": ["tap: not found"], "toolCalls": {"tap": 2}}}},
        ]}

    def lab_experiments(self):
        return {"experiments": []}


def test_lab_tools_start_experiments_and_report_failures_compactly():
    from cyclone_agent_mcp.tools import PhoneTools

    gateway = _LabGateway()
    tools = PhoneTools(gateway=gateway)
    started = tools.call("phone_lab_start", {"device_id": "phone-1", "name": "marks", "missions": ["nav.home"],
                                             "variants": [{"name": "A"}, {"name": "B", "marks": False}], "repetitions": 2})
    assert started["status"] == "running"
    report = tools.call("phone_lab_report", {"experiment_id": "exp-20260925-120000-abcd"})
    assert "trials" not in report and report["failures"][0]["category"] == "false_success"
    assert report["failures"][0]["errorTail"] == ["tap: not found"]
    assert tools.call("phone_lab_report", {}) == {"experiments": []}
    for bad in ({"device_id": "phone-1", "name": "x", "missions": ["../x"]},
                {"device_id": "phone-1", "name": "x", "missions": ["nav.home"], "variants": [{"name": "A", "shell": "id"}]},
                {"device_id": "phone-1", "name": "x", "missions": ["nav.home"], "repetitions": 99}):
        assert tools.call("phone_lab_start", bad)["error"]["code"] == "INVALID_REQUEST"
    assert tools.call("phone_lab_report", {"experiment_id": "../../etc"})["error"]["code"] == "INVALID_REQUEST"


def test_app_manual_is_read_only_and_validated():
    class ManualGateway(FakeToolsGateway):
        def app_manual(self, device_id, package_name, query=None):
            self.calls.append(("app_manual", device_id, package_name, query))
            return {"appLabel": "Instagram", "currentVersion": "402.0", "query": query, "clear": True,
                    "abilities": [{"id": "ab:0123456789ab", "name": "Requests on Messages"}, {"id": "ab:ffffffffffff", "name": "Open Settings"}],
                    "hits": [{"id": "ab:0123456789ab", "score": 0.94}], "quiz": None, "scores": {"map": 0.75}, "markdown": "# Instagram"}

    gateway = ManualGateway([DeviceSummary("phone-1", "READY")])
    tools = PhoneTools(gateway)
    out = tools.call("phone_app_manual", {"device_id": "phone-1", "app": "com.instagram.android", "query": " message requests "})
    assert gateway.calls[-1] == ("app_manual", "phone-1", "com.instagram.android", "message requests")
    assert out["clearMatch"] is True
    assert out["matches"][0]["name"] == "Requests on Messages" and out["matches"][0]["fit"] == 0.94
    contract = next(c for c in TOOL_CONTRACTS if c.name == "phone_app_manual")
    assert contract.read_only and contract.phone_scoped
    for bad in ({"device_id": "phone-1", "app": "../etc"},
                {"device_id": "phone-1", "app": "com.instagram.android", "query": "x" * 300},
                {"device_id": "phone-1", "app": "com.instagram.android", "shell": "id"}):
        result = tools.call("phone_app_manual", bad)
        assert not isinstance(result, dict) or "abilities" not in result, bad
