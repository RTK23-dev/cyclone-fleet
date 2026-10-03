"""Guards for parallel sessions (plan 26 §6, alpha.65): a mission behind the front one never sees or touches the
owner's screen, never takes the phone's controller, is stopped and answered through Task Kit, and a posting task's
Share gate stays on while any posting task runs."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MISSION = MOBILE / "mind/mission"
PLANES = MOBILE / "runtime/plane/MissionPlanes.kt"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_a_behind_mission_never_reads_the_owners_screen_to_begin():
    missions = read(MISSION / "MindMissions.kt")
    assert "val situation = if (run.front) toolbox.situation() else Crew.BEHIND_SITUATION.format(device.now())" in missions
    # Front-only surfaces: the overlay, the phone's controller and the task service are touched only for the front run.
    execute = missions[missions.index("private fun execute("):missions.index("private fun finish(")]
    assert "if (run.front) {\n            publishTask(context, taskId, mission0)" in execute
    assert "DeviceState.setController(DeviceState.Controller.AGENT)" in execute.split("} else {")[0]


def test_a_behind_mission_has_no_screen_until_it_has_its_own():
    planes = read(PLANES)
    before = planes[planes.index("override fun before(tool: String"):planes.index("override fun after(")]
    assert "if (behind && plane is TaskPlane.Screen) {" in before
    assert "Crew.behindRefusal(tool, hasOwnScreen = false)?.let { return it }" in before
    crew = read(MISSION / "Crew.kt")
    assert 'val BEHIND_WITHOUT_SCREEN = setOf("open_app", "set_timer", "set_alarm")' in crew
    # Every road to the owner's screen waits to become the front mission first.
    assert "if (behind && to == PlaneKind.SCREEN && !awaitFront(reason)) return false" in planes
    assert "if (where == TaskPlane.Screen && behind && !awaitFront(modelNote)) {" in planes
    assert "if (!behind) DeviceState.setController(DeviceState.Controller.HUMAN)" in planes
    # Only the front mission's plane reaches the pill.
    assert "if (current?.missionId == missionId) {" in planes


def test_one_cyclone_mission_per_app():
    planes = read(PLANES)
    assert "fun heldByOther(pkg: String, missionId: String): String?" in planes
    assert planes.count("if (!waitForApp(pkg)) return") >= 2


def test_tasks_behind_are_stopped_and_answered_through_task_kit():
    commands = read(MOBILE / "task/TaskCommands.kt")
    assert "others = { MindMissions.behindTasks.value }" in commands
    assert "if (id != null && MindMissions.isBehind(id)) {" in commands
    notes = read(MISSION / "BehindNotifications.kt")
    # Every button on a behind task's notification is a Task Kit pending intent.
    actions = re.findall(r"addAction\(0, [^,]+, ([^)]+\))", notes)
    assert actions and all(a.startswith("TaskCommands.pendingIntent(") for a in actions), actions


def test_the_share_gate_stays_on_while_any_posting_task_runs():
    gate = read(MOBILE / "policy/PublishGate.kt")
    assert "fun active(): Boolean = posting.any(::isRunning)" in gate
    adapter = read(MOBILE / "gateway/GatewayV5CommandAdapter.kt")
    assert "PublishGate.mark(id, publish == true)" in adapter
    assert "PublishGate.missionId = if (publish" not in adapter


def test_lab_runs_always_work_alone():
    missions = read(MISSION / "MindMissions.kt")
    lab = missions[missions.index("fun startLab("):missions.index("fun liveMetrics(")]
    assert "if (isLive()) return null" in lab and "front = true" in lab
