"""Discover MRZ Studio on this PC, without executing a discovered command.

The ordinary connection store still owns program pins, tool permissions and calls.
Discovery reads only the known settings file or bounded loopback HTTP endpoints.
Its background thread is independent of the task scheduler and the Glass tab.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path
from typing import Any, Callable

from . import mcp
from .center import CommandError

DEFAULT_API = "http://127.0.0.1:8787"
INTERVAL = 30.0
MAX_SETTINGS = 64 * 1024


def local_http(method: str, url: str, *, headers: dict[str, str], timeout: float) -> mcp.Response:
    """A bounded probe. No system proxy, redirects, downloads, or access off this PC."""
    p = urllib.parse.urlsplit(url)
    local_base(urllib.parse.urlunsplit((p.scheme, p.netloc, "", p.query, p.fragment)))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), mcp._NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, method=method, headers=headers), timeout=timeout) as response:
            return mcp.Response(response.status, dict(response.headers), response.read(MAX_SETTINGS + 1))
    except (OSError, urllib.error.URLError) as exc:
        raise mcp.McpError("Studio could not be reached.") from exc


def local_base(value: Any) -> str:
    """Literal loopback only; localhost is canonicalized before any network request."""
    if not isinstance(value, str) or len(value) > 2048:
        raise ValueError("Use a local Studio address.")
    p = urllib.parse.urlsplit(value.strip())
    if p.scheme != "http" or p.hostname not in mcp.LOOPBACK or p.username or p.password or p.query or p.fragment:
        raise ValueError("MRZ discovery accepts HTTP on this PC only.")
    if p.path not in ("", "/"):
        raise ValueError("Use the Studio origin without a path.")
    host = "[::1]" if p.hostname == "::1" else "127.0.0.1"
    return f"http://{host}" + (f":{p.port}" if p.port else "")


def local_mcp_url(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("The local MCP address is missing.")
    p = urllib.parse.urlsplit(value)
    origin = local_base(urllib.parse.urlunsplit((p.scheme, p.netloc, "", p.query, p.fragment)))
    if not p.path.startswith("/") or "\\" in p.path:
        raise ValueError("The local MCP address needs a path.")
    return origin + p.path


def recipe(data: Any) -> tuple[str, str, dict[str, Any]]:
    if not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("studio"), dict):
        raise ValueError("Studio returned incompatible connection settings.")
    api = local_base(data["studio"].get("api_base"))
    ui = local_base(data["studio"].get("ui_base"))
    entries = data.get("connectors")
    if not isinstance(entries, list) or len(entries) > 20:
        raise ValueError("Studio connection settings are invalid.")
    matching = [c for c in entries if isinstance(c, dict) and c.get("id") == "employee-id"]
    if len(matching) != 1:
        raise ValueError("Add one Employee ID connector in Studio's MCP settings.")
    c = matching[0]
    if c.get("enabled") is not True:
        raise ValueError("Employee ID is disabled in Studio. Enable it there to connect.")
    if c.get("glass_kind") == "local_stdio":
        args = c.get("args")
        if not isinstance(c.get("command"), str) or not isinstance(args, list) or not all(isinstance(a, str) for a in args):
            raise ValueError("The Studio program recipe is invalid.")
        spec = {"config": {"mcpServers": {"employee-id": {"command": c["command"], "args": args}}}, "name": "MRZ Studio · Employee ID"}
    elif c.get("glass_kind") == "remote_http":
        spec = {"name": "MRZ Studio · Employee ID", "url": local_mcp_url(c.get("http_url"))}
    else:
        raise ValueError("Choose a local program or local HTTP MCP in Studio settings.")
    return api, ui, spec


def fingerprint(api: str, spec: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps([api, spec], sort_keys=True).encode()).hexdigest()


class MrzDiscovery:
    def __init__(self, store: Any, *, settings: Path | None = None,
                 send: Callable[..., mcp.Response] = local_http) -> None:
        self.store = store
        self.settings = settings or Path(os.environ.get("CYCLONE_MRZ_SETTINGS") or Path.home() / "MRZ-Studio-Local" / "control" / "mcp-connections.json")
        self.send = send
        self._lock = threading.RLock()
        self._scan_lock = threading.Lock()
        self._connect_lock = threading.Lock()
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self._spec: dict[str, Any] | None = None
        self._state: dict[str, Any] = {"state": "looking", "detail": "Looking for MRZ Studio on this PC.",
                                      "apiBase": DEFAULT_API, "uiBase": "http://127.0.0.1:5173", "checkedAt": None,
                                      "ready": False, "health": None, "recipe": None, "connectionId": None,
                                      "settingsChanged": False}
        with store._c._lock:
            store._c._db.execute("CREATE TABLE IF NOT EXISTS mrz_connection (id INTEGER PRIMARY KEY CHECK(id=1), connection_id TEXT NOT NULL, api_base TEXT NOT NULL, recipe_hash TEXT NOT NULL)")

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._loop, name="cyclone-mrz-discovery", daemon=True)
            self._thread.start()

    def close(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread:
            self._thread.join(timeout=10)

    def _loop(self) -> None:
        while not self._stop.is_set():
            self.scan()
            self._wake.wait(INTERVAL)
            self._wake.clear()

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            state = dict(self._state)
        with self.store._c._lock:
            link = self._link()
            if link:
                state["connectionId"] = link["connection_id"]
                state["connectionStatus"] = self.store._row(link["connection_id"])["status"]
            else:
                state["connectionId"] = None
                state["settingsChanged"] = False
        return state

    def _link(self) -> Any:
        return self.store._c._db.execute("SELECT m.* FROM mrz_connection m JOIN connection c ON c.id=m.connection_id WHERE m.id=1").fetchone()

    def _get(self, url: str) -> Any:
        r = self.send("GET", url, timeout=2.0, headers={"Accept": "application/json"})
        if r.status != 200 or len(r.body) > MAX_SETTINGS:
            raise ValueError("Studio did not return a valid response.")
        return json.loads(r.body)

    def scan(self) -> dict[str, Any]:
        # Only one probe in flight. An owner refresh never queues another batch.
        if not self._scan_lock.acquire(blocking=False):
            return self.snapshot()
        try:
            state = {"state": "offline", "detail": "Start MRZ Studio. Cyclone checks again automatically.",
                     "apiBase": DEFAULT_API, "uiBase": "http://127.0.0.1:5173", "checkedAt": int(time.time() * 1000),
                     "ready": False, "health": None, "recipe": None, "connectionId": None, "settingsChanged": False}
            spec = None
            try:
                if self.settings.exists():
                    with self.settings.open("rb") as f:
                        raw = f.read(MAX_SETTINGS + 1)
                    if len(raw) > MAX_SETTINGS:
                        raise ValueError("Studio settings exceed 64 KB.")
                    data = json.loads(raw)
                else:
                    data = self._get(DEFAULT_API + "/api/mcp-connections")
                api, ui, spec = recipe(data)
                state.update(apiBase=api, uiBase=ui, recipe=spec.get("config"))
                health = self._get(api + "/api/health")
                if not isinstance(health, dict) or health.get("mode") != "local" or not all(isinstance(health.get(k), dict) for k in ("worker", "photoshop", "template")):
                    raise ValueError("This is not a compatible MRZ Studio health endpoint.")
                flags = {"api": health.get("ok") is True, "worker": health["worker"].get("online") is True,
                         "photoshop": health["photoshop"].get("found") is True, "template": health["template"].get("present") is True,
                         "dryRun": health.get("dryRun") is True}
                ready = flags["api"] and flags["worker"] and flags["template"] and (flags["photoshop"] or flags["dryRun"])
                state.update(state="found", health=flags, ready=ready,
                             detail="Studio is ready for jobs." if ready else "Studio found. Check the worker, Photoshop and template before generating.")
                if flags["dryRun"]:
                    state["detail"] = "Studio is in dry-run mode: output is a placeholder." if ready else "Studio found in dry-run mode; the worker or template is not ready."
                # An owner may have used the paste route before discovery started.
                # Associate only the exact saved recipe; this grants no execution or tools.
                self._adopt(spec, api)
            except (OSError, ValueError, mcp.McpError) as exc:
                state["detail"] = str(exc) if isinstance(exc, ValueError) else "Studio is offline or unreachable. Cyclone will keep checking."
                state["state"] = "attention" if isinstance(exc, ValueError) else "offline"
            with self.store._c._lock:
                link = self._link()
                if link:
                    state["connectionId"] = link["connection_id"]
                    state["settingsChanged"] = spec is not None and link["recipe_hash"] != fingerprint(state["apiBase"], spec)
            with self._lock:
                self._state, self._spec = state, spec
            return self.snapshot()
        except Exception:  # a discovery fault must not end the background service
            with self._lock:
                self._state.update(state="attention", ready=False, detail="MRZ discovery needs attention. Check Studio and try again.")
            return self.snapshot()
        finally:
            self._scan_lock.release()

    def connect(self) -> dict[str, Any]:
        # This is an owner action. Ordinary add/refresh retains hash and tool approval boundaries.
        with self._connect_lock:
            self.scan()
            with self._lock:
                spec = self._spec
                state = dict(self._state)
            if not spec or state["state"] != "found":
                raise CommandError("Open Studio's MCP settings and check its endpoint before connecting.")
            with self.store._c._lock:
                link = self._link()
                if link:
                    if state["settingsChanged"]:
                        raise CommandError("Studio's recipe changed. Remove the old MRZ connection after its jobs finish, then connect and approve the new recipe.")
                    return self.store.get(link["connection_id"])
            result = self.store.add(spec)
            with self.store._c._lock:
                self.store._c._db.execute("INSERT OR REPLACE INTO mrz_connection VALUES(1,?,?,?)", (result["id"], state["apiBase"], fingerprint(state["apiBase"], spec)))
                self.store._c._audit("owner", "connection.mrz", result["id"], {"apiBase": state["apiBase"]})
            return result

    def adopt_paste(self) -> None:
        """An owner-added recipe gets the same durable association without another Connect click."""
        with self._lock:
            spec, state = self._spec, dict(self._state)
        if spec and state["state"] == "found":
            self._adopt(spec, state["apiBase"])

    def existing_paste(self, launch: dict[str, Any]) -> dict[str, Any] | None:
        """Repeated paste of the saved MRZ recipe reuses its connection and approvals."""
        with self._lock:
            spec, state = self._spec, dict(self._state)
        if not spec or "config" not in spec or state["state"] != "found":
            return None
        expected = spec["config"]["mcpServers"]["employee-id"]
        if launch["launcher"] != expected["command"] or launch["args"] != expected["args"] or launch.get("envKeys"):
            return None
        self._adopt(spec, state["apiBase"])
        with self.store._c._lock:
            link = self._link()
            if link and link["recipe_hash"] == fingerprint(state["apiBase"], spec):
                return self.store.get(link["connection_id"])
        return None

    def _adopt(self, spec: dict[str, Any], api: str) -> None:
        with self.store._c._lock:
            if self._link():
                return
            for row in self.store._c._db.execute("SELECT * FROM connection ORDER BY created_at, id"):
                matches = False
                if "config" in spec and row["kind"] == "local":
                    launch = json.loads(row["launch"])
                    expected = spec["config"]["mcpServers"]["employee-id"]
                    matches = (launch["launcher"] == expected["command"] and launch["args"] == expected["args"]
                               and not launch.get("envKeys"))
                elif "url" in spec and row["kind"] == "remote":
                    matches = row["url"] == spec["url"]
                if matches:
                    self.store._c._db.execute("INSERT OR REPLACE INTO mrz_connection VALUES(1,?,?,?)", (row["id"], api, fingerprint(api, spec)))
                    self.store._c._audit("engine", "connection.mrz_adopt", row["id"], {"apiBase": api})
                    return

    def recover(self, connection_id: str, tool: str) -> None:
        """An allowed call may reconnect its approved MCP, never replay a mutation or grant tools."""
        with self.store._c._lock:
            link = self._link()
            if not link or link["connection_id"] != connection_id:
                return
            row = self.store._row(connection_id)
            if row["status"] != "error" or tool not in json.loads(row["allowed"]):
                return
            if row["kind"] == "local":
                launch = json.loads(row["launch"])
                if launch.get("approvedHash") != launch["hash"]:
                    return
        state = self.scan()
        if state["state"] == "found" and not state["settingsChanged"]:
            self.store.refresh(connection_id)

    def is_linked(self, connection_id: str) -> bool:
        with self.store._c._lock:
            link = self._link()
        return bool(link and link["connection_id"] == connection_id)

    def artifact_allowed(self, connection_id: str, url: str) -> bool:
        """Only job-output routes on the explicitly linked Studio origin, never arbitrary localhost files."""
        import re
        with self.store._c._lock:
            link = self._link()
        if not link or link["connection_id"] != connection_id:
            return False
        p = urllib.parse.urlsplit(url)
        try:
            origin = local_base(urllib.parse.urlunsplit((p.scheme, p.netloc, "", p.query, p.fragment)))
        except ValueError:
            return False
        return origin == link["api_base"] and bool(re.fullmatch(r"/api/jobs/[A-Za-z0-9_-]{1,100}/files/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", p.path))
