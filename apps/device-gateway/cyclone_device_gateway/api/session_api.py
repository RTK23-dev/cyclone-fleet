from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..desktop_runtime.sessions import ExecutionSessionService, SESSION_ACTIONS


class SessionStartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    package: str = Field(min_length=3, max_length=240)


class SessionObserveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["compact", "full"] = "compact"


class SessionActionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tool: str = Field(min_length=3, max_length=80)
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = Field(default="", max_length=1000)


def create_session_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    service = getattr(runtime, "sessions", None) or ExecutionSessionService(runtime.fleet)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/devices/{device_id}/sessions", dependencies=[Depends(auth)])
    def session_list(device_id: str):
        return _call(lambda: service.list(device_id))

    @router.post("/v1/devices/{device_id}/sessions", dependencies=[Depends(auth)])
    def session_start(device_id: str, body: SessionStartBody):
        return _call(lambda: service.start(device_id, body.package))

    @router.get("/v1/devices/{device_id}/sessions/{session_id}", dependencies=[Depends(auth)])
    def session_status(device_id: str, session_id: str):
        return _call(lambda: service.status(device_id, session_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/pause", dependencies=[Depends(auth)])
    def session_pause(device_id: str, session_id: str):
        return _call(lambda: service.pause(device_id, session_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/resume", dependencies=[Depends(auth)])
    def session_resume(device_id: str, session_id: str):
        return _call(lambda: service.resume(device_id, session_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/handoff", dependencies=[Depends(auth)])
    def session_handoff(device_id: str, session_id: str):
        return _call(lambda: service.handoff(device_id, session_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/stop", dependencies=[Depends(auth)])
    def session_stop(device_id: str, session_id: str):
        return _call(lambda: service.stop(device_id, session_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/observe", dependencies=[Depends(auth)])
    def session_observe(device_id: str, session_id: str, body: SessionObserveBody):
        return _call(lambda: service.observe(device_id, session_id, mode=body.mode))

    @router.get("/v1/devices/{device_id}/sessions/{session_id}/ui/search", dependencies=[Depends(auth)])
    def session_search(
        device_id: str,
        session_id: str,
        q: str = Query(min_length=1, max_length=300),
        limit: int = Query(default=30, ge=1, le=100),
    ):
        return _call(lambda: service.search(device_id, session_id, q, limit=limit))

    @router.get("/v1/devices/{device_id}/sessions/{session_id}/ui/element/{element_id}", dependencies=[Depends(auth)])
    def session_element(device_id: str, session_id: str, element_id: str):
        return _call(lambda: service.element(device_id, session_id, element_id))

    @router.post("/v1/devices/{device_id}/sessions/{session_id}/action", dependencies=[Depends(auth)])
    def session_action(device_id: str, session_id: str, body: SessionActionBody):
        if body.tool not in SESSION_ACTIONS and body.tool != "phone.tap":
            raise HTTPException(
                status_code=400,
                detail={"code": "CAPABILITY_UNAVAILABLE", "message": "This tool is not exposed for background sessions."},
            )
        return _call(lambda: service.action(device_id, session_id, body.tool, body.params, body.goal))

    @router.get("/v1/devices/{device_id}/sessions/{session_id}/snapshot", dependencies=[Depends(auth)])
    def session_snapshot(device_id: str, session_id: str):
        value = _call(lambda: service.snapshot(device_id, session_id))
        if isinstance(value, Response):
            return value
        descriptor = value["session"]
        metadata = value.get("metadata") or {}
        headers = {
            "Cache-Control": "no-store",
            "X-Cyclone-Session-Id": str(descriptor["sessionId"]),
            "X-Cyclone-Display-Id": str(descriptor["displayId"]),
            "X-Cyclone-Foreground-Substitution": "false",
        }
        for source, target in (
            ("frameId", "X-Cyclone-Frame-Id"),
            ("timestampMs", "X-Cyclone-Frame-Timestamp-Ms"),
            ("width", "X-Cyclone-Frame-Width"),
            ("height", "X-Cyclone-Frame-Height"),
        ):
            if metadata.get(source) is not None:
                headers[target] = str(metadata[source])
        return Response(content=value["data"], media_type=value["mediaType"], headers=headers)

    return router


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = {
            RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.DEVICE_UNAUTHORIZED.value: 409,
            RuntimeErrorCode.DEVICE_NOT_READY.value: 409,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.PROTOCOL_MISMATCH.value: 426,
            RuntimeErrorCode.PHONE_LOCKED.value: 423,
            RuntimeErrorCode.HUMAN_HAS_CONTROL.value: 409,
            RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE.value: 409,
            RuntimeErrorCode.STALE_SESSION.value: 410,
            RuntimeErrorCode.FOREGROUND_REQUIRED.value: 409,
            RuntimeErrorCode.STALE_OBSERVATION.value: 409,
            RuntimeErrorCode.POLICY_DENIED.value: 403,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value: 503,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
