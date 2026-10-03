"""Keep background work on (plan 26, A42-1).

The phone's background screens need the Shizuku helper. Without root it stops at every restart; its own "start on
boot" needs one permission that only a computer can grant. This module grants exactly that permission, to exactly
that app, over the paired phone's ADB channel, and reads back that it holds. It is a fixed step the owner starts from
Glass: never a route that takes a command, never exposed to a model.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Protocol

HELPER_PACKAGE = "moe.shizuku.privileged.api"
PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"


class Adb(Protocol):
    def shell(self, *args: str, timeout: float = 15) -> str: ...


@dataclass(frozen=True)
class KeepOnResult:
    ok: bool
    state: str
    message: str

    def public(self) -> dict[str, Any]:
        return {"ok": self.ok, "state": self.state, "message": self.message}


def helper_installed(adb: Adb) -> bool:
    return adb.shell("pm", "path", HELPER_PACKAGE, timeout=10).strip().startswith("package:")


def helper_holds_permission(adb: Adb) -> bool:
    text = adb.shell("dumpsys", "package", HELPER_PACKAGE, timeout=20)
    return re.search(re.escape(PERMISSION) + r": granted=true", text) is not None


def keep_background_on(adb: Adb) -> KeepOnResult:
    """Grant the helper its start-on-boot permission, then prove it holds it. Idempotent."""
    if not helper_installed(adb):
        return KeepOnResult(False, "helper_missing",
                            "Install the Shizuku helper on the phone first (Cyclone → Settings → AI → Background work → Set up).")
    if helper_holds_permission(adb):
        return KeepOnResult(True, "already_on", "Background work already restarts by itself after a phone restart.")
    adb.shell("pm", "grant", HELPER_PACKAGE, PERMISSION, timeout=20)
    if not helper_holds_permission(adb):
        return KeepOnResult(False, "not_granted", "Android did not grant the permission. Check that USB debugging is allowed, then try again.")
    return KeepOnResult(True, "on", "Done. In the Shizuku app, turn on \"Start on boot\" once; after that background work "
                                     "comes back by itself after every restart.")
