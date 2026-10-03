from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.desktop_runtime.page_text import (
    PAGE_SUMMARY_CHAR_LIMIT,
    PAGE_TEXT_CHAR_LIMIT,
    _compact_observation,
)
from cyclone_device_gateway.desktop_runtime.api import create_desktop_app
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode

from test_device_operation_contract import SemanticBridge, make_runtime


def _settings_desktop_observation():
    """Realistic Android observe.semantic payload for Pixel Settings (REDACTED labels only)."""
    return {
        "observationId": "obs-settings-1",
        "pageKey": "SETTINGS_HOME",
        "package": "com.android.settings",
        "activity": "com.android.settings.Settings",
        "pageTitle": "Settings",
        "accessibilityFingerprint": "settings-home-fingerprint",
        "pageContext": {
            "pageKey": "SETTINGS_HOME",
            "packageName": "com.android.settings",
            "title": "Settings",
            "controls": [
                {"label": "Network and internet", "role": "button"},
                {"label": "Connected devices", "role": "button"},
            ],
        },
        "pageText": {
            "protocol": "cyclone-page-text-v1",
            "lineCount": 6,
            "truncated": False,
            "lines": [
                {"text": "Settings", "role": "heading", "y": 40, "x": 24},
                {"text": "Network and internet", "role": "text", "y": 180, "x": 24},
                {"text": "Connected devices", "role": "text", "y": 260, "x": 24},
                {"text": "Apps", "role": "text", "y": 340, "x": 24},
                {"text": "Notifications", "role": "text", "y": 420, "x": 24},
                {"text": "Battery", "role": "text", "y": 500, "x": 24},
            ],
        },
        "pageSummary": {
            "protocol": "cyclone-page-summary-v1",
            "pageKey": "SETTINGS_HOME",
            "title": "Settings",
            "headings": ["Settings"],
            "buttons": ["Network and internet", "Connected devices", "Apps", "Notifications", "Battery"],
            "contentNote": "6 visible text lines, 5 interactive nodes",
        },
        "semanticControls": [
            {"elementId": "semantic:network", "label": "Network and internet", "role": "button"},
            {"elementId": "semantic:devices", "label": "Connected devices", "role": "button"},
            {"elementId": "semantic:apps", "label": "Apps", "role": "button"},
            {"elementId": "semantic:notifications", "label": "Notifications", "role": "button"},
            {"elementId": "semantic:battery", "label": "Battery", "role": "button"},
        ],
        "rawAccessibility": {
            "nodes": [
                {"id": "must-not-reach-compact", "text": "Settings"},
                {"id": "raw-2", "text": "Network and internet"},
            ]
        },
    }


def _apps_desktop_observation():
    return {
        "observationId": "obs-apps-1",
        "pageKey": "SETTINGS_APPS",
        "package": "com.android.settings",
        "activity": "com.android.settings.applications.ManageApplications",
        "pageTitle": "Apps",
        "pageContext": {"pageKey": "SETTINGS_APPS", "title": "Apps"},
        "pageText": {
            "protocol": "cyclone-page-text-v1",
            "lines": [
                {"text": "Apps", "role": "heading", "y": 40, "x": 24},
                {"text": "All apps", "role": "text", "y": 160, "x": 24},
                {"text": "Default apps", "role": "text", "y": 240, "x": 24},
                {"text": "Screen time", "role": "text", "y": 320, "x": 24},
            ],
        },
        "pageSummary": {
            "protocol": "cyclone-page-summary-v1",
            "title": "Apps",
            "buttons": ["All apps", "Default apps", "Screen time"],
            "contentNote": "4 visible text lines, 3 interactive nodes",
        },
        "semanticControls": [
            {"elementId": "semantic:all-apps", "label": "All apps"},
            {"elementId": "semantic:default-apps", "label": "Default apps"},
            {"elementId": "semantic:screen-time", "label": "Screen time"},
        ],
        "rawAccessibility": {"nodes": [{"id": "must-not-reach-apps"}]},
    }


class RealisticDesktopBridge(SemanticBridge):
    def __init__(self, payload):
        super().__init__()
        self.payload = payload

    def request(self, op, args=None, request_id=None):
        if op == "observe.semantic":
            return dict(self.payload)
        return super().request(op, args, request_id)


def _observe(tmp_path, payload):
    runtime, session = make_runtime(tmp_path, bridge=RealisticDesktopBridge(payload))
    app = create_desktop_app(runtime.settings, runtime)
    headers = {"Authorization": "Bearer gateway-secret"}
    with TestClient(app) as client:
        response = client.post(f"/v1/devices/{session.device_id}/agent/observe", headers=headers, json={})
    return response


def _assert_compact_strings(observation, *needles):
    page_text = observation["pageText"]
    page_summary = observation["pageSummary"]
    assert isinstance(page_text, str) and page_text.strip()
    assert isinstance(page_summary, str) and page_summary.strip()
    assert len(page_text) <= PAGE_TEXT_CHAR_LIMIT
    assert len(page_summary) <= PAGE_SUMMARY_CHAR_LIMIT
    for needle in needles:
        assert needle in page_text
        assert needle in page_summary or needle in page_text
    assert observation["pageTextCard"]["protocol"] == "cyclone-page-text-v1"
    assert observation["pageSummaryCard"]["protocol"] == "cyclone-page-summary-v1"
    assert observation["compact"]["pageTextPreserved"] is True
    assert observation["compact"]["pageSummaryPreserved"] is True
    assert observation["compact"]["rawTreeExcluded"] is True
    assert "rawAccessibility" not in observation
    assert "rawTree" not in observation
    assert "accessibilityTree" not in observation


def test_desktop_observe_preserves_settings_page_text_as_strings(tmp_path):
    response = _observe(tmp_path, _settings_desktop_observation())
    assert response.status_code == 200, response.text
    observation = response.json()["observation"]
    _assert_compact_strings(observation, "Settings", "Network and internet", "Apps", "Battery")
    assert observation["semanticControls"][0]["label"] == "Network and internet"


def test_desktop_observe_preserves_apps_page_text_as_strings(tmp_path):
    response = _observe(tmp_path, _apps_desktop_observation())
    assert response.status_code == 200, response.text
    _assert_compact_strings(response.json()["observation"], "Apps", "All apps", "Default apps")


def test_empty_page_text_lines_are_synthesized_from_locatable_control_labels():
    compact = _compact_observation({
        "observationId": "obs-empty-lines",
        "pageTitle": "Settings",
        "pageText": {"protocol": "cyclone-page-text-v1", "lines": []},
        "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Settings", "contentNote": ""},
        "semanticControls": [
            {"label": "Network and internet"},
            {"label": "Apps"},
            {"label": "Battery"},
        ],
        "rawAccessibility": {"nodes": [{"id": "drop-me"}]},
    })
    assert "Settings" in compact["pageText"]
    assert "Network and internet" in compact["pageText"]
    assert "Apps" in compact["pageText"]
    assert compact["pageSummary"]
    assert "rawAccessibility" not in compact


def test_missing_upstream_page_context_fails_as_agent_context_truncation():
    with pytest.raises(DesktopRuntimeError) as raised:
        _compact_observation({
            "observationId": "obs-blank",
            "pageText": {"protocol": "cyclone-page-text-v1", "lines": []},
            "pageSummary": {"protocol": "cyclone-page-summary-v1"},
            "semanticControls": [],
        })
    assert raised.value.code == RuntimeErrorCode.AGENT_CONTEXT_TRUNCATION.value


def test_desktop_observe_empty_context_is_http_409_not_silent_null(tmp_path):
    response = _observe(tmp_path, {
        "observationId": "obs-blank",
        "pageText": None,
        "pageSummary": None,
        "semanticControls": [],
        "rawAccessibility": {"nodes": [{"id": "drop-me"}]},
    })
    assert response.status_code in {409, 503}, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "AGENT_CONTEXT_TRUNCATION"
    assert "pageText" in detail["message"] or "pageSummary" in detail["message"]


def test_compact_observation_does_not_emit_dicts_at_page_text_keys():
    compact = _compact_observation(_settings_desktop_observation())
    assert isinstance(compact["pageText"], str)
    assert isinstance(compact["pageSummary"], str)
    # Agent D compact.py _bounded_text returns None for dicts; strings survive phone.observe.
    assert compact["pageTextCard"]["protocol"] == "cyclone-page-text-v1"
