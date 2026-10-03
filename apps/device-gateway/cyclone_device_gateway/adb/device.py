from __future__ import annotations

from dataclasses import asdict
import re

from .client import ADBClient


CYCLONE_PACKAGE = "com.cyclone.mobile"


def _prop(adb: ADBClient, name: str) -> str:
    return adb.shell("getprop", name).strip()


def collect_device_status(adb: ADBClient, requested_serial: str | None = None) -> dict:
    d = adb.select_device(requested_serial)
    size = adb.shell("wm", "size").strip()
    orientation_raw = adb.shell("dumpsys", "input").strip()
    match = re.search(r"SurfaceOrientation:\s*(\d+)", orientation_raw)
    orientation = int(match.group(1)) if match else None

    try:
        root = "uid=0" in adb.shell("su", "-c", "id", timeout=5)
    except Exception:
        root = False

    try:
        cyclone_path = adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=5).strip()
        cyclone_installed = cyclone_path.startswith("package:")
    except Exception:
        cyclone_path = ""
        cyclone_installed = False

    return {
        **asdict(d),
        "manufacturer": _prop(adb, "ro.product.manufacturer"),
        "android_version": _prop(adb, "ro.build.version.release"),
        "sdk": _prop(adb, "ro.build.version.sdk"),
        "screen_resolution": size.split(":", 1)[-1].strip() if ":" in size else size,
        "orientation": orientation,
        "root_available": root,
        "cyclone_package": CYCLONE_PACKAGE,
        "cyclone_app_installed": cyclone_installed,
        "cyclone_package_path": cyclone_path or None,
    }
