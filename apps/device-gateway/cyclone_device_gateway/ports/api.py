"""Port Hub routes for Glass (plan 48). Same loopback bearer as every gateway route. They manage plugins: add, check,
allow ports, pause, test, new key, review changes, remove. A plugin key appears in exactly two answers (add and new
key), once, and never again.

The PC agent MCP servers do not call these routes: adding a plugin and allowing its ports stays a person's act in Glass.
"""
from __future__ import annotations

import json
from typing import Any

from starlette.concurrency import run_in_threadpool

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response

from ..auth import verify_bearer
from .hub import PortHub, PortsError
from .traffic import TrafficError


def create_ports_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def hub() -> PortHub:
        found = getattr(runtime, "ports", None)
        if found is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE",
                                                         "message": "Cyclone Ports isn't available in this build."})
        return found

    def call(fn):
        try:
            return fn()
        except (PortsError, TrafficError) as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc

    def body_of(body: Any, *keys: str) -> dict[str, Any]:
        if not isinstance(body, dict) or any(k not in body for k in keys):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST",
                                                         "message": f"Send a JSON object with {', '.join(keys)}."})
        return body

    @router.get("/v1/ports/overview", dependencies=[Depends(auth)])
    def overview():
        return call(lambda: hub().overview())

    @router.get("/v1/ports/starters/id-generator", dependencies=[Depends(auth)])
    def id_generator(refresh: bool = False):
        return call(lambda: hub().id_generator.status(refresh=refresh))

    @router.post("/v1/ports/starters/id-generator/config", dependencies=[Depends(auth)])
    def id_generator_config(body: dict[str, Any]):
        return call(lambda: hub().id_generator.configure(body))

    @router.post("/v1/ports/starters/id-generator/connect", dependencies=[Depends(auth)])
    def id_generator_connect(body: dict[str, Any]):
        b = body_of(body, "allowed")
        return call(lambda: hub().id_generator.connect(b["allowed"]))

    @router.get("/v1/ports/starters/id-generator/schema", dependencies=[Depends(auth)])
    def id_generator_schema():
        return call(lambda: hub().id_generator.schema())

    @router.get("/v1/ports/skills", dependencies=[Depends(auth)])
    def skills(app: str = Query(default="", max_length=120), routine: str = Query(default="", max_length=60)):
        def approved():
            hub().resolve(routine, app)  # validate context before advertising
            return {"skills": hub().id_generator.skills() if hub().id_generator.allows(app or None, routine or None) else []}
        return call(approved)

    @router.post("/v1/ports/plugins/preview", dependencies=[Depends(auth)])
    def preview(body: dict[str, Any]):
        b = body_of(body, "endpoint")
        return call(lambda: hub().preview(b["endpoint"]))

    @router.post("/v1/ports/plugins", dependencies=[Depends(auth)])
    def add(body: dict[str, Any]):
        b = body_of(body, "endpoint", "allowed")
        return call(lambda: hub().add(b["endpoint"], b["allowed"]))

    @router.get("/v1/ports/plugins/{name}", dependencies=[Depends(auth)])
    def plugin(name: str):
        return call(lambda: hub().plugin(name))

    @router.post("/v1/ports/plugins/{name}/check", dependencies=[Depends(auth)])
    def check(name: str):
        return call(lambda: hub().check(name))

    @router.post("/v1/ports/plugins/{name}/ports", dependencies=[Depends(auth)])
    def set_port(name: str, body: dict[str, Any]):
        b = body_of(body, "port", "allowed")
        return call(lambda: hub().set_port(name, b["port"], b["allowed"]))

    @router.post("/v1/ports/plugins/{name}/pause", dependencies=[Depends(auth)])
    def pause(name: str, body: dict[str, Any]):
        b = body_of(body, "paused")
        return call(lambda: hub().pause(name, b["paused"]))

    @router.post("/v1/ports/plugins/{name}/test", dependencies=[Depends(auth)])
    def test(name: str):
        return call(lambda: hub().send_test(name))

    @router.post("/v1/ports/plugins/{name}/key", dependencies=[Depends(auth)])
    def new_key(name: str):
        return call(lambda: hub().new_key(name))

    @router.post("/v1/ports/plugins/{name}/approve", dependencies=[Depends(auth)])
    def approve(name: str):
        return call(lambda: hub().approve_changes(name))

    @router.post("/v1/ports/plugins/{name}/delete", dependencies=[Depends(auth)])
    def delete(name: str):
        return call(lambda: hub().remove(name))

    # Run 2: which plugin serves each port, everywhere or for one routine or app.
    @router.get("/v1/ports/bindings", dependencies=[Depends(auth)])
    def bindings(scope: str = Query(default="default", max_length=120)):
        return call(lambda: hub().bindings(scope))

    @router.post("/v1/ports/bindings", dependencies=[Depends(auth)])
    def set_binding(body: dict[str, Any]):
        b = body_of(body, "scope", "port", "plugins")
        return call(lambda: hub().set_binding(b["scope"], b["port"], b["plugins"]))

    @router.get("/v1/ports/resolve", dependencies=[Depends(auth)])
    def resolve(routine: str = Query(default="", max_length=60), app: str = Query(default="", max_length=120)):
        return call(lambda: hub().resolve(routine, app))

    # Run 3: live traffic. These carry a run's messages; run 4 connects the phone to them.
    @router.get("/v1/ports/activity", dependencies=[Depends(auth)])
    def activity(plugin: str = Query(default="", max_length=60), port: str = Query(default="", max_length=120),
                 run: str = Query(default="", max_length=80), status: str = Query(default="", pattern=r"^(|ok|failed)$"),
                 limit: int = Query(default=100, ge=1, le=500)):
        ok = None if not status else status == "ok"
        return call(lambda: {"activity": hub().store.activity(limit, plugin or None, port=port or None,
                                                               run_id=run or None, ok=ok)})

    @router.get("/v1/ports/runs", dependencies=[Depends(auth)])
    def runs(limit: int = Query(default=30, ge=1, le=100)):
        return call(lambda: {"runs": hub().store.runs(limit), "testRuns": hub().test_runs.list()})

    @router.get("/v1/ports/runs/{run_id}", dependencies=[Depends(auth)])
    def run(run_id: str):
        h = hub()
        return call(lambda: {"runId": run_id, "activity": list(reversed(h.store.activity(300, run_id=run_id))),
                             "waits": [_public_wait(w) for w in h.store.waits(run_id=run_id)]})

    @router.post("/v1/ports/runs/{run_id}/emit", dependencies=[Depends(auth)])
    def emit(run_id: str, body: dict[str, Any]):
        b = body_of(body, "port")
        return call(lambda: hub().traffic.emit(run_id, b["port"], b.get("data"), b.get("meta") or {}, b.get("file"),
                                               b.get("pageKey")))

    @router.post("/v1/ports/runs/{run_id}/await", dependencies=[Depends(auth)])
    def await_port(run_id: str, body: dict[str, Any]):
        b = body_of(body, "port")
        return call(lambda: hub().traffic.wait(run_id, b["port"], b.get("match"), b.get("timeoutS", 120), b.get("meta") or {}))

    @router.get("/v1/ports/waits/{await_id}", dependencies=[Depends(auth)])
    def wait_result(await_id: str, waitS: float = Query(default=0, ge=0, le=30)):
        return call(lambda: hub().traffic.result(await_id, waitS))

    @router.post("/v1/ports/waits/{await_id}/cancel", dependencies=[Depends(auth)])
    def wait_cancel(await_id: str):
        return call(lambda: hub().traffic.cancel(await_id, "cancelled by the owner"))

    @router.post("/v1/ports/test-runs", dependencies=[Depends(auth)])
    def start_test_run(body: dict[str, Any]):
        b = body_of(body, "scenario")
        return call(lambda: hub().test_runs.start(b["scenario"], b.get("routine"), b.get("app")))

    @router.get("/v1/ports/test-runs/{run_id}", dependencies=[Depends(auth)])
    def test_run(run_id: str):
        return call(lambda: hub().test_runs.get(run_id))

    @router.post("/v1/ports/test-runs/{run_id}/stop", dependencies=[Depends(auth)])
    def stop_test_run(run_id: str):
        return call(lambda: hub().test_runs.stop(run_id))

    # Plugins call these two without the Glass bearer: the run's port token, or the one-time artifact token, is the key.
    @router.get("/v1/ports/artifacts/{artifact_id}", include_in_schema=False)
    def artifact(artifact_id: str, t: str = Query(default="", max_length=80)):
        found = getattr(runtime, "ports", None)
        if found is None:
            return Response(status_code=404)
        status, data, mime = found.traffic.artifact(artifact_id, t)
        return Response(content=data, status_code=status, media_type=mime, headers={"Cache-Control": "no-store"})

    @router.post("/v1/ports/{run_id}/{port}/deliver", include_in_schema=False)
    async def deliver(run_id: str, port: str, request: Request):
        found = getattr(runtime, "ports", None)
        if found is None:
            return JSONResponse({"error": {"code": "unavailable", "message": "Cyclone Ports isn't available.", "retryable": True}}, 503)
        length = int(request.headers.get("content-length") or 0)
        if length > MAX_DELIVERY:
            return JSONResponse({"error": {"code": "too_large", "message": "too large", "retryable": False}}, 413)
        raw = await request.body()
        if len(raw) > MAX_DELIVERY:
            return JSONResponse({"error": {"code": "too_large", "message": "too large", "retryable": False}}, 413)
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            return JSONResponse({"error": {"code": "bad_json", "message": "bad json", "retryable": False}}, 400)
        status, answer = await run_in_threadpool(found.traffic.deliver, run_id, port, request.headers.get("authorization"), body)
        return JSONResponse(answer, status)

    return router


MAX_DELIVERY = 28 * 1024 * 1024  # a 20 MB file in base64 plus the envelope


def _public_wait(wait: dict[str, Any]) -> dict[str, Any]:
    request = wait["request"]
    return {"awaitId": wait["awaitId"], "port": wait["port"], "plugin": wait["plugin"], "state": wait["state"],
            "timeoutAt": wait["timeoutAt"], "createdAt": wait["createdAt"], "match": request.get("match") or {},
            "result": wait["result"]}
