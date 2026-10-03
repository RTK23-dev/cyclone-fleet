"""Regression checks for bugs found in review of the alpha.99 fleet layer. Each one failed before its fix.

Real Command Center, real SQLite; only the phone RPC is faked (the phone is hardware).
"""
from __future__ import annotations

import csv
import io
import sqlite3
import tempfile
import unittest
from pathlib import Path

from tests import _fastapi_stub

_fastapi_stub.install()

from cyclone_device_gateway.command.center import CommandCenter  # noqa: E402
from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetError, FleetOrchestrator  # noqa: E402
from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore  # noqa: E402
from tests.test_fleet_orchestrator import Clock, FakePhones, paired  # noqa: E402

ASSIGN = [{"deviceId": "dev_a", "label": "Work Phone", "goal": "open Gmail"},
          {"deviceId": "dev_b", "label": "Tablet", "goal": "open Maps"}]


class Base(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        self.phones, self.clock = FakePhones(), Clock()
        self.devices = [paired("dev_a", "CPH2717"), paired("dev_b", "SM-X710")]
        self.nicknames = {"dev_a": "Work Phone", "dev_b": "Tablet"}
        self.boot()

    def boot(self) -> None:
        self.center = CommandCenter(self.dir / "cc" / "command.db", self.phones, lambda: list(self.devices), clock=self.clock)
        self.center.request_tick = self.center.tick          # no loop thread here: a requested tick runs inline
        self.fleet = FleetOrchestrator(self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
                                       self.dir / "fleet" / "missions.json", clock=self.clock)

    def reboot(self) -> None:
        self.fleet._store.close()
        self.center.stop()
        self.boot()

    def tearDown(self) -> None:
        self.center.stop()

    def tick(self, ms: int = 0) -> None:
        self.clock.now += ms
        self.center.tick()

    @staticmethod
    def states(m): return {p["label"]: p["state"] for p in m["phones"]}


class CrashTests(Base):
    def test_a_crash_between_reserve_and_commit_does_not_swallow_the_retry(self):
        rid = "crashtest-0001"
        self.fleet._reserve(self.fleet._mission_id(rid), "open Maps on Tablet", [])   # the gateway dies here
        self.reboot()
        result = self.fleet.run_command("open Maps on Tablet", client_request_id=rid)
        self.assertEqual(len(result["mission"]["phones"]), 1, "the retry must really create the work")
        self.assertEqual(self.phones.started, [("dev_b", "Open Maps")])

    def test_an_empty_ghost_mission_from_an_older_crash_is_swept_at_startup(self):
        db = sqlite3.connect(self.fleet._store._path)
        db.execute("INSERT INTO fleet_mission(mission_id, command, created_at, notes_json, rows_json) VALUES ('flt_ghost0001','x',1,'[]','[]')")
        db.commit(); db.close()
        self.reboot()
        self.assertEqual(self.fleet.missions(), [])
        self.assertEqual(self.fleet._store.count(), 0)


class RolloutTests(Base):
    def test_a_canary_rollout_works_and_the_mission_list_stays_healthy(self):
        result = self.fleet.rollout(ASSIGN, canary=1)
        self.assertEqual(result["remaining"], 1)
        self.assertEqual(self.phones.started, [("dev_a", "open Gmail")])            # only the canary
        self.assertEqual(self.fleet.missions()[0]["phones"][1]["state"], "QUEUED")  # held back, not "failed"
        self.assertIn("canary", self.fleet.queue()["waiting"][0]["reason"].lower()) if self.fleet.queue()["waiting"] else None
        self.fleet.health()

    def _canary_done(self):
        mid = self.fleet.rollout(ASSIGN, canary=1)["mission"]["missionId"]
        self.phones.finish(self.phones.mission_for("dev_a", "open Gmail")); self.tick()
        return mid

    def test_continue_starts_the_rest_and_binds_their_tasks_to_the_mission(self):
        mid = self._canary_done()
        self.assertEqual(self.fleet.continue_rollout(mid)["started"], 1)
        task = self.fleet._lookup(mid)["assignments"][1]["taskId"]
        self.assertEqual(self.fleet._store.mission_of_task(task), mid)
        self.assertEqual(self.fleet.continue_rollout(mid)["started"], 0)            # not twice

    def test_continue_obeys_pause(self):
        mid = self._canary_done()
        self.fleet.set_paused(True)
        with self.assertRaises(FleetError) as ctx:
            self.fleet.continue_rollout(mid)
        self.assertEqual(ctx.exception.code, "FLEET_PAUSED")
        self.assertEqual(len(self.phones.started), 1)

    def test_continue_obeys_do_not_target(self):
        mid = self._canary_done()
        self.fleet.set_excluded("dev_b", True)
        out = self.fleet.continue_rollout(mid)
        self.assertEqual(out["started"], 0)
        self.assertEqual(len(self.phones.started), 1)
        self.assertIn("do-not-target", out["mission"]["phones"][1]["cause"])

    def test_a_failed_canary_does_not_start_the_rest(self):
        mid = self.fleet.rollout(ASSIGN, canary=1)["mission"]["missionId"]
        self.phones.finish(self.phones.mission_for("dev_a", "open Gmail"), status="failed", summary="x"); self.tick()
        with self.assertRaises(FleetError) as ctx:
            self.fleet.continue_rollout(mid)
        self.assertEqual(ctx.exception.code, "CANARY_FAILED")


class RetryTests(Base):
    def test_a_retried_task_is_bound_to_its_mission_and_survives_a_refusal_in_the_batch(self):
        m = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        for dev, goal in (("dev_a", "Open Gmail"), ("dev_b", "Open Maps")):
            self.phones.finish(self.phones.mission_for(dev, goal), status="failed", summary="x")
        self.tick()
        real = self.center.create_task
        calls = []
        def flaky(body):
            calls.append(body["deviceId"])
            if body["deviceId"] == "dev_a":
                raise ValueError("The Command Center refused this one.")
            return real(body)
        self.center.create_task = flaky
        out = self.fleet.retry_failed(m["missionId"])
        self.assertEqual(out["retried"], 1)                                   # dev_b still retried
        self.assertEqual(calls, ["dev_a", "dev_b"])
        rows = {r["deviceId"]: r for r in self.fleet._store.get(m["missionId"])["assignments"]}   # persisted, not just in memory
        self.assertEqual(rows["dev_b"]["retries"], 1)
        self.assertEqual(self.fleet._store.mission_of_task(rows["dev_b"]["taskId"]), m["missionId"])
        self.assertIn("refused", rows["dev_a"]["error"])


class QueueTests(Base):
    def test_a_later_task_never_pushes_the_front_task_back(self):
        first = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        self.fleet.run_command("open Maps on Work Phone", confirm=True)
        snap = self.fleet.mission(first["missionId"])
        self.assertEqual(self.states(snap), {"Work Phone": "RUNNING"})
        self.assertEqual(snap["phones"][0]["hint"], "")

    def test_the_second_task_says_it_is_queued_behind_one(self):
        self.fleet.run_command("open Gmail on Work Phone")
        second = self.fleet.run_command("open Maps on Work Phone", confirm=True)["mission"]
        self.assertEqual(self.states(second), {"Work Phone": "QUEUED"})
        self.assertEqual(second["phones"][0]["hint"], "Queued behind 1.")

    def test_one_poll_costs_one_task_query_not_one_per_phone(self):
        for i in range(6):
            self.fleet.run_command(f"open App{i} on Tablet", confirm=True)
        calls = []
        real = self.center.list_tasks
        self.center.list_tasks = lambda *a, **k: (calls.append(1), real(*a, **k))[1]
        self.fleet.missions(limit=50)
        self.assertLessEqual(len(calls), 2)


class ExportTests(Base):
    def test_csv_neutralises_formulas_and_quotes_commas_and_newlines(self):
        m = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), summary='=HYPERLINK("http://evil","x"),y\nz'); self.tick()
        rows = list(csv.reader(io.StringIO(self.fleet.export_csv(m["missionId"]))))
        self.assertEqual(len(rows), 2)                                         # the newline did not make a third row
        self.assertEqual(len(rows[1]), 5)                                      # the comma did not make a sixth column
        self.assertTrue(rows[1][4].startswith("'="))                           # a spreadsheet will not run it


class StoreTests(unittest.TestCase):
    def test_trim_survives_a_huge_protect_set_and_removes_task_bindings_of_deleted_missions(self):
        store = FleetStore(Path(tempfile.mkdtemp()) / "f.db", max_missions=10, retention_days=0, clock=lambda: 10_000_000)
        keep = set()
        for i in range(3000):
            store.save({"missionId": f"flt_{i:06d}", "command": "c", "createdAt": 1000 + i, "notes": [], "assignments": [{"deviceId": "d"}]})
            store.bind_task(f"tsk_{i}", f"flt_{i:06d}")
            if i >= 5:
                keep.add(f"flt_{i:06d}")
        deleted = store.trim(protect_ids=keep)                                 # 2995 ids: far past SQLite's 999-variable limit
        self.assertEqual(deleted, 5)
        self.assertEqual(store.mission_of_task("tsk_0"), "")                   # its binding went with it
        self.assertEqual(store.mission_of_task("tsk_2999"), "flt_002999")

    def test_trim_is_skipped_when_the_clock_jumps_backwards(self):
        now = [10_000_000]
        store = FleetStore(Path(tempfile.mkdtemp()) / "f.db", max_missions=1, retention_days=1, clock=lambda: now[0])
        for i in range(3):
            store.save({"missionId": f"flt_{i}", "command": "c", "createdAt": 9_000_000 + i, "notes": [], "assignments": [{"deviceId": "d"}]})
        now[0] = 1_000
        self.assertEqual(store.trim(), 0)
        self.assertEqual(store.count(), 3)


class StopTests(Base):
    def test_stop_fleet_missions_leaves_other_command_center_tasks_alone(self):
        self.fleet.run_command("open Gmail on Work Phone")
        other = self.center.create_task({"title": "direct", "goal": "open Clock", "deviceId": "dev_b", "requestId": "direct-task-0001"})
        self.tick()
        out = self.fleet.stop_fleet_missions()
        self.assertEqual(out, {"stopped": 1, "scope": "fleet"})
        self.assertNotEqual(self.center.get_task(other["id"])["status"], "cancelled")


if __name__ == "__main__":
    unittest.main()
