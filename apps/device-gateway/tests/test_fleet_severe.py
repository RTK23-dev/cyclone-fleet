"""Reproductions for the open fleet defects. Phone RPC is not involved."""
import tempfile
from pathlib import Path

from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetOrchestrator
from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore


class _Cc:
    def list_tasks(self, status=None, limit=200):
        return []

    def request_tick(self):
        return None


def test_empty_mission_is_swept_on_start():
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "missions.json"
        store = FleetStore(path.with_name("fleet.db"), clock=lambda: 1)
        store.save({"missionId": "flt_ghost", "command": "", "createdAt": 1, "notes": [], "assignments": []})
        store.close()
        fleet = FleetOrchestrator(_Cc(), lambda: [], lambda: {}, path, clock=lambda: 1)
        assert fleet._store.get("flt_ghost") is None
        fleet.close()


def test_a_poisoned_row_does_not_break_the_list():
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "fleet.db"
        store = FleetStore(path, clock=lambda: 1)
        store.save({"missionId": "flt_ok", "command": "ok", "createdAt": 2, "notes": [], "assignments": []})
        store._db.execute("UPDATE fleet_mission SET rows_json = ? WHERE mission_id = ?", ("not-json", "flt_ok"))
        store._db.commit()
        items, _cursor = store.page(10)
        assert items[0]["assignments"] == []
        store.close()


def test_a_corrupt_database_is_quarantined():
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "fleet.db"
        path.write_text("this is not sqlite", encoding="utf-8")
        store = FleetStore(path, clock=lambda: 1)
        assert store.open_error
        store.save({"missionId": "flt_new", "command": "ok", "createdAt": 1, "notes": [], "assignments": []})
        assert store.get("flt_new") is not None
        store.close()
        assert path.with_suffix(".broken").is_file()


def test_full_change_queue_keeps_the_newest():
    with tempfile.TemporaryDirectory() as folder:
        fleet = FleetOrchestrator(_Cc(), lambda: [], lambda: {}, Path(folder) / "missions.json", clock=lambda: 1)
        fleet._change_stop.set()
        for i in range(300):
            fleet.enqueue_task_change({"taskId": f"t{i}", "status": "running"})
        newest = None
        while not fleet._changes.empty():
            newest = fleet._changes.get_nowait()
        assert newest["taskId"] == "t299"
        assert fleet._changes_dropped > 0
        fleet.close()
