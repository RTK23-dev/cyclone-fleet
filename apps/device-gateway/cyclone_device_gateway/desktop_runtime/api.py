from __future__ import annotations

import asyncio
from contextlib import suppress
import json
import hashlib
import hmac
import queue
import secrets
import time
from typing import Any, Literal

from fastapi import APIRouter, Body, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..config import Settings
from ..server import create_app as create_legacy_app
from ..api.stream_api import create_stream_router
from ..backends.desktop_android import DesktopAndroidBackend
from ..virtual import AndroidEmulatorProvider, VirtualDeviceConfig, VirtualDeviceRegistry, VirtualDeviceService
from .agent import DesktopAgentService
from .batch import FleetBatchService
from .controls import ClipboardService, ManualControlService
from .diagnostics import FleetDiagnosticSupervisor
from .fleet import DeviceFleetManager
from .models import DESKTOP_PROTOCOL_VERSION, DesktopRuntimeError, RuntimeErrorCode, VIDEO_PROFILES
from .pairing import PairingCoordinator
from .readiness import enrich_device_public
from .layer2 import Layer2WorkspaceService
from ..glass import LaunchCodes, create_glass_router, resolve_glass_dist
from .sessions import ExecutionSessionService
from .lan_share import LanShareDirectory
from .v5_contract import V5ContractService
from .trust_v33 import PCTrustCoordinator
from .video import StreamMessage, VideoFleetLimiter, VideoStreamController
from .workspace import FleetWorkspaceStore
from ..cloud_control import create_cloud_control_router


class PairCompleteBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str
    pairing_id: str = Field(min_length=1, max_length=160)


class PairQrCompleteBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pairing_id: str = Field(min_length=1, max_length=160)


class ManualControlBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text", "wake", "yield_ai", "take_human"]
    x: float | None = None
    y: float | None = None
    x1: float | None = None
    y1: float | None = None
    x2: float | None = None
    y2: float | None = None
    duration_ms: int | None = None
    text: str | None = None
    sessionId: str | None = Field(default=None, min_length=1, max_length=160)
    session_id: str | None = Field(default=None, min_length=1, max_length=160)


class ClipboardBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str


class AgentObserveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["compact", "full"] = "compact"
    include_screenshot: bool = False
    session_id: str | None = None
    sessionId: str | None = None
    display_id: int | None = Field(default=None, ge=0)
    displayId: int | None = Field(default=None, ge=0)
    executionContext: dict[str, Any] | None = None


class AgentActionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    capability_id: str
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    expected_observation_id: str | None = None
    request_ai_control: bool = False
    session_id: str | None = None
    sessionId: str | None = None
    display_id: int | None = Field(default=None, ge=0)
    displayId: int | None = Field(default=None, ge=0)
    executionContext: dict[str, Any] | None = None


class AgentDebugBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected: str = ""
    goal: str = ""


class AgentTeachStartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    goal: str = ""


class AgentTeachStopBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    compile_for_review: bool = True


class StreamDiagnosticBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    stage: str = Field(min_length=1, max_length=120, pattern=r"^[A-Za-z0-9_.-]+$")
    code: str | None = Field(default=None, max_length=120, pattern=r"^[A-Za-z0-9_.-]+$")
    attempt: int | None = Field(default=None, ge=0, le=100)
    close_code: int | None = Field(default=None, ge=0, le=9999)
    retryable: bool | None = None


class FleetGroupBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    device_ids: list[str] = Field(default_factory=list, max_length=32)


class FleetSelectionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_ids: list[str] = Field(default_factory=list, max_length=32)


class FleetBatchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_ids: list[str] = Field(min_length=1, max_length=32)
    operation: Literal["home", "back", "open_app", "screenshot", "recover"]
    params: dict[str, Any] = Field(default_factory=dict)


class VirtualCreateBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provider: str = Field(min_length=1, max_length=80)
    image: str = Field(min_length=1, max_length=240)
    width: int = Field(default=1080, ge=320, le=3840)
    height: int = Field(default=1920, ge=480, le=3840)
    dpi: int = Field(default=420, ge=120, le=640)
    fps: int = Field(default=30, ge=1, le=60)
    locale: str = Field(default="en-US", min_length=2, max_length=32)
    timezone: str = Field(default="UTC", min_length=1, max_length=64)
    network_mode: Literal["loopback"] = "loopback"
    storage_mb: int = Field(default=8192, ge=2048, le=131072)


class VirtualConfigureBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    image: str = Field(min_length=1, max_length=240)
    width: int = Field(default=1080, ge=320, le=3840)
    height: int = Field(default=1920, ge=480, le=3840)
    dpi: int = Field(default=420, ge=120, le=640)
    fps: int = Field(default=30, ge=1, le=60)
    locale: str = Field(default="en-US", min_length=2, max_length=32)
    timezone: str = Field(default="UTC", min_length=1, max_length=64)
    network_mode: Literal["loopback"] = "loopback"
    storage_mb: int = Field(default=8192, ge=2048, le=131072)


class DesktopRuntime:
    def __init__(self, settings: Settings, *, fleet: DeviceFleetManager | None = None):
        self.settings = settings
        self.instance_id = secrets.token_hex(8)
        # USB topology changes are normally event-driven through adb track-devices. A 20 second
        # fallback is intentionally retained for recovery if that stream dies on a particular PC.
        self.fleet = fleet or DeviceFleetManager(adb_path=settings.adb_path, poll_seconds=20.0)
        self.virtual_registry = VirtualDeviceRegistry(settings.runtime_dir / "virtual" / "instances.json")
        self.virtual = VirtualDeviceService(
            self.virtual_registry,
            [AndroidEmulatorProvider(settings.runtime_dir)],
        )
        # Plan 44 run 1 (alpha 90): cloud phones (VMOS Cloud, DuoPlus, remote ADB) kept connected by this PC.
        from ..cloud_fleet import CloudFleetService
        self.cloud = CloudFleetService(settings.runtime_dir / "cloud", self.fleet)
        self.fleet.set_source_resolver(
            lambda serial: self.virtual_registry.metadata_for_serial(serial) or self.cloud.metadata_for_serial(serial))
        self.workspace = FleetWorkspaceStore(settings.runtime_dir / "fleet-workspace.json")
        self.live_diagnostics = FleetDiagnosticSupervisor(self.fleet)
        # Alpha 87: keep Cyclone on each phone current, and know why it stopped (health reports, freezes).
        from ..phone_care.service import PhoneCareService
        self.care = PhoneCareService(self.fleet, settings.runtime_dir / "phone-care", diagnostics=self.live_diagnostics)
        self.cloud.care = self.care
        # Alpha 88: wakes a stopped Cyclone app on its own, and runs the owner's one-click connection fixes.
        from .connection_medic import ConnectionMedic
        from .accessibility_keeper import AccessibilityKeeper
        self.medic = ConnectionMedic(self.fleet, self.live_diagnostics,
                                     keeper=AccessibilityKeeper(settings.runtime_dir / "accessibility-keeper.json", self.live_diagnostics))
        self.pairing = PairingCoordinator(self.fleet, self.live_diagnostics)
        self.trust = PCTrustCoordinator(self.fleet)
        self.controls = ManualControlService(self.fleet)
        self.clipboard = ClipboardService(self.fleet)
        self.layer2 = Layer2WorkspaceService(self.fleet)
        self.agent = DesktopAgentService(self.fleet, snapshot=self._snapshot_for_batch, layer2=self.layer2)
        self.sessions = ExecutionSessionService(self.fleet)
        self.batches = FleetBatchService(lambda device_id: DesktopAndroidBackend(
            self.fleet, self.agent, device_id, snapshot=self._snapshot_for_batch,
        ))
        self.video_limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
        # Wi-Fi screen share: the phone's own stream when it shares (AnyDesk-style), ADB screenshots otherwise.
        share_contract = V5ContractService(self.fleet)
        # Cyclone Lab: measured Mind missions, scored from the phone's real state through the lab's fixed probes.
        from ..lab.probes import PhoneProbe
        from ..lab.runner import LabService
        self.lab = LabService(settings.runtime_dir / "lab", share_contract, lambda device_id: PhoneProbe(self.fleet.get(device_id).adb))
        # Plan 33 (C0): the Command Center's accounts, tasks, routines, results and approvals, in one local SQLite file.
        from ..command.center import CommandCenter
        self.command = CommandCenter(settings.runtime_dir / "command" / "command.db", share_contract, self.fleet.list_public)
        from .scenes import SceneStore
        from .fleet_orchestrator import FleetOrchestrator
        self.scenes = SceneStore(settings.runtime_dir / "fleet-scenes.json")
        self.fleet_orchestrator = FleetOrchestrator(
            self.command, self.fleet.list_public, self.workspace.nickname_map, settings.runtime_dir / "fleet" / "missions.json",
            groups=lambda: list(self.workspace.public().get("groups") or []),
            events=getattr(self.fleet, "events", None),
        )
        self.command.set_task_listener(self.fleet_orchestrator.enqueue_task_change)
        # Plan 48: the Port Hub (Cyclone Ports). Absent only when the ports kit isn't installed.
        from ..ports.hub import PortHub
        try:
            self.ports: PortHub | None = PortHub(settings.runtime_dir / "ports",
                                                 base_url=f"http://127.0.0.1:{settings.port}")
        except RuntimeError:
            self.ports = None
        # Plan 48 run 4: the hub collects each ready phone's port messages and answers its runs' waits. Codes are sealed
        # only to a phone key the owner trusted in Glass (the Command Center's device keys).
        self.port_phones = None
        if self.ports is not None:
            from ..ports.phone import PhoneBridge

            def trusted_key(device_id: str) -> dict[str, str] | None:
                row = self.command.delivery.trusted_key(device_id)
                return None if row is None else {"publicKey": row["public_key"], "fingerprint": row["fingerprint"]}

            self.port_phones = PhoneBridge(self.ports, share_contract, self.fleet.list_public, trusted_key)
        # Alpha.102: every number Cyclone can receive codes on (the fleet's SIMs, forwarders, rented numbers).
        from ..numbers.service import NumbersService
        self.numbers = NumbersService(
            settings.runtime_dir / "numbers" / "numbers.db",
            devices=self.fleet.list_public,
            read_phone=share_contract.numbers_list,
            plugins=lambda: self.ports.overview()["plugins"] if self.ports is not None else [],
            accounts=self.command.list_accounts,
        )
        self.lan_share = LanShareDirectory(
            status=share_contract.share_status,
            trust_record=self.trust.store.record,
            sign=self.trust.identity.sign,
        )
        self.fleet.set_video_factory(lambda session: VideoStreamController(
            session,
            self.video_limiter,
            lan_share=self.lan_share.for_device(session.device_id),
            diagnostic=lambda stage, details, device_id=session.device_id: self.live_diagnostics.mark(
                device_id,
                stage,
                details=details,
            ),
        ))

    def _snapshot_for_batch(self, device_id: str, profile: str) -> dict[str, Any]:
        session = self.fleet.get(device_id)
        if session.video is None:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Screenshot capture is unavailable.")
        capture = session.video.snapshot(fresh=True) if profile == "live-phone" else session.video.snapshot()
        data = capture.get("data")
        if not isinstance(data, bytes):
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Screenshot capture returned no image.")
        codec = str(capture.get("codec") or "image/jpeg")
        suffix = ".png" if codec == "image/png" else ".jpg"
        root = self.settings.runtime_dir / "fleet-screenshots"
        root.mkdir(parents=True, exist_ok=True)
        path = root / (f"{device_id}-live-phone{suffix}" if profile == "live-phone" else f"{device_id}-{int(time.time() * 1000)}{suffix}")
        path.write_bytes(data)
        return {
            "deviceId": device_id, "filePath": str(path.resolve()), "codec": codec,
            "width": capture.get("width"), "height": capture.get("height"),
            "timestampMs": capture.get("timestamp_ms"),
        }

    def start(self) -> None:
        self.fleet.start()
        # The diagnostic supervisor is deliberately independent of pairing. As soon as ADB reports
        # an authorized phone, it records a bounded baseline and follows only the Cyclone app PID.
        self.live_diagnostics.start()
        self.trust.start()
        self.command.start()
        if self.ports is not None:
            self.ports.start()
        if self.port_phones is not None:
            self.port_phones.start()
        self.care.start()
        self.medic.start()
        self.cloud.start()

    def stop(self) -> None:
        closer = getattr(self, "fleet_orchestrator", None)
        if closer is not None:
            closer.close()
        self.cloud.stop()
        self.medic.stop()
        self.care.stop()
        self.command.stop()
        if self.port_phones is not None:
            self.port_phones.stop()
        if self.ports is not None:
            self.ports.stop()
        self.numbers.close()
        # Stop trust refresh before retiring ADB sessions so no reconnect races shutdown cleanup.
        self.trust.stop()
        self.live_diagnostics.stop()
        self.fleet.stop()


def create_desktop_router(runtime: DesktopRuntime, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/fleet", dependencies=[Depends(auth)])
    @router.get("/v1/devices", dependencies=[Depends(auth)], include_in_schema=False)
    def fleet(
        q: str = Query(default="", max_length=160),
        source: str | None = Query(default=None, pattern=r"^(USB|LAN|VIRTUAL)$"),
        group_id: str | None = Query(default=None, max_length=48),
    ) -> dict[str, Any]:
        group = runtime.workspace.group(group_id) if group_id else None
        devices = runtime.workspace.search(_public_devices(runtime), q, source=source, group=group)
        return {"protocol": DESKTOP_PROTOCOL_VERSION, "devices": devices, "workspace": runtime.workspace.public()}

    @router.post("/v1/fleet/scan", dependencies=[Depends(auth)])
    def fleet_scan() -> dict[str, Any]:
        _call(lambda: runtime.fleet.refresh_once(source="manual"))
        return {
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "devices": _public_devices(runtime),
            "discovery": runtime.fleet.diagnostics(),
            "liveDiagnostics": runtime.live_diagnostics.status(),
        }

    @router.get("/v1/fleet/workspace", dependencies=[Depends(auth)])
    def fleet_workspace() -> dict[str, Any]:
        return runtime.workspace.public()

    @router.post("/v1/fleet/groups/{group_id}", dependencies=[Depends(auth)])
    def fleet_group_put(group_id: str, body: FleetGroupBody) -> dict[str, Any]:
        return _service_call(lambda: runtime.workspace.put_group(group_id, body.name, body.device_ids))

    @router.post("/v1/fleet/groups/{group_id}/delete", dependencies=[Depends(auth)])
    def fleet_group_delete(group_id: str) -> dict[str, Any]:
        runtime.workspace.delete_group(group_id)
        return {"groupId": group_id, "deleted": True}

    @router.post("/v1/fleet/selection", dependencies=[Depends(auth)])
    def fleet_selection(body: FleetSelectionBody) -> dict[str, Any]:
        return _service_call(lambda: {"selectedDeviceIds": runtime.workspace.set_selection(body.device_ids)})

    @router.post("/v1/fleet/batches", dependencies=[Depends(auth)])
    def fleet_batch(body: FleetBatchBody) -> dict[str, Any]:
        return _service_call(lambda: runtime.batches.submit(body.device_ids, body.operation, body.params))

    @router.get("/v1/fleet/batches/{batch_id}", dependencies=[Depends(auth)])
    def fleet_batch_status(batch_id: str) -> dict[str, Any]:
        return _service_call(lambda: runtime.batches.get(batch_id))

    @router.post("/v1/fleet/batches/{batch_id}/cancel", dependencies=[Depends(auth)])
    def fleet_batch_cancel(batch_id: str) -> dict[str, Any]:
        return _service_call(lambda: runtime.batches.cancel(batch_id))

    @router.get("/v1/virtual/providers", dependencies=[Depends(auth)])
    def virtual_providers() -> dict[str, Any]:
        return {"providers": runtime.virtual.health()}

    @router.get("/v1/virtual/providers/{provider_id}", dependencies=[Depends(auth)])
    def virtual_provider_status(provider_id: str) -> dict[str, Any]:
        return _service_call(lambda: next(item for item in runtime.virtual.health() if item["provider"] == provider_id))

    @router.get("/v1/virtual/providers/{provider_id}/images", dependencies=[Depends(auth)])
    def virtual_images(provider_id: str) -> dict[str, Any]:
        return _service_call(lambda: {"provider": provider_id, "images": runtime.virtual.list_images(provider_id)})

    @router.get("/v1/virtual/instances", dependencies=[Depends(auth)])
    def virtual_instances() -> dict[str, Any]:
        return {"instances": runtime.virtual.list_instances()}

    @router.get("/v1/virtual/instances/{instance_id}", dependencies=[Depends(auth)])
    def virtual_instance_status(instance_id: str) -> dict[str, Any]:
        return _service_call(lambda: runtime.virtual.get(instance_id))

    @router.get("/v1/virtual/instances/{instance_id}/endpoint", dependencies=[Depends(auth)])
    def virtual_instance_endpoint(instance_id: str) -> dict[str, Any]:
        return _service_call(lambda: runtime.virtual.endpoint(instance_id))

    @router.post("/v1/virtual/instances", dependencies=[Depends(auth)])
    def virtual_create(body: VirtualCreateBody) -> dict[str, Any]:
        config = VirtualDeviceConfig(
            image=body.image, width=body.width, height=body.height, dpi=body.dpi, fps=body.fps,
            locale=body.locale, timezone=body.timezone, network_mode=body.network_mode, storage_mb=body.storage_mb,
        )
        return _service_call(lambda: runtime.virtual.create(body.provider, config))

    @router.post("/v1/virtual/instances/{instance_id}/configure", dependencies=[Depends(auth)])
    def virtual_configure(instance_id: str, body: VirtualConfigureBody) -> dict[str, Any]:
        config = VirtualDeviceConfig(
            image=body.image, width=body.width, height=body.height, dpi=body.dpi, fps=body.fps,
            locale=body.locale, timezone=body.timezone, network_mode=body.network_mode, storage_mb=body.storage_mb,
        )
        return _service_call(lambda: runtime.virtual.configure(instance_id, config))

    @router.post("/v1/virtual/instances/{instance_id}/{operation}", dependencies=[Depends(auth)])
    def virtual_lifecycle(instance_id: str, operation: Literal["start", "stop", "reset", "delete"]) -> dict[str, Any]:
        result = _service_call(lambda: runtime.virtual.lifecycle(instance_id, operation))
        if operation in {"start", "stop", "reset", "delete"}:
            try:
                runtime.fleet.refresh_once(source=f"virtual-{operation}")
            except DesktopRuntimeError:
                pass
        return result

    @router.get("/v1/runtime/self-test", dependencies=[Depends(auth)])
    def runtime_self_test() -> dict[str, Any]:
        return {
            "ok": True,
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
            "httpAuth": "CURRENT_TAURI_SESSION",
            "websocketAuth": "CURRENT_TAURI_SESSION",
        }

    @router.websocket("/v1/runtime/self-test/ws")
    async def runtime_self_test_ws(websocket: WebSocket):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
        await websocket.send_json({
            "ok": True,
            "protocol": DESKTOP_PROTOCOL_VERSION,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
        })
        await websocket.close(code=1000)

    @router.get("/v1/diagnostics/status", dependencies=[Depends(auth)])
    def diagnostics_status() -> dict[str, Any]:
        devices = _public_devices(runtime)
        discovery = runtime.fleet.diagnostics()
        live_diagnostics = runtime.live_diagnostics.status()
        paired = sum(1 for device in devices if device.get("paired") is True)
        action_required = sum(
            1
            for device in devices
            if any(
                card.get("state") in {"ACTION_REQUIRED", "OFFLINE"}
                for card in (device.get("readiness") or {}).values()
            )
        )
        recovering = sum(
            1
            for device in devices
            if any(
                card.get("state") == "RECOVERING"
                for card in (device.get("readiness") or {}).values()
            )
        )
        adb_available = discovery.get("adbAvailable") is True
        if not adb_available and discovery.get("lastScanError"):
            message = "ADB is not available to Cyclone"
        elif discovery.get("rawAdbDeviceCount", 0) > 0 and not devices:
            message = "ADB sees a phone, but Cyclone has not added it to the fleet"
        elif action_required:
            message = f"{action_required} phone(s) need one action"
        elif recovering:
            message = f"{recovering} phone(s) are recovering a connection plane"
        elif devices:
            message = "Ready"
        else:
            message = "Waiting for a USB phone"
        return {
            "backendReachable": True,
            "runtimeInstanceId": runtime.instance_id,
            "runtimePort": runtime.settings.port,
            "sessionBinding": _session_binding(runtime, token),
            "deviceCount": len(devices),
            "pairedDeviceCount": paired,
            "recoveryActive": action_required > 0 or recovering > 0 or not adb_available,
            "message": message,
            "discovery": discovery,
            "liveDiagnostics": live_diagnostics,
        }

    @router.get("/v1/diagnostics/discovery", dependencies=[Depends(auth)])
    def diagnostics_discovery() -> dict[str, Any]:
        return {
            # ADB inventory is intentionally independent from the local HTTP/media/AI planes.
            # An authorized phone must remain visible here even when another plane is unavailable.
            "devices": _public_devices(runtime),
            "discovery": runtime.fleet.diagnostics(),
            "liveDiagnostics": runtime.live_diagnostics.status(),
        }

    @router.get("/v1/devices/{device_id}/health", dependencies=[Depends(auth)])
    def device_health(device_id: str) -> dict[str, Any]:
        session = runtime.fleet.get(device_id)
        device = enrich_device_public(session, _safe_trust_status(runtime, device_id))
        return {
            "deviceId": device_id,
            "device_id": device_id,
            "health": device.get("health"),
            "planes": device.get("planes"),
            "readiness": device.get("readiness"),
        }

    @router.post("/v1/devices/{device_id}/diagnostics/stream-event", dependencies=[Depends(auth)])
    def diagnostics_stream_event(device_id: str, body: StreamDiagnosticBody) -> dict[str, Any]:
        runtime.fleet.get(device_id)
        details = {
            "code": body.code,
            "attempt": body.attempt,
            "closeCode": body.close_code,
            "retryable": body.retryable,
            "source": "pc-ui",
        }
        runtime.live_diagnostics.mark(device_id, body.stage, details={key: value for key, value in details.items() if value is not None})
        return {"ok": True, "recorded": True}

    @router.post("/v1/devices/{device_id}/diagnostics/bundle", dependencies=[Depends(auth)])
    def diagnostics_bundle(device_id: str) -> dict[str, Any]:
        session = runtime.fleet.get(device_id)
        video = session.video.diagnostics() if session.video is not None and hasattr(session.video, "diagnostics") else {}
        trust = _safe_trust_status(runtime, device_id)
        bridge_probe: dict[str, Any]
        try:
            health = session.bridge().request("bridge.status", {}, request_id=f"desktop-debug-{secrets.token_urlsafe(12)}")
            bridge_probe = {
                "ok": True,
                "gatewayEnabled": health.get("gatewayEnabled") is True,
                "socketListening": health.get("socketListening") is True,
                "accessibilityConnected": health.get("accessibilityConnected") is True,
            }
        except Exception as exc:
            bridge_probe = {"ok": False, "errorClass": exc.__class__.__name__}
        try:
            capture = session.adb.exec_out("screencap", "-p", timeout=6)
            capture_probe = {
                "ok": len(capture) > 8 and capture.startswith(b"\x89PNG\r\n\x1a\n"),
                "bytesReceived": len(capture),
            }
        except Exception as exc:
            capture_probe = {"ok": False, "errorClass": exc.__class__.__name__}
        created_at = int(time.time() * 1000)
        path = runtime.live_diagnostics.create_connection_bundle(device_id, {
            "schemaVersion": 2,
            "runtimeInstanceId": runtime.instance_id,
            "desktopProtocol": DESKTOP_PROTOCOL_VERSION,
            "sessionBinding": _session_binding(runtime, token),
            "device": enrich_device_public(session, trust),
            "aiTrust": trust,
            "discovery": runtime.fleet.diagnostics(),
            "video": video,
            "authenticatedBridgeProbe": bridge_probe,
            "rawCaptureProbe": capture_probe,
            "liveDiagnostics": runtime.live_diagnostics.status(),
        })
        if path is None:
            raise HTTPException(status_code=503, detail={"code": "DIAGNOSTICS_UNAVAILABLE", "message": "Connection diagnostics are unavailable."})
        return {"ok": True, "deviceId": device_id, "path": path, "createdAtEpochMs": created_at}

    @router.get("/v1/devices/{device_id}/trust", dependencies=[Depends(auth)])
    def trust_status(device_id: str):
        return _call(lambda: runtime.trust.status(device_id))

    @router.post("/v1/devices/{device_id}/connection/fix", dependencies=[Depends(auth)])
    def connection_fix(device_id: str, body: dict[str, Any] = Body(...)):
        # One of a few fixed repairs (start Cyclone, open Accessibility settings, open Cyclone); never a free command.
        if not isinstance(body, dict) or set(body) != {"action"} or not isinstance(body.get("action"), str):
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": "Send {action}."})
        result = _call(lambda: runtime.medic.fix(device_id, body["action"]))
        result["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
        return result

    @router.post("/v1/devices/{device_id}/trust/begin", dependencies=[Depends(auth)])
    def trust_begin(device_id: str):
        runtime.live_diagnostics.mark(device_id, "trust.challenge.requested", details={"protocol": "3.3"})
        result = _call(lambda: runtime.trust.begin(device_id))
        stage = "trust.session.authenticated" if result.get("sessionReady") else "trust.phone_confirmation_required"
        runtime.live_diagnostics.mark(device_id, stage, details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/complete", dependencies=[Depends(auth)])
    def trust_complete(device_id: str):
        result = _call(lambda: runtime.trust.complete(device_id))
        stage = "trust.session.authenticated" if result.get("sessionReady") else "trust.phone_confirmation_required"
        runtime.live_diagnostics.mark(device_id, stage, details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/rotate", dependencies=[Depends(auth)])
    def trust_rotate(device_id: str):
        result = _call(lambda: runtime.trust.rotate(device_id))
        runtime.live_diagnostics.mark(device_id, "trust.rotated", details={"protocol": "3.3"})
        return result

    @router.post("/v1/devices/{device_id}/trust/revoke", dependencies=[Depends(auth)])
    def trust_revoke(device_id: str):
        result = _call(lambda: runtime.trust.revoke(device_id))
        runtime.live_diagnostics.mark(device_id, "trust.revoked", details={"protocol": "3.3"})
        return result

    # Legacy code/QR pairing stays as an explicitly secondary transition fallback. The V3.3
    # normal USB path above never asks the user to copy a local gateway secret or four-letter code.
    @router.post("/v1/devices/{device_id}/pair/begin", dependencies=[Depends(auth)])
    def pair_begin(device_id: str):
        return _call(lambda: runtime.pairing.begin(device_id))

    @router.post("/v1/devices/{device_id}/pair/complete", dependencies=[Depends(auth)])
    @router.post("/v1/devices/{device_id}/pair/confirm", dependencies=[Depends(auth)], include_in_schema=False)
    def pair_complete(device_id: str, body: PairCompleteBody):
        result = _call(lambda: runtime.pairing.complete(device_id, body.pairing_id, body.code))
        if isinstance(result, dict):
            result = dict(result)
            try:
                result["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
            except DesktopRuntimeError:
                pass
        return result

    @router.post("/v1/devices/{device_id}/pair/qr/complete", dependencies=[Depends(auth)])
    def pair_qr_complete(device_id: str, body: PairQrCompleteBody):
        result = _call(lambda: runtime.pairing.complete_qr(device_id, body.pairing_id))
        if isinstance(result, dict) and result.get("paired") is True:
            result = dict(result)
            try:
                result["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
            except DesktopRuntimeError:
                pass
        return result

    @router.post("/v1/devices/{device_id}/pair/revoke", dependencies=[Depends(auth)])
    def pair_revoke(device_id: str):
        return _call(lambda: runtime.pairing.revoke(device_id))

    @router.post("/v1/devices/{device_id}/control", dependencies=[Depends(auth)])
    def manual_control(device_id: str, body: ManualControlBody):
        return _call(lambda: runtime.controls.execute(device_id, body.model_dump(exclude_none=True)))

    @router.get("/v1/devices/{device_id}/clipboard", dependencies=[Depends(auth)])
    def clipboard_get(device_id: str):
        return _call(lambda: runtime.clipboard.capability(device_id))

    @router.post("/v1/devices/{device_id}/clipboard", dependencies=[Depends(auth)])
    def clipboard_set(device_id: str, body: ClipboardBody):
        return _call(lambda: runtime.clipboard.set(device_id, body.text))

    @router.get("/v1/devices/{device_id}/agent/status", dependencies=[Depends(auth)])
    def agent_status(device_id: str):
        return _call(lambda: runtime.agent.status(device_id))

    @router.get("/v1/devices/{device_id}/agent/capabilities", dependencies=[Depends(auth)])
    def agent_capabilities(device_id: str):
        return _call(lambda: runtime.agent.capabilities(device_id))

    @router.post("/v1/devices/{device_id}/agent/observe", dependencies=[Depends(auth)])
    def agent_observe(device_id: str, body: AgentObserveBody):
        payload = body.model_dump(exclude_none=True)
        return _call(lambda: runtime.agent.observe(
            device_id,
            mode=body.mode,
            include_screenshot=body.include_screenshot,
            payload=payload,
        ))

    @router.get("/v1/devices/{device_id}/agent/screenshot", dependencies=[Depends(auth)])
    def agent_screenshot(device_id: str, profile: Literal["thumbnail", "focus"] = Query(default="thumbnail")):
        return _call(lambda: runtime.agent.screenshot(device_id, profile=profile))

    @router.get("/v1/devices/{device_id}/agent/ui/search", dependencies=[Depends(auth)])
    def agent_ui_search(
        device_id: str,
        q: str = Query(min_length=1, max_length=300),
        session_id: str | None = Query(default=None),
        sessionId: str | None = Query(default=None),
        display_id: int | None = Query(default=None, ge=0),
        displayId: int | None = Query(default=None, ge=0),
    ):
        payload = {
            key: value
            for key, value in {
                "session_id": session_id,
                "sessionId": sessionId,
                "display_id": display_id,
                "displayId": displayId,
            }.items()
            if value is not None
        }
        return _call(lambda: runtime.agent.ui_search(device_id, q, payload=payload or None))

    @router.get("/v1/devices/{device_id}/agent/ui/element/{element_id}", dependencies=[Depends(auth)])
    def agent_ui_element(
        device_id: str,
        element_id: str,
        session_id: str | None = Query(default=None),
        sessionId: str | None = Query(default=None),
        display_id: int | None = Query(default=None, ge=0),
        displayId: int | None = Query(default=None, ge=0),
    ):
        payload = {
            key: value
            for key, value in {
                "session_id": session_id,
                "sessionId": sessionId,
                "display_id": display_id,
                "displayId": displayId,
            }.items()
            if value is not None
        }
        return _call(lambda: runtime.agent.ui_element(device_id, element_id, payload=payload or None))

    @router.get("/v1/devices/{device_id}/agent/page/current", dependencies=[Depends(auth)])
    def agent_current_page(device_id: str):
        return _call(lambda: runtime.agent.current_page(device_id))

    @router.get("/v1/devices/{device_id}/agent/page/history", dependencies=[Depends(auth)])
    def agent_page_history(device_id: str):
        return _call(lambda: runtime.agent.page_history(device_id))

    @router.post("/v1/devices/{device_id}/agent/action", dependencies=[Depends(auth)])
    def agent_action(device_id: str, body: AgentActionBody):
        return _call(lambda: runtime.agent.action(device_id, body.model_dump(exclude_none=True)))

    @router.post("/v1/devices/{device_id}/agent/debug", dependencies=[Depends(auth)])
    def agent_debug(device_id: str, body: AgentDebugBody):
        return _call(lambda: runtime.agent.debug_bundle(device_id, expected=body.expected, goal=body.goal))

    @router.post("/v1/devices/{device_id}/agent/teach/start", dependencies=[Depends(auth)])
    def agent_teach_start(device_id: str, body: AgentTeachStartBody):
        return _call(lambda: runtime.agent.teach_start(device_id, goal=body.goal))

    @router.get("/v1/devices/{device_id}/agent/teach/status", dependencies=[Depends(auth)])
    def agent_teach_status(device_id: str):
        return _call(lambda: runtime.agent.teach_status(device_id))

    @router.post("/v1/devices/{device_id}/agent/teach/stop", dependencies=[Depends(auth)])
    def agent_teach_stop(device_id: str, body: AgentTeachStopBody):
        return _call(lambda: runtime.agent.teach_stop(device_id, compile_for_review=body.compile_for_review))

    @router.websocket("/v1/fleet/events")
    async def fleet_events(websocket: WebSocket):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
        q = runtime.fleet.events.subscribe()
        try:
            await websocket.send_json({
                "event": "FLEET_SNAPSHOT",
                "protocol": DESKTOP_PROTOCOL_VERSION,
                "devices": _public_devices(runtime),
            })
            while True:
                try:
                    item = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                await websocket.send_json(_enrich_event(runtime, item))
        except WebSocketDisconnect:
            pass
        finally:
            runtime.fleet.events.unsubscribe(q)

    @router.websocket("/v1/devices/{device_id}/video")
    async def video(websocket: WebSocket, device_id: str, profile: str = Query(default="thumbnail")):
        if not _websocket_authorized(websocket, token):
            await websocket.close(code=4401)
            return
        if profile not in VIDEO_PROFILES:
            await websocket.close(code=4400)
            return
        try:
            session = runtime.fleet.get(device_id)
            controller = session.video
            if controller is None:
                raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Video runtime is unavailable.")
        except DesktopRuntimeError:
            await websocket.close(code=4404)
            return
        await websocket.accept(subprotocol=_accepted_subprotocol(websocket))
        # Self-healing live view: a phone that is known but not ready over USB right now (cable moved, debugging prompt
        # pending, ADB restarting) gets a retryable reason instead of a closed door. The producer keeps capturing and
        # frames flow again as soon as ADB is back, like a remote-desktop client that reconnects on its own.
        adb_state = str(getattr(getattr(session, "adb_device", None), "state", "") or "")
        if adb_state != "device":
            reason = {"unauthorized": "USB_UNAUTHORIZED", "offline": "USB_OFFLINE"}.get(adb_state, "USB_ABSENT")
            runtime.live_diagnostics.mark(device_id, "server.ws.usb_not_ready", details={"profile": profile, "code": reason})
            await websocket.send_text(json.dumps({"type": "stream.error", "code": reason, "retryable": True}, separators=(",", ":")))
        q = controller.subscribe(profile)
        runtime.live_diagnostics.mark(device_id, "server.ws.accepted", details={"profile": profile, "transport": "websocket"})
        first_binary = True
        async def watch_disconnect() -> None:
            # Sending alone does not notice a closed browser while the encoder is quiet.
            # Receive close frames so reloads cannot leave orphan subscriber queues.
            while (await websocket.receive())["type"] != "websocket.disconnect":
                pass

        disconnect = asyncio.create_task(watch_disconnect())
        try:
            while not disconnect.done():
                try:
                    message: StreamMessage = await asyncio.to_thread(q.get, True, 1.0)
                except queue.Empty:
                    continue
                if message.kind == "close":
                    await websocket.close(code=1012)
                    break
                if message.kind == "binary":
                    await websocket.send_bytes(message.data)  # type: ignore[arg-type]
                    if first_binary:
                        first_binary = False
                        runtime.live_diagnostics.mark(device_id, "server.ws.first_frame_sent", details={"profile": profile})
                else:
                    await websocket.send_text(message.data)  # type: ignore[arg-type]
        except WebSocketDisconnect as exc:
            runtime.live_diagnostics.mark(device_id, "server.ws.disconnected", details={"profile": profile, "closeCode": exc.code})
        except Exception as exc:
            runtime.live_diagnostics.mark(
                device_id,
                "server.ws.failed",
                details={"profile": profile, "errorClass": exc.__class__.__name__, "retryable": True},
            )
        finally:
            controller.unsubscribe(profile, q)
            disconnect.cancel()
            with suppress(asyncio.CancelledError, WebSocketDisconnect, RuntimeError):
                await disconnect

    return router


def create_desktop_app(settings: Settings | None = None, runtime: DesktopRuntime | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = create_legacy_app(settings)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://127.0.0.1:1420",
            "http://localhost:1420",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "tauri://localhost",
        ],
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "Accept"],
    )
    desktop = runtime or DesktopRuntime(settings)
    app.state.desktop_runtime = desktop
    app.include_router(create_desktop_router(desktop, settings.token))
    app.include_router(create_stream_router(desktop, settings.token))
    app.include_router(create_cloud_control_router(desktop, settings.token))
    from ..lab.api import create_lab_router
    app.include_router(create_lab_router(desktop, settings.token))
    from ..market.api import create_market_router
    app.include_router(create_market_router(desktop, settings.token))
    from ..command.api import create_command_router, create_oauth_callback_router
    app.include_router(create_command_router(desktop, settings.token))
    app.include_router(create_oauth_callback_router(desktop))
    from ..ports.api import create_ports_router
    app.include_router(create_ports_router(desktop, settings.token))
    if getattr(desktop, "numbers", None) is not None:
        from ..numbers.api import create_numbers_router
        app.include_router(create_numbers_router(desktop.numbers, settings.token))
    # Alpha 87: phone care (update the phone's Cyclone, why it stopped). Test doubles without it skip the routes.
    if getattr(desktop, "care", None) is not None:
        from ..phone_care.api import create_phone_care_router
        app.include_router(create_phone_care_router(desktop.care, settings.token))
    if getattr(desktop, "cloud", None) is not None:
        from ..cloud_fleet.api import create_cloud_fleet_router
        app.include_router(create_cloud_fleet_router(desktop.cloud, settings.token))
    # Cyclone Glass: static web app + launch-code session. Same origin, so no new CORS origins.
    app.state.glass_codes = LaunchCodes()
    from .fleet_api import create_fleet_router
    app.include_router(create_fleet_router(desktop, settings.token))
    app.include_router(create_glass_router(settings.token, app.state.glass_codes, resolve_glass_dist()))
    app.add_event_handler("startup", desktop.start)
    app.add_event_handler("shutdown", desktop.stop)
    return app


def _safe_trust_status(runtime: DesktopRuntime, device_id: str) -> dict[str, Any]:
    try:
        return runtime.trust.status(device_id)
    except DesktopRuntimeError as exc:
        return {
            "deviceId": device_id,
            "protocolVersion": "3.3",
            "state": "EXPIRED",
            "confirmationRequired": False,
            "trusted": False,
            "sessionReady": False,
            "sessionSecretPersisted": False,
            "lastSafeError": exc.safe_message,
        }
    except Exception:
        return {
            "deviceId": device_id,
            "protocolVersion": "3.3",
            "state": "UNPAIRED",
            "confirmationRequired": False,
            "trusted": False,
            "sessionReady": False,
            "sessionSecretPersisted": False,
            "lastSafeError": "Cyclone AI trust status is temporarily unavailable.",
        }


def _public_devices(runtime: DesktopRuntime) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in runtime.fleet.list_public():
        device_id = str(item.get("deviceId") or item.get("id") or "")
        if not device_id:
            continue
        try:
            result.append(enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id)))
        except DesktopRuntimeError:
            continue
    return result


def _enrich_event(runtime: DesktopRuntime, item: dict[str, Any]) -> dict[str, Any]:
    value = dict(item)
    device_id = str(value.get("deviceId") or "")
    if device_id and "device" in value:
        try:
            value["device"] = enrich_device_public(runtime.fleet.get(device_id), _safe_trust_status(runtime, device_id))
        except DesktopRuntimeError:
            pass
    return value


def _session_binding(runtime: DesktopRuntime, token: str) -> str:
    material = f"{runtime.instance_id}\0{runtime.settings.port}\0{token}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()[:24]


def _websocket_authorized(websocket: WebSocket, token: str) -> bool:
    value = websocket.headers.get("authorization", "")
    if value.startswith("Bearer "):
        supplied = value[7:]
        if supplied and hmac.compare_digest(supplied.encode(), token.encode()):
            return True
    for protocol in _requested_subprotocols(websocket):
        prefix = "cyclone-token."
        if protocol.startswith(prefix):
            supplied = protocol[len(prefix):]
            if supplied and hmac.compare_digest(supplied.encode(), token.encode()):
                return True
    return False


def _requested_subprotocols(websocket: WebSocket) -> list[str]:
    raw = websocket.headers.get("sec-websocket-protocol", "")
    return [item.strip() for item in raw.split(",") if item.strip()]


def _accepted_subprotocol(websocket: WebSocket) -> str | None:
    requested = _requested_subprotocols(websocket)
    return "cyclone-v1" if "cyclone-v1" in requested else None


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = {
            RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.DEVICE_UNAUTHORIZED.value: 409,
            RuntimeErrorCode.DEVICE_NOT_READY.value: 409,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.PAIRING_EXPIRED.value: 409,
            RuntimeErrorCode.PAIRING_REPLAY.value: 409,
            RuntimeErrorCode.PAIRING_CODE_REJECTED.value: 403,
            RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED.value: 429,
            RuntimeErrorCode.PAIRING_SESSION_MISMATCH.value: 409,
            RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED.value: 409,
            RuntimeErrorCode.TRUST_REVOKED.value: 403,
            RuntimeErrorCode.TRUST_EXPIRED.value: 401,
            RuntimeErrorCode.TRUST_AUTH_FAILED.value: 403,
            RuntimeErrorCode.TRUST_REJECTED.value: 403,
            RuntimeErrorCode.PROTOCOL_MISMATCH.value: 426,
            RuntimeErrorCode.PHONE_LOCKED.value: 423,
            RuntimeErrorCode.HUMAN_HAS_CONTROL.value: 409,
            RuntimeErrorCode.BACKGROUND_MODE_UNAVAILABLE.value: 409,
            RuntimeErrorCode.STALE_SESSION.value: 410,
            RuntimeErrorCode.FOREGROUND_REQUIRED.value: 409,
            RuntimeErrorCode.STALE_OBSERVATION.value: 409,
            RuntimeErrorCode.POLICY_DENIED.value: 403,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.STREAM_CAPACITY.value: 503,
            RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value: 503,
            RuntimeErrorCode.GATE.value: 409,
            RuntimeErrorCode.MUTATE_LOCK.value: 409,
            RuntimeErrorCode.TARGET_MISMATCH.value: 409,
            RuntimeErrorCode.STALE_WORKSPACE.value: 409,
            RuntimeErrorCode.QUEUE_EMPTY.value: 409,
            RuntimeErrorCode.USER_UNVERIFIED.value: 409,
        }.get(exc.code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc


def _service_call(fn):
    try:
        return fn()
    except (KeyError, StopIteration) as exc:
        raise HTTPException(status_code=404, detail={"code": "NOT_FOUND", "message": "Requested fleet resource was not found."}) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)[:240]}) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail={"code": "PROVIDER_UNAVAILABLE", "message": str(exc)[:240]}) from exc
