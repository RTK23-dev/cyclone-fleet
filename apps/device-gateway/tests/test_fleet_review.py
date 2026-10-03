"""Fleet store, nicknames, and sentence splits. No phone."""
import tempfile
from pathlib import Path

from cyclone_device_gateway.desktop_runtime.fleet_command import KnownDevice, split_command
from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore
from cyclone_device_gateway.desktop_runtime.workspace import FleetWorkspaceStore


def test_nickname_round_trip_and_unique():
    with tempfile.TemporaryDirectory() as folder:
        store = FleetWorkspaceStore(Path(folder) / "workspace.json")
        store.set_nickname("dev_a", "Work Phone")
        assert store.nickname_map() == {"dev_a": "Work Phone"}
        assert store.resolve_nickname("work phone") == "dev_a"
        try:
            store.set_nickname("dev_b", "Work Phone")
        except ValueError as exc:
            assert "already" in str(exc)
        else:
            raise AssertionError("duplicate nickname was accepted")
        again = FleetWorkspaceStore(Path(folder) / "workspace.json")
        assert again.nickname_map()["dev_a"] == "Work Phone"


def test_store_pages_and_task_index():
    with tempfile.TemporaryDirectory() as folder:
        store = FleetStore(Path(folder) / "fleet.db", clock=lambda: 1_000)
        store.save({"missionId": "flt_a", "command": "a", "createdAt": 2, "notes": [], "assignments": []})
        store.save({"missionId": "flt_b", "command": "b", "createdAt": 2, "notes": [], "assignments": []})
        store.bind_task("task-9", "flt_b")
        assert store.mission_of_task("task-9") == "flt_b"
        page, cursor = store.page(1)
        assert len(page) == 1
        assert cursor


def test_split_does_not_guess():
    phones = [
        KnownDevice("dev_a", "Work Phone", ("Work Phone",)),
        KnownDevice("dev_b", "Tablet", ("Tablet",)),
    ]
    plan = split_command("open mail on Work Phone, open camera on Tablet", phones, [])
    assert plan.ok
    assert {item.device_id for item in plan.assignments} == {"dev_a", "dev_b"}
    ambiguous = split_command("open mail", phones, [])
    assert not ambiguous.ok
