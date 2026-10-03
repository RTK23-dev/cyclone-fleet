"""`/v1/cloud/*`: the owner's cloud accounts and which phones Cyclone keeps connected. Same loopback bearer as every
gateway route. No route takes a command, an adb argument or a URL to call; keys go in and never come back out."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from .models import ProviderError
from .service import CloudFleetService


class AccountBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provider: str = Field(min_length=1, max_length=16)
    label: str | None = Field(default=None, max_length=40)
    secrets: dict[str, str] = Field(default_factory=dict, max_length=4)
    installCyclone: bool = True
    baseUrl: str | None = Field(default=None, max_length=200)
    endpoints: dict[str, str] = Field(default_factory=dict, max_length=4)


class PhoneBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    keep: bool | None = None
    address: str | None = Field(default=None, max_length=300)


class AddressBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    address: str = Field(min_length=3, max_length=300)
    name: str | None = Field(default=None, max_length=80)


def create_cloud_fleet_router(service: CloudFleetService, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn) -> Any:
        try:
            return fn()
        except ProviderError as exc:
            raise HTTPException(status_code=502, detail=exc.to_dict()) from exc
        except DesktopRuntimeError as exc:
            status = {RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404, RuntimeErrorCode.INVALID_REQUEST.value: 400}.get(exc.code, 503)
            raise HTTPException(status_code=status, detail=exc.to_dict()) from exc

    @router.get("/v1/cloud", dependencies=[Depends(auth)])
    def cloud_status():
        return call(service.status)

    @router.post("/v1/cloud/accounts", dependencies=[Depends(auth)])
    def add_account(body: AccountBody):
        return call(lambda: service.add_account(body.model_dump()))

    @router.post("/v1/cloud/accounts/{account_id}/remove", dependencies=[Depends(auth)])
    def remove_account(account_id: str):
        return call(lambda: service.remove_account(account_id))

    @router.post("/v1/cloud/accounts/{account_id}/refresh", dependencies=[Depends(auth)])
    def refresh(account_id: str):
        return call(lambda: (service.refresh_phones(account_id), service.account_public(account_id))[1])

    @router.post("/v1/cloud/accounts/{account_id}/addresses", dependencies=[Depends(auth)])
    def add_address(account_id: str, body: AddressBody):
        return call(lambda: service.add_address(account_id, body.address, body.name))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}", dependencies=[Depends(auth)])
    def set_phone(account_id: str, remote_id: str, body: PhoneBody):
        return call(lambda: service.set_phone(account_id, remote_id, keep=body.keep, address=body.address))

    return router
