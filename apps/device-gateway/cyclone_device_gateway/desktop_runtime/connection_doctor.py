"""Which part of the connection failed, in plain words, with one thing to do (alpha 88).

"Connected" is really a chain: the cable and Windows, the phone's USB-debugging Allow, adb, Cyclone installed and
running, the phone app answering, this PC's trust and its session, the PC Gateway switch, and Cyclone
Accessibility. The first broken link is the answer. Every surface (Glass, `/v1/devices`, the MCP) reads this one
verdict, so they can't disagree. Pure: the facts come from the fleet session and the trust status.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from .models import AITrustState, BridgeState, DiscoveryState

BUSY_ERROR_CLASSES = {"BRIDGEBUSYERROR", "PHONE_APP_BUSY"}
LOCK_WORDS = ("unlock", "locked")


@dataclass(frozen=True)
class Diagnosis:
    layer: str
    code: str
    ok: bool
    title: str
    message: str
    # One button at most. kind: connect | install | start_app | open_accessibility | open_cyclone
    action_kind: str | None = None
    action_label: str | None = None
    # Cyclone is already fixing it (reconnecting, restarting the app): show progress, not a button.
    working: bool = False

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        kind, label = value.pop("action_kind"), value.pop("action_label")
        value["action"] = {"kind": kind, "label": label} if kind else None
        return value


def diagnose(
    *,
    discovery: DiscoveryState,
    bridge: BridgeState,
    trust: AITrustState,
    session_ready: bool,
    source: str = "USB",
    app_running: bool | None = None,
    bridge_error_class: str | None = None,
    gateway_enabled: bool | None = None,
    accessibility: bool | None = None,
    match_code: str | None = None,
    last_error: str | None = None,
) -> Diagnosis:
    wired = source == "USB"
    if discovery == DiscoveryState.ABSENT:
        if wired:
            return Diagnosis("usb", "USB_NOT_DETECTED", False, "Phone not detected",
                             "Windows doesn't see the phone. Plug it in with a cable that carries data, not a charge-only one.")
        if source == "CLOUD":
            return Diagnosis("cloud", "CLOUD_LINK_DOWN", False, "Cloud phone not reachable",
                             "Cyclone is reopening its link to the cloud phone.", working=True)
        return Diagnosis("usb", "LINK_LOST", False, "Phone not reachable", "Cyclone reconnects when the phone is back on the network.", working=True)
    if discovery == DiscoveryState.OFFLINE:
        return Diagnosis("usb", "USB_OFFLINE", False, "Phone is offline", "Unlock the phone, then unplug it and plug it in again.")
    if discovery == DiscoveryState.UNAUTHORIZED:
        return Diagnosis("authorize", "USB_ALLOW", False, "Tap Allow on the phone",
                         "The phone asks to allow USB debugging. Tick “Always allow from this computer”, then tap Allow.")
    if bridge == BridgeState.APP_MISSING:
        return Diagnosis("app", "APP_MISSING", False, "Cyclone isn't on this phone", "Install it from this PC. It takes about a minute.",
                         "install", "Install Cyclone")
    if app_running is False:
        return Diagnosis("app", "APP_STOPPED", False, "Cyclone stopped on the phone", "Starting it again…",
                         "start_app", "Start Cyclone", working=True)
    if (bridge_error_class or "").upper() in BUSY_ERROR_CLASSES:
        return Diagnosis("app", "APP_BUSY", False, "Cyclone is busy", "The phone app is finishing something and answers again in a moment.",
                         working=True)
    if trust == AITrustState.CONFIRMATION_REQUIRED:
        code = f"{match_code[:3]} {match_code[3:]}" if match_code and len(match_code) == 6 else None
        return Diagnosis("trust", "TRUST_CONFIRM", False, "Tap Allow on the phone",
                         f"The phone asks “Connect this PC?”. Check it shows {code}, then tap Allow." if code
                         else "The phone asks “Connect this PC?”. Tap Allow.", working=True)
    if trust == AITrustState.UNPAIRED:
        return Diagnosis("trust", "TRUST_NEEDED", False, "Connect this phone", "You'll tap Allow on the phone once. It stays connected after that.",
                         "connect", "Connect")
    if trust in {AITrustState.REVOKED, AITrustState.EXPIRED}:
        return Diagnosis("trust", "TRUST_AGAIN", False, "Allow this PC again", "This PC's access ended. Connect again and tap Allow on the phone.",
                         "connect", "Connect again")
    if not session_ready:
        if last_error and any(word in last_error.lower() for word in LOCK_WORDS):
            return Diagnosis("trust", "PHONE_LOCKED", False, "Unlock the phone", "Cyclone reconnects as soon as the phone is unlocked.", working=True)
        return Diagnosis("trust", "RESUMING", False, "Reconnecting", "Restoring the trusted connection. No tap needed.", working=True)
    if gateway_enabled is False:
        return Diagnosis("gateway", "GATEWAY_OFF", False, "Turn on PC Gateway",
                         "On the phone, open Cyclone → Settings → PC Gateway and turn it on.", "open_cyclone", "Open Cyclone on the phone")
    if bridge != BridgeState.CONNECTED:
        return Diagnosis("gateway", "RECONNECTING", False, "Reconnecting", "The phone app is answering again in a moment.", working=True)
    if accessibility is False:
        return Diagnosis("accessibility", "ACCESSIBILITY_OFF", False, "Cyclone Accessibility is off",
                         "Android turns it off when Cyclone is stopped. Repair turns it back on over USB; "
                         "if it can't, the Accessibility list opens on the phone.",
                         "open_accessibility", "Repair")
    return Diagnosis("ready", "READY", True, "Connected", "")


def diagnose_session(session: Any, discovery: DiscoveryState, bridge: BridgeState, trust: AITrustState,
                     trust_status: dict[str, Any] | None) -> Diagnosis:
    status = trust_status or {}
    # Without a trust status (older callers) a phone that answers the bridge counts as resumed.
    session_ready = bool(status.get("sessionReady")) if trust_status is not None else bridge == BridgeState.CONNECTED
    return diagnose(
        discovery=discovery,
        bridge=bridge,
        trust=trust,
        session_ready=session_ready,
        source=str(getattr(session, "source", "USB") or "USB"),
        app_running=getattr(session, "app_running", None),
        bridge_error_class=getattr(session, "bridge_error_class", None),
        gateway_enabled=getattr(session, "bridge_gateway_enabled", None),
        accessibility=getattr(session, "accessibility_connected", None),
        match_code=status.get("matchCode") if isinstance(status.get("matchCode"), str) else None,
        last_error=str(status.get("lastSafeError") or getattr(session, "bridge_last_error", "") or "") or None,
    )
