from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from .service import CloudControlService, CloudSession


SESSION_HEADER = "X-Cyclone-Session-Token"


class SessionMintBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    deviceId: str | None = Field(default=None, max_length=160)
    serial: str | None = Field(default=None, max_length=160)
    ttlSeconds: int = Field(default=7200, ge=60, le=7200)


class ObserveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    includeUiSummary: bool = True


class TapBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    x: float
    y: float
    normalized: bool = False
    goal: str = ""


class SwipeBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    x1: float
    y1: float
    x2: float
    y2: float
    durationMs: int = Field(default=300, ge=100, le=3000)
    goal: str = ""


class TypeBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    text: str = Field(min_length=1, max_length=4096)
    goal: str = ""


class LaunchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    packageName: str = Field(min_length=1, max_length=240)
    activity: str | None = Field(default=None, max_length=240)
    goal: str = ""


class KeyBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: str = Field(min_length=1, max_length=160)
    key: Literal["back", "home", "recents", "enter"]
    goal: str = ""


def create_cloud_control_router(runtime: Any, token: str) -> APIRouter:
    service = CloudControlService(runtime, token)
    runtime.cloud_control = service
    router = APIRouter(prefix="/cloud")

    def principal(authorization: str | None, session_token: str | None = None) -> CloudSession | str:
        # Local One/MCP clients keep using Authorization: Bearer. Custom GPT Actions
        # cannot update their configured API-key secret every time Sync mints a new
        # short-lived session, so the public Action schema passes the allowed
        # SESSION_TOKEN explicitly in X-Cyclone-Session-Token instead. Prefer the
        # scoped session header when both are present (for clean 1.5.3 upgrades).
        credential = f"Bearer {session_token}" if session_token else authorization
        try:
            return service.authenticate(credential)
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=401, detail=exc.to_dict()) from exc

    def bind_base(request: Request) -> None:
        service.public_base = str(request.base_url).rstrip("/") + "/cloud"

    @router.get("")
    @router.get("/")
    def cloud_root(request: Request) -> dict[str, Any]:
        bind_base(request)
        contract = service.public_contract()
        contract["health"] = "/cloud/v1/health"
        return contract

    @router.get("/v1/health")
    def health(request: Request) -> dict[str, Any]:
        bind_base(request)
        return service.public_contract()

    @router.post("/v1/sessions")
    def mint(body: SessionMintBody, request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
        bind_base(request)
        actor = principal(authorization)
        if isinstance(actor, CloudSession) and body.deviceId and actor.device_id != body.deviceId:
            raise HTTPException(status_code=401, detail={"code": "AUTH_REJECTED", "message": "Session token does not match this device."})
        return _call(lambda: service.mint(device_id=body.deviceId, serial=body.serial, ttl_seconds=body.ttlSeconds))

    @router.get("/v1/devices")
    def list_devices(
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.list_devices(principal(authorization, session_token)))

    @router.get("/v1/devices/{device_id}/status")
    def device_status(
        device_id: str,
        request: Request,
        sessionId: str | None = Query(default=None),
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.status(principal(authorization, session_token), device_id, sessionId))

    @router.post("/v1/devices/{device_id}/observe")
    def observe(
        device_id: str,
        body: ObserveBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.observe(
            principal(authorization, session_token),
            device_id,
            session_id=body.sessionId,
            include_ui_summary=body.includeUiSummary,
        ))

    @router.post("/v1/devices/{device_id}/tap")
    def tap(
        device_id: str,
        body: TapBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.tap(principal(authorization, session_token), device_id, body.model_dump()))

    @router.post("/v1/devices/{device_id}/swipe")
    def swipe(
        device_id: str,
        body: SwipeBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.swipe(principal(authorization, session_token), device_id, body.model_dump()))

    @router.post("/v1/devices/{device_id}/type")
    def type_text(
        device_id: str,
        body: TypeBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.type_text(principal(authorization, session_token), device_id, body.model_dump()))

    @router.post("/v1/devices/{device_id}/launch")
    def launch(
        device_id: str,
        body: LaunchBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.launch_app(principal(authorization, session_token), device_id, body.model_dump()))

    @router.post("/v1/devices/{device_id}/key")
    def press_key(
        device_id: str,
        body: KeyBody,
        request: Request,
        authorization: str | None = Header(default=None),
        session_token: str | None = Header(default=None, alias=SESSION_HEADER),
    ) -> dict[str, Any]:
        bind_base(request)
        return _call(lambda: service.press_key(principal(authorization, session_token), device_id, body.model_dump()))

    @router.get("/v1/screenshots/{shot_id}")
    def screenshot(shot_id: str):
        try:
            shot = service.screenshot(shot_id)
        except DesktopRuntimeError as exc:
            status = 404 if exc.code == RuntimeErrorCode.DEVICE_NOT_FOUND.value else 503
            raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
        return Response(content=shot.data, media_type=shot.media_type)

    return router


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = {
            RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
            RuntimeErrorCode.AUTH_REJECTED.value: 401,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value: 503,
            RuntimeErrorCode.HUMAN_HAS_CONTROL.value: 409,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
