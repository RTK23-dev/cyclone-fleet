"""Android's install results in plain words: one title, one sentence, one thing to do. Pure functions.

`adb install` prints `Success`, or `adb: failed to install x.apk: Failure [INSTALL_FAILED_…: detail]` on stderr.
The code is kept for developers; the owner sees the words.
"""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass

_CODE = re.compile(r"\b(INSTALL_[A-Z_]+)\b")


@dataclass(frozen=True)
class InstallOutcome:
    ok: bool
    code: str
    title: str
    message: str
    action: str | None
    retryable: bool

    def to_dict(self) -> dict:
        return asdict(self)


SUCCESS = InstallOutcome(True, "SUCCESS", "Updated", "Cyclone on the phone is up to date.", None, False)

_KNOWN: dict[str, InstallOutcome] = {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE": InstallOutcome(
        False, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "A different build is on the phone",
        "The Cyclone on this phone was signed by someone else (a test or developer build), so Android won't replace it.",
        "Remove Cyclone from the phone, then press Update again. Removing it clears its data on the phone.", False),
    "INSTALL_FAILED_VERSION_DOWNGRADE": InstallOutcome(
        False, "INSTALL_FAILED_VERSION_DOWNGRADE", "The phone is ahead of this PC",
        "The phone already has a newer Cyclone than this PC offers.",
        "Update this PC: type cyclone update.", False),
    "INSTALL_FAILED_INSUFFICIENT_STORAGE": InstallOutcome(
        False, "INSTALL_FAILED_INSUFFICIENT_STORAGE", "The phone is out of space",
        "Android needs more free space to install the update.",
        "Free up about 500 MB on the phone, then try again.", True),
    "INSTALL_FAILED_USER_RESTRICTED": InstallOutcome(
        False, "INSTALL_FAILED_USER_RESTRICTED", "The phone didn't allow the install",
        "The phone blocked installing over USB, or the prompt on the phone was declined.",
        "Unlock the phone and allow the install. Some phones need \"Install via USB\" turned on in Developer options.", True),
    "INSTALL_FAILED_ABORTED": InstallOutcome(
        False, "INSTALL_FAILED_ABORTED", "The install was cancelled",
        "The install stopped before it finished.", "Unlock the phone and try again.", True),
    "INSTALL_FAILED_VERIFICATION_FAILURE": InstallOutcome(
        False, "INSTALL_FAILED_VERIFICATION_FAILURE", "The phone's app check stopped it",
        "Google Play Protect or the phone's verifier blocked the install.",
        "Allow the install in the Play Protect message on the phone, then try again.", True),
    "INSTALL_FAILED_OLDER_SDK": InstallOutcome(
        False, "INSTALL_FAILED_OLDER_SDK", "This phone's Android is too old",
        "Cyclone needs Android 13 or newer.", None, False),
    "INSTALL_FAILED_NO_MATCHING_ABIS": InstallOutcome(
        False, "INSTALL_FAILED_NO_MATCHING_ABIS", "This phone isn't supported",
        "Cyclone runs on 64-bit ARM phones.", None, False),
    "INSTALL_FAILED_INVALID_APK": InstallOutcome(
        False, "INSTALL_FAILED_INVALID_APK", "The download was damaged",
        "Android couldn't read the downloaded app.", "Try again: Cyclone downloads it fresh.", True),
}

_UNREACHABLE = InstallOutcome(
    False, "PHONE_UNREACHABLE", "The phone isn't reachable over USB",
    "The USB connection dropped or USB debugging isn't allowed right now.",
    "Plug the phone in, unlock it, allow USB debugging, then try again.", True)

_TIMEOUT = InstallOutcome(
    False, "INSTALL_TIMED_OUT", "The install took too long",
    "The phone didn't finish installing in time.", "Unlock the phone, keep it plugged in, and try again.", True)


def read_install_output(text: str) -> InstallOutcome:
    """What `adb install` said (its stdout on success, its stderr or error text on failure)."""
    value = (text or "").strip()
    code_match = _CODE.search(value)
    if code_match:
        code = code_match.group(1)
        if code in _KNOWN:
            return _KNOWN[code]
        if code.startswith("INSTALL_PARSE_FAILED"):
            return InstallOutcome(False, code, "The download was damaged", "Android couldn't read the downloaded app.",
                                  "Try again: Cyclone downloads it fresh.", True)
        return InstallOutcome(False, code, "Android didn't install the update",
                              "Android refused the update. The code below says why.", "Try again, or send the details to support.", True)
    lowered = value.lower()
    if lowered.endswith("success") or "\nsuccess" in lowered or lowered == "success":
        return SUCCESS
    if "timed out" in lowered:
        return _TIMEOUT
    if any(word in lowered for word in ("device offline", "no devices", "not found", "unauthorized", "device '", "closed")):
        return _UNREACHABLE
    return InstallOutcome(False, "INSTALL_FAILED_UNKNOWN", "Android didn't install the update",
                          "The install didn't finish.", "Try again, or send the details to support.", True)
