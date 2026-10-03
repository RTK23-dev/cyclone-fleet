"""Plan 40 P1: deleting a profile is the owner's own act, from the main profile, always after an automatic backup."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(path: str) -> str:
    return (MOBILE / path).read_text(encoding="utf-8")


class ProfileLifecycleGuard(unittest.TestCase):
    def test_only_the_lifecycle_removes_users(self):
        users = []
        for file in MOBILE.rglob("*.kt"):
            text = file.read_text(encoding="utf-8")
            if "ProfileSetupPlan.removeUser(" in text or "\"remove-user\"" in text:
                users.append(file.relative_to(MOBILE).as_posix())
        self.assertEqual(sorted(users), ["runtime/workspaces/ProfileLifecycle.kt", "runtime/workspaces/ProfileSetupPlan.kt"])

    def test_delete_is_guarded_backed_up_and_only_from_the_trash(self):
        life = read("runtime/workspaces/ProfileLifecycle.kt")
        delete = life[life.index("fun deleteNow("):life.index("fun emptyTrash(")]
        order = [delete.index(s) for s in ("guarded(context, id)", "check(record.inTrash)", "backup(context, record, onProgress)",
                                           "ProfileSetupPlan.removeUser(user)")]
        self.assertEqual(order, sorted(order))
        # A failed backup stops the deletion.
        self.assertIn(".getOrElse { error(", delete)
        trash = read("runtime/workspaces/ProfileTrash.kt")
        for rule in ("taskRunning ->", "currentUserId != mainUserId", "target == mainUserId || target == 0",
                     "ProfileRecovery.validOwned(user, record.parentUserId, record.secondaryUser)"):
            self.assertIn(rule, trash)
        self.assertIn("const val TRASH_DAYS = 7", trash)

    def test_no_model_or_gateway_path_reaches_the_lifecycle(self):
        for file in MOBILE.rglob("*.kt"):
            rel = file.relative_to(MOBILE).as_posix()
            if rel.startswith(("agent/", "mind/", "gateway/", "ai/", "automation/")):
                text = file.read_text(encoding="utf-8")
                self.assertNotRegex(text, r"ProfileLifecycle\.|ProfileRegistryStore\.(markRemoved|drop)\(", rel)


if __name__ == "__main__":
    unittest.main()
