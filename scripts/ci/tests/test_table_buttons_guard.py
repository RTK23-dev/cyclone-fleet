"""Guards for table buttons (plan 43 T3): a press only creates ordinary Command Center tasks and row updates. It never
answers an approval, never touches the vault or sealed secrets, and never runs anything but a task."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BUTTONS = ROOT / "apps/device-gateway/cyclone_device_gateway/command/buttons.py"
AI = ROOT / "apps/device-gateway/cyclone_device_gateway/command/ai.py"


def code(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    return re.sub(r'"""[\s\S]*?"""', "", text)  # docstrings may explain the rules; the code may not break them


def test_a_button_never_approves_or_touches_secrets():
    source = code(BUTTONS)
    for forbidden in ("answer_approval", "cc_answer", ".answer(", "approve(", "delivery", "vault", "sealed", "subprocess", "os.system", "eval(", "exec("):
        assert forbidden not in source, f"buttons.py must not use {forbidden}"


def test_a_button_starts_work_only_through_tasks_and_routines():
    source = code(BUTTONS)
    starts = set(re.findall(r"self\._c\.(\w+)\(", source))
    assert starts <= {"create_task", "run_routine_now", "get_routine", "_audit", "_clock"}, starts


def test_an_agent_press_is_a_proposal_the_owner_applies():
    source = AI.read_text(encoding="utf-8")
    assert '"press_button": ("phone",' in source
    assert '"query_table": ("read",' in source and "personal=False" in source
