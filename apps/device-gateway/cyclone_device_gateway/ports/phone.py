"""Plan 48 run 4: the phone side of the Port Hub.

The phone never calls the PC. This bridge collects from each ready phone's port outbox (``ports.poll``) and plays its
runs' messages into the hub's traffic, exactly like a run on the PC would:
- **emit** items go to ``traffic.emit`` (a screenshot is fetched first with ``ports.blob``);
- **await** items open a wait with ``traffic.wait``; a worker follows it and answers the phone (``ports.answer``) when
  a plugin delivered, the time ran out or it failed;
- **cancel** items cancel the wait (the run stopped or gave up).

What reaches the phone:
- a **code** only as an envelope sealed to the phone's *trusted* device key (plan 33): the bridge takes it from the
  hub's memory once, seals it and forgets it. A phone whose key the owner hasn't trusted in Glass gets no code;
- a **value** or **link** as data (the contract's own secret check refuses secret-looking values, so they fail closed);
- a **file** in gallery chunks (``ports.file``), then its name.

Polling is adaptive: fast (about 1.5 s) while a phone has messages or an open wait, otherwise every 5 s. That keeps the
phone "connected" (the phone counts a poll in the last 20 s) without asking every phone every second and a half.
"""
from __future__ import annotations

import base64
import hashlib
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from . import seal
from .traffic import TrafficError

FAST_S = 1.5
IDLE_S = 5.0
RESULT_WAIT_S = 25.0
SEEN_KEEP = 500
FILE_CHUNK = 512 * 1024
PHONE_FILE_MIME = ("image/", "video/", "audio/")
TERMINAL = frozenset({"delivered", "timed_out", "cancelled", "failed", "empty", "conflict", "off", "unavailable", "refused"})


class PhoneBridge:
    """Collects port messages from ready phones and answers their runs' waits through the hub."""

    def __init__(self, hub: Any, contract: Any, devices: Callable[[], list[dict[str, Any]]],
                 trusted_key: Callable[[str], dict[str, str] | None], *,
                 clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], None] = time.sleep,
                 threaded: bool = True) -> None:
        self.hub = hub
        self.traffic = hub.traffic
        self.contract = contract
        self._devices = devices
        self._trusted_key = trusted_key
        self._clock = clock
        self._sleep = sleep
        self._threaded = threaded
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._next: dict[str, float] = {}           # device -> next poll time
        self._acks: dict[str, list[str]] = {}       # device -> item ids to acknowledge on the next poll
        self._seen: dict[str, list[str]] = {}       # device -> item ids already handled (a lost ack never runs twice)
        self._waits: dict[str, str] = {}            # phone await item id -> hub awaitId
        self._open: dict[str, int] = {}             # device -> waits still followed
        self._workers: list[threading.Thread] = []

    # ---- lifecycle ---------------------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-port-phones", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
        self._thread = None

    def _loop(self) -> None:
        while not self._stop.wait(0.25):
            try:
                self.tick()
            except Exception:  # noqa: BLE001 - one bad tick never stops the bridge
                pass

    def join(self, timeout: float = 10.0) -> None:
        """Waits for answer workers (tests)."""
        deadline = time.monotonic() + timeout
        for worker in list(self._workers):
            worker.join(max(0.0, deadline - time.monotonic()))

    # ---- polling -----------------------------------------------------------------------------------------------------

    def ready(self) -> list[str]:
        try:
            listed = self._devices()
        except Exception:  # noqa: BLE001 - discovery trouble means no phone is ready this tick
            return []
        return [str(d["deviceId"]) for d in listed if d.get("paired") and d.get("state") == "ready" and d.get("deviceId")]

    def tick(self) -> None:
        now = self._clock()
        for device in self.ready():
            if self._next.get(device, 0.0) <= now:
                busy = self.poll(device)
                with self._lock:
                    busy = busy or self._open.get(device, 0) > 0
                self._next[device] = now + (FAST_S if busy else IDLE_S)

    def poll(self, device: str) -> bool:
        """One collection from [device]. Returns True when it had messages."""
        with self._lock:
            ack = self._acks.pop(device, [])
        try:
            skills = [{k: v for k, v in s.items() if k != "schemaUrl"} for s in self.traffic.hub.id_generator.skills()]
            try:
                items = self.contract.ports_poll(device, ack, skills=skills)["items"]
            except DesktopRuntimeError as error:
                # Alpha 99 phones do not yet understand advertisements. Ordinary Ports still work on them.
                if error.code != RuntimeErrorCode.INVALID_REQUEST:
                    raise
                items = self.contract.ports_poll(device, ack)["items"]
        except DesktopRuntimeError as error:
            if not _refused(error):
                with self._lock:  # the phone didn't hear us: acknowledge again next time
                    self._acks.setdefault(device, [])[:0] = ack
                return False
            items = self._isolate(device)
        for item in items:
            self._handle(device, item)
        return bool(items)

    def _isolate(self, device: str) -> list[dict[str, Any]]:
        """The PC's own check refused a poll (a message looked secret, or malformed). Take one at a time; drop the bad one."""
        try:
            return self.contract.ports_poll(device, [], max_items=1)["items"]
        except DesktopRuntimeError as error:
            if not _refused(error):
                return []
        try:
            return self.contract.ports_poll(device, [], drop=True, max_items=1)["items"]
        except DesktopRuntimeError:
            return []

    def _handle(self, device: str, item: dict[str, Any]) -> None:
        item_id = item["id"]
        with self._lock:
            seen = self._seen.setdefault(device, [])
            self._acks.setdefault(device, []).append(item_id)
            if item_id in seen:
                return
            seen.append(item_id)
            del seen[:-SEEN_KEEP]
        meta = {"app": item.get("app"), "routine": item.get("routine"), "taskId": item.get("taskId"), "plugin": item.get("plugin")}
        kind = item["kind"]
        if kind == "emit":
            self._emit(device, item, meta)
        elif kind == "await":
            self._await(device, item, meta)
        else:
            await_id = self._waits.get(item["item"])
            if await_id is not None:
                try:
                    self.traffic.cancel(await_id, item.get("reason") or "cancelled")
                except TrafficError:
                    pass

    # ---- out ports ---------------------------------------------------------------------------------------------------

    def _emit(self, device: str, item: dict[str, Any], meta: dict[str, Any]) -> None:
        data = dict(item.get("data") or {})
        page_key = data.pop("pageKey", None)
        file = None
        if item.get("blob"):
            raw = self._blob(device, item["id"])
            if raw is None:
                return
            file = {"base64": base64.b64encode(raw).decode(), "mime": item["blob"]["mime"]}
        try:
            self.traffic.emit(item["runId"], item["port"], data, meta, file=file, page_key=page_key)
        except TrafficError:
            pass  # the hub logged why; an out message never blocks a run

    def _blob(self, device: str, item_id: str) -> bytes | None:
        out, offset = bytearray(), 0
        for _ in range(64):
            try:
                chunk = self.contract.ports_blob(device, item_id, offset)
            except DesktopRuntimeError:
                return None
            data = base64.b64decode(chunk["data"])
            out += data
            offset += len(data)
            if chunk["done"] or not data:
                return bytes(out) if len(out) == chunk["bytes"] else None
        return None

    # ---- in ports ----------------------------------------------------------------------------------------------------

    def _await(self, device: str, item: dict[str, Any], meta: dict[str, Any]) -> None:
        match = dict(item.get("match") or {})
        try:
            opened = self.traffic.wait(item["runId"], item["port"], match, item["timeoutS"], meta)
        except TrafficError as error:
            self._answer(device, item["id"], "failed", reason=str(error)[:200])
            return
        if opened["state"] != "waiting":
            self._answer(device, item["id"], opened["state"], reason=opened.get("reason"))
            return
        self._waits[item["id"]] = opened["awaitId"]
        with self._lock:
            self._open[device] = self._open.get(device, 0) + 1
        follow = lambda: self._follow(device, item, opened["awaitId"], opened.get("plugin"))  # noqa: E731
        if self._threaded:
            worker = threading.Thread(target=follow, name="cyclone-port-wait", daemon=True)
            self._workers.append(worker)
            worker.start()
        else:
            follow()

    def _follow(self, device: str, item: dict[str, Any], await_id: str, plugin: str | None) -> None:
        try:
            while not self._stop.is_set():
                result = self.traffic.result(await_id, RESULT_WAIT_S)
                if result["state"] in TERMINAL:
                    self._finish(device, item, await_id, result)
                    return
        except TrafficError:
            self._answer(device, item["id"], "failed", reason="the wait was lost", plugin=plugin)
        finally:
            with self._lock:
                self._open[device] = max(0, self._open.get(device, 0) - 1)
            self._waits.pop(item["id"], None)

    def _finish(self, device: str, item: dict[str, Any], await_id: str, result: dict[str, Any]) -> None:
        plugin = result.get("plugin")
        state = result["state"]
        if state != "delivered":
            self._answer(device, item["id"], state, reason=result.get("reason"), plugin=plugin)
            return
        port = item["port"]
        if port == "code.in":
            self._deliver_code(device, item, await_id, plugin)
        elif port == "value.in":
            if "value" not in result:
                self._answer(device, item["id"], "failed", reason="the value is no longer held", plugin=plugin)
                return
            try:
                self.contract.ports_answer(device, item["id"], "delivered", plugin=plugin, value=result["value"], has_value=True)
            except DesktopRuntimeError:
                # The PC's own secret check refused it: a value that looks like a secret never reaches the phone.
                self._answer(device, item["id"], "failed", reason="the value looked like a secret, so it wasn't passed on", plugin=plugin)
        elif port == "link.in":
            url = result.get("url")
            if not isinstance(url, str) or not url.startswith("https://"):
                self._answer(device, item["id"], "failed", reason="the link was not https", plugin=plugin)
                return
            try:
                self.contract.ports_answer(device, item["id"], "delivered", plugin=plugin, url=url)
            except DesktopRuntimeError:
                self._answer(device, item["id"], "failed", reason="the link was refused", plugin=plugin)
        elif port == "file.in":
            self._deliver_file(device, item, result, plugin)
        else:
            self._answer(device, item["id"], "failed", reason=f"{port} isn't delivered to phones", plugin=plugin)

    def _deliver_code(self, device: str, item: dict[str, Any], await_id: str, plugin: str | None) -> None:
        code = self.traffic.take_code(await_id)
        if code is None:
            self._answer(device, item["id"], "failed", reason="the code is no longer held", plugin=plugin)
            return
        key = self._trusted_key(device)
        if not key:
            code = ""
            self._answer(device, item["id"], "failed", plugin=plugin,
                         reason="this phone's key isn't trusted yet: compare its fingerprint in Glass (Command Center → Phones)")
            return
        try:
            sealed = seal.code_envelope(key["publicKey"], key["fingerprint"], item["runId"], item["place"], code)
        except (ValueError, KeyError):
            sealed = None
        finally:
            code = ""  # the bridge forgets the code at once; only the sealed envelope travels
        if sealed is None:
            self._answer(device, item["id"], "failed", reason="the code couldn't be sealed to this phone", plugin=plugin)
            return
        try:
            self.contract.ports_answer(device, item["id"], "delivered", plugin=plugin, sealed=sealed)
        except DesktopRuntimeError:
            pass  # the phone's own wait times out; the code is gone either way

    def _deliver_file(self, device: str, item: dict[str, Any], result: dict[str, Any], plugin: str | None) -> None:
        name, mime = str(result.get("name") or ""), str(result.get("mime") or "")
        path = Path(self.traffic.root) / "runs" / item["runId"] / "files" / name
        if not name or not path.is_file():
            self._answer(device, item["id"], "failed", reason="the file isn't on the PC any more", plugin=plugin)
            return
        if not mime.startswith(PHONE_FILE_MIME):
            self._answer(device, item["id"], "failed", reason="only images, video and audio reach the phone for now", plugin=plugin)
            return
        blob = path.read_bytes()
        sha = hashlib.sha256(blob).hexdigest()
        received: dict[str, Any] | None = None
        try:
            for offset in range(0, len(blob), FILE_CHUNK):
                received = self.contract.ports_file(device, item["id"], name=name, mime=mime, size=len(blob), sha256=sha,
                                                    offset=offset, data=blob[offset:offset + FILE_CHUNK])
        except DesktopRuntimeError:
            self._answer(device, item["id"], "failed", reason="the file didn't reach the phone", plugin=plugin)
            return
        if not received or not received.get("done"):
            self._answer(device, item["id"], "failed", reason="the file didn't arrive whole", plugin=plugin)
            return
        file = {"name": received.get("name") or name, "folder": received.get("folder"), "mime": mime, "bytes": len(blob)}
        try:
            self.contract.ports_answer(device, item["id"], "delivered", plugin=plugin, file=file)
        except DesktopRuntimeError:
            pass

    def _answer(self, device: str, item_id: str, state: str, *, reason: str | None = None, plugin: str | None = None) -> None:
        try:
            self.contract.ports_answer(device, item_id, state, reason=reason, plugin=plugin)
        except DesktopRuntimeError:
            pass  # the phone's own deadline covers a lost answer


def _refused(error: DesktopRuntimeError) -> bool:
    """The PC's own checks refused what the phone sent (secret-looking, or malformed), not a transport problem."""
    from ..desktop_runtime.models import RuntimeErrorCode
    return "Secret-bearing" in str(error) or error.code in (str(RuntimeErrorCode.PROTOCOL_MISMATCH), RuntimeErrorCode.PROTOCOL_MISMATCH.value)
