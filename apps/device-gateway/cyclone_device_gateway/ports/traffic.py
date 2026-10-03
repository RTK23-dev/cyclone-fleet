"""Port traffic (plan 48, run 3): messages from runs to plugins, and plugins answering runs that wait.

**Out ports** follow the Port map (``bindings``) for the run's routine and app:
- one envelope per run message with a unique ``id``, sent to each plugin it routes to;
- screenshots and files go as one-time artifact links;
- up to three attempts with backoff (honouring ``Retry-After``). A failure is logged and never blocks the run.

**In ports** take one plugin's answer:
- the wait is saved with its request, so a gateway restart re-sends the same ``awaitId`` and token and the run keeps
  waiting;
- the plugin delivers to ``/v1/ports/{runId}/{port}/deliver`` with the run's port token. The status codes are the
  contract's (401, 403, 404, 409, 410, 413, 422);
- a repeat with the same ``deliveryId`` is answered 200 again.

**What is kept:**
- the database and the activity log keep metadata only: who, which port, the state, sizes;
- a delivered **code**, value or link stays in memory until the run takes it, for at most 5 minutes. A code is never
  written anywhere; run 4 seals it to the phone;
- a delivered file is saved in the run's folder.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import secrets
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

from . import kit
from .store import now_ms

if TYPE_CHECKING:  # pragma: no cover
    from .hub import PortHub

RUN_ID = re.compile(r"^[A-Za-z0-9_-]{4,80}$")
ATTEMPTS = 3
ARTIFACT_TTL_S = 120
HELD_TTL_S = 300
MAX_FILE = 20 * 1024 * 1024
TOKEN_FIELD = "token"


class TrafficError(ValueError):
    """A refused run request; the message is for the owner (or the run's log)."""


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Traffic:
    def __init__(self, hub: "PortHub", root: Path, base_url: str, *, sleep: Callable[[float], None] = time.sleep) -> None:
        self.hub = hub
        self.store = hub.store
        self.root = root
        self.base_url = base_url.rstrip("/")
        self._sleep = sleep
        self._pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="cyclone-port-send")
        self._lock = threading.Lock()
        self._seq: dict[str, int] = {}
        self._artifacts: dict[str, dict[str, Any]] = {}
        self._held: dict[str, dict[str, Any]] = {}       # awaitId -> {"value"|"code"|"url": …, "until": ms}; memory only
        self._events: dict[str, threading.Event] = {}
        self._pending: list[Any] = []

    # ---- lifecycle ---------------------------------------------------------------------------------------------------

    def resume(self) -> None:
        """After a restart: re-send each open wait with its own awaitId and token; time out the expired ones."""
        for wait in self.store.waits(state="waiting"):
            if now_ms() >= wait["timeoutAt"]:
                self._time_out(wait)
                continue
            token = self.store.wait_token(wait["awaitId"])
            if token is None:  # memory-only keys after a restart: the run can't be answered any more
                self.store.finish_wait(wait["awaitId"], "cancelled", {"reason": "restart"})
                continue
            self._events.setdefault(wait["awaitId"], threading.Event())
            future = self._pool.submit(self._ask, wait, token, True)
            with self._lock:
                self._pending.append(future)

    def tick(self) -> None:
        """Times out expired waits and forgets expired artifacts and held values. Called about once a second."""
        now = now_ms()
        for wait in self.store.waits(state="waiting"):
            if now >= wait["timeoutAt"]:
                self._time_out(wait)
        with self._lock:
            for key in [k for k, a in self._artifacts.items() if a["until"] < now]:
                del self._artifacts[key]
            for key in [k for k, h in self._held.items() if h["until"] < now]:
                del self._held[key]

    def stop(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    def flush(self, timeout: float = 15.0) -> None:
        """Waits for queued sends (tests and test runs)."""
        deadline = time.monotonic() + timeout
        while True:
            with self._lock:
                pending = [f for f in self._pending if not f.done()]
                self._pending = pending
            if not pending or time.monotonic() > deadline:
                return
            time.sleep(0.02)

    # ---- helpers -----------------------------------------------------------------------------------------------------

    @staticmethod
    def _run(run_id: Any, meta: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(run_id, str) or not RUN_ID.match(run_id):
            raise TrafficError("That isn't a run id.")
        out = {"runId": run_id}
        for key in ("taskId", "rowId", "app", "routine", "plugin"):
            value = meta.get(key)
            if value is not None and not (isinstance(value, str) and len(value) <= 160):
                raise TrafficError(f"{key} must be text.")
            out[key] = value
        if out.get("plugin") is not None and not re.fullmatch(r"[a-z][a-z0-9-]{1,40}", out["plugin"]):
            raise TrafficError("plugin must be a registered plugin name.")
        return out

    def _route(self, run: dict[str, Any], port: str) -> dict[str, Any]:
        try:
            resolved = self.hub.resolve(run.get("routine"), run.get("app"))
        except Exception as exc:  # noqa: BLE001 - a bad routine or app id is the caller's mistake
            raise TrafficError(str(exc)) from exc
        row = next((r for r in resolved["ports"] if r["port"] == port), None)
        if row is None:
            raise TrafficError(f"{port} isn't a port Cyclone knows.")
        target = run.get("plugin")
        if target:
            candidate = next((c for c in row["candidates"] if c["name"] == target and c["live"]), None)
            # A target may resolve an automatic conflict, but never override the owner's explicit choices.
            allowed = row["chosen"] is None or target in row["chosen"]
            row = dict(row, effective=[target] if candidate and allowed else [],
                       state="ok" if candidate and allowed else "unavailable")
        return row

    def _next_seq(self, run_id: str) -> int:
        with self._lock:
            self._seq[run_id] = self._seq.get(run_id, 0) + 1
            return self._seq[run_id]

    def _artifact(self, blob: bytes, mime: str) -> str:
        artifact_id, token = "art_" + secrets.token_hex(8), secrets.token_urlsafe(20)
        with self._lock:
            self._artifacts[artifact_id] = {"data": blob, "mime": mime, "token": token, "used": False,
                                            "until": now_ms() + ARTIFACT_TTL_S * 1000}
        return f"{self.base_url}/v1/ports/artifacts/{artifact_id}?t={token}"

    def artifact(self, artifact_id: str, token: str) -> tuple[int, bytes, str]:
        with self._lock:
            art = self._artifacts.get(artifact_id)
            if art is None or not secrets.compare_digest(art["token"], token or ""):
                return 404, b"", "text/plain"
            if art["used"] or art["until"] < now_ms():
                return 410, b"", "text/plain"
            art["used"] = True
            data, art["data"] = art["data"], b""
        return 200, data, art["mime"]

    def _signed(self, plugin: str, port: str, action: str, body: dict[str, Any]) -> tuple[int, int, float | None]:
        """One signed POST to a plugin: (status, latency ms, Retry-After seconds)."""
        record = self.store.plugin(plugin)
        key = self.store.key(plugin)
        if record is None or key is None:
            return 0, 0, None
        prefix = urllib.parse.urlsplit(record["endpoint"]).path
        raw = json.dumps(body).encode()
        headers = {"Content-Type": "application/json", kit.CONTRACT_HEADER: kit.CONTRACT,
                   kit.SIGNATURE_HEADER: kit.sign(key, "POST", f"{prefix}/ports/{port}{action}", raw),
                   kit.TRACE_HEADER: f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"}
        status, answer, latency = self.hub._fetch("POST", f"{record['endpoint']}/ports/{port}{action}", raw, headers, 10.0)
        retry = None
        if isinstance(answer, dict) and isinstance(answer.get("retryAfter"), (int, float)):
            retry = float(answer["retryAfter"])
        return status, latency, retry

    def _with_retries(self, plugin: str, port: str, action: str, body: dict[str, Any]) -> tuple[int, int, int]:
        status, latency, attempt = 0, 0, 0
        for attempt in range(1, ATTEMPTS + 1):
            status, latency, retry_after = self._signed(plugin, port, action, body)
            if status not in (0, 429, 500, 502, 503, 504) or attempt == ATTEMPTS:
                break
            self._sleep(min(retry_after if retry_after is not None else 0.5 * 4 ** (attempt - 1), 10.0))
        return status, latency, attempt

    # ---- out ports ---------------------------------------------------------------------------------------------------

    def emit(self, run_id: Any, port: Any, data: Any = None, meta: dict[str, Any] | None = None,
             file: dict[str, Any] | None = None, page_key: str | None = None) -> dict[str, Any]:
        run = self._run(run_id, meta or {})
        if not isinstance(port, str):
            raise TrafficError("Name the port.")
        row = self._route(run, port)
        if row["way"] != "out":
            raise TrafficError(f"{port} brings something to the run; wait on it instead.")
        if not row["pluginServed"]:
            raise TrafficError(f"{port} is handled by the hub and the vault, not plugins.")
        data = dict(data or {}) if isinstance(data, dict) or data is None else None
        if data is None:
            raise TrafficError("data must be an object.")
        blob, mime = None, "image/png"
        if port in ("screen.shot", "file.out"):
            if not isinstance(file, dict) or not isinstance(file.get("base64"), str):
                raise TrafficError(f"{port} needs the file (base64).")
            try:
                blob = base64.b64decode(file["base64"], validate=True)
            except ValueError as exc:
                raise TrafficError("The file isn't valid base64.") from exc
            if len(blob) > MAX_FILE:
                raise TrafficError("The file is larger than 20 MB.")
            mime = str(file.get("mime") or mime)[:80]
            data = {"mime": mime, "bytes": len(blob), **data}
        spec = kit.port_spec(port, "out")
        base = {"v": 1, "runId": run["runId"], "taskId": run.get("taskId"), "rowId": run.get("rowId"),
                "port": port, "way": "out", "seq": self._next_seq(run["runId"]), "sentAt": _now_iso(),
                "app": run.get("app"), "pageKey": page_key, "sensitivity": spec.sensitivity if spec else "personal",
                "data": data, "id": "msg_" + secrets.token_hex(10)}
        check = dict(base, artifactUrl="https://example.invalid/x") if blob is not None else base
        problems = kit.validate_envelope(check, strict=True)
        if problems:
            raise TrafficError("; ".join(problems))
        targets = list(row["effective"])
        if not targets:
            self.store.log("ports", "emit", ok=row["state"] in ("off", "empty"), port=port, run_id=run["runId"],
                           detail={"off": "switched off here", "empty": "no plugin serves it"}.get(row["state"], row["state"]))
            return {"messageId": base["id"], "sentTo": [], "state": row["state"]}
        for plugin in targets:
            envelope = dict(base)
            if blob is not None:
                envelope["artifactUrl"] = self._artifact(blob, mime)
            future = self._pool.submit(self._deliver_out, plugin, port, envelope)
            with self._lock:
                self._pending.append(future)
        return {"messageId": base["id"], "sentTo": targets, "state": row["state"]}

    def _deliver_out(self, plugin: str, port: str, envelope: dict[str, Any]) -> None:
        status, latency, attempts = self._with_retries(plugin, port, "", envelope)
        ok = 200 <= status < 300
        detail = "delivered" if ok else ("unreachable" if status == 0 else f"answered {status}")
        if attempts > 1:
            detail += f" after {attempts} tries"
        self.store.log(plugin, "emit", ok=ok, port=port, run_id=envelope["runId"], status=status,
                       latency_ms=latency, detail=detail)

    # ---- in ports ----------------------------------------------------------------------------------------------------

    def wait(self, run_id: Any, port: Any, match: Any = None, timeout_s: Any = 120,
             meta: dict[str, Any] | None = None) -> dict[str, Any]:
        run = self._run(run_id, meta or {})
        if not isinstance(port, str):
            raise TrafficError("Name the port.")
        row = self._route(run, port)
        if row["way"] != "in":
            raise TrafficError(f"{port} goes from the run to plugins; send on it instead.")
        if not row["pluginServed"]:
            raise TrafficError(f"{port} is handled by the hub and the vault, not plugins.")
        if row["state"] != "ok" or len(row["effective"]) != 1:
            self.store.log("ports", "await", ok=False, port=port, run_id=run["runId"], detail=_why(row["state"]))
            return {"state": row["state"], "reason": _why(row["state"])}
        if match is not None and not isinstance(match, dict):
            raise TrafficError("match must be an object.")
        try:
            timeout = max(5.0, min(float(timeout_s), float(kit.LIMITS["await_timeout_max_s"])))
        except (TypeError, ValueError) as exc:
            raise TrafficError("timeoutS must be a number of seconds.") from exc
        plugin = row["effective"][0]
        await_id, token = "aw_" + secrets.token_hex(8), secrets.token_urlsafe(24)
        request = {"v": 1, "runId": run["runId"], "taskId": run.get("taskId"), "rowId": run.get("rowId"),
                   "app": run.get("app"), "routine": run.get("routine"), "port": port, "awaitId": await_id, "match": match or {}, "timeoutS": timeout,
                   "sentAt": _now_iso(), "deliverUrl": f"{self.base_url}/v1/ports/{run['runId']}/{port}/deliver"}
        wait = {"awaitId": await_id, "runId": run["runId"], "port": port, "way": row["way"], "plugin": plugin,
                "request": request, "timeoutAt": now_ms() + int(timeout * 1000)}
        self.store.put_wait(wait, token)
        self._events[await_id] = threading.Event()
        future = self._pool.submit(self._ask, wait, token, False)
        with self._lock:
            self._pending.append(future)
        return {"state": "waiting", "awaitId": await_id, "plugin": plugin, "timeoutAt": wait["timeoutAt"]}

    def _ask(self, wait: dict[str, Any], token: str, again: bool) -> None:
        body = dict(wait["request"], token=token)
        status, latency, attempts = self._with_retries(wait["plugin"], wait["port"], "/await", body)
        ok = 200 <= status < 300
        self.store.log(wait["plugin"], "await", ok=ok, port=wait["port"], run_id=wait["runId"], status=status,
                       latency_ms=latency, detail=("asked again after a restart" if again else "asked")
                       if ok else ("unreachable" if status == 0 else f"answered {status}"))
        if not ok and self.store.finish_wait(wait["awaitId"], "failed", {"reason": "the plugin didn't take the wait"}):
            self._wake(wait["awaitId"])

    def _time_out(self, wait: dict[str, Any]) -> None:
        if not self.store.finish_wait(wait["awaitId"], "timed_out", {"reason": "nothing came in time"}):
            return
        self._wake(wait["awaitId"])
        self.store.log(wait["plugin"], "timeout", ok=False, port=wait["port"], run_id=wait["runId"],
                       detail="nothing came in time")
        self._pool.submit(self._signed, wait["plugin"], wait["port"], "/cancel",
                          {"v": 1, "runId": wait["runId"], "port": wait["port"], "awaitId": wait["awaitId"],
                           "reason": "timeout"})

    def cancel(self, await_id: Any, reason: str = "cancelled") -> dict[str, Any]:
        wait = self.store.wait(await_id) if isinstance(await_id, str) else None
        if wait is None:
            raise TrafficError("No such wait.")
        if self.store.finish_wait(await_id, "cancelled", {"reason": reason}):
            self._wake(await_id)
            self.store.log(wait["plugin"], "cancel", ok=True, port=wait["port"], run_id=wait["runId"], detail=reason)
            self._pool.submit(self._signed, wait["plugin"], wait["port"], "/cancel",
                              {"v": 1, "runId": wait["runId"], "port": wait["port"], "awaitId": await_id,
                               "reason": reason})
        return self.result(await_id)

    def _wake(self, await_id: str) -> None:
        event = self._events.get(await_id)
        if event is not None:
            event.set()

    def result(self, await_id: Any, wait_s: float = 0) -> dict[str, Any]:
        """The run's view of a wait. With wait_s, holds the request until something happens (long poll).

        For code.in it says the code arrived and how long it is, never the code: run 4 seals it to the phone."""
        wait = self.store.wait(await_id) if isinstance(await_id, str) else None
        if wait is None:
            raise TrafficError("No such wait.")
        if wait["state"] == "waiting" and wait_s > 0:
            event = self._events.setdefault(await_id, threading.Event())
            event.wait(min(float(wait_s), 30.0))
            wait = self.store.wait(await_id) or wait
        if wait["plugin"] == "id-generator" and wait["state"] in ("waiting", "delivered") and not self._generator_allowed(wait):
            if wait["state"] == "waiting":
                return self.cancel(await_id, "plugin permission or usage scope changed")
            self.store.finish_wait(await_id, "cancelled", {"reason": "plugin permission or usage scope changed"}, only_if_waiting=False)
            with self._lock:
                self._held.pop(await_id, None)
            wait = self.store.wait(await_id) or wait
        out = {"awaitId": await_id, "runId": wait["runId"], "port": wait["port"], "plugin": wait["plugin"],
               "state": wait["state"], "timeoutAt": wait["timeoutAt"], **(wait["result"] or {})}
        held = self._held.get(await_id)
        if held is not None and wait["state"] == "delivered":
            if "value" in held:
                out["value"] = held["value"]
            if "url" in held:
                out["url"] = held["url"]
        return out

    def _generator_allowed(self, wait: dict[str, Any]) -> bool:
        request = wait["request"]
        try:
            route = self._route({"app": request.get("app"), "routine": request.get("routine"), "plugin": wait["plugin"]}, wait["port"])
            return wait["plugin"] in route["effective"]
        except TrafficError:
            return False

    def take_code(self, await_id: str) -> str | None:
        """Run 4's sealed delivery takes the code once; it is gone from memory afterwards."""
        with self._lock:
            held = self._held.pop(await_id, None)
        return held.get("code") if held else None

    # ---- plugin -> hub -------------------------------------------------------------------------------------------------

    def deliver(self, run_id: str, port: str, authorization: str | None, body: Any) -> tuple[int, dict[str, Any]]:
        token = authorization[5:].strip() if authorization and authorization.startswith("Port ") else ""
        waits = [w for w in self.store.waits(run_id=run_id, limit=500) if w["port"] == port]
        if not waits:
            return 404, kit.error_body("no_waiting_run")
        wait = next((w for w in waits if token and secrets.compare_digest(self.store.wait_token(w["awaitId"]) or "", token)), None)
        if wait is None:
            # A finished wait's token is gone. A repeat of the delivery that finished it gets the same answer;
            # anything else for a finished wait is 409 (answered) or 410 (expired). With a wait still open, 401.
            delivery_id = body.get("deliveryId") if isinstance(body, dict) else None
            if any(w["state"] == "waiting" for w in waits):
                return 401, kit.error_body("bad_token")
            done = next((w for w in waits if w["state"] == "delivered" and delivery_id and w["deliveryId"] == delivery_id), None)
            if done is not None:
                return 200, {"accepted": True, "awaitId": done["awaitId"], "duplicate": True}
            if any(w["state"] == "delivered" for w in waits):
                return 409, kit.error_body("already_delivered")
            return 410, kit.error_body("expired")
        if now_ms() >= wait["timeoutAt"]:
            self._time_out(wait)
            return 410, kit.error_body("expired")
        if wait["plugin"] == "id-generator":
            if not self._generator_allowed(wait):
                self.cancel(wait["awaitId"], "plugin permission or usage scope changed")
                return 410, kit.error_body("expired")
        problems = kit.validate_delivery(port, body, wait["way"])
        if problems:
            return 422, kit.error_body("invalid", "; ".join(problems), problems=problems)
        held: dict[str, Any] = {"until": now_ms() + HELD_TTL_S * 1000}
        if port == "code.in":
            result = {"codeLength": len(body["code"]), "source": body["source"][:80]}
            held["code"] = body["code"]
            note = f"code received ({len(body['code'])} characters), held for the phone"
        elif port == "link.in":
            host = urllib.parse.urlsplit(body["url"]).hostname or ""
            result = {"host": host, "source": body["source"][:80]}
            held["url"] = body["url"]
            note = f"link to {host}"
        elif port == "file.in":
            try:
                blob = base64.b64decode(body["base64"], validate=True)
            except ValueError:
                return 422, kit.error_body("invalid", "base64 is not valid")
            if hashlib.sha256(blob).hexdigest() != body["sha256"].lower():
                return 422, kit.error_body("invalid", "sha256 does not match the file")
            name = re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(body["name"]))[:120] or "file"
            folder = self.root / "runs" / run_id / "files"
            folder.mkdir(parents=True, exist_ok=True)
            (folder / name).write_bytes(blob)
            result = {"name": name, "mime": body["mime"][:80], "bytes": len(blob)}
            note = f"{name}, {len(blob)} bytes"
        else:
            raw = json.dumps(body["value"])
            result = {"bytes": len(raw.encode())}
            held["value"] = body["value"]
            note = f"value, {result['bytes']} bytes"
        if not self.store.finish_wait(wait["awaitId"], "delivered", result, body.get("deliveryId")):
            return 409, kit.error_body("already_delivered")
        with self._lock:
            if len(held) > 1:
                self._held[wait["awaitId"]] = held
        self._wake(wait["awaitId"])
        self.store.log(wait["plugin"], "deliver", ok=True, port=port, run_id=run_id, status=200, detail=note)
        return 200, {"accepted": True, "awaitId": wait["awaitId"]}


def _why(state: str) -> str:
    return {
        "empty": "no live plugin serves this port",
        "conflict": "two plugins could answer; choose one on the Port map",
        "off": "this port is switched off here",
        "unavailable": "the chosen plugin isn't live",
    }.get(state, state)


# ---- test runs: the owner tries plugins end to end from Glass, before any phone run uses them -----------------------------

SCENARIOS: dict[str, dict[str, Any]] = {
    "events": {"title": "Run events", "steps": [
        ("emit", "run.event", {"stage": "started"}), ("emit", "log.line", {"text": "Test run from Cyclone Glass"}),
        ("emit", "run.event", {"stage": "done"})]},
    "signup": {"title": "Sign-up with a code", "steps": [
        ("emit", "run.event", {"stage": "started"}),
        ("emit", "account.fields", {"fields": {"name": "Sam Example", "dateOfBirth": "1999-04-02", "email": "sam@example.com",
                                               "username": "sam.example"}}),
        ("emit", "screen.shot", {}), ("emit", "log.line", {"text": "Waiting for the verification code"}),
        ("await", "code.in", {"from": "Example", "pattern": r"\b\d{6}\b"}),
        ("emit", "run.event", {"stage": "created"}), ("emit", "run.event", {"stage": "done"})]},
    "image": {"title": "An image from the PC", "steps": [
        ("emit", "run.event", {"stage": "started"}), ("await", "file.in", {"kind": "image"}),
        ("emit", "log.line", {"text": "Profile photo received"}), ("emit", "run.event", {"stage": "done"})]},
    "value": {"title": "A value from a plugin", "steps": [
        ("emit", "run.event", {"stage": "started"}), ("await", "value.in", {"ask": "a caption"}),
        ("emit", "run.event", {"stage": "done"})]},
}
# A 1x1 PNG, the test run's "screenshot".
TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="


def _step_label(kind: str, port: str, data: dict[str, Any]) -> str:
    names = {"run.event": "run event", "log.line": "note", "screen.shot": "screenshot", "account.fields": "account details",
             "code.in": "a verification code", "file.in": "an image", "value.in": "a value", "link.in": "a link"}
    if kind == "await":
        return f"Wait for {names.get(port, port)}"
    if port == "run.event":
        return f"Send “{data.get('stage', 'event')}”"
    return f"Send {names.get(port, port)}"


class TestRuns:
    """Plays a fixed scenario through the real hub (routes, signing, waits, deliveries), step by step, so the owner can
    watch it in Glass. Test data only (Sam Example); a test run id starts with ``run_test_``."""

    def __init__(self, traffic: Traffic) -> None:
        self.traffic = traffic
        self._runs: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()

    def start(self, scenario: Any, routine: Any = None, app: Any = None, timeout_s: float = 120) -> dict[str, Any]:
        if scenario not in SCENARIOS:
            raise TrafficError("Choose a test: " + ", ".join(SCENARIOS))
        for value in (routine, app):
            if value is not None and not isinstance(value, str):
                raise TrafficError("routine and app must be text.")
        run_id = "run_test_" + secrets.token_hex(4)
        steps = [{"kind": k, "port": p, "state": "next", "detail": "", "label": _step_label(k, p, d)}
                 for k, p, d in SCENARIOS[scenario]["steps"]]
        run = {"runId": run_id, "scenario": scenario, "title": SCENARIOS[scenario]["title"], "state": "running",
               "routine": routine or None, "app": app or None, "steps": steps, "startedAt": now_ms(), "awaitId": None}
        with self._lock:
            self._runs[run_id] = run
            for old in sorted(self._runs, key=lambda k: self._runs[k]["startedAt"])[:-20]:
                del self._runs[old]
        self.traffic.store.log("ports", "run", ok=True, run_id=run_id, detail=f"test run: {run['title']}")
        threading.Thread(target=self._play, args=(run, timeout_s), name="cyclone-port-test-run", daemon=True).start()
        return self.get(run_id)

    def get(self, run_id: str) -> dict[str, Any]:
        with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise TrafficError("No such test run.")
            return json.loads(json.dumps(run))

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            return [json.loads(json.dumps(r)) for r in sorted(self._runs.values(), key=lambda r: -r["startedAt"])]

    def stop(self, run_id: str) -> dict[str, Any]:
        run = self._runs.get(run_id)
        if run is None:
            raise TrafficError("No such test run.")
        run["state"] = "stopped"
        if run.get("awaitId"):
            try:
                self.traffic.cancel(run["awaitId"], "stopped")
            except TrafficError:
                pass
        return self.get(run_id)

    def _play(self, run: dict[str, Any], timeout_s: float) -> None:
        meta = {"routine": run["routine"], "app": run["app"]}
        for index, (kind, port, data) in enumerate(SCENARIOS[run["scenario"]]["steps"]):
            step = run["steps"][index]
            if run["state"] != "running":
                step["state"] = "skipped"
                continue
            step["state"] = "now"
            try:
                if kind == "emit":
                    file = {"base64": TINY_PNG, "mime": "image/png"} if port == "screen.shot" else None
                    sent = self.traffic.emit(run["runId"], port, data, meta, file, "test:page" if file else None)
                    self.traffic.flush(20)
                    if not sent["sentTo"]:
                        step.update(state="skipped", detail=_why(sent["state"]))
                        continue
                    failed = self._failed(run["runId"], port, len(sent["sentTo"]))
                    step.update(state="bad" if failed else "ok",
                                detail=("to " + ", ".join(self._title(n) for n in sent["sentTo"]))
                                + (f"; {failed} not delivered" if failed else ""))
                else:
                    asked = self.traffic.wait(run["runId"], port, data, timeout_s, meta)
                    if asked["state"] != "waiting":
                        step.update(state="bad", detail=asked.get("reason", asked["state"]))
                        run["state"] = "needs_you"
                        continue
                    run["awaitId"] = asked["awaitId"]
                    step.update(detail=f"waiting on {self._title(asked['plugin'])}")
                    outcome = self.traffic.result(asked["awaitId"])
                    while outcome["state"] == "waiting" and run["state"] == "running":
                        outcome = self.traffic.result(asked["awaitId"], wait_s=1.0)
                    run["awaitId"] = None
                    if outcome["state"] != "delivered":
                        step.update(state="bad", detail=outcome.get("reason", outcome["state"]))
                        if run["state"] == "running":
                            run["state"] = "needs_you"
                        continue
                    if port == "code.in":
                        self.traffic.take_code(asked["awaitId"])  # a test run never uses the code: drop it at once
                        detail = f"a {outcome.get('codeLength')}-character code from {outcome.get('source')}; would be sealed to the phone"
                    elif port == "file.in":
                        detail = f"{outcome.get('name')} ({outcome.get('bytes')} bytes) saved in the run's folder"
                    elif port == "link.in":
                        detail = f"a link to {outcome.get('host')}"
                    else:
                        detail = f"a value ({outcome.get('bytes')} bytes)"
                    step.update(state="ok", detail=detail)
            except TrafficError as exc:
                step.update(state="bad", detail=str(exc))
        if run["state"] == "running":
            run["state"] = "done" if all(s["state"] in ("ok", "skipped") for s in run["steps"]) else "needs_you"
        run["finishedAt"] = now_ms()

    def _title(self, name: str) -> str:
        record = self.traffic.store.plugin(name)
        return (record["manifest"].get("title") or name) if record else name

    def _failed(self, run_id: str, port: str, sent: int) -> int:
        rows = self.traffic.store.activity(sent * 4, run_id=run_id, port=port, kinds=("emit",))
        return len([r for r in rows[:sent] if not r["ok"] and r["plugin"] != "ports"])
