"""API connectors (plan 34, M3): an OpenAPI 3 or Swagger 2 description becomes a connection whose tools are the API's
operations. Nothing is generated or run: the gateway reads the description once, keeps a small normalised copy, and
sends each HTTP request itself.

Rules:
- **Only the description's own servers.** Calls go to the base address the description names (or the one the owner
  typed), https only (plain http only to this PC), and never to a private network address. The host name is resolved
  once per call, every address is checked, and the connection is made to the checked address (no DNS rebinding).
  Redirects are not followed.
- **Classes come from the HTTP method.** GET and HEAD read; DELETE is sensitive (always asks); POST, PUT and PATCH
  change things, and are sensitive when their name sends, pays, deletes or grants. The owner can move a tool between
  *read* and *changes things*; a sensitive tool can never be lowered (``connections.settings``).
- **Keys stay sealed.** An API key, bearer token, basic sign-in or OAuth token comes from the gateway's grant store at
  call time and is sent only to the pinned host. It never enters the database, the description, a card or a result.
- **Bounded.** Descriptions up to 5 MB, 200 operations, answers up to 25 MB; YAML aliases are refused (no expansion
  bombs); ``$ref`` only inside the document, 20 levels deep.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import http.client
import ipaddress
import json
import re
import socket
import ssl
import urllib.parse
from typing import Any, Callable

from ..desktop_runtime.v5_contract import INLINE_SECRET
from . import mcp

MAX_SPEC = 5 * 1024 * 1024
MAX_ANSWER = 25 * 1024 * 1024
MAX_OPERATIONS = 200
MAX_INPUTS = 40
METHODS = ("get", "put", "post", "delete", "patch", "head")
READ_METHODS = ("GET", "HEAD")
USER_AGENT = "Cyclone-Command-Center/1"
#: Header parameters Cyclone sets itself (or never sends); a description cannot make them owner inputs.
RESERVED_HEADERS = {"authorization", "cookie", "host", "content-type", "content-length", "accept", "proxy-authorization",
                    "transfer-encoding", "connection", "user-agent", "x-forwarded-for"}
FILE_TYPES = ("video/", "image/", "audio/", "application/pdf", "text/csv")
NAME = re.compile(r"[^A-Za-z0-9_.-]+")


class SpecError(ValueError):
    """The description cannot be used; the message is for the owner."""


# ---------------------------------------------------------------------------------------------------------- parsing


def load_text(text: Any) -> dict[str, Any]:
    """A description as JSON or YAML. YAML is read with the safe loader and anchors/aliases are refused."""
    if not isinstance(text, str) or not text.strip():
        raise SpecError("Paste the API description (OpenAPI or Swagger, JSON or YAML).")
    if len(text.encode("utf-8", "replace")) > MAX_SPEC:
        raise SpecError("The API description is larger than 5 MB.")
    stripped = text.lstrip()
    value: Any = None
    if stripped.startswith(("{", "[")):
        try:
            value = json.loads(text)
        except (ValueError, RecursionError) as exc:
            raise SpecError("The API description is not valid JSON.") from exc
    else:
        try:
            import yaml  # PyYAML: a gateway dependency since alpha.58
        except ImportError as exc:  # pragma: no cover - packaging guard
            raise SpecError("This Cyclone cannot read YAML; paste the JSON version of the description.") from exc

        class _NoAliases(yaml.SafeLoader):
            def compose_node(self, parent: Any, index: Any) -> Any:  # noqa: D401 - PyYAML hook
                if self.check_event(yaml.AliasEvent):
                    raise SpecError("The description uses YAML aliases, which Cyclone does not expand.")
                return super().compose_node(parent, index)

        try:
            value = yaml.load(text, Loader=_NoAliases)  # noqa: S506 - a SafeLoader subclass
        except SpecError:
            raise
        except (yaml.YAMLError, RecursionError) as exc:
            raise SpecError("The API description is not valid YAML or JSON.") from exc
    if not isinstance(value, dict):
        raise SpecError("The API description is not an OpenAPI or Swagger document.")
    return value


def _pointer(doc: dict[str, Any], ref: str) -> Any:
    node: Any = doc
    for raw in ref[2:].split("/") if ref != "#" else []:
        part = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        else:
            return None
    return node


def _deref(doc: dict[str, Any], node: Any, seen: tuple[str, ...] = ()) -> Any:
    """Follow a local ``$ref`` (one hop chain, cycles and remote refs give an empty schema)."""
    while isinstance(node, dict) and isinstance(node.get("$ref"), str):
        ref = node["$ref"]
        if not ref.startswith("#") or ref in seen or len(seen) >= 20:
            return {}
        seen = (*seen, ref)
        node = _pointer(doc, ref)
    return node if node is not None else {}


def _schema(doc: dict[str, Any], node: Any, depth: int = 0) -> dict[str, Any]:
    """A small, self-contained JSON schema for one input: type, enum, description, default; objects and arrays keep
    their shape only a few levels deep (they are sent as JSON)."""
    node = _deref(doc, node)
    if not isinstance(node, dict) or depth > 6:
        return {}
    if isinstance(node.get("allOf"), list):
        merged: dict[str, Any] = {"type": "object", "properties": {}, "required": []}
        for part in node["allOf"][:10]:
            sub = _schema(doc, part, depth + 1)
            merged["properties"].update(sub.get("properties") or {})
            merged["required"] += [r for r in sub.get("required") or [] if isinstance(r, str)]
            for k in ("description", "enum", "default"):
                if k in sub:
                    merged.setdefault(k, sub[k])
            if sub.get("type") and sub.get("type") != "object" and not sub.get("properties"):
                merged["type"] = sub["type"]
        node = {**{k: v for k, v in node.items() if k != "allOf"}, **merged}
    kind = node.get("type")
    if isinstance(kind, list):
        kind = next((k for k in kind if k != "null"), None)
    if kind is None:
        if isinstance(node.get("properties"), dict):
            kind = "object"
        elif "items" in node:
            kind = "array"
        elif isinstance(node.get("oneOf") or node.get("anyOf"), list):
            kind = "object"
    out: dict[str, Any] = {}
    if kind in ("string", "integer", "number", "boolean", "object", "array"):
        out["type"] = kind
    if isinstance(node.get("description"), str):
        out["description"] = _text(node["description"], 300)
    if isinstance(node.get("enum"), list):
        out["enum"] = [e for e in node["enum"] if isinstance(e, (str, int, float)) and not isinstance(e, bool)][:40]
    if isinstance(node.get("default"), (str, int, float, bool)):
        out["default"] = node["default"]
    if kind == "object" and isinstance(node.get("properties"), dict) and depth < 3:
        out["properties"] = {str(k)[:64]: _schema(doc, v, depth + 1) for k, v in list(node["properties"].items())[:60]}
        if isinstance(node.get("required"), list):
            out["required"] = [r for r in node["required"] if isinstance(r, str)][:60]
    if kind == "array" and depth < 3:
        out["items"] = _schema(doc, node.get("items"), depth + 1)
    return out


def _text(value: Any, limit: int) -> str:
    """Descriptions are shown to the owner; secret-shaped examples in them are hidden."""
    text = str(value or "").strip()
    return hide_secrets(text)[:limit]


def hide_secrets(text: str) -> str:
    """``password: hunter2`` becomes ``[hidden]``: the label and the value after it."""
    return re.sub(INLINE_SECRET.pattern + r"\s*[\"']?[^\s,;\"'}]*", "[hidden]", text)


def _servers(doc: dict[str, Any], node: Any, source_url: str | None) -> list[str]:
    out: list[str] = []
    for server in node if isinstance(node, list) else []:
        if not isinstance(server, dict) or not isinstance(server.get("url"), str):
            continue
        url = server["url"]
        variables = server.get("variables") if isinstance(server.get("variables"), dict) else {}
        for name, spec in variables.items():
            default = spec.get("default") if isinstance(spec, dict) else None
            url = url.replace("{" + str(name) + "}", str(default if default is not None else ""))
        if "{" in url:
            continue
        if not urllib.parse.urlsplit(url).scheme:
            if not source_url:
                continue
            url = urllib.parse.urljoin(source_url, url)
        out.append(url)
    return out


def _base(url: str) -> str:
    try:
        checked = mcp.check_url(url, what="API address")
    except mcp.McpError as exc:
        raise SpecError(str(exc)) from exc
    parts = urllib.parse.urlsplit(checked)
    if parts.query:
        raise SpecError("The API address has a query; give the base address only.")
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path.rstrip("/"), "", ""))


def _schemes(doc: dict[str, Any], swagger: bool) -> dict[str, dict[str, Any]]:
    raw = doc.get("securityDefinitions") if swagger else (doc.get("components") or {}).get("securitySchemes") if isinstance(doc.get("components"), dict) else None
    out: dict[str, dict[str, Any]] = {}
    for name, node in list(raw.items())[:20] if isinstance(raw, dict) else []:
        node = _deref(doc, node)
        if not isinstance(node, dict):
            continue
        kind = str(node.get("type", "")).lower()
        entry: dict[str, Any] = {"type": "unsupported", "why": f"{kind or 'unknown'} sign-in"}
        if kind == "apikey":
            where, header = node.get("in"), node.get("name")
            if where in ("header", "query") and isinstance(header, str) and re.match(r"^[A-Za-z][A-Za-z0-9_.-]{0,63}$", header):
                if where == "header" and header.lower() in ("host", "cookie", "content-type", "content-length"):
                    entry = {"type": "unsupported", "why": "a key in a reserved header"}
                else:
                    entry = {"type": where, "name": header}
            else:
                entry = {"type": "unsupported", "why": "an API key in a cookie"}
        elif kind == "http" or (swagger and kind == "basic"):
            scheme = "basic" if swagger else str(node.get("scheme", "")).lower()
            if scheme in ("bearer", "basic"):
                entry = {"type": scheme, "name": "Authorization"}
        elif kind == "oauth2":
            if swagger:
                flow = node if node.get("flow") == "accessCode" else None
                scopes = node.get("scopes")
            else:
                flows = node.get("flows") if isinstance(node.get("flows"), dict) else {}
                flow = flows.get("authorizationCode") if isinstance(flows.get("authorizationCode"), dict) else None
                scopes = (flow or {}).get("scopes")
            if flow and isinstance(flow.get("authorizationUrl"), str) and isinstance(flow.get("tokenUrl"), str):
                try:
                    entry = {"type": "oauth2", "authorizationUrl": mcp.check_url(flow["authorizationUrl"], what="sign-in page"),
                             "tokenUrl": mcp.check_url(flow["tokenUrl"], what="token address"),
                             "scopes": sorted(str(s)[:200] for s in (scopes or {}) if isinstance(s, str))[:40]}
                except mcp.McpError:
                    entry = {"type": "unsupported", "why": "a sign-in address that is not https"}
            else:
                entry = {"type": "unsupported", "why": "an OAuth flow other than sign-in in the browser"}
        out[str(name)[:64]] = entry
    return out


def _requirement(value: Any) -> list[dict[str, list[str]]] | None:
    if not isinstance(value, list):
        return None
    out = []
    for item in value[:10]:
        if isinstance(item, dict):
            out.append({str(k): [s for s in v if isinstance(s, str)][:40] if isinstance(v, list) else [] for k, v in item.items()})
    return out


def normalize(doc: dict[str, Any], *, source_url: str | None = None, base_url: str | None = None) -> dict[str, Any]:
    """The description, reduced to what Cyclone needs: servers, sign-in, and each operation's inputs."""
    swagger = str(doc.get("swagger", "")).startswith("2")
    if not swagger and not str(doc.get("openapi", "")).startswith("3"):
        raise SpecError("This is not an OpenAPI 3 or Swagger 2 description.")
    info = doc.get("info") if isinstance(doc.get("info"), dict) else {}
    if swagger:
        host, base_path = doc.get("host"), doc.get("basePath") or ""
        schemes = [s for s in doc.get("schemes") or [] if s in ("https", "http")] or ["https"]
        servers: list[str] = []
        if isinstance(host, str) and host:
            servers = [f"{'https' if 'https' in schemes else schemes[0]}://{host}{base_path}"]
        elif source_url:
            parts = urllib.parse.urlsplit(source_url)
            servers = [f"{parts.scheme}://{parts.netloc}{base_path}"]
    else:
        servers = _servers(doc, doc.get("servers"), source_url)
        if not servers and source_url and not doc.get("servers"):
            parts = urllib.parse.urlsplit(source_url)
            servers = [f"{parts.scheme}://{parts.netloc}"]
    bases = []
    for url in ([base_url] if base_url else []) + servers:
        try:
            base = _base(url)
        except SpecError:
            if url == base_url:
                raise
            continue
        if base not in bases:
            bases.append(base)
    if not bases:
        raise SpecError("The description does not say where the API is (an https address). Add the API's address.")
    security_schemes = _schemes(doc, swagger)
    global_security = _requirement(doc.get("security"))
    operations: list[dict[str, Any]] = []
    skipped: list[str] = []
    names: set[str] = set()
    paths = doc.get("paths") if isinstance(doc.get("paths"), dict) else {}
    for path, item in paths.items():
        if not isinstance(path, str) or not path.startswith("/") or not isinstance(item, dict):
            continue
        item = _deref(doc, item)
        shared = item.get("parameters") if isinstance(item.get("parameters"), list) else []
        for method in METHODS:
            op = item.get(method)
            if not isinstance(op, dict):
                continue
            if len(operations) >= MAX_OPERATIONS:
                skipped.append(f"{method.upper()} {path}: more than {MAX_OPERATIONS} operations")
                continue
            try:
                operations.append(_operation(doc, swagger, path, method, op, shared, names, global_security))
            except SpecError as exc:
                skipped.append(f"{method.upper()} {path}: {exc}")
    if not operations:
        raise SpecError("The description has no operations Cyclone can call." + (f" ({skipped[0]})" if skipped else ""))
    required = [r for op in operations for r in op["security"]]
    scheme_name = next((name for req in required for name in req if security_schemes.get(name, {}).get("type") not in (None, "unsupported")), None)
    unsupported = next((security_schemes[name]["why"] for req in required for name in req
                        if security_schemes.get(name, {}).get("type") == "unsupported"), None)
    scheme = None
    if scheme_name:
        scheme = {"id": scheme_name, **security_schemes[scheme_name]}
        if scheme["type"] == "oauth2":
            wanted = sorted({s for op in operations for req in op["security"] for s in req.get(scheme_name, [])})
            scheme["scopes"] = wanted[:40]
    for op in operations:
        op["auth"] = bool(scheme and any(scheme["id"] in req for req in op["security"]))
        del op["security"]
    api = {
        "title": _text(info.get("title") or urllib.parse.urlsplit(bases[0]).hostname or "API", 60) or "API",
        "version": _text(info.get("version"), 40),
        "base": bases[0], "servers": bases[:5],
        "sourceUrl": source_url,
        "scheme": scheme,
        "unsupportedSignIn": unsupported if not scheme and unsupported else None,
        "operations": operations,
        "skipped": skipped[:20],
    }
    api["hash"] = hashlib.sha256(json.dumps({k: api[k] for k in ("base", "scheme", "operations")}, sort_keys=True).encode()).hexdigest()
    return api


def _operation(doc: dict[str, Any], swagger: bool, path: str, method: str, op: dict[str, Any], shared: list[Any],
               names: set[str], global_security: list[dict[str, list[str]]] | None) -> dict[str, Any]:
    raw_name = op.get("operationId") if isinstance(op.get("operationId"), str) else f"{method}_{path}"
    name = NAME.sub("_", raw_name).strip("_.-")[:100] or f"{method}_op"
    base, n = name, 2
    while name in names:
        name, n = f"{base}_{n}", n + 1
    names.add(name)
    params: dict[tuple[str, str], dict[str, Any]] = {}
    for raw in [*shared, *(op.get("parameters") if isinstance(op.get("parameters"), list) else [])]:
        p = _deref(doc, raw)
        if isinstance(p, dict) and isinstance(p.get("name"), str) and p.get("in") in ("path", "query", "header", "body", "formData", "cookie"):
            params[(p["name"], p["in"])] = p
    inputs: list[dict[str, Any]] = []
    body: dict[str, Any] | None = None
    for (pname, where), p in params.items():
        if where == "cookie":
            if p.get("required"):
                raise SpecError("it needs a cookie")
            continue
        if where == "header" and pname.lower() in RESERVED_HEADERS:
            continue
        if where == "body":  # Swagger 2
            body = {"type": "json", "schema": _schema(doc, p.get("schema")), "required": bool(p.get("required"))}
            continue
        if where == "formData":
            if p.get("type") == "file":
                raise SpecError("it uploads a file")
            body = body if body and body["type"] == "form" else {"type": "form", "schema": {"type": "object", "properties": {}, "required": []}, "required": False}
            body["schema"]["properties"][pname] = _schema(doc, p)
            if p.get("required"):
                body["schema"]["required"].append(pname)
                body["required"] = True
            continue
        schema = _schema(doc, p if swagger else p.get("schema") or {})
        if isinstance(p.get("description"), str):
            schema["description"] = _text(p["description"], 300)
        if schema.get("type") == "object" and where != "body":
            raise SpecError("it takes an object in the address")
        inputs.append({"arg": pname, "name": pname, "in": where, "required": bool(p.get("required")) or where == "path", "schema": schema})
    if not swagger and op.get("requestBody") is not None:
        rb = _deref(doc, op["requestBody"])
        content = rb.get("content") if isinstance(rb, dict) and isinstance(rb.get("content"), dict) else {}
        kind = next((k for k in content if k.split(";")[0].strip() == "application/json"), None) \
            or next((k for k in content if k.split(";")[0].strip().endswith("+json")), None)
        form = next((k for k in content if k.split(";")[0].strip() == "application/x-www-form-urlencoded"), None)
        if kind:
            body = {"type": "json", "schema": _schema(doc, (content[kind] or {}).get("schema")), "required": bool(rb.get("required"))}
        elif form:
            body = {"type": "form", "schema": _schema(doc, (content[form] or {}).get("schema")), "required": bool(rb.get("required"))}
        elif rb.get("required"):
            raise SpecError("it sends a file or a body type Cyclone does not support")
    if body is not None:
        schema = body["schema"]
        taken = {i["arg"] for i in inputs}
        if schema.get("type") == "object" and isinstance(schema.get("properties"), dict):
            required = set(schema.get("required") or [])
            for prop, sub in schema["properties"].items():
                arg = prop if prop not in taken else f"body_{prop}"
                inputs.append({"arg": arg, "name": prop, "in": "body", "required": body["required"] and prop in required, "schema": sub})
                taken.add(arg)
        else:
            arg = "body" if "body" not in taken else "request_body"
            inputs.append({"arg": arg, "name": "", "in": "body", "required": body["required"], "schema": schema or {"type": "object"}})
        body = {"type": body["type"], "required": body["required"],
                "whole": not (schema.get("type") == "object" and isinstance(schema.get("properties"), dict))}
    if len(inputs) > MAX_INPUTS:
        raise SpecError(f"it has more than {MAX_INPUTS} inputs")
    # An operation's own list replaces the global one; an empty requirement ({}) means signing in is optional.
    own = _requirement(op.get("security"))
    security = [req for req in (own if own is not None else global_security or []) if req]
    return {
        "name": name, "method": method.upper(), "path": path[:300],
        "summary": _text(op.get("summary"), 120), "description": _text(op.get("description") or op.get("summary"), 400),
        "inputs": inputs, "body": body, "security": security,
    }


def tools(api: dict[str, Any]) -> list[dict[str, Any]]:
    """The operations as MCP-shaped tools, so rules, pinning, results and artifacts apply unchanged."""
    out = []
    for op in api["operations"]:
        properties = {i["arg"]: i["schema"] for i in op["inputs"]}
        required = [i["arg"] for i in op["inputs"] if i["required"]]
        schema: dict[str, Any] = {"type": "object", "properties": properties}
        if required:
            schema["required"] = required
        annotations = {"readOnlyHint": op["method"] in READ_METHODS, "destructiveHint": op["method"] == "DELETE"}
        description = f"{op['method']} {op['path']}" + (f": {op['description']}" if op["description"] else "")
        out.append({"name": op["name"], "title": op["summary"][:80], "description": description[:400], "inputSchema": schema,
                    "annotations": annotations})
    return out


def classify(op: dict[str, Any]) -> str:
    """read, change or sensitive from the HTTP method (the description's words can only raise the risk)."""
    from .connections import SENSITIVE_WORDS, _words
    words = _words(op["name"])
    if op["method"] == "DELETE":
        return "sensitive"
    if op["method"] in READ_METHODS:
        return "sensitive" if words and words[0] in SENSITIVE_WORDS else "read"
    return "sensitive" if SENSITIVE_WORDS & set(words) else "change"


def to_openapi(api: dict[str, Any]) -> dict[str, Any]:
    """A minimal OpenAPI 3 document for [api]; a card carries this, and importing it runs through [normalize] again,
    so a card is checked exactly like a pasted description."""
    doc: dict[str, Any] = {"openapi": "3.0.3", "info": {"title": api["title"], "version": api["version"] or "1"},
                           "servers": [{"url": u} for u in api["servers"]], "paths": {}}
    scheme = api.get("scheme")
    if scheme:
        kind = scheme["type"]
        node: dict[str, Any]
        if kind in ("header", "query"):
            node = {"type": "apiKey", "in": kind, "name": scheme["name"]}
        elif kind in ("bearer", "basic"):
            node = {"type": "http", "scheme": kind}
        else:
            node = {"type": "oauth2", "flows": {"authorizationCode": {"authorizationUrl": scheme["authorizationUrl"], "tokenUrl": scheme["tokenUrl"],
                                                                       "scopes": {s: "" for s in scheme.get("scopes") or []}}}}
        doc["components"] = {"securitySchemes": {scheme["id"]: node}}
    for op in api["operations"]:
        entry: dict[str, Any] = {"operationId": op["name"]}
        if op["summary"]:
            entry["summary"] = op["summary"]
        if op["description"] and op["description"] != op["summary"]:
            entry["description"] = op["description"]
        entry["parameters"] = [{"name": i["name"], "in": i["in"], "required": i["required"], "schema": {k: v for k, v in i["schema"].items() if k != "description"},
                                **({"description": i["schema"]["description"]} if i["schema"].get("description") else {})}
                               for i in op["inputs"] if i["in"] != "body"]
        body_inputs = [i for i in op["inputs"] if i["in"] == "body"]
        if op.get("body"):
            if op["body"]["whole"]:
                schema = body_inputs[0]["schema"] if body_inputs else {"type": "object"}
                required = bool(body_inputs and body_inputs[0]["required"])
            else:
                schema = {"type": "object", "properties": {i["name"]: i["schema"] for i in body_inputs}}
                req = [i["name"] for i in body_inputs if i["required"]]
                if req:
                    schema["required"] = req
                required = bool(req)
            media = "application/json" if op["body"]["type"] == "json" else "application/x-www-form-urlencoded"
            entry["requestBody"] = {"required": required, "content": {media: {"schema": schema}}}
        entry["security"] = [{scheme["id"]: list(scheme.get("scopes") or []) if scheme["type"] == "oauth2" else []}] if (op["auth"] and scheme) else []
        doc["paths"].setdefault(op["path"], {})[op["method"].lower()] = entry
    return doc


# ---------------------------------------------------------------------------------------------------------- calls


class _PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host: str, port: int, address: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout, context=ssl.create_default_context())
        self._address = address

    def connect(self) -> None:
        sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)  # type: ignore[attr-defined]


class _PinnedHTTP(http.client.HTTPConnection):
    def __init__(self, host: str, port: int, address: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address

    def connect(self) -> None:
        self.sock = socket.create_connection((self._address, self.port), self.timeout)


def _public(address: str) -> bool:
    ip = ipaddress.ip_address(address.split("%")[0])
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified)


def resolve(url: str, *, allow_loopback: bool) -> str:
    """The one address a request to [url] may use: every address the name resolves to must be public (or this PC,
    when the owner pointed the connection at this PC)."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    port = parts.port or (443 if parts.scheme == "https" else 80)
    if host in mcp.LOOPBACK:
        if not allow_loopback:
            raise mcp.McpError("That address is on this PC; Cyclone does not call it for this connection.")
        return "127.0.0.1" if host != "::1" else "::1"
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise mcp.McpError("The API's address could not be found.") from exc
    addresses = [info[4][0] for info in infos]
    if not addresses or not all(_public(a) for a in addresses):
        raise mcp.McpError("The API's address is on a private network; Cyclone does not call it.")
    return addresses[0]


def request(method: str, url: str, *, headers: dict[str, str], body: bytes | None = None, timeout: float = 60.0,
            allow_loopback: bool = False, limit: int = MAX_ANSWER) -> mcp.Response:
    """One HTTP request to a checked, pinned address. Redirects are returned, not followed."""
    url = mcp.check_url(url, what="API address")
    parts = urllib.parse.urlsplit(url)
    address = resolve(url, allow_loopback=allow_loopback)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    kind = _PinnedHTTPS if parts.scheme == "https" else _PinnedHTTP
    connection = kind(parts.hostname or "", port, address, timeout)
    target = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    try:
        connection.request(method, target, body=body, headers={"User-Agent": USER_AGENT, **headers})
        response = connection.getresponse()
        chunks, size = [], 0
        while True:
            chunk = response.read(256 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > limit:
                raise mcp.McpError("The API's answer is too large.")
            chunks.append(chunk)
        return mcp.Response(response.status, {k.lower(): v for k, v in response.getheaders()}, b"".join(chunks))
    except ssl.SSLError as exc:
        raise mcp.McpError("The API's https certificate could not be verified.") from exc
    except (OSError, http.client.HTTPException) as exc:
        raise mcp.McpError("The API could not be reached.") from exc
    finally:
        connection.close()


def fetch_spec(url: Any, *, send: Callable[..., mcp.Response] = request) -> tuple[str, str]:
    """Download a description (https, public, up to three re-checked redirects). Returns (final url, text)."""
    current = mcp.check_url(url, what="description address") if isinstance(url, str) else None
    if current is None:
        raise SpecError("The description address is not valid.")
    loopback = urllib.parse.urlsplit(current).hostname in mcp.LOOPBACK
    for _ in range(4):
        try:
            response = send("GET", current, headers={"Accept": "application/json, application/yaml, text/yaml, */*"},
                            timeout=30, allow_loopback=loopback, limit=MAX_SPEC)
        except mcp.McpError as exc:
            raise SpecError(str(exc)) from exc
        if response.status in (301, 302, 303, 307, 308) and response.headers.get("location"):
            current = mcp.check_url(urllib.parse.urljoin(current, response.headers["location"]), what="description address")
            if urllib.parse.urlsplit(current).hostname in mcp.LOOPBACK and not loopback:
                raise SpecError("The description address redirected to this PC.")
            continue
        if response.status != 200:
            raise SpecError(f"The description could not be downloaded ({response.status}).")
        return current, response.body.decode("utf-8", "replace")
    raise SpecError("The description address redirected too often.")


class ApiSession:
    """The Session shape ``ConnectionStore`` uses (list_tools, call_tool, close) over plain HTTP calls to one API."""

    def __init__(self, api: dict[str, Any], *, credential: Callable[[], dict[str, Any] | None],
                 send: Callable[..., mcp.Response] = request) -> None:
        self.api = api
        self._credential = credential
        self._send = send
        self._ops = {op["name"]: op for op in api["operations"]}
        self._loopback = urllib.parse.urlsplit(api["base"]).hostname in mcp.LOOPBACK

    def list_tools(self) -> list[dict[str, Any]]:
        return tools(self.api)

    def close(self) -> None:
        """Nothing is held open between calls."""

    def call_tool(self, name: str, arguments: dict[str, Any], *, timeout: float = 60.0) -> dict[str, Any]:
        op = self._ops.get(name)
        if op is None:
            raise mcp.McpError(f"The API has no operation {name}.")
        url, headers, body = self.build(op, arguments)
        if op["auth"]:
            url, headers = self._sign(url, headers)
        response = self._send(op["method"], url, headers=headers, body=body, timeout=min(timeout, 120.0), allow_loopback=self._loopback)
        return self._result(op, url, response)

    # ------------------------------------------------------------------ request

    def build(self, op: dict[str, Any], arguments: dict[str, Any]) -> tuple[str, dict[str, str], bytes | None]:
        known = {i["arg"]: i for i in op["inputs"]}
        unknown = sorted(set(arguments) - set(known))
        if unknown:
            raise mcp.McpError(f"{op['name']} has no input {unknown[0]}.")
        path = op["path"]
        query: list[tuple[str, str]] = []
        headers: dict[str, str] = {"Accept": "application/json, */*;q=0.8"}
        fields: dict[str, Any] = {}
        whole: Any = None
        for arg, spec in known.items():
            if arg not in arguments or arguments[arg] is None or arguments[arg] == "":
                if spec["required"]:
                    raise mcp.McpError(f"{op['name']} needs {arg}.")
                continue
            value = _typed(arg, spec["schema"], arguments[arg])
            where = spec["in"]
            if where == "path":
                text = _scalar(arg, value)
                if text in (".", "..") or not text:
                    raise mcp.McpError(f"{arg} is not a valid value.")
                path = path.replace("{" + spec["name"] + "}", urllib.parse.quote(text, safe=""))
            elif where == "query":
                for item in value if isinstance(value, list) else [value]:
                    query.append((spec["name"], _scalar(arg, item)))
            elif where == "header":
                text = _scalar(arg, value)
                if any(c in text for c in "\r\n\0") or len(text) > 1000:
                    raise mcp.McpError(f"{arg} is one line of text.")
                headers[spec["name"]] = text
            elif spec["name"]:
                fields[spec["name"]] = value
            else:
                whole = value
        if re.search(r"\{[^}]+\}", path):
            raise mcp.McpError(f"{op['name']} is missing a value in its address.")
        body: bytes | None = None
        if op.get("body") and op["method"] not in READ_METHODS:
            payload = whole if op["body"]["whole"] else (fields if fields or op["body"]["required"] else None)
            if payload is not None:
                if op["body"]["type"] == "json":
                    body = json.dumps(payload).encode()
                    headers["Content-Type"] = "application/json"
                else:
                    if not isinstance(payload, dict):
                        raise mcp.McpError("A form takes named fields.")
                    body = urllib.parse.urlencode([(k, _scalar(k, v)) for k, v in payload.items()]).encode()
                    headers["Content-Type"] = "application/x-www-form-urlencoded"
        url = self.api["base"] + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        if not url.startswith(self.api["base"] + "/") and url != self.api["base"]:
            raise mcp.McpError("That call would leave the API's address.")
        return url, headers, body

    def _sign(self, url: str, headers: dict[str, str]) -> tuple[str, dict[str, str]]:
        grant = self._credential()
        if not grant:
            raise mcp.NeedsSignIn(None)
        if grant.get("query"):
            joiner = "&" if "?" in url else "?"
            return url + joiner + urllib.parse.urlencode({grant["query"]: grant["value"]}), headers
        if grant.get("header"):
            return url, {**headers, grant["header"]: grant["value"]}
        if grant.get("token"):
            return url, {**headers, "Authorization": f"Bearer {grant['token']}"}
        raise mcp.NeedsSignIn(None)

    # ------------------------------------------------------------------ answer

    def _result(self, op: dict[str, Any], url: str, response: mcp.Response) -> dict[str, Any]:
        status = response.status
        kind = (response.headers.get("content-type") or "").split(";")[0].strip().lower()
        if status == 401 and op["auth"]:
            raise mcp.NeedsSignIn(None)
        if 300 <= status < 400:
            return {"isError": True, "content": [{"type": "text", "text": f"The API answered {status} (a redirect); Cyclone does not follow redirects for API calls."}]}
        if status >= 400:
            snippet = hide_secrets(response.body[:600].decode("utf-8", "replace")).strip()
            words = {401: "it wants a sign-in", 403: "it refused (check the key's permissions)", 404: "not found",
                     429: "too many requests; try again later"}.get(status, "")
            text = f"The API answered {status}" + (f" ({words})" if words else "") + (f": {snippet}" if snippet else ".")
            return {"isError": True, "content": [{"type": "text", "text": text[:800]}]}
        if op["method"] == "HEAD" or not response.body:
            return {"content": [{"type": "text", "text": f"Done ({status})."}], "structuredContent": {"status": status}}
        if kind == "application/json" or kind.endswith("+json"):
            try:
                value = json.loads(response.body)
            except (ValueError, RecursionError):
                value = None
            if isinstance(value, (dict, list)):
                return {"content": [{"type": "text", "text": json.dumps(value)[:4000]}], "structuredContent": value if isinstance(value, dict) else {"items": value}}
        if kind.startswith(FILE_TYPES):
            name = urllib.parse.urlsplit(url).path.rsplit("/", 1)[-1] or op["name"]
            return {"content": [{"type": "resource", "resource": {"uri": f"api:{name}", "mimeType": kind,
                                                                   "blob": base64.b64encode(response.body).decode()}},
                                {"type": "text", "text": f"A file came back ({kind}, {len(response.body)} bytes)."}]}
        if kind.startswith("text/") or kind in ("application/xml",) or not kind:
            return {"content": [{"type": "text", "text": response.body[:32_000].decode("utf-8", "replace")}]}
        return {"content": [{"type": "text", "text": f"The API answered with {kind or 'data'} ({len(response.body)} bytes), which Cyclone does not keep."}]}


def _typed(arg: str, schema: dict[str, Any], value: Any) -> Any:
    """An argument in its schema's type. Text from a form or a step becomes a number or true/false when the input
    wants one; objects and arrays may come as JSON text."""
    kind = schema.get("type")
    if kind in ("integer", "number"):
        if isinstance(value, str):
            try:
                value = int(value) if kind == "integer" and re.match(r"^-?\d+$", value.strip()) else float(value)
            except ValueError as exc:
                raise mcp.McpError(f"{arg} must be a number.") from exc
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise mcp.McpError(f"{arg} must be a number.")
        if kind == "integer":
            if float(value) != int(value):
                raise mcp.McpError(f"{arg} must be a whole number.")
            value = int(value)
    elif kind == "boolean":
        if isinstance(value, str) and value.strip().lower() in ("true", "false"):
            value = value.strip().lower() == "true"
        if not isinstance(value, bool):
            raise mcp.McpError(f"{arg} is true or false.")
    elif kind in ("object", "array"):
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except (ValueError, RecursionError) as exc:
                if kind == "array":
                    value = [v.strip() for v in value.split(",") if v.strip()]
                else:
                    raise mcp.McpError(f"{arg} is JSON.") from exc
        if kind == "object" and not isinstance(value, dict) or kind == "array" and not isinstance(value, list):
            raise mcp.McpError(f"{arg} must be {'an object' if kind == 'object' else 'a list'}.")
    elif kind == "string" and isinstance(value, (int, float)) and not isinstance(value, bool):
        value = str(value)
    if isinstance(schema.get("enum"), list) and schema["enum"] and not isinstance(value, (list, dict)) and value not in schema["enum"]:
        raise mcp.McpError(f"{arg} is one of: {', '.join(str(e) for e in schema['enum'][:10])}.")
    return value


def _scalar(arg: str, value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, str)):
        return str(value)
    if isinstance(value, list) and all(isinstance(v, (str, int, float)) and not isinstance(v, bool) for v in value):
        return ",".join(str(v) for v in value)
    raise mcp.McpError(f"{arg} must be plain text or a number.")


def public(api: dict[str, Any] | None) -> dict[str, Any] | None:
    """What Glass shows about an API connection (no request details beyond the base address)."""
    if not api:
        return None
    scheme = api.get("scheme")
    return {"title": api["title"], "version": api["version"], "base": api["base"], "sourceUrl": api.get("sourceUrl"),
            "operations": len(api["operations"]), "skipped": list(api.get("skipped") or []),
            "unsupportedSignIn": api.get("unsupportedSignIn"),
            "scheme": None if not scheme else {"type": scheme["type"], "name": scheme.get("name"), "scopes": list(scheme.get("scopes") or []),
                                               "authorizationUrl": scheme.get("authorizationUrl")}}


def clone(api: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(api)
