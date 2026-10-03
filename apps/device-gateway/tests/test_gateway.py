from __future__ import annotations

from pathlib import Path
import struct

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBClient, ADBDevice, ADBError
from cyclone_device_gateway.adb.screenshot import ScreenshotStore
from cyclone_device_gateway.actions.router import (
    ActionRouter,
    ActionValidationError,
    validate_action,
)
from cyclone_device_gateway.auth import AuditLog, redact_params, verify_bearer
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.retrieval.service import RetrievalService
from cyclone_device_gateway.root.provider import RootProvider, RootUnavailable
from cyclone_device_gateway.server import Gateway, create_app
from cyclone_device_gateway.state.store import StateStore
from cyclone_device_gateway.uiautomator.client import normalize_xml


class FakeADB(ADBClient):
    def __init__(self, devices=None):
        self.serial = None
        self._devices = devices or [ADBDevice("PIXEL8", "device", "Pixel_8")]

    def devices(self):
        return self._devices

    def shell(self, *args, timeout=15):
        if args[:2] == ("getprop", "ro.product.manufacturer"):
            return "Google\n"
        if args[:2] == ("getprop", "ro.build.version.release"):
            return "16\n"
        if args[:2] == ("getprop", "ro.build.version.sdk"):
            return "36\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        if args[:2] == ("dumpsys", "input"):
            return "SurfaceOrientation: 0\n"
        if args[:2] == ("su", "-c"):
            return "uid=0(root)" if args[2] == "id" else f"root:{args[2]}"
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n" if args[2] == "com.cyclone.mobile" else ""
        if args and args[0] == "uiautomator":
            return "UI hierarchy dumped"
        return ""

    def exec_out(self, *args, timeout=15):
        if args == ("screencap", "-p"):
            return (
                b"\x89PNG\r\n\x1a\n"
                + b"\x00\x00\x00\x0dIHDR"
                + struct.pack(">II", 1080, 2400)
                + b"rest"
            )
        if args and args[0] == "cat":
            return (
                b'<hierarchy><node text="Apps" resource-id="android:id/title" '
                b'class="android.widget.TextView" clickable="true" enabled="true" '
                b'bounds="[0,0][10,10]" /></hierarchy>'
            )
        return b""

    def forward_bridge(self, local_port=8766):
        return None


class FakeBridge:
    def __init__(self):
        self.page = "HOME"
        self.calls = []
        self.fail_action = False
        self.observation_number = 0

    def _semantic(self):
        self.observation_number += 1
        observation_id = f"obs-{self.observation_number}"
        return {
            "observationId": observation_id,
            "pageKey": self.page,
            "pageTitle": "Apps" if self.page == "APPS" else "Home",
            "package": "com.test",
            "activity": "Main",
            "display": {"width": 1080, "height": 2400, "orientation": "portrait"},
            "accessibilityFingerprint": f"{self.page}-fingerprint",
            "semanticControls": [
                {
                    "elementId": f"semantic:{observation_id}:apps",
                    "label": "Apps",
                    "semanticName": "apps",
                    "role": "button",
                    "resourceId": "id/apps",
                    "clickable": True,
                    "enabled": True,
                    "visibleToUser": True,
                }
            ],
            "controlCount": 1,
            "rawNodeCount": 2,
            "rawAccessibility": {
                "nodes": [
                    {
                        "id": "raw-apps",
                        "text": "Apps",
                        "resourceId": "id/apps",
                        "role": "button",
                        "clickable": True,
                        "enabled": True,
                    },
                    {
                        "id": "raw-other",
                        "text": "Other",
                        "role": "text",
                        "enabled": True,
                    },
                ]
            },
        }

    def request(self, op, args=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            return {
                "gatewayEnabled": True,
                "socketListening": True,
                "accessibilityConnected": True,
                "appVersion": "0.11.4-v2.9.4",
            }
        if op == "observe.semantic":
            return self._semantic()
        if op == "observe.page_debug":
            return {
                "funnel": {
                    "rawNodeCount": 2500,
                    "semanticControlCount": 80,
                    "agentPayloadControlCount": 36,
                },
                "diagnosis": {"stage": "AGENT_CONTEXT_TRUNCATION"},
            }
        if op == "debug.snapshot":
            return {"latestObservation": {"pageKey": self.page}}
        if op == "action.execute":
            before = f"{self.page}-fingerprint"
            if self.fail_action:
                return {
                    "source": "PC_CODEX",
                    "tool": args["tool"],
                    "execution": {
                        "ok": False,
                        "beforeFingerprint": before,
                        "afterFingerprint": before,
                        "error": {
                            "code": "ELEMENT_NOT_FOUND",
                            "message": "missing",
                        },
                    },
                }
            self.page = "APPS"
            return {
                "source": "PC_CODEX",
                "tool": args["tool"],
                "execution": {
                    "ok": True,
                    "beforeFingerprint": before,
                    "afterFingerprint": "APPS-fingerprint",
                    "error": None,
                },
            }
        if op == "ui.element":
            return {"elementId": (args or {}).get("elementId"), "source": "ANDROID_BRIDGE"}
        if op.startswith("teach."):
            return {"ok": True, "op": op}
        return {}


class FakeUIA:
    def observe(self):
        return normalize_xml(
            '<hierarchy><node text="Apps" resource-id="u/apps" '
            'class="android.widget.TextView" clickable="true" enabled="true" '
            'bounds="[0,0][10,10]" /></hierarchy>'
        )


def settings(tmp_path):
    return Settings(
        "http-secret",
        None,
        "adb",
        tmp_path,
        bridge_token="android-bridge-secret",
    )


def test_device_selection_is_deterministic():
    adb = FakeADB([ADBDevice("A", "device"), ADBDevice("B", "device")])
    with pytest.raises(ADBError):
        adb.select_device()
    assert adb.select_device("B").serial == "B"


def test_auth_rejection_and_acceptance():
    with pytest.raises(HTTPException) as error:
        verify_bearer(None, "x")
    assert error.value.status_code == 401
    with pytest.raises(HTTPException) as error:
        verify_bearer("Bearer bad", "x")
    assert error.value.status_code == 403
    verify_bearer("Bearer x", "x")


def test_settings_require_independent_android_bridge_token(monkeypatch):
    monkeypatch.setenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "http-token")
    monkeypatch.delenv("CYCLONE_ANDROID_BRIDGE_TOKEN", raising=False)
    with pytest.raises(RuntimeError, match="CYCLONE_ANDROID_BRIDGE_TOKEN"):
        Settings.from_env()

    monkeypatch.setenv("CYCLONE_ANDROID_BRIDGE_TOKEN", "phone-session-token")
    loaded = Settings.from_env()
    assert loaded.token == "http-token"
    assert loaded.bridge_token == "phone-session-token"


def test_unknown_action_and_nested_shell_rejected():
    with pytest.raises(ActionValidationError):
        validate_action("adb.shell", {})
    with pytest.raises(ActionValidationError):
        validate_action("phone.click", {"selector": {"shell": "id"}})


def test_screenshot_metadata_and_dedup(tmp_path):
    store = ScreenshotStore(tmp_path)
    adb = FakeADB()
    first = store.capture(adb)
    second = store.capture(adb)
    assert first.sha256 == second.sha256 and first.path == second.path
    assert (first.width, first.height) == (1080, 2400)
    assert len(list(tmp_path.glob("*.png"))) == 1


def test_android_action_failure_is_not_recorded_as_success(tmp_path):
    bridge = FakeBridge()
    bridge.fail_action = True
    db = StateStore(tmp_path / "db.sqlite")
    audit = AuditLog(tmp_path / "audit.jsonl")

    def observe():
        semantic = bridge.request("observe.semantic", {})
        observation_id = db.add_observation(semantic)
        return {**db.get_observation(observation_id), "device_serial": "PIXEL8"}

    stabilized = []
    router = ActionRouter(
        bridge,
        db,
        audit,
        observe,
        stabilize=lambda: stabilized.append(True),
    )
    result = router.execute(
        tool="phone.click",
        params={"selector": {"text": "Missing"}},
        goal="Open missing item",
    )
    assert result["success"] is False
    assert result["error_class"] == "ELEMENT_NOT_FOUND"
    assert result["verification"] == "android_action_failed"
    assert db.transition_history()[0]["success"] == 0
    assert stabilized == [True]


def test_transition_recorded_and_type_redacted(tmp_path):
    bridge = FakeBridge()
    db = StateStore(tmp_path / "db.sqlite")
    audit = AuditLog(tmp_path / "audit.jsonl")

    def observe():
        semantic = bridge.request("observe.semantic", {})
        observation_id = db.add_observation(semantic)
        return {**db.get_observation(observation_id), "device_serial": "PIXEL8"}

    router = ActionRouter(bridge, db, audit, observe, stabilize=lambda: None)
    result = router.execute(
        tool="phone.type",
        params={
            "selector": {"resourceId": "id/search", "editable": True},
            "text": "super-secret",
        },
        goal="test",
    )
    assert result["before_page"] == "HOME"
    assert result["after_page"] == "APPS"
    assert db.transition_history()[0]["verification"] == "verification_missing"
    assert result["success"] is False
    text = (tmp_path / "audit.jsonl").read_text()
    assert "super-secret" not in text
    assert "typed_value_redacted" in text


def test_agent2_semantic_controls_are_searchable_and_inspectable(tmp_path):
    db = StateStore(tmp_path / "db.sqlite")
    semantic = FakeBridge()._semantic()
    uia = FakeUIA().observe()
    db.add_observation(semantic, uia=uia)
    retrieval = RetrievalService(db)

    hits = retrieval.search_ui("Apps")
    sources = {item["source"] for item in hits}
    assert "CYCLONE_ACCESSIBILITY" in sources
    assert "CYCLONE_ACCESSIBILITY_RAW" in sources
    assert "UIAUTOMATOR" in sources

    semantic_hit = next(item for item in hits if item["source"] == "CYCLONE_ACCESSIBILITY")
    assert semantic_hit["id"].startswith("semantic:")
    inspected = retrieval.get_element(semantic_hit["elementId"])
    assert inspected is not None
    assert inspected["label"] == "Apps"

    compact = retrieval.get_page_context("compact", "Open Apps")
    assert compact["pageKey"] == "HOME"
    assert compact["controls"][0]["label"] == "Apps"
    assert compact["counts"]["raw"] == 2
    assert compact["provenance"]["merged"] is False


def test_source_comparison_never_silently_merges(tmp_path):
    db = StateStore(tmp_path / "db.sqlite")
    db.add_observation(FakeBridge()._semantic(), uia=FakeUIA().observe())
    compared = RetrievalService(db).compare_sources("Apps")
    assert compared["merged"] is False
    assert len(compared["sources"]["CYCLONE_ACCESSIBILITY"]) == 1
    assert len(compared["sources"]["CYCLONE_ACCESSIBILITY_RAW"]) == 1
    assert len(compared["sources"]["UIAUTOMATOR"]) == 1


def test_root_capability_gating():
    class NoRoot(FakeADB):
        def shell(self, *args, timeout=15):
            if args[:2] == ("su", "-c"):
                raise ADBError("denied")
            return super().shell(*args, timeout=timeout)

    root = RootProvider(NoRoot())
    assert root.available() is False
    with pytest.raises(RootUnavailable):
        root.dumpsys_window()


def test_debug_bundle_and_http_auth(tmp_path):
    configured = settings(tmp_path)
    gateway = Gateway(
        configured,
        adb=FakeADB(),
        bridge=FakeBridge(),
        uia=FakeUIA(),
        root=RootProvider(FakeADB(), tmp_path / "traces"),
    )
    bundle = gateway.debug_bundle(expected="Apps", goal="Open Apps")
    path = Path(bundle["path"])
    assert {
        "cyclone-semantic.json",
        "cyclone-page-debug.json",
        "cyclone-debug-snapshot.json",
        "uiautomator.json",
        "screen.png",
        "page-transitions.json",
        "manifest.json",
    }.issubset({item.name for item in path.iterdir()})

    client = TestClient(create_app(configured, gateway))
    assert client.get("/v1/device/status").status_code == 401
    assert (
        client.get(
            "/v1/device/status",
            headers={"Authorization": "Bearer http-secret"},
        ).status_code
        == 200
    )


def test_mcp_observe_contract_returns_compact_agent2_controls(tmp_path):
    configured = settings(tmp_path)
    gateway = Gateway(
        configured,
        adb=FakeADB(),
        bridge=FakeBridge(),
        uia=FakeUIA(),
        root=RootProvider(FakeADB(), tmp_path / "traces"),
    )
    client = TestClient(create_app(configured, gateway))
    response = client.post(
        "/v1/observe",
        headers={"Authorization": "Bearer http-secret"},
        json={"include_screenshot": False, "mode": "compact"},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["pageKey"] == "HOME"
    assert payload["controls"][0]["label"] == "Apps"
    assert payload["screenshot"] is None


def _legacy_observe_client(tmp_path):
    configured = settings(tmp_path)
    gateway = Gateway(
        configured,
        adb=FakeADB(),
        bridge=FakeBridge(),
        uia=FakeUIA(),
        root=RootProvider(FakeADB(), tmp_path / "traces"),
    )
    return TestClient(create_app(configured, gateway))


def test_legacy_observe_accepts_missing_body(tmp_path):
    client = _legacy_observe_client(tmp_path)
    response = client.post(
        "/v1/observe",
        headers={"Authorization": "Bearer http-secret"},
    )
    assert response.status_code == 200
    assert response.status_code != 422
    payload = response.json()
    assert payload["pageKey"] == "HOME"
    assert payload["controls"][0]["label"] == "Apps"
    assert payload["screenshot"] is None
    empty_json = client.post(
        "/v1/observe",
        headers={
            "Authorization": "Bearer http-secret",
            "Content-Type": "application/json",
        },
        content=b"",
    )
    assert empty_json.status_code == 200
    assert empty_json.json()["pageKey"] == "HOME"


def test_legacy_observe_accepts_empty_object(tmp_path):
    client = _legacy_observe_client(tmp_path)
    response = client.post(
        "/v1/observe",
        headers={"Authorization": "Bearer http-secret"},
        json={},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["pageKey"] == "HOME"
    assert payload["controls"][0]["label"] == "Apps"
    assert payload["screenshot"] is None


def test_legacy_observe_unauthenticated_is_401(tmp_path):
    client = _legacy_observe_client(tmp_path)
    assert client.post("/v1/observe").status_code == 401
    assert client.post("/v1/observe", json={}).status_code == 401


def test_wait_for_stable_requires_two_matching_semantic_samples(tmp_path):
    gateway = Gateway(
        settings(tmp_path),
        adb=FakeADB(),
        bridge=FakeBridge(),
        uia=FakeUIA(),
        root=RootProvider(FakeADB(), tmp_path / "traces"),
    )
    result = gateway.wait_for_stable(timeout_seconds=0.2, poll_seconds=0)
    assert result is not None
    assert result["pageKey"] == "HOME"


def test_phone_type_redaction_has_no_value():
    assert redact_params(
        "phone.type",
        {"text": "abc", "resource_id": "x"},
    ) == {"typed_value_redacted": True}
