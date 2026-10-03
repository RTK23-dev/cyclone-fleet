"""Fleet routes (multi-phone). Same loopback bearer as every gateway route.

These do not talk to a phone and do not hold a model key: each row here is one Command
Center task (/v1/cc/tasks), which is where a goal actually becomes a Mind mission on a
phone, with that phone's own key, model, GATE and Owner Moments. This layer only works
out which phone(s) a sentence is for and tracks the resulting tasks as one mission.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from .fleet_orchestrator import FleetError, FleetOrchestrator
from .models import DesktopRuntimeError
from .scenes import SceneError


def create_fleet_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def fleet() -> FleetOrchestrator:
        orchestrator = getattr(runtime, "fleet_orchestrator", None)
        if orchestrator is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "The fleet is not available."})
        return orchestrator

    def call(fn):
        try:
            return fn()
        except FleetError as exc:
            status = {"NOT_FOUND": 404}.get(exc.code, 400)
            raise HTTPException(status_code=status, detail={"code": exc.code, "message": exc.message}) from exc
        except SceneError as exc:
            status = {"NOT_FOUND": 404}.get(exc.code, 400)
            raise HTTPException(status_code=status, detail={"code": exc.code, "message": exc.message}) from exc
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=409, detail=exc.to_dict()) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc

    def body_of(body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send a JSON object."})
        return body

    def scope_of(b: dict[str, Any]) -> list[str] | None:
        """Which phones a command may touch. `groupId` (a Workspace group) or `deviceIds`; neither means every phone.

        An explicit empty scope is honoured as "no phones", never widened to "all phones".
        """
        group_id = b.get("groupId")
        if group_id is not None:
            group = runtime.workspace.group(str(group_id))
            if group is None:
                raise FleetError("NOT_FOUND", f"No group with id {group_id}.")
            return [str(i) for i in group.get("deviceIds") or []]
        ids = b.get("deviceIds")
        if ids is None:
            return None
        if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
            raise FleetError("INVALID_REQUEST", "deviceIds is a list of phone ids.")
        return ids

    @router.get("/v1/fleet/overview", dependencies=[Depends(auth)])
    def overview():
        return call(lambda: fleet().overview())

    @router.get("/v1/fleet/phones", dependencies=[Depends(auth)])
    def phones():
        nicknames = runtime.workspace.nickname_map()
        return {"phones": [
            {"deviceId": d.device_id, "label": d.label, "nickname": nicknames.get(d.device_id), "paired": d.paired}
            for d in call(lambda: fleet().directory())
        ]}

    @router.post("/v1/fleet/phones/{device_id}/nickname", dependencies=[Depends(auth)])
    def set_nickname(device_id: str, body: dict[str, Any] = Body(...)):
        b = body_of(body)
        def rename():
            if not fleet().knows_device(device_id):
                raise FleetError("NOT_FOUND", "That phone isn't known to the fleet. Connect it first.")
            return runtime.workspace.set_nickname(device_id, str(b.get("nickname") or ""))
        return call(rename)

    @router.post("/v1/fleet/command/preview", dependencies=[Depends(auth)])
    def preview(body: dict[str, Any] = Body(...)):
        b = body_of(body)
        command = str(b.get("command") or "")
        return call(lambda: fleet().plan(command, scope_of(b)).public())

    @router.post("/v1/fleet/command", dependencies=[Depends(auth)])
    def run_command(body: dict[str, Any] = Body(...)):
        b = body_of(body)
        command = str(b.get("command") or "")
        skip = b.get("skipDeviceIds")
        return call(lambda: fleet().run_command(
            command, confirm=bool(b.get("confirm")), device_ids=scope_of(b), client_request_id=b.get("requestId"),
            skip_device_ids=skip if isinstance(skip, list) else None,
        ))

    @router.post("/v1/fleet/dispatch", dependencies=[Depends(auth)])
    def dispatch(body: dict[str, Any] = Body(...)):
        b = body_of(body)
        assignments = b.get("assignments")
        if not isinstance(assignments, list):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "assignments is a list."})
        return call(lambda: fleet().dispatch(assignments, str(b.get("command") or ""), client_request_id=b.get("requestId")))

    @router.get("/v1/fleet/health", dependencies=[Depends(auth)])
    def fleet_health():
        return call(lambda: fleet().health())

    @router.post("/v1/fleet/pause", dependencies=[Depends(auth)])
    def fleet_pause(body: dict[str, Any] = Body(...)):
        return call(lambda: fleet().set_paused(bool(body_of(body).get("paused"))))

    @router.post("/v1/fleet/phones/{device_id}/exclude", dependencies=[Depends(auth)])
    def fleet_exclude(device_id: str, body: dict[str, Any] = Body(...)):
        return call(lambda: fleet().set_excluded(device_id, bool(body_of(body).get("excluded", True))))

    @router.get("/v1/fleet/missions/{mission_id}/export", dependencies=[Depends(auth)])
    def export_mission(mission_id: str):
        return call(lambda: fleet().export_mission(mission_id))

    @router.get("/v1/fleet/missions/{mission_id}/export.csv", dependencies=[Depends(auth)])
    def export_mission_csv(mission_id: str):
        from fastapi.responses import PlainTextResponse
        return PlainTextResponse(call(lambda: fleet().export_csv(mission_id)), media_type="text/csv")

    @router.post("/v1/fleet/rollout", dependencies=[Depends(auth)])
    def fleet_rollout(body: dict[str, Any] = Body(...)):
        b = body_of(body)
        assignments = b.get("assignments")
        if not isinstance(assignments, list):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "assignments is a list."})
        return call(lambda: fleet().rollout(assignments, canary=int(b.get("canary") or 1), command=str(b.get("command") or "")))

    @router.get("/v1/fleet/missions", dependencies=[Depends(auth)])
    def missions(limit: int = Query(default=50, ge=1, le=200), cursor: str | None = Query(default=None)):
        return call(lambda: fleet().missions_page(limit, cursor=cursor))

    @router.get("/v1/fleet/missions/{mission_id}", dependencies=[Depends(auth)])
    def mission(mission_id: str):
        return call(lambda: fleet().mission(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/cancel", dependencies=[Depends(auth)])
    def cancel_mission(mission_id: str):
        return call(lambda: fleet().cancel_mission(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/retry", dependencies=[Depends(auth)])
    def retry_mission(mission_id: str):
        return call(lambda: fleet().retry_failed(mission_id))

    @router.post("/v1/fleet/missions/{mission_id}/continue", dependencies=[Depends(auth)])
    def continue_mission(mission_id: str):
        return call(lambda: fleet().continue_rollout(mission_id))

    @router.post("/v1/fleet/spend-cap", dependencies=[Depends(auth)])
    def spend_cap(body: dict[str, Any] = Body(...)):
        raw = body_of(body).get("cap")
        return call(lambda: fleet().set_spend_cap(float(raw) if raw else None))

    @router.post("/v1/fleet/missions/{mission_id}/handoff", dependencies=[Depends(auth)])
    def handoff_mission(mission_id: str, body: dict[str, Any] = Body(...)):
        b = body_of(body)
        return call(lambda: fleet().handoff(mission_id, str(b.get("deviceId") or ""), str(b.get("goal") or "")))

    @router.get("/v1/fleet/approvals", dependencies=[Depends(auth)])
    def fleet_approvals():
        """Owner asks only. Answering stays on the Command Center approval route."""
        return call(lambda: fleet().approvals())

    @router.get("/v1/fleet/queue", dependencies=[Depends(auth)])
    def fleet_queue():
        return call(lambda: fleet().queue())

    @router.post("/v1/fleet/stop-fleet-missions", dependencies=[Depends(auth)])
    def stop_fleet_missions():
        """Cancel every open Command Center task that belongs to a known fleet mission."""
        return call(lambda: fleet().stop_fleet_missions())

    @router.post("/v1/fleet/stop-all", dependencies=[Depends(auth)])
    def stop_all():
        """Emergency stop: cancel every open Command Center task on every phone.

        Glass labels this 'Stop everything'. It is not limited to fleet missions.
        """
        return call(lambda: fleet().stop_all())

    @router.get("/v1/fleet/scenes", dependencies=[Depends(auth)])
    def scenes_list():
        store = getattr(runtime, "scenes", None)
        return {"scenes": store.list_scenes() if store else []}

    @router.post("/v1/fleet/scenes/{scene_id}", dependencies=[Depends(auth)])
    def scenes_put(scene_id: str, body: dict[str, Any] = Body(...)):
        b = body_of(body)
        steps = b.get("steps")
        if not isinstance(steps, list):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "steps is a list."})
        return call(lambda: runtime.scenes.put_scene(scene_id, str(b.get("name") or scene_id), steps))

    @router.post("/v1/fleet/scenes/{scene_id}/delete", dependencies=[Depends(auth)])
    def scenes_delete(scene_id: str):
        call(lambda: runtime.scenes.delete_scene(scene_id))
        return {"sceneId": scene_id, "deleted": True}

    @router.post("/v1/fleet/scenes/{scene_id}/run", dependencies=[Depends(auth)])
    def scenes_run(scene_id: str):
        return call(lambda: fleet().run_scene(runtime.scenes, scene_id, runtime.workspace.resolve_nickname))

    return router
