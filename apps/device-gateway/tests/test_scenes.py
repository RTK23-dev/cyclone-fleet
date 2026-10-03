from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from cyclone_device_gateway.desktop_runtime.scenes import SceneError, SceneStore


class SceneStoreTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "scenes.json"

    def test_put_and_get(self):
        store = SceneStore(self.path)
        scene = store.put_scene("morning-check", "Morning check", [
            {"nickname": "Work Phone", "goal": "check email for anything urgent"},
            {"nickname": "Tablet", "goal": "check the calendar for today"},
        ])
        self.assertEqual(scene["name"], "Morning check")
        self.assertEqual(len(scene["steps"]), 2)
        self.assertEqual(store.get_scene("morning-check")["name"], "Morning check")

    def test_duplicate_device_in_same_scene_rejected(self):
        store = SceneStore(self.path)
        with self.assertRaises(SceneError):
            store.put_scene("bad-scene", "Bad scene", [
                {"nickname": "Work Phone", "goal": "do X"},
                {"nickname": "work phone", "goal": "do Y"},  # same device, different case
            ])

    def test_invalid_scene_id_rejected(self):
        store = SceneStore(self.path)
        with self.assertRaises(SceneError):
            store.put_scene("Not A Valid Id!", "name", [{"nickname": "Work Phone", "goal": "x"}])

    def test_empty_steps_rejected(self):
        store = SceneStore(self.path)
        with self.assertRaises(SceneError):
            store.put_scene("empty", "Empty", [])

    def test_persists_across_reload(self):
        store = SceneStore(self.path)
        store.put_scene("s1", "Scene one", [{"nickname": "Work Phone", "goal": "x"}])
        reloaded = SceneStore(self.path)
        self.assertEqual(reloaded.get_scene("s1")["name"], "Scene one")

    def test_delete(self):
        store = SceneStore(self.path)
        store.put_scene("s1", "Scene one", [{"nickname": "Work Phone", "goal": "x"}])
        store.delete_scene("s1")
        with self.assertRaises(SceneError):
            store.get_scene("s1")

    def test_get_missing_scene_raises_not_found(self):
        store = SceneStore(self.path)
        with self.assertRaises(SceneError) as ctx:
            store.get_scene("nonexistent")
        self.assertEqual(ctx.exception.code, "NOT_FOUND")

    def test_resolve_for_dispatch_maps_nicknames_to_device_ids(self):
        store = SceneStore(self.path)
        store.put_scene("morning-check", "Morning check", [
            {"nickname": "Work Phone", "goal": "check email"},
            {"nickname": "Tablet", "goal": "check calendar"},
        ])
        nickname_to_id = {"work phone": "dev_aaa", "tablet": "dev_bbb"}
        resolved, missing = store.resolve_for_dispatch(
            "morning-check", lambda n: nickname_to_id.get(n.casefold()),
        )
        self.assertEqual(missing, [])
        self.assertEqual({r["deviceId"] for r in resolved}, {"dev_aaa", "dev_bbb"})

    def test_resolve_for_dispatch_reports_missing_device_instead_of_dropping_silently(self):
        store = SceneStore(self.path)
        store.put_scene("morning-check", "Morning check", [
            {"nickname": "Work Phone", "goal": "check email"},
            {"nickname": "Old Phone", "goal": "check something"},  # no longer paired
        ])
        nickname_to_id = {"work phone": "dev_aaa"}
        resolved, missing = store.resolve_for_dispatch(
            "morning-check", lambda n: nickname_to_id.get(n.casefold()),
        )
        self.assertEqual(len(resolved), 1)
        self.assertEqual(missing, ["Old Phone"])

    def test_list_scenes(self):
        store = SceneStore(self.path)
        store.put_scene("s1", "Scene one", [{"nickname": "Work Phone", "goal": "x"}])
        store.put_scene("s2", "Scene two", [{"nickname": "Tablet", "goal": "y"}])
        names = {s["name"] for s in store.list_scenes()}
        self.assertEqual(names, {"Scene one", "Scene two"})


if __name__ == "__main__":
    unittest.main()
