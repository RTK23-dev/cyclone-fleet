"""The Cyclone Lab runner: experiments of missions x variants x repetitions on one phone, one trial at a time.

Per trial: check the apps are installed and the phone is awake and unlocked, set the phone to the mission's starting
state (remembering what it was), start the Mind mission with the variant, play the owner from the mission's script
(answer, fill, decline; never approve, never a secret), read back the phone's record, judge it against the phone's real
state, and put every setting back. Trials are counterbalanced (the arm order rotates) so time of day, battery and app
caches do not favour one variant. Everything is appended to disk as it happens; a crash loses at most one trial.
"""
from __future__ import annotations

import json
import re
import secrets
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Protocol

from .. import __version__ as GATEWAY_VERSION
from ..desktop_runtime.models import DesktopRuntimeError
from . import stats
from .missions import LabMission, load_missions
from .probes import PhoneProbe
from .verdict import Measure, TrialFacts, judge

EXPERIMENT_ID = re.compile(r"^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$")
VARIANT_NAME = re.compile(r"^[A-Za-z0-9 ._-]{1,40}$")
VARIANT_KEYS = frozenset({"name", "modelId", "effort", "workingMinutes", "marks", "freshMemory", "promptAddendum", "useMap", "plane", "context"})
#: Plan 26: where the mission works; absent keeps lab runs on the screen.
PLANES = frozenset({"automatic", "screen", "background"})
#: Plan 37: the context a mission runs with; absent keeps lab runs classic.
CONTEXTS = frozenset({"classic", "workspace"})
BOOLEAN_KNOBS = ("marks", "freshMemory", "useMap")
MAX_TRIALS = 600
POLL_SECONDS = 2.0


class LabError(ValueError):
    pass


class LabContract(Protocol):
    def lab_start(self, device_id: str, goal: str, run_id: str, variant: dict[str, Any]) -> dict[str, Any]: ...
    def lab_status(self, device_id: str, mission_id: str) -> dict[str, Any]: ...
    def lab_answer(self, device_id: str, mission_id: str, action: str, *, text: str | None = None,
                   values: dict[str, str] | None = None) -> dict[str, Any]: ...
    def lab_record(self, device_id: str, mission_id: str) -> dict[str, Any]: ...


@dataclass
class Trial:
    index: int
    mission_id: str
    variant: str
    rep: int


def schedule(mission_ids: list[str], variants: list[str], repetitions: int) -> list[Trial]:
    """Every mission x variant x repetition, with the variant order rotated per mission and repetition."""
    trials: list[Trial] = []
    for rep in range(repetitions):
        for m, mission in enumerate(mission_ids):
            shift = (rep + m) % len(variants)
            for variant in variants[shift:] + variants[:shift]:
                trials.append(Trial(len(trials), mission, variant, rep))
    return trials


def validate_variants(raw: Any) -> list[dict[str, Any]]:
    if not isinstance(raw, list) or not 1 <= len(raw) <= 4:
        raise LabError("An experiment has 1..4 variants.")
    names: set[str] = set()
    out = []
    for variant in raw:
        if not isinstance(variant, dict) or not set(variant) <= VARIANT_KEYS or not isinstance(variant.get("name"), str):
            raise LabError("A variant has a name and only known knobs.")
        if any(key in variant and not isinstance(variant[key], bool) for key in BOOLEAN_KNOBS):
            raise LabError("marks, freshMemory and useMap are true or false.")
        if "plane" in variant and variant["plane"] is not None and variant["plane"] not in PLANES:
            raise LabError("plane is automatic, screen or background.")
        if "context" in variant and variant["context"] is not None and variant["context"] not in CONTEXTS:
            raise LabError("context is classic or workspace.")
        name = variant["name"].strip()
        if not VARIANT_NAME.match(name) or name in names:
            raise LabError("Variant names are unique, 1..40 letters, digits, space, dot, dash or underscore.")
        names.add(name)
        out.append({**variant, "name": name})
    return out


def owner_action(mission: LabMission, moment: dict[str, Any]) -> tuple[str, str | None, dict[str, str] | None]:
    """How the lab answers a moment: the mission's script where it has one, otherwise decline; stop when only the
    owner's own hands or secrets would do. Approvals are always declined."""
    kind = moment.get("kind")
    if kind in {"secret", "handover"}:
        return "stop", None, None
    if kind == "question" and mission.owner.get("reply"):
        return "reply", mission.owner["reply"], None
    if kind == "values":
        script = {k.lower(): v for k, v in (mission.owner.get("fill") or {}).items()}
        values = {}
        for f in moment.get("fields") or []:
            value = script.get(f["label"].lower()) or script.get("*")
            if value:
                values[f["label"]] = value
        if values:
            return "fill", None, values
        if mission.owner.get("reply") and not moment.get("fields"):
            return "reply", mission.owner["reply"], None
    return "decline", None, None


class LabService:
    def __init__(self, root: Path, contract: LabContract, probe_for: Callable[[str], PhoneProbe], *,
                 clock: Callable[[], float] = time.time, sleep: Callable[[float], None] = time.sleep,
                 poll_seconds: float = POLL_SECONDS):
        self.root = root
        self.contract = contract
        self.probe_for = probe_for
        self.clock = clock
        self.sleep = sleep
        self.poll_seconds = poll_seconds
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._active: dict[str, Any] | None = None

    # ---- missions ---------------------------------------------------------------------------------------------------

    def missions(self) -> tuple[list[LabMission], list[str]]:
        return load_missions(self.root / "missions")

    def catalog(self) -> dict[str, Any]:
        missions, problems = self.missions()
        suites = sorted({s for m in missions for s in m.suites})
        return {"missions": [m.public() for m in missions], "suites": suites, "problems": problems,
                "customDir": str(self.root / "missions")}

    # ---- experiments --------------------------------------------------------------------------------------------------

    def create(self, device_id: str, name: str, mission_ids: list[str], variants: Any, repetitions: int) -> dict[str, Any]:
        if not isinstance(name, str) or not 0 < len(name.strip()) <= 80:
            raise LabError("Give the experiment a name (max 80 characters).")
        if not isinstance(repetitions, int) or not 1 <= repetitions <= 20:
            raise LabError("Repetitions are 1..20.")
        known = {m.id: m for m in self.missions()[0]}
        if not isinstance(mission_ids, list) or not mission_ids or len(set(mission_ids)) != len(mission_ids):
            raise LabError("Pick at least one mission.")
        unknown = [m for m in mission_ids if m not in known]
        if unknown:
            raise LabError(f"Unknown missions: {', '.join(map(str, unknown[:5]))}.")
        arms = validate_variants(variants)
        trials = schedule(mission_ids, [v["name"] for v in arms], repetitions)
        if len(trials) > MAX_TRIALS:
            raise LabError(f"That is {len(trials)} runs; keep one experiment under {MAX_TRIALS}.")
        with self._lock:
            if self._thread and self._thread.is_alive():
                raise LabError("An experiment is already running. Stop it or wait for it to finish.")
            stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime(self.clock()))
            exp_id = f"exp-{stamp}-{secrets.token_hex(2)}"
            experiment = {
                "id": exp_id, "name": name.strip(), "deviceId": device_id, "createdAt": int(self.clock() * 1000),
                "missions": mission_ids, "variants": arms, "repetitions": repetitions, "status": "running",
                "reason": None, "total": len(trials), "done": 0, "current": None, "gatewayVersion": GATEWAY_VERSION,
                "appVersion": None,
            }
            folder = self._folder(exp_id)
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "trials.jsonl").touch()
            self._active = experiment
            self._write(experiment)
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, args=(experiment, trials, known), name=f"cyclone-lab-{exp_id}", daemon=True)
            self._thread.start()
        return self._public(experiment)

    def stop(self, exp_id: str) -> dict[str, Any]:
        with self._lock:
            if not self._active or self._active["id"] != exp_id:
                raise LabError("That experiment is not running.")
            self._stop.set()
            return self._public(self._active)

    def list(self) -> list[dict[str, Any]]:
        out = []
        folder = self.root / "experiments"
        for path in sorted(folder.glob("exp-*/experiment.json"), reverse=True)[:100] if folder.is_dir() else []:
            experiment = self._read(path.parent.name)
            if experiment:
                trials = self.trials(experiment["id"])
                arms = {v["name"]: stats.arm_stats([t for t in trials if t["variant"] == v["name"]]) for v in experiment["variants"]}
                out.append({**self._public(experiment), "arms": {k: {"rate": a["rate"], "scored": a["scored"]} for k, a in arms.items()}})
        return out

    def get(self, exp_id: str) -> dict[str, Any]:
        experiment = self._read(exp_id)
        if not experiment:
            raise LabError("No such experiment.")
        trials = self.trials(exp_id)
        names = [v["name"] for v in experiment["variants"]]
        return {
            "experiment": self._public(experiment),
            "arms": {name: stats.arm_stats([t for t in trials if t["variant"] == name]) for name in names},
            "comparisons": [stats.compare(trials, names[0], other) for other in names[1:]],
            "matrix": stats.matrix(trials),
            "insights": stats.insights(trials),
            "trials": trials,
        }

    def trials(self, exp_id: str) -> list[dict[str, Any]]:
        path = self._folder(exp_id) / "trials.jsonl"
        if not path.is_file():
            return []
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                continue
        return rows

    def export_path(self, exp_id: str) -> Path:
        if not EXPERIMENT_ID.match(exp_id) or not self._read(exp_id):
            raise LabError("No such experiment.")
        return self._folder(exp_id) / "trials.jsonl"

    # ---- the run ------------------------------------------------------------------------------------------------------

    def _run(self, experiment: dict[str, Any], trials: list[Trial], missions: dict[str, LabMission]) -> None:
        variants = {v["name"]: v for v in experiment["variants"]}
        start_failures = 0
        try:
            for trial in trials:
                if self._stop.is_set():
                    self._finish(experiment, "stopped", "Stopped by you.")
                    return
                row = self._trial(experiment, trial, missions[trial.mission_id], variants[trial.variant])
                self._append(experiment["id"], row)
                with self._lock:
                    experiment["done"] += 1
                    experiment["appVersion"] = experiment["appVersion"] or ((row.get("phone") or {}).get("app") or {}).get("versionName")
                    self._write(experiment)
                if row.get("verdict") != "skipped":  # a skip never reached the phone; it says nothing either way
                    start_failures = start_failures + 1 if row.get("phase") == "start" else 0
                if start_failures >= 3:
                    self._finish(experiment, "halted", "The phone refused three missions in a row: " + str(row.get("error"))[:160])
                    return
                self.sleep(3.0)
            self._finish(experiment, "stopped" if self._stop.is_set() else "done", None)
        except Exception as exc:  # never leave an experiment "running" forever
            self._finish(experiment, "halted", f"Lab error: {exc}"[:200])

    def _trial(self, experiment: dict[str, Any], trial: Trial, mission: LabMission, variant: dict[str, Any]) -> dict[str, Any]:
        device = experiment["deviceId"]
        run_id = f"{experiment['id']}-{trial.index}"
        row: dict[str, Any] = {
            "trialId": run_id, "experimentId": experiment["id"], "index": trial.index, "missionId": mission.id,
            "variant": trial.variant, "rep": trial.rep, "startedAt": int(self.clock() * 1000), "owner": [],
            "verdict": "infra", "category": "infra", "cause": "", "signals": [], "checks": [], "phone": None,
            "error": None, "phase": "preflight", "durationMs": 0,
        }
        self._current(experiment, trial, "preparing the phone")
        probe = self.probe_for(device)
        restores: list[Callable[[], None]] = []
        started = self.clock()
        try:
            missing = [app for app in mission.apps if not probe.package_installed(app)]
            if missing:
                row.update(verdict="skipped", category="skipped", cause=f"app not installed: {missing[0]}")
                return row
            if not self._awake(probe):
                row.update(cause="phone is locked; unlock it and keep it awake while the lab runs")
                return row
            row["phase"] = "setup"
            self._setup(probe, mission, restores)
            self.sleep(1.5)
            row["phase"] = "start"
            self._current(experiment, trial, "starting the mission")
            try:
                ack = self.contract.lab_start(device, mission.goal, run_id, variant)
            except DesktopRuntimeError as exc:
                row.update(error=f"{exc.code}: {exc}"[:200], cause=f"the phone refused the mission ({exc.code})")
                return row
            mission_id = ack["missionId"]
            row["missionOnPhone"] = mission_id
            row["phase"] = "running"
            started = self.clock()
            lab_stopped = self._watch(experiment, trial, device, mission, mission_id, row["owner"])
            row["phase"] = "judging"
            self._current(experiment, trial, "checking the phone")
            record = self._record(device, mission_id)
            if lab_stopped == "timeout" and "stopped by the owner" in str(record.get("summary", "")).lower():
                # Alpha 91: the Lab stopped it for time; the phone only knows "the owner pressed Stop".
                record = {**record, "summary": f"Ran out of Lab time after {record.get('turns', 0)} turns (stopped by the Lab)."}
            row["phone"] = record
            verdict = judge(mission, TrialFacts(record, row["owner"], lab_stopped), Measure(probe))
            row.update(verdict.public())
            if lab_stopped == "stopped":  # you stopped the experiment; this run says nothing about Cyclone
                row.update(verdict="infra", category="infra", cause="stopped by you")
            row["phase"] = "done"
        except Exception as exc:
            row.update(error=str(exc)[:200], cause=f"lab error during {row['phase']}")
        finally:
            row["durationMs"] = int((self.clock() - started) * 1000)
            row["endedAt"] = int(self.clock() * 1000)
            for restore in reversed(restores):
                try:
                    restore()
                except Exception:
                    pass
            try:
                probe.home()
            except Exception:
                pass
        return row

    def _awake(self, probe: PhoneProbe) -> bool:
        for _ in range(24):  # up to about two minutes for the owner to unlock
            try:
                if not probe.screen_on():
                    probe.wake()
                if not probe.locked():
                    return True
            except Exception:
                pass
            if self._stop.is_set():
                return False
            self.sleep(5.0)
        return False

    def _setup(self, probe: PhoneProbe, mission: LabMission, restores: list[Callable[[], None]]) -> None:
        for step in mission.setup:
            kind = step["do"]
            if kind == "home":
                probe.home()
            elif kind == "force_stop":
                probe.force_stop(step["package"])
            elif kind == "launch":
                probe.launch(step["package"])
                self.sleep(1.5)
            elif kind == "setting":
                ns, key = step["namespace"], step["key"]
                before = probe.setting(ns, key)
                probe.put_setting(ns, key, step["value"])
                restores.append(lambda ns=ns, key=key, before=before: probe.restore_setting(ns, key, before))
            elif kind == "night_mode":
                before = probe.night_mode()
                probe.set_night_mode(step["on"])
                if before is not None:
                    restores.append(lambda before=before: probe.set_night_mode(before))
            elif kind == "dnd":
                before = probe.setting("global", "zen_mode")
                probe.set_dnd(step["on"])
                restores.append(lambda before=before: probe.set_dnd(before not in {None, "0"}))
            elif kind == "lab_file":
                if step["present"]:
                    probe.create_lab_file()
                else:
                    probe.remove_lab_file()
                restores.append(probe.remove_lab_file)

    def _watch(self, experiment: dict[str, Any], trial: Trial, device: str, mission: LabMission, mission_id: str,
               owner_log: list[dict[str, Any]]) -> str | None:
        deadline = self.clock() + mission.minutes * 60 + 30
        answered: set[str] = set()
        stopped: str | None = None
        steer = mission.owner.get("steer")
        while True:
            self.sleep(self.poll_seconds)
            try:
                status = self.contract.lab_status(device, mission_id)
            except DesktopRuntimeError:
                if self.clock() > deadline + 60:
                    return "timeout"
                continue
            if not status["live"]:
                return stopped
            self._current(experiment, trial, f"working · {status['turns']} turns", status)
            if steer and int(status.get("turns") or 0) >= int(steer.get("afterTurns", 1)):
                # Plan 38: the lab changes the task mid-run, like an owner using Steer in the Ask bar.
                owner_log.append({"kind": "steer", "action": "steer", "at": int(self.clock() * 1000), "fields": []})
                try:
                    self.contract.lab_answer(device, mission_id, "steer", text=steer["text"])
                except DesktopRuntimeError:
                    pass
                steer = None
            moment = status.get("moment")
            if moment:
                key = moment.get("requestId") or f"{moment['kind']}:{moment['text']}"
                if key not in answered:
                    answered.add(key)
                    action, text, values = owner_action(mission, moment)
                    owner_log.append({"kind": moment["kind"], "action": action, "at": int(self.clock() * 1000),
                                      "fields": [f["label"] for f in moment.get("fields") or []]})
                    try:
                        self.contract.lab_answer(device, mission_id, action, text=text, values=values)
                    except DesktopRuntimeError:
                        pass
                    if action == "stop":
                        stopped = "needs_owner"
            if self._stop.is_set() and stopped is None:
                stopped = "stopped"
                self._safe_stop(device, mission_id)
            if self.clock() > deadline and stopped is None:
                stopped = "timeout"
                self._safe_stop(device, mission_id)
            if stopped and self.clock() > deadline + 60:
                return stopped

    def _safe_stop(self, device: str, mission_id: str) -> None:
        try:
            self.contract.lab_answer(device, mission_id, "stop")
        except DesktopRuntimeError:
            pass

    def _record(self, device: str, mission_id: str) -> dict[str, Any]:
        for _ in range(10):
            record = self.contract.lab_record(device, mission_id)
            if not record.get("live"):
                return record
            self.sleep(1.0)
        return record

    # ---- storage -----------------------------------------------------------------------------------------------------

    def _current(self, experiment: dict[str, Any], trial: Trial, phase: str, status: dict[str, Any] | None = None) -> None:
        with self._lock:
            experiment["current"] = {
                "index": trial.index, "missionId": trial.mission_id, "variant": trial.variant, "rep": trial.rep,
                "phase": phase, "turns": (status or {}).get("turns"), "costUsd": (status or {}).get("costUsd"),
            }
            self._write(experiment)

    def _finish(self, experiment: dict[str, Any], status: str, reason: str | None) -> None:
        with self._lock:
            experiment.update(status=status, reason=reason, current=None, finishedAt=int(self.clock() * 1000))
            self._write(experiment)
            self._active = None

    def _folder(self, exp_id: str) -> Path:
        if not EXPERIMENT_ID.match(exp_id):
            raise LabError("Experiment id is malformed.")
        return self.root / "experiments" / exp_id

    def _write(self, experiment: dict[str, Any]) -> None:
        path = self._folder(experiment["id"]) / "experiment.json"
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(experiment, indent=1), encoding="utf-8")
        tmp.replace(path)

    def _read(self, exp_id: str) -> dict[str, Any] | None:
        if not EXPERIMENT_ID.match(exp_id):
            return None
        if self._active and self._active["id"] == exp_id:
            with self._lock:
                return json.loads(json.dumps(self._active))
        path = self._folder(exp_id) / "experiment.json"
        try:
            experiment = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if experiment.get("status") == "running":  # the gateway restarted mid-experiment
            experiment.update(status="halted", reason="The gateway restarted during this experiment.", current=None)
        return experiment

    def _append(self, exp_id: str, row: dict[str, Any]) -> None:
        with (self._folder(exp_id) / "trials.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, separators=(",", ":")) + "\n")

    @staticmethod
    def _public(experiment: dict[str, Any]) -> dict[str, Any]:
        return {k: experiment.get(k) for k in ("id", "name", "deviceId", "createdAt", "finishedAt", "missions", "variants",
                                                  "repetitions", "status", "reason", "total", "done", "current",
                                                  "gatewayVersion", "appVersion")}
