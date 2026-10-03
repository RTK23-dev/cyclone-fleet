from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from ..adb.client import ADBClient
from ..adb.onboarding import ADBTransportOnboarding, TransportOnboardingError
from ..auth import verify_bearer


class WirelessPairBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pair_endpoint: str = Field(min_length=1, max_length=320)
    connect_endpoint: str = Field(min_length=1, max_length=320)
    pairing_code: str = Field(min_length=1, max_length=16)


class RemoteConnectBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    endpoint: str = Field(min_length=1, max_length=320)


class RemoteDisconnectBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    endpoint: str = Field(min_length=1, max_length=320)


def create_transport_router(runtime: Any, token: str) -> APIRouter:
    """Authenticated, allowlisted ADB onboarding routes for Cyclone One.

    The router exposes only USB inventory, Android Wireless Debugging pair/connect,
    VMOS/remote ADB connect, and endpoint disconnect. It never accepts an ADB argv list,
    shell string, command name, or arbitrary subprocess payload.
    """

    router = APIRouter()
    onboarding = ADBTransportOnboarding(ADBClient(runtime.settings.adb_path))

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def run(action, *, refresh_source: str | None = None) -> dict[str, object]:
        try:
            result = action()
            if refresh_source is not None:
                runtime.fleet.refresh_once(source=refresh_source)
            return result
        except TransportOnboardingError as exc:
            raise HTTPException(
                status_code=400,
                detail={"code": exc.code, "message": exc.safe_message},
            ) from exc

    @router.get("/v1/transport/usb", dependencies=[Depends(auth)])
    def usb_status() -> dict[str, object]:
        return run(onboarding.usb_status)

    @router.post("/v1/transport/wifi/pair", dependencies=[Depends(auth)])
    def wifi_pair(body: WirelessPairBody) -> dict[str, object]:
        return run(
            lambda: onboarding.pair_wireless(
                body.pair_endpoint,
                body.pairing_code,
                body.connect_endpoint,
            ),
            refresh_source="transport-wifi",
        )

    @router.post("/v1/transport/vmos/connect", dependencies=[Depends(auth)])
    def vmos_connect(body: RemoteConnectBody) -> dict[str, object]:
        return run(
            lambda: onboarding.connect(body.endpoint, mode="vmos"),
            refresh_source="transport-vmos",
        )

    @router.post("/v1/transport/disconnect", dependencies=[Depends(auth)])
    def disconnect(body: RemoteDisconnectBody) -> dict[str, object]:
        return run(
            lambda: onboarding.disconnect(body.endpoint),
            refresh_source="transport-disconnect",
        )

    return router
