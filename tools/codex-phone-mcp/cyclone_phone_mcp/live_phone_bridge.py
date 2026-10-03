"""Dedicated HTTPS MCP surface for Cyclone Live Phone.

Cyclone One owns the loopback endpoint and optional HTTPS tunnel. The primary
1.4 catalog is the six-tool Cyclone Interaction Protocol (CIP). Legacy
cyclone_phone_* calls remain accepted for compatible clients but are not the
normal advertised surface. Native Codex remains a separate stdio path.
"""
from __future__ import annotations

import base64
import hmac
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from .cip import CipEngine
from .live_phone import ACTIONS, validate_request
from .live_phone_ipc import root

PORT = 8788
SUPPORTED_PROTOCOLS = ("2025-03-26", "2025-06-18")
LATEST_PROTOCOL = SUPPORTED_PROTOCOLS[-1]

LEGACY_PREFIX = "cyclone_phone_"
LEGACY_OPERATIONS = {
    "devices": "devices", "status": "status", "observe": "observe", "screenshot": "screenshot",
    "locate": "locate", "search_ui": "locate", "inspect": "inspect", "tap": "tap",
    "long_press": "long-press", "type": "type", "clear_text": "clear-text",
    "swipe": "swipe", "scroll": "scroll", "back": "back", "home": "home", "open_app": "open-app",
}


class MethodNotFound(ValueError): pass
class InvalidParams(ValueError): pass


def _s(props=None, required=None, description=""):
    return {"type": "object", "properties": props or {}, "required": required or [],
            "additionalProperties": False, "description": description}


def _string(description, max_length=240, enum=None):
    value = {"type": "string", "maxLength": max_length, "description": description}
    if enum: value["enum"] = enum
    return value


def _point_schema():
    return _s({
        "x": {"type": "number", "minimum": 0, "maximum": 1},
        "y": {"type": "number", "minimum": 0, "maximum": 1},
        "space": {"type": "string", "enum": ["display_norm"], "default": "display_norm"},
    }, ["x", "y"], "Normalized coordinates in the current observation frame.")


def _action_schema():
    target = {"oneOf": [
        _s({"element_id": _string("Current observation-scoped element id.")}, ["element_id"]),
        _s({"point": _point_schema()}, ["point"]),
    ]}
    return _s({
        "kind": _string("Typed phone action.", enum=["tap", "long_press", "type", "clear", "scroll", "swipe", "back", "home", "open_app"]),
        "target": target,
        "text": _string("Text to enter into the selected editable element.", 4000),
        "direction": _string("Semantic scroll direction.", enum=["forward", "backward", "up", "down"]),
        "from": _point_schema(), "to": _point_schema(),
        "duration_ms": {"type": "integer", "minimum": 100, "maximum": 3000},
        "package": _string("Android package to open."),
    }, ["kind"])


def catalog():
    device = _string("Optional physical device id. Omit or use auto when exactly one phone is ready.")
    observation = _string("Current immutable observation id; expires after 30 seconds or any mutation.")
    tools = [
        ("cyclone_devices", "Cyclone Devices", "List ready physical USB/LAN phones. One phone is auto-selected.", _s()),
        ("cyclone_see", "Cyclone See", "Observe the visible physical screen. Returns semantic UI and the current screenshot image.",
         _s({"device": device, "goal": _string("Optional visible goal for same-call semantic ranking.", 1000)})),
        ("cyclone_find", "Cyclone Find", "Find a semantic element inside the current observation without mutating the phone.",
         _s({"device": device, "observation_id": observation, "query": _string("Visible UI text or goal to find.")}, ["observation_id", "query"])),
        ("cyclone_inspect", "Cyclone Inspect", "Inspect one current observation-scoped element.",
         _s({"device": device, "observation_id": observation, "element_id": _string("Current element id.")}, ["observation_id", "element_id"])),
        ("cyclone_act", "Cyclone Act", "Perform exactly one typed foreground phone mutation. Never retry UNCERTAIN; inspect the returned after-state.",
         _s({"device": device, "request_id": _string("Unique at-most-once mutation id.", 128),
             "observation_id": observation, "action": _action_schema(),
             "goal": _string("Intended visible result.", 1000),
             "user_authorized": {"type": "boolean", "default": False,
                "description": "Acknowledges explicit user intent when the action requires it; Android policy remains authoritative."}},
            ["request_id", "observation_id", "action"])),
        ("cyclone_session", "Cyclone Session", "Read Live Phone session state. Pause/stop remain owned by Cyclone One's user controls.",
         _s({"operation": _string("Session operation.", enum=["status", "enable", "pause", "stop"])})),
    ]
    result = []
    for name, title, description, schema in tools:
        mutating = name == "cyclone_act"
        result.append({"name": name, "title": title, "description": description,
            "inputSchema": schema,
            "annotations": {"readOnlyHint": not mutating, "destructiveHint": mutating,
                "idempotentHint": name != "cyclone_act", "openWorldHint": True}})
    return result


def _legacy_catalog():
    result = []
    for name, op in LEGACY_OPERATIONS.items():
        props, required = {}, []
        def field(key, description, needed=True):
            props[key] = _string(description, 4000 if key == "text" else 240)
            if needed: required.append(key)
        if op not in {"devices", "status"}: field("device", "Physical device id.")
        if op in {"locate"} | ACTIONS: field("goal", "Intended visible result.")
        if op in ACTIONS:
            field("observation_id", "Current observation id.")
            props["user_authorized"] = {"type": "boolean", "default": False}
        if op in {"inspect", "tap", "long-press", "type", "clear-text"}: field("element", "Current element id.")
        if op == "type": field("text", "Text to type.")
        if op in {"scroll", "swipe"}:
            field("direction", "Semantic direction."); props["direction"]["enum"] = ["forward", "backward", "up", "down", "left", "right"]
        if op == "open-app": field("package", "Android package.")
        result.append({"name": LEGACY_PREFIX + name, "inputSchema": _s(props, required)})
    return result


def cloud_key():
    from cyclone_device_gateway.tooling_seam import _protect, _unprotect
    path = root() / "cloud-key.dpapi"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_protect(secrets.token_urlsafe(32).encode()))
    return _unprotect(path.read_bytes()).decode()


def _validate_top(schema, args):
    if not isinstance(args, dict): raise InvalidParams("arguments must be an object")
    props, required = schema["properties"], schema.get("required", [])
    if set(args) - set(props) or set(required) - set(args): raise InvalidParams("arguments do not match the typed tool schema")
    for key, value in args.items():
        spec = props[key]; expected = spec.get("type")
        if expected == "string":
            if not isinstance(value, str) or len(value) > spec.get("maxLength", 240): raise InvalidParams(f"{key} must be a bounded string")
            if spec.get("enum") and value not in spec["enum"]: raise InvalidParams(f"{key} is not supported")
        elif expected == "boolean" and type(value) is not bool: raise InvalidParams(f"{key} must be boolean")
        elif expected == "object" and not isinstance(value, dict): raise InvalidParams(f"{key} must be an object")
    return args


class Bridge:
    def __init__(self, broker):
        self.broker = broker; self.lock = broker.lock; self.cip = CipEngine(broker.engine)
        self.frames = {}; self.last_cloud = 0

    def mark_connected(self):
        self.last_cloud = int(time.time())
        try: (root() / "connector-status.json").write_text(json.dumps({"at": self.last_cloud}), encoding="utf-8")
        except OSError: pass

    def control(self):
        try: return json.loads((root() / "control.json").read_text())
        except (OSError, ValueError): return {}

    def dispatch(self, method, params):
        self.mark_connected()
        if method == "initialize":
            if not isinstance(params, dict) or not isinstance(params.get("protocolVersion"), str): raise InvalidParams("initialize params required")
            if not isinstance(params.get("capabilities", {}), dict) or not isinstance(params.get("clientInfo", {}), dict): raise InvalidParams("invalid initialize metadata")
            requested = params["protocolVersion"]; negotiated = requested if requested in SUPPORTED_PROTOCOLS else LATEST_PROTOCOL
            return {"protocolVersion": negotiated, "capabilities": {"tools": {}, "resources": {}},
                "serverInfo": {"name": "Cyclone Live Phone", "title": "Cyclone Live Phone", "version": "1.4.0"},
                "instructions": "Use Cyclone SEE -> ACT -> verified after-state. Prefer cyclone_see, cyclone_find and current element ids; normalized visual points are observation-bound. Every mutation needs a unique request_id. Never retry UNCERTAIN. Live Phone is physical foreground only."}
        if method == "ping": return {}
        if method == "tools/list": return {"tools": catalog()}
        if method == "resources/list": return {"resources": []}
        if method == "resources/templates/list": return {"resourceTemplates": []}
        if method == "resources/read":
            if not isinstance(params, dict) or not isinstance(params.get("uri"), str): raise InvalidParams("resource uri required")
            return self.read_frame(params)
        if method != "tools/call": raise MethodNotFound("Unsupported MCP method")
        if not isinstance(params, dict): raise InvalidParams("tools/call params required")
        name, args = params.get("name", ""), params.get("arguments", {})
        primary = {tool["name"]: tool["inputSchema"] for tool in catalog()}
        legacy = {tool["name"]: tool["inputSchema"] for tool in _legacy_catalog()}
        with self.lock:
            self.last_cloud = int(time.time()); self.frames.clear()
            if name in primary:
                args = _validate_top(primary[name], args)
                op = {"cyclone_devices": "devices", "cyclone_see": "see", "cyclone_find": "find",
                    "cyclone_inspect": "inspect", "cyclone_act": "act", "cyclone_session": "session"}[name]
                result = self.cip.dispatch(op, args)
                return self.response(result)
            if name in legacy:
                args = _validate_top(legacy[name], args)
                suffix = name[len(LEGACY_PREFIX):]
                request = validate_request({"operation": LEGACY_OPERATIONS[suffix], **args})
                result = self.broker.handle(request)
                return self.response(result)
        raise MethodNotFound("Unknown phone tool")

    def _image_candidates(self, result):
        candidates = []
        if isinstance(result.get("observation"), dict): candidates.append(result["observation"])
        if isinstance(result.get("after"), dict): candidates.append(result["after"])
        candidates.extend([result, result.get("after", {})])
        seen = set()
        for observation in candidates:
            if not isinstance(observation, dict) or id(observation) in seen: continue
            seen.add(id(observation)); yield observation

    def response(self, result):
        images, control = [], self.control()
        for observation in self._image_candidates(result):
            screenshot = observation.get("screenshot") if isinstance(observation.get("screenshot"), dict) else {}
            vision = observation.get("vision") if isinstance(observation.get("vision"), dict) else {}
            path_value = vision.get("path") if vision.get("ready") else screenshot.get("reference")
            if not path_value or control.get("stopped", True) or result.get("control_changed"): continue
            try: path = Path(path_value).resolve()
            except (TypeError, OSError): continue
            if path not in {(root() / "latest.png").resolve(), (root() / "latest.jpg").resolve()}: continue
            try: data = path.read_bytes()
            except OSError: continue
            if len(data) > 8 * 1024 * 1024: continue
            mime = vision.get("mime") or screenshot.get("mime") or ("image/png" if path.suffix.lower() == ".png" else "image/jpeg")
            device = observation.get("device"); obs_id = observation.get("observation_id") or observation.get("id")
            uri = "cyclone-image://" + secrets.token_urlsafe(24)
            self.frames[uri] = (time.monotonic() + 30, control.get("generation"), device, obs_id, mime, data)
            observation["screenshot"] = {"available": True, "mime": mime,
                "width": vision.get("width") or screenshot.get("width"), "height": vision.get("height") or screenshot.get("height"),
                "reference": uri, "expires_in_seconds": 30}
            images.append({"type": "image", "mimeType": mime, "data": base64.b64encode(data).decode(),
                "annotations": {"audience": ["assistant"], "priority": 1.0}})
        while len(self.frames) > 4: self.frames.pop(next(iter(self.frames)))
        def clean(value):
            if isinstance(value, dict):
                return {key: clean(item) for key, item in value.items()
                    if key not in {"screenshot_path", "path", "artifact", "reference"}
                    or (key == "reference" and isinstance(item, str) and item.startswith("cyclone-image://"))}
            if isinstance(value, list): return [clean(item) for item in value]
            return value
        clean_result = clean(result); encoded = json.dumps(clean_result)
        if len(encoded.encode()) > 2 * 1024 * 1024:
            self.frames.clear(); clean_result = {"ok": False, "error": "RESPONSE_TOO_LARGE"}; encoded = json.dumps(clean_result); images = []
        return {"content": [{"type": "text", "text": encoded}, *images], "structuredContent": clean_result,
                "isError": clean_result.get("ok") is False}

    def read_frame(self, params):
        with self.lock:
            frame, control = self.frames.get(params.get("uri")), self.control()
            if not frame or control.get("stopped", True) or control.get("enabled") is not True:
                self.frames.clear(); raise InvalidParams("Image expired")
            expires, generation, device, observation, mime, data = frame
            current = self.broker.engine.observations.get(device)
            if time.monotonic() >= expires or generation != control.get("generation") or not current or current[0] != observation:
                self.frames.pop(params.get("uri"), None); raise InvalidParams("Image expired")
            return {"contents": [{"uri": params["uri"], "mimeType": mime, "blob": base64.b64encode(data).decode()}]}


class Server(ThreadingHTTPServer):
    daemon_threads = True; allow_reuse_address = True
    def __init__(self, address, bridge, token):
        self.bridge, self.token = bridge, token; self.slots = threading.BoundedSemaphore(4); super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *args): pass
    def setup(self): super().setup(); self.connection.settimeout(95)
    def _path_ok(self):
        try: return urlparse(self.path).path.rstrip("/") == "/mcp"
        except ValueError: return False
    def _origin_ok(self):
        origin = self.headers.get("Origin")
        if not origin: return True
        try: parsed = urlparse(origin)
        except ValueError: return False
        host = (parsed.hostname or "").lower()
        if host in {"127.0.0.1", "localhost"}: return parsed.scheme in {"http", "https"}
        return parsed.scheme == "https" and (host == "chatgpt.com" or host.endswith(".chatgpt.com") or host == "openai.com" or host.endswith(".openai.com") or host.endswith(".trycloudflare.com"))
    def _authorized(self): return self._path_ok() and hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.server.token)
    def _cors_headers(self):
        origin = self.headers.get("Origin")
        if not origin or not self._origin_ok(): return {}
        return {"Access-Control-Allow-Origin": origin, "Vary": "Origin",
            "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID",
            "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Expose-Headers": "Mcp-Session-Id, WWW-Authenticate"}
    def reply(self, status, body=None, extra_headers=None):
        data = b"" if body is None else json.dumps(body).encode(); self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8"); self.send_header("Cache-Control", "no-store"); self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in self._cors_headers().items(): self.send_header(key, value)
        for key, value in (extra_headers or {}).items(): self.send_header(key, value)
        self.send_header("Content-Length", str(len(data))); self.end_headers()
        if data: self.wfile.write(data)
    def _preflight(self):
        if not self._origin_ok(): self.reply(403, {"error": "origin not allowed"}); return False
        if not self._path_ok(): self.reply(404, {"error": "not found"}); return False
        return True
    def _protocol_ok(self):
        version = self.headers.get("MCP-Protocol-Version"); return not version or version in SUPPORTED_PROTOCOLS
    def do_OPTIONS(self):
        if self._preflight(): self.reply(204)
    def _auth_or_reply(self):
        if self._authorized(): return True
        self.reply(401, {"error": "Authorization: Bearer token required"}, {"WWW-Authenticate": 'Bearer realm="cyclone-live-phone"'}); return False
    def do_GET(self):
        if self._preflight() and self._auth_or_reply(): self.reply(405, {"error": "SSE stream not provided"}, {"Allow": "POST, GET, DELETE, OPTIONS"})
    def do_DELETE(self):
        if self._preflight() and self._auth_or_reply(): self.reply(405, {"error": "Stateless endpoint has no HTTP session to delete"})
    @staticmethod
    def _rpc_error(ident, code, message): return {"jsonrpc": "2.0", "id": ident, "error": {"code": code, "message": message}}
    def _process_message(self, message):
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0": return self._rpc_error(None, -32600, "Invalid Request")
        method = message.get("method")
        if method is None: self.server.bridge.mark_connected(); return None
        if not isinstance(method, str): return self._rpc_error(message.get("id"), -32600, "Invalid Request")
        has_id, ident = "id" in message, message.get("id")
        if has_id and (isinstance(ident, (dict, list, bool)) or len(str(ident)) > 128): return self._rpc_error(None, -32600, "Invalid Request")
        params = message.get("params", {})
        if not isinstance(params, dict): return self._rpc_error(ident if has_id else None, -32602, "Invalid params")
        if not has_id: self.server.bridge.mark_connected(); return None
        try: return {"jsonrpc": "2.0", "id": ident, "result": self.server.bridge.dispatch(method, params)}
        except MethodNotFound: return self._rpc_error(ident, -32601, "Method not found")
        except (InvalidParams, ValueError, TypeError, KeyError): return self._rpc_error(ident, -32602, "Invalid or unavailable Live Phone request; SEE again")
        except Exception: return self._rpc_error(ident, -32603, "Phone unavailable. Never replay an uncertain mutation; SEE again.")
    def do_POST(self):
        if not self._preflight() or not self._auth_or_reply(): return
        if not self._protocol_ok(): return self.reply(400, {"error": "unsupported MCP protocol version", "supported": list(SUPPORTED_PROTOCOLS)})
        if "application/json" not in self.headers.get("Content-Type", "").lower(): return self.reply(415, {"error": "application/json required"})
        if not self.server.slots.acquire(blocking=False): return self.reply(429, {"error": "Live Phone is busy"})
        try:
            if self.headers.get("Transfer-Encoding"): return self.reply(413, {"error": "bounded Content-Length required"})
            try: size = int(self.headers.get("Content-Length", "0"))
            except ValueError: return self.reply(400, {"error": "invalid Content-Length"})
            if not 0 < size <= 64 * 1024: return self.reply(413, {"error": "request too large"})
            try: request = json.loads(self.rfile.read(size))
            except (json.JSONDecodeError, UnicodeDecodeError): return self.reply(200, self._rpc_error(None, -32700, "Parse error"))
            if isinstance(request, list):
                if not request or any(isinstance(item, dict) and item.get("method") == "initialize" for item in request): return self.reply(200, self._rpc_error(None, -32600, "Invalid Request"))
                replies = [reply for reply in (self._process_message(item) for item in request) if reply is not None]
                return self.reply(200, replies) if replies else self.reply(202)
            response = self._process_message(request); return self.reply(200, response) if response is not None else self.reply(202)
        finally: self.server.slots.release()


def start_bridge(broker):
    server = Server(("127.0.0.1", PORT), Bridge(broker), cloud_key())
    threading.Thread(target=server.serve_forever, name="cyclone-live-phone-mcp", daemon=True).start()
    def heartbeat():
        while True:
            try: (root() / "broker-status.json").write_text(json.dumps({"at": int(time.time())}), encoding="utf-8")
            except OSError: pass
            time.sleep(3)
    threading.Thread(target=heartbeat, name="cyclone-live-health", daemon=True).start()
