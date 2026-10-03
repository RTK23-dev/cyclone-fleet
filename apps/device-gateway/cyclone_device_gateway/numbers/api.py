"""`/v1/numbers`: Glass → Numbers. Same loopback bearer as every gateway route. Numbers only, never a text or a code."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from .service import NumbersError, NumbersService


def create_numbers_router(service: NumbersService, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn):
        try:
            return fn()
        except NumbersError as exc:
            status = {"NOT_FOUND": 404, "CONFLICT": 409}.get(exc.code, 400)
            raise HTTPException(status_code=status, detail={"code": exc.code, "message": exc.message}) from exc

    @router.get("/v1/numbers", dependencies=[Depends(auth)])
    def numbers(refresh: bool = Query(default=False)):
        return call(lambda: service.overview(refresh=refresh))

    @router.post("/v1/numbers", dependencies=[Depends(auth)])
    def add(body: Any = Body(...)):
        return call(lambda: service.add(body))

    @router.post("/v1/numbers/{number_id}", dependencies=[Depends(auth)])
    def update(number_id: str, body: Any = Body(...)):
        return call(lambda: service.update(number_id, body))

    @router.post("/v1/numbers/{number_id}/delete", dependencies=[Depends(auth)])
    def remove(number_id: str):
        return call(lambda: service.remove(number_id))

    @router.get("/v1/accounts/{account_id}/number", dependencies=[Depends(auth)])
    def account_number(account_id: str):
        """Read only, for agents and Account Setup: the number an account uses."""
        return {"number": call(lambda: service.number_for_account(account_id))}

    return router
