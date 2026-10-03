"""HTTP adapters for phone_skill_save / phone_skill_run.

Routes forward to Android SkillCompiler.compile / AutomationStore.
No PC-side skill JSON store is created.
"""
from __future__ import annotations

from typing import Any

from fastapi import Depends, Header, HTTPException, Query

from . import skills as skill_adapter
from .api.schemas import SkillRunRequest, SkillSaveRequest
from .auth import verify_bearer


def attach_to_app(app) -> None:
    """Register skill adapters on a Desktop FastAPI app (create_desktop_app)."""
    if getattr(app.state, "_cyclone_v37_skill_routes", False):
        return
    _install_agent_methods()
    runtime = getattr(app.state, "desktop_runtime", None)
    if runtime is None:
        return
    app.state._cyclone_v37_skill_routes = True
    token = runtime.settings.token

    def auth(authorization: str | None = Header(default=None)):
        verify_bearer(authorization, token)

    def _device_or_fallback(device_id: str | None) -> str:
        if device_id:
            return device_id
        for item in runtime.fleet.list_public():
            candidate = str(item.get("deviceId") or item.get("id") or "")
            if not candidate:
                continue
            session = runtime.fleet.get(candidate)
            if getattr(session, "credential", None):
                return candidate
        raise HTTPException(
            status_code=503,
            detail={"code": "CAPABILITY_UNAVAILABLE", "message": "No paired phone is available for skill compile/run."},
        )

    @app.post("/v1/skills/save", dependencies=[Depends(auth)])
    def skill_save(request: SkillSaveRequest):
        return _invoke(lambda: runtime.agent.skill_save(_device_or_fallback(None), request.model_dump()))

    @app.post("/v1/skills/{skill_id}/runs", dependencies=[Depends(auth)])
    def skill_run(skill_id: str, request: SkillRunRequest):
        return _invoke(lambda: runtime.agent.skill_run(
            _device_or_fallback(None),
            skill_id,
            dry_run=request.dryRun,
            params=request.params,
        ))

    @app.get("/v1/skills/match", dependencies=[Depends(auth)])
    def skill_match(
        goal: str = Query(min_length=1, max_length=1000),
        pageKey: str = Query(default="", max_length=240),
    ):
        return _invoke(lambda: runtime.agent.skill_match(_device_or_fallback(None), goal, pageKey))

    @app.post("/v1/devices/{device_id}/agent/skills", dependencies=[Depends(auth)])
    def device_skill_save(device_id: str, request: SkillSaveRequest):
        return _invoke(lambda: runtime.agent.skill_save(device_id, request.model_dump()))

    @app.post("/v1/devices/{device_id}/agent/skills/{skill_id}/runs", dependencies=[Depends(auth)])
    def device_skill_run(device_id: str, skill_id: str, request: SkillRunRequest):
        return _invoke(lambda: runtime.agent.skill_run(device_id, skill_id, dry_run=request.dryRun, params=request.params))

    @app.get("/v1/devices/{device_id}/agent/skills", dependencies=[Depends(auth)])
    def device_skill_match(
        device_id: str,
        goal: str = Query(min_length=1, max_length=1000),
        pageKey: str = Query(default="", max_length=240),
    ):
        return _invoke(lambda: runtime.agent.skill_match(device_id, goal, pageKey))


def _invoke(fn):
    from .desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode

    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = {
            RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value: 503,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc


def _install_agent_methods() -> None:
    from .desktop_runtime.agent import DesktopAgentService

    if getattr(DesktopAgentService, "_cyclone_v37_skill_loop", False):
        return

    def skill_save(self, device_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, skill_adapter.COMPILE_OP, skill_adapter.compile_args(payload))
        wrapped = skill_adapter.wrap_compile_result(result)
        return {**self._operation_context(session, device_id, "skill_save"), **wrapped}

    def skill_run(
        self,
        device_id: str,
        skill_id: str,
        *,
        dry_run: bool = False,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(
            session,
            skill_adapter.RUN_OP,
            skill_adapter.run_args(skill_id, dry_run=dry_run, params=params),
        )
        body = result if isinstance(result, dict) else {"raw": result}
        return {**self._operation_context(session, device_id, "skill_run"), **body}

    def skill_match(self, device_id: str, goal: str, page_key: str = "") -> dict[str, Any]:
        session = self._paired(device_id)
        result = self._request(session, skill_adapter.MATCH_OP, skill_adapter.match_args(goal, page_key))
        body = result if isinstance(result, dict) else {"raw": result}
        return {**self._operation_context(session, device_id, "skill_match"), **body}

    DesktopAgentService.skill_save = skill_save
    DesktopAgentService.skill_run = skill_run
    DesktopAgentService.skill_match = skill_match
    DesktopAgentService._cyclone_v37_skill_loop = True
