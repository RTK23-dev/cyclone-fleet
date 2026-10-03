"""Command Center routes for Glass (plan 33, C0). Same loopback bearer as every gateway route. Nothing here takes a
command, a secret value or a free-form phone operation: tasks are goal text that phones run as Mind missions, and
approvals answer one open Owner Moment by id.

The PC agent MCP servers do not call these routes (guarded in CI): approving stays a person's act in Glass.
"""
from __future__ import annotations

from typing import Any

import html

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from .center import CommandCenter, CommandError
from .pages import TEMPLATES as PAGE_TEMPLATES


def create_command_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def cc() -> CommandCenter:
        center = getattr(runtime, "command", None)
        if center is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "The Command Center is not available."})
        return center

    def call(fn):
        from .ai import AiError
        from .pages import PageConflict
        from .tables import TableConflict
        try:
            return fn()
        except PageConflict as exc:
            raise HTTPException(status_code=409, detail={"code": "PAGE_CHANGED", "message": str(exc)}) from exc
        except TableConflict as exc:
            raise HTTPException(status_code=409, detail={"code": "ROW_CHANGED", "message": str(exc)}) from exc
        except AiError as exc:
            raise HTTPException(status_code=409, detail={"code": "AI_UNAVAILABLE", "message": str(exc)}) from exc
        except CommandError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=409, detail=exc.to_dict()) from exc

    def body_of(body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send a JSON object."})
        return body

    @router.get("/v1/cc/overview", dependencies=[Depends(auth)])
    def overview():
        return call(lambda: cc().overview())

    @router.get("/v1/cc/accounts", dependencies=[Depends(auth)])
    def accounts():
        return call(lambda: {"accounts": cc().list_accounts()})

    @router.post("/v1/cc/accounts", dependencies=[Depends(auth)])
    def create_account(body: dict[str, Any]):
        return call(lambda: cc().create_account(body_of(body)))

    @router.post("/v1/cc/accounts/{account_id}", dependencies=[Depends(auth)])
    def update_account(account_id: str, body: dict[str, Any]):
        return call(lambda: cc().update_account(account_id, body_of(body)))

    @router.post("/v1/cc/accounts/{account_id}/delete", dependencies=[Depends(auth)])
    def delete_account(account_id: str):
        return call(lambda: cc().delete_account(account_id))

    @router.get("/v1/cc/tasks", dependencies=[Depends(auth)])
    def tasks(status: str | None = Query(default=None, pattern=r"^(open|done)$")):
        return call(lambda: {"tasks": cc().list_tasks(status=status)})

    @router.post("/v1/cc/tasks", dependencies=[Depends(auth)])
    def create_task(body: dict[str, Any]):
        return call(lambda: cc().create_task(body_of(body)))

    @router.get("/v1/cc/tasks/{task_id}", dependencies=[Depends(auth)])
    def get_task(task_id: str):
        return call(lambda: cc().get_task(task_id))

    @router.post("/v1/cc/tasks/{task_id}/cancel", dependencies=[Depends(auth)])
    def cancel_task(task_id: str):
        return call(lambda: cc().cancel_task(task_id))

    @router.get("/v1/cc/routines", dependencies=[Depends(auth)])
    def routines():
        return call(lambda: {"routines": cc().list_routines()})

    @router.post("/v1/cc/routines", dependencies=[Depends(auth)])
    def create_routine(body: dict[str, Any]):
        return call(lambda: cc().create_routine(body_of(body)))

    @router.post("/v1/cc/routines/pause-all", dependencies=[Depends(auth)])
    def pause_all(body: dict[str, Any]):
        paused = body_of(body).get("paused")
        if not isinstance(paused, bool) or set(body) != {"paused"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"paused\": true|false}."})
        return call(lambda: cc().pause_all(paused))

    @router.post("/v1/cc/routines/{routine_id}", dependencies=[Depends(auth)])
    def update_routine(routine_id: str, body: dict[str, Any]):
        return call(lambda: cc().update_routine(routine_id, body_of(body)))

    @router.post("/v1/cc/routines/{routine_id}/run", dependencies=[Depends(auth)])
    def run_routine(routine_id: str):
        return call(lambda: cc().run_routine_now(routine_id))

    @router.post("/v1/cc/routines/{routine_id}/delete", dependencies=[Depends(auth)])
    def delete_routine(routine_id: str):
        return call(lambda: cc().delete_routine(routine_id))

    @router.get("/v1/cc/results", dependencies=[Depends(auth)])
    def results(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: {"results": cc().results(limit)})

    @router.get("/v1/cc/approvals", dependencies=[Depends(auth)])
    def approvals(state: str = Query(default="open", pattern=r"^(open|answered|withdrawn|all)$")):
        return call(lambda: {"approvals": cc().list_approvals(state=state)})

    @router.post("/v1/cc/approvals/{approval_id}/answer", dependencies=[Depends(auth)])
    def answer(approval_id: str, body: dict[str, Any]):
        return call(lambda: cc().answer(approval_id, body_of(body)))

    # Plan 33 (C1): the vault. Every body is ciphertext made in the browser; nothing here can decrypt it.
    @router.get("/v1/cc/vault", dependencies=[Depends(auth)])
    def vault():
        return call(lambda: cc().vault.get())

    @router.post("/v1/cc/vault/init", dependencies=[Depends(auth)])
    def vault_init(body: dict[str, Any]):
        return call(lambda: cc().vault.init(body))

    @router.post("/v1/cc/vault/rewrap", dependencies=[Depends(auth)])
    def vault_rewrap(body: dict[str, Any]):
        return call(lambda: cc().vault.rewrap(body))

    @router.post("/v1/cc/vault/items", dependencies=[Depends(auth)])
    def vault_put(body: dict[str, Any]):
        return call(lambda: cc().vault.put_item(body))

    @router.post("/v1/cc/vault/import", dependencies=[Depends(auth)])
    def vault_import(body: dict[str, Any]):
        items = body_of(body).get("items")
        if not isinstance(items, list) or not 1 <= len(items) <= 1000 or set(body) != {"items"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {\"items\": [...]} (1..1000)."})
        return call(lambda: {"items": [cc().vault.put_item(item, created_by="import") for item in items]})

    @router.post("/v1/cc/vault/items/{item_id}/delete", dependencies=[Depends(auth)])
    def vault_delete(item_id: str):
        return call(lambda: cc().vault.delete_item(item_id))

    @router.post("/v1/cc/vault/restore", dependencies=[Depends(auth)])
    def vault_restore(body: dict[str, Any]):
        return call(lambda: cc().vault.restore(body))

    @router.post("/v1/cc/vault/reset", dependencies=[Depends(auth)])
    def vault_reset(body: dict[str, Any]):
        return call(lambda: cc().vault.reset(body))

    @router.post("/v1/cc/vault/audit", dependencies=[Depends(auth)])
    def vault_audit(body: dict[str, Any]):
        return call(lambda: cc().vault.client_audit(body))

    # Plan 33 (C2): phones' device keys and sealed leases. Envelopes are opaque bytes the gateway cannot open.
    @router.get("/v1/cc/phones", dependencies=[Depends(auth)])
    def phones():
        return call(lambda: {"phones": cc().delivery.phones()})

    @router.post("/v1/cc/phones/{device_id}/key", dependencies=[Depends(auth)])
    def phone_key(device_id: str):
        return call(lambda: cc().delivery.fetch_key(device_id))

    @router.post("/v1/cc/phones/{device_id}/trust", dependencies=[Depends(auth)])
    def phone_trust(device_id: str, body: dict[str, Any]):
        return call(lambda: cc().delivery.trust(device_id, body_of(body)))

    @router.post("/v1/cc/phones/{device_id}/untrust", dependencies=[Depends(auth)])
    def phone_untrust(device_id: str):
        return call(lambda: cc().delivery.untrust(device_id))

    @router.get("/v1/cc/leases", dependencies=[Depends(auth)])
    def leases(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: {"leases": cc().delivery.leases(limit)})

    @router.get("/v1/cc/leases/pending", dependencies=[Depends(auth)])
    def leases_pending():
        return call(lambda: {"pending": cc().delivery.pending()})

    @router.post("/v1/cc/tasks/{task_id}/leases", dependencies=[Depends(auth)])
    def lease_submit(task_id: str, body: dict[str, Any]):
        return call(lambda: cc().delivery.submit(task_id, body_of(body)))

    @router.post("/v1/cc/leases/{lease_id}/revoke", dependencies=[Depends(auth)])
    def lease_revoke(lease_id: str):
        return call(lambda: cc().delivery.revoke(lease_id))

    # Plan 33 (C3): connections (MCP servers), their calls and the files they make.
    @router.get("/v1/cc/integrations/mrz", dependencies=[Depends(auth)])
    def mrz_status():
        return call(lambda: cc().connections.mrz.snapshot())

    @router.post("/v1/cc/integrations/mrz/check", dependencies=[Depends(auth)])
    def mrz_check():
        return call(lambda: cc().connections.mrz.scan())

    @router.post("/v1/cc/integrations/mrz/connect", dependencies=[Depends(auth)])
    def mrz_connect():
        return call(lambda: cc().connections.mrz.connect())

    @router.get("/v1/cc/connections", dependencies=[Depends(auth)])
    def connections():
        return call(lambda: {"connections": cc().connections.list(), "higgsfield": "https://mcp.higgsfield.ai/mcp", "mrz": cc().connections.mrz.snapshot()})

    @router.post("/v1/cc/connections", dependencies=[Depends(auth)])
    def connection_add(body: dict[str, Any]):
        return call(lambda: cc().connections.add(body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/refresh", dependencies=[Depends(auth)])
    def connection_refresh(connection_id: str):
        return call(lambda: cc().connections.refresh(connection_id))

    @router.post("/v1/cc/connections/{connection_id}/settings", dependencies=[Depends(auth)])
    def connection_settings(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.settings(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/sign-in", dependencies=[Depends(auth)])
    def connection_sign_in(connection_id: str, request: Request):
        redirect = str(request.base_url).rstrip("/") + "/v1/cc/connections/oauth/callback"
        return call(lambda: cc().connections.begin_sign_in(connection_id, redirect))

    @router.post("/v1/cc/connections/{connection_id}/sign-out", dependencies=[Depends(auth)])
    def connection_sign_out(connection_id: str):
        return call(lambda: cc().connections.sign_out(connection_id))

    @router.post("/v1/cc/connections/{connection_id}/remove", dependencies=[Depends(auth)])
    def connection_remove(connection_id: str):
        return call(lambda: cc().connections.remove(connection_id))

    # Plan 34: keys, a pasted OAuth client, and local servers (approve the exact command, env keys, logs).
    @router.post("/v1/cc/connections/{connection_id}/key", dependencies=[Depends(auth)])
    def connection_key(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_key(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/client", dependencies=[Depends(auth)])
    def connection_client(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_client(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/approve", dependencies=[Depends(auth)])
    def connection_approve(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.approve_local(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/config", dependencies=[Depends(auth)])
    def connection_config(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.update_local(connection_id, body_of(body)))

    @router.post("/v1/cc/connections/{connection_id}/env", dependencies=[Depends(auth)])
    def connection_env(connection_id: str, body: dict[str, Any]):
        return call(lambda: cc().connections.set_env(connection_id, body_of(body)))

    @router.get("/v1/cc/connections/{connection_id}/logs", dependencies=[Depends(auth)])
    def connection_logs(connection_id: str):
        return call(lambda: {"lines": cc().connections.logs(connection_id)})

    # Plan 34 M4: connector cards. Export holds no keys or tokens; import is checked like a new connection.
    @router.get("/v1/cc/connections/{connection_id}/card", dependencies=[Depends(auth)])
    def connection_card(connection_id: str):
        return call(lambda: cc().connections.card(connection_id))

    @router.post("/v1/cc/connections/import", dependencies=[Depends(auth)])
    def connection_import(body: dict[str, Any]):
        return call(lambda: cc().connections.import_card(body_of(body)))

    @router.get("/v1/cc/calls/{call_id}", dependencies=[Depends(auth)])
    def call_get(call_id: str):
        return call(lambda: cc().connections.get_call(call_id))

    @router.post("/v1/cc/connections/{connection_id}/call", dependencies=[Depends(auth)])
    def connection_call(connection_id: str, body: dict[str, Any]):
        raw = body_of(body)
        if not set(raw) <= {"tool", "arguments", "pollTool"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Send {tool, arguments, pollTool?}."})
        return call(lambda: cc().connections.call(connection_id, raw.get("tool"), raw.get("arguments", {}), poll_tool=raw.get("pollTool")))

    @router.get("/v1/cc/calls", dependencies=[Depends(auth)])
    def calls(limit: int = Query(default=100, ge=1, le=500)):
        return call(lambda: {"calls": cc().connections.calls(limit)})

    @router.get("/v1/cc/artifacts", dependencies=[Depends(auth)])
    def artifacts(limit: int = Query(default=100, ge=1, le=500)):
        return call(lambda: {"artifacts": cc().connections.artifacts(limit)})

    @router.get("/v1/cc/artifacts/{artifact_id}/file", dependencies=[Depends(auth)])
    def artifact_file(artifact_id: str):
        meta, path = call(lambda: cc().connections.artifact(artifact_id))
        return FileResponse(path, media_type=meta["mime"], filename=meta["name"],
                            headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"})

    # Plan 43 (T1): tables. Typed cells only, no secrets, views computed here, every change kept, trash first.
    # Plan 43 (T5 + T6): accounts by phone and app, and the sign-ups each phone mapped.
    @router.get("/v1/cc/signup/maps", dependencies=[Depends(auth)])
    def signup_maps(deviceId: str = Query(max_length=120)):
        return call(lambda: cc().signup.maps(deviceId))

    @router.post("/v1/cc/signup/map", dependencies=[Depends(auth)])
    def signup_map(body: dict[str, Any]):
        return call(lambda: cc().signup.map_signup(body_of(body)))

    @router.post("/v1/cc/signup/forget", dependencies=[Depends(auth)])
    def signup_forget(body: dict[str, Any]):
        return call(lambda: cc().signup.forget(body_of(body)))

    @router.post("/v1/cc/signup/table", dependencies=[Depends(auth)])
    def signup_table(body: dict[str, Any]):
        return call(lambda: cc().signup.make_table(body_of(body)))

    # Plan 43 (T7): Create accounts from a sign-up table's Ready rows.
    @router.post("/v1/cc/signup/prepare", dependencies=[Depends(auth)])
    def signup_prepare(body: dict[str, Any]):
        return call(lambda: cc().signup.prepare(body_of(body)))

    @router.post("/v1/cc/signup/create", dependencies=[Depends(auth)])
    def signup_create(body: dict[str, Any]):
        return call(lambda: cc().signup.create(body_of(body)))

    @router.post("/v1/cc/signup/pause", dependencies=[Depends(auth)])
    def signup_pause(body: dict[str, Any]):
        return call(lambda: cc().signup.stop(body_of(body), pause=True))

    @router.post("/v1/cc/signup/cancel", dependencies=[Depends(auth)])
    def signup_cancel(body: dict[str, Any]):
        return call(lambda: cc().signup.stop(body_of(body), pause=False))

    @router.get("/v1/cc/tables", dependencies=[Depends(auth)])
    def tables():
        return call(lambda: {"tables": cc().tables.list()})

    @router.post("/v1/cc/tables", dependencies=[Depends(auth)])
    def create_table(body: dict[str, Any]):
        return call(lambda: cc().tables.create(body_of(body)))

    @router.get("/v1/cc/tables-trash", dependencies=[Depends(auth)])
    def tables_trash():
        return call(lambda: cc().tables.trash())

    @router.get("/v1/cc/tables/{table_id}", dependencies=[Depends(auth)])
    def get_table(table_id: str):
        return call(lambda: cc().tables.get(table_id))

    @router.post("/v1/cc/tables/{table_id}", dependencies=[Depends(auth)])
    def update_table(table_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.update(table_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/archive", dependencies=[Depends(auth)])
    def archive_table(table_id: str):
        return call(lambda: cc().tables.archive(table_id))

    @router.post("/v1/cc/tables/{table_id}/restore", dependencies=[Depends(auth)])
    def restore_table(table_id: str):
        return call(lambda: cc().tables.restore(table_id))

    @router.post("/v1/cc/tables/{table_id}/delete", dependencies=[Depends(auth)])
    def delete_table(table_id: str):
        return call(lambda: cc().tables.delete(table_id))

    @router.post("/v1/cc/tables/{table_id}/properties", dependencies=[Depends(auth)])
    def add_property(table_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.add_property(table_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/properties/{prop_id}", dependencies=[Depends(auth)])
    def update_property(table_id: str, prop_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.update_property(table_id, prop_id, body_of(body)))

    @router.get("/v1/cc/tables/{table_id}/properties/{prop_id}/candidates", dependencies=[Depends(auth)])
    def relation_candidates(table_id: str, prop_id: str, q: str | None = Query(default=None, max_length=100)):
        return call(lambda: {"items": cc().tables.candidates(table_id, prop_id, q)})

    # Plan 43 (T3): press a row's button. It only creates ordinary tasks; the phone's approvals still apply.
    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/buttons/{prop_id}", dependencies=[Depends(auth)])
    def press_button(table_id: str, row_id: str, prop_id: str):
        return call(lambda: cc().buttons.press(table_id, row_id, prop_id))

    @router.post("/v1/cc/tables/{table_id}/properties/{prop_id}/delete", dependencies=[Depends(auth)])
    def delete_property(table_id: str, prop_id: str):
        return call(lambda: cc().tables.delete_property(table_id, prop_id))

    @router.post("/v1/cc/tables/{table_id}/views", dependencies=[Depends(auth)])
    def add_view(table_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.add_view(table_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/views/{view_id}", dependencies=[Depends(auth)])
    def update_view(table_id: str, view_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.update_view(table_id, view_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/views/{view_id}/delete", dependencies=[Depends(auth)])
    def delete_view(table_id: str, view_id: str):
        return call(lambda: cc().tables.delete_view(table_id, view_id))

    @router.get("/v1/cc/tables/{table_id}/rows", dependencies=[Depends(auth)])
    def table_rows(table_id: str, view: str | None = Query(default=None, max_length=60), q: str | None = Query(default=None, max_length=100)):
        return call(lambda: cc().tables.rows(table_id, view, q))

    @router.get("/v1/cc/tables/{table_id}/export.csv", dependencies=[Depends(auth)])
    def table_csv(table_id: str, view: str | None = Query(default=None, max_length=60)):
        from fastapi.responses import Response
        text = call(lambda: cc().tables.export_csv(table_id, view))
        return Response(text, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": 'attachment; filename="table.csv"'})

    @router.post("/v1/cc/tables/{table_id}/rows", dependencies=[Depends(auth)])
    def create_row(table_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.create_row(table_id, body_of(body)))

    @router.get("/v1/cc/tables/{table_id}/rows/{row_id}", dependencies=[Depends(auth)])
    def get_row(table_id: str, row_id: str):
        return call(lambda: cc().tables.get_row(table_id, row_id))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}", dependencies=[Depends(auth)])
    def update_row(table_id: str, row_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.update_row(table_id, row_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/page", dependencies=[Depends(auth)])
    def save_row_page(table_id: str, row_id: str, body: dict[str, Any]):
        return call(lambda: cc().tables.save_row_page(table_id, row_id, body_of(body)))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/undo", dependencies=[Depends(auth)])
    def undo_row(table_id: str, row_id: str):
        return call(lambda: cc().tables.undo(table_id, row_id))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/archive", dependencies=[Depends(auth)])
    def archive_row(table_id: str, row_id: str):
        return call(lambda: cc().tables.archive_row(table_id, row_id))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/restore", dependencies=[Depends(auth)])
    def restore_row(table_id: str, row_id: str):
        return call(lambda: cc().tables.restore_row(table_id, row_id))

    @router.post("/v1/cc/tables/{table_id}/rows/{row_id}/delete", dependencies=[Depends(auth)])
    def delete_row(table_id: str, row_id: str):
        return call(lambda: cc().tables.delete_row(table_id, row_id))

    # Plan 33 (C5): pages. Typed blocks only, no secrets, versioned saves, trash first.
    @router.get("/v1/cc/pages", dependencies=[Depends(auth)])
    def pages():
        return call(lambda: {"pages": cc().pages.tree(), "templates": sorted(PAGE_TEMPLATES)})

    @router.post("/v1/cc/pages", dependencies=[Depends(auth)])
    def page_create(body: dict[str, Any]):
        return call(lambda: cc().pages.create(body_of(body)))

    @router.get("/v1/cc/pages-trash", dependencies=[Depends(auth)])
    def page_trash():
        return call(lambda: {"pages": cc().pages.trash()})

    @router.get("/v1/cc/pages-search", dependencies=[Depends(auth)])
    def page_search(q: str = Query(default="", max_length=100)):
        return call(lambda: {"pages": cc().pages.search(q)})

    @router.get("/v1/cc/backlinks", dependencies=[Depends(auth)])
    def backlinks(kind: str = Query(default="", max_length=20), id: str = Query(default="", max_length=160)):  # noqa: A002
        return call(lambda: {"pages": cc().pages.backlinks(kind, id)})

    @router.get("/v1/cc/pages/{page_id}", dependencies=[Depends(auth)])
    def page_get(page_id: str):
        return call(lambda: cc().pages.get(page_id))

    @router.post("/v1/cc/pages/{page_id}", dependencies=[Depends(auth)])
    def page_update(page_id: str, body: dict[str, Any]):
        return call(lambda: cc().pages.update(page_id, body_of(body)))

    @router.post("/v1/cc/pages/{page_id}/move", dependencies=[Depends(auth)])
    def page_move(page_id: str, body: dict[str, Any]):
        return call(lambda: cc().pages.move(page_id, body_of(body)))

    @router.post("/v1/cc/pages/{page_id}/archive", dependencies=[Depends(auth)])
    def page_archive(page_id: str):
        return call(lambda: cc().pages.archive(page_id))

    @router.post("/v1/cc/pages/{page_id}/restore", dependencies=[Depends(auth)])
    def page_restore(page_id: str):
        return call(lambda: cc().pages.restore(page_id))

    @router.post("/v1/cc/pages/{page_id}/delete", dependencies=[Depends(auth)])
    def page_delete(page_id: str):
        return call(lambda: cc().pages.delete(page_id))

    @router.get("/v1/cc/pages/{page_id}/version", dependencies=[Depends(auth)])
    def page_version(page_id: str):
        return call(lambda: cc().pages.version(page_id))

    # Plan 33 §7 (C4, moved forward): the AI project manager. The OpenRouter key goes in once and never comes back out;
    # the model's changes are proposals the owner applies (tasks and routines always).
    @router.get("/v1/cc/ai", dependencies=[Depends(auth)])
    def ai_status():
        return call(lambda: cc().ai.status())

    @router.post("/v1/cc/ai/settings", dependencies=[Depends(auth)])
    def ai_settings(body: dict[str, Any]):
        return call(lambda: cc().ai.update_settings(body_of(body)))

    @router.post("/v1/cc/ai/key", dependencies=[Depends(auth)])
    def ai_key(body: dict[str, Any]):
        return call(lambda: cc().ai.set_key(body_of(body)))

    @router.post("/v1/cc/ai/key/test", dependencies=[Depends(auth)])
    def ai_key_test():
        return call(lambda: cc().ai.test_key())

    @router.post("/v1/cc/ai/key/forget", dependencies=[Depends(auth)])
    def ai_key_forget():
        return call(lambda: cc().ai.forget_key())

    @router.get("/v1/cc/ai/models", dependencies=[Depends(auth)])
    def ai_models(all: bool = Query(default=False), refresh: bool = Query(default=False)):
        return call(lambda: cc().ai.models(everything=all, refresh=refresh))

    @router.get("/v1/cc/ai/conversations", dependencies=[Depends(auth)])
    def ai_conversations():
        return call(lambda: {"conversations": cc().ai.conversations(), "proposals": cc().ai.open_proposals()})

    @router.post("/v1/cc/ai/conversations", dependencies=[Depends(auth)])
    def ai_new_conversation(body: dict[str, Any]):
        return call(lambda: cc().ai.create_conversation(body_of(body)))

    @router.get("/v1/cc/ai/conversations/{conversation_id}", dependencies=[Depends(auth)])
    def ai_conversation(conversation_id: str):
        return call(lambda: cc().ai.get(conversation_id))

    @router.post("/v1/cc/ai/conversations/{conversation_id}", dependencies=[Depends(auth)])
    def ai_update_conversation(conversation_id: str, body: dict[str, Any]):
        return call(lambda: cc().ai.update_conversation(conversation_id, body_of(body)))

    @router.post("/v1/cc/ai/conversations/{conversation_id}/messages", dependencies=[Depends(auth)])
    def ai_send(conversation_id: str, body: dict[str, Any]):
        return call(lambda: cc().ai.send(conversation_id, body_of(body)))

    @router.post("/v1/cc/ai/conversations/{conversation_id}/stop", dependencies=[Depends(auth)])
    def ai_stop(conversation_id: str):
        return call(lambda: cc().ai.stop(conversation_id))

    @router.post("/v1/cc/ai/conversations/{conversation_id}/delete", dependencies=[Depends(auth)])
    def ai_delete(conversation_id: str):
        return call(lambda: cc().ai.delete_conversation(conversation_id))

    @router.post("/v1/cc/ai/proposals/{proposal_id}/apply", dependencies=[Depends(auth)])
    def ai_apply(proposal_id: str):
        return call(lambda: cc().ai.apply(proposal_id))

    @router.post("/v1/cc/ai/proposals/{proposal_id}/discard", dependencies=[Depends(auth)])
    def ai_discard(proposal_id: str):
        return call(lambda: cc().ai.discard(proposal_id))

    @router.get("/v1/cc/audit", dependencies=[Depends(auth)])
    def audit(limit: int = Query(default=200, ge=1, le=1000)):
        return call(lambda: cc().audit(limit))

    return router


def create_oauth_callback_router(runtime: Any) -> APIRouter:
    """The one Command Center route without the bearer: the sign-in page sends the owner's browser back here with a
    code. It is only accepted with the single-use state (32 random bytes, 10 minutes) that Glass started, and the
    code is useless without the PKCE verifier the gateway kept. Nothing is reflected back but the connection's name."""
    router = APIRouter()

    @router.get("/v1/cc/connections/oauth/callback", response_class=HTMLResponse)
    def oauth_callback(state: str = Query(default="", max_length=200), code: str = Query(default="", max_length=2000),
                       error: str = Query(default="", max_length=200)):
        center = getattr(runtime, "command", None)
        try:
            if center is None:
                raise CommandError("The Command Center is not running.")
            name = center.connections.finish_sign_in(state, code, error or None)
            title, text = "Signed in", f"Cyclone is signed in to {name}. You can close this tab and go back to Glass."
        except CommandError as exc:
            title, text = "Not signed in", str(exc)
        page = (f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>"
                f"<body style=\"font:16px system-ui;margin:3rem;max-width:36rem\"><h1>{html.escape(title)}</h1><p>{html.escape(text)}</p>")
        return HTMLResponse(page, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})

    return router
