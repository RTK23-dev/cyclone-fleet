from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetError, FleetOrchestrator, mission_status
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.scenes import SceneStore


class FakePhones:
    """The phone side of the `cc.*` contract. Everything above it - the Command Center's SQLite state, its
    dispatch rules (one task per phone, parallel across phones, wait-and-retry), its approvals - is the real code."""

    def __init__(self) -> None:
        self.started: list[tuple[str, str]] = []          # (deviceId, goal), in the order phones were told
        self.stopped: list[str] = []                       # missionIds the phone was told to stop
        self.refuse: dict[str, str] = {}                   # deviceId -> refusal code (locked / busy / disconnected)
        self._missions: dict[str, dict[str, Any]] = {}
        self._n = 0

    def cc_start(self, device_id: str, goal: str, **_extra: Any) -> dict[str, Any]:
        if device_id in self.refuse:
            raise DesktopRuntimeError(self.refuse[device_id], "The phone can't take it right now.")
        self._n += 1
        mission_id = f"m{self._n:04d}"
        self._missions[mission_id] = {"turns": 1, "workingMs": 1000, "costUsd": 0.01, "summary": "", "live": True,
                                      "status": "running", "moment": None, "leases": []}
        self.started.append((device_id, goal))
        self.last = mission_id
        return {"missionId": mission_id}

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return dict(self._missions[mission_id])

    def cc_answer(self, device_id: str, mission_id: str, action: str, **_: Any) -> dict[str, Any]:
        if action == "stop":
            self.stopped.append(mission_id)
        return {}

    def cc_media(self, *_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    def mission_for(self, device_id: str, goal: str) -> str:
        index = [i for i, s in enumerate(self.started) if s == (device_id, goal)][-1]
        return f"m{index + 1:04d}"           # missions are numbered in start order

    def finish(self, mission_id: str, status: str = "completed", summary: str = "done") -> None:
        self._missions[mission_id].update(live=False, status=status, summary=summary)

    def ask_owner(self, mission_id: str, text: str) -> None:
        self._missions[mission_id]["moment"] = {"kind": "approval", "requestId": "req_00000001", "text": text,
                                                "gate": "send", "approvableHere": True, "choices": [], "fields": []}


class Clock:
    def __init__(self) -> None:
        self.now = 1_000_000

    def __call__(self) -> int:
        return self.now


def paired(device_id: str, name: str, **extra: Any) -> dict[str, Any]:
    return {"deviceId": device_id, "id": device_id, "name": name, "model": name, "paired": True, "state": "ready", **extra}


class FleetIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        self.phones = FakePhones()
        self.clock = Clock()
        self.devices: list[dict[str, Any]] = [paired("dev_a", "CPH2717"), paired("dev_b", "SM-X710")]
        self.nicknames = {"dev_a": "Work Phone", "dev_b": "Tablet"}
        self.center = CommandCenter(self.dir / "cc" / "command.db", self.phones, lambda: list(self.devices), clock=self.clock)
        self.center.request_tick = self.center.tick   # no loop thread in this harness: a requested tick runs inline
        self.fleet = FleetOrchestrator(self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
                                       self.dir / "missions.json", clock=self.clock)

    def tearDown(self) -> None:
        self.center.stop()

    def tick(self, advance_ms: int = 0) -> None:
        self.clock.now += advance_ms
        self.center.tick()

    def states(self, mission: dict[str, Any]) -> dict[str, str]:
        return {p["label"]: p["state"] for p in mission["phones"]}

    # ------------------------------------------------------------------ the core promise

    def test_one_sentence_starts_different_goals_on_two_phones_at_once(self):
        result = self.fleet.run_command("unlock and check messages on Work Phone, open camera on Tablet")
        self.assertTrue(result["dispatched"], result)
        # Both phones were told in the SAME dispatch pass - not one after the other.
        self.assertEqual(sorted(self.phones.started), [("dev_a", "Unlock and check messages"), ("dev_b", "Open camera")])
        mission = result["mission"]
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})
        self.assertEqual(mission["status"], "RUNNING")

    def test_the_mission_completes_only_when_every_phone_reports_done(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), summary="3 unread")
        self.tick()
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(self.states(snap), {"Work Phone": "COMPLETED", "Tablet": "RUNNING"})
        self.assertEqual(snap["status"], "RUNNING")                      # not complete until the other phone is
        self.phones.finish(self.phones.mission_for("dev_b", "Open Maps"), summary="map open")
        self.tick()
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(snap["status"], "COMPLETED")
        self.assertEqual({p["label"]: p["summary"] for p in snap["phones"]}, {"Work Phone": "3 unread", "Tablet": "map open"})

    def test_one_phone_failing_does_not_discard_the_other_phones_success(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), summary="3 unread")
        self.phones.finish(self.phones.mission_for("dev_b", "Open Maps"), status="gave_up")
        self.tick()
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(snap["status"], "PARTIAL_FAILURE")
        self.assertEqual(self.states(snap), {"Work Phone": "COMPLETED", "Tablet": "FAILED"})
        self.assertEqual(next(p for p in snap["phones"] if p["label"] == "Work Phone")["summary"], "3 unread")

    # ------------------------------------------------------------------ locked / busy phones wait, then resume

    def test_a_locked_phone_waits_and_resumes_by_itself_when_it_becomes_ready(self):
        self.phones.refuse["dev_b"] = "HUMAN_HAS_CONTROL"                # e.g. someone is holding the tablet
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "WAITING"})
        self.assertIn("control", next(p for p in mission["phones"] if p["label"] == "Tablet")["cause"].lower())
        self.assertEqual(mission["status"], "RUNNING")                   # the fleet does not freeze for one phone
        self.assertEqual([d for d, _ in self.phones.started], ["dev_a"])
        del self.phones.refuse["dev_b"]                                  # the owner lets go of the tablet
        self.tick(advance_ms=31_000)                                     # the engine retries after its 30 s wait
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(self.states(snap), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})
        self.assertIn(("dev_b", "Open Maps"), self.phones.started)

    def test_a_phone_takes_a_front_task_and_two_behind_then_the_fourth_waits(self):
        # PHONE_TASKS = 3: one in front, two queued behind it on the phone. The fourth waits for a slot, then advances.
        from cyclone_device_gateway.command.center import PHONE_TASKS
        self.assertEqual(PHONE_TASKS, 3)
        ms = [self.fleet.run_command(f"open {app} on Work Phone", confirm=True)["mission"] for app in ("Gmail", "Maps", "Clock", "Photos")]
        self.assertEqual([self.states(m)["Work Phone"] for m in ms], ["RUNNING", "QUEUED", "QUEUED", "WAITING"])
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"))
        self.tick(advance_ms=31_000)
        self.assertNotEqual(self.states(self.fleet.mission(ms[3]["missionId"]))["Work Phone"], "WAITING")

    def test_a_phone_that_needs_the_owner_is_shown_without_blocking_the_other(self):
        mission = self.fleet.run_command("send hello on Work Phone, open Maps on Tablet")["mission"]
        self.phones.ask_owner(self.phones.mission_for("dev_a", "Send hello"), "Send this message to John?")
        self.tick()
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(self.states(snap), {"Work Phone": "NEEDS_YOU", "Tablet": "RUNNING"})
        self.assertEqual(snap["status"], "NEEDS_YOU")
        approval = next(p for p in snap["phones"] if p["label"] == "Work Phone")["approval"]
        self.assertEqual(approval["text"], "Send this message to John?")

    # ------------------------------------------------------------------ naming and refusing

    def test_a_named_phone_that_is_not_paired_fails_alone_and_the_other_still_runs(self):
        self.devices[1]["paired"] = False
        result = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")
        mission = result["mission"]
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "FAILED"})
        self.assertIn("isn't paired", next(p for p in mission["phones"] if p["label"] == "Tablet")["cause"])
        self.assertEqual([d for d, _ in self.phones.started], ["dev_a"])

    def test_a_phone_that_is_remembered_but_disconnected_is_not_silently_replaced(self):
        del self.devices[1]                                              # Tablet unplugged; nickname remembered
        mission = self.fleet.run_command("check mail on Work Phone, open camera on Tablet")["mission"]
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "FAILED"})
        self.assertEqual(self.phones.started, [("dev_a", "Check mail")])

    def test_ambiguous_or_unnamed_commands_start_nothing(self):
        result = self.fleet.run_command("check my email")
        self.assertFalse(result["dispatched"])
        self.assertIn("Which phone", result["clarification"])
        self.assertEqual(self.phones.started, [])

    def test_a_medium_confidence_split_is_shown_first_and_runs_only_when_confirmed(self):
        result = self.fleet.run_command("Work Phone check mail, Tablet open camera")
        self.assertFalse(result["dispatched"])
        self.assertTrue(result["needsConfirmation"])
        self.assertEqual({a["label"] for a in result["plan"]["assignments"]}, {"Work Phone", "Tablet"})
        self.assertEqual(self.phones.started, [])                        # nothing ran yet
        result = self.fleet.run_command("Work Phone check mail, Tablet open camera", confirm=True)
        self.assertTrue(result["dispatched"])
        self.assertEqual(len(self.phones.started), 2)

    def test_the_command_center_refuses_secret_shaped_goals_and_the_reason_reaches_the_owner(self):
        result = self.fleet.run_command("log in on Work Phone with password: hunter2, open Maps on Tablet")
        mission = result["mission"]
        work = next(p for p in mission["phones"] if p["label"] == "Work Phone")
        self.assertEqual(work["state"], "FAILED")
        self.assertIn("secret", work["cause"])
        self.assertNotIn("dev_a", [d for d, _ in self.phones.started])   # the password never reached the phone
        self.assertEqual(self.states(mission)["Tablet"], "RUNNING")

    def test_broadcast_to_every_phone_needs_confirmation_then_starts_them_all(self):
        result = self.fleet.run_command("open Settings on all phones")
        self.assertTrue(result["needsConfirmation"])
        self.assertEqual(self.phones.started, [])
        result = self.fleet.run_command("open Settings on all phones", confirm=True)
        self.assertEqual(sorted(d for d, _ in self.phones.started), ["dev_a", "dev_b"])

    # ------------------------------------------------------------------ idempotency, cancel, stop-all

    def test_a_double_submit_does_not_run_anything_twice(self):
        first = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet", client_request_id="click-0001")
        second = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet", client_request_id="click-0001")
        self.assertEqual(first["mission"]["missionId"], second["mission"]["missionId"])
        self.assertEqual(len(self.phones.started), 2)
        self.assertEqual(len(self.fleet.missions()), 1)

    def test_cancel_a_mission_stops_its_phones_and_only_its_phones(self):
        keep = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        drop = self.fleet.run_command("open Maps on Tablet")["mission"]
        snap = self.fleet.cancel_mission(drop["missionId"])
        self.assertEqual(snap["status"], "CANCELLED")
        self.assertEqual(self.phones.stopped, [self.phones.mission_for("dev_b", "Open Maps")])   # the phone was told to stop
        self.assertEqual(self.fleet.mission(keep["missionId"])["status"], "RUNNING")

    def test_stop_all_cancels_every_open_task_on_every_phone(self):
        self.phones.refuse["dev_b"] = "DEVICE_DISCONNECTED"
        a = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        b = self.fleet.run_command("open Maps on Tablet")["mission"]      # waiting, never started
        result = self.fleet.stop_all()
        self.assertEqual(result["stopped"], 2)
        self.assertEqual(self.fleet.mission(a["missionId"])["status"], "CANCELLED")
        self.assertEqual(self.fleet.mission(b["missionId"])["status"], "CANCELLED")
        self.assertEqual(len(self.phones.stopped), 1)                     # only the phone that was actually working
        self.assertEqual(self.fleet.stop_all()["stopped"], 0)             # nothing left to stop

    def test_finished_work_is_not_touched_by_stop_all(self):
        mission = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"))
        self.tick()
        self.fleet.stop_all()
        self.assertEqual(self.fleet.mission(mission["missionId"])["status"], "COMPLETED")

    # ------------------------------------------------------------------ direct dispatch, scenes, persistence

    def test_dispatch_by_explicit_assignments_is_what_the_per_phone_ask_button_uses(self):
        mission = self.fleet.dispatch([{"deviceId": "dev_b", "label": "Tablet", "goal": "Open YouTube"}], "Open YouTube")
        self.assertEqual(self.states(mission), {"Tablet": "RUNNING"})

    def test_two_instructions_for_one_phone_in_one_mission_are_refused(self):
        with self.assertRaises(FleetError):
            self.fleet.dispatch([{"deviceId": "dev_a", "goal": "one"}, {"deviceId": "dev_a", "goal": "two"}])

    def test_a_saved_command_runs_by_nickname_and_refuses_if_a_phone_is_unknown(self):
        scenes = SceneStore(self.dir / "scenes.json")
        scenes.put_scene("morning", "Morning check", [{"nickname": "Work Phone", "goal": "check email"},
                                                      {"nickname": "Tablet", "goal": "check calendar"}])
        resolve = lambda name: {v.casefold(): k for k, v in self.nicknames.items()}.get(name.casefold())
        mission = self.fleet.run_scene(scenes, "morning", resolve)
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})
        scenes.put_scene("broken", "Broken", [{"nickname": "Work Phone", "goal": "x1"}, {"nickname": "Ghost", "goal": "x2"}])
        started_before = len(self.phones.started)
        with self.assertRaises(FleetError):
            self.fleet.run_scene(scenes, "broken", resolve)
        self.assertEqual(len(self.phones.started), started_before)        # refused as a whole, nothing ran

    def test_missions_survive_a_gateway_restart(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        again = FleetOrchestrator(self.center, lambda: list(self.devices), lambda: dict(self.nicknames), self.dir / "missions.json")
        snap = again.mission(mission["missionId"])
        self.assertEqual(self.states(snap), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})

    def test_unknown_mission_is_not_found(self):
        with self.assertRaises(FleetError) as ctx:
            self.fleet.mission("flt_nope")
        self.assertEqual(ctx.exception.code, "NOT_FOUND")


class MissionStatusTests(unittest.TestCase):
    def test_folding(self):
        self.assertEqual(mission_status(["COMPLETED", "COMPLETED"]), "COMPLETED")
        self.assertEqual(mission_status(["COMPLETED", "FAILED"]), "PARTIAL_FAILURE")
        self.assertEqual(mission_status(["FAILED", "FAILED"]), "FAILED")
        self.assertEqual(mission_status(["CANCELLED", "CANCELLED"]), "CANCELLED")
        self.assertEqual(mission_status(["COMPLETED", "CANCELLED"]), "PARTIAL_FAILURE")
        self.assertEqual(mission_status(["RUNNING", "FAILED"]), "RUNNING")
        self.assertEqual(mission_status(["NEEDS_YOU", "RUNNING"]), "NEEDS_YOU")
        self.assertEqual(mission_status(["WAITING", "COMPLETED"]), "WAITING")
        self.assertEqual(mission_status([]), "FAILED")


if __name__ == "__main__":
    unittest.main()
