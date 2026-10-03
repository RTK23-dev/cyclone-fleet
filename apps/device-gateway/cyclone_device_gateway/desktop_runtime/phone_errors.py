"""One reading of a phone that didn't answer, for every PC path that talks to it (alpha 87).

A phone that is gone and a phone whose Cyclone app is busy used to look the same: both became DEVICE_DISCONNECTED,
so a few seconds of work on the phone read as a lost cable. Now:
- the phone refusing new work while it finishes old work (`PHONE_APP_BUSY`), or not answering within the bridge
  timeout on a live connection, is PHONE_APP_BUSY, retryable;
- a transport that is really gone (refused, reset, closed without a word) stays DEVICE_DISCONNECTED.
"""
from __future__ import annotations

from ..cyclone_bridge.client import BridgeBusyError, BridgeError
from .models import DesktopRuntimeError, RuntimeErrorCode

BUSY_MESSAGE = "Cyclone on the phone is busy. Try again in a moment."
DISCONNECTED_MESSAGE = "Phone disconnected from Cyclone Gateway."

# Phone answers that mean "not now", never "never": callers may retry them.
TRANSIENT_CODES = frozenset({
    "PHONE_APP_BUSY", "STALE_OBSERVATION", "FOREGROUND_REQUIRED", "PHONE_LOCKED",
    "OBSERVATION_CHANGED_DURING_CAPTURE", "DISPLAY_GONE",
})


def busy() -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PHONE_APP_BUSY, BUSY_MESSAGE, retryable=True)


def transport(exc: BridgeError) -> DesktopRuntimeError:
    """The error for a bridge that failed below the operation level (no phone answer at all)."""
    if isinstance(exc, BridgeBusyError):
        return busy()
    return DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, DISCONNECTED_MESSAGE, retryable=True)


def retryable(code: str) -> bool:
    return code in TRANSIENT_CODES
