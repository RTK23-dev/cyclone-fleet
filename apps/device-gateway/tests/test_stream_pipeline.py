from __future__ import annotations

import queue
import threading
import time
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBError
from cyclone_device_gateway.api.stream_api import create_stream_router
from cyclone_device_gateway.desktop_runtime.models import (
    DesktopRuntimeError,
    VIDEO_PROTOCOL_VERSION,
)
from cyclone_device_gateway.desktop_runtime.video import StreamMessage, VideoFleetLimiter, VideoStreamController
from cyclone_device_gateway.media.backend import ScrcpyMediaBackend

_PNG_1X1 = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDAT\x08\xd7c\xf8\xcf\xc0\xf0\x1f\x00\x05\x00\x01\xff\x89\x99"
    b"=\x1d\x00\x00\x00\x00IEND\xaeB`\x82"
)


def unavailable_media_backend() -> ScrcpyMediaBackend:
    return ScrcpyMediaBackend(artifact_path=Path(__file__).with_name("missing-scrcpy-server"))


class FakeStreamADB:
    def __init__(self, *, fail_capture: bool = False):
        self.fail_capture = fail_capture
        self.captures = 0
        self.process_calls: list[list[str]] = []

    def exec_out(self, *args, timeout=15):
        self.captures += 1
        if self.fail_capture:
            raise ADBError("capture unavailable")
        return _PNG_1X1

    def start_process(self, args, stdout=None):
        self.process_calls.append(list(args))
        raise RuntimeError("h264 unavailable in unit test")


class FakeStreamSession:
    def __init__(self, adb=None):
        self.device_id = "dev_stream_test"
        self.serial = "SERIAL-1"
        self.screen_awake = True
        self.display_width = 1080
        self.display_height = 2400
        self.credential = "Z" * 43
        self.adb = adb
        self.adb_device = type("AdbDevice", (), {"state": "device"})()
        self.video = None


class VideoStreamPipelineTests(unittest.TestCase):
    def test_new_socket_before_old_close_gets_a_fresh_decoder_session(self):
        controller = VideoStreamController(FakeStreamSession(FakeStreamADB()), VideoFleetLimiter(),
            media_backend=unavailable_media_backend(), jpeg_first=True)
        def produce(profile, stop):
            controller._broadcast(profile, StreamMessage("text", '{"type":"stream.init"}'))
            stop.wait(3)
        controller._produce_jpeg = produce
        old = controller.subscribe("focus")
        self.assertIn("stream.init", old.get(timeout=2).data)
        new = controller.subscribe("focus")
        try:
            self.assertIn("stream.init", new.get(timeout=2).data)
            self.assertEqual(controller.limiter.snapshot()["sources"], 1)
        finally:
            controller.unsubscribe("focus", old)
            controller.unsubscribe("focus", new)
            controller.stop_all()

    def test_new_viewer_restarts_dead_producer_even_with_an_orphan_queue(self):
        controller = VideoStreamController(FakeStreamSession(FakeStreamADB()), VideoFleetLimiter(),
            media_backend=unavailable_media_backend(), jpeg_first=True)
        orphan = queue.Queue()
        controller._subscribers["focus"].add(orphan)
        q = controller.subscribe("focus")
        try:
            self.assertIn("stream.init", q.get(timeout=2).data)
            self.assertEqual(q.get(timeout=2).kind, "binary")
        finally:
            controller.stop_all()
        self.assertEqual(controller.subscriber_count(), 0)

    def test_quick_reload_waits_for_old_producer_and_starts_exactly_one_replacement(self):
        controller = VideoStreamController(FakeStreamSession(FakeStreamADB()), VideoFleetLimiter(),
            media_backend=unavailable_media_backend(), jpeg_first=True)
        started = queue.Queue()
        allow_old_exit = threading.Event()
        def produce(profile, stop):
            started.put(stop)
            stop.wait(3)
            if not allow_old_exit.is_set():
                allow_old_exit.wait(3)
        controller._produce_jpeg = produce
        first = controller.subscribe("focus")
        old_stop = started.get(timeout=2)
        controller.unsubscribe("focus", first)
        second = controller.subscribe("focus")
        self.assertTrue(old_stop.is_set())
        self.assertTrue(started.empty(), "replacement must wait for old media cleanup")
        allow_old_exit.set()
        new_stop = started.get(timeout=2)
        try:
            self.assertIsNot(old_stop, new_stop)
            self.assertIs(controller._stops["focus"], new_stop)
            self.assertEqual(controller.limiter.snapshot()["sources"], 1)
        finally:
            controller.unsubscribe("focus", second)
            controller.stop_all()
        self.assertEqual(controller.limiter.snapshot()["sources"], 0)

    def test_capture_outage_emits_one_error_then_keepalives_and_keeps_subscription(self):
        adb = FakeStreamADB(fail_capture=True)
        controller = VideoStreamController(
            FakeStreamSession(adb),
            VideoFleetLimiter(),
            media_backend=unavailable_media_backend(),
        )
        q = controller.subscribe("focus")
        self.assertIn("stream.init", q.get(timeout=2).data)
        first = q.get(timeout=2)
        self.assertIn("FRAME_CAPTURE_FAILED", first.data)
        seen_errors = 1
        seen_keepalive = False
        deadline = time.monotonic() + 7.0
        while time.monotonic() < deadline and not seen_keepalive:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                message = q.get(timeout=min(2.5, remaining))
            except queue.Empty:
                continue
            if message.kind != "text":
                continue
            if "FRAME_CAPTURE_FAILED" in message.data:
                seen_errors += 1
            if '"type":"stream.keepalive"' in message.data:
                seen_keepalive = True
        self.assertTrue(seen_keepalive, "expected a keepalive during the capture outage")
        self.assertEqual(seen_errors, 1, "capture outage must emit exactly one stream.error per episode")
        controller.unsubscribe("focus", q)
        controller.stop_all()

    def test_snapshot_returns_fresh_then_cached_frame_without_repeated_capture(self):
        adb = FakeStreamADB()
        controller = VideoStreamController(FakeStreamSession(adb), VideoFleetLimiter())
        frame = controller.snapshot()
        self.assertGreater(len(frame["data"]), 8)
        self.assertTrue(frame["codec"].startswith("image/"))
        self.assertEqual(frame["width"], 1)
        self.assertEqual(frame["height"], 1)
        adb.captures = 0
        cached = controller.snapshot()
        self.assertEqual(adb.captures, 0, "cached snapshot must not issue another ADB capture")
        self.assertEqual(cached["data"], frame["data"])
        self.assertTrue(controller.diagnostics()["lastFrameAvailable"])

    def test_snapshot_failure_is_bounded_and_retryable(self):
        adb = FakeStreamADB(fail_capture=True)
        controller = VideoStreamController(FakeStreamSession(adb), VideoFleetLimiter())
        with self.assertRaises(DesktopRuntimeError) as ctx:
            controller.snapshot()
        self.assertEqual(ctx.exception.code, "CAPABILITY_UNAVAILABLE")
        self.assertTrue(ctx.exception.retryable)

    def test_stream_endpoints_require_pc_auth_and_adb_but_not_ai_pairing(self):
        session = FakeStreamSession(FakeStreamADB())
        controller = VideoStreamController(session, VideoFleetLimiter())
        session.video = controller

        class FakeFleet:
            def get(self, device_id: str):
                if device_id != session.device_id:
                    raise DesktopRuntimeError("DEVICE_NOT_FOUND", "not found")
                return session

        class FakeRuntime:
            def __init__(self):
                self.fleet = FakeFleet()

        app = FastAPI()
        app.include_router(create_stream_router(FakeRuntime(), "pc-secret"))
        client = TestClient(app)
        headers = {"Authorization": "Bearer pc-secret"}
        base = f"/v1/devices/{session.device_id}/stream"

        self.assertEqual(client.get(f"{base}/snapshot").status_code, 401)
        self.assertEqual(client.get("/v1/devices/unknown/stream/snapshot", headers=headers).status_code, 404)
        self.assertEqual(client.get(f"{base}/snapshot?profile=bogus", headers=headers).status_code, 400)
        session.credential = None
        unpaired_response = client.get(f"{base}/snapshot", headers=headers)
        self.assertEqual(unpaired_response.status_code, 200)
        self.assertTrue(unpaired_response.headers["content-type"].startswith("image/"))
        session.credential = "Z" * 43

        response = client.get(f"{base}/snapshot?profile=focus", headers=headers)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.headers["content-type"].startswith("image/"))
        self.assertIn("X-Cyclone-Frame-Sequence", response.headers)
        self.assertEqual(response.headers["cache-control"], "no-store")

        status = client.get(f"{base}/status", headers=headers)
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.json()["protocol"], VIDEO_PROTOCOL_VERSION)
        self.assertIn("video", status.json())

    def test_explicit_jpeg_mode_uses_screenshots_without_provisional_avc(self):
        adb = FakeStreamADB()

        class TrackingBackend:
            def start(self, *_args, **_kwargs):
                raise AssertionError("JPEG-first live view must not start scrcpy")
            def stop(self, *_args, **_kwargs):
                return None
            def latest_safe_snapshot(self, *_args, **_kwargs):
                raise AssertionError("live producer should capture directly")
            def status(self, *_args, **_kwargs):
                return {"backend": "unused", "sessionCount": 0, "sessions": []}
            def probe(self, *_args, **_kwargs):
                return {"artifactVerified": False}

        controller = VideoStreamController(
            FakeStreamSession(adb),
            VideoFleetLimiter(),
            media_backend=TrackingBackend(),
            jpeg_first=True,
        )
        q = controller.subscribe("focus")
        init = q.get(timeout=2)
        frame = q.get(timeout=2)
        self.assertEqual(init.kind, "text")
        self.assertIn('"codec":"image/', init.data)
        self.assertIn('"backend":"adb-screenshot"', init.data)
        self.assertNotIn("video/avc", init.data)
        self.assertNotIn('"provisional":true', init.data)
        self.assertEqual(frame.kind, "binary")
        self.assertEqual(controller.diagnostics()["primaryBackend"], "adb-screenshot")
        self.assertTrue(controller.diagnostics()["jpegFirst"])
        controller.unsubscribe("focus", q)
        controller.stop_all()


if __name__ == "__main__":
    unittest.main()
