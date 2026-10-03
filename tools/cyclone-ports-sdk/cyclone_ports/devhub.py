"""The Dev Hub: a stand-in for the gateway's Port Hub, so plugins can be built and tested before the real one ships.

It does what the real hub will do on the plugin side of the contract (SPEC.md):
- signs every hub -> plugin request (``POST /ports/{port}``, ``/await``, ``/cancel``);
- serves one-time artifact links for ``screen.shot`` and ``file.out``;
- accepts deliveries on ``POST /v1/ports/{runId}/{port}/deliver`` with the run's port token, with the same status
  codes (401, 403, 404, 409, 410, 422);
- checks that ``code.in`` comes from a registered source.

And it plays the phone: a scenario file lists the ``emit`` and ``await`` steps a run would make.

What it never does: keep a code. A delivered ``code.in`` is handed to :attr:`DevHub.phone_fill` (a no-op unless a test
sets it) and dropped; the audit log says "would be sealed to the phone" and records only metadata.

Usage::

    python -m cyclone_ports.devhub scenarios/signup.json --secret dev-secret \\
        --plugin http://127.0.0.1:8771 --plugin http://127.0.0.1:8773 --source my-second-phone
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

from .catalog import CONTRACT, LIMITS, port_spec
from .sdk import (CONTRACT_HEADER, SIGNATURE_HEADER, TRACE_HEADER, error_body, sign, validate_delivery,
                  validate_envelope, validate_manifest)

# A 1x1 transparent PNG, the screenshot when a scenario gives none.
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
ARTIFACT_TTL_S = 120


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class Await:
    await_id: str
    run_id: str
    port: str
    token: str
    match: dict[str, Any]
    expires_at: float
    way: str = "in"
    delivery_id: str | None = None
    state: str = "waiting"  # waiting | delivered | timed_out | cancelled
    result: dict[str, Any] = field(default_factory=dict)
    done: threading.Event = field(default_factory=threading.Event)


@dataclass
class Artifact:
    data: bytes
    mime: str
    token: str
    expires_at: float
    used: bool = False


class DevHub:
    def __init__(self, secret: str, host: str = "127.0.0.1", port: int = 0, sources: list[str] | None = None,
                 run_dir: str | os.PathLike | None = None, log_path: str | os.PathLike | None = None,
                 quiet: bool = False):
        self.secret = secret
        self.quiet = quiet
        self.sources = set(sources or [])
        self.run_dir = Path(run_dir) if run_dir else None
        self.log_path = Path(log_path) if log_path else None
        self.bindings: dict[str, tuple[str, str]] = {}  # port -> (plugin endpoint, way)
        self.audit: list[dict[str, Any]] = []   # metadata only, never values
        self.phone_fill: Callable[[str, str], None] = lambda run_id, code: None
        self._awaits: dict[str, Await] = {}
        self._artifacts: dict[str, Artifact] = {}
        self._seq: dict[str, int] = {}
        self._lock = threading.Lock()
        self._httpd = ThreadingHTTPServer((host, port), self._handler())
        self._thread: threading.Thread | None = None

    # ---- lifecycle ---------------------------------------------------------------------------------------------------

    @property
    def url(self) -> str:
        host, port = self._httpd.server_address[:2]
        return f"http://{host}:{port}"

    def start(self) -> "DevHub":
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        if self._thread is not None:
            self._httpd.shutdown()
        self._httpd.server_close()

    # ---- binding -----------------------------------------------------------------------------------------------------

    def bind(self, port: str, endpoint: str, way: str | None = None) -> None:
        spec = port_spec(port, way)
        if spec is None or not spec.plugin_served:
            raise ValueError(f"{port} is not a port a plugin can serve")
        self.bindings[port] = (endpoint.rstrip("/"), spec.way)

    def bind_plugin(self, endpoint: str) -> dict[str, Any]:
        """Reads a plugin's manifest and binds every port it serves (the owner's "Add plugin" in Glass)."""
        endpoint = endpoint.rstrip("/")
        with urllib.request.urlopen(endpoint + "/cyclone-plugin.json", timeout=5) as response:
            manifest = json.loads(response.read())
        problems = validate_manifest(manifest)
        if problems:
            raise ValueError(f"{endpoint}: " + "; ".join(problems))
        for served in manifest["serves"]:
            self.bind(served["port"], endpoint, served["way"])
        self._log({"event": "plugin_added", "plugin": manifest["name"], "version": manifest["version"],
                   "ports": [s["port"] for s in manifest["serves"]]})
        return manifest

    # ---- hub -> plugin -----------------------------------------------------------------------------------------------

    def _post_signed(self, url: str, body: dict[str, Any], retries: int = 0,
                     trace: str | None = None) -> tuple[int, dict[str, Any]]:
        """Signs and POSTs. Each attempt gets a fresh request id (replay protection); the body, and so the envelope
        id the plugin de-duplicates on, stays the same."""
        raw = json.dumps(body).encode()
        parsed = urllib.parse.urlparse(url)
        path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        status, answer = 0, {}
        for attempt in range(retries + 1):
            headers = {"Content-Type": "application/json", CONTRACT_HEADER: CONTRACT,
                       SIGNATURE_HEADER: sign(self.secret, "POST", path, raw),
                       TRACE_HEADER: trace or f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"}
            request = urllib.request.Request(url, data=raw, method="POST", headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    return response.status, json.loads(response.read() or b"{}")
            except urllib.error.HTTPError as error:
                try:
                    status, answer = error.code, json.loads(error.read() or b"{}")
                except ValueError:
                    status, answer = error.code, {}
            except (urllib.error.URLError, OSError) as error:
                status, answer = 0, error_body("unreachable", str(error), retryable=True)
            if status not in (0, 429, 500, 502, 503, 504):
                break
            time.sleep(0.2 * (2 ** attempt))
        return status, answer

    def envelope(self, run: dict[str, Any], port: str, data: dict[str, Any] | None = None, **extra: Any) -> dict[str, Any]:
        spec = port_spec(port, self.bindings.get(port, (None, None))[1])
        with self._lock:
            self._seq[run["runId"]] = self._seq.get(run["runId"], 0) + 1
            seq = self._seq[run["runId"]]
        env = {"v": 1, "id": "msg_" + secrets.token_hex(10), "runId": run["runId"], "taskId": run.get("taskId"), "rowId": run.get("rowId"),
               "port": port, "way": spec.way, "seq": seq, "sentAt": now_iso(), "app": run.get("app"),
               "pageKey": extra.pop("pageKey", None), "sensitivity": spec.sensitivity, "data": data or {}}
        env.update(extra)
        return env

    def artifact(self, data: bytes, mime: str) -> str:
        artifact_id, token = "art_" + secrets.token_hex(6), secrets.token_urlsafe(18)
        with self._lock:
            self._artifacts[artifact_id] = Artifact(data, mime, token, time.time() + ARTIFACT_TTL_S)
        return f"{self.url}/v1/artifacts/{artifact_id}?t={token}"

    def emit(self, run: dict[str, Any], port: str, data: dict[str, Any] | None = None, *,
             file_bytes: bytes | None = None, mime: str = "image/png", page_key: str | None = None) -> int:
        """Sends an out-port envelope to the bound plugin. Returns the plugin's HTTP status (0 = unreachable,
        -1 = no plugin bound, which a real run treats as "skipped")."""
        endpoint, way = self.bindings.get(port, (None, None))
        if endpoint is None:
            self._log({"event": "emit_skipped", "runId": run["runId"], "port": port, "reason": "unbound"})
            return -1
        if way != "out":
            raise ValueError(f"{port} is not an out port")
        extra: dict[str, Any] = {"pageKey": page_key}
        if port in ("screen.shot", "file.out"):
            blob = TINY_PNG if file_bytes is None else file_bytes
            extra["artifactUrl"] = self.artifact(blob, mime)
            data = {"mime": mime, "bytes": len(blob), **(data or {})}
        env = self.envelope(run, port, data, **extra)
        problems = validate_envelope(env)
        if problems:
            raise ValueError(f"{port}: " + "; ".join(problems))
        status, _ = self._post_signed(f"{endpoint}/ports/{port}", env, retries=2)
        entry = {"event": "emit", "runId": run["runId"], "port": port, "seq": env["seq"], "status": status}
        if port == "run.event":
            entry["stage"] = env["data"].get("stage")
        elif port == "account.fields":
            entry["fieldNames"] = sorted((env["data"].get("fields") or {}).keys())
        self._log(entry)
        return status

    def await_(self, run: dict[str, Any], port: str, match: dict[str, Any] | None = None,
               timeout_s: float = 60) -> dict[str, Any]:
        """Asks the bound plugin for an in port and waits. Returns the run-side result:
        ``{"state": "delivered", ...}`` or ``{"state": "timed_out"}`` / ``{"state": "failed", ...}``."""
        endpoint, way = self.bindings.get(port, (None, None))
        if endpoint is None:
            self._log({"event": "await_failed", "runId": run["runId"], "port": port, "reason": "unbound"})
            return {"state": "failed", "reason": "unbound"}
        if way != "in":
            raise ValueError(f"{port} is not an in port")
        timeout_s = max(1, min(float(timeout_s), LIMITS["await_timeout_max_s"]))
        record = Await("aw_" + secrets.token_hex(6), run["runId"], port, secrets.token_urlsafe(24), match or {},
                       time.time() + timeout_s, way)
        with self._lock:
            self._awaits[record.await_id] = record
        request = {"v": 1, "runId": run["runId"], "taskId": run.get("taskId"), "rowId": run.get("rowId"),
                   "app": run.get("app"), "port": port, "awaitId": record.await_id, "match": record.match,
                   "timeoutS": timeout_s, "sentAt": now_iso(),
                   "deliverUrl": f"{self.url}/v1/ports/{run['runId']}/{port}/deliver", "token": record.token}
        status, _ = self._post_signed(f"{endpoint}/ports/{port}/await", request, retries=2)
        self._log({"event": "await", "runId": run["runId"], "port": port, "awaitId": record.await_id,
                   "timeoutS": timeout_s, "status": status})
        if status not in (200, 202):
            with self._lock:
                record.state = "cancelled"
            return {"state": "failed", "reason": f"plugin answered {status}"}
        record.done.wait(timeout_s)
        with self._lock:
            if record.state == "waiting":
                record.state = "timed_out"
            state = record.state
        if state == "timed_out":
            self._post_signed(f"{endpoint}/ports/{port}/cancel",
                              {"v": 1, "runId": run["runId"], "port": port, "awaitId": record.await_id,
                               "reason": "timeout"})
            self._log({"event": "await_timed_out", "runId": run["runId"], "port": port, "awaitId": record.await_id})
            return {"state": "timed_out"}
        return {"state": state, **record.result}

    # ---- plugin -> hub -----------------------------------------------------------------------------------------------

    def _deliver(self, run_id: str, port: str, auth: str | None, body: Any) -> tuple[int, dict[str, Any]]:
        token = auth[5:].strip() if auth and auth.startswith("Port ") else ""
        with self._lock:
            record = next((a for a in self._awaits.values() if token and secrets.compare_digest(a.token, token)), None)
            any_for_port = any(a.run_id == run_id and a.port == port for a in self._awaits.values())
        if record is None:
            return (401, error_body("bad_token")) if any_for_port else (404, error_body("no_waiting_run"))
        if record.run_id != run_id or record.port != port:
            return 401, error_body("bad_token")
        delivery_id = body.get("deliveryId") if isinstance(body, dict) else None
        if record.state == "delivered":
            if delivery_id and delivery_id == record.delivery_id:  # a retry of the delivery we took: same answer
                return 200, {"accepted": True, "awaitId": record.await_id, "duplicate": True}
            return 409, error_body("already_delivered")
        if record.state != "waiting" or time.time() > record.expires_at:
            return 410, error_body("expired")
        problems = validate_delivery(port, body, record.way)
        if problems:
            return 422, error_body("invalid", "; ".join(problems), problems=problems)

        if port == "code.in":
            if body["source"] not in self.sources:
                self._log({"event": "delivery_refused", "runId": run_id, "port": port, "reason": "unregistered_source"})
                return 403, error_body("source_not_registered")
            code = body["code"]
            result = {"filled": True, "codeLength": len(code), "source": body["source"]}
            self.phone_fill(run_id, code)  # the real hub seals the code to the phone here
            del code
            note = "would be sealed to the phone"
        elif port == "file.in":
            try:
                blob = base64.b64decode(body["base64"], validate=True)
            except ValueError:
                return 422, error_body("invalid", "base64 is not valid")
            if hashlib.sha256(blob).hexdigest() != body["sha256"].lower():
                return 422, error_body("invalid", "sha256 does not match the file")
            name = re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(body["name"]))[:120] or "file"
            result = {"name": name, "mime": body["mime"], "bytes": len(blob)}
            if self.run_dir:
                folder = self.run_dir / run_id / "files"
                folder.mkdir(parents=True, exist_ok=True)
                (folder / name).write_bytes(blob)
                result["saved"] = str(folder / name)
            note = "saved to the run folder"
        elif port == "link.in":
            result = {"url": body["url"], "source": body["source"]}
            note = "would open in the run"
        else:
            result = {"value": body["value"]}
            note = "handed to the run"

        with self._lock:
            if record.state != "waiting":
                return 409, error_body("already_delivered")
            record.state, record.result, record.delivery_id = "delivered", result, delivery_id
        record.done.set()
        logged = {k: v for k, v in result.items() if k in ("codeLength", "source", "name", "mime", "bytes")}
        self._log({"event": "delivered", "runId": run_id, "port": port, "awaitId": record.await_id, "note": note,
                   **logged})
        return 200, {"accepted": True, "awaitId": record.await_id}

    def _artifact(self, artifact_id: str, token: str) -> tuple[int, bytes, str]:
        with self._lock:
            art = self._artifacts.get(artifact_id)
            if art is None or not secrets.compare_digest(art.token, token or ""):
                return 404, b"", "text/plain"
            if art.used or time.time() > art.expires_at:
                return 410, b"", "text/plain"
            art.used = True
            self._artifacts[artifact_id] = Artifact(b"", art.mime, art.token, art.expires_at, True)
        return 200, art.data, art.mime

    def _handler(self):
        hub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _send(self, status: int, raw: bytes, mime: str = "application/json") -> None:
                self.send_response(status)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                parsed = urlparse(self.path)
                m = re.match(r"^/v1/artifacts/(art_[0-9a-f]+)$", parsed.path)
                if not m:
                    return self._send(404, json.dumps(error_body("not_found")).encode())
                status, data, mime = hub._artifact(m.group(1), (parse_qs(parsed.query).get("t") or [""])[0])
                return self._send(status, data, mime)

            def do_POST(self):
                m = re.match(r"^/v1/ports/([A-Za-z0-9_-]+)/([a-z0-9.-]+)/deliver$", self.path)
                if not m:
                    return self._send(404, json.dumps(error_body("not_found")).encode())
                length = int(self.headers.get("Content-Length") or 0)
                if length > LIMITS["file_bytes"] * 4 // 3 + LIMITS["envelope_bytes"]:
                    return self._send(413, json.dumps(error_body("too_large")).encode())
                try:
                    body = json.loads(self.rfile.read(length) or b"{}")
                except ValueError:
                    return self._send(400, json.dumps(error_body("bad_json")).encode())
                status, answer = hub._deliver(m.group(1), m.group(2), self.headers.get("Authorization"), body)
                return self._send(status, json.dumps(answer).encode())

        return Handler

    # ---- audit -------------------------------------------------------------------------------------------------------

    def _log(self, entry: dict[str, Any]) -> None:
        entry = {"at": now_iso(), **entry}
        with self._lock:
            self.audit.append(entry)
            if self.log_path:
                self.log_path.parent.mkdir(parents=True, exist_ok=True)
                with self.log_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(entry) + "\n")
        if not self.quiet:
            print("hub:", json.dumps(entry), file=sys.stderr, flush=True)

    # ---- scenarios ---------------------------------------------------------------------------------------------------

    def run_scenario(self, scenario: dict[str, Any], base: Path | None = None) -> list[dict[str, Any]]:
        """Plays a scenario's steps as a phone run would. Returns one result per step."""
        run = scenario["run"]
        results = []
        for step in scenario["steps"]:
            if "emit" in step:
                blob = None
                if step.get("file") and base is not None:
                    blob = (base / step["file"]).read_bytes()
                status = self.emit(run, step["emit"], step.get("data"), file_bytes=blob,
                                   mime=step.get("mime", "image/png"), page_key=step.get("pageKey"))
                results.append({"emit": step["emit"], "status": status})
            elif "await" in step:
                outcome = self.await_(run, step["await"], step.get("match"), step.get("timeoutS", 60))
                results.append({"await": step["await"], **outcome})
                if outcome["state"] != "delivered" and step.get("required", True):
                    self.emit(run, "run.event", {"stage": "needs_you", "reason": f"{step['await']} {outcome['state']}"})
                    break
            else:
                raise ValueError(f"unknown step {step!r}")
        return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-devhub", description="Play a Cyclone run against your plugins.")
    parser.add_argument("scenario", help="a scenario JSON file (see scenarios/)")
    parser.add_argument("--secret", default=os.environ.get("CYCLONE_PLUGIN_SECRET", ""),
                        help="the shared plugin secret (or CYCLONE_PLUGIN_SECRET)")
    parser.add_argument("--plugin", action="append", default=[], help="a plugin endpoint; binds every port it serves")
    parser.add_argument("--bind", action="append", default=[], help="port=endpoint, for one port")
    parser.add_argument("--source", action="append", default=[], help="a registered code source (code.in)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--run-dir", default="devhub-runs", help="where file.in files are saved")
    parser.add_argument("--log", default=None, help="append the audit log (metadata only) to this JSONL file")
    args = parser.parse_args(argv)
    if not args.secret:
        parser.error("--secret or CYCLONE_PLUGIN_SECRET is required")

    hub = DevHub(args.secret, args.host, args.port, args.source, args.run_dir, args.log).start()
    try:
        for endpoint in args.plugin:
            hub.bind_plugin(endpoint)
        for pair in args.bind:
            port, _, endpoint = pair.partition("=")
            hub.bind(port, endpoint)
        path = Path(args.scenario)
        results = hub.run_scenario(json.loads(path.read_text(encoding="utf-8")), path.parent)
        print(json.dumps(results, indent=2))
        return 0 if all(r.get("state", "delivered") == "delivered" and r.get("status", 202) in (-1, 200, 202)
                        for r in results) else 1
    finally:
        hub.stop()


if __name__ == "__main__":
    sys.exit(main())
