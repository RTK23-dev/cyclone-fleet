"""Acceptance checks that can run without a phone.

The Command Center and SQLite are real. The phone RPC is the test harness in test_fleet_orchestrator.
A row that needs a device stays UNVERIFIED in docs/FLEET_ACCEPTANCE.md.
"""
import tempfile
import unittest
from pathlib import Path

from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetOrchestrator
from tests.test_fleet_orchestrator import Clock, FakePhones, paired


class AcceptanceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        self.phones = FakePhones()
        self.clock = Clock()
        self.devices = [paired("dev_a", "CPH2717"), paired("dev_b", "SM-X710")]
        self.nicknames = {"dev_a": "Work Phone", "dev_b": "Tablet"}
        self.center = CommandCenter(self.dir / "cc" / "command.db", self.phones, lambda: list(self.devices), clock=self.clock)
        self.center.request_tick = self.center.tick
        self.fleet = FleetOrchestrator(
            self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
            self.dir / "missions.json", clock=self.clock,
        )

    def tearDown(self) -> None:
        self.fleet.close()
        self.center.stop()

    def test_two_phones_one_request_id_is_one_mission(self):
        first = self.fleet.run_command(
            "open mail on Work Phone, open camera on Tablet", client_request_id="accept-two-phones",
        )
        second = self.fleet.run_command(
            "open mail on Work Phone, open camera on Tablet", client_request_id="accept-two-phones",
        )
        self.assertTrue(first["dispatched"])
        self.assertEqual(first["mission"]["missionId"], second["mission"]["missionId"])
        self.assertEqual(len(self.phones.started), 2)

    def test_owner_moment_is_listed_and_not_answered(self):
        mission = self.fleet.run_command("send hello on Work Phone", confirm=True)["mission"]
        self.phones.ask_owner(self.phones.mission_for("dev_a", "Send hello"), "Send this message to John?")
        self.center.tick()
        listed = self.fleet.approvals()
        self.assertEqual(listed["count"], 1)
        self.assertIn("John", listed["approvals"][0]["text"])
        self.assertFalse(hasattr(self.fleet, "answer_approval"))
        self.assertFalse(self.fleet.health()["answersApprovals"])
        self.assertEqual(self.fleet.mission(mission["missionId"])["phones"][0]["state"], "NEEDS_YOU")

    def test_locked_phone_is_not_shown_as_running(self):
        self.devices[1]["state"] = "SLEEPING"
        self.devices[1]["screen"] = "SLEEPING"
        mission = self.fleet.run_command("open mail on Work Phone, open camera on Tablet")["mission"]
        tablet = next(phone for phone in mission["phones"] if phone["label"] == "Tablet")
        self.assertNotEqual(tablet["state"], "RUNNING")
        self.assertIn("asleep", tablet["hint"].lower())

    def test_restart_does_not_create_a_second_task(self):
        self.fleet.run_command("open mail on Work Phone", confirm=True, client_request_id="accept-restart")
        self.assertEqual(len(self.phones.started), 1)
        self.fleet.close()
        again = FleetOrchestrator(
            self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
            self.dir / "missions.json", clock=self.clock,
        )
        result = again.run_command("open mail on Work Phone", confirm=True, client_request_id="accept-restart")
        self.assertEqual(len(self.phones.started), 1)
        self.assertTrue(result["dispatched"])
        again.close()

    def test_broadcast_asks_before_it_runs(self):
        asked = self.fleet.run_command("open the clock on all phones")
        self.assertFalse(asked["dispatched"])
        self.assertTrue(asked["needsConfirmation"])
        self.assertEqual(self.phones.started, [])
        started = self.fleet.run_command("open the clock on all phones", confirm=True)
        self.assertTrue(started["dispatched"])
        self.assertEqual(len(self.phones.started), 2)

    def test_three_stops_have_different_scope(self):
        one = self.fleet.run_command("open mail on Work Phone", confirm=True)["mission"]
        two = self.fleet.run_command("open camera on Tablet", confirm=True)["mission"]
        other = self.center.create_task({"title": "Direct", "goal": "open notes", "deviceId": "dev_a", "requestId": "direct-task-1"})
        self.fleet.cancel_mission(one["missionId"])
        self.assertEqual(self.center.get_task(other["id"])["status"], "scheduled")
        self.fleet.stop_fleet_missions()
        self.assertEqual(self.center.get_task(other["id"])["status"], "scheduled")
        self.fleet.stop_all()
        self.assertEqual(self.center.get_task(other["id"])["status"], "cancelled")
        self.assertEqual(self.fleet.mission(two["missionId"])["phones"][0]["state"], "CANCELLED")

    def test_canary_holds_the_rest_until_continued(self):
        rolled = self.fleet.rollout([
            {"deviceId": "dev_a", "label": "Work Phone", "goal": "open mail"},
            {"deviceId": "dev_b", "label": "Tablet", "goal": "open camera"},
        ], canary=1, command="canary")
        self.assertEqual(rolled["remaining"], 1)
        self.assertEqual([device for device, _goal in self.phones.started], ["dev_a"])
        self.fleet.continue_rollout(rolled["mission"]["missionId"])
        self.assertIn("dev_b", [device for device, _goal in self.phones.started])

    def test_empty_group_addresses_nobody(self):
        result = self.fleet.run_command("open mail on the shop group", device_ids=[])
        self.assertFalse(result["dispatched"])
        self.assertEqual(self.phones.started, [])
