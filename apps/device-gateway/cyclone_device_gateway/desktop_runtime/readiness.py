from __future__ import annotations

from typing import Any

from .connection_doctor import diagnose_session
from .models import AITrustState, BridgeState, DeviceFleetState, DiscoveryState, MediaState

HEALTH_READY = "READY"
HEALTH_DEGRADED = "DEGRADED"
HEALTH_UNAVAILABLE = "UNAVAILABLE"


def _video_diagnostics(session: Any) -> dict[str, Any]:
    controller = getattr(session, "video", None)
    if controller is None or not hasattr(controller, "diagnostics"):
        return {}
    try:
        value = controller.diagnostics()
    except Exception:
        return {}
    return value if isinstance(value, dict) else {}


def discovery_state(session: Any) -> DiscoveryState:
    # A phone the fleet already saw leave is absent, whatever adb last said about it (alpha 88).
    if getattr(session, "state", None) == DeviceFleetState.DISCONNECTED:
        return DiscoveryState.ABSENT
    state = str(getattr(getattr(session, "adb_device", None), "state", "") or "").lower()
    if state == "device":
        return DiscoveryState.ADB_READY
    if state == "unauthorized":
        return DiscoveryState.UNAUTHORIZED
    if state in {"offline", "recovery", "sideload", "bootloader"}:
        return DiscoveryState.OFFLINE
    return DiscoveryState.ABSENT


def media_state(session: Any, diagnostics: dict[str, Any] | None = None) -> MediaState:
    discovery = discovery_state(session)
    if discovery != DiscoveryState.ADB_READY:
        return MediaState.UNAVAILABLE if discovery == DiscoveryState.OFFLINE else MediaState.STOPPED
    controller = getattr(session, "video", None)
    if controller is None:
        return MediaState.UNAVAILABLE
    diagnostics = diagnostics if diagnostics is not None else _video_diagnostics(session)
    active = diagnostics.get("activeProfiles") or []
    frames = diagnostics.get("framesByProfile") or {}
    failures = diagnostics.get("failuresByProfile") or {}
    last_event = str(diagnostics.get("lastEvent") or "")
    if active:
        if not bool(getattr(session, "screen_awake", True)):
            return MediaState.SLEEPING
        if any(int(value or 0) > 0 for value in frames.values()):
            return MediaState.LIVE
        if any(int(value or 0) >= 3 for value in failures.values()) or "failed" in last_event:
            return MediaState.RECONNECTING
        if "keyframe" in last_event:
            return MediaState.WAITING_KEYFRAME
        return MediaState.STARTING
    return MediaState.STOPPED


def bridge_state(session: Any) -> BridgeState:
    error = str(getattr(session, "bridge_last_error", "") or "")
    error_class = str(getattr(session, "bridge_error_class", "") or "")
    lowered = error.lower()
    if "not installed" in lowered:
        return BridgeState.APP_MISSING
    if discovery_state(session) != DiscoveryState.ADB_READY:
        return BridgeState.APP_STOPPED
    if getattr(session, "bridge_ok", None) is True:
        return BridgeState.CONNECTED
    if "auth" in error_class.lower() or "auth" in lowered or "token" in lowered or "signature" in lowered:
        return BridgeState.AUTH_FAILED
    if getattr(session, "bridge_ok", None) is False:
        return BridgeState.DEGRADED
    return BridgeState.SOCKET_STARTING


def ai_trust_state(session: Any, bridge: BridgeState | None = None, trust_status: dict[str, Any] | None = None) -> AITrustState:
    if trust_status:
        explicit = str(trust_status.get("state") or "")
        mapping = {
            "UNPAIRED": AITrustState.UNPAIRED,
            "CONFIRMATION_REQUIRED": AITrustState.CONFIRMATION_REQUIRED,
            "TRUSTED": AITrustState.TRUSTED,
            "REVOKED": AITrustState.REVOKED,
            "EXPIRED": AITrustState.EXPIRED,
        }
        if explicit in mapping:
            return mapping[explicit]
    if getattr(session, "pending_pairing", None) is not None:
        return AITrustState.CONFIRMATION_REQUIRED
    if not getattr(session, "credential", None):
        return AITrustState.UNPAIRED
    bridge = bridge or bridge_state(session)
    if bridge == BridgeState.AUTH_FAILED:
        return AITrustState.EXPIRED
    return AITrustState.TRUSTED


def connection_label(
    discovery: DiscoveryState,
    media: MediaState,
    bridge: BridgeState,
    trust: AITrustState,
) -> str:
    if discovery == DiscoveryState.UNAUTHORIZED:
        return "Phone detected · USB debugging authorization needed"
    if discovery in {DiscoveryState.ABSENT, DiscoveryState.OFFLINE}:
        return "Phone disconnected · Waiting for USB recovery"
    if media == MediaState.LIVE and trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        return "Screen connected · Allow AI control on phone"
    if media == MediaState.LIVE and trust == AITrustState.TRUSTED and bridge in {BridgeState.DEGRADED, BridgeState.SOCKET_STARTING}:
        return "Screen live · AI bridge reconnecting"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED and media == MediaState.UNAVAILABLE:
        return "AI ready · Live display unavailable"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED and media == MediaState.LIVE:
        return "Screen live · AI/Codex ready"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED:
        return "Phone connected · AI/Codex ready"
    if trust == AITrustState.EXPIRED:
        return "Phone connected · Allow AI control again"
    if trust == AITrustState.REVOKED:
        return "Phone connected · AI access revoked"
    if bridge == BridgeState.APP_MISSING:
        return "Phone connected · Cyclone Mobile is not installed"
    if media in {MediaState.STARTING, MediaState.WAITING_KEYFRAME, MediaState.RECONNECTING}:
        return "Phone connected · Live display starting"
    return "Phone connected · AI access not yet allowed"


def readiness_cards(
    discovery: DiscoveryState,
    media: MediaState,
    bridge: BridgeState,
    trust: AITrustState,
) -> dict[str, dict[str, str | bool]]:
    phone_ready = discovery == DiscoveryState.ADB_READY
    if phone_ready:
        phone = {"state": "READY", "ready": True, "message": "USB connection ready"}
    elif discovery == DiscoveryState.UNAUTHORIZED:
        phone = {"state": "ACTION_REQUIRED", "ready": False, "message": "Approve USB debugging on the phone"}
    else:
        phone = {"state": "OFFLINE", "ready": False, "message": "Reconnect the phone by USB"}

    if media == MediaState.LIVE:
        display = {"state": "READY", "ready": True, "message": "Live display ready"}
    elif media == MediaState.STOPPED:
        display = {"state": "IDLE", "ready": False, "message": "Open the phone to start live display"}
    elif media in {MediaState.STARTING, MediaState.WAITING_KEYFRAME, MediaState.RECONNECTING}:
        display = {"state": "RECOVERING", "ready": False, "message": "Live display is reconnecting"}
    elif media == MediaState.SLEEPING:
        display = {"state": "SLEEPING", "ready": False, "message": "Phone screen is sleeping"}
    else:
        display = {"state": "LIMITED", "ready": False, "message": "Live display is unavailable; AI can remain usable"}

    ai_ready = trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED
    if ai_ready:
        ai = {"state": "READY", "ready": True, "message": "AI/Codex access ready"}
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        ai = {"state": "ACTION_REQUIRED", "ready": False, "message": "Allow this PC on the phone"}
    elif trust in {AITrustState.EXPIRED, AITrustState.REVOKED} or bridge == BridgeState.AUTH_FAILED:
        ai = {"state": "ACTION_REQUIRED", "ready": False, "message": "AI trust must be restored"}
    elif trust == AITrustState.TRUSTED:
        ai = {"state": "RECOVERING", "ready": False, "message": "AI bridge is reconnecting"}
    else:
        ai = {"state": "LIMITED", "ready": False, "message": "AI access is unavailable"}
    return {"phoneConnection": phone, "liveDisplay": display, "aiCodexAccess": ai}


def _health_card(state: str, reason_code: str, message: str, action: str | None = None) -> dict[str, str | bool | None]:
    """Stable, per-plane status for Page Cards and deterministic recovery clients."""
    return {
        "state": state,
        "ready": state == HEALTH_READY,
        "reasonCode": reason_code,
        "message": message,
        "recommendedAction": action,
    }


def health_planes(
    session: Any,
    discovery: DiscoveryState,
    media: MediaState,
    bridge: BridgeState,
    trust: AITrustState,
    session_ready: bool | None = None,
) -> dict[str, dict[str, str | bool | None]]:
    """Independent health evidence. These codes are public API, so keep them stable."""
    if discovery == DiscoveryState.ADB_READY:
        usb = _health_card(HEALTH_READY, "USB_AUTHORIZED", "USB debugging is authorized.")
    elif discovery == DiscoveryState.UNAUTHORIZED:
        usb = _health_card(HEALTH_UNAVAILABLE, "USB_UNAUTHORIZED", "USB debugging authorization is required.", "APPROVE_USB_DEBUGGING")
    elif discovery == DiscoveryState.OFFLINE:
        usb = _health_card(HEALTH_UNAVAILABLE, "USB_OFFLINE", "The phone is offline to ADB.", "RECONNECT_USB")
    else:
        usb = _health_card(HEALTH_UNAVAILABLE, "USB_ABSENT", "The phone is not present in ADB.", "RECONNECT_USB")

    gateway_enabled = getattr(session, "bridge_gateway_enabled", None)
    socket_listening = getattr(session, "bridge_socket_listening", None)
    if bridge == BridgeState.APP_MISSING:
        gateway = _health_card(HEALTH_UNAVAILABLE, "GATEWAY_APP_MISSING", "Cyclone Mobile is not installed.", "INSTALL_CYCLONE_MOBILE")
    elif bridge == BridgeState.AUTH_FAILED:
        gateway = _health_card(HEALTH_DEGRADED, "GATEWAY_AUTH_REJECTED", "The phone rejected the current bridge session.", "RESTORE_PHONE_TRUST")
    elif bridge == BridgeState.CONNECTED and gateway_enabled is not False and socket_listening is not False:
        gateway = _health_card(HEALTH_READY, "GATEWAY_CONNECTED", "Cyclone Mobile gateway is reachable.")
    elif bridge == BridgeState.SOCKET_STARTING:
        gateway = _health_card(HEALTH_DEGRADED, "GATEWAY_STARTING", "Cyclone Mobile gateway is starting.", "WAIT_FOR_GATEWAY")
    else:
        gateway = _health_card(HEALTH_DEGRADED, "GATEWAY_UNAVAILABLE", "Cyclone Mobile gateway is reconnecting.", "RETRY_GATEWAY")

    accessibility = getattr(session, "accessibility_connected", None)
    if accessibility is True:
        accessibility_card = _health_card(HEALTH_READY, "ACCESSIBILITY_CONNECTED", "Cyclone Accessibility is connected.")
    elif accessibility is False:
        accessibility_card = _health_card(HEALTH_UNAVAILABLE, "ACCESSIBILITY_DISABLED", "Enable Cyclone Accessibility on the phone.", "ENABLE_ACCESSIBILITY")
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        accessibility_card = _health_card(HEALTH_DEGRADED, "ACCESSIBILITY_UNVERIFIED", "Accessibility will be checked after phone trust is enabled.", "ALLOW_THIS_PC")
    else:
        accessibility_card = _health_card(HEALTH_DEGRADED, "ACCESSIBILITY_UNKNOWN", "Accessibility status has not been confirmed yet.", "RETRY_GATEWAY")

    # Matched only when this USB session's own trusted session is open (session_ready), not one carried over.
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED and session_ready is not False:
        token = _health_card(HEALTH_READY, "TOKEN_SESSION_MATCHED", "The trusted phone session matches this device.")
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        token = _health_card(HEALTH_UNAVAILABLE, "TOKEN_SESSION_UNPAIRED", "Allow this PC on the phone before AI control.", "ALLOW_THIS_PC")
    elif trust in {AITrustState.EXPIRED, AITrustState.REVOKED} or bridge == BridgeState.AUTH_FAILED:
        token = _health_card(HEALTH_UNAVAILABLE, "TOKEN_SESSION_MISMATCH", "The previous phone session is no longer valid.", "RESTORE_PHONE_TRUST")
    else:
        token = _health_card(HEALTH_DEGRADED, "TOKEN_SESSION_PENDING", "The trusted phone session is being restored.", "WAIT_FOR_TRUST")

    if gateway["ready"] and accessibility_card["ready"] and token["ready"]:
        semantic = _health_card(HEALTH_READY, "SEMANTIC_READY", "Semantic observation and canonical actions are ready.")
    elif accessibility is False:
        semantic = _health_card(HEALTH_UNAVAILABLE, "SEMANTIC_ACCESSIBILITY_UNAVAILABLE", "Semantic controls require Cyclone Accessibility.", "ENABLE_ACCESSIBILITY")
    elif token["reasonCode"] in {"TOKEN_SESSION_UNPAIRED", "TOKEN_SESSION_MISMATCH"}:
        semantic = _health_card(HEALTH_UNAVAILABLE, "SEMANTIC_TRUST_REQUIRED", "Semantic controls require a trusted phone session.", "RESTORE_PHONE_TRUST")
    else:
        semantic = _health_card(HEALTH_DEGRADED, "SEMANTIC_GATEWAY_DEGRADED", "Semantic controls are waiting for the phone gateway.", "RETRY_GATEWAY")

    if media == MediaState.LIVE:
        media_card = _health_card(HEALTH_READY, "MEDIA_LIVE", "Live display is receiving frames.")
    elif media in {MediaState.STARTING, MediaState.WAITING_KEYFRAME, MediaState.RECONNECTING}:
        media_card = _health_card(HEALTH_DEGRADED, "MEDIA_RECONNECTING", "Live display is reconnecting.", "WAIT_FOR_MEDIA")
    elif media == MediaState.SLEEPING:
        media_card = _health_card(HEALTH_DEGRADED, "MEDIA_SLEEPING", "Wake the phone to resume live display.", "WAKE_PHONE")
    else:
        media_card = _health_card(HEALTH_UNAVAILABLE, "MEDIA_UNAVAILABLE", "Live display is unavailable.", "OPEN_LIVE_DISPLAY")

    return {
        "usbAuthorization": usb,
        "gateway": gateway,
        "accessibility": accessibility_card,
        "tokenSession": token,
        "semantic": semantic,
        "media": media_card,
    }


def enrich_device_public(session: Any, trust_status: dict[str, Any] | None = None) -> dict[str, Any]:
    """Return the V3.3 public device shape without leaking serials, credentials or frame bytes.

    The legacy aggregate ``state`` is kept for existing UI compatibility, but bridge/media failures
    no longer force the entire phone into ``ATTENTION``. Consumers should prefer ``planes`` and
    ``readiness``.
    """
    public = dict(session.public())
    diagnostics = _video_diagnostics(session)
    discovery = discovery_state(session)
    media = media_state(session, diagnostics)
    bridge = bridge_state(session)
    trust = ai_trust_state(session, bridge, trust_status)

    if discovery == DiscoveryState.UNAUTHORIZED:
        legacy_state = "UNAUTHORIZED"
    elif discovery != DiscoveryState.ADB_READY:
        legacy_state = "DISCONNECTED"
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        legacy_state = "PAIRING" if trust == AITrustState.CONFIRMATION_REQUIRED else "UNPAIRED"
    elif not bool(getattr(session, "screen_awake", True)):
        legacy_state = "SLEEPING"
    else:
        legacy_state = "READY"

    public["state"] = legacy_state
    public["paired"] = trust == AITrustState.TRUSTED
    public["connectionLabel"] = connection_label(discovery, media, bridge, trust)
    public["planes"] = {
        "discovery": discovery.value,
        "media": media.value,
        "bridge": bridge.value,
        "aiTrust": trust.value,
    }
    status = trust_status or {}
    # Bounded, secret-free trust facts so a client can say *why* a trusted phone is not usable yet
    # (for example the phone is locked while the session restores) and show the connect match code.
    public["trust"] = {
        "state": trust.value,
        "sessionReady": bool(status.get("sessionReady")),
        "matchCode": status.get("matchCode") if isinstance(status.get("matchCode"), str) else None,
        "lastSafeError": str(status.get("lastSafeError"))[:240] if status.get("lastSafeError") else None,
        # The name the phone shows for this PC under Linked PCs.
        "pcLabel": str(status.get("pcLabel"))[:80] if isinstance(status.get("pcLabel"), str) and status.get("pcLabel") else None,
    }
    public["readiness"] = readiness_cards(discovery, media, bridge, trust)
    session_ready = bool(status.get("sessionReady")) if trust_status is not None else None
    public["health"] = {
        "version": "cyclone.device-health.v1",
        "planes": health_planes(session, discovery, media, bridge, trust, session_ready),
    }
    # Alpha 88: the one answer every surface shows — the first broken link, in plain words, with one action.
    public["connection"] = diagnose_session(session, discovery, bridge, trust, trust_status).to_dict()
    public["control"] = control_summary(session)
    public["videoDiagnostics"] = {
        "subscriberCount": int(diagnostics.get("subscriberCount") or 0),
        "activeProfiles": list(diagnostics.get("activeProfiles") or []),
        "lastEvent": str(diagnostics.get("lastEvent") or "idle"),
        "lastFrameAvailable": bool(diagnostics.get("lastFrameAvailable")),
    }
    return public


def control_summary(session: Any) -> dict[str, Any]:
    """Who is in control, from one owner: the phone. The owner taking over on the phone wins; otherwise AI works only
    after the PC user hands it control (request_ai_control / Give AI control)."""
    phone = getattr(session, "phone_controller", None)
    pc = getattr(session, "input_owner", "HUMAN")
    if phone == "HUMAN":
        effective, message = "OWNER_ON_PHONE", "You have the phone. AI waits until you hand it back on the phone."
    elif pc == "AI":
        effective, message = "AI", "AI can act on the phone."
    else:
        effective, message = "PC_USER", "AI asks for control before it acts."
    return {"effective": effective, "phone": phone, "pc": pc, "message": message}
