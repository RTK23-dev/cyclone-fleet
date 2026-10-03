"""One command, many phones - on top of the Command Center, not beside it.

The Command Center (command/center.py) already does the hard, safety-critical part:
it hands a goal to one phone's own Mind (the phone's model and key, its GATE and Owner
Moments, its verification), keeps one task per phone, runs different phones in
parallel, waits - and resumes on its own - when a phone is locked, busy or offline, and
puts approvals in one inbox. "Done" is what the phone reports.

This module adds only what was missing above it:
  * a Mission: the per-phone tasks of one command, tracked and reported as one result;
  * dispatch: validate each named phone, then create one Command Center task per phone;
  * partial failure that does not discard the phones that did succeed;
  * cancel a mission (only that mission's tasks), stop fleet missions, emergency stop-all.

It never talks to a phone and never holds an API key. It cannot mutate a phone; it can only
ask the Command Center to create, watch or cancel a task.

Phase 1 changes (alpha.95.dev1):
  * Non-blocking dispatch: a nudge asks the Command Center loop to tick. tick() is not
    safe to run beside that loop (sweep() sits outside the CC lock), so the nudge wakes
    the loop when it is running and otherwise uses one coalesced worker. HTTP dispatch
    does not wait for the tick.
  * Scoped stop: stop_fleet_missions() cancels only tasks belonging to known fleet
    missions; stop_all() remains the emergency "cancel everything" path. Both are exposed
    in the API with distinct routes and distinct Glass labels.
  * SQLite store: missions are persisted in fleet_store.FleetStore (indexed, paginated,
    retention policy). Legacy missions.json is migrated automatically on first start.
    Age cleanup uses this orchestrator's clock and never deletes a mission that is still
    open in the Command Center.
  * Idempotency: client_request_id deduplication works across restarts and across
    parallel submits because the mission id is reserved and stored before any task is created.
  * Memory: only a recent cache is kept. mission() falls back to the store. missions()
    is paginated with a cursor.
"""
from __future__ import annotations

import logging
import os
import queue
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Callable

from .fleet_command import Assignment, KnownDevice, KnownGroup, Plan, build_directory, split_command
from .fleet_store import FleetStore
from .models import FleetEventType


def _cap(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


MAX_ASSIGNMENTS = _cap("FLEET_MAX_PER_COMMAND", 16)
MAX_PHONES = _cap("FLEET_MAX_PHONES", 32)
CACHE_LIMIT = 200
TRIM_EVERY_MS = 60_000
OPEN_TASK_LIMIT = 5000
log = logging.getLogger("cyclone.fleet")

# Command Center task state -> what the person sees for one phone.
_PHONE_STATE = {
    "scheduled": "QUEUED", "making": "QUEUED", "waiting_device": "WAITING", "running": "RUNNING",
    "needs_you": "NEEDS_YOU", "succeeded": "COMPLETED", "failed": "FAILED", "cancelled": "CANCELLED",
}
_TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}
_CC_OPEN = {"scheduled", "making", "waiting_device", "running", "needs_you"}


class FleetError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _now_ms() -> int:
    return int(time.time() * 1000)


def mission_status(states: list[str]) -> str:
    """Fold the per-phone states into one. Successful phones are never hidden by a failed one."""
    if not states:
        return "FAILED"
    live = [s for s in states if s not in _TERMINAL]
    if live:
        if "NEEDS_YOU" in live:
            return "NEEDS_YOU"
        if "RUNNING" in live:
            return "RUNNING"
        return "WAITING" if "WAITING" in live else "QUEUED"
    if all(s == "COMPLETED" for s in states):
        return "COMPLETED"
    if all(s == "CANCELLED" for s in states):
        return "CANCELLED"
    return "PARTIAL_FAILURE" if "COMPLETED" in states else "FAILED"


class FleetOrchestrator:
    def __init__(
        self,
        command_center: Any,
        fleet_devices: Callable[[], list[dict[str, Any]]],
        nicknames: Callable[[], dict[str, str]],
        store_path: Path,
        *,
        clock: Callable[[], int] = _now_ms,
        groups: Callable[[], list[dict[str, Any]]] | None = None,
        events: Any = None,
    ):
        self._cc = command_center
        self._devices = fleet_devices
        self._nicknames = nicknames
        self._groups = groups or (lambda: [])
        self._clock = clock
        self._events = events
        self._paused = False
        self._excluded: set[str] = set()
        self._spend_cap: float | None = None

        # Derive the SQLite path alongside whatever store_path was passed.
        # If store_path is "fleet/missions.json" the DB goes to "fleet/fleet.db".
        db_path = store_path.with_name("fleet.db")
        self._store = FleetStore(
            db_path, legacy_json=store_path if store_path.suffix == ".json" else None, clock=clock,
        )

        # Hot cache of recent missions only. SQLite is the source of truth.
        self._lock = threading.RLock()
        self._missions: dict[str, dict[str, Any]] = {}
        self._inflight: dict[str, threading.Event] = {}
        self._sync_nudge = False
        self._last_trim = -10**18
        self._store.delete_empty()          # ghosts from a crash between reserve and commit
        self._load()
        self._paused = self._store.control("paused") == "1"
        raw_excluded = self._store.control("excluded")
        self._excluded = {part for part in raw_excluded.split(",") if part}
        cap = self._store.control("spendCap")
        self._spend_cap = float(cap) if cap else None
        self._changes: queue.Queue = queue.Queue(maxsize=256)
        self._change_stop = threading.Event()
        self._change_thread = threading.Thread(target=self._change_loop, name="fleet-changes", daemon=True)
        self._change_thread.start()

    # ------------------------------------------------------------------ understanding a sentence

    def directory(self, only: list[str] | None = None) -> list[KnownDevice]:
        known = build_directory(self._devices(), self._nicknames())
        # `None` means "no restriction". An explicit empty list (an empty group, a cleared selection) means NO phones:
        # it must never widen to every phone, which is the opposite of what the owner scoped it to.
        if only is not None:
            wanted = set(only)
            known = [d for d in known if d.device_id in wanted]
        return known

    def knows_device(self, device_id: str) -> bool:
        """True for a phone the fleet can see right now, or one that already has a nickname."""
        if device_id in self._nicknames():
            return True
        return any(str(d.get("deviceId") or d.get("id")) == device_id for d in self._devices())

    def plan(self, command: str, device_ids: list[str] | None = None) -> Plan:
        return split_command(command, self.directory(device_ids), self._known_groups())

    def _known_groups(self) -> list[KnownGroup]:
        groups: list[KnownGroup] = []
        try:
            raw = self._groups()
        except Exception:  # noqa: BLE001 - a missing group list asks, it does not guess
            return groups
        for item in raw or []:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "").strip()
            group_id = str(item.get("groupId") or "")
            ids = item.get("deviceIds") or []
            if name and group_id and isinstance(ids, list):
                groups.append(KnownGroup(group_id, name, tuple(str(i) for i in ids)))
        return groups

    def run_command(
        self, command: str, *, confirm: bool = False, device_ids: list[str] | None = None,
        client_request_id: str | None = None, skip_device_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        """Parse one sentence and, if it is unambiguous, start it. Never guesses a phone."""
        if client_request_id:
            mission_id = self._mission_id(client_request_id)
            existing = self._lookup(mission_id)
            if existing and not existing.get("pending"):
                return {"dispatched": True, "needsConfirmation": False, "clarification": None,
                        "plan": None, "mission": self._snapshot(existing)}
        plan = self.plan(command, device_ids)
        if not plan.ok:
            return {"dispatched": False, "needsConfirmation": False, "clarification": plan.clarification,
                    "plan": plan.public(), "mission": None}
        warnings = self.preflight(plan.assignments)
        if warnings and not confirm:
            return {"dispatched": False, "needsConfirmation": True, "clarification": None,
                    "warnings": warnings, "plan": plan.public(), "mission": None}
        if plan.needs_confirmation and not confirm:
            return {"dispatched": False, "needsConfirmation": True, "clarification": None,
                    "plan": plan.public(), "mission": None}
        assignments = plan.assignments
        if skip_device_ids:
            skipped = set(skip_device_ids)
            assignments = [item for item in assignments if item.device_id not in skipped]
        mission = self.dispatch(assignments, command, notes=plan.notes, client_request_id=client_request_id)
        return {"dispatched": True, "needsConfirmation": False, "clarification": None,
                "plan": plan.public(), "mission": mission}

    # ------------------------------------------------------------------ starting work

    def dispatch(
        self, assignments: list[Assignment | dict[str, Any]], command: str = "", *, notes: list[str] | None = None,
        client_request_id: str | None = None, strict: bool = False,
    ) -> dict[str, Any]:
        """Create one Command Center task per phone. `strict` refuses the whole mission if any phone is unusable."""
        items = [a if isinstance(a, Assignment) else Assignment(
            str(a.get("deviceId") or ""), str(a.get("label") or a.get("deviceId") or ""), str(a.get("goal") or "").strip(),
        ) for a in assignments]
        if not items:
            raise FleetError("INVALID_REQUEST", "There is nothing to run.")
        if self._paused:
            raise FleetError("FLEET_PAUSED", "Fleet dispatch is paused. Resume it before starting phones.")
        excluded = [item.label for item in items if item.device_id in self._excluded]
        if excluded:
            raise FleetError("DO_NOT_TARGET", "These phones are marked do-not-target: " + ", ".join(excluded))
        if len(items) > MAX_ASSIGNMENTS:
            raise FleetError("INVALID_REQUEST", f"A mission can address at most {MAX_ASSIGNMENTS} phones.")
        seen: set[str] = set()
        for item in items:
            if not item.device_id or not item.goal:
                raise FleetError("INVALID_REQUEST", "Every assignment needs a phone and a goal.")
            if item.device_id in seen:
                raise FleetError("INVALID_REQUEST", "Each phone can be given one instruction per mission; merge them into one.")
            seen.add(item.device_id)

        mission_id = self._mission_id(client_request_id) if client_request_id else f"flt_{secrets.token_hex(6)}"
        owner, existing = self._reserve(mission_id, command, notes)
        if existing is not None:
            return self._snapshot(existing)
        committed = False
        try:
            live = {str(d.get("deviceId") or d.get("id")): d for d in self._devices()}
            problems: dict[str, str] = {}
            for item in items:
                device = live.get(item.device_id)
                if device is None:
                    problems[item.device_id] = f"{item.label} isn't connected right now."
                elif not device.get("paired"):
                    problems[item.device_id] = f"{item.label} isn't paired yet. Pair it, then try again."
            if strict and problems:
                raise FleetError("DEVICE_NOT_AVAILABLE", " ".join(problems.values()))

            rows: list[dict[str, Any]] = []
            for index, item in enumerate(items):
                row: dict[str, Any] = {"deviceId": item.device_id, "label": item.label, "goal": item.goal,
                                       "taskId": None, "error": problems.get(item.device_id)}
                if row["error"] is None:
                    try:
                        task = self._cc.create_task({
                            "title": f"{item.label}: {item.goal}"[:80], "goal": item.goal, "deviceId": item.device_id,
                            # Idempotent: the same mission+phone can never become two tasks, even on a double submit.
                            "requestId": f"{mission_id}-{index}",
                        })
                        row["taskId"] = task["id"]
                        self._store.bind_task(task["id"], mission_id)
                    except ValueError as exc:            # the Command Center's own refusal, written for the owner
                        row["error"] = str(exc)
                rows.append(row)

            mission = {"missionId": mission_id, "command": command[:2000], "createdAt": self._clock(),
                       "notes": list(notes or []), "assignments": rows}
            self._remember(mission)
            self._store.save(mission)
            committed = True
            now = self._clock()
            if now < self._last_trim or now - self._last_trim >= TRIM_EVERY_MS:   # once a minute, not on every dispatch
                self._last_trim = now
                self._store.trim(protect_ids=self._open_mission_ids())
                self._forget_trimmed()
            self._emit(FleetEventType.MISSION_CREATED, missionId=mission_id, status="queued")
        except Exception:
            if not committed:
                self._store.delete(mission_id)
                with self._lock:
                    self._missions.pop(mission_id, None)
            raise
        finally:
            self._release(mission_id)
        # Wake the Command Center. Do not wait: tick() is not safe beside the CC loop,
        # and HTTP must return without waiting for every phone to be told.
        self._nudge_async()
        if self._sync_nudge:
            self.flush()
        return self._snapshot(mission)

    def on_task_change(self, change: dict[str, Any]) -> None:
        """Command Center state transition. Ids and the new status only — no goal, summary, or secret."""
        task_id = str(change.get("taskId") or "")
        status = str(change.get("status") or "")
        device_id = str(change.get("deviceId") or "")
        if not task_id or not status:
            return
        mission_id = self._mission_of(task_id)
        kind = FleetEventType.NEEDS_YOU if status == "needs_you" else FleetEventType.TASK_UPDATED
        self._emit(kind, device_id, missionId=mission_id, taskId=task_id, deviceId=device_id, status=status)
        log.info("fleet.task mission=%s task=%s device=%s status=%s", mission_id, task_id, device_id, status)
        if mission_id and status in {"succeeded", "failed", "cancelled"}:
            self._enforce_spend_cap(mission_id)
        if mission_id and status in {"succeeded", "failed", "cancelled"} and self._mission_settled(mission_id, task_id, status):
            self._emit(FleetEventType.MISSION_DONE, "", missionId=mission_id, status="done")

    def _emit(self, event: FleetEventType, device_id: str = "", **payload: Any) -> None:
        broker = getattr(self, "_events", None)
        if broker is None:
            return
        broker.publish_state(event, device_id, **payload)

    def _mission_of(self, task_id: str) -> str:
        stored = self._store.mission_of_task(task_id)
        if stored:
            return stored
        with self._lock:
            missions = list(self._missions.values())
        for mission in missions:
            for row in mission.get("assignments") or []:
                if row.get("taskId") == task_id:
                    return str(mission.get("missionId") or "")
        return ""

    def _mission_settled(self, mission_id: str, task_id: str, status: str) -> bool:
        mission = self._lookup(mission_id)
        if not mission:
            return False
        terminal = {"succeeded", "failed", "cancelled"}
        for row in mission.get("assignments") or []:
            if row.get("error"):
                continue
            tid = row.get("taskId")
            if not tid:
                return False
            if tid == task_id:
                if status not in terminal:
                    return False
                continue
            try:
                task = self._cc.get_task(tid)
            except Exception:  # noqa: BLE001 - an unreadable sibling is not done
                return False
            if task is None or str(task.get("status") or "") not in terminal:
                return False
        return True

    def _reserve(self, mission_id: str, command: str, notes: list[str] | None) -> tuple[bool, dict[str, Any] | None]:
        """Reserve a mission id before any task is created. The waiter of a parallel duplicate gets the finished one."""
        while True:
            with self._lock:
                existing = self._missions.get(mission_id)
                if existing is None:
                    stored = self._store.get(mission_id)
                    if stored and not stored.get("pending"):
                        self._remember(stored)
                        return False, stored
                elif not existing.get("pending"):
                    return False, existing
                event = self._inflight.get(mission_id)
                if event is None:
                    event = threading.Event()
                    self._inflight[mission_id] = event
                    placeholder = {"missionId": mission_id, "command": command[:2000], "createdAt": self._clock(),
                                   "notes": list(notes or []), "assignments": [], "pending": True}
                    self._missions[mission_id] = placeholder
                    owner = True
                else:
                    owner = False
            if owner:
                # In memory only. A placeholder on disk would survive a crash as an empty mission and
                # swallow the owner's retry. Duplicate tasks are impossible anyway: task requestIds are
                # derived from the mission id, so a retry after a crash re-uses the tasks already made.
                return True, None
            event.wait(timeout=30)
            finished = self._lookup(mission_id)
            if finished and not finished.get("pending"):
                return False, finished

    def _release(self, mission_id: str) -> None:
        with self._lock:
            event = self._inflight.pop(mission_id, None)
            mission = self._missions.get(mission_id)
            if mission and mission.get("pending"):
                mission.pop("pending", None)
        if event:
            event.set()

    def _nudge_async(self) -> None:
        """Wake the Command Center loop. Never call tick() from this thread."""
        request = getattr(self._cc, "request_tick", None)
        if request is not None:
            request()

    def flush(self, timeout: float = 5.0) -> None:
        """Wait for a scheduled tick. Tests use this; the HTTP route does not."""
        flush = getattr(self._cc, "flush_tick", None)
        if flush is not None:
            flush(timeout)
            return
        thread = getattr(self, "_nudge_thread", None)
        if thread and thread is not threading.current_thread():
            thread.join(timeout=timeout)

    # ------------------------------------------------------------------ watching and stopping

    def mission(self, mission_id: str) -> dict[str, Any]:
        mission = self._lookup(mission_id)
        if mission is None or mission.get("pending"):
            raise FleetError("NOT_FOUND", f"No mission with id {mission_id}.")
        return self._snapshot(mission)

    def missions(self, limit: int = 50, *, cursor: str | None = None) -> list[dict[str, Any]]:
        page = self.missions_page(limit, cursor=cursor)
        return page["missions"]

    def missions_page(self, limit: int = 50, *, cursor: str | None = None) -> dict[str, Any]:
        """Newest first. `cursor` is the previous page's nextCursor. Memory is a cache, not the list."""
        items, next_cursor = self._store.page(max(1, int(limit)), cursor=cursor)
        approvals = self._open_approvals()
        index = self._open_task_index()
        missions = []
        for item in items:
            if item.get("pending"):
                continue
            self._remember(item)
            missions.append(self._snapshot(item, approvals, index))
        return {"missions": missions, "nextCursor": next_cursor}

    def cancel_mission(self, mission_id: str) -> dict[str, Any]:
        mission = self._lookup(mission_id)
        if mission is None or mission.get("pending"):
            raise FleetError("NOT_FOUND", f"No mission with id {mission_id}.")
        for row in mission["assignments"]:
            if row["taskId"]:
                self._cancel_task(row["taskId"])
        return self._snapshot(mission)

    def stop_fleet_missions(self) -> dict[str, Any]:
        """Cancel every open CC task that belongs to a known fleet mission.

        Scope: fleet missions only, including ones that are on disk but not in the hot cache.
        Non-fleet Command Center tasks (routines, direct per-phone goals, etc.) are left
        untouched. Use stop_all() as the emergency 'cancel everything' path.
        """
        stopped = 0
        fleet_task_ids: set[str] = set()
        cursor: str | None = None
        while True:
            items, cursor = self._store.page(200, cursor=cursor)
            for mission in items:
                for row in mission.get("assignments") or []:
                    if row.get("taskId"):
                        fleet_task_ids.add(row["taskId"])
            if not cursor:
                break
        for task_id in fleet_task_ids:
            if self._cancel_task(task_id):
                stopped += 1
        return {"stopped": stopped, "scope": "fleet"}

    def stop_all(self) -> dict[str, Any]:
        """Emergency stop: cancel every open Command Center task on every phone (each phone is told to stop).

        This is broader than stop_fleet_missions(): it also cancels non-fleet tasks
        (routines, direct per-phone goals). Glass labels this 'Stop everything' to make
        the scope obvious to the owner.
        """
        stopped = 0
        for task in self._cc.list_tasks(status="open", limit=1000):
            if self._cancel_task(task["id"]):
                stopped += 1
        return {"stopped": stopped, "scope": "all"}

    def retry_failed(self, mission_id: str) -> dict[str, Any]:
        """Retry failed phones on this mission. Completed phones are not given a new task."""
        mission = self._lookup(mission_id)
        if mission is None:
            raise FleetError("NOT_FOUND", "That mission is not here.")
        if self._paused:
            raise FleetError("FLEET_PAUSED", "Fleet dispatch is paused.")
        snap = self._snapshot(mission)
        retried = 0
        rows = list(mission.get("assignments") or [])
        by_id = {row.get("deviceId"): row for row in rows}
        try:
            for phone in snap["phones"]:
                if phone.get("state") not in {"FAILED", "CANCELLED"}:
                    continue
                row = by_id.get(phone.get("deviceId"))
                if row is None or not row.get("goal") or row.get("deviceId") in self._excluded:
                    continue
                if int(row.get("retries") or 0) >= 3:
                    row["error"] = "Retry limit reached."
                    continue
                attempt = int(row.get("retries") or 0) + 1
                try:
                    task = self._cc.create_task({
                        "title": f"{row.get('label')}: {row.get('goal')}"[:80],
                        "goal": row.get("goal"),
                        "deviceId": row.get("deviceId"),
                        "requestId": f"{mission_id}-retry-{row.get('deviceId')}-{attempt}",
                    })
                except ValueError as exc:          # the Command Center's own refusal; keep going with the other phones
                    row["error"] = str(exc)
                    continue
                row["taskId"] = task["id"]
                row["error"] = None
                row["retries"] = attempt
                self._store.bind_task(task["id"], mission_id)    # so its events and spend still count for this mission
                retried += 1
        finally:
            mission["assignments"] = rows
            self._store.save(mission)                            # never leave memory and disk disagreeing
        self._audit("fleet.retry", mission_id)
        self._nudge_async()
        return {"retried": retried, "mission": self._snapshot(mission)}

    def continue_rollout(self, mission_id: str) -> dict[str, Any]:
        """Start the phones held back by a canary, only if the owner asks and the canary did not fail.

        Pause and Do-not-target apply here exactly as they do to a new command: a phone excluded or a fleet paused
        after the canary started must not be started by this call.
        """
        mission = self._lookup(mission_id)
        if mission is None:
            raise FleetError("NOT_FOUND", "That mission is not here.")
        remaining = [row for row in mission.get("assignments") or [] if row.get("stage") == "waiting" and not row.get("taskId")]
        if not remaining:
            return {"started": 0, "mission": self._snapshot(mission)}
        if self._paused:
            raise FleetError("FLEET_PAUSED", "Fleet dispatch is paused. Resume it before continuing the rollout.")
        snap = self._snapshot(mission)
        canary_failed = any(phone.get("state") == "FAILED" and phone.get("taskId") for phone in snap["phones"])
        if canary_failed:
            raise FleetError("CANARY_FAILED", "The canary did not succeed. The rest were not started.")
        live = {str(d.get("deviceId") or d.get("id")): d for d in self._devices()}
        started = 0
        try:
            for index, row in enumerate(remaining):
                device_id = str(row.get("deviceId") or "")
                if device_id in self._excluded:
                    row["error"], row["stage"] = f"{row.get('label')} is marked do-not-target.", "skipped"
                    continue
                device = live.get(device_id)
                if device is None or not device.get("paired"):
                    row["error"], row["stage"] = f"{row.get('label')} isn't connected and paired right now.", "skipped"
                    continue
                try:
                    task = self._cc.create_task({
                        "title": f"{row.get('label')}: {row.get('goal')}"[:80],
                        "goal": row.get("goal"),
                        "deviceId": device_id,
                        "requestId": f"{mission_id}-rollout-{device_id}-{index}",
                    })
                except ValueError as exc:
                    row["error"], row["stage"] = str(exc), "skipped"
                    continue
                row["taskId"] = task["id"]
                row["stage"] = "rest"
                self._store.bind_task(task["id"], mission_id)
                started += 1
        finally:
            self._store.save(mission)
        self._audit("fleet.rollout.continue", mission_id)
        self._nudge_async()
        return {"started": started, "mission": self._snapshot(mission)}

    def handoff(self, mission_id: str, device_id: str, goal: str) -> dict[str, Any]:
        """Owner-started follow-up on one phone. Never starts by itself and never approves anything."""
        mission = self._lookup(mission_id)
        if mission is None:
            raise FleetError("NOT_FOUND", "That mission is not here.")
        goal = str(goal or "").strip()
        if not device_id or not goal:
            raise FleetError("INVALID_REQUEST", "A handoff needs a phone and a goal.")
        label = next((p.get("label") for p in self._snapshot(mission)["phones"] if p.get("deviceId") == device_id), device_id)
        return self.dispatch([{"deviceId": device_id, "label": label, "goal": goal}], f"Handoff from {mission_id}", notes=[f"After {mission_id}"])

    def queue(self) -> dict[str, Any]:
        """What is waiting, why, and which mission it belongs to. Ids and reasons only."""
        waiting: list[dict[str, Any]] = []
        for mission in self.missions(limit=100):
            for phone in mission.get("phones") or []:
                if phone.get("state") not in {"QUEUED", "WAITING"}:
                    continue
                waiting.append({
                    "missionId": mission.get("missionId"),
                    "deviceId": phone.get("deviceId"),
                    "label": phone.get("label"),
                    "status": phone.get("state"),
                    "reason": phone.get("hint") or phone.get("cause") or "Waiting for the phone.",
                })
        return {"waiting": waiting, "count": len(waiting), "maxPhones": MAX_PHONES, "maxPerCommand": MAX_ASSIGNMENTS, "paused": self._paused}

    def preflight(self, assignments: list[Any]) -> list[str]:
        """Owner-facing warnings. Never a secret, and never a reason to guess a phone."""
        live = {str(d.get("deviceId") or d.get("id")): d for d in self._devices()}
        index = self._open_task_index()
        warnings: list[str] = []
        for item in assignments:
            device_id = item.device_id if hasattr(item, "device_id") else str(item.get("deviceId") or "")
            label = item.label if hasattr(item, "label") else device_id
            device = live.get(device_id) or {}
            health = device.get("health") or {}
            battery = health.get("batteryPercent")
            if isinstance(battery, int) and battery < 15:
                warnings.append(f"{label} battery {battery}%.")
            permissions = health.get("permissions") or {}
            if permissions.get("camera") is False:
                warnings.append(f"{label} has the camera permission off.")
            if permissions.get("accessibility") is False or device.get("connectionHealth", {}).get("accessibilityConnected") is False:
                warnings.append(f"{label} has accessibility off.")
            open_tasks = len(index.get(device_id, []))
            if open_tasks:
                warnings.append(f"{label} already has {open_tasks} open task(s); this waits behind them.")
        return warnings

    def set_paused(self, paused: bool) -> dict[str, Any]:
        self._paused = bool(paused)
        self._store.set_control("paused", "1" if self._paused else "0")
        self._audit("fleet.pause" if paused else "fleet.resume", "")
        return {"paused": self._paused}

    def set_excluded(self, device_id: str, excluded: bool) -> dict[str, Any]:
        if excluded:
            self._excluded.add(device_id)
        else:
            self._excluded.discard(device_id)
        self._store.set_control("excluded", ",".join(sorted(self._excluded)))
        self._audit("fleet.exclude" if excluded else "fleet.include", device_id)
        return {"deviceId": device_id, "doNotTarget": device_id in self._excluded}

    def set_spend_cap(self, cap: float | None) -> dict[str, Any]:
        self._spend_cap = cap if cap and cap > 0 else None
        self._store.set_control("spendCap", "" if self._spend_cap is None else str(self._spend_cap))
        return {"spendCap": self._spend_cap}

    def rollout(self, assignments: list[dict[str, Any]], *, canary: int = 1, command: str = "") -> dict[str, Any]:
        """Start the canary phones. The rest are stored on the same mission and do not run yet."""
        if canary < 1:
            raise FleetError("INVALID_REQUEST", "A rollout needs at least one canary phone.")
        first = assignments[:canary]
        rest = assignments[canary:]
        mission = self.dispatch(first, command, notes=[f"Canary {len(first)} of {len(assignments)}. The rest wait."])
        stored = self._lookup(str(mission.get("missionId") or ""))
        if stored is not None and rest:
            rows = list(stored.get("assignments") or [])
            for item in rest:
                rows.append({"deviceId": item.get("deviceId"), "label": item.get("label"), "goal": item.get("goal"), "taskId": None, "error": None, "stage": "waiting"})
            stored["assignments"] = rows
            self._store.save(stored)
            mission = self._snapshot(stored)
        return {"mission": mission, "remaining": len(rest), "canary": len(first)}

    def health(self) -> dict[str, Any]:
        waiting = self.queue()
        subscribers = 0
        broker = self._events
        if broker is not None and hasattr(broker, "subscriber_count"):
            try:
                subscribers = int(broker.subscriber_count())
            except Exception:
                subscribers = 0
        return {
            "paused": self._paused,
            "excluded": sorted(self._excluded),
            "queueDepth": waiting["count"],
            "maxPhones": MAX_PHONES,
            "maxPerCommand": MAX_ASSIGNMENTS,
            "spendCap": self._spend_cap,
            "eventSubscribers": subscribers,
            "store": "fleet.db",
        }

    def export_mission(self, mission_id: str) -> dict[str, Any]:
        mission = self.mission(mission_id)
        phones = []
        cost = 0.0
        for phone in mission.get("phones") or []:
            amount = phone.get("costUsd")
            if isinstance(amount, (int, float)):
                cost += float(amount)
            phones.append({
                "deviceId": phone.get("deviceId"),
                "label": phone.get("label"),
                "state": phone.get("state"),
                "summary": phone.get("summary") or "",
                "costUsd": amount,
            })
        return {"missionId": mission_id, "status": mission.get("status"), "costUsd": cost, "phones": phones, "overCap": self._spend_cap is not None and cost >= self._spend_cap, "spendCapNote": "The cap stops further phones. Work already done on a phone is already spent."}

    def export_csv(self, mission_id: str) -> str:
        """CSV a spreadsheet can open safely. Summaries are written by a phone's model and may carry text from a web page,
        so a cell that starts like a formula is neutralised and every field is quoted by the csv writer."""
        import csv
        import io

        def safe(value: Any) -> str:
            text = "" if value is None else str(value)
            return "'" + text if text[:1] in ("=", "+", "-", "@", "\t", "\r") else text

        payload = self.export_mission(mission_id)
        out = io.StringIO()
        writer = csv.writer(out, lineterminator="\n")
        writer.writerow(["deviceId", "label", "state", "costUsd", "summary"])
        for phone in payload["phones"]:
            writer.writerow([safe(phone.get("deviceId")), safe(phone.get("label")), safe(phone.get("state")),
                             safe(phone.get("costUsd")), safe(phone.get("summary"))])
        return out.getvalue()

    def enqueue_task_change(self, change: dict[str, Any]) -> None:
        """The Command Center calls this. Handling happens on the fleet thread, not inside the Command Center lock."""
        try:
            self._changes.put_nowait(dict(change))
        except queue.Full:
            log.info("fleet.change_dropped")

    def _change_loop(self) -> None:
        while not self._change_stop.is_set():
            try:
                change = self._changes.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                self.on_task_change(change)
            except Exception:
                log.info("fleet.change_failed")

    def close(self) -> None:
        """Stop live events. Open missions stay in SQLite and are not started again."""
        self._change_stop.set()
        self._change_thread.join(timeout=2)
        try:
            self._store.close()
        except Exception:  # noqa: BLE001 - shutting down; nothing more to do
            pass
        broker = self._events
        if broker is not None and hasattr(broker, "close"):
            broker.close()
        log.info("fleet.closed")

    def _audit(self, action: str, obj: str) -> None:
        audit = getattr(self._cc, "_audit", None)
        if audit is None:
            return
        try:
            audit("owner", action, obj or "fleet", {})
        except Exception:
            return

    def _cancel_task(self, task_id: str) -> bool:
        try:
            self._cc.cancel_task(task_id)
            return True
        except ValueError:                      # already finished - nothing to stop
            return False

    # ------------------------------------------------------------------ saved multi-phone commands

    def run_scene(self, scene_store: Any, scene_id: str, resolve_nickname: Callable[[str], str | None]) -> dict[str, Any]:
        resolved, missing = scene_store.resolve_for_dispatch(scene_id, resolve_nickname)
        if missing:
            raise FleetError("DEVICE_NOT_AVAILABLE", "These phones in the saved command aren't known: " + ", ".join(missing))
        scene = scene_store.get_scene(scene_id)
        labels = {d.device_id: d.label for d in self.directory()}
        assignments = [Assignment(r["deviceId"], labels.get(r["deviceId"], r["deviceId"]), r["goal"]) for r in resolved]
        return self.dispatch(assignments, scene["name"], strict=True)

    # ------------------------------------------------------------------ the whole fleet at a glance

    def overview(self) -> dict[str, Any]:
        """Every phone you can address: its state, what it is doing, what it is queued for, and when it was last seen.

        Read-only. It reads the fleet list and the Command Center's open tasks; it never touches a phone.
        """
        try:
            open_tasks = self._cc.list_tasks(status="open", limit=1000)
        except Exception:  # noqa: BLE001 - a dashboard must still show phones if the task list is briefly unreadable
            open_tasks = []
        by_phone: dict[str, list[dict[str, Any]]] = {}
        for task in open_tasks:
            if task.get("deviceId"):
                by_phone.setdefault(task["deviceId"], []).append(task)
        approvals = self._open_approvals()
        nicknames = self._nicknames()
        rows: list[dict[str, Any]] = []
        for device in self._devices():
            device_id = str(device.get("deviceId") or device.get("id") or "")
            if not device_id:
                continue
            tasks = sorted(by_phone.get(device_id, []), key=lambda t: t.get("createdAt") or 0)
            health = device.get("connectionHealth") or {}
            rows.append({
                "deviceId": device_id,
                "label": nicknames.get(device_id) or str(device.get("name") or device_id),
                "nickname": nicknames.get(device_id),
                "model": device.get("model") or device.get("name"),
                "state": str(device.get("state") or "unknown"),
                "stateLabel": str(device.get("connectionLabel") or device.get("state") or ""),
                "paired": bool(device.get("paired")),
                "source": str(device.get("source") or "USB"),
                "screen": device.get("screen"),
                "lastSeenMs": device.get("lastSeenMs"),
                "accessibilityConnected": health.get("accessibilityConnected"),
                "addressable": bool(device.get("paired")),
                "tasks": [{
                    "taskId": t["id"], "title": t.get("title") or "", "status": _PHONE_STATE.get(t.get("status"), "RUNNING"),
                    "needsYou": t["id"] in approvals,
                    "approval": {
                        "id": approvals[t["id"]]["id"],
                        "text": approvals[t["id"]].get("text") or "",
                        "answerHere": bool(approvals[t["id"]].get("answerHere")),
                    } if t["id"] in approvals else None,
                } for t in tasks],
            })
        rows.sort(key=lambda r: (not r["paired"], r["label"].casefold()))
        return {"phones": rows, "counts": {
            "phones": len(rows), "ready": sum(1 for r in rows if r["state"] == "ready"),
            "busy": sum(1 for r in rows if r["tasks"]), "needYou": sum(1 for r in rows if any(t["needsYou"] for t in r["tasks"])),
        }}

    def approvals(self) -> dict[str, Any]:
        """Open owner asks for fleet phones. This lists them. It never answers them."""
        open_approvals = self._open_approvals()
        rows = []
        for task_id, approval in open_approvals.items():
            rows.append({
                "approvalId": approval.get("id"),
                "taskId": task_id,
                "text": approval.get("text") or "",
                "answerHere": bool(approval.get("answerHere")),
            })
        return {"approvals": rows, "count": len(rows)}

    # ------------------------------------------------------------------ internals

    def _live_by_id(self) -> dict[str, dict[str, Any]]:
        try:
            return {str(d.get("deviceId") or d.get("id")): d for d in self._devices()}
        except Exception:  # noqa: BLE001 - hints are extra detail; never fail a status read because of them
            return {}

    @staticmethod
    def _why_not_running(device: dict[str, Any] | None) -> str:
        """One honest line on why a queued/waiting phone has not started, from what the fleet already knows."""
        if device is None:
            return "This phone is offline. It starts by itself when it comes back."
        if not device.get("paired"):
            return "This phone isn't paired. Pair it and the task starts."
        state = str(device.get("state") or "")
        screen = str(device.get("screenState") or device.get("screen") or "")
        if state in {"DISCONNECTED", "UNAUTHORIZED"}:
            return "This phone is offline. It starts by itself when it comes back."
        if state == "SLEEPING" or screen == "SLEEPING":
            return "This phone is asleep or locked. It starts by itself when you wake it."
        if device.get("appRunning") is False:
            return "Cyclone isn't running on this phone yet. It starts by itself when the app is ready."
        if state == "ready":
            return ""
        label = str(device.get("connectionLabel") or state or "not ready")
        return f"This phone is {label.lower()}. It starts by itself when the phone is ready."

    @staticmethod
    def _mission_id(client_request_id: str) -> str:
        clean = "".join(ch for ch in client_request_id if ch.isalnum() or ch in "_-")[:40]
        if len(clean) < 6:
            raise FleetError("INVALID_REQUEST", "requestId must be at least 6 letters, digits, dash or underscore.")
        return f"flt_{clean}"

    def _open_task_index(self) -> dict[str, list[dict[str, Any]]]:
        """Open Command Center tasks by phone, oldest first. One query per request, never one per phone row."""
        try:
            tasks = self._cc.list_tasks(status="open", limit=OPEN_TASK_LIMIT)
        except Exception:  # noqa: BLE001 - extra detail; never fail a status read because of it
            return {}
        index: dict[str, list[dict[str, Any]]] = {}
        # list_tasks is newest-first with insertion order as the tie-break, so reversing it gives creation order.
        for task in reversed(tasks):
            index.setdefault(str(task.get("deviceId") or ""), []).append(task)
        return index

    @staticmethod
    def _queued_behind(device_id: str, task_id: str | None, index: dict[str, list[dict[str, Any]]]) -> int:
        """How many tasks are AHEAD of this one on the phone. A task added later never pushes an earlier one back."""
        ahead = 0
        for task in index.get(device_id, []):
            if task.get("id") == task_id:
                return ahead
            ahead += 1
        return ahead

    def _enforce_spend_cap(self, mission_id: str) -> None:
        if not self._spend_cap:
            return
        mission = self._lookup(mission_id)
        if mission is None:
            return
        cost = 0.0
        for row in mission.get("assignments") or []:
            task_id = row.get("taskId")
            if not task_id:
                continue
            try:
                task = self._cc.get_task(task_id)
            except Exception:
                continue
            amount = ((task or {}).get("run") or {}).get("costUsd")
            if isinstance(amount, (int, float)):
                cost += float(amount)
        if cost >= self._spend_cap:
            log.info("fleet.spend_cap mission=%s cost=%s", mission_id, cost)
            self.cancel_mission(mission_id)

    def _open_approvals(self) -> dict[str, dict[str, Any]]:
        try:
            return {a["taskId"]: a for a in self._cc.list_approvals(state="open")}
        except Exception:  # noqa: BLE001 - approvals are extra detail; never fail a status read because of them
            return {}

    def _snapshot(self, mission: dict[str, Any], approvals: dict[str, dict[str, Any]] | None = None,
                  index: dict[str, list[dict[str, Any]]] | None = None) -> dict[str, Any]:
        approvals = self._open_approvals() if approvals is None else approvals
        index = self._open_task_index() if index is None else index
        live = self._live_by_id()
        phones: list[dict[str, Any]] = []
        for row in mission["assignments"]:
            entry: dict[str, Any] = {
                "deviceId": row.get("deviceId", ""), "label": row.get("label", ""), "goal": row.get("goal", ""),
                "taskId": row.get("taskId"), "state": "FAILED", "cause": row.get("error") or "", "summary": "",
                "turns": 0, "approval": None, "hint": "",
            }
            if row.get("stage") == "waiting" and not row.get("taskId"):
                entry["state"] = "QUEUED"                       # held back by a canary; not a failure
                entry["hint"] = "Held back by the canary. It starts when you continue the rollout."
            elif row.get("taskId"):
                try:
                    task = self._cc.get_task(row["taskId"])
                except ValueError:
                    task = None
                if task is None:
                    entry["cause"] = "The task no longer exists."
                else:
                    entry["state"] = _PHONE_STATE.get(task["status"], "RUNNING")
                    entry["cause"] = task.get("cause") or ""
                    run = task.get("run") or {}
                    entry["summary"] = run.get("summary") or ""
                    entry["turns"] = int(run.get("turns") or 0)
                    entry["costUsd"] = run.get("costUsd")
                    blocked = self._why_not_running(live.get(row.get("deviceId")))
                    # A locked, asleep, offline, or not-ready phone is never shown as working.
                    if blocked and entry["state"] in {"QUEUED", "WAITING", "RUNNING"}:
                        entry["state"] = "WAITING"
                        entry["hint"] = blocked
                        if not entry["cause"]:
                            entry["cause"] = blocked
                    approval = approvals.get(row.get("taskId"))
                    if approval and entry["state"] == "NEEDS_YOU":
                        # Enough for a person to see WHAT they are being asked before they answer. Answering itself
                        # stays on the Command Center's own approval route (a person's act, never this module's).
                        entry["approval"] = {
                            "id": approval["id"], "kind": approval["kind"], "text": approval["text"],
                            "gate": approval.get("gate"), "send": approval.get("send"),
                            "answerHere": bool(approval.get("answerHere")), "approvableHere": bool(approval.get("approvableHere")),
                            "choices": list(approval.get("choices") or []),
                        }
                    if entry["state"] in ("QUEUED", "WAITING") and not entry["hint"]:
                        entry["hint"] = self._why_not_running(live.get(row.get("deviceId")))
                    behind = self._queued_behind(row.get("deviceId", ""), row.get("taskId"), index)
                    if behind and entry["state"] not in {"COMPLETED", "FAILED", "CANCELLED"}:
                        entry["hint"] = f"Queued behind {behind}."
                        if entry["state"] == "RUNNING":
                            entry["state"] = "QUEUED"
            phones.append(entry)
        states = [p["state"] for p in phones]
        counts: dict[str, int] = {}
        for state in states:
            counts[state] = counts.get(state, 0) + 1
        return {
            "missionId": mission["missionId"], "command": mission["command"], "createdAt": mission["createdAt"],
            "notes": list(mission["notes"]), "status": mission_status(states), "counts": counts, "phones": phones,
        }

    def _load(self) -> None:
        """Cache recent missions only. Older ones stay in SQLite and are read by mission()."""
        with self._lock:
            for row in self._store.recent(limit=CACHE_LIMIT):
                if isinstance(row.get("missionId"), str) and isinstance(row.get("assignments"), list):
                    self._missions[row["missionId"]] = row

    def _lookup(self, mission_id: str) -> dict[str, Any] | None:
        with self._lock:
            mission = self._missions.get(mission_id)
        if mission is not None:
            return mission
        stored = self._store.get(mission_id)
        if stored is not None:
            self._remember(stored)
        return stored

    def _remember(self, mission: dict[str, Any]) -> None:
        with self._lock:
            self._missions[mission["missionId"]] = mission
            if len(self._missions) <= CACHE_LIMIT:
                return
            overflow = sorted(self._missions.values(), key=lambda m: m.get("createdAt") or 0)
            for old in overflow:
                if len(self._missions) <= CACHE_LIMIT:
                    break
                if old["missionId"] == mission["missionId"]:
                    continue
                self._missions.pop(old["missionId"], None)

    def _forget_trimmed(self) -> None:
        with self._lock:
            cached = list(self._missions)
        for mission_id in cached:
            if self._store.get(mission_id) is None:
                with self._lock:
                    self._missions.pop(mission_id, None)

    def _open_mission_ids(self) -> set[str]:
        """Missions the Command Center still has open. trim() must not delete these."""
        try:
            open_tasks = {t["id"] for t in self._cc.list_tasks(status="open", limit=1000)}
        except Exception:  # noqa: BLE001 - if we cannot tell, protect everything we can see
            open_tasks = set()
            protect_unknown = True
        else:
            protect_unknown = False
        protect: set[str] = set()
        cursor: str | None = None
        while True:
            items, cursor = self._store.page(200, cursor=cursor)
            for mission in items:
                rows = mission.get("assignments") or []
                if mission.get("pending"):
                    protect.add(mission["missionId"])
                    continue
                for row in rows:
                    task_id = row.get("taskId")
                    if task_id and (protect_unknown or task_id in open_tasks):
                        protect.add(mission["missionId"])
                        break
                    if not task_id and not row.get("error"):       # held back by a canary: still to be started
                        protect.add(mission["missionId"])
                        break
            if not cursor:
                break
        return protect
