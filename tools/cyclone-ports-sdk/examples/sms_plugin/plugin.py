"""Example plugin: SMS codes (``code.in``).

The owner's other phone runs an SMS forwarder app that POSTs each incoming text to this plugin's ``/sms`` webhook:

    POST /sms   X-Forwarder-Token: <SMS_FORWARDER_TOKEN>
    {"from": "Example", "text": "Your Example code is 482913", "receivedAt": "2026-10-01T14:04:02Z"}

When a run waits on ``code.in`` with ``match = {"from": "Example"}`` (and optionally a ``pattern``), the plugin finds a text
that arrived after the wait started (within the window), takes the code out of it and delivers it to the hub with
the run's port token. The hub seals it to the phone; the plugin forgets the text at once.

Rules this example follows (SPEC.md §7), keep them in yours:
- texts are held in memory only, for at most ``WINDOW_S`` seconds, and dropped once used;
- nothing logs a code or a message body;
- the ``source`` it reports is the label the owner registered for this phone (``--source``), so the hub can refuse
  codes from anywhere else. ::

    SMS_FORWARDER_TOKEN=... CYCLONE_PLUGIN_SECRET=... python examples/sms_plugin/plugin.py --source my-second-phone
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from cyclone_ports import PluginServer, deliver, error_body  # noqa: E402

MANIFEST = json.loads((Path(__file__).parent / "cyclone-plugin.json").read_text(encoding="utf-8"))
WINDOW_S = 180          # a text older than this is never used
GRACE_S = 10            # a text that arrived just before the wait started still counts (clock skew, fast senders)
DEFAULT_PATTERN = r"\b\d{4,8}\b"


@dataclass
class Text:
    sender: str
    body: str
    at: float


@dataclass
class Waiting:
    request: dict
    since: float


def find_code(text: Text, match: dict) -> str | None:
    """The code in ``text`` if it fits ``match`` (sender or app name, then the pattern), else None."""
    want = str(match.get("from", "")).lower()
    if want and want not in text.sender.lower() and want not in text.body.lower():
        return None
    try:
        pattern = re.compile(match.get("pattern") or DEFAULT_PATTERN)
    except re.error:
        pattern = re.compile(DEFAULT_PATTERN)
    found = pattern.search(text.body)
    return found.group(0) if found else None


def build(secret: str, source: str, forwarder_token: str, host: str = "127.0.0.1", port: int = 0) -> PluginServer:
    server = PluginServer(dict(MANIFEST), secret, host, port)
    server.manifest["endpoint"] = server.url
    lock = threading.Lock()
    texts: list[Text] = []
    waiting: dict[str, Waiting] = {}

    def prune(now: float) -> None:
        texts[:] = [t for t in texts if now - t.at <= WINDOW_S]
        for await_id in [a for a, w in waiting.items() if now > w.since + float(w.request.get("timeoutS", 60))]:
            del waiting[await_id]

    def try_match() -> None:
        """Pairs waiting runs with texts. Delivery happens outside the lock."""
        pairs = []
        with lock:
            prune(time.time())
            for await_id, w in list(waiting.items()):
                for t in texts:
                    if t.at < w.since - GRACE_S:
                        continue
                    code = find_code(t, w.request.get("match") or {})
                    if code:
                        pairs.append((w.request, code, t.sender))
                        texts.remove(t)
                        del waiting[await_id]
                        break
        for request, code, sender in pairs:
            status, _ = deliver(request["deliverUrl"], request["token"],
                                {"v": 1, "code": code, "source": source, "from": sender})
            print(f"sms-codes: delivered a code for {request['runId']} -> {status}", flush=True)  # never the code

    def on_sms(body: dict | None, headers: dict) -> tuple[int, dict]:
        given = headers.get("X-Forwarder-Token") or headers.get("x-forwarder-token") or ""
        if not forwarder_token or not hmac.compare_digest(given, forwarder_token):
            return 401, error_body("bad_token")
        if not isinstance(body, dict) or not isinstance(body.get("text"), str):
            return 400, error_body("bad_request", "text is required")
        with lock:
            texts.append(Text(str(body.get("from", "")), body["text"][:1000], time.time()))
        threading.Thread(target=try_match, daemon=True).start()
        return 202, {"queued": True}

    def on_await(port_name: str, request: dict) -> None:
        with lock:  # the hub may send the same awaitId again: keep the first wait's start time
            if request["awaitId"] not in waiting:
                waiting[request["awaitId"]] = Waiting(request, time.time())
        threading.Thread(target=try_match, daemon=True).start()

    def on_cancel(port_name: str, request: dict) -> None:
        with lock:
            waiting.pop(request.get("awaitId", ""), None)

    server.route("POST", "/sms", on_sms)
    server.on_await = on_await
    server.on_cancel = on_cancel
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", required=True, help="the label the owner registered for this phone or number")
    parser.add_argument("--host", default="127.0.0.1", help="0.0.0.0 lets the other phone reach /sms on your LAN")
    parser.add_argument("--port", type=int, default=8773)
    args = parser.parse_args()
    secret = os.environ.get("CYCLONE_PLUGIN_SECRET") or sys.exit("set CYCLONE_PLUGIN_SECRET")
    token = os.environ.get("SMS_FORWARDER_TOKEN") or sys.exit("set SMS_FORWARDER_TOKEN (the forwarder app sends it)")
    server = build(secret, args.source, token, args.host, args.port)
    print(f"sms-codes on {server.url}, forwarder webhook {server.url}/sms")
    server.serve_forever()


if __name__ == "__main__":
    main()
