"""The Port Hub (plan 48, run 1): the owner adds plugins, Cyclone pins what each one asks for, issues its key once,
checks it with the kit's conformance suite, watches its health, and sends signed messages to it.

Rules this module keeps:
- **The owner adds every plugin** from Glass, and allows each port it serves with a visible switch. Public ports start
  allowed. Personal and secret ports start allowed only for a plugin on this PC; a remote plugin starts with them off.
- **Pinned manifests.** What a plugin asked for when it was added is pinned. If its ports, needs, features or endpoint
  change later, it stops receiving anything until the owner reviews the change.
- **Keys are shown once** (``k<N>.<secret>``), kept sealed apart from the database, and never returned again. A new
  key replaces the old one, and the plugin must pass its checks again before it is live.
- **Nothing is green unless it passed.** A plugin is live only after the conformance checks pass with its key.
- **Metadata only.** The activity log keeps who, which port, the status and the time, never a body.
- **No control.** Plugins never get a way to approve, pay, send, delete or reach the phone directly.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from . import bindings as binding
from . import kit
from .store import PortStore, now_ms

PIN_FIELDS = ("serves", "needs", "features", "endpoint")
MONITOR_EVERY_S = 30.0
FETCH_TIMEOUT_S = 5.0
SEND_TIMEOUT_S = 10.0
MAX_MANIFEST_BYTES = 64 * 1024
FAILURES_UNREACHABLE = 2

#: One human hint per conformance check, so a failed check always says what to do next.
HINTS: list[tuple[str, str]] = [
    ("manifest is served", "Cyclone couldn't read the plugin's manifest. Is the plugin running at this address?"),
    ("manifest is valid", "The plugin's manifest doesn't follow cyclone.ports/1. The details say what to fix."),
    ("manifest endpoint matches", "The manifest names another address. It still works; update its endpoint field."),
    ("/health", "Add GET /health that answers {\"ok\": true}. The SDK's PluginServer does this."),
    ("unsigned request", "The plugin accepts requests without a signature. Check X-Cyclone-Signature on every request."),
    ("wrong secret", "The plugin accepts requests signed with another key. It must check the signature with its own key."),
    ("stale signature", "The plugin accepts old signatures. Refuse any signature more than 5 minutes old."),
    ("another path", "The plugin doesn't check that the signature matches the path. Update to the latest SDK."),
    ("errors use", "Errors should look like {\"error\": {\"code\", \"message\", \"retryable\"}}."),
    ("does not serve", "Answer 404 for ports the plugin doesn't list in its manifest."),
    ("tolerates unknown", "The plugin breaks on a field or value it doesn't know. Ignore unknown fields and values."),
    ("replayed", "The plugin accepts the same signed request twice. Remember request ids for 10 minutes."),
    ("malformed envelope", "The plugin accepts a broken message. Reject messages missing required fields."),
    ("same awaitId", "A repeated wait must be accepted as the same wait."),
    ("accepts a cancel", "Answer 200 to /cancel and stop that wait."),
    ("accepts an await", "Answer 202 to /await at once and deliver later."),
    ("accepts a sample envelope", "The plugin refused Cyclone's signed message."),
]
KEY_HINT = ("The plugin doesn't have its key yet. Set CYCLONE_PLUGIN_SECRET to the key Cyclone showed you, restart "
            "the plugin, then run the checks again.")


class PortsError(ValueError):
    """A refused request; the message is for the owner."""


def _hint(check: dict[str, Any]) -> str:
    if check["ok"]:
        return ""
    name = check["name"]
    # A plugin without its key refuses every signed request first (401), whatever the check was about.
    if not name.startswith("refuses") and "HTTP 401" in check.get("detail", ""):
        return KEY_HINT
    return next((hint for key, hint in HINTS if key in name), "")


def normalize_endpoint(raw: Any) -> str:
    """The plugin's base URL. Plain http only on this PC (loopback); https anywhere else. Never another scheme."""
    if not isinstance(raw, str) or not raw.strip():
        raise PortsError("Enter the plugin's address, like http://127.0.0.1:8771.")
    text = raw.strip()
    if "://" not in text:
        text = "http://" + text
    for suffix in ("/cyclone-plugin.json", "/health"):
        if text.endswith(suffix):
            text = text[: -len(suffix)]
    try:
        parts = urllib.parse.urlsplit(text)
        port = parts.port
    except ValueError as exc:
        raise PortsError("That address isn't valid.") from exc
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise PortsError("Use an http:// address on this PC or an https:// address.")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise PortsError("Leave out user names, passwords, queries and # in the address.")
    host = parts.hostname.lower()
    if parts.scheme == "http" and not _loopback(host):
        raise PortsError("Plain http is only allowed for a plugin on this PC (127.0.0.1 or localhost). Use https for "
                         "a plugin elsewhere.")
    netloc = (f"[{host}]" if ":" in host else host) + (f":{port}" if port else "")
    return urllib.parse.urlunsplit((parts.scheme, netloc, parts.path.rstrip("/"), "", ""))


def _loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def is_remote(endpoint: str) -> bool:
    return not _loopback((urllib.parse.urlsplit(endpoint).hostname or "").lower())


def pin_hash(manifest: dict[str, Any], endpoint: str) -> str:
    pinned = {k: manifest.get(k) for k in PIN_FIELDS if k != "endpoint"}
    pinned["endpoint"] = endpoint
    return hashlib.sha256(json.dumps(pinned, sort_keys=True).encode()).hexdigest()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # a plugin answers itself; a redirect is refused, never followed
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def http_json(method: str, url: str, body: bytes | None = None, headers: dict[str, str] | None = None,
              timeout: float = FETCH_TIMEOUT_S) -> tuple[int, Any, int]:
    """(status, JSON or None, latency ms). Status 0 means unreachable."""
    started = time.monotonic()
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            raw = response.read(MAX_MANIFEST_BYTES + 1)
            status = response.status
    except urllib.error.HTTPError as error:
        raw, status = error.read(MAX_MANIFEST_BYTES + 1) if error.fp else b"", error.code
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None, int((time.monotonic() - started) * 1000)
    latency = int((time.monotonic() - started) * 1000)
    if len(raw) > MAX_MANIFEST_BYTES:
        return status, None, latency
    try:
        return status, json.loads(raw) if raw else None, latency
    except ValueError:
        return status, None, latency


class PortHub:
    def __init__(self, root: Path, *, store: PortStore | None = None,
                 checker: Callable[[str, str], list[Any]] | None = None,
                 fetch: Callable[..., tuple[int, Any, int]] = http_json,
                 base_url: str = "http://127.0.0.1:8765") -> None:
        if kit is None:
            raise RuntimeError("The Cyclone Ports kit (tools/cyclone-ports-sdk) is not installed.")
        self.store = store or PortStore(root)
        self._check = checker or _conformance
        self._fetch = fetch
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._seq: dict[str, int] = {}
        # Run 3: live traffic between runs and plugins, and test runs the owner starts from Glass.
        from .traffic import TestRuns, Traffic
        self.traffic = Traffic(self, root, base_url)
        self.test_runs = TestRuns(self.traffic)
        self._ticker: threading.Thread | None = None
        from .id_generator import IdGeneratorStarter
        self.id_generator = IdGeneratorStarter(self)

    # ---- lifecycle ---------------------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._monitor_loop, name="cyclone-port-hub", daemon=True)
        self._thread.start()
        self.traffic.resume()
        self._ticker = threading.Thread(target=self._tick_loop, name="cyclone-port-waits", daemon=True)
        self._ticker.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
        self._thread = None
        self.traffic.stop()

    def _tick_loop(self) -> None:
        while not self._stop.wait(1.0):
            try:
                self.traffic.tick()
            except Exception:  # noqa: BLE001 - the clock never takes the gateway down
                pass

    def _monitor_loop(self) -> None:
        while not self._stop.wait(MONITOR_EVERY_S):
            try:
                self.monitor_once()
                self.id_generator.status(refresh=True)
            except Exception:  # noqa: BLE001 - the monitor never takes the gateway down
                pass

    # ---- reading -----------------------------------------------------------------------------------------------------

    def _manifest(self, endpoint: str) -> dict[str, Any]:
        status, manifest, _ = self._fetch("GET", endpoint + "/cyclone-plugin.json")
        if status == 0:
            raise PortsError("Nothing answered at that address. Start the plugin, then try again.")
        if status != 200 or not isinstance(manifest, dict):
            raise PortsError(f"That address answered, but not with a Cyclone plugin manifest (HTTP {status}).")
        return manifest

    def _serves(self, manifest: dict[str, Any], allowed: list[str] | None, remote: bool) -> list[dict[str, Any]]:
        out = []
        for served in manifest.get("serves") or []:
            spec = kit.port_spec(served.get("port"), served.get("way"))
            if spec is None:
                continue
            default = spec.sensitivity == "public" or not remote
            out.append({"port": spec.name, "way": spec.way, "sensitivity": spec.sensitivity,
                        "summary": spec.summary, "extension": kit.is_extension(spec.name),
                        "allowed": (spec.name in allowed) if allowed is not None else default})
        return out

    def preview(self, raw_endpoint: Any) -> dict[str, Any]:
        endpoint = normalize_endpoint(raw_endpoint)
        manifest = self._manifest(endpoint)
        problems = kit.validate_manifest(manifest)
        existing = self.store.by_endpoint(endpoint) or (
            self.store.plugin(manifest["name"]) if isinstance(manifest.get("name"), str) else None)
        remote = is_remote(endpoint)
        return {
            "endpoint": endpoint,
            "remote": remote,
            "problems": problems,
            "alreadyAdded": existing["name"] if existing else None,
            "manifest": _public_manifest(manifest),
            "serves": self._serves(manifest, None, remote) if not problems else [],
            "endpointDiffers": not problems and manifest["endpoint"].rstrip("/") != endpoint,
        }

    def overview(self) -> dict[str, Any]:
        plugins = [self._public(p) for p in self.store.plugins()]
        today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
        served: dict[str, list[str]] = {}
        for plugin in plugins:
            if plugin["status"] != "active":
                continue
            for s in plugin["serves"]:
                if s["allowed"]:
                    served.setdefault(s["port"], []).append(plugin["name"])
        resolved = {r["port"]: r for r in binding.table(plugins, self.store.bindings("default"), ["default"])}
        catalog = [{"port": p.name, "way": p.way, "sensitivity": p.sensitivity, "summary": p.summary,
                    "pluginServed": p.plugin_served, "servedBy": served.get(p.name, []),
                    "state": resolved[p.name]["state"], "effective": resolved[p.name]["effective"]}
                   for p in kit.CATALOG.values()]
        return {
            "contract": kit.CONTRACT,
            "keysPersistent": self.store.keys_persistent,
            "plugins": plugins,
            "catalog": catalog,
            "extensions": sorted({s["port"] for p in plugins for s in p["serves"] if s["extension"]}),
            "activity": self.store.activity(40),
            "conflicts": [r["port"] for r in resolved.values() if r["state"] in ("conflict", "unavailable")],
            "today": self.store.counts_since(int(today.timestamp() * 1000)),
        }

    def plugin(self, name: str) -> dict[str, Any]:
        return {"plugin": self._public(self._get(name)), "activity": self.store.activity(60, name)}

    def _get(self, name: str) -> dict[str, Any]:
        record = self.store.plugin(name) if isinstance(name, str) else None
        if record is None:
            raise PortsError("That plugin isn't added.")
        return record

    def _public(self, record: dict[str, Any]) -> dict[str, Any]:
        manifest = record["manifest"]
        remote = is_remote(record["endpoint"])
        checks = record["checks"] or None
        items = [dict(c, hint=_hint(c)) for c in (checks or {}).get("items", [])]
        for c in items:
            c["cause"] = "key" if c["hint"] == KEY_HINT else ""
        required_failed = [c for c in items if c["required"] and not c["ok"]]
        waiting_key = bool(items) and any(c["hint"] == KEY_HINT for c in required_failed)
        has_key = self.store.key(record["name"]) is not None
        if record["pending"]:
            status = "needs_review"
        elif record["paused"]:
            status = "paused"
        elif not has_key:
            status = "key_lost"
        elif not checks or required_failed:
            status = "waiting_key" if (not checks or waiting_key) else "failing_checks"
        elif record["health"] == "failing":
            status = "unreachable"
        else:
            status = "active"
        return {
            "name": record["name"],
            "title": manifest.get("title") or record["name"],
            "description": manifest.get("description") or "",
            "version": manifest.get("version") or "",
            "endpoint": record["endpoint"],
            "remote": remote,
            "status": status,
            "paused": bool(record["paused"]),
            "serves": self._serves(manifest, record["consent"] or [], remote),
            "needsPersonal": bool((manifest.get("needs") or {}).get("personal")),
            "features": [f for f in manifest.get("features") or [] if isinstance(f, str)],
            "kid": record["kid"],
            "health": record["health"],
            "healthDetail": record["health_detail"],
            "seenAt": record["seen_at"],
            "checkedAt": record["checked_at"],
            "checks": None if checks is None else {
                "passed": not required_failed, "total": len(items), "failed": len(required_failed), "items": items},
            "pending": _diff(manifest, record["pending"]) if record["pending"] else None,
            "createdAt": record["created_at"],
        }

    # ---- changing ----------------------------------------------------------------------------------------------------

    def add(self, raw_endpoint: Any, allowed: Any) -> dict[str, Any]:
        endpoint = normalize_endpoint(raw_endpoint)
        manifest = self._manifest(endpoint)
        problems = kit.validate_manifest(manifest)
        if problems:
            raise PortsError("The plugin's manifest has problems: " + "; ".join(problems[:3]))
        name = manifest["name"]
        if self.store.plugin(name) or self.store.by_endpoint(endpoint):
            raise PortsError(f"{manifest.get('title') or name} is already added.")
        consent = self._consent(manifest, allowed)
        kid, secret = "k1", secrets.token_urlsafe(32)
        self.store.insert({"name": name, "endpoint": endpoint, "manifest": manifest,
                           "pin_hash": pin_hash(manifest, endpoint), "consent": consent, "kid": kid})
        self.store.put_key(name, kid, secret)
        self.store.log(name, "added", ok=True, detail=f"version {manifest['version']}")
        return {"plugin": self._public(self._get(name)), "key": _key_card(f"{kid}.{secret}")}

    def _consent(self, manifest: dict[str, Any], allowed: Any) -> list[str]:
        if not isinstance(allowed, list) or not all(isinstance(p, str) for p in allowed):
            raise PortsError("Send the ports you allow as a list.")
        served = {s.get("port") for s in manifest.get("serves") or []}
        unknown = [p for p in allowed if p not in served]
        if unknown:
            raise PortsError(f"{unknown[0]} isn't a port this plugin serves.")
        return sorted(set(allowed))

    def set_port(self, name: str, port: Any, allowed: Any) -> dict[str, Any]:
        record = self._get(name)
        if not isinstance(allowed, bool):
            raise PortsError("Send allowed: true or false.")
        consent = set(record["consent"] or [])
        if port not in {s.get("port") for s in record["manifest"].get("serves") or []}:
            raise PortsError("That isn't a port this plugin serves.")
        (consent.add if allowed else consent.discard)(port)
        self.store.update(name, consent=sorted(consent))
        self.store.log(name, "consent", ok=True, port=port, detail="allowed" if allowed else "switched off")
        return self.plugin(name)

    def pause(self, name: str, paused: Any) -> dict[str, Any]:
        self._get(name)
        if not isinstance(paused, bool):
            raise PortsError("Send paused: true or false.")
        self.store.update(name, paused=1 if paused else 0)
        self.store.log(name, "paused" if paused else "resumed", ok=True)
        return self.plugin(name)

    def remove(self, name: str) -> dict[str, Any]:
        self._get(name)
        self.store.delete(name)
        self.store.forget_plugin_bindings(name)
        self.store.log(name, "removed", ok=True)
        return {"removed": name}

    def new_key(self, name: str) -> dict[str, Any]:
        record = self._get(name)
        number = int(re.sub(r"\D", "", record["kid"]) or "1") + 1
        kid, secret = f"k{number}", secrets.token_urlsafe(32)
        self.store.put_key(name, kid, secret)
        self.store.update(name, kid=kid, checks=None, checked_at=None)
        self.store.log(name, "key", ok=True, detail=f"new key {kid}; checks needed again")
        return {"plugin": self._public(self._get(name)), "key": _key_card(f"{kid}.{secret}")}

    def check(self, name: str) -> dict[str, Any]:
        record = self._get(name)
        key = self.store.key(name)
        if key is None:
            raise PortsError("This plugin's key was lost. Make a new key first.")
        started = time.monotonic()
        try:
            results = self._check(record["endpoint"], key)
        except Exception as exc:  # noqa: BLE001 - a broken plugin must not break the hub
            results = [_Check("checks could run", False, type(exc).__name__)]
        items = [{"name": c.name, "ok": bool(c.ok), "required": bool(c.required), "detail": str(c.detail)[:200]}
                 for c in results]
        passed = all(c["ok"] for c in items if c["required"]) and bool(items)
        reachable = not (len(items) == 1 and not items[0]["ok"] and "manifest is served" in items[0]["name"])
        stamp = now_ms()
        self.store.update(name, checks={"items": items}, checked_at=stamp,
                          health="ok" if reachable else "failing", failures=0 if reachable else record["failures"] + 1,
                          seen_at=stamp if reachable else record["seen_at"],
                          health_detail="" if reachable else "Nothing answered at its address.")
        failed = len([c for c in items if c["required"] and not c["ok"]])
        self.store.log(name, "check", ok=passed, latency_ms=int((time.monotonic() - started) * 1000),
                       detail="all checks passed" if passed else f"{failed} {'check' if failed == 1 else 'checks'} failed")
        return self.plugin(name)

    def approve_changes(self, name: str) -> dict[str, Any]:
        record = self._get(name)
        if not record["pending"]:
            raise PortsError("There's nothing to review.")
        manifest = record["pending"]
        served = {s.get("port") for s in manifest.get("serves") or []}
        consent = [p for p in record["consent"] or [] if p in served]
        self.store.update(name, manifest=manifest, pending=None, pin_hash=pin_hash(manifest, record["endpoint"]),
                          consent=consent)
        self.store.log(name, "approved", ok=True, detail=f"version {manifest.get('version')}")
        return self.check(name)

    def send_test(self, name: str) -> dict[str, Any]:
        """A signed message the plugin can show: log.line or run.event when it serves one, else a harmless cancel."""
        public = self._public(self._get(name))
        if public["status"] not in ("active", "unreachable"):
            raise PortsError("Run the checks first; only a plugin that passed them gets messages.")
        allowed = {s["port"]: s for s in public["serves"] if s["allowed"]}
        run = {"runId": "run_test_" + secrets.token_hex(4), "taskId": None, "rowId": None, "app": None}
        if "log.line" in allowed or "run.event" in allowed:
            port = "log.line" if "log.line" in allowed else "run.event"
            data = {"text": "Test from Cyclone Glass"} if port == "log.line" else {"stage": "started", "test": True}
            status, latency = self._send(name, port, "", self._envelope(run, port, data))
        else:
            port = next(iter(allowed), None) or public["serves"][0]["port"]
            way = next(s["way"] for s in public["serves"] if s["port"] == port)
            if way == "out":
                status, latency = self._send(name, port, "", self._envelope(run, port, {"test": True}))
            else:
                status, latency = self._send(name, port, "/cancel", {"v": 1, "runId": run["runId"], "port": port,
                                                                     "awaitId": "aw_test", "reason": "test"})
        ok = 200 <= status < 300
        self.store.log(name, "test", ok=ok, port=port, run_id=run["runId"], status=status, latency_ms=latency,
                       detail="delivered" if ok else ("unreachable" if status == 0 else f"answered {status}"))
        if ok:
            self.store.update(name, seen_at=now_ms(), health="ok", failures=0, health_detail="")
        return {"ok": ok, "status": status, "latencyMs": latency, "port": port, **self.plugin(name)}

    # ---- bindings (run 2) ---------------------------------------------------------------------------------------------

    def _plugins(self) -> list[dict[str, Any]]:
        return [self._public(p) for p in self.store.plugins()]

    def bindings(self, scope: Any = "default") -> dict[str, Any]:
        """The port map for one scope: every port, who could serve it, and who does (with inherited choices)."""
        try:
            scope = binding.check_scope(scope)
        except binding.BindingError as exc:
            raise PortsError(str(exc)) from exc
        plugins = self._plugins()
        scopes = [scope] if scope == "default" else [scope, "default"]
        choices = self.store.bindings()
        counts: dict[str, int] = {}
        for (s, _port) in choices:
            counts[s] = counts.get(s, 0) + 1
        return {
            "scope": scope,
            "scopes": [{"scope": s, "choices": n} for s, n in sorted(counts.items()) if s != "default"],
            "ports": binding.table(plugins, choices, scopes),
            "plugins": [{"name": p["name"], "title": p["title"], "status": p["status"]} for p in plugins],
        }

    def set_binding(self, scope: Any, port: Any, plugins: Any) -> dict[str, Any]:
        try:
            scope = binding.check_scope(scope)
            known = {row[0]: row for row in binding.catalog_ports(self._plugins())}
            if port not in known or not known[port][3]:
                raise binding.BindingError("That port can't be bound to a plugin.")
            cands = binding.candidates(self._plugins(), port)
            chosen = binding.validate_choice(port, known[port][1], plugins, cands)
        except binding.BindingError as exc:
            raise PortsError(str(exc)) from exc
        self.store.set_binding(scope, port, chosen)
        where = "everywhere" if scope == "default" else scope.replace(":", " ", 1)
        what = "automatic" if chosen is None else ("off" if not chosen else ", ".join(chosen))
        self.store.log("ports", "binding", ok=True, port=port, detail=f"{where}: {what}")
        return self.bindings(scope)

    def resolve(self, routine: str | None = None, app: str | None = None) -> dict[str, Any]:
        """What a run with this routine and app would reach on each port (run 3 sends along this table)."""
        try:
            scopes = binding.chain(routine or None, app or None)
        except binding.BindingError as exc:
            raise PortsError(str(exc)) from exc
        plugins = self._plugins()
        if not self.id_generator.allows(app, routine):
            # Keep the candidate so an explicit choice fails closed, rather than silently falling back.
            plugins = [dict(p, status="paused") if p["name"] == "id-generator" else p for p in plugins]
        return {"scopes": scopes, "ports": binding.table(plugins, self.store.bindings(), scopes)}

    # ---- signing and sending -----------------------------------------------------------------------------------------

    def _envelope(self, run: dict[str, Any], port: str, data: dict[str, Any]) -> dict[str, Any]:
        spec = kit.port_spec(port, "out")
        self._seq[run["runId"]] = self._seq.get(run["runId"], 0) + 1
        return {"v": 1, "id": "msg_" + secrets.token_hex(10), "runId": run["runId"], "taskId": run.get("taskId"),
                "rowId": run.get("rowId"), "port": port, "way": "out", "seq": self._seq[run["runId"]],
                "sentAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "app": run.get("app"),
                "pageKey": None, "sensitivity": spec.sensitivity if spec else "personal", "data": data}

    def _send(self, name: str, port: str, action: str, body: dict[str, Any]) -> tuple[int, int]:
        record = self._get(name)
        key = self.store.key(name)
        if key is None:
            return 0, 0
        path_prefix = urllib.parse.urlsplit(record["endpoint"]).path
        path = f"{path_prefix}/ports/{port}{action}"
        raw = json.dumps(body).encode()
        headers = {"Content-Type": "application/json", kit.CONTRACT_HEADER: kit.CONTRACT,
                   kit.SIGNATURE_HEADER: kit.sign(key, "POST", path, raw),
                   kit.TRACE_HEADER: f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"}
        status, _, latency = self._fetch("POST", f"{record['endpoint']}/ports/{port}{action}", raw, headers,
                                         SEND_TIMEOUT_S)
        return status, latency

    # ---- health ------------------------------------------------------------------------------------------------------

    def monitor_once(self) -> None:
        for record in self.store.plugins():
            name = record["name"]
            status, health, _ = self._fetch("GET", record["endpoint"] + "/health")
            up = status == 200 and isinstance(health, dict) and health.get("ok") is True
            if not up:
                failures = record["failures"] + 1
                failing = failures >= FAILURES_UNREACHABLE
                detail = "Nothing answered at its address." if status == 0 else f"/health answered {status}."
                if failing and record["health"] != "failing":
                    self.store.log(name, "health", ok=False, status=status, detail=detail)
                self.store.update(name, failures=failures, health="failing" if failing else record["health"],
                                  health_detail=detail)
                continue
            if record["health"] == "failing":
                self.store.log(name, "health", ok=True, detail="answering again")
            fields: dict[str, Any] = {"failures": 0, "health": "ok", "health_detail": "", "seen_at": now_ms()}
            status, manifest, _ = self._fetch("GET", record["endpoint"] + "/cyclone-plugin.json")
            if status == 200 and isinstance(manifest, dict) and not kit.validate_manifest(manifest):
                changed = pin_hash(manifest, record["endpoint"]) != record["pin_hash"]
                if changed and manifest != record["pending"]:
                    fields["pending"] = manifest
                    self.store.log(name, "drift", ok=False, detail="changed what it asks for; review needed")
                elif not changed:
                    if record["pending"]:
                        fields["pending"] = None
                    if manifest.get("version") != record["manifest"].get("version"):
                        fields["manifest"] = manifest
                        self.store.log(name, "version", ok=True, detail=f"now {manifest.get('version')}")
            self.store.update(name, **fields)


def _conformance(endpoint: str, key: str) -> list[Any]:
    from cyclone_ports.conformance import check_plugin

    return check_plugin(endpoint, key)


class _Check:
    def __init__(self, name: str, ok: bool, detail: str = "", required: bool = True) -> None:
        self.name, self.ok, self.detail, self.required = name, ok, detail, required


def _public_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    keep = ("contract", "name", "version", "title", "description", "endpoint", "needs", "features")
    return {k: manifest.get(k) for k in keep if k in manifest}


def _diff(current: dict[str, Any], pending: dict[str, Any]) -> dict[str, Any]:
    def ports(m: dict[str, Any]) -> dict[str, str]:
        return {s.get("port"): s.get("way") for s in m.get("serves") or [] if isinstance(s, dict)}
    now, then = ports(current), ports(pending)
    return {
        "version": pending.get("version"),
        "added": sorted(p for p in then if p not in now),
        "removed": sorted(p for p in now if p not in then),
        "needsPersonal": bool((pending.get("needs") or {}).get("personal")),
        "featuresAdded": sorted(set(pending.get("features") or []) - set(current.get("features") or [])),
        "endpointChanged": (pending.get("endpoint") or "").rstrip("/") != (current.get("endpoint") or "").rstrip("/"),
    }


def _key_card(key: str) -> dict[str, str]:
    """The key, shown once, with the lines to set it in the plugin's shell."""
    return {
        "value": key,
        "powershell": f'$env:CYCLONE_PLUGIN_SECRET = "{key}"',
        "bash": f"export CYCLONE_PLUGIN_SECRET='{key}'",
        "cmd": f"set CYCLONE_PLUGIN_SECRET={key}",
    }
