from __future__ import annotations

import unittest
from pathlib import Path
import tempfile

from cyclone_device_gateway.desktop_runtime.workspace import FleetWorkspaceStore


class NicknameTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "workspace.json"

    def test_set_and_read_back(self):
        store = FleetWorkspaceStore(self.path)
        result = store.set_nickname("dev_abc123", "Work Phone")
        self.assertEqual(result, {"deviceId": "dev_abc123", "nickname": "Work Phone"})
        self.assertEqual(store.nickname_map().get("dev_abc123"), "Work Phone")

    def test_resolve_is_case_insensitive(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        self.assertEqual(store.resolve_nickname("work phone"), "dev_abc123")
        self.assertEqual(store.resolve_nickname("WORK PHONE"), "dev_abc123")
        self.assertIsNone(store.resolve_nickname("nonexistent"))

    def test_duplicate_nickname_rejected(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        with self.assertRaises(ValueError):
            store.set_nickname("dev_def456", "work phone")  # case-insensitive collision

    def test_renaming_own_device_is_fine(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        store.set_nickname("dev_abc123", "Work Phone")  # same name again, not a collision with itself
        store.set_nickname("dev_abc123", "Old Work Phone")
        self.assertEqual(store.nickname_map().get("dev_abc123"), "Old Work Phone")

    def test_empty_nickname_clears_it(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        store.set_nickname("dev_abc123", "  ")
        self.assertIsNone(store.nickname_map().get("dev_abc123"))

    def test_too_long_nickname_rejected(self):
        store = FleetWorkspaceStore(self.path)
        with self.assertRaises(ValueError):
            store.set_nickname("dev_abc123", "x" * 41)

    def test_persists_across_reload(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        store.set_nickname("dev_def456", "Tablet")
        reloaded = FleetWorkspaceStore(self.path)
        self.assertEqual(reloaded.nickname_map(), {"dev_abc123": "Work Phone", "dev_def456": "Tablet"})

    def test_old_workspace_file_without_nicknames_key_still_loads(self):
        self.path.write_text('{"schemaVersion": 1, "groups": [], "selectedDeviceIds": []}', encoding="utf-8")
        store = FleetWorkspaceStore(self.path)
        self.assertEqual(store.nickname_map(), {})
        # and groups/selection from the old file are unaffected
        self.assertEqual(store.public()["groups"], [])

    def test_public_includes_nicknames(self):
        store = FleetWorkspaceStore(self.path)
        store.set_nickname("dev_abc123", "Work Phone")
        self.assertEqual(store.public()["nicknames"], {"dev_abc123": "Work Phone"})


if __name__ == "__main__":
    unittest.main()
