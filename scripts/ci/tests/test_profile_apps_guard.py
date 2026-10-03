"""Plan 43 T4: the PC can list, switch and manage apps of the phone's profiles, but never create or delete a profile,
never manage Profile A's own apps, and only through fixed root verbs on profiles Cyclone owns."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def read(rel: str) -> str:
    return (MOBILE / rel).read_text(encoding="utf-8")


def test_profile_apps_uses_only_fixed_verbs_and_never_creates_or_deletes():
    source = read("runtime/workspaces/ProfileApps.kt")
    verbs = set(re.findall(r"ProfileSetupPlan\.(\w+)\(", source))
    assert verbs <= {"currentUser", "listProfileApps", "installExisting", "uninstallForProfile", "switchUser", "validProfileName",
                     "validPackageName"}, verbs
    for forbidden in ("removeUser", "createSecondaryUser", "createManagedProfile", "ProfileLifecycle", "ProcessBuilder", "Runtime.getRuntime"):
        assert forbidden not in source, forbidden
    # Every Cyclone profile is re-checked against Android's user list and the registry before anything changes.
    assert "ProfileRecovery.validOwned(user, record.parentUserId, record.secondaryUser)" in source


def test_only_profile_apps_removes_an_app_from_a_profile():
    users = [f.relative_to(MOBILE).as_posix() for f in MOBILE.rglob("*.kt") if "uninstallForProfile(" in f.read_text(encoding="utf-8")]
    assert sorted(users) == ["runtime/workspaces/ProfileApps.kt", "runtime/workspaces/ProfileSetupPlan.kt"]


def test_profile_a_apps_are_the_owners_on_the_phone():
    adapter = read("gateway/GatewayV5ProfilesAdapter.kt")
    assert "Profile A's apps are managed on the phone." in adapter
    plan = read("runtime/workspaces/ProfileSetupPlan.kt")
    assert 'require(userId > 0 && validPackageName(packageName) && packageName != "com.cyclone.mobile")' in plan
