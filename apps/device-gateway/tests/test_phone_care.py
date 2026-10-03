"""Alpha 87 phone care: the phone's Cyclone kept current from the PC, why it stopped, and busy vs. gone."""
from __future__ import annotations

import hashlib
import json
import socket
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from cyclone_device_gateway.adb.client import ADBError
from cyclone_device_gateway.cyclone_bridge.client import BridgeBusyError, BridgeDisconnectedError, CycloneBridgeClient
from cyclone_device_gateway.desktop_runtime import phone_errors
from cyclone_device_gateway.desktop_runtime.models import DeviceFleetState, RuntimeErrorCode
from cyclone_device_gateway.phone_care import health as h
from cyclone_device_gateway.phone_care.apk import ApkError, ApkSource, apk_name, release_urls
from cyclone_device_gateway.phone_care.install_errors import read_install_output
from cyclone_device_gateway.phone_care.service import PhoneCareService
from cyclone_device_gateway.phone_care.updater import UpdateJobs
from cyclone_device_gateway.phone_care.verdict import compose
from cyclone_device_gateway.phone_care.versions import InstalledApp, compare, parse_dumpsys_package

PC = "5.0.0-alpha.87.dev1"
OLD = "5.0.0-alpha.84.dev1"
NOW = 1_790_000_000_000


def dumpsys(version: str, code: int) -> str:
    return f"Packages:\n  Package [com.cyclone.mobile] (abc):\n    versionCode={code} minSdk=33 targetSdk=35\n    versionName={version}\n"


# Versions and install errors -----------------------------------------------------------------------------------

def test_the_phone_version_comes_from_dumpsys_and_compares_by_release_order():
    assert parse_dumpsys_package(dumpsys(OLD, 229)) == InstalledApp(OLD, 229)
    assert parse_dumpsys_package("Unable to find package: com.cyclone.mobile") is None
    assert parse_dumpsys_package("") is None
    assert compare(OLD, PC) == "older"
    assert compare(PC, PC) == "same"
    assert compare("5.0.0-alpha.90.dev1", PC) == "newer"
    assert compare(None, PC) == "missing"
    assert compare("weird", PC) == "unknown"


@pytest.mark.parametrize("text,code,retryable", [
    ("Performing Streamed Install\nSuccess", "SUCCESS", False),
    ("adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.cyclone.mobile signatures do not match newer version; ignoring!]",
     "INSTALL_FAILED_UPDATE_INCOMPATIBLE", False),
    ("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", "INSTALL_FAILED_VERSION_DOWNGRADE", False),
    ("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", "INSTALL_FAILED_INSUFFICIENT_STORAGE", True),
    ("Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]", "INSTALL_FAILED_USER_RESTRICTED", True),
    ("Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse]", "INSTALL_PARSE_FAILED_NOT_APK", True),
    ("Failure [INSTALL_FAILED_SOMETHING_NEW]", "INSTALL_FAILED_SOMETHING_NEW", True),
    ("adb: device offline", "PHONE_UNREACHABLE", True),
    ("ADB command timed out", "INSTALL_TIMED_OUT", True),
])
def test_android_install_results_read_as_plain_words(text, code, retryable):
    outcome = read_install_output(text)
    assert outcome.code == code
    assert outcome.retryable is retryable
    assert outcome.title and outcome.message
    assert outcome.ok is (code == "SUCCESS")


# The verified phone build ------------------------------------------------------------------------------------

def fake_release(apk: bytes, *, listed: bool = True, sha: str | None = None):
    calls: list[str] = []
    package_url, manifest_url = release_urls(PC)
    manifest = {"sha256": {apk_name(PC): sha or hashlib.sha256(apk).hexdigest()} if listed else {}}

    def fetch(url: str, _timeout: float) -> bytes:
        calls.append(url)
        if url == manifest_url:
            return json.dumps(manifest).encode()
        if url == package_url:
            return apk
        raise AssertionError(url)
    return fetch, calls


def test_the_phone_build_is_used_only_when_the_release_manifest_lists_its_checksum(tmp_path):
    fetch, calls = fake_release(b"APK-BYTES")
    source = ApkSource(tmp_path, fetch)
    path = source.ensure(PC)
    assert path.read_bytes() == b"APK-BYTES"
    assert path.name == apk_name(PC)
    # Cached: the second time only the manifest is read.
    source.ensure(PC)
    assert len([c for c in calls if c.endswith(".apk")]) == 1

    bad_fetch, _ = fake_release(b"TAMPERED", sha=hashlib.sha256(b"GOOD").hexdigest())
    with pytest.raises(ApkError, match="checksum"):
        ApkSource(tmp_path / "b", bad_fetch).ensure(PC)
    assert not list((tmp_path / "b").rglob("*.apk"))

    unlisted, _ = fake_release(b"APK", listed=False)
    with pytest.raises(ApkError, match="isn't listed"):
        ApkSource(tmp_path / "c", unlisted).ensure(PC)
    with pytest.raises(ApkError):
        ApkSource(tmp_path / "d", fetch).ensure("not-a-version")


# The update job ----------------------------------------------------------------------------------------------

class FakeAdb:
    def __init__(self, versions: list[str | None], install: str | Exception = "Success"):
        self.versions = list(versions)
        self.install = install
        self.commands: list[list[str]] = []

    def shell(self, *args, timeout=15):
        assert args == ("dumpsys", "package", "com.cyclone.mobile")
        version = self.versions.pop(0) if len(self.versions) > 1 else self.versions[0]
        return "Unable to find package: com.cyclone.mobile" if version is None else dumpsys(version, 1)

    def run(self, args, timeout=15):
        self.commands.append(list(args))
        if isinstance(self.install, Exception):
            raise self.install
        return self.install


class FakeApks:
    def __init__(self, error: Exception | None = None):
        self.error = error
        self.forgotten: list[str] = []

    def ensure(self, version, progress=lambda _s: None):
        progress("downloading")
        progress("verifying")
        if self.error:
            raise self.error
        return Path(f"/cache/{version}/Cyclone-{version}.apk")

    def forget(self, version):
        self.forgotten.append(version)


def run_job(adb, apks=None):
    finished = []
    jobs = UpdateJobs(apks or FakeApks(), clock=lambda: NOW / 1000, spawn=lambda fn: fn(), on_finish=lambda d, j: finished.append(j))
    jobs.start("dev_a", adb, PC)
    return jobs.get("dev_a"), finished


def test_an_update_installs_this_pcs_build_without_downgrade_or_uninstall():
    adb = FakeAdb([OLD, PC])
    job, finished = run_job(adb)
    assert job["state"] == "done" and job["from"] == OLD and job["target"] == PC
    assert adb.commands == [["install", "-r", str(Path(f"/cache/{PC}/Cyclone-{PC}.apk"))]]
    assert finished and finished[-1]["state"] == "done"


def test_a_signature_mismatch_explains_itself_and_keeps_the_phones_app():
    adb = FakeAdb([OLD], ADBError("adb: failed to install: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"))
    job, _ = run_job(adb)
    assert job["state"] == "failed"
    assert job["error"]["code"] == "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    assert job["error"]["retryable"] is False
    assert not any("uninstall" in c for cmd in adb.commands for c in cmd)


def test_a_phone_ahead_of_the_pc_is_never_downgraded_and_a_current_phone_is_left_alone():
    ahead = FakeAdb(["5.0.0-alpha.99.dev1"])
    job, _ = run_job(ahead)
    assert job["error"]["code"] == "INSTALL_FAILED_VERSION_DOWNGRADE" and ahead.commands == []
    same = FakeAdb([PC])
    job, _ = run_job(same)
    assert job["state"] == "done" and same.commands == []


def test_a_damaged_download_is_forgotten_and_a_silent_non_update_is_caught():
    apks = FakeApks()
    job, _ = run_job(FakeAdb([OLD], ADBError("Failure [INSTALL_PARSE_FAILED_NOT_APK: nope]")), apks)
    assert job["state"] == "failed" and apks.forgotten == [PC]
    job, _ = run_job(FakeAdb([OLD, OLD]))
    assert job["error"]["code"] == "INSTALL_NOT_APPLIED"
    job, _ = run_job(FakeAdb([OLD]), FakeApks(ApkError("offline")))
    assert job["error"]["code"] == "DOWNLOAD_FAILED" and job["error"]["retryable"] is True


# Health history ----------------------------------------------------------------------------------------------

REPORT = {
    "app": {"versionName": OLD, "versionCode": 229, "uptimeMs": 5000, "secret": "x"},
    "exits": [
        {"atMs": NOW - 3_600_000, "kind": "anr", "reason": 6, "unexpected": True, "description": "Input dispatching timed out",
         "mainThread": ["com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490", "bad frame with spaces"]},
        {"atMs": NOW - 7_200_000, "kind": "low_memory", "reason": 3, "unexpected": True},
        {"atMs": 0, "kind": "crash"},
        "junk",
    ],
    "stalls": [{"startedAtMs": NOW - 60_000, "durationMs": 23_703, "suspect": "com.cyclone.mobile.X.y:1", "frames": ["com.cyclone.mobile.X.y:1"], "samples": 40}],
    "typedText": "never kept",
}


def test_the_health_report_keeps_only_known_fields_and_merges_across_reconnects(tmp_path):
    clean = h.clean_report(REPORT)
    assert "typedText" not in json.dumps(clean) and "secret" not in json.dumps(clean)
    assert len(clean["exits"]) == 2
    assert clean["exits"][0]["mainThread"] == ["com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490"]
    store = h.HealthStore(tmp_path)
    store.add("dev/a", clean, NOW)
    merged = store.add("dev/a", clean, NOW + 1)
    assert len(merged["exits"]) == 2 and len(merged["stalls"]) == 1
    assert h.last_stop(merged)["label"] == "it stopped responding and Android closed it"
    assert h.freezes(merged, NOW) == {"count": 1, "longestMs": 23_703, "lastAtMs": NOW - 60_000, "suspect": "com.cyclone.mobile.X.y:1"}


# The verdict: one calm answer ---------------------------------------------------------------------------------

def verdict(**kw):
    base = dict(reachable=True, pc_version=PC, phone=InstalledApp(PC, 232), phone_known=True, job=None, history={}, now_ms=NOW)
    base.update(kw)
    return compose(**base)


def test_one_headline_and_at_most_one_action_in_priority_order():
    assert verdict()["status"] == "good" and verdict()["headline"] == "Up to date" and verdict()["action"] is None
    older = verdict(phone=InstalledApp(OLD, 229))
    assert older["headline"] == "Update available" and older["action"] == {"kind": "update", "label": "Update phone"}
    assert verdict(phone=None)["action"]["label"] == "Install Cyclone"
    newer = verdict(phone=InstalledApp("5.0.0-alpha.99.dev1", 1))
    assert newer["headline"] == "This PC needs an update" and newer["action"] is None and "cyclone update" in newer["hint"]
    working = verdict(phone=InstalledApp(OLD, 229), job={"state": "installing"})
    assert working["status"] == "working" and "plugged in" in working["detail"]
    failed = verdict(job={"state": "failed", "finishedAtMs": NOW, "error": read_install_output("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]").to_dict()})
    assert failed["status"] == "problem" and failed["action"] is None and "Remove Cyclone" in failed["hint"]
    assert verdict(reachable=False)["status"] == "offline"


def test_an_unexpected_stop_or_a_real_freeze_is_worth_a_headline_but_normal_noise_is_not():
    history = h.merge({}, h.clean_report(REPORT), NOW)
    stopped = verdict(history=history)
    assert stopped["headline"] == "Cyclone stopped unexpectedly" and stopped["atMs"] == NOW - 3_600_000
    assert stopped["details"]["lastStop"]["kind"] == "anr"
    memory_only = h.merge({}, h.clean_report({"exits": [{"atMs": NOW - 1000, "kind": "low_memory", "reason": 3}]}), NOW)
    assert verdict(history=memory_only)["headline"] == "Up to date"
    frozen = h.merge({}, h.clean_report({"stalls": REPORT["stalls"]}), NOW)
    assert verdict(history=frozen)["headline"] == "Cyclone froze once today"
    short = h.merge({}, h.clean_report({"stalls": [{"startedAtMs": NOW - 1000, "durationMs": 600}]}), NOW)
    assert verdict(history=short)["headline"] == "Up to date"
    assert verdict(history=short)["details"]["freezes"]["count"] == 1


# The service with a fake fleet ------------------------------------------------------------------------------

class FakeBridge:
    def __init__(self, report):
        self.report = report
        self.timeout = 10

    def request(self, op, args):
        assert op == "health.report"
        return self.report


class FakeFleet:
    def __init__(self, session):
        self.session = session

    def get(self, device_id):
        return self.session

    def list_public(self):
        return [{"deviceId": "dev_a"}]


def test_the_service_collects_health_and_answers_with_versions_and_evidence(tmp_path):
    session = SimpleNamespace(state=DeviceFleetState.READY, credential="c", mobile_version=OLD, adb=FakeAdb([OLD]),
                              bridge=lambda: FakeBridge(REPORT))
    service = PhoneCareService(FakeFleet(session), tmp_path, pc_version=lambda: PC, clock=lambda: NOW / 1000)
    assert service.collect("dev_a")["exits"]
    answer = service.care("dev_a")
    assert answer["headline"] == "Update available"
    assert answer["details"]["versions"] == {"phone": OLD, "phoneCode": 1, "pc": PC, "compare": "older"}
    assert answer["details"]["lastStop"]["kind"] == "anr"


# Busy is not disconnected --------------------------------------------------------------------------------------

def test_a_phone_that_accepts_but_does_not_answer_is_busy_and_a_refused_one_is_gone():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    held: list[socket.socket] = []
    threading.Thread(target=lambda: held.append(server.accept()[0]), daemon=True).start()
    client = CycloneBridgeClient(port=port, token="t", timeout=0.3, auto_forward=False)
    with pytest.raises(BridgeBusyError):
        client.request("bridge.status", {})
    server.close()
    for conn in held:
        conn.close()
    closed = socket.socket()
    closed.bind(("127.0.0.1", 0))
    free_port = closed.getsockname()[1]
    closed.close()
    with pytest.raises(BridgeDisconnectedError) as gone:
        CycloneBridgeClient(port=free_port, token="t", timeout=0.3, auto_forward=False).request("bridge.status", {})
    assert not isinstance(gone.value, BridgeBusyError)

    busy = phone_errors.transport(BridgeBusyError("x"))
    assert busy.code == RuntimeErrorCode.PHONE_APP_BUSY.value and busy.retryable
    lost = phone_errors.transport(BridgeDisconnectedError("x"))
    assert lost.code == RuntimeErrorCode.DEVICE_DISCONNECTED.value and lost.retryable
    assert phone_errors.retryable("OBSERVATION_CHANGED_DURING_CAPTURE") and not phone_errors.retryable("POLICY_DENIED")


def test_the_care_routes_need_the_bearer_and_start_only_this_pcs_update(tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
    from cyclone_device_gateway.phone_care.api import create_phone_care_router

    # The care view reads the version, then the update reads it again before installing and once after.
    session = SimpleNamespace(state=DeviceFleetState.UNPAIRED, credential=None, mobile_version=None, adb=FakeAdb([OLD, OLD, PC]),
                              bridge=lambda: FakeBridge(REPORT))

    class Fleet(FakeFleet):
        def get(self, device_id):
            if device_id != "dev_a":
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Device is not connected.")
            return self.session

    jobs = UpdateJobs(FakeApks(), clock=lambda: NOW / 1000, spawn=lambda fn: fn())
    service = PhoneCareService(Fleet(session), tmp_path, pc_version=lambda: PC, clock=lambda: NOW / 1000, jobs=jobs)
    app = FastAPI()
    app.include_router(create_phone_care_router(service, "tok"))
    client = TestClient(app)
    assert client.get("/v1/devices/dev_a/care").status_code == 401
    auth = {"Authorization": "Bearer tok"}
    first = client.get("/v1/devices/dev_a/care", headers=auth).json()
    # Not paired yet, and still updatable: an old phone app may be why pairing fails.
    assert first["headline"] == "Update available" and first["action"]["label"] == "Update phone"
    done = client.post("/v1/devices/dev_a/care/update", headers=auth).json()
    assert done["headline"] == "Updated"
    assert session.adb.commands == [["install", "-r", str(Path(f"/cache/{PC}/Cyclone-{PC}.apk"))]]
    assert client.get("/v1/devices/nope/care", headers=auth).status_code == 404


def test_decision_numbers_reach_the_pc_as_counts_and_times_only(tmp_path):
    raw = {**REPORT, "decisions": {
        "provider": "JEV (TypeSafe)", "phoneModel": "earned", "speed": "auto", "lessons": 120, "lastDay": 30,
        "byDecider": {"grammar": 40, "decisions": 70, "phone": 10, "../x": 5}, "onPhoneShare": 0.42,
        "decisionsMs": {"p50": 420, "p95": 1100, "count": 70}, "phoneModelMs": {"p50": 3, "p95": 6, "count": 10},
        "instantVerified": 0.97, "instantChecked": 60, "shadowAgreement": 0.99, "shadowChecked": 70, "teaching": 90,
        "earned": ["volume_up", "Bad Intent"], "actions": [{"intent": "volume_up", "samples": 55, "agreement": 0.99, "earned": True}],
        "request": "text mom i'm late"}}
    clean = h.clean_report(raw)
    decisions = clean["decisions"]
    assert decisions["earned"] == ["volume_up"] and "../x" not in decisions["byDecider"]
    assert decisions["decisionsMs"] == {"p50": 420, "p95": 1100, "count": 70}
    assert "text mom" not in json.dumps(decisions)
    merged = h.merge({}, clean, NOW)
    answer = verdict(history=merged)
    assert answer["details"]["decisions"]["onPhoneShare"] == 0.42
