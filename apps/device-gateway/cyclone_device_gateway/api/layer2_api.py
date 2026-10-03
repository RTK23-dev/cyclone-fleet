from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.layer2 import Layer2WorkspaceService
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode


class WorkspaceCommandBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    operation: str = Field(min_length=1, max_length=80)
    params: dict[str, Any] = Field(default_factory=dict)


def create_layer2_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    service = getattr(runtime, "layer2", None) or Layer2WorkspaceService(runtime.fleet)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/devices/{device_id}/workspaces", dependencies=[Depends(auth)])
    def workspace_list(device_id: str):
        return _call(lambda: service.list(device_id))

    @router.post("/v1/devices/{device_id}/workspaces", dependencies=[Depends(auth)])
    def workspace_command(device_id: str, body: WorkspaceCommandBody):
        return _call(lambda: service.command(device_id, body.operation, body.params))

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
            RuntimeErrorCode.GATE.value: 409,
            RuntimeErrorCode.MUTATE_LOCK.value: 409,
            RuntimeErrorCode.TARGET_MISMATCH.value: 409,
            RuntimeErrorCode.STALE_WORKSPACE.value: 409,
            RuntimeErrorCode.QUEUE_EMPTY.value: 409,
            RuntimeErrorCode.USER_UNVERIFIED.value: 409,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
