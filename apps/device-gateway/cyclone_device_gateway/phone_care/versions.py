"""Which Cyclone the phone has, compared with this PC's release. Pure functions."""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..terminal.release import version_key

PACKAGE = "com.cyclone.mobile"
_NAME = re.compile(r"^\s*versionName=(\S+)", re.MULTILINE)
_CODE = re.compile(r"^\s*versionCode=(\d+)", re.MULTILINE)


@dataclass(frozen=True)
class InstalledApp:
    version_name: str
    version_code: int | None


def parse_dumpsys_package(text: str) -> InstalledApp | None:
    """The installed Cyclone from `dumpsys package com.cyclone.mobile`, or None when it isn't installed."""
    if not text or "Unable to find package" in text:
        return None
    name = _NAME.search(text)
    if not name:
        return None
    code = _CODE.search(text)
    return InstalledApp(name.group(1).strip(), int(code.group(1)) if code else None)


def compare(phone: str | None, pc: str) -> str:
    """'missing', 'same', 'older' (the phone needs an update), 'newer' (this PC does), or 'unknown'."""
    if phone is None:
        return "missing"
    a, b = version_key(phone), version_key(pc)
    if a is None or b is None:
        return "unknown"
    if a == b:
        return "same"
    return "older" if a < b else "newer"
