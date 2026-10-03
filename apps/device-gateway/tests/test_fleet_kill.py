"""A real process kill. The phone RPC is not involved. The child is the gateway stand-in."""
import os
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path


CHILD = textwrap.dedent(
    """
    import time
    from pathlib import Path
    from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore
    path = Path(sys.argv[1])
    store = FleetStore(path, clock=lambda: 1)
    store.save({"missionId": "flt_kill", "command": "open mail", "createdAt": 1, "notes": [], "assignments": [{"deviceId": "dev_a", "goal": "open mail", "taskId": "task-1"}]})
    store.bind_task("task-1", "flt_kill")
    path.with_suffix(".ready").write_text("1", encoding="utf-8")
    time.sleep(30)
    """
)


class KillRestartTests(unittest.TestCase):
    def test_kill_dash_9_keeps_the_mission_and_does_not_duplicate_it(self):
        with tempfile.TemporaryDirectory() as folder:
            db = Path(folder) / "fleet.db"
            ready = db.with_suffix(".ready")
            child = subprocess.Popen(
                [sys.executable, "-c", "import sys\n" + CHILD, str(db)],
                cwd=str(Path(__file__).resolve().parents[1]),
                env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parents[1])},
            )
            for _ in range(50):
                if ready.is_file():
                    break
                time.sleep(0.05)
            self.assertTrue(ready.is_file())
            os.kill(child.pid, signal.SIGKILL)
            child.wait(timeout=5)
            from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore
            store = FleetStore(db, clock=lambda: 2)
            mission = store.get("flt_kill")
            self.assertIsNotNone(mission)
            self.assertEqual(store.mission_of_task("task-1"), "flt_kill")
            store.save(mission)
            self.assertEqual(store.mission_of_task("task-1"), "flt_kill")
            store.close()
