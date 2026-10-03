"""`/v1/devices/{id}/care`: one calm answer per phone, and the owner's Update button. Same loopback bearer as every
gateway route. No free-form input: the update always installs this PC's own verified phone build."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from .service import PhoneCareService


def create_phone_care_router(service: PhoneCareService, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn):
        try:
            return fn()
        except DesktopRuntimeError as exc:
            status = {RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404, RuntimeErrorCode.DEVICE_NOT_READY.value: 409}.get(exc.code, 503)
            raise HTTPException(status_code=status, detail=exc.to_dict()) from exc

    @router.get("/v1/devices/{device_id}/care", dependencies=[Depends(auth)])
    def phone_care(device_id: str):
        return call(lambda: service.care(device_id))

    @router.post("/v1/devices/{device_id}/care/update", dependencies=[Depends(auth)])
    def phone_care_update(device_id: str):
        return call(lambda: service.start_update(device_id))

    return router
