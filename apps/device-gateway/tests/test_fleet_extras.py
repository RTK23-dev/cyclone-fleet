"""What the alpha.95 port added on top of the alpha.59 fleet patch, tested against the real Command Center.

Covers: a mission surviving a gateway restart (gap 4), an explicit empty scope never widening to every phone, the
approval detail a person needs before answering, the "why hasn't it started" hint, the one-screen fleet overview, and the
HTTP handlers in fleet_api.py (called directly - see _fastapi_stub.py for what that does and does not prove).
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from tests import _fastapi_stub

_fastapi_stub.install()

from cyclone_device_gateway.command.center import CommandCenter  # noqa: E402
from cyclone_device_gateway.desktop_runtime.fleet_api import create_fleet_router  # noqa: E402
from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetOrchestrator  # noqa: E402
from cyclone_device_gateway.desktop_runtime.scenes import SceneStore  # noqa: E402
from cyclone_device_gateway.desktop_runtime.workspace import FleetWorkspaceStore  # noqa: E402
from tests.test_fleet_orchestrator import Clock, FakePhones, paired  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        self.phones = FakePhones()
        self.clock = Clock()
        self.devices: list[dict[str, Any]] = [paired("dev_a", "CPH2717"), paired("dev_b", "SM-X710")]
        self.nicknames = {"dev_a": "Work Phone", "dev_b": "Tablet"}
        self.boot()

    def boot(self) -> None:
        """(Re)start the gateway's fleet: the Command Center and orchestrator over the SAME files on disk."""
        self.center = CommandCenter(self.dir / "cc" / "command.db", self.phones, lambda: list(self.devices), clock=self.clock)
        self.center.request_tick = self.center.tick   # no loop thread in this harness: a requested tick runs inline
        self.fleet = FleetOrchestrator(self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
                                       self.dir / "fleet" / "missions.json", clock=self.clock)

    def tearDown(self) -> None:
        self.center.stop()

    def tick(self, advance_ms: int = 0) -> None:
        self.clock.now += advance_ms
        self.center.tick()

    @staticmethod
    def states(mission: dict[str, Any]) -> dict[str, str]:
        return {p["label"]: p["state"] for p in mission["phones"]}


class RestartTests(Base):
    def test_a_running_mission_survives_a_gateway_restart_and_still_finishes(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.assertEqual(self.states(mission), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})
        self.center.stop()
        self.boot()                                            # the gateway restarts; the phones kept working meanwhile
        self.assertEqual([m["missionId"] for m in self.fleet.missions()], [mission["missionId"]])
        self.assertEqual(self.states(self.fleet.mission(mission["missionId"])), {"Work Phone": "RUNNING", "Tablet": "RUNNING"})
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), summary="3 unread")
        self.phones.finish(self.phones.mission_for("dev_b", "Open Maps"), summary="map open")
        self.tick()
        snap = self.fleet.mission(mission["missionId"])
        self.assertEqual(snap["status"], "COMPLETED")
        self.assertEqual({p["label"]: p["summary"] for p in snap["phones"]}, {"Work Phone": "3 unread", "Tablet": "map open"})
        self.assertEqual(len(self.phones.started), 2)           # the restart never re-told a phone

    def test_a_double_submit_after_a_restart_is_still_one_mission(self):
        first = self.fleet.run_command("open Gmail on Work Phone", client_request_id="req-restart-1")["mission"]
        self.center.stop()
        self.boot()
        again = self.fleet.run_command("open Gmail on Work Phone", client_request_id="req-restart-1")["mission"]
        self.assertEqual(first["missionId"], again["missionId"])
        self.assertEqual(len(self.phones.started), 1)

    def test_a_damaged_legacy_missions_file_does_not_stop_the_gateway_and_missions_stay_in_sqlite(self):
        self.fleet.run_command("open Gmail on Work Phone")
        self.center.stop()
        (self.dir / "fleet" / "missions.json").write_text("{not json", encoding="utf-8")
        self.boot()                                            # must not raise
        self.assertEqual(len(self.fleet.missions()), 1)         # the SQLite store, not the old JSON, is the source of truth
        self.assertEqual(len(self.center.list_tasks(status="open")), 1)


class ScopeTests(Base):
    def test_control_an_unscoped_confirmed_broadcast_reaches_every_phone(self):
        # Without this, "nothing started" below could be true for the wrong reason (a broadcast always asks first).
        result = self.fleet.run_command("open Maps on all phones", confirm=True)
        self.assertTrue(result["dispatched"], result)
        self.assertEqual(sorted(self.phones.started), [("dev_a", "Open Maps"), ("dev_b", "Open Maps")])

    def test_an_explicit_empty_scope_means_no_phones_never_every_phone(self):
        result = self.fleet.run_command("open Maps on all phones", confirm=True, device_ids=[])
        self.assertFalse(result["dispatched"])
        self.assertEqual(self.phones.started, [])
        self.assertEqual(self.fleet.plan("open Maps on all phones", []).assignments, [])

    def test_a_scope_still_narrows_to_just_those_phones(self):
        result = self.fleet.run_command("open Maps on all phones", confirm=True, device_ids=["dev_b"])
        self.assertTrue(result["dispatched"], result)
        self.assertEqual(self.phones.started, [("dev_b", "Open Maps")])


class SnapshotDetailTests(Base):
    def test_a_phone_that_needs_you_shows_what_it_is_asking_and_whether_it_can_be_answered_here(self):
        mission = self.fleet.run_command("send hello on Work Phone")["mission"]
        self.phones.ask_owner(self.phones.mission_for("dev_a", "Send hello"), "Send this message to John?")
        self.tick()
        approval = self.fleet.mission(mission["missionId"])["phones"][0]["approval"]
        self.assertEqual(approval["text"], "Send this message to John?")
        self.assertEqual(approval["gate"], "send")
        self.assertTrue(approval["approvableHere"])
        self.assertTrue(approval["answerHere"])

    def test_a_phone_that_cannot_start_says_why_in_one_line(self):
        self.devices[1] = paired("dev_b", "SM-X710", state="sleeping", connectionLabel="Sleeping")
        mission = self.fleet.run_command("open Maps on Tablet")["mission"]
        phone = mission["phones"][0]
        self.assertEqual(phone["state"], "WAITING")
        self.assertIn("sleeping", phone["hint"].lower())

    def test_a_phone_that_is_running_has_no_hint(self):
        mission = self.fleet.run_command("open Maps on Tablet")["mission"]
        self.assertEqual(mission["phones"][0]["hint"], "")

    def test_a_phone_that_vanished_says_it_is_not_connected(self):
        mission = self.fleet.run_command("open Maps on Tablet")["mission"]
        self.devices = [d for d in self.devices if d["deviceId"] != "dev_b"]
        self.phones.finish(self.phones.mission_for("dev_b", "Open Maps"))
        again = self.fleet.run_command("open Clock on Work Phone")["mission"]
        self.assertEqual(self.states(again), {"Work Phone": "RUNNING"})
        self.assertEqual(self.fleet.mission(mission["missionId"])["phones"][0]["hint"], "")  # finished: nothing to explain


class OverviewTests(Base):
    def test_the_overview_lists_every_phone_with_its_open_work(self):
        self.devices.append({"deviceId": "dev_c", "id": "dev_c", "name": "Old Pixel", "paired": False, "state": "unpaired",
                             "connectionLabel": "Not paired", "source": "USB", "lastSeenMs": 5})
        self.fleet.run_command("send hello on Work Phone, open Maps on Tablet")
        self.phones.ask_owner(self.phones.mission_for("dev_a", "Send hello"), "Send this message to John?")
        self.tick()
        overview = self.fleet.overview()
        by_label = {p["label"]: p for p in overview["phones"]}
        self.assertEqual(set(by_label), {"Work Phone", "Tablet", "Old Pixel"})
        self.assertEqual([t["status"] for t in by_label["Work Phone"]["tasks"]], ["NEEDS_YOU"])
        self.assertTrue(by_label["Work Phone"]["tasks"][0]["needsYou"])
        self.assertEqual([t["status"] for t in by_label["Tablet"]["tasks"]], ["RUNNING"])
        self.assertFalse(by_label["Old Pixel"]["addressable"])
        self.assertEqual(by_label["Old Pixel"]["tasks"], [])
        self.assertEqual(overview["counts"], {"phones": 3, "ready": 2, "busy": 2, "needYou": 1})
        self.assertEqual([p["label"] for p in overview["phones"]][-1], "Old Pixel")   # addressable phones come first

    def test_the_overview_survives_an_unreadable_task_list(self):
        self.center.list_tasks = lambda **_k: (_ for _ in ()).throw(RuntimeError("db busy"))  # type: ignore[method-assign]
        self.assertEqual(self.fleet.overview()["counts"]["phones"], 2)


class HttpHandlerTests(Base):
    def setUp(self) -> None:
        super().setUp()
        self.workspace = FleetWorkspaceStore(self.dir / "ws.json")
        self.scenes = SceneStore(self.dir / "scenes.json")
        runtime = type("Runtime", (), {})()
        runtime.fleet_orchestrator, runtime.workspace, runtime.scenes, runtime.command = self.fleet, self.workspace, self.scenes, self.center
        self.router = create_fleet_router(runtime, "token")
        self.call = lambda method, path, *a, **k: _fastapi_stub.endpoint(self.router, method, path)(*a, **k)

    def status_of(self, fn) -> int:
        from fastapi import HTTPException            # the stand-in's, or the real one when fastapi is installed
        with self.assertRaises(HTTPException) as ctx:
            fn()
        return ctx.exception.status_code

    def test_every_documented_route_exists(self):
        wanted = {("GET", "/v1/fleet/overview"), ("GET", "/v1/fleet/phones"), ("POST", "/v1/fleet/phones/{device_id}/nickname"),
                  ("POST", "/v1/fleet/command/preview"), ("POST", "/v1/fleet/command"), ("POST", "/v1/fleet/dispatch"),
                  ("GET", "/v1/fleet/missions"), ("GET", "/v1/fleet/missions/{mission_id}"),
                  ("POST", "/v1/fleet/missions/{mission_id}/cancel"), ("POST", "/v1/fleet/stop-all"),
                  ("GET", "/v1/fleet/scenes"), ("POST", "/v1/fleet/scenes/{scene_id}"),
                  ("POST", "/v1/fleet/scenes/{scene_id}/delete"), ("POST", "/v1/fleet/scenes/{scene_id}/run")}
        have = {(m, r.path) for r in self.router.routes for m in r.methods}
        self.assertTrue(wanted <= have, wanted - have)

    def test_a_command_runs_and_a_double_submit_is_one_mission(self):
        body = {"command": "open Gmail on Work Phone, open Maps on Tablet", "requestId": "req-http-0001"}
        first = self.call("POST", "/v1/fleet/command", body)
        second = self.call("POST", "/v1/fleet/command", body)
        self.assertTrue(first["dispatched"])
        self.assertEqual(first["mission"]["missionId"], second["mission"]["missionId"])
        self.assertEqual(len(self.phones.started), 2)

    def test_preview_runs_nothing(self):
        plan = self.call("POST", "/v1/fleet/command/preview", {"command": "open Gmail on Work Phone, open Maps on Tablet"})
        self.assertEqual(len(plan["assignments"]), 2)
        self.assertEqual(self.phones.started, [])

    def test_a_group_scopes_a_broadcast_to_its_members_only(self):
        self.workspace.put_group("office", "Office", ["dev_a"])
        result = self.call("POST", "/v1/fleet/command", {"command": "open Maps on all phones", "groupId": "office", "confirm": True})
        self.assertTrue(result["dispatched"], result)
        self.assertEqual(self.phones.started, [("dev_a", "Open Maps")])

    def test_an_empty_group_runs_on_no_phone(self):
        self.workspace.put_group("nobody", "Nobody", [])
        result = self.call("POST", "/v1/fleet/command", {"command": "open Maps on all phones", "groupId": "nobody", "confirm": True})
        self.assertFalse(result["dispatched"])
        self.assertEqual(self.phones.started, [])

    def test_an_unknown_group_is_a_404_not_a_guess(self):
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/command", {"command": "open Maps on all phones", "groupId": "nope"})), 404)
        self.assertEqual(self.phones.started, [])

    def test_a_malformed_device_scope_is_a_400(self):
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/command", {"command": "open Maps on Tablet", "deviceIds": "dev_b"})), 400)
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/command", {"command": "open Maps on Tablet", "deviceIds": [1]})), 400)

    def test_a_missing_mission_is_a_404(self):
        self.assertEqual(self.status_of(lambda: self.call("GET", "/v1/fleet/missions/{mission_id}", "flt_nope")), 404)

    def test_a_non_object_body_is_a_422(self):
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/command", ["not", "an", "object"])), 422)

    def test_cancel_and_stop_all(self):
        mission = self.call("POST", "/v1/fleet/command", {"command": "open Gmail on Work Phone, open Maps on Tablet"})["mission"]
        cancelled = self.call("POST", "/v1/fleet/missions/{mission_id}/cancel", mission["missionId"])
        self.assertEqual(cancelled["status"], "CANCELLED")
        self.assertEqual(len(self.phones.stopped), 2)
        self.call("POST", "/v1/fleet/command", {"command": "open Clock on Work Phone"})
        self.assertEqual(self.call("POST", "/v1/fleet/stop-all"), {"stopped": 1, "scope": "all"})

    def test_naming_a_phone_the_fleet_has_never_seen_is_a_404(self):
        from fastapi import HTTPException
        with self.assertRaises(HTTPException) as ctx:
            self.call("POST", "/v1/fleet/phones/{device_id}/nickname", "dev_ghost", {"nickname": "Ghost"})
        self.assertEqual(ctx.exception.status_code, 404)

    def test_nicknames_stay_unique_through_the_route(self):
        self.call("POST", "/v1/fleet/phones/{device_id}/nickname", "dev_a", {"nickname": "Work Phone"})
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/phones/{device_id}/nickname", "dev_b", {"nickname": "work phone"})), 400)

    def test_a_scene_saves_runs_and_deletes(self):
        self.workspace.set_nickname("dev_a", "Work Phone")
        self.workspace.set_nickname("dev_b", "Tablet")
        steps = [{"nickname": "Work Phone", "goal": "check email"}, {"nickname": "Tablet", "goal": "check the calendar"}]
        self.call("POST", "/v1/fleet/scenes/{scene_id}", "morning", {"name": "Morning", "steps": steps})
        self.assertEqual([s["sceneId"] if "sceneId" in s else s.get("id") for s in self.call("GET", "/v1/fleet/scenes")["scenes"]], ["morning"])
        ran = self.call("POST", "/v1/fleet/scenes/{scene_id}/run", "morning")
        self.assertEqual(sorted(self.phones.started), [("dev_a", "check email"), ("dev_b", "check the calendar")])
        self.assertEqual(ran["status"], "RUNNING")
        self.call("POST", "/v1/fleet/scenes/{scene_id}/delete", "morning")
        self.assertEqual(self.call("GET", "/v1/fleet/scenes")["scenes"], [])
        self.assertEqual(self.status_of(lambda: self.call("POST", "/v1/fleet/scenes/{scene_id}/run", "morning")), 404)


if __name__ == "__main__":
    unittest.main()
