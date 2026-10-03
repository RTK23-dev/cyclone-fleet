"""Guards for plan 38 (alpha.68): steer, queue, parallel and plan diversions.

- every Ask-bar choice and Pause / Resume goes through Task Kit to the engine that owns the task;
- a diversion never adds a stop of its own and never gets around the approval of a serious action;
- plan versions run whatever the settings (not only in workspace runs);
- Parallel is only offered when the crew admits it;
- a steer always becomes a new goal version the plan must follow.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MIND = BASE / "mind"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def callers(pattern: str) -> set[str]:
    regex = re.compile(pattern)
    return {p.relative_to(BASE).as_posix() for p in BASE.rglob("*.kt") if regex.search(read(p))}


def test_steer_queue_parallel_and_pause_reach_missions_only_through_task_kit():
    assert callers(r"MindMissions\.(steerTask|queueTask|parallelTask|pause|unpause)\(") == {"task/TaskCommands.kt"}
    kit = read(BASE / "task/TaskKit.kt")
    for command in ('Steer(val text: String) : TaskCommand("steer"', 'Queue(val text: String) : TaskCommand("queue"',
                    'Parallel(val text: String) : TaskCommand("parallel"', 'Unpause : TaskCommand("unpause"'):
        assert command in kit, command


def test_both_ask_bars_send_the_chosen_option_as_a_task_kit_command():
    for surface in ("ui/overlay/OverlayChrome.kt", "ui/v32/CycloneV39AiChatPage.kt"):
        text = read(BASE / surface)
        assert "MindMissions.askOptions(" in text, surface
        assert "TaskCommands.send(context, target, com.cyclone.mobile.task.AskWhileWorking.command(choice, text))" in text, surface
    overlay = read(BASE / "ui/overlay/OverlayChrome.kt")
    assert "com.cyclone.mobile.task.TaskCommand.Unpause else com.cyclone.mobile.task.TaskCommand.Pause" in overlay
    # The Ask page no longer guesses: while a mission runs, the owner chooses.
    assert "MindMissions.steer(normalized)" not in read(BASE / "ui/v32/CycloneV39AiChatPage.kt")


def test_parallel_is_only_offered_when_the_crew_admits_it():
    missions = read(MIND / "mission/MindMissions.kt")
    assert "AskWhileWorking.options(question, parallelBlocker(context, text), MissionQueue.isNewTask(text))" in missions
    assert "is Crew.Admit.Queue -> admit.reason" in missions
    assert "parallelBlocker(context, goal)?.let { return it }" in missions
    ask = read(BASE / "task/AskWhileWorking.kt")
    assert "enabled = parallelBlocker == null" in ask


def test_plan_versions_run_in_every_mission_whatever_the_settings():
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "    val planVersions = com.cyclone.mobile.mind.divert.PlanVersions(goal)\n" in toolbox
    assert "val diverted = planVersions.planned(plan, divert)" in toolbox
    # plan_update's divert argument is part of the base spec, not a workspace extra.
    specs = read(MIND / "workspace/WorkspaceSpecs.kt")
    assert '"divert"' not in specs.split('"plan_update" ->', 1)[1].split("}", 1)[0]


def test_a_diversion_never_stops_the_mission_and_never_skips_an_approval():
    versions = read(MIND / "divert/PlanVersions.kt")
    for forbidden in ("MindEnding", "awaitApproval", "cancel", "owner.ask"):
        assert forbidden not in versions, forbidden
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    # The note is added to the approval that was going to be asked anyway; it never decides whether one is asked.
    assert "?: planVersions.approvalNote()) else null" in toolbox
    assert "val approval = if (!gated) null else if (draft != null)" in toolbox


def test_a_steer_is_always_a_new_goal_version_and_the_finish_checks_it_once():
    loop = read(MIND / "MindLoop.kt")
    assert "toolbox.onSteer(text)" in loop and "MindPrompt.steered(text, version.coerceAtLeast(2))" in loop
    toolbox = read(MIND / "PhoneMindToolbox.kt")
    assert "val next = planVersions.steer(text)" in toolbox
    assert "planVersions.finishNote()?.let { return MindToolResult(it" in toolbox
    versions = read(MIND / "divert/PlanVersions.kt")
    assert "if (!needsReplan || replanNoted) return null" in versions


def test_a_pause_holds_at_the_step_boundary_and_is_not_working_time():
    loop = read(MIND / "MindLoop.kt")
    assert "while (paused() && !cancelled()) sleep(PAUSE_POLL_MS)" in loop
    assert "MindPrompt.PAUSED" in loop
