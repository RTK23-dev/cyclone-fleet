from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ToolContract:
    name: str
    read_only: bool
    phone_scoped: bool


TOOL_CONTRACTS = (
    ToolContract("phone_list", True, False),
    ToolContract("phone_status", True, True),
    ToolContract("phone_capabilities", True, True),
    ToolContract("phone_observe", True, True),
    ToolContract("phone_locate", True, True),
    ToolContract("phone_ui_search", True, True),
    ToolContract("phone_inspect_element", True, True),
    ToolContract("phone_screenshot", True, True),
    ToolContract("phone_current_page", True, True),
    ToolContract("phone_page_history", True, True),
    ToolContract("phone_act", False, True),
    ToolContract("phone_workspace", False, True),
    ToolContract("phone_skill_save", False, True),
    ToolContract("phone_skill_run", False, True),
    ToolContract("phone_group_act", False, False),
    ToolContract("phone_debug_bundle", True, True),
    ToolContract("phone_teach_start", False, True),
    ToolContract("phone_teach_status", True, True),
    ToolContract("phone_teach_stop", False, True),
    ToolContract("phone_virtual_list", True, False),
    ToolContract("phone_virtual_create", False, False),
    ToolContract("phone_virtual_start", False, False),
    ToolContract("phone_virtual_stop", False, False),
    ToolContract("phone_routine_run", False, True),
    ToolContract("phone_routine_status", True, True),
    ToolContract("phone_routine_cancel", False, True),
    # The App Manual (plan 36 §8): what you can do in an app and the path to each. Read only; the app's own words.
    ToolContract("phone_app_manual", True, True),
    # Cyclone Lab: measured Mind missions scored from the phone's real state, for A/B testing Cyclone itself.
    ToolContract("phone_lab_missions", True, False),
    ToolContract("phone_lab_start", False, True),
    ToolContract("phone_lab_report", True, False),
    ToolContract("phone_lab_stop", False, False),
)

TOOL_NAMES = tuple(contract.name for contract in TOOL_CONTRACTS)

PLANNER_TOOLS = frozenset({"phone_status", "phone_skill_run", "phone_skill_save", "phone_capabilities"})
UI_TOOLS = frozenset({"phone_observe", "phone_locate", "phone_ui_search", "phone_inspect_element", "phone_act"})


def surface_role(name: str) -> str:
    if name in PLANNER_TOOLS:
        return "planner"
    if name in UI_TOOLS:
        return "ui"
    return "shared"

FORBIDDEN_TOOL_FRAGMENTS = (
    "shell",
    "powershell",
    "command",
    "subprocess",
    "adb",
    "root",
    "su",
    "script",
    "exec",
)

ALLOWED_ACTIONS = frozenset(
    {
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    }
)

ALLOWED_GROUP_ACTIONS = frozenset(
    {
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    }
)
