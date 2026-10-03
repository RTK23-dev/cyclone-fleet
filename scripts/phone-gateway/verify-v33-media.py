from __future__ import annotations

import argparse
import json
from pathlib import Path
import queue
import sys
import time


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "apps" / "device-gateway"))

from cyclone_device_gateway.adb.client import ADBClient, ADBDevice  # noqa: E402
from cyclone_device_gateway.media.backend import ScrcpyMediaBackend  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Cyclone V3.3 scrcpy media on one Android phone")
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=float, default=10.0)
    args = parser.parse_args()

    adb = ADBClient(args.adb, args.serial)
    selected = adb.select_device(args.serial)
    device = type(
        "PhysicalDevice",
        (),
        {
            "device_id": f"physical-{args.serial[-6:]}",
            "serial": args.serial,
            "adb": adb,
            "adb_device": ADBDevice(
                selected.serial,
                selected.state,
                selected.model,
                selected.product,
                selected.device,
                selected.transport_id,
            ),
            "screen_awake": True,
            "display_width": None,
            "display_height": None,
        },
    )()

    diagnostics: list[dict] = []
    backend = ScrcpyMediaBackend(
        diagnostic=lambda stage, details: diagnostics.append({"stage": stage, "details": details}),
    )
    session = backend.start(device, "focus")
    events = session.subscribe()
    started = time.monotonic()
    deadline = started + max(3.0, args.seconds)
    first_frame_ms: int | None = None
    frames = 0
    config_packets = 0
    keyframes = 0
    dimensions: list[int] | None = None
    states: list[str] = []
    error: dict | None = None
    final_status: dict = {}
    try:
        while time.monotonic() < deadline:
            try:
                event = events.get(timeout=min(0.5, max(0.01, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if event.kind == "state":
                state = str(event.data.get("state") or "")
                if state and (not states or states[-1] != state):
                    states.append(state)
                if state == "UNAVAILABLE":
                    error = dict(event.data)
                    break
            elif event.kind == "session":
                dimensions = [int(event.data["width"]), int(event.data["height"])]
            elif event.kind == "packet":
                if event.data.get("config") is True:
                    config_packets += 1
                    continue
                frames += 1
                if event.data.get("keyframe") is True:
                    keyframes += 1
                if first_frame_ms is None:
                    first_frame_ms = round((time.monotonic() - started) * 1000)
    finally:
        final_status = session.status()
        session.unsubscribe(events)
        backend.shutdown()

    elapsed = max(0.001, time.monotonic() - started)
    result = {
        "ok": first_frame_ms is not None and frames > 0 and error is None,
        "serialSuffix": args.serial[-4:],
        "model": selected.model,
        "androidRelease": adb.shell("getprop", "ro.build.version.release").strip(),
        "androidApi": adb.shell("getprop", "ro.build.version.sdk").strip(),
        "backend": "scrcpy-v4.0",
        "codec": "video/avc",
        "firstFrameMs": first_frame_ms,
        "frames": frames,
        "measuredFps": round(frames / elapsed, 2),
        "configPackets": config_packets,
        "keyframes": keyframes,
        "dimensions": dimensions,
        "states": states,
        "error": error,
        "finalStatus": final_status,
        "diagnostics": diagnostics[-12:],
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
