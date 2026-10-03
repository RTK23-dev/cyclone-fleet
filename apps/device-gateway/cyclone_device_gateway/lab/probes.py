"""Typed, fixed phone probes for Cyclone Lab.

The lab scores a mission from what the phone actually shows, not from what the model says. These probes read that
state over ADB. They are the lab's own instruments: never exposed as a route or to a model, every command is a fixed
argument list, and every variable part (package, setting key, value) is checked against a strict pattern first,
because ADB joins shell arguments into one remote command line. The few writes (setup/cleanup) are an allowlist of
reversible phone settings plus one lab-owned file, and every setting the lab changes is restored afterwards.
"""
from __future__ import annotations

import time

import re
from dataclasses import dataclass, field
from typing import Protocol

from ..uiautomator.client import normalize_xml

PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SETTING_VALUE = re.compile(r"^-?[0-9]{1,9}(?:\.[0-9]{1,3})?$")
CYCLONE_PACKAGE = "com.cyclone.mobile"
LAB_FILE = "/sdcard/Download/cyclone-lab-note.txt"

#: Settings the lab may read. Keys are fixed; nothing outside this map reaches `settings get`.
READABLE_SETTINGS: dict[tuple[str, str], str] = {
    ("system", "accelerometer_rotation"): "auto-rotate",
    ("system", "screen_off_timeout"): "screen timeout (ms)",
    ("system", "screen_brightness_mode"): "adaptive brightness",
    ("system", "font_scale"): "font size",
    ("system", "haptic_feedback_enabled"): "touch vibration",
    ("global", "zen_mode"): "do not disturb",
    ("global", "bluetooth_on"): "bluetooth",
    ("global", "wifi_on"): "wi-fi",
    ("global", "airplane_mode_on"): "airplane mode",
    ("secure", "ui_night_mode"): "dark theme",
}
#: Settings the lab may set up (and always restores). A subset of the readable ones, all harmless and reversible.
WRITABLE_SETTINGS = frozenset({
    ("system", "accelerometer_rotation"),
    ("system", "screen_off_timeout"),
    ("system", "screen_brightness_mode"),
    ("system", "font_scale"),
    ("system", "haptic_feedback_enabled"),
})
#: Build properties the lab may read to check an answer.
READABLE_PROPS = frozenset({"ro.build.version.release", "ro.product.model", "ro.product.manufacturer"})


CLOCK_PACKAGES = ("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock",
                  "com.oneplus.deskclock", "com.miui.clock")


class ProbeError(RuntimeError):
    pass


class Adb(Protocol):
    def shell(self, *args: str, timeout: float = 15) -> str: ...
    def exec_out(self, *args: str, timeout: float = 15) -> bytes: ...


@dataclass
class ScreenText:
    """Visible text of the foreground app, without Cyclone's own overlay (its summary must not score itself)."""
    package: str | None
    texts: list[str] = field(default_factory=list)

    def joined(self) -> str:
        return "\n".join(self.texts)


def _package(value: str) -> str:
    if not isinstance(value, str) or not PACKAGE.match(value) or len(value) > 120:
        raise ProbeError("package name is malformed")
    return value


def _setting(namespace: str, key: str, *, write: bool = False) -> tuple[str, str]:
    pair = (namespace, key)
    if pair not in READABLE_SETTINGS or (write and pair not in WRITABLE_SETTINGS):
        raise ProbeError(f"setting {namespace}/{key} is not on the lab allowlist")
    return pair


class PhoneProbe:
    def __init__(self, adb: Adb):
        self.adb = adb

    # ---- reads --------------------------------------------------------------------------------------------------

    def foreground_package(self) -> str | None:
        text = self.adb.shell("dumpsys", "activity", "activities", timeout=15)
        for pattern in (r"topResumedActivity=.*? ([A-Za-z0-9_.]+)/", r"mResumedActivity: .*? ([A-Za-z0-9_.]+)/", r"ResumedActivity: .*? ([A-Za-z0-9_.]+)/"):
            match = re.search(pattern, text)
            if match:
                return match.group(1)
        return None

    def home_package(self) -> str | None:
        text = self.adb.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                              "-c", "android.intent.category.HOME", timeout=10)
        match = re.search(r"^([A-Za-z0-9_.]+)/", text.strip().splitlines()[-1] if text.strip() else "")
        return match.group(1) if match else None

    def screen_text(self) -> ScreenText:
        # Alpha 91: never read a stale or half-written dump. The old file is removed first, the dump must say it wrote
        # the file, and one retry follows a failed dump (uiautomator gives up while an animation keeps the screen busy).
        last = ""
        for attempt in range(2):
            self.adb.shell("rm", "-f", "/sdcard/cyclone_lab_uia.xml", timeout=10)
            last = str(self.adb.shell("uiautomator", "dump", "/sdcard/cyclone_lab_uia.xml", timeout=25))
            if "dumped" in last.lower():
                break
            if attempt == 0:
                time.sleep(1.5)
        else:
            reason = "the screen never went idle (an animation keeps it busy)" if "idle" in last.lower() else "uiautomator wrote no dump"
            raise ProbeError(f"screen unavailable: {reason}")
        xml = self.adb.exec_out("cat", "/sdcard/cyclone_lab_uia.xml", timeout=10).decode("utf-8", "replace")
        if "<hierarchy" not in xml:
            raise ProbeError("screen unavailable: the dump was empty")
        nodes = normalize_xml(xml)["nodes"]
        texts: list[str] = []
        packages: dict[str, int] = {}
        for node in nodes:
            if node["package"] == CYCLONE_PACKAGE:
                continue
            packages[node["package"]] = packages.get(node["package"], 0) + 1
            for value in (node["text"], node["content_desc"]):
                if value and value.strip():
                    texts.append(value.strip())
        main = max(packages, key=packages.get) if packages else None
        return ScreenText(main, texts)

    def setting(self, namespace: str, key: str) -> str | None:
        ns, name = _setting(namespace, key)
        value = self.adb.shell("settings", "get", ns, name, timeout=10).strip()
        return None if value in {"", "null"} else value

    def prop(self, name: str) -> str:
        if name not in READABLE_PROPS:
            raise ProbeError("property is not on the lab allowlist")
        return self.adb.shell("getprop", name, timeout=10).strip()

    def battery_level(self) -> int | None:
        match = re.search(r"^\s*level:\s*(\d+)", self.adb.shell("dumpsys", "battery", timeout=10), re.MULTILINE)
        return int(match.group(1)) if match else None

    def wifi_ssid(self) -> str | None:
        match = re.search(r'connected to "([^"]{1,64})"', self.adb.shell("cmd", "wifi", "status", timeout=10))
        return match.group(1) if match else None

    def google_accounts(self) -> list[str]:
        text = self.adb.shell("dumpsys", "account", timeout=15)
        return sorted(set(re.findall(r"Account \{name=([^,\s]{3,120}), type=com\.google\}", text)))

    def timer_running(self) -> bool | None:
        """Alpha 91: is a Clock timer counting down? Read from Clock's own notification, so a timer set by a tool (with
        Clock never in front) is seen too. None when the notification list can't be read."""
        text = str(self.adb.shell("dumpsys", "notification", "--noredact", timeout=15))
        if "NotificationRecord" not in text:
            return None
        for block in text.split("NotificationRecord(")[1:]:
            head = block[:4000]
            if not any(package in head[:400] for package in CLOCK_PACKAGES):
                continue
            lower = head.lower()
            if "timer" in lower and ("chronometer" in lower or "running" in lower or "countdown" in lower) \
                    and "time's up" not in lower and "paused" not in lower:
                return True
        return False

    def night_mode(self) -> bool | None:
        text = self.adb.shell("cmd", "uimode", "night", timeout=10).lower()
        return True if "yes" in text else False if "no" in text else None

    def package_installed(self, package: str) -> bool:
        try:
            return self.adb.shell("pm", "path", _package(package), timeout=10).strip().startswith("package:")
        except Exception:
            return False

    def lab_file_exists(self) -> bool:
        return self.adb.shell("ls", LAB_FILE, timeout=10).strip() == LAB_FILE

    def locked(self) -> bool:
        text = self.adb.shell("dumpsys", "window", timeout=15)
        return bool(re.search(r"(mDreamingLockscreen=true|isStatusBarKeyguard=true|mShowingLockscreen=true|KeyguardShowing=true)", text))

    def screen_on(self) -> bool:
        text = self.adb.shell("dumpsys", "power", timeout=10)
        return bool(re.search(r"(mWakefulness=Awake|Display Power: state=ON)", text))

    # ---- setup / cleanup (allowlisted, reversible) -----------------------------------------------------------------

    def put_setting(self, namespace: str, key: str, value: str) -> None:
        ns, name = _setting(namespace, key, write=True)
        if not isinstance(value, str) or not SETTING_VALUE.match(value):
            raise ProbeError("setting value must be a plain number")
        self.adb.shell("settings", "put", ns, name, value, timeout=10)

    def restore_setting(self, namespace: str, key: str, value: str | None) -> None:
        ns, name = _setting(namespace, key, write=True)
        if value is None:
            self.adb.shell("settings", "delete", ns, name, timeout=10)
        else:
            self.put_setting(ns, name, value)

    def home(self) -> None:
        self.adb.shell("input", "keyevent", "KEYCODE_HOME", timeout=10)

    def wake(self) -> None:
        self.adb.shell("input", "keyevent", "KEYCODE_WAKEUP", timeout=10)

    def launch(self, package: str) -> None:
        """Open an app's launcher screen (a fixed monkey launch of the LAUNCHER category; the package is checked)."""
        self.adb.shell("monkey", "-p", _package(package), "-c", "android.intent.category.LAUNCHER", "1", timeout=15)

    def force_stop(self, package: str) -> None:
        if _package(package) == CYCLONE_PACKAGE:
            raise ProbeError("the lab never stops Cyclone itself")
        self.adb.shell("am", "force-stop", package, timeout=10)

    def set_night_mode(self, on: bool) -> None:
        self.adb.shell("cmd", "uimode", "night", "yes" if on else "no", timeout=10)

    def set_dnd(self, on: bool) -> None:
        self.adb.shell("cmd", "notification", "set_dnd", "priority" if on else "off", timeout=10)

    def create_lab_file(self) -> None:
        self.adb.shell("touch", LAB_FILE, timeout=10)

    def remove_lab_file(self) -> None:
        self.adb.shell("rm", "-f", LAB_FILE, timeout=10)
