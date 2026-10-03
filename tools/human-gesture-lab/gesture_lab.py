#!/usr/bin/env python3
"""Cyclone Human Gesture Lab: trace validation, metrics, fuzzing and calibration.

The synthetic generator is a deterministic lab reference, not Android production code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import statistics
import sys
import time
from pathlib import Path
from typing import Any, Iterable, Sequence

TRACE_SCHEMA = "cyclone.human_gesture.trace.v1"
PROFILES = ("OFF", "LIGHT", "NORMAL")
GESTURE_TYPES = ("tap", "swipe", "drag")
EPS = 1e-12


class TraceError(ValueError):
    pass


def num(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TraceError(f"{name} must be a finite number")
    value = float(value)
    if not math.isfinite(value):
        raise TraceError(f"{name} must be finite")
    return value


def parse_trace(trace: dict[str, Any]) -> tuple[list[tuple[float, float, float]], float, float, float]:
    if not isinstance(trace, dict) or trace.get("schema") != TRACE_SCHEMA:
        raise TraceError(f"schema must be {TRACE_SCHEMA!r}")
    if trace.get("profile") not in PROFILES:
        raise TraceError(f"profile must be one of {PROFILES}")
    if trace.get("gesture_type") not in GESTURE_TYPES:
        raise TraceError(f"gesture_type must be one of {GESTURE_TYPES}")
    viewport = trace.get("viewport")
    if not isinstance(viewport, dict):
        raise TraceError("viewport must be an object")
    width = num(viewport.get("width_px"), "viewport.width_px")
    height = num(viewport.get("height_px"), "viewport.height_px")
    duration = num(trace.get("duration_ms"), "duration_ms")
    if width <= 0 or height <= 0:
        raise TraceError("viewport dimensions must be > 0")
    if duration < 0:
        raise TraceError("duration_ms must be >= 0")
    raw = trace.get("points")
    if not isinstance(raw, list) or not raw:
        raise TraceError("points must be a non-empty array")
    if trace["gesture_type"] in {"swipe", "drag"} and len(raw) < 2:
        raise TraceError(f"{trace['gesture_type']} requires at least two points")
    points: list[tuple[float, float, float]] = []
    last_t = -math.inf
    for i, point in enumerate(raw):
        if not isinstance(point, dict):
            raise TraceError(f"points[{i}] must be an object")
        u, v, tau = (num(point.get(k), f"points[{i}].{k}") for k in ("u", "v", "t"))
        if not (0 <= u <= 1 and 0 <= v <= 1):
            raise TraceError(f"points[{i}] must stay inside normalized viewport [0, 1]")
        if not 0 <= tau <= 1:
            raise TraceError(f"points[{i}].t must be in [0, 1]")
        if tau + EPS < last_t:
            raise TraceError("point time must be monotonic")
        last_t = tau
        points.append((u * width, v * height, tau * duration))
    if len(points) > 1:
        if abs(raw[0]["t"]) > 1e-9 or abs(raw[-1]["t"] - 1.0) > 1e-9:
            raise TraceError("multi-point traces must start at t=0 and end at t=1")
        if duration <= 0:
            raise TraceError("multi-point traces require duration_ms > 0")
    return points, width, height, duration


def target_bounds(trace: dict[str, Any], width: float, height: float):
    target = trace.get("target")
    if target is None:
        return None
    if not isinstance(target, dict):
        raise TraceError("target must be an object when present")
    bounds = target.get("bounds_norm")
    if bounds is None:
        return None
    if not isinstance(bounds, dict):
        raise TraceError("target.bounds_norm must be an object")
    l, t, r, b = (num(bounds.get(k), f"target.bounds_norm.{k}") for k in ("left", "top", "right", "bottom"))
    if not (0 <= l <= r <= 1 and 0 <= t <= b <= 1):
        raise TraceError("target.bounds_norm must be ordered and within [0, 1]")
    return l * width, t * height, r * width, b * height


def dist(a, b) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def inside(point, bounds) -> bool:
    l, t, r, b = bounds
    return l - 1e-9 <= point[0] <= r + 1e-9 and t - 1e-9 <= point[1] <= b + 1e-9


def stable_hash(trace: dict[str, Any]) -> str:
    payload = {k: trace.get(k) for k in ("schema", "gesture_type", "profile", "seed", "viewport", "duration_ms", "target", "points")}
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def analyze_trace(trace: dict[str, Any]) -> dict[str, Any]:
    points, width, height, duration = parse_trace(trace)
    bounds = target_bounds(trace, width, height)
    path = sum(dist(a, b) for a, b in zip(points, points[1:]))
    chord = dist(points[0], points[-1]) if len(points) > 1 else 0.0
    if chord > EPS:
        dx, dy = points[-1][0] - points[0][0], points[-1][1] - points[0][1]
        signed = [(dx * (p[1] - points[0][1]) - dy * (p[0] - points[0][0])) / chord for p in points]
    else:
        signed = [0.0] * len(points)
    deviations = [abs(v) for v in signed]
    turns = []
    for a, b, c in zip(points, points[1:], points[2:]):
        ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
        if math.hypot(ux, uy) > EPS and math.hypot(vx, vy) > EPS:
            turns.append(math.atan2(ux * vy - uy * vx, ux * vx + uy * vy))
    signs = [1 if angle > 0 else -1 for angle in turns if abs(angle) > 1e-5]
    sign_changes = sum(a != b for a, b in zip(signs, signs[1:]))
    speeds: list[tuple[float, float]] = []
    total_time = max(points[-1][2] - points[0][2], EPS)
    for a, b in zip(points, points[1:]):
        dt = b[2] - a[2]
        if dt <= EPS:
            if dist(a, b) > EPS:
                raise TraceError("non-zero movement requires increasing time")
            continue
        speeds.append((dist(a, b) / dt, (((a[2] + b[2]) / 2) - points[0][2]) / total_time))
    speed_values = [v for v, _ in speeds]
    peak_i = max(range(len(speeds)), key=lambda i: speeds[i][0]) if speeds else 0
    accels = []
    for (s0, p0), (s1, p1) in zip(speeds, speeds[1:]):
        dt = (p1 - p0) * total_time
        if dt > EPS:
            accels.append((s1 - s0) / dt)
    endpoint_in_target = inside(points[-1], bounds) if bounds else None
    endpoint_inset = None
    if bounds and endpoint_in_target:
        l, t, r, b = bounds
        x, y = points[-1][:2]
        endpoint_inset = min(x - l, r - x, y - t, b - y)
    boundary_touches = sum(1 for x, y, _ in points if abs(x) <= 1e-9 or abs(y) <= 1e-9 or abs(x - width) <= 1e-9 or abs(y - height) <= 1e-9)
    synthesis = trace.get("synthesis") if isinstance(trace.get("synthesis"), dict) else {}
    return {
        "schema": TRACE_SCHEMA,
        "gesture_type": trace["gesture_type"],
        "profile": trace["profile"],
        "seed": trace.get("seed"),
        "point_count": len(points),
        "duration_ms": duration,
        "path_length_px": path,
        "chord_length_px": chord,
        "path_chord_ratio": path / chord if chord > EPS else (1.0 if path <= EPS else None),
        "max_perpendicular_deviation_px": max(deviations, default=0.0),
        "mean_perpendicular_deviation_px": statistics.fmean(deviations) if deviations else 0.0,
        "max_perpendicular_deviation_chord_ratio": max(deviations, default=0.0) / chord if chord > EPS else 0.0,
        "total_abs_turn_rad": sum(abs(v) for v in turns),
        "curvature_sign_changes": sign_changes,
        "peak_velocity_px_per_ms": max(speed_values, default=0.0),
        "mean_velocity_px_per_ms": statistics.fmean(speed_values) if speed_values else 0.0,
        "peak_velocity_position": speeds[peak_i][1] if speeds else 0.0,
        "peak_abs_acceleration_px_per_ms2": max((abs(v) for v in accels), default=0.0),
        "viewport_compliant": all(0 <= x <= width and 0 <= y <= height for x, y, _ in points),
        "boundary_touch_count": boundary_touches,
        "reported_clipped": synthesis.get("clipped") if isinstance(synthesis.get("clipped"), bool) else None,
        "endpoint_in_target": endpoint_in_target,
        "endpoint_inset_px": endpoint_inset,
        "deterministic_hash": stable_hash(trace),
    }


def bezier(p0, p1, p2, p3, t):
    q = 1 - t
    return (q**3 * p0[0] + 3*q*q*t*p1[0] + 3*q*t*t*p2[0] + t**3*p3[0], q**3 * p0[1] + 3*q*q*t*p1[1] + 3*q*t*t*p2[1] + t**3*p3[1])


def synthetic_trace(seed: int, profile: str | None = None, case: int | None = None) -> dict[str, Any]:
    r = random.Random(seed)
    default_profile = r.choice(PROFILES)
    profile = profile or default_profile
    width, height = r.choice([(320, 480), (720, 1280), (1080, 1920), (1080, 2400), (1440, 3120)])
    default_case = r.randrange(8)
    case = default_case if case is None else case
    if case == 0:
        start, end = (r.uniform(.18,.82), r.uniform(.62,.9)), (r.uniform(.18,.82), r.uniform(.1,.38))
    elif case == 1:
        start, end = (r.uniform(.65,.9), r.uniform(.25,.75)), (r.uniform(.1,.35), r.uniform(.25,.75))
    elif case == 2:
        start, end = (r.uniform(.08,.25), r.uniform(.7,.92)), (r.uniform(.7,.92), r.uniform(.08,.3))
    elif case == 3:
        start = (r.uniform(.2,.8), r.uniform(.2,.8)); end = (min(.98,max(.02,start[0]+r.uniform(-.025,.025))), min(.98,max(.02,start[1]+r.uniform(-.025,.025))))
    elif case == 4:
        start, end = (r.choice([0.0,1.0]), r.uniform(.05,.95)), (r.uniform(.25,.75), r.uniform(.2,.8))
    elif case == 5:
        start, end = (r.uniform(.2,.8), r.uniform(.2,.8)), (r.uniform(.05,.95), r.choice([0.0,1.0]))
    elif case == 6:
        start = (r.uniform(.1,.9), r.uniform(.1,.9)); end = start
    else:
        start, end = (r.uniform(.05,.2), r.uniform(.75,.95)), (r.uniform(.8,.95), r.uniform(.05,.25))
    dx, dy = end[0]-start[0], end[1]-start[1]
    dx_px, dy_px = dx*width, dy*height
    chord = math.hypot(dx_px, dy_px)
    bow = 0.0 if profile == "OFF" or chord <= EPS else r.uniform(.01,.03) if profile == "LIGHT" else r.uniform(.025,.06)
    side = r.choice((-1.0,1.0))
    if chord > EPS:
        control = chord * bow * side / .75
        ou, ov = control * (-dy_px/chord) / width, control * (dx_px/chord) / height
    else:
        ou = ov = 0.0
    c1, c2 = (start[0]+.30*dx+ou, start[1]+.30*dy+ov), (start[0]+.70*dx+ou, start[1]+.70*dy+ov)
    duration = max(80.0, min(900.0, 105.0 + .31*chord + r.uniform(-18.0,18.0)))
    points = []
    for i in range(17):
        tau = i/16; s = tau*tau*(3-2*tau)
        u, v = (start[0]+dx*s, start[1]+dy*s) if profile == "OFF" else bezier(start,c1,c2,end,s)
        points.append({"u": min(1.0,max(0.0,u)), "v": min(1.0,max(0.0,v)), "t": tau})
    return {"schema": TRACE_SCHEMA, "engine": {"name":"human-gesture-lab-reference","version":"1"}, "source":"synthetic_reference", "gesture_type":"swipe", "profile":profile, "seed":seed, "viewport":{"width_px":width,"height_px":height}, "duration_ms":round(duration,6), "points":points}


def percentile(values: Sequence[float], p: float) -> float:
    if not values: return 0.0
    v=sorted(values); rank=(len(v)-1)*p; lo=math.floor(rank); hi=math.ceil(rank)
    return v[lo] if lo == hi else v[lo]*(hi-rank)+v[hi]*(rank-lo)


def summary(values: Iterable[float]) -> dict[str,float]:
    v=list(values)
    return {"min":min(v,default=0.0),"p50":percentile(v,.5),"p95":percentile(v,.95),"p99":percentile(v,.99),"max":max(v,default=0.0),"mean":statistics.fmean(v) if v else 0.0}


def run_fuzz(count: int, seed: int) -> dict[str,Any]:
    r=random.Random(seed); failures=[]; done=0; profiles={p:0 for p in PROFILES}; started=time.perf_counter()
    for i in range(count):
        s=r.randrange(2**63)
        try:
            trace=synthetic_trace(s,case=i%8); m=analyze_trace(trace); replay=analyze_trace(synthetic_trace(s,profile=trace["profile"],case=i%8))
            if m["deterministic_hash"] != replay["deterministic_hash"]: raise AssertionError("deterministic replay hash mismatch")
            if not m["viewport_compliant"]: raise AssertionError("viewport clipping")
            if any(isinstance(v,float) and not math.isfinite(v) for v in m.values()): raise AssertionError("non-finite metric")
            profiles[trace["profile"]]+=1; done+=1
        except Exception as exc:
            failures.append({"index":i,"seed":s,"case":i%8,"error":f"{type(exc).__name__}: {exc}"}); done+=1
            if len(failures)>=25: break
    elapsed=time.perf_counter()-started
    return {"schema":"cyclone.human_gesture.fuzz_report.v1","input_seed":seed,"requested_count":count,"executed_count":done,"failure_count":len(failures),"failures":failures,"elapsed_ms":elapsed*1000,"gestures_per_second":done/elapsed if elapsed else 0.0,"profile_samples":profiles}


def run_calibration(count: int, seed: int) -> dict[str,Any]:
    r=random.Random(seed); groups={p:[] for p in PROFILES}
    for i in range(count):
        profile=PROFILES[i%3]; m=analyze_trace(synthetic_trace(r.randrange(2**63),profile,case=i%8))
        if m["chord_length_px"]>8: groups[profile].append(m)
    out={"schema":"cyclone.human_gesture.calibration_report.v1","input_seed":seed,"requested_count":count,"note":"synthetic lab reference model; not production Android engine evidence","profiles":{}}
    for p,items in groups.items():
        out["profiles"][p]={"sample_count":len(items),"path_chord_ratio":summary(m["path_chord_ratio"] for m in items),"max_deviation_chord_ratio":summary(m["max_perpendicular_deviation_chord_ratio"] for m in items),"duration_ms":summary(m["duration_ms"] for m in items),"curvature_sign_changes":summary(float(m["curvature_sign_changes"]) for m in items),"peak_velocity_position":summary(m["peak_velocity_position"] for m in items)}
    return out


def run_benchmark(count: int, seed: int) -> dict[str,Any]:
    r=random.Random(seed); traces=[synthetic_trace(r.randrange(2**63),case=i%8) for i in range(count)]; lat=[]; started=time.perf_counter()
    for trace in traces:
        t=time.perf_counter_ns(); analyze_trace(trace); lat.append((time.perf_counter_ns()-t)/1000)
    elapsed=time.perf_counter()-started
    return {"schema":"cyclone.human_gesture.lab_benchmark.v1","operation":"validate+analyze one normalized trace","count":count,"input_seed":seed,"latency_us":summary(lat),"elapsed_ms":elapsed*1000,"traces_per_second":count/elapsed if elapsed else 0.0,"note":"Python lab analyzer benchmark; not Android gesture synthesis latency"}


def load(path: str):
    if path == "-": return json.load(sys.stdin)
    with Path(path).open(encoding="utf-8") as f: return json.load(f)


def emit(value): print(json.dumps(value,indent=2,sort_keys=True,allow_nan=False))


def parser():
    p=argparse.ArgumentParser(description="Cyclone Human Gesture trace lab"); sub=p.add_subparsers(dest="command",required=True)
    for name in ("analyze","validate"): q=sub.add_parser(name); q.add_argument("trace")
    for name,default in (("fuzz",10000),("calibrate",12000),("benchmark",10000)):
        q=sub.add_parser(name); q.add_argument("--count",type=int,default=default); q.add_argument("--seed",type=int,default=20260908)
    q=sub.add_parser("fixture"); q.add_argument("--seed",type=int,default=42); q.add_argument("--profile",choices=PROFILES,default="NORMAL")
    return p


def main(argv: Sequence[str]|None=None) -> int:
    a=parser().parse_args(argv)
    try:
        if a.command=="analyze": emit(analyze_trace(load(a.trace)))
        elif a.command=="validate":
            m=analyze_trace(load(a.trace)); emit({"ok":True,"schema":m["schema"],"deterministic_hash":m["deterministic_hash"]})
        elif a.command in {"fuzz","calibrate","benchmark"}:
            if a.count<=0: raise TraceError("--count must be > 0")
            report={"fuzz":run_fuzz,"calibrate":run_calibration,"benchmark":run_benchmark}[a.command](a.count,a.seed); emit(report)
            if a.command=="fuzz" and report["failure_count"]: return 2
        else: emit(synthetic_trace(a.seed,a.profile,case=0))
        return 0
    except (TraceError,json.JSONDecodeError,OSError) as exc:
        print(f"error: {exc}",file=sys.stderr); return 2


if __name__ == "__main__": raise SystemExit(main())
