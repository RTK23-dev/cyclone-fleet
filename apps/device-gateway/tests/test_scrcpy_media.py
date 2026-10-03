from __future__ import annotations

import hashlib
import queue
import struct
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from cyclone_device_gateway.media.artifact import (
    SCRCPY_COMMIT,
    SCRCPY_SERVER_SHA256,
    SCRCPY_TAG,
    SCRCPY_VERSION,
    ScrcpyArtifact,
    ScrcpyArtifactError,
    metadata,
)
from cyclone_device_gateway.media.backend import MediaEvent, MediaProfile, MediaState, ScrcpyMediaBackend, ScrcpyMediaSession
from cyclone_device_gateway.media.protocol import (
    CodecEvent,
    MediaPacket,
    SCRCPY_CODEC_H264,
    ScrcpyProtocolError,
    ScrcpyVideoPacketParser,
    SessionEvent,
)

CONFIG_FLAG = 1 << 62
KEY_FLAG = 1 << 61


def _media_header(pts_flags: int, payload: bytes) -> bytes:
    return struct.pack(">QI", pts_flags, len(payload)) + payload


class ScrcpyProtocolTests(unittest.TestCase):
    def test_fragmented_and_coalesced_scrcpy_v4_packets(self):
        config = b"\x00\x00\x00\x01\x67\x64\x00\x1f"
        key = b"\x00\x00\x00\x01\x65\x88\x84"
        wire = (
            struct.pack(">I", SCRCPY_CODEC_H264)
            + struct.pack(">III", 0x80000000, 1080, 2400)
            + _media_header(CONFIG_FLAG, config)
            + _media_header(KEY_FLAG | 123_456, key)
        )

        parser = ScrcpyVideoPacketParser()
        events = []
        for cut in (1, 2, 5, 3, 17, 4, 11, 64):
            if not wire:
                break
            chunk, wire = wire[:cut], wire[cut:]
            events.extend(parser.feed(chunk))
        if wire:
            events.extend(parser.feed(wire))
        parser.finish()

        self.assertEqual(len(events), 4)
        self.assertIsInstance(events[0], CodecEvent)
        self.assertEqual(events[0].codec_id, SCRCPY_CODEC_H264)
        self.assertIsInstance(events[1], SessionEvent)
        self.assertEqual((events[1].width, events[1].height), (1080, 2400))
        self.assertIsInstance(events[2], MediaPacket)
        self.assertTrue(events[2].config)
        self.assertFalse(events[2].keyframe)
        self.assertEqual(events[2].payload, config)
        self.assertIsInstance(events[3], MediaPacket)
        self.assertFalse(events[3].config)
        self.assertTrue(events[3].keyframe)
        self.assertEqual(events[3].pts_us, 123_456)
        self.assertEqual(events[3].payload, key)

    def test_multiple_packets_in_one_read_and_rotation_session(self):
        first = struct.pack(">I", SCRCPY_CODEC_H264)
        portrait = struct.pack(">III", 0x80000000, 1080, 2400)
        frame = _media_header(44_000, b"\x00\x00\x01\x41\x01")
        landscape = struct.pack(">III", 0x80000000, 2400, 1080)
        parser = ScrcpyVideoPacketParser()
        events = parser.feed(first + portrait + frame + landscape)
        self.assertEqual([type(item) for item in events], [CodecEvent, SessionEvent, MediaPacket, SessionEvent])
        self.assertEqual((events[-1].width, events[-1].height), (2400, 1080))

    def test_truncated_and_oversized_packets_fail_closed(self):
        parser = ScrcpyVideoPacketParser()
        parser.feed(struct.pack(">I", SCRCPY_CODEC_H264) + b"\x00\x01")
        with self.assertRaises(ScrcpyProtocolError):
            parser.finish()

        parser = ScrcpyVideoPacketParser(max_packet_bytes=8)
        parser.feed(struct.pack(">I", SCRCPY_CODEC_H264))
        with self.assertRaises(ScrcpyProtocolError):
            parser.feed(struct.pack(">QI", 1, 9))


class ScrcpyArtifactTests(unittest.TestCase):
    def test_pin_metadata_is_exact_and_runtime_latest_fetch_is_forbidden(self):
        info = metadata()
        self.assertEqual(SCRCPY_VERSION, "4.0")
        self.assertEqual(SCRCPY_TAG, "v4.0")
        self.assertEqual(SCRCPY_COMMIT, "2322868e9e256eb5fce0b3d659ab2a409f29bae1")
        self.assertEqual(SCRCPY_SERVER_SHA256, "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a")
        self.assertFalse(info["runtimeLatestFetchAllowed"])
        self.assertNotIn("latest", info["source"].lower())

    def test_checksum_guard_rejects_modified_server(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "scrcpy-server-v4.0"
            path.write_bytes(b"not the pinned server")
            artifact = ScrcpyArtifact(path)
            with self.assertRaises(ScrcpyArtifactError):
                artifact.verify()
            self.assertNotEqual(hashlib.sha256(path.read_bytes()).hexdigest(), artifact.sha256)


class ScrcpyBackendTests(unittest.TestCase):
    def test_late_viewer_receives_session_and_codec_configuration(self):
        session = ScrcpyMediaSession(object(), MediaProfile.named("focus"), object())
        config = MediaEvent("packet", {
            "sessionId": session.session_id,
            "ptsUs": None,
            "config": True,
            "keyframe": False,
            "payload": b"\x00\x00\x00\x01\x67\x64\x00\x1f",
        })
        session._state = MediaState.LIVE
        session._width, session._height = 864, 1920
        session._last_config_event = config
        viewer = queue.Queue()
        session._seed_subscriber(viewer)
        self.assertEqual([viewer.get_nowait().kind for _ in range(3)], ["state", "session", "packet"])

    def test_focus_profile_is_true_video_rate_and_thumbnail_is_bounded(self):
        focus = MediaProfile.named("focus")
        thumb = MediaProfile.named("thumbnail")
        self.assertEqual(focus.target_fps, 30)
        self.assertEqual(focus.max_long_edge, 1920)
        self.assertEqual(focus.bitrate_bps, 12_000_000)
        self.assertLessEqual(thumb.target_fps, 10)
        self.assertLess(thumb.bitrate_bps, focus.bitrate_bps)

    def test_safe_snapshot_is_independent_of_bridge_trust(self):
        png = (
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
            b"\x00\x00\x00\x02\x00\x00\x00\x03"
        )

        class FakeAdb:
            def exec_out(self, *args, timeout=0):
                self.last = (args, timeout)
                return png

        class FakeDevice:
            device_id = "dev_media"
            adb = FakeAdb()
            credential = None

        result = ScrcpyMediaBackend().latest_safe_snapshot(FakeDevice())
        self.assertEqual(result.codec, "image/png")
        self.assertEqual((result.width, result.height), (2, 3))

    def test_each_session_uses_and_removes_its_own_remote_server_jar(self):
        class FakeArtifact:
            path = Path("scrcpy-server-v4.0")
            version = "4.0"

            def verify(self):
                pass

        class FakeProcess:
            returncode = None

            def poll(self):
                return self.returncode

            def terminate(self):
                self.returncode = -15

            def wait(self, timeout=0):
                return self.returncode

            def kill(self):
                self.returncode = -9

        class FakeStream:
            def settimeout(self, timeout):
                self.timeout = timeout

            def shutdown(self, how):
                pass

            def close(self):
                pass

        class FakeAdb:
            def __init__(self):
                self.runs = []
                self.started = []

            def run(self, args, timeout=0):
                self.runs.append((tuple(args), timeout))

            def start_process(self, args, stdout=None):
                self.started.append(tuple(args))
                return FakeProcess()

            def remove_forward(self, port):
                pass

        class FakeDevice:
            device_id = "dev_unique_server"
            adb = FakeAdb()

        session = ScrcpyMediaSession(FakeDevice(), MediaProfile.named("focus"), FakeArtifact())
        session._connect = lambda port, process: FakeStream()
        session._consume = lambda stream, parser, process: None

        with patch("cyclone_device_gateway.media.backend.secrets.randbelow", side_effect=[1, 2]):
            session._run_once()
            session._run_once()

        pushes = [args for args, _ in FakeDevice.adb.runs if args[0] == "push"]
        removals = [args for args, _ in FakeDevice.adb.runs if args[:3] == ("shell", "rm", "-f")]
        self.assertEqual(
            [args[2] for args in pushes],
            [
                "/data/local/tmp/cyclone-scrcpy-server-00000001.jar",
                "/data/local/tmp/cyclone-scrcpy-server-00000002.jar",
            ],
        )
        self.assertEqual([args[3] for args in removals], [args[2] for args in pushes])
        self.assertIn(f"CLASSPATH={pushes[0][2]}", FakeDevice.adb.started[0])
        self.assertIn(f"CLASSPATH={pushes[1][2]}", FakeDevice.adb.started[1])
        self.assertIn("send_dummy_byte=true", FakeDevice.adb.started[0])

    def test_forward_tunnel_retries_until_android_ready_byte_arrives(self):
        class FakeArtifact:
            path = Path("scrcpy-server-v4.0")
            version = "4.0"

        class FakeProcess:
            returncode = None

            def poll(self):
                return None

        class FakeSocket:
            def __init__(self, ready):
                self.ready = ready
                self.closed = False

            def settimeout(self, timeout):
                self.timeout = timeout

            def recv(self, size):
                return b"\x00" if self.ready else b""

            def close(self):
                self.closed = True

        first = FakeSocket(False)
        ready = FakeSocket(True)
        session = ScrcpyMediaSession(object(), MediaProfile.named("focus"), FakeArtifact())
        with patch(
            "cyclone_device_gateway.media.backend.socket.create_connection",
            side_effect=[first, ready],
        ):
            connected = session._connect(12345, FakeProcess())

        self.assertIs(connected, ready)
        self.assertTrue(first.closed)
        self.assertFalse(ready.closed)

    def test_controller_reframes_scrcpy_config_and_keyframe_without_image_conversion(self):
        import queue
        from cyclone_device_gateway.desktop_runtime.video import VideoFleetLimiter, VideoStreamController
        from cyclone_device_gateway.media.backend import MediaEvent

        config = b"\x00\x00\x00\x01\x67\x64\x00\x1f"
        key = b"\x00\x00\x00\x01\x65\x88"

        class FakeMedia:
            session_id = "media-session-1"
            def __init__(self):
                self.q = queue.Queue()
                self.q.put(MediaEvent("session", {
                    "sessionId": self.session_id,
                    "width": 1080,
                    "height": 2400,
                    "clientResized": False,
                }))
                self.q.put(MediaEvent("packet", {
                    "sessionId": self.session_id,
                    "ptsUs": None,
                    "config": True,
                    "keyframe": False,
                    "payload": config,
                }))
                self.q.put(MediaEvent("packet", {
                    "sessionId": self.session_id,
                    "ptsUs": 77_000,
                    "config": False,
                    "keyframe": True,
                    "payload": key,
                }))
            def subscribe(self):
                return self.q
            def unsubscribe(self, q):
                pass
            def status(self):
                return {"width": 1080, "height": 2400}

        media = FakeMedia()

        class FakeBackend:
            def __init__(self):
                self.stops = 0
            def start(self, device, profile):
                return media
            def stop(self, device):
                self.stops += 1
            def latest_safe_snapshot(self, device):
                raise AssertionError("primary H.264 path must not request a screenshot")
            def status(self, device):
                return {"backend": "fake", "sessionCount": 1, "sessions": []}
            def probe(self, device):
                return {"artifactVerified": True}

        class Device:
            device_id = "dev_stream"
            serial = "SERIAL"
            screen_awake = True
            display_width = 1080
            display_height = 2400
            credential = None
            adb_device = type("AdbDevice", (), {"state": "device"})()

        backend = FakeBackend()
        controller = VideoStreamController(Device(), VideoFleetLimiter(), media_backend=backend)
        q = controller.subscribe("focus")
        init = q.get(timeout=2)
        config_msg = q.get(timeout=2)
        key_msg = q.get(timeout=2)
        self.assertIn('"codec":"video/avc"', init.data)
        self.assertIn('"backend":"scrcpy-v4.0"', init.data)

        config_header = struct.unpack(">QII", config_msg.data[:16])
        key_header = struct.unpack(">QII", key_msg.data[:16])
        self.assertEqual(config_header[0], 0)
        self.assertTrue(config_header[1] & 0x80000000)
        self.assertFalse(config_header[1] & 0x20000000)
        self.assertEqual(config_msg.data[16:], config)
        self.assertEqual(key_header[0], 77_000)
        self.assertTrue(key_header[1] & 0x40000000)
        self.assertTrue(key_header[1] & 0x20000000)
        self.assertEqual(key_msg.data[16:], key)
        keepalive = q.get(timeout=3)
        self.assertIn('"type":"stream.keepalive"', keepalive.data)
        controller.unsubscribe("focus", q)
        deadline = time.monotonic() + 2
        while controller.diagnostics()["activeProfiles"] and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual(backend.stops, 0, "closing one profile must not kill another live profile")
        controller.stop_all()


if __name__ == "__main__":
    unittest.main()
