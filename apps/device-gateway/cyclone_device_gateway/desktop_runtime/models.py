from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
import hashlib
import time
from typing import Any


DESKTOP_PROTOCOL_VERSION = "cyclone.desktop.runtime.v1"
VIDEO_PROTOCOL_VERSION = "cyclone.desktop.video.v1"
CYCLONE_ONE_SESSION_PROTOCOL_VERSION = "cyclone.one.session.v1"
MAX_FLEET_DEVICES = 16


class DiscoveryState(StrEnum):
    ABSENT = "ABSENT"
    UNAUTHORIZED = "UNAUTHORIZED"
    OFFLINE = "OFFLINE"
    ADB_READY = "ADB_READY"


class MediaState(StrEnum):
    STOPPED = "STOPPED"
    STARTING = "STARTING"
    WAITING_KEYFRAME = "WAITING_KEYFRAME"
    LIVE = "LIVE"
    SLEEPING = "SLEEPING"
    RECONNECTING = "RECONNECTING"
    UNAVAILABLE = "UNAVAILABLE"


class BridgeState(StrEnum):
    APP_MISSING = "APP_MISSING"
    APP_STOPPED = "APP_STOPPED"
    SOCKET_STARTING = "SOCKET_STARTING"
    CONNECTED = "CONNECTED"
    AUTH_FAILED = "AUTH_FAILED"
    DEGRADED = "DEGRADED"


class AITrustState(StrEnum):
    UNPAIRED = "UNPAIRED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
    TRUSTED = "TRUSTED"
    REVOKED = "REVOKED"
    EXPIRED = "EXPIRED"


class DeviceFleetState(StrEnum):
    # Legacy aggregate states retained for compatibility. V3.3 UI/API readiness must use the
    # independent discovery/media/bridge/AI-trust planes instead of treating this as authority.
    DISCONNECTED = "DISCONNECTED"
    UNAUTHORIZED = "UNAUTHORIZED"
    UNPAIRED = "UNPAIRED"
    PAIRING = "PAIRING"
    READY = "READY"
    SLEEPING = "SLEEPING"
    ATTENTION = "ATTENTION"


class FleetEventType(StrEnum):
    DEVICE_ADDED = "DEVICE_ADDED"
    DEVICE_REMOVED = "DEVICE_REMOVED"
    STATE_CHANGED = "STATE_CHANGED"
    PAIRING_CHANGED = "PAIRING_CHANGED"
    SCREEN_STATE_CHANGED = "SCREEN_STATE_CHANGED"
    SESSION_ADDED = "session.added"
    SESSION_REMOVED = "session.removed"
    MISSION_CREATED = "MISSION_CREATED"
    TASK_UPDATED = "TASK_UPDATED"
    NEEDS_YOU = "NEEDS_YOU"
    MISSION_DONE = "MISSION_DONE"


class RuntimeErrorCode(StrEnum):
    DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED"
    DEVICE_UNAUTHORIZED = "DEVICE_UNAUTHORIZED"
    DEVICE_NOT_READY = "DEVICE_NOT_READY"
    PAIRING_REQUIRED = "PAIRING_REQUIRED"
    PAIRING_EXPIRED = "PAIRING_EXPIRED"
    PAIRING_REPLAY = "PAIRING_REPLAY"
    PAIRING_CODE_REJECTED = "PAIRING_CODE_REJECTED"
    PAIRING_ATTEMPTS_EXCEEDED = "PAIRING_ATTEMPTS_EXCEEDED"
    PAIRING_SESSION_MISMATCH = "PAIRING_SESSION_MISMATCH"
    TRUST_CONFIRMATION_REQUIRED = "TRUST_CONFIRMATION_REQUIRED"
    TRUST_REVOKED = "TRUST_REVOKED"
    TRUST_EXPIRED = "TRUST_EXPIRED"
    TRUST_AUTH_FAILED = "TRUST_AUTH_FAILED"
    TRUST_REJECTED = "TRUST_REJECTED"
    PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    PHONE_LOCKED = "PHONE_LOCKED"
    CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
    BACKGROUND_MODE_UNAVAILABLE = "BACKGROUND_MODE_UNAVAILABLE"
    STALE_SESSION = "STALE_SESSION"
    FOREGROUND_REQUIRED = "FOREGROUND_REQUIRED"
    INVALID_REQUEST = "INVALID_REQUEST"
    AUTH_REJECTED = "AUTH_REJECTED"
    STALE_OBSERVATION = "STALE_OBSERVATION"
    POLICY_DENIED = "POLICY_DENIED"
    AGENT_CONTEXT_TRUNCATION = "AGENT_CONTEXT_TRUNCATION"
    STREAM_CAPACITY = "STREAM_CAPACITY"
    HUMAN_HAS_CONTROL = "HUMAN_HAS_CONTROL"
    SESSION_REQUIRED = "SESSION_REQUIRED"
    SESSION_DISPLAY_MISMATCH = "SESSION_DISPLAY_MISMATCH"
    STALE_CONTROL_REVISION = "STALE_CONTROL_REVISION"
    MAPPING_PLANE_BUSY = "MAPPING_PLANE_BUSY"
    MAPPING_JOB_NOT_FOUND = "MAPPING_JOB_NOT_FOUND"
    RUN_NOT_FOUND = "RUN_NOT_FOUND"
    MAPPING_INVALID_STATE = "MAPPING_INVALID_STATE"
    ASK_BUSY = "ASK_BUSY"
    OVERLAY_UNAVAILABLE = "OVERLAY_UNAVAILABLE"
    MOMENT_CHANGED = "MOMENT_CHANGED"
    ANSWER_ON_PHONE = "ANSWER_ON_PHONE"
    SEALED_REJECTED = "SEALED_REJECTED"
    KEY_UNAVAILABLE = "KEY_UNAVAILABLE"
    GATE = "GATE"
    MUTATE_LOCK = "MUTATE_LOCK"
    TARGET_MISMATCH = "TARGET_MISMATCH"
    STALE_WORKSPACE = "STALE_WORKSPACE"
    QUEUE_EMPTY = "QUEUE_EMPTY"
    USER_UNVERIFIED = "USER_UNVERIFIED"
    # Alpha 87: the phone is alive but its Cyclone app is still busy (not a lost connection).
    PHONE_APP_BUSY = "PHONE_APP_BUSY"


class DesktopRuntimeError(RuntimeError):
    def __init__(self, code: RuntimeErrorCode | str, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.code = str(code)
        self.safe_message = message[:300]
        self.retryable = retryable

    def to_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.safe_message, "retryable": self.retryable}


def deterministic_device_id(serial: str) -> str:
    digest = hashlib.sha256(f"cyclone-desktop-v1\0{serial}".encode("utf-8")).hexdigest()
    return f"dev_{digest[:20]}"


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass(frozen=True)
class VideoProfileSpec:
    name: str
    max_long_edge: int
    target_fps: int
    bitrate_bps: int
    preferred_codec: str
    cpu_weight: int


VIDEO_PROFILES: dict[str, VideoProfileSpec] = {
    # Fleet cards do not auto-start media. An explicit thumbnail is a bounded H.264 preview;
    # only the emergency screenshot path runs at one frame per second.
    "thumbnail": VideoProfileSpec(
        name="thumbnail",
        max_long_edge=540,
        target_fps=8,
        bitrate_bps=1_000_000,
        preferred_codec="video/avc",
        cpu_weight=1,
    ),
    "focus": VideoProfileSpec(
        name="focus",
        max_long_edge=1920,
        target_fps=30,
        bitrate_bps=12_000_000,
        preferred_codec="video/avc",
        cpu_weight=4,
    ),
}
