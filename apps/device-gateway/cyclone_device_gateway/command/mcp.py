"""A small MCP client for the Command Center's connections (plan 33, C3): the Streamable HTTP transport (JSON-RPC over
POST, answered as JSON or as a server-sent event stream) and the OAuth 2.1 sign-in MCP servers use (protected resource
metadata, authorization server metadata, dynamic client registration, PKCE S256, resource indicators, refresh).

Only fixed JSON-RPC methods are sent: ``initialize``, ``notifications/initialized``, ``tools/list`` and
``tools/call``. The model never talks to a server through this module; the Command Center does, under the owner's
allowlist, caps and approvals (``connections.py``).

URLs: https, or plain http to this PC (a local server, and the tests). Tokens are passed in, never logged or stored
here.
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import re
import secrets
import socket
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable

PROTOCOL_VERSION = "2025-06-18"
CLIENT_INFO = {"name": "Cyclone Command Center", "version": "1"}
MAX_RESPONSE = 8 * 1024 * 1024
LOOPBACK = {"127.0.0.1", "localhost", "::1"}


class McpError(RuntimeError):
    """A connection problem, worded for the owner. [status] is the HTTP status when there was one."""

    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


class NeedsSignIn(McpError):
    def __init__(self, resource_metadata: str | None = None) -> None:
        super().__init__("This connection needs you to sign in.")
        self.resource_metadata = resource_metadata


def check_url(url: Any, *, what: str = "URL") -> str:
    """https anywhere, or http only to this PC."""
    if not isinstance(url, str) or len(url) > 500:
        raise McpError(f"The {what} is not valid.")
    parts = urllib.parse.urlsplit(url.strip())
    if parts.scheme not in ("https", "http") or not parts.hostname or parts.username or parts.password:
        raise McpError(f"The {what} must be an https address.")
    if parts.scheme == "http" and parts.hostname not in LOOPBACK:
        raise McpError(f"The {what} must be https (plain http only to this PC).")
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path or "/", parts.query, ""))


def public_host(url: str, *, allow_loopback: bool) -> bool:
    """False for addresses on this PC's networks (so a server cannot make Cyclone fetch from inside the LAN)."""
    host = urllib.parse.urlsplit(url).hostname or ""
    if host in LOOPBACK:
        return allow_loopback
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        return False
    for info in infos:
        address = ipaddress.ip_address(info[4][0])
        if address.is_private or address.is_loopback or address.is_link_local or address.is_reserved or address.is_multicast:
            return False
    return True


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: Any, **kwargs: Any) -> None:  # noqa: D401 - redirects are refused, not followed
        return None


_OPENER = urllib.request.build_opener(_NoRedirect())


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    body: bytes


def http(method: str, url: str, *, headers: dict[str, str] | None = None, body: bytes | None = None,
         timeout: float = 30.0, stop: Callable[[bytes], bool] | None = None) -> Response:
    """One request. Redirects are not followed. With [stop], an event stream is read only until stop(body) is true."""
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        response = _OPENER.open(request, timeout=timeout)  # noqa: S310 - check_url limits schemes and hosts
    except urllib.error.HTTPError as exc:
        data = exc.read(64 * 1024) if exc.fp else b""
        return Response(exc.code, {k.lower(): v for k, v in exc.headers.items()}, data)
    except (urllib.error.URLError, OSError) as exc:
        raise McpError("The server could not be reached.") from exc
    with response:
        head = {k.lower(): v for k, v in response.headers.items()}
        chunks: list[bytes] = []
        size = 0
        while True:
            line = response.readline(64 * 1024) if stop else response.read(64 * 1024)
            if not line:
                break
            chunks.append(line)
            size += len(line)
            if size > MAX_RESPONSE:
                raise McpError("The server's answer is too large.")
            if stop and line in (b"\n", b"\r\n") and stop(b"".join(chunks)):
                break
        return Response(response.status, head, b"".join(chunks))


def _sse_messages(raw: bytes) -> list[dict[str, Any]]:
    out = []
    for event in re.split(rb"\r?\n\r?\n", raw):
        data = b"\n".join(line[5:].lstrip() for line in event.splitlines() if line.startswith(b"data:"))
        if data:
            try:
                value = json.loads(data)
            except ValueError:
                continue
            if isinstance(value, dict):
                out.append(value)
    return out


def _pick(messages: list[Any], wanted: Any) -> dict[str, Any] | None:
    """The result for request [wanted] among [messages], or None; a JSON-RPC error is raised in the owner's words."""
    for m in messages:
        if isinstance(m, dict) and m.get("id") == wanted and ("result" in m or "error" in m):
            if "error" in m:
                error = m["error"] if isinstance(m["error"], dict) else {}
                raise McpError(f"The server refused: {str(error.get('message', 'error'))[:200]}")
            result = m.get("result")
            if not isinstance(result, dict):
                raise McpError("The server's answer is malformed.")
            return result
    return None


class Session:
    """The four MCP messages Cyclone sends, over any transport. A transport implements [_post]: send one JSON-RPC
    message and return the result for its id (or None for a notification)."""

    _next: int = 0
    _ready: bool = False
    server: dict[str, Any]

    def _post(self, message: dict[str, Any], *, timeout: float) -> dict[str, Any] | None:  # pragma: no cover - abstract
        raise NotImplementedError

    def request(self, method: str, params: dict[str, Any] | None = None, *, timeout: float = 60.0) -> dict[str, Any]:
        if not self._ready and method != "initialize":
            self.initialize()
        self._next += 1
        message: dict[str, Any] = {"jsonrpc": "2.0", "id": self._next, "method": method}
        if params is not None:
            message["params"] = params
        result = self._post(message, timeout=timeout)
        assert result is not None
        return result

    def _reset(self) -> None:
        """Forget transport state before a new initialize."""

    def initialize(self) -> dict[str, Any]:
        self._reset()
        result = self.request("initialize", {"protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": CLIENT_INFO}, timeout=30)
        self.server = result.get("serverInfo") if isinstance(result.get("serverInfo"), dict) else {}
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, timeout=15)
        self._ready = True
        return result

    def list_tools(self) -> list[dict[str, Any]]:
        tools: list[dict[str, Any]] = []
        cursor = None
        for _ in range(10):
            result = self.request("tools/list", {"cursor": cursor} if cursor else {}, timeout=30)
            listed = result.get("tools")
            if isinstance(listed, list):
                tools.extend(t for t in listed if isinstance(t, dict) and isinstance(t.get("name"), str))
            cursor = result.get("nextCursor")
            if not isinstance(cursor, str) or not cursor:
                break
        return tools

    def call_tool(self, name: str, arguments: dict[str, Any], *, timeout: float = 300.0) -> dict[str, Any]:
        return self.request("tools/call", {"name": name, "arguments": arguments}, timeout=timeout)


    def close(self) -> None:
        """Release the transport (a stream, a process). Safe to call twice."""


@dataclass
class McpClient(Session):
    url: str
    token: str | None = None
    send: Callable[..., Response] = http
    #: API key or custom header sign-ins (plan 34), e.g. {"X-Api-Key": "..."}; never logged.
    extra_headers: dict[str, str] = field(default_factory=dict)
    session_id: str | None = None
    _next: int = 0
    _ready: bool = False
    server: dict[str, Any] = field(default_factory=dict)

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": PROTOCOL_VERSION}
        headers.update(self.extra_headers)
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        return headers

    def _post(self, message: dict[str, Any], *, timeout: float) -> dict[str, Any] | None:
        wanted = message.get("id")

        def done(raw: bytes) -> bool:
            return any(m.get("id") == wanted and ("result" in m or "error" in m) for m in _sse_messages(raw))

        response = self.send("POST", self.url, headers=self._headers(), body=json.dumps(message).encode(), timeout=timeout,
                             stop=done if wanted is not None else None)
        if response.status == 401:
            header = response.headers.get("www-authenticate", "")
            match = re.search(r'resource_metadata="([^"]+)"', header)
            raise NeedsSignIn(match.group(1) if match else None)
        if response.status == 404 and self.session_id:
            self.session_id, self._ready = None, False
            raise McpError("The server ended the session.", 404)
        if response.status == 403:
            raise McpError("The server refused this (403). Check the account's plan or sign in again.", 403)
        if response.status >= 400:
            raise McpError(f"The server answered {response.status}.", response.status)
        if wanted is None:
            return None
        session = response.headers.get("mcp-session-id")
        if session and re.match(r"^[\x21-\x7e]{1,200}$", session):
            self.session_id = session
        kind = response.headers.get("content-type", "")
        if "text/event-stream" in kind:
            messages = _sse_messages(response.body)
        else:
            try:
                parsed = json.loads(response.body or b"null")
            except ValueError as exc:
                raise McpError("The server's answer is not JSON.") from exc
            messages = parsed if isinstance(parsed, list) else [parsed]
        result = _pick(messages, wanted)
        if result is None:
            raise McpError("The server did not answer the request.")
        return result

    def _reset(self) -> None:
        self.session_id = None

# ---------------------------------------------------------------------------------------------------- OAuth 2.1


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def pkce() -> tuple[str, str]:
    verifier = _b64url(secrets.token_bytes(32))
    return verifier, _b64url(hashlib.sha256(verifier.encode()).digest())


def _get_json(send: Callable[..., Response], url: str) -> dict[str, Any] | None:
    try:
        response = send("GET", check_url(url), headers={"Accept": "application/json"}, timeout=20)
    except McpError:
        return None
    if response.status != 200:
        return None
    try:
        value = json.loads(response.body)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _well_known(base: str, name: str) -> list[str]:
    parts = urllib.parse.urlsplit(base)
    origin = f"{parts.scheme}://{parts.netloc}"
    path = parts.path.rstrip("/")
    urls = [f"{origin}/.well-known/{name}{path}"] if path else []
    return urls + [f"{origin}/.well-known/{name}"]


@dataclass
class OAuthServer:
    issuer: str
    authorization_endpoint: str
    token_endpoint: str
    registration_endpoint: str | None
    resource: str
    scopes: list[str]

    def public(self) -> dict[str, Any]:
        return {"issuer": self.issuer, "authorizationEndpoint": self.authorization_endpoint, "tokenEndpoint": self.token_endpoint,
                "registrationEndpoint": self.registration_endpoint, "resource": self.resource, "scopes": self.scopes}

    @classmethod
    def from_public(cls, value: dict[str, Any]) -> "OAuthServer":
        return cls(value["issuer"], value["authorizationEndpoint"], value["tokenEndpoint"], value.get("registrationEndpoint"),
                   value["resource"], list(value.get("scopes") or []))


def discover(server_url: str, resource_metadata: str | None, send: Callable[..., Response] = http) -> OAuthServer:
    """RFC 9728 protected resource metadata, then RFC 8414 / OIDC authorization server metadata."""
    prm = None
    for url in ([resource_metadata] if resource_metadata else []) + _well_known(server_url, "oauth-protected-resource"):
        prm = _get_json(send, url)
        if prm:
            break
    resource = server_url
    scopes: list[str] = []
    if prm:
        servers = prm.get("authorization_servers")
        issuer = servers[0] if isinstance(servers, list) and servers and isinstance(servers[0], str) else None
        if isinstance(prm.get("resource"), str):
            resource = prm["resource"]
        if isinstance(prm.get("scopes_supported"), list):
            scopes = [s for s in prm["scopes_supported"] if isinstance(s, str)][:20]
    else:
        parts = urllib.parse.urlsplit(server_url)
        issuer = f"{parts.scheme}://{parts.netloc}"
    if not issuer:
        raise McpError("The server does not say where to sign in.")
    issuer = check_url(issuer, what="sign-in server").rstrip("/")
    meta = None
    for url in _well_known(issuer, "oauth-authorization-server") + _well_known(issuer, "openid-configuration") + [issuer + "/.well-known/openid-configuration"]:
        meta = _get_json(send, url)
        if meta:
            break
    if meta is None:
        meta = {"authorization_endpoint": issuer + "/authorize", "token_endpoint": issuer + "/token", "registration_endpoint": issuer + "/register"}
    methods = meta.get("code_challenge_methods_supported")
    if isinstance(methods, list) and "S256" not in methods:
        raise McpError("The sign-in server does not support PKCE (S256), so Cyclone will not use it.")
    # Without the resource's own scope list, no scope is asked for, so the server grants its defaults.
    registration = meta.get("registration_endpoint")
    return OAuthServer(
        issuer=issuer,
        authorization_endpoint=check_url(meta.get("authorization_endpoint"), what="sign-in page"),
        token_endpoint=check_url(meta.get("token_endpoint"), what="token address"),
        registration_endpoint=check_url(registration, what="registration address") if isinstance(registration, str) else None,
        resource=resource,
        scopes=scopes,
    )


def register(server: OAuthServer, redirect_uri: str, send: Callable[..., Response] = http) -> dict[str, Any]:
    """RFC 7591 dynamic registration of a public client (PKCE, no secret)."""
    if not server.registration_endpoint:
        raise McpError("This server does not let apps register themselves, so Cyclone cannot sign in to it yet.")
    body = {"client_name": "Cyclone Command Center", "redirect_uris": [redirect_uri], "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"], "token_endpoint_auth_method": "none"}
    response = send("POST", server.registration_endpoint, headers={"Content-Type": "application/json", "Accept": "application/json"},
                    body=json.dumps(body).encode(), timeout=20)
    try:
        value = json.loads(response.body)
    except ValueError:
        value = None
    if response.status not in (200, 201) or not isinstance(value, dict) or not isinstance(value.get("client_id"), str):
        raise McpError("The server did not register Cyclone as an app.")
    out = {"client_id": value["client_id"]}
    if isinstance(value.get("client_secret"), str):
        out["client_secret"] = value["client_secret"]
    return out


def authorize_url(server: OAuthServer, client_id: str, redirect_uri: str, state: str, challenge: str) -> str:
    query = {"response_type": "code", "client_id": client_id, "redirect_uri": redirect_uri, "state": state,
             "code_challenge": challenge, "code_challenge_method": "S256"}
    if server.resource:  # MCP servers name their resource (RFC 8707); a plain API's sign-in may not accept one
        query["resource"] = server.resource
    if server.scopes:
        query["scope"] = " ".join(server.scopes)
    joiner = "&" if "?" in server.authorization_endpoint else "?"
    return server.authorization_endpoint + joiner + urllib.parse.urlencode(query)


def token_request(server: OAuthServer, form: dict[str, str], client: dict[str, Any], send: Callable[..., Response] = http) -> dict[str, Any]:
    body = {**form, "client_id": client["client_id"]}
    if server.resource:
        body["resource"] = server.resource
    if client.get("client_secret"):
        body["client_secret"] = client["client_secret"]
    response = send("POST", server.token_endpoint, headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
                    body=urllib.parse.urlencode(body).encode(), timeout=30)
    try:
        value = json.loads(response.body)
    except ValueError:
        value = None
    if response.status != 200 or not isinstance(value, dict) or not isinstance(value.get("access_token"), str):
        raise McpError("Signing in did not work (the server gave no token). Try again.")
    if str(value.get("token_type", "bearer")).lower() != "bearer":
        raise McpError("The server gave a kind of token Cyclone does not use.")
    return value


def fetch_file(url: str, dest: "Any", *, limit: int, allow_loopback: bool, timeout: float = 120.0,
               url_policy: Callable[[str], bool] | None = None) -> tuple[str, int, str]:
    """Download a generated file into [dest] (a Path): https to a public host, up to three redirects, each re-checked,
    at most [limit] bytes. Returns (content type, size, sha256)."""
    # A constrained local output must reach that local server, even if Windows has a proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect()) if url_policy is not None else _OPENER
    for _ in range(4):
        url = check_url(url, what="file address")
        if url_policy is not None and not url_policy(url):
            raise McpError("The file is outside this connection's approved output routes.")
        if not public_host(url, allow_loopback=allow_loopback):
            raise McpError("The file is on a private network address; Cyclone does not fetch from there.")
        request = urllib.request.Request(url, method="GET", headers={"Accept": "video/*, image/*, audio/*"})
        try:
            response = opener.open(request, timeout=timeout)  # noqa: S310 - checked above
        except urllib.error.HTTPError as exc:
            location = exc.headers.get("Location") if exc.code in (301, 302, 303, 307, 308) else None
            if not location:
                raise McpError(f"The file could not be downloaded ({exc.code}).") from exc
            url = urllib.parse.urljoin(url, location)
            continue
        except (urllib.error.URLError, OSError) as exc:
            raise McpError("The file could not be downloaded.") from exc
        with response:
            kind = (response.headers.get("Content-Type") or "application/octet-stream").split(";")[0].strip().lower()
            digest = hashlib.sha256()
            size = 0
            with open(dest, "wb") as out:
                while True:
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > limit:
                        raise McpError("The file is too large.")
                    digest.update(chunk)
                    out.write(chunk)
            return kind, size, digest.hexdigest()
    raise McpError("The file address redirected too often.")


def offers_oauth(server_url: str, resource_metadata: str | None, send: Callable[..., Response] = http) -> bool:
    """True when the server publishes OAuth metadata (so "Sign in" can work); False means it wants a key or header."""
    if resource_metadata and _get_json(send, resource_metadata):
        return True
    for url in _well_known(server_url, "oauth-protected-resource") + _well_known(server_url, "oauth-authorization-server"):
        if _get_json(send, url):
            return True
    return False


class LegacySseClient(Session):
    """The older MCP transport (2024-11-05): a GET event stream announces where to POST, and the answers come back on
    that stream. Used only when a server refuses Streamable HTTP. The POST address must stay on the same host."""

    def __init__(self, url: str, *, token: str | None = None, extra_headers: dict[str, str] | None = None) -> None:
        self.url = check_url(url, what="server address")
        self.token = token
        self.extra_headers = dict(extra_headers or {})
        self._next, self._ready, self.server = 0, False, {}
        self._endpoint: str | None = None
        self._answers: dict[Any, dict[str, Any]] = {}
        self._cond = threading.Condition()
        self._stream: Any = None
        self._closed = False

    def _headers(self, accept: str) -> dict[str, str]:
        headers = {"Accept": accept, **self.extra_headers}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def _open(self) -> None:
        request = urllib.request.Request(self.url, method="GET", headers=self._headers("text/event-stream"))
        try:
            stream = _OPENER.open(request, timeout=300)  # noqa: S310 - check_url limits schemes and hosts
        except urllib.error.HTTPError as exc:
            if exc.code == 401:
                match = re.search(r'resource_metadata="([^"]+)"', exc.headers.get("WWW-Authenticate", "") or "")
                raise NeedsSignIn(match.group(1) if match else None) from exc
            raise McpError(f"The server answered {exc.code}.", exc.code) from exc
        except (urllib.error.URLError, OSError) as exc:
            raise McpError("The server could not be reached.") from exc
        if "text/event-stream" not in (stream.headers.get("Content-Type") or ""):
            stream.close()
            raise McpError("The server does not speak MCP here.")
        self._stream, self._closed = stream, False
        threading.Thread(target=self._read, args=(stream,), name="cyclone-mcp-sse", daemon=True).start()
        with self._cond:
            if not self._cond.wait_for(lambda: self._endpoint is not None or self._closed, timeout=15) or self._endpoint is None:
                self.close()
                raise McpError("The server did not say where to send requests.")

    def _read(self, stream: Any) -> None:
        event, data = "message", []
        try:
            while True:
                raw = stream.readline(MAX_RESPONSE)
                if not raw:
                    break
                line = raw.rstrip(b"\r\n")
                if not line:
                    self._dispatch(event, b"\n".join(data))
                    event, data = "message", []
                    continue
                if line.startswith(b":"):
                    continue
                name, _, value = line.partition(b":")
                value = value[1:] if value.startswith(b" ") else value
                if name == b"event":
                    event = value.decode("utf-8", "replace")
                elif name == b"data":
                    data.append(value)
        except (OSError, ValueError):
            pass
        finally:
            with self._cond:
                self._closed = True
                self._cond.notify_all()

    def _dispatch(self, event: str, data: bytes) -> None:
        if event == "endpoint":
            target = urllib.parse.urljoin(self.url, data.decode("utf-8", "replace").strip())
            here, there = urllib.parse.urlsplit(self.url), urllib.parse.urlsplit(target)
            if (here.scheme, here.netloc) != (there.scheme, there.netloc):
                return  # a POST address on another host is ignored; the open then fails
            with self._cond:
                self._endpoint = target
                self._cond.notify_all()
            return
        try:
            message = json.loads(data or b"null")
        except ValueError:
            return
        if isinstance(message, dict) and "id" in message and ("result" in message or "error" in message):
            with self._cond:
                self._answers[message["id"]] = message
                self._cond.notify_all()

    def _post(self, message: dict[str, Any], *, timeout: float) -> dict[str, Any] | None:
        if self._stream is None or self._closed:
            self._open()
        assert self._endpoint is not None
        headers = {"Content-Type": "application/json", **self._headers("application/json, text/event-stream")}
        response = http("POST", self._endpoint, headers=headers, body=json.dumps(message).encode(), timeout=timeout)
        if response.status == 401:
            raise NeedsSignIn(None)
        if response.status >= 400:
            raise McpError(f"The server answered {response.status}.", response.status)
        wanted = message.get("id")
        if wanted is None:
            return None
        with self._cond:
            self._cond.wait_for(lambda: wanted in self._answers or self._closed, timeout=timeout)
            answer = self._answers.pop(wanted, None)
        if answer is None:
            raise McpError("The server did not answer the request.")
        return _pick([answer], wanted)

    def _reset(self) -> None:
        self.close()
        self._endpoint, self._stream = None, None

    def close(self) -> None:
        with self._cond:
            self._closed = True
            self._cond.notify_all()
        if self._stream is not None:
            # The reader thread is blocked in readline; shutting the socket down wakes it (close alone would wait).
            sock = getattr(getattr(getattr(self._stream, "fp", None), "raw", None), "_sock", None)
            try:
                if sock is not None:
                    sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
