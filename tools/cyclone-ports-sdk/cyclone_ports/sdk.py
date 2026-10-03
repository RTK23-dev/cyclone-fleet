"""A small, dependency-free SDK for Cyclone port plugins (contract ``cyclone.ports/1``, see SPEC.md).

What a plugin gets from it:
- :class:`PluginServer`: serves ``GET /cyclone-plugin.json`` and ``GET /health``, verifies the hub's signature on
  every ``POST`` (key rotation, replay protection), reads envelopes tolerantly (unknown fields and values never break
  it), and calls your handlers for out ports, awaits and cancels;
- :func:`deliver`: sends data for an in port back to the hub, idempotently, with retries;
- the validators the Dev Hub and the conformance checker use, so a plugin can test itself.

Python 3.10+, standard library only.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets as _secrets
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Mapping
from urllib.parse import urlparse

from .catalog import CATALOG, CONTRACT, FORBIDDEN_FIELD_WORDS, LIMITS, RUN_STAGES, is_extension, port_spec

NAME = re.compile(r"^[a-z][a-z0-9-]{1,40}$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
PORT_PATH = re.compile(r"^/ports/([a-z0-9.-]+)(/await|/cancel)?$")
SIGNATURE_HEADER = "X-Cyclone-Signature"
CONTRACT_HEADER = "X-Cyclone-Contract"
TRACE_HEADER = "traceparent"
DEFAULT_KID = "k1"


# ---- errors ----------------------------------------------------------------------------------------------------------

def error_body(code: str, message: str = "", retryable: bool = False, **extra: Any) -> dict[str, Any]:
    """The one error shape every party uses: ``{"error": {"code", "message", "retryable", ...}}``."""
    return {"error": {"code": code, "message": message or code.replace("_", " "), "retryable": retryable, **extra}}


def error_code(body: Any) -> str:
    """The error code from an answer, whatever its shape."""
    err = body.get("error") if isinstance(body, dict) else None
    return err.get("code", "") if isinstance(err, dict) else (err or "")


# ---- secrets and signing (hub -> plugin) -----------------------------------------------------------------------------

def parse_secret(value: str) -> tuple[str, str]:
    """``"k2.s3cr3t"`` -> ``("k2", "s3cr3t")``. A value without a key id gets ``k1``."""
    kid, dot, rest = value.partition(".")
    if dot and re.match(r"^k[0-9a-z-]{1,15}$", kid) and rest:
        return kid, rest
    return DEFAULT_KID, value


def secrets_from_env(env: Mapping[str, str] | None = None) -> dict[str, str]:
    """Reads ``CYCLONE_PLUGIN_SECRET`` and, during a rotation, ``CYCLONE_PLUGIN_SECRET_NEXT``. Returns {kid: secret}."""
    env = os.environ if env is None else env
    keys: dict[str, str] = {}
    for var in ("CYCLONE_PLUGIN_SECRET", "CYCLONE_PLUGIN_SECRET_NEXT"):
        if env.get(var):
            kid, secret = parse_secret(env[var])
            keys[kid] = secret
    return keys


def _keyring(secret: str | Mapping[str, str]) -> dict[str, str]:
    if isinstance(secret, str):
        kid, value = parse_secret(secret)
        return {kid: value}
    return dict(secret)


def signing_string(t: int, request_id: str, method: str, path: str, body: bytes) -> bytes:
    """What the signature covers: time, request id, method, path (with query) and the body's SHA-256."""
    return f"{t}\n{request_id}\n{method.upper()}\n{path}\n{hashlib.sha256(body).hexdigest()}".encode()


def sign(secret: str, method: str, path: str, body: bytes, t: int | None = None, request_id: str | None = None,
         kid: str | None = None) -> str:
    """The value of ``X-Cyclone-Signature``: ``t=<unix s>,kid=<key id>,id=<request id>,v1=<hex HMAC-SHA256>``.

    ``secret`` may carry its key id (``"k2.value"``). ``path`` is the request path including any query string."""
    parsed_kid, value = parse_secret(secret)
    kid = kid or parsed_kid
    t = int(time.time()) if t is None else t
    request_id = request_id or "req_" + _secrets.token_hex(12)
    mac = hmac.new(value.encode(), signing_string(t, request_id, method, path, body), hashlib.sha256).hexdigest()
    return f"t={t},kid={kid},id={request_id},v1={mac}"


def parse_signature(header: str | None) -> dict[str, str]:
    if not header:
        return {}
    return dict(p.strip().split("=", 1) for p in header.split(",") if "=" in p)


def verify(secret: str | Mapping[str, str], header: str | None, method: str, path: str, body: bytes,
           now: float | None = None) -> bool:
    """Checks a hub signature against one secret or a keyring {kid: secret} (both keys during a rotation)."""
    parts = parse_signature(header)
    keys = _keyring(secret)
    value = keys.get(parts.get("kid", DEFAULT_KID))
    if not value or not parts.get("id") or not parts.get("v1"):
        return False
    try:
        t = int(parts.get("t", ""))
    except ValueError:
        return False
    now = time.time() if now is None else now
    if abs(now - t) > LIMITS["signature_skew_s"]:
        return False
    expected = hmac.new(value.encode(), signing_string(t, parts["id"], method, path, body), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"])


class ReplayCache:
    """Remembers signed request ids for the signature window, so a captured request can't be sent twice."""

    def __init__(self, window_s: float = 2 * LIMITS["signature_skew_s"]):
        self.window_s = window_s
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    def first_time(self, request_id: str) -> bool:
        now = time.time()
        with self._lock:
            if len(self._seen) > 10_000:
                self._seen = {k: v for k, v in self._seen.items() if v > now}
            if self._seen.get(request_id, 0) > now:
                return False
            self._seen[request_id] = now + self.window_s
            return True


# ---- validators ------------------------------------------------------------------------------------------------------

def validate_manifest(m: Any) -> list[str]:
    problems: list[str] = []
    if not isinstance(m, dict):
        return ["the manifest must be a JSON object"]
    if m.get("contract") != CONTRACT:
        problems.append(f"contract must be {CONTRACT!r}")
    if not isinstance(m.get("name"), str) or not NAME.match(m["name"]):
        problems.append("name must be lowercase letters, digits and dashes (2-41 characters)")
    if not isinstance(m.get("version"), str) or not SEMVER.match(m["version"]):
        problems.append("version must be semver, like 1.2.0")
    endpoint = m.get("endpoint")
    if not isinstance(endpoint, str) or not re.match(r"^https?://", endpoint):
        problems.append("endpoint must be an http(s) URL")
    elif endpoint.startswith("http://") and not re.match(r"^http://(127\.0\.0\.1|localhost|\[::1\])(:\d+)?/?$", endpoint):
        problems.append("plain http is only allowed on loopback (127.0.0.1, localhost, ::1); use https elsewhere")
    if "features" in m and (not isinstance(m["features"], list) or not all(isinstance(f, str) for f in m["features"])):
        problems.append("features must be a list of strings")
    serves = m.get("serves")
    if not isinstance(serves, list) or not serves:
        problems.append("serves must list at least one port")
        serves = []
    seen: dict[str, str] = {}
    for i, s in enumerate(serves):
        if not isinstance(s, dict):
            problems.append(f"serves[{i}] must be an object")
            continue
        name = s.get("port")
        if is_extension(name):
            if s.get("way") not in ("out", "in"):
                problems.append(f"serves[{i}].way must be 'out' or 'in' for extension port {name}")
                continue
            if name.split(".")[1] != m.get("name"):
                problems.append(f"serves[{i}].port {name}: extension ports must be named x.{m.get('name')}.<name>")
        else:
            port = CATALOG.get(name)
            if port is None:
                problems.append(f"serves[{i}].port {name!r} is not in the catalog (extensions are x.<plugin>.<name>)")
                continue
            if not port.plugin_served:
                problems.append(f"serves[{i}].port {port.name} is handled by the hub and the vault only in v1")
            if s.get("way") != port.way:
                problems.append(f"serves[{i}].way must be {port.way!r} for {port.name}")
        if name in seen:
            problems.append(f"serves lists {name} twice")
        seen[name] = s.get("way")
    needs = m.get("needs", {})
    if not isinstance(needs, dict) or not isinstance(needs.get("personal", False), bool):
        problems.append("needs.personal must be true or false")
    else:
        personal = [n for n, w in seen.items() if (spec := port_spec(n, w)) and spec.sensitivity == "personal"]
        if personal and not needs.get("personal"):
            problems.append("a plugin serving a personal port must declare needs.personal: true")
    return problems


def validate_envelope(env: Any, strict: bool = True) -> list[str]:
    """Checks an out-port envelope.

    ``strict=True`` is what the hub checks before it sends. ``strict=False`` is the tolerant reader a plugin uses on
    receipt: structure only, so a newer hub's new stages, fields or values never break an older plugin."""
    problems: list[str] = []
    if not isinstance(env, dict):
        return ["the envelope must be a JSON object"]
    for key in ("v", "id", "runId", "port", "way", "seq", "sentAt", "sensitivity"):
        if key not in env:
            problems.append(f"missing {key}")
    if env.get("v") != 1:
        problems.append("v must be 1 (this contract's major version)")
    if not isinstance(env.get("data", {}), dict):
        problems.append("data must be an object")
    if len(json.dumps(env).encode()) > LIMITS["envelope_bytes"]:
        problems.append("the envelope is too large")
    if not strict:
        if env.get("way") not in ("out", "in"):
            problems.append("way must be 'out' or 'in'")
        return problems

    port = port_spec(env.get("port"), env.get("way"))
    if port is None:
        return problems + [f"port {env.get('port')!r} is not in the catalog"]
    if env.get("way") != port.way:
        problems.append(f"way must be {port.way!r}")
    if env.get("sensitivity") != port.sensitivity:
        problems.append(f"sensitivity must be {port.sensitivity!r}")
    data = env.get("data") or {}
    if port.name == "run.event" and data.get("stage") not in RUN_STAGES:
        problems.append(f"run.event data.stage must be one of {', '.join(RUN_STAGES)}")
    if port.name == "account.fields" or is_extension(port.name):
        fields = data.get("fields") if port.name == "account.fields" else data
        for key in (fields or {}):
            if any(w in str(key).lower() for w in FORBIDDEN_FIELD_WORDS):
                problems.append(f"{port.name} may not carry {key!r}")
    if port.name == "log.line" and len(str(data.get("text", ""))) > LIMITS["log_line_chars"]:
        problems.append("log.line text is too long")
    if port.name in ("screen.shot", "file.out") and not env.get("artifactUrl"):
        problems.append(f"{port.name} needs an artifactUrl")
    return problems


def validate_delivery(port_name: str, body: Any, way: str | None = "in") -> list[str]:
    """Checks a plugin's delivery for an in port (SPEC.md §5)."""
    if not isinstance(body, dict):
        return ["the delivery must be a JSON object"]
    port = port_spec(port_name, way)
    if port is None or port.way != "in" or not port.plugin_served:
        return [f"{port_name} is not an in port a plugin can deliver to"]
    problems: list[str] = []
    if body.get("v") != 1:
        problems.append("v must be 1")
    if "deliveryId" in body and (not isinstance(body["deliveryId"], str) or not 8 <= len(body["deliveryId"]) <= 80):
        problems.append("deliveryId must be a string of 8-80 characters")
    if port_name == "code.in":
        code = body.get("code")
        if not isinstance(code, str) or not re.match(r"^[A-Za-z0-9-]{3,%d}$" % LIMITS["code_chars"], code):
            problems.append("code must be 3-12 letters, digits or dashes")
        for key in ("source", "from"):
            if not isinstance(body.get(key), str) or not body.get(key):
                problems.append(f"{key} is required")
    elif port_name == "link.in":
        url = body.get("url")
        if not isinstance(url, str) or not url.startswith("https://"):
            problems.append("url must be an https link")
        if not isinstance(body.get("source"), str):
            problems.append("source is required")
    elif port_name == "value.in" or is_extension(port_name):
        if "value" not in body:
            problems.append("value is required")
        elif len(json.dumps(body["value"]).encode()) > LIMITS["value_bytes"]:
            problems.append("value is too large")
    elif port_name == "file.in":
        for key in ("name", "mime", "base64", "sha256"):
            if not isinstance(body.get(key), str) or not body.get(key):
                problems.append(f"{key} is required")
        if isinstance(body.get("sha256"), str) and not re.match(r"^[0-9a-fA-F]{64}$", body["sha256"]):
            problems.append("sha256 must be 64 hex characters")
        if isinstance(body.get("base64"), str) and len(body["base64"]) * 3 // 4 > LIMITS["file_bytes"]:
            problems.append("the file is too large")
    return problems


# ---- plugin -> hub ---------------------------------------------------------------------------------------------------

RETRYABLE = {0, 429, 500, 502, 503, 504}


def deliver(deliver_url: str, token: str, payload: dict[str, Any], timeout: float = 10, attempts: int = 4,
            backoff_s: float = 0.5, traceparent: str | None = None) -> tuple[int, dict[str, Any]]:
    """POSTs a delivery for an in port. Returns (HTTP status, JSON answer); status 0 means unreachable.

    Adds a ``deliveryId`` if the payload has none, so retries are safe: the hub answers a repeat of the same delivery
    with 200 instead of 409. Retries network errors, 429 and 5xx with backoff (honouring Retry-After)."""
    payload = dict(payload)
    payload.setdefault("deliveryId", "dl_" + _secrets.token_hex(12))
    raw = json.dumps(payload).encode()
    headers = {"Authorization": f"Port {token}", "Content-Type": "application/json", CONTRACT_HEADER: CONTRACT}
    if traceparent:
        headers[TRACE_HEADER] = traceparent
    status, answer = 0, {}
    for attempt in range(max(1, attempts)):
        retry_after = None
        try:
            request = urllib.request.Request(deliver_url, data=raw, method="POST", headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            status = error.code
            retry_after = error.headers.get("Retry-After") if error.headers else None
            try:
                answer = json.loads(error.read() or b"{}")
            except ValueError:
                answer = {}
        except (urllib.error.URLError, OSError) as error:
            status, answer = 0, error_body("unreachable", str(error), retryable=True)
        if status not in RETRYABLE or attempt == attempts - 1:
            break
        try:
            delay = float(retry_after) if retry_after else backoff_s * (2 ** attempt)
        except ValueError:
            delay = backoff_s * (2 ** attempt)
        time.sleep(min(delay, 10))
    return status, answer


def fetch_artifact(url: str, timeout: float = 10) -> bytes:
    """Downloads a one-time artifact link from an out-port envelope. A second fetch of the same link fails."""
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


# ---- the plugin server -----------------------------------------------------------------------------------------------

class PluginServer:
    """Serves a plugin over HTTP. Register handlers, then :meth:`serve_forever` (or :meth:`start` for a thread).

    Handlers:
    - ``on_out(port, envelope)``: an out-port envelope arrived (signature-checked, read tolerantly). The hub may
      retry: de-duplicate on ``envelope["id"]``;
    - ``on_await(port, request)``: a run is waiting on an in port. Deliver later with :func:`deliver` using
      ``request["deliverUrl"]`` and ``request["token"]``. The same ``awaitId`` can arrive twice (the hub re-sends
      after a restart): treat it as the same wait;
    - ``on_cancel(port, request)``: the run stopped waiting (timeout, done, cancelled).
    Extra routes (for example a webhook your SMS forwarder posts to) go through :meth:`route`.

    ``secret`` is one secret (``"value"`` or ``"k2.value"``) or a keyring ``{kid: secret}``; by default it is read
    from ``CYCLONE_PLUGIN_SECRET`` (and ``CYCLONE_PLUGIN_SECRET_NEXT`` during a rotation).
    """

    def __init__(self, manifest: dict[str, Any], secret: str | Mapping[str, str] | None = None,
                 host: str = "127.0.0.1", port: int = 0):
        problems = validate_manifest(manifest)
        if problems:
            raise ValueError("manifest problems: " + "; ".join(problems))
        keys = _keyring(secret) if secret else secrets_from_env()
        if not keys:
            raise ValueError("no plugin secret: pass one or set CYCLONE_PLUGIN_SECRET")
        self.manifest = manifest
        self.keys = keys
        self.replays = ReplayCache()
        self.on_out: Callable[[str, dict], None] = lambda port, env: None
        self.on_await: Callable[[str, dict], None] = lambda port, req: None
        self.on_cancel: Callable[[str, dict], None] = lambda port, req: None
        self._routes: dict[tuple[str, str], Callable[[dict | None, dict], tuple[int, dict]]] = {}
        self._httpd = ThreadingHTTPServer((host, port), self._handler())
        self._thread: threading.Thread | None = None

    @property
    def url(self) -> str:
        host, port = self._httpd.server_address[:2]
        if host in ("0.0.0.0", "::"):  # listening everywhere: the hub still reaches it on loopback
            host = "127.0.0.1"
        return f"http://{host}:{port}"

    def served(self) -> dict[str, str]:
        return {s["port"]: s["way"] for s in self.manifest["serves"]}

    def route(self, method: str, path: str, fn: Callable[[dict | None, dict], tuple[int, dict]]) -> None:
        """An extra, unsigned route on the plugin itself (not called by the hub). ``fn(json_body, headers)``."""
        self._routes[(method.upper(), path)] = fn

    def start(self) -> "PluginServer":
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def serve_forever(self) -> None:
        self._httpd.serve_forever()

    def stop(self) -> None:
        if self._thread is not None:
            self._httpd.shutdown()
        self._httpd.server_close()

    def _handler(self):
        plugin = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # quiet: never log bodies
                pass

            def _send(self, status: int, body: dict[str, Any]) -> None:
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.send_header(CONTRACT_HEADER, CONTRACT)
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                path = urlparse(self.path).path
                if path == "/cyclone-plugin.json":
                    return self._send(200, plugin.manifest)
                if path == "/health":
                    return self._send(200, {"ok": True, "name": plugin.manifest["name"],
                                            "version": plugin.manifest["version"], "contract": CONTRACT})
                extra = plugin._routes.get(("GET", path))
                if extra:
                    return self._send(*extra(None, dict(self.headers)))
                return self._send(404, error_body("not_found"))

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                if length > LIMITS["file_bytes"] + LIMITS["envelope_bytes"]:
                    return self._send(413, error_body("too_large"))
                raw = self.rfile.read(length)
                path = urlparse(self.path).path
                extra = plugin._routes.get(("POST", path))
                if extra:
                    try:
                        body = json.loads(raw or b"{}")
                    except ValueError:
                        return self._send(400, error_body("bad_json"))
                    return self._send(*extra(body, dict(self.headers)))
                match = PORT_PATH.match(path)
                if not match:
                    return self._send(404, error_body("not_found"))
                header = self.headers.get(SIGNATURE_HEADER)
                if not verify(plugin.keys, header, "POST", self.path, raw):
                    return self._send(401, error_body("bad_signature"))
                if not plugin.replays.first_time(parse_signature(header)["id"]):
                    return self._send(401, error_body("replayed", "this signed request was already received"))
                port, action = match.group(1), match.group(2) or ""
                way = plugin.served().get(port)
                if way is None:
                    return self._send(404, error_body("port_not_served"))
                try:
                    body = json.loads(raw or b"{}")
                except ValueError:
                    return self._send(400, error_body("bad_json"))
                if not isinstance(body, dict) or body.get("port", port) != port:
                    return self._send(422, error_body("port_mismatch", "the body is for another port"))
                if action == "":
                    if way != "out":
                        return self._send(404, error_body("port_not_served", f"{port} is an in port"))
                    problems = validate_envelope(body, strict=False)
                    if problems:
                        return self._send(422, error_body("bad_envelope", problems=problems))
                    plugin.on_out(port, body)
                    return self._send(202, {"received": True})
                if way != "in":
                    return self._send(404, error_body("port_not_served", f"{port} is an out port"))
                if action == "/await":
                    for key in ("awaitId", "deliverUrl", "token"):
                        if not isinstance(body.get(key), str):
                            return self._send(422, error_body("bad_await", f"{key} is required"))
                    plugin.on_await(port, body)
                    return self._send(202, {"accepted": True})
                plugin.on_cancel(port, body)
                return self._send(200, {"cancelled": True})

        return Handler
