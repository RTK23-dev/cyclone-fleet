"""Marketplace routes for Glass: the phone's store (typed market.* ops) and this PC's MCP connections."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import V5ContractService
from .pc_connections import PcConnections


def create_market_router(runtime: Any, token: str, connections: PcConnections | None = None) -> APIRouter:
    router = APIRouter()
    pc = connections or PcConnections()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def contract() -> V5ContractService:
        return getattr(runtime, "v5_contract", None) or V5ContractService(runtime.fleet)

    def call(fn):
        try:
            return fn()
        except DesktopRuntimeError as exc:
            status = {"INVALID_REQUEST": 400, "HUMAN_HAS_CONTROL": 409, "ASK_BUSY": 409, "OVERLAY_UNAVAILABLE": 503,
                      "PAIRING_REQUIRED": 401, "DEVICE_DISCONNECTED": 503, "PROTOCOL_MISMATCH": 502}.get(str(exc.code), 503)
            raise HTTPException(status_code=status, detail=exc.to_dict()) from exc

    def inputs(body: dict[str, Any]) -> dict[str, Any] | None:
        if not set(body) <= {"inputs"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"inputs\": {...}}."})
        return body.get("inputs")

    @router.get("/v1/devices/{device_id}/market", dependencies=[Depends(auth)])
    def catalog(device_id: str):
        return call(lambda: contract().market_catalog(device_id))

    @router.post("/v1/devices/{device_id}/market/{listing_id}/install", dependencies=[Depends(auth)])
    def install(device_id: str, listing_id: str, body: dict[str, Any]):
        values = inputs(body)
        return call(lambda: contract().market_change(device_id, "market.install", listing_id, values))

    @router.post("/v1/devices/{device_id}/market/{listing_id}/remove", dependencies=[Depends(auth)])
    def remove(device_id: str, listing_id: str):
        return call(lambda: contract().market_change(device_id, "market.remove", listing_id))

    @router.post("/v1/devices/{device_id}/market/{listing_id}/run", dependencies=[Depends(auth)])
    def run(device_id: str, listing_id: str, body: dict[str, Any]):
        values = inputs(body)
        return call(lambda: contract().market_change(device_id, "market.run", listing_id, values))

    @router.get("/v1/pc/connections", dependencies=[Depends(auth)])
    def pc_connections():
        return pc.status()

    @router.post("/v1/pc/connections/{host}/connect", dependencies=[Depends(auth)])
    def pc_connect(host: str):
        try:
            return pc.connect(host)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail={"code": "NOT_FOUND", "message": str(exc)}) from exc
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": str(exc)}) from exc

    return router
