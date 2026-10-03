"""Example plugin: PC image picker (``file.in``).

When a run waits on ``file.in``, it sends the newest image in ``--folder``. If the folder is empty it keeps looking
until the run's timeout, so you can drop an image in while the phone waits. The folder is fixed by the owner here: a
run can't ask for another path. ::

    python examples/pc_images/plugin.py --folder ~/Pictures/cyclone --port 8772
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import mimetypes
import os
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from cyclone_ports import LIMITS, PluginServer, deliver  # noqa: E402

MANIFEST = json.loads((Path(__file__).parent / "cyclone-plugin.json").read_text(encoding="utf-8"))
IMAGE_TYPES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
POLL_S = 0.5


def newest_image(folder: Path) -> Path | None:
    images = [p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_TYPES
              and p.stat().st_size <= LIMITS["file_bytes"]]
    return max(images, key=lambda p: p.stat().st_mtime, default=None)


def build(secret: str, folder: str | os.PathLike, host: str = "127.0.0.1", port: int = 0) -> PluginServer:
    folder = Path(folder)
    server = PluginServer(dict(MANIFEST), secret, host, port)
    server.manifest["endpoint"] = server.url
    cancelled: set[str] = set()
    active: set[str] = set()
    lock = threading.Lock()

    def send_when_ready(request: dict) -> None:
        deadline = time.time() + float(request.get("timeoutS", 60))
        while time.time() < deadline and request["awaitId"] not in cancelled:
            image = newest_image(folder) if folder.is_dir() else None
            if image is not None:
                blob = image.read_bytes()
                status, answer = deliver(request["deliverUrl"], request["token"], {
                    "v": 1, "name": image.name, "mime": mimetypes.guess_type(image.name)[0] or "image/png",
                    "base64": base64.b64encode(blob).decode(), "sha256": hashlib.sha256(blob).hexdigest()})
                print(f"pc-images: sent {image.name} ({len(blob)} bytes) -> {status}", flush=True)
                return
            time.sleep(POLL_S)

    def on_await(port_name: str, request: dict) -> None:
        with lock:  # the hub may send the same awaitId again: one sender per wait
            if request["awaitId"] in active:
                return
            active.add(request["awaitId"])
        threading.Thread(target=send_when_ready, args=(request,), daemon=True).start()

    server.on_await = on_await
    server.on_cancel = lambda port_name, request: cancelled.add(request.get("awaitId", ""))
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--folder", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8772)
    args = parser.parse_args()
    secret = os.environ.get("CYCLONE_PLUGIN_SECRET") or sys.exit("set CYCLONE_PLUGIN_SECRET")
    server = build(secret, os.path.expanduser(args.folder), args.host, args.port)
    print(f"pc-images on {server.url}, folder {args.folder}")
    server.serve_forever()


if __name__ == "__main__":
    main()
