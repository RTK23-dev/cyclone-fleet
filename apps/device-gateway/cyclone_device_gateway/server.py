from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
import json
import shutil
import time
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
import uvicorn

from .actions.router import ActionRouter, ActionValidationError
from .adb.client import ADBClient
from .adb.device import collect_device_status
from .adb.screenshot import ScreenshotStore
from .api.schemas import (
    ActionRequest,
    DebugBundleRequest,
    ObserveRequest,
    TeachStartRequest,
    TeachStopRequest,
)
from .auth import AuditLog, verify_bearer
from .capabilities.models import (
    CapabilityActionRequest,
    CapabilityObserveRequest,
    GatewayErrorCode,
)
from .capabilities.registry import CapabilityRegistry, OBSERVATION_CAPABILITIES
from .capabilities.service import CapabilityService, ERROR_HTTP_STATUS
from .config import Settings
from .cyclone_bridge.client import CycloneBridgeClient
from .retrieval.service import RetrievalService
from .root.provider import RootProvider
from .state.store import StateStore
from .uiautomator.client import UiAutomatorProvider


class Gateway:
    def __init__(self, settings: Settings, *, adb=None, bridge=None, uia=None, root=None):
        self.settings = settings
        self.runtime = settings.runtime_dir
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.adb = adb or ADBClient(settings.adb_path, settings.device_serial)
        self.bridge = bridge or CycloneBridgeClient(
            settings.bridge_host,
            settings.bridge_port,
            settings.bridge_token,
        )
        self.uia = uia or UiAutomatorProvider(self.adb)
        self.root = root or RootProvider(self.adb, self.runtime / "input-traces")
        self.store = StateStore(self.runtime / "gateway.sqlite3")
        self.screens = ScreenshotStore(self.runtime / "screenshots")
        self.audit = AuditLog(self.runtime / "audit.jsonl")
        self.retrieval = RetrievalService(self.store)
        self.actions = ActionRouter(
            self.bridge,
            self.store,
            self.audit,
            self._observe_for_action,
            self.wait_for_stable,
        )
        self.capability_registry = CapabilityRegistry()
        self.capabilities = CapabilityService(
            self.actions,
            self.store,
            self.capability_registry,
        )
        self._device_status: dict[str, Any] | None = None

    def discover_capabilities(self):
        try:
            self.adb.forward_bridge(self.settings.bridge_port)
        except Exception:
            pass
        return self.capability_registry.discover(self.bridge)

    def device_status(self) -> dict:
        status = collect_device_status(self.adb, self.settings.device_serial)
        try:
            self.adb.forward_bridge(self.settings.bridge_port)
            bridge_status = self.bridge.request("bridge.status", {})
            reachable = True
        except Exception as exc:
            bridge_status = {"error": str(exc)}
            reachable = False
        status.update(
            {
                "cyclone_bridge_reachable": reachable,
                "cyclone_bridge": bridge_status,
            }
        )
        status["device_session_id"] = self.store.add_device_session(status)
        self._device_status = status
        return status

    def observe(
        self,
        screenshot: bool = False,
        uiautomator: bool = True,
        diagnostics: bool = False,
        execution_scope: dict[str, Any] | None = None,
    ) -> dict:
        semantic = self.bridge.request("observe.semantic", dict(execution_scope or {}))

        page_debug: dict[str, Any] | None = None
        debug_snapshot: dict[str, Any] | None = None
        if diagnostics:
            try:
                page_debug = self.bridge.request("observe.page_debug", {})
            except Exception as exc:
                page_debug = {"error": str(exc)}
            try:
                debug_snapshot = self.bridge.request("debug.snapshot", {})
            except Exception as exc:
                debug_snapshot = {"error": str(exc)}

        uia = None
        if uiautomator:
            try:
                uia = self.uia.observe()
            except Exception as exc:
                uia = {
                    "source": "UIAUTOMATOR",
                    "error": str(exc),
                    "nodes": [],
                }

        shot = None
        if screenshot:
            try:
                shot = {"source": "ADB", **asdict(self.screens.capture(self.adb))}
            except Exception as exc:
                shot = {"source": "ADB", "error": str(exc)}

        raw: dict[str, Any] = {"semantic": semantic}
        if page_debug is not None:
            raw["page_debug"] = page_debug
        if debug_snapshot is not None:
            raw["debug_snapshot"] = debug_snapshot

        observation_id = self.store.add_observation(
            semantic,
            uia=uia,
            screenshot=shot,
            raw=raw,
        )
        observation = self.store.get_observation(observation_id) or {}
        observation["device_serial"] = getattr(self.adb, "serial", None)
        return observation

    def _observe_for_action(self) -> dict:
        return self.observe(screenshot=False, uiautomator=False, diagnostics=False)

    def wait_for_stable(
        self,
        timeout_seconds: float = 2.5,
        poll_seconds: float = 0.15,
    ) -> dict | None:
        deadline = time.monotonic() + timeout_seconds
        previous_signature: tuple[Any, Any, Any] | None = None
        stable_samples = 0
        last: dict | None = None

        while time.monotonic() < deadline:
            current = self.bridge.request("observe.semantic", {})
            last = current
            signature = (
                current.get("pageKey") or current.get("page_key"),
                current.get("accessibilityFingerprint") or current.get("fingerprint"),
                current.get("package"),
            )
            if signature == previous_signature:
                stable_samples += 1
                if stable_samples >= 1:
                    return current
            else:
                previous_signature = signature
                stable_samples = 0
            time.sleep(poll_seconds)
        return last

    def knowledge_context(self, goal: str | None = None) -> dict[str, Any]:
        """Retrieve bounded canonical App Graph/Brain hints for the current observation.

        The Android app remains the sole knowledge owner. The PC gateway only asks the
        frozen app_graph.get and brain.recall operations and attaches their already-
        sanitized results to model-facing page context.
        """
        current = self.store.current_observation()
        semantic = current.get("semantic", {}) if current else {}
        if not isinstance(semantic, dict):
            semantic = {}
        package_name = semantic.get("package") or (current or {}).get("package")
        page_key = semantic.get("pageKey") or semantic.get("page_key") or (current or {}).get("page_key")
        if not package_name:
            return {"knownRouteHints": [], "brainRecall": None, "knowledgeProvenance": "ANDROID_CANONICAL"}

        query_goal = (goal or "").strip() or "Navigate the current phone state"
        args = {
            "package": package_name,
            "pageKey": page_key,
            "goal": query_goal,
        }

        app_graph: dict[str, Any] | None = None
        brain: dict[str, Any] | None = None
        app_graph_error: str | None = None
        brain_error: str | None = None
        try:
            value = self.bridge.request("app_graph.get", args)
            app_graph = value if isinstance(value, dict) else {"value": value}
        except Exception as exc:
            app_graph_error = str(exc)
        try:
            value = self.bridge.request("brain.recall", args)
            brain = value if isinstance(value, dict) else {"value": value}
        except Exception as exc:
            brain_error = str(exc)

        route_hints: list[Any] = []
        if app_graph:
            retrieval = app_graph.get("retrieval")
            if retrieval not in (None, {}, []):
                if isinstance(retrieval, list):
                    route_hints = retrieval[:5]
                else:
                    route_hints = [retrieval]

        brain_recall: Any = None
        if brain:
            brain_recall = brain.get("recall") if "recall" in brain else brain

        result: dict[str, Any] = {
            "knownRouteHints": route_hints,
            "brainRecall": brain_recall,
            "appGraph": app_graph,
            "knowledgeProvenance": "ANDROID_CANONICAL",
        }
        errors = {}
        if app_graph_error:
            errors["appGraph"] = app_graph_error
        if brain_error:
            errors["brain"] = brain_error
        if errors:
            result["knowledgeErrors"] = errors
        return result

    def debug_bundle(self, expected: str = "", goal: str = "") -> dict:
        stamp = (
            time.strftime("%Y%m%d-%H%M%S", time.localtime())
            + f"-{time.time_ns() % 1_000_000_000:09d}"
        )
        folder = self.runtime / "debug-bundles" / stamp
        folder.mkdir(parents=True, exist_ok=False)

        observation = self.observe(
            screenshot=True,
            uiautomator=True,
            diagnostics=False,
        )
        semantic = observation["semantic"]

        page_debug_args = {}
        if expected:
            page_debug_args["expected"] = expected
        try:
            page_debug = self.bridge.request("observe.page_debug", page_debug_args)
        except Exception as exc:
            page_debug = {"error": str(exc)}

        try:
            debug_snapshot = self.bridge.request("debug.snapshot", {})
        except Exception as exc:
            debug_snapshot = {"error": str(exc)}

        package_activity = {
            "package": observation.get("package"),
            "activity": observation.get("activity"),
            "page_key": observation.get("page_key"),
        }
        try:
            root_data = {
                "dumpsys_window": self.root.dumpsys_window(),
                "dumpsys_input": self.root.dumpsys_input(),
                "filtered_logcat": self.root.filtered_logcat(),
            }
        except Exception as exc:
            root_data = {"root_telemetry_error": str(exc)}

        payloads = {
            "cyclone-semantic.json": semantic,
            "cyclone-page-debug.json": page_debug,
            "cyclone-debug-snapshot.json": debug_snapshot,
            "uiautomator.json": observation.get("uiautomator"),
            "package-activity.json": package_activity,
            "root-telemetry.json": root_data,
            "recent-actions.json": self.store.recent_actions(50),
            "page-transitions.json": self.store.transition_history(50),
        }
        for name, payload in payloads.items():
            (folder / name).write_text(
                json.dumps(payload, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )

        screenshot = observation.get("screenshot") or {}
        source_path = screenshot.get("path")
        if source_path and Path(source_path).exists():
            shutil.copy2(source_path, folder / "screen.png")

        manifest = {
            "created_at": time.time(),
            "observation_id": observation.get("id"),
            "goal": goal,
            "expected": expected,
            "files": sorted(path.name for path in folder.iterdir()),
        }
        (folder / "manifest.json").write_text(
            json.dumps(manifest, indent=2),
            encoding="utf-8",
        )
        return {"bundle_id": stamp, "path": str(folder), **manifest}


def _parse_observe_request(raw: bytes) -> ObserveRequest:
    """Legacy `/v1/observe` accepts a missing or empty body as ObserveRequest defaults."""
    if not raw or not raw.strip():
        return ObserveRequest()
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail="Invalid JSON body") from exc
    if payload is None:
        return ObserveRequest()
    try:
        return ObserveRequest.model_validate(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=json.loads(exc.json())) from exc


async def _observe_request_body(http_request: Request) -> ObserveRequest:
    return _parse_observe_request(await http_request.body())


def create_app(settings: Settings | None = None, gateway: Gateway | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    gateway = gateway or Gateway(settings)
    # Alpha 91: the API reports the product release it belongs to (it said 2.9.5 for many releases).
    from .terminal.release import installed_version
    app = FastAPI(title="Cyclone Device Gateway", version=installed_version())

    def auth(authorization: str | None = Header(default=None)):
        verify_bearer(authorization, settings.token)

    @app.get("/v1/device/status", dependencies=[Depends(auth)])
    def device_status():
        return gateway.device_status()

    @app.post("/v1/observe", dependencies=[Depends(auth)])
    def observe(request: ObserveRequest = Depends(_observe_request_body)):
        request = request or ObserveRequest()
        gateway.observe(
            screenshot=request.wants_screenshot,
            uiautomator=request.uiautomator,
            diagnostics=request.mode == "full",
        )
        result = gateway.retrieval.get_page_context(request.mode, request.goal)
        if result is None:
            raise HTTPException(404, "No observation captured")
        result.update(gateway.knowledge_context(request.goal))
        return result

    @app.get("/v1/ui/search", dependencies=[Depends(auth)])
    def ui_search(q: str = Query(min_length=1), limit: int = Query(20, ge=1, le=100)):
        return {"results": gateway.retrieval.search_ui(q, limit)}

    @app.get("/v1/ui/element/{element_id}", dependencies=[Depends(auth)])
    def ui_element(element_id: str):
        result = gateway.retrieval.get_element(element_id)
        if result is not None:
            return result
        try:
            return gateway.bridge.request("ui.element", {"elementId": element_id})
        except Exception:
            raise HTTPException(404, "Element not found")

    @app.get("/v1/page/current", dependencies=[Depends(auth)])
    def page_current(mode: str = "compact", goal: str | None = None):
        try:
            result = gateway.retrieval.get_page_context(mode, goal)
        except ValueError as exc:
            raise HTTPException(400, str(exc))
        if result is None:
            raise HTTPException(404, "No observation captured")
        result.update(gateway.knowledge_context(goal))
        return result

    @app.get("/v1/page/history", dependencies=[Depends(auth)])
    def page_history(limit: int = Query(50, ge=1, le=500)):
        return {
            "pages": gateway.store.page_history(limit),
            "transitions": gateway.store.transition_history(limit),
        }

    @app.post("/v1/action", dependencies=[Depends(auth)])
    def action(request: ActionRequest):
        try:
            result = gateway.actions.execute(**request.model_dump())
        except ActionValidationError as exc:
            raise HTTPException(400, str(exc))
        except Exception:
            return JSONResponse(
                status_code=ERROR_HTTP_STATUS[GatewayErrorCode.DEVICE_DISCONNECTED],
                content={
                    "request_id": request.request_id,
                    "success": False,
                    "transport_ok": False,
                    "execution_ok": False,
                    "verification_ok": False,
                    "error_class": GatewayErrorCode.DEVICE_DISCONNECTED,
                },
            )
        status = _legacy_action_http_status(request.tool, result)
        if status != 200:
            return JSONResponse(status_code=status, content=result)
        return result

    @app.get("/v1/capabilities", dependencies=[Depends(auth)])
    def capability_discovery():
        if hasattr(gateway, "discover_capabilities"):
            discovery = gateway.discover_capabilities()
        else:
            discovery = gateway.capability_registry.discover(gateway.bridge)
        return discovery.model_dump(mode="json")

    @app.post("/v1/capabilities/action", dependencies=[Depends(auth)])
    def capability_action(request: CapabilityActionRequest):
        result = gateway.capabilities.execute(request)
        status = 200 if result.ok else ERROR_HTTP_STATUS[result.error.code]
        return JSONResponse(status_code=status, content=result.model_dump(mode="json"))

    @app.post("/v1/capabilities/observe", dependencies=[Depends(auth)])
    def capability_observe(request: CapabilityObserveRequest):
        result = gateway.capabilities.observe(
            request,
            gateway.observe,
            gateway.retrieval,
            gateway.knowledge_context,
        )
        status = 200 if result.ok else ERROR_HTTP_STATUS[result.error.code]
        return JSONResponse(status_code=status, content=result.model_dump(mode="json"))

    @app.post("/v1/debug/bundle", dependencies=[Depends(auth)])
    def debug_bundle(request: DebugBundleRequest):
        return gateway.debug_bundle(request.expected, request.goal)

    @app.post("/v1/teach/start", dependencies=[Depends(auth)])
    def teach_start(request: TeachStartRequest):
        return gateway.bridge.request(
            "teach.start",
            request.model_dump(exclude_none=True),
        )

    @app.get("/v1/teach/status", dependencies=[Depends(auth)])
    def teach_status():
        return gateway.bridge.request("teach.status", {})

    @app.post("/v1/teach/stop", dependencies=[Depends(auth)])
    def teach_stop(request: TeachStopRequest):
        return gateway.bridge.request("teach.stop", request.model_dump())

    @app.get("/v1/debug/compare-sources", dependencies=[Depends(auth)])
    def compare_sources(q: str = Query(min_length=1)):
        return gateway.retrieval.compare_sources(q)

    return app


def _legacy_action_http_status(tool: str, result: dict[str, Any]) -> int:
    if result.get("transport_ok") is not True:
        return ERROR_HTTP_STATUS[GatewayErrorCode.DEVICE_DISCONNECTED]
    if result.get("execution_ok") is not True:
        error_class = str(result.get("error_class") or "").upper()
        if error_class == GatewayErrorCode.POLICY_DENIED:
            return ERROR_HTTP_STATUS[GatewayErrorCode.POLICY_DENIED]
        if error_class == GatewayErrorCode.PROTOCOL_MISMATCH:
            return ERROR_HTTP_STATUS[GatewayErrorCode.PROTOCOL_MISMATCH]
        return ERROR_HTTP_STATUS[GatewayErrorCode.EXECUTION_FAILED]
    if tool not in OBSERVATION_CAPABILITIES and result.get("verification_ok") is not True:
        return ERROR_HTTP_STATUS[GatewayErrorCode.VERIFICATION_FAILED]
    return 200


def main() -> None:
    settings = Settings.from_env()
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port)


if __name__ == "__main__":
    main()
