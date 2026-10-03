"""Plan 31: `/v1/pc/*` routes that replace the Cyclone One desktop window's Remote MCP, ChatGPT Attach and share
controls, plus the run/stop card's "seen" flag. The same loopback bearer as every gateway route; fixed operations
only, no free-form command."""
from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Body, Depends, Header, HTTPException

from ..auth import verify_bearer
from ..tooling_seam import one_runtime_dir
from .attach import AttachService
from .common import PcFeatureError
from .share import ShareService
from .tunnel import TunnelService

WELCOME_FILE = "glass-welcome.json"


def create_pc_router(token: str, port: int, tunnel: TunnelService | None = None, attach: AttachService | None = None,
                     share: ShareService | None = None) -> tuple[APIRouter, ShareService]:
    router = APIRouter()
    tunnel = tunnel or TunnelService()
    attach = attach or AttachService()
    share = share or ShareService(port)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn):
        try:
            return fn()
        except PcFeatureError as exc:
            raise HTTPException(status_code=exc.status, detail={"code": exc.code, "message": str(exc)}) from exc

    def mode_of(body: Any) -> str | None:
        if body is None:
            return None
        if not isinstance(body, dict) or not set(body) <= {"mode"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Only mode may be sent."})
        mode = body.get("mode")
        return mode if isinstance(mode, str) and mode else None

    # Remote MCP tunnel ------------------------------------------------------------------------------------------
    @router.get("/v1/pc/tunnel", dependencies=[Depends(auth)])
    def tunnel_status():
        return call(tunnel.status)

    @router.post("/v1/pc/tunnel/start", dependencies=[Depends(auth)])
    def tunnel_start(body: dict[str, Any] | None = Body(default=None)):
        mode = mode_of(body)
        return call(lambda: tunnel.start(mode))

    @router.post("/v1/pc/tunnel/stop", dependencies=[Depends(auth)])
    def tunnel_stop():
        return call(tunnel.stop)

    @router.post("/v1/pc/tunnel/restart", dependencies=[Depends(auth)])
    def tunnel_restart(body: dict[str, Any] | None = Body(default=None)):
        mode = mode_of(body)
        return call(lambda: tunnel.restart(mode))

    @router.post("/v1/pc/tunnel/rotate", dependencies=[Depends(auth)])
    def tunnel_rotate():
        return call(tunnel.rotate)

    @router.post("/v1/pc/tunnel/mode", dependencies=[Depends(auth)])
    def tunnel_mode(body: dict[str, Any] = Body(...)):
        mode = mode_of(body)
        return call(lambda: tunnel.set_mode(mode or ""))

    @router.post("/v1/pc/tunnel/smoke", dependencies=[Depends(auth)])
    def tunnel_smoke():
        return call(tunnel.smoke)

    @router.get("/v1/pc/tunnel/token", dependencies=[Depends(auth)])
    def tunnel_token():
        return call(tunnel.token)

    @router.get("/v1/pc/tunnel/docs", dependencies=[Depends(auth)])
    def tunnel_docs():
        return call(tunnel.docs)

    # ChatGPT Attach ---------------------------------------------------------------------------------------------
    @router.get("/v1/pc/attach/fleet", dependencies=[Depends(auth)])
    def fleet_load():
        return call(attach.load)

    @router.put("/v1/pc/attach/fleet", dependencies=[Depends(auth)])
    def fleet_save(body: dict[str, Any] = Body(...)):
        return call(lambda: attach.save(body))

    @router.post("/v1/pc/attach/sync", dependencies=[Depends(auth)])
    def fleet_sync():
        return call(attach.sync)

    @router.post("/v1/pc/attach/handoff", dependencies=[Depends(auth)])
    def fleet_handoff(body: dict[str, Any] = Body(...)):
        return call(lambda: attach.save_handoff(body.get("markdown")))

    @router.post("/v1/pc/attach/handoff/check", dependencies=[Depends(auth)])
    def fleet_handoff_check(body: dict[str, Any] = Body(...)):
        """Glass checks before copying to the clipboard: the saved keys are only on this side."""
        def run():
            markdown = body.get("markdown")
            if not isinstance(markdown, str):
                raise PcFeatureError("The handoff is empty.", "INVALID_REQUEST", 422)
            attach.check_handoff(markdown)
            return {"ok": True}
        return call(run)

    @router.get("/v1/pc/attach/resources", dependencies=[Depends(auth)])
    def fleet_resources():
        return call(attach.resources)

    @router.get("/v1/pc/attach/share", dependencies=[Depends(auth)])
    def share_status():
        return call(share.status)

    @router.post("/v1/pc/attach/share/start", dependencies=[Depends(auth)])
    def share_start():
        return call(share.start)

    @router.post("/v1/pc/attach/share/stop", dependencies=[Depends(auth)])
    def share_stop():
        return call(share.stop)

    # The run/stop card ------------------------------------------------------------------------------------------
    @router.get("/v1/pc/welcome", dependencies=[Depends(auth)])
    def welcome():
        try:
            seen = json.loads((one_runtime_dir() / WELCOME_FILE).read_text(encoding="utf-8")).get("seen") is True
        except (OSError, ValueError, AttributeError):
            seen = False
        return {"seen": seen}

    @router.post("/v1/pc/welcome/seen", dependencies=[Depends(auth)])
    def welcome_seen():
        (one_runtime_dir() / WELCOME_FILE).write_text(json.dumps({"seen": True}), encoding="utf-8")
        return {"seen": True}

    return router, share
