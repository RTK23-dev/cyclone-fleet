from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..desktop_runtime.v5_contract import V5ContractService


def create_v5_contract_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    service = getattr(runtime, "v5_contract", None) or V5ContractService(runtime.fleet)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/devices/{device_id}/atlas/places", dependencies=[Depends(auth)])
    def atlas_places(device_id: str):
        return _call(lambda: service.atlas_places(device_id))

    @router.get("/v1/devices/{device_id}/apps", dependencies=[Depends(auth)])
    def apps_list(device_id: str):
        return _call(lambda: service.apps_list(device_id))

    # Plan 43 T4: the phone's profiles, switching between them, and each Cyclone profile's apps.
    @router.get("/v1/devices/{device_id}/profiles", dependencies=[Depends(auth)])
    def profiles_list(device_id: str):
        return _call(lambda: service.profiles_list(device_id))

    @router.get("/v1/devices/{device_id}/profiles/{profile_id}/apps", dependencies=[Depends(auth)])
    def profiles_apps(device_id: str, profile_id: str):
        return _call(lambda: service.profiles_apps(device_id, profile_id))

    @router.post("/v1/devices/{device_id}/profiles/{profile_id}/switch", dependencies=[Depends(auth)])
    def profiles_switch(device_id: str, profile_id: str):
        return _call(lambda: service.profiles_switch(device_id, profile_id))

    @router.post("/v1/devices/{device_id}/profiles/{profile_id}/apps", dependencies=[Depends(auth)])
    def profiles_app(device_id: str, profile_id: str, body: dict[str, Any]):
        if not isinstance(body, dict) or set(body) != {"package", "action"}:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": "Send {package, action: install|remove}."})
        return _call(lambda: service.profiles_app(device_id, profile_id, body["package"], body["action"]))

    @router.get("/v1/devices/{device_id}/atlas/here", dependencies=[Depends(auth)])
    def atlas_here(device_id: str):
        return _call(lambda: service.atlas_here(device_id))

    @router.get("/v1/devices/{device_id}/share/status", dependencies=[Depends(auth)])
    def share_status(device_id: str):
        return _call(lambda: service.share_status(device_id))

    @router.post("/v1/devices/{device_id}/share/request", dependencies=[Depends(auth)])
    def share_request(device_id: str):
        trust = getattr(runtime, "trust", None)
        return _call(lambda: service.share_request(device_id, getattr(trust, "pc_label", None)))

    @router.get("/v1/devices/{device_id}/knowledge", dependencies=[Depends(auth)])
    def knowledge_summary(device_id: str):
        return _call(lambda: service.knowledge_summary(device_id))

    @router.get("/v1/devices/{device_id}/apps/versions", dependencies=[Depends(auth)])
    def atlas_versions(device_id: str, placeId: str = Query(min_length=9, max_length=200)):
        return _call(lambda: service.atlas_versions(device_id, placeId))

    @router.get("/v1/devices/{device_id}/apps/scenarios", dependencies=[Depends(auth)])
    def scenarios_list(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=200),
        persona: Literal["live", "mapping"] = Query(default="mapping"),
    ):
        return _call(lambda: service.scenarios_list(device_id, placeId, persona))

    @router.get("/v1/devices/{device_id}/runs", dependencies=[Depends(auth)])
    def runs_list(
        device_id: str,
        limit: int = Query(default=50, ge=1, le=200),
        filter: Literal["all", "failed", "completed", "stopped"] = Query(default="all"),
    ):
        return _call(lambda: service.runs_list(device_id, limit, filter))

    @router.post("/v1/devices/{device_id}/runs/{run_id}/mark", dependencies=[Depends(auth)])
    def runs_mark(device_id: str, run_id: str, body: dict[str, Any]):
        if set(body) != {"expected"} or not isinstance(body.get("expected"), bool):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"expected\": true|false}."})
        return _call(lambda: service.runs_mark(device_id, run_id, body["expected"]))

    @router.get("/v1/devices/{device_id}/skills", dependencies=[Depends(auth)])
    def skills_list(device_id: str):
        return _call(lambda: service.skills_list(device_id))

    @router.get("/v1/devices/{device_id}/dictionary", dependencies=[Depends(auth)])
    def dictionary_get(device_id: str, placeId: str = Query(..., max_length=200)):
        return _call(lambda: service.dictionary_get(device_id, placeId))

    @router.post("/v1/devices/{device_id}/dictionary/edit", dependencies=[Depends(auth)])
    def dictionary_edit(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.dictionary_edit(device_id, body))

    @router.get("/v1/devices/{device_id}/models", dependencies=[Depends(auth)])
    def models_list(device_id: str):
        return _call(lambda: service.models_list(device_id))

    @router.get("/v1/devices/{device_id}/manual", dependencies=[Depends(auth)])
    def manual_get(device_id: str, placeId: str = Query(..., max_length=200), q: str | None = Query(None, max_length=200)):
        return _call(lambda: service.manual_get(device_id, placeId, q))

    @router.post("/v1/devices/{device_id}/runs/{run_id}/learn", dependencies=[Depends(auth)])
    def runs_learn(device_id: str, run_id: str):
        return _call(lambda: service.learn_run(device_id, run_id))

    @router.get("/v1/devices/{device_id}/runs/{run_id}", dependencies=[Depends(auth)])
    def runs_get(device_id: str, run_id: str):
        return _call(lambda: service.runs_get(device_id, run_id))

    @router.get("/v1/devices/{device_id}/atlas", dependencies=[Depends(auth)])
    def atlas_get(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
    ):
        return _call(lambda: service.atlas_get(device_id, placeId, persona))

    @router.get("/v1/devices/{device_id}/atlas/diff", dependencies=[Depends(auth)])
    def atlas_diff(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
        since: str | None = Query(default=None, max_length=128),
    ):
        return _call(lambda: service.atlas_diff(device_id, placeId, persona, since))

    @router.post("/v1/devices/{device_id}/mapping/start", dependencies=[Depends(auth)])
    def mapping_start(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.start", body))

    @router.post("/v1/devices/{device_id}/mapping/pause", dependencies=[Depends(auth)])
    def mapping_pause(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.pause", body))

    @router.post("/v1/devices/{device_id}/mapping/stop", dependencies=[Depends(auth)])
    def mapping_stop(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.stop", body))

    @router.post("/v1/devices/{device_id}/mapping/status", dependencies=[Depends(auth)])
    def mapping_status(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.status", body))

    @router.post("/v1/devices/{device_id}/ask/start", dependencies=[Depends(auth)])
    def ask_start(device_id: str, body: dict[str, Any]):
        # Goal text only; secret-bearing payloads are rejected before forwarding or logging.
        return _call(lambda: service.forward(device_id, "ask.start", body))

    @router.post("/v1/devices/{device_id}/ask/status", dependencies=[Depends(auth)])
    def ask_status(device_id: str, body: dict[str, Any] | None = None):
        # Alpha 91: {"requestId": ...} reads one request back (lane, who decided, times); without it, the latest.
        return _call(lambda: service.forward(device_id, "ask.status", body or {}))

    @router.post("/v1/devices/{device_id}/ask/cancel", dependencies=[Depends(auth)])
    def ask_cancel(device_id: str, body: dict[str, Any] | None = None):
        # Stops what a request started: its Instant run, or its Flash/Mind task through the phone's Task Kit.
        return _call(lambda: service.forward(device_id, "ask.cancel", body or {}))

    @router.get("/v1/devices/{device_id}/secrets/slots", dependencies=[Depends(auth)])
    def secret_slots(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
    ):
        return _call(lambda: service.secret_slots(device_id, placeId, persona))

    @router.post("/v1/devices/{device_id}/secrets/request", dependencies=[Depends(auth)])
    def secret_request(device_id: str, body: dict[str, Any]):
        # Raw object is inspected by the contract service before schema-style field validation.
        # This avoids framework validation responses echoing an accidental secret-bearing extra.
        return _call(lambda: service.forward(device_id, "secrets.request", body))

    return router


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        code = str(exc.code)
        status = {
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.SESSION_REQUIRED.value: 400,
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH.value: 409,
            RuntimeErrorCode.HUMAN_HAS_CONTROL.value: 409,
            RuntimeErrorCode.STALE_CONTROL_REVISION.value: 409,
            RuntimeErrorCode.MAPPING_PLANE_BUSY.value: 409,
            RuntimeErrorCode.MAPPING_JOB_NOT_FOUND.value: 404,
            RuntimeErrorCode.RUN_NOT_FOUND.value: 404,
            RuntimeErrorCode.MAPPING_INVALID_STATE.value: 409,
            RuntimeErrorCode.ASK_BUSY.value: 409,
            RuntimeErrorCode.OVERLAY_UNAVAILABLE.value: 503,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.PROTOCOL_MISMATCH.value: 502,
        }.get(code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
