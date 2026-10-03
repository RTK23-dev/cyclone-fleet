"""Loopback stream and execution-session API for the desktop gateway.

V3.3 keeps the foreground media plane independent from Cyclone AI trust. Execution sessions are
different: their lifecycle, semantic state, exact-session snapshots and actions are authenticated
through the Android Gateway and are mounted here as a sibling router.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response

from ..auth import verify_bearer
from ..desktop_runtime.models import (
    DesktopRuntimeError,
    VIDEO_PROFILES,
    VIDEO_PROTOCOL_VERSION,
)
from .camera_stream_api import create_camera_stream_router
from .layer2_api import create_layer2_router
from .session_api import create_session_router
from .v5_contract_api import create_v5_contract_router


def create_stream_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def adb_controller(device_id: str):
        try:
            session = runtime.fleet.get(device_id)
        except DesktopRuntimeError:
            raise HTTPException(status_code=404, detail={"code": "DEVICE_NOT_FOUND", "message": "Phone is not connected."})
        adb_state = str(getattr(getattr(session, "adb_device", None), "state", "") or "")
        if adb_state != "device":
            code = "DEVICE_UNAUTHORIZED" if adb_state == "unauthorized" else "DEVICE_DISCONNECTED"
            message = "Approve USB debugging on the phone." if adb_state == "unauthorized" else "Phone is not ADB-ready."
            raise HTTPException(status_code=409, detail={"code": code, "message": message})
        controller = getattr(session, "video", None)
        if controller is None or not hasattr(controller, "snapshot"):
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "Video runtime is unavailable."})
        return controller

    @router.get("/v1/devices/{device_id}/stream/snapshot", dependencies=[Depends(auth)])
    def stream_snapshot(device_id: str, profile: str = Query(default="focus")):
        if profile not in VIDEO_PROFILES:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": "Unknown video profile."})
        controller = adb_controller(device_id)
        try:
            frame = controller.snapshot()
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=503, detail=exc.to_dict()) from exc
        headers = {
            "Cache-Control": "no-store",
            "X-Cyclone-Stream-Profile": profile,
            "X-Cyclone-Frame-Codec": str(frame["codec"]),
            "X-Cyclone-Frame-Timestamp-Ms": str(frame["timestamp_ms"]),
            "X-Cyclone-Frame-Sequence": str(frame["sequence"]),
        }
        if frame["width"] is not None and frame["height"] is not None:
            headers["X-Cyclone-Frame-Width"] = str(frame["width"])
            headers["X-Cyclone-Frame-Height"] = str(frame["height"])
        return Response(content=frame["data"], media_type=str(frame["codec"]), headers=headers)

    @router.get("/v1/devices/{device_id}/stream/status", dependencies=[Depends(auth)])
    def stream_status(device_id: str):
        controller = adb_controller(device_id)
        diagnostics = controller.diagnostics() if hasattr(controller, "diagnostics") else {}
        return {
            "ok": True,
            "deviceId": device_id,
            "protocol": VIDEO_PROTOCOL_VERSION,
            "video": diagnostics,
        }

    # Camera streaming is a sibling media plane. It reuses Cyclone's pinned scrcpy transport,
    # captures the physical sensor aspect ratio, and fans one encoded source to <=5 viewers.
    router.include_router(create_camera_stream_router(runtime, token))

    # Session routes share the same ephemeral local bearer, but every operation behind them also
    # requires the phone's trusted Android Gateway credential. No direct ADB execution route exists.
    router.include_router(create_session_router(runtime, token))
    router.include_router(create_layer2_router(runtime, token))
    router.include_router(create_v5_contract_router(runtime, token))
    return router
