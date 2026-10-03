"""Example plugin: a run logger (out ports).

Appends every envelope it receives to ``<out>/runs.jsonl`` and downloads screenshots and files to
``<out>/artifacts/``. Run it, then point the Dev Hub at it::

    python examples/logger/plugin.py --out ./logger-out --port 8771     # CYCLONE_PLUGIN_SECRET must be set
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from cyclone_ports import PluginServer, fetch_artifact  # noqa: E402

MANIFEST = json.loads((Path(__file__).parent / "cyclone-plugin.json").read_text(encoding="utf-8"))


def build(secret: str, out_dir: str | os.PathLike, host: str = "127.0.0.1", port: int = 0) -> PluginServer:
    out = Path(out_dir)
    (out / "artifacts").mkdir(parents=True, exist_ok=True)
    server = PluginServer(dict(MANIFEST), secret, host, port)
    server.manifest["endpoint"] = server.url
    lock = threading.Lock()
    seen: dict[str, None] = {}  # envelope ids, newest last: the hub may retry a send

    def on_out(port_name: str, envelope: dict) -> None:
        with lock:
            if envelope["id"] in seen:
                return
            seen[envelope["id"]] = None
            while len(seen) > 5000:
                seen.pop(next(iter(seen)))
        record = dict(envelope)
        url = record.pop("artifactUrl", None)  # one-time links are useless once fetched; don't keep them
        if url:
            data = envelope.get("data") or {}
            ext = ".png" if data.get("mime", "image/png") == "image/png" else ".bin"
            path = out / "artifacts" / f"{envelope['runId']}-{int(envelope.get('seq', 0)):04d}-{port_name}{ext}"
            try:
                path.write_bytes(fetch_artifact(url))
                record["savedAs"] = path.name
            except OSError as error:  # expired or already used: keep the event, note the miss
                record["artifactError"] = type(error).__name__
        with lock, (out / "runs.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps(record) + "\n")

    server.on_out = on_out
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="logger-out")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8771)
    args = parser.parse_args()
    secret = os.environ.get("CYCLONE_PLUGIN_SECRET") or sys.exit("set CYCLONE_PLUGIN_SECRET")
    server = build(secret, args.out, args.host, args.port)
    print(f"run-logger on {server.url}, writing to {args.out}")
    server.serve_forever()


if __name__ == "__main__":
    main()
