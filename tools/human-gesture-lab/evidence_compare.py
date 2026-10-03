#!/usr/bin/env python3
"""Compare Human Gesture trace corpora without conflating evidence provenance."""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path
import statistics
import sys
from typing import Any, Iterable, Sequence

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("cyclone_human_gesture_lab", HERE / "gesture_lab.py")
lab = importlib.util.module_from_spec(_spec)
assert _spec and _spec.loader
sys.modules.setdefault(_spec.name, lab)
_spec.loader.exec_module(lab)

REPORT_SCHEMA = "cyclone.human_gesture.evidence_compare.v1"
SOURCE_CLASSES = ("production_core", "device_capture", "synthetic_reference")
UNKNOWN_SOURCE = "unclassified"
METRICS = (
    "path_chord_ratio",
    "max_perpendicular_deviation_chord_ratio",
    "duration_ms",
    "peak_velocity_position",
)


def source_class(trace: dict[str, Any]) -> str:
    raw = trace.get("source")
    if not isinstance(raw, str):
        return UNKNOWN_SOURCE
    value = raw.strip().lower()
    return value if value in SOURCE_CLASSES else UNKNOWN_SOURCE


def scenario_name(trace: dict[str, Any]) -> str:
    value = trace.get("scenario")
    if not isinstance(value, str) or not value.strip():
        return "unspecified"
    return value.strip()[:96]


def _percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    rank = (len(values) - 1) * p
    lo, hi = math.floor(rank), math.ceil(rank)
    if lo == hi:
        return values[lo]
    return values[lo] * (hi - rank) + values[hi] * (rank - lo)


def _summary(values: Iterable[float]) -> dict[str, float | None]:
    v = [float(x) for x in values]
    return {
        "p50": _percentile(v, .50),
        "p95": _percentile(v, .95),
        "p99": _percentile(v, .99),
        "mean": statistics.fmean(v) if v else None,
    }


def _iter_json_payload(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, list):
        for item in value:
            if isinstance(item, dict):
                yield item
        return
    if not isinstance(value, dict):
        return
    if value.get("schema") == lab.TRACE_SCHEMA:
        yield value
        return
    traces = value.get("traces")
    if isinstance(traces, list):
        for item in traces:
            if isinstance(item, dict):
                yield item


def load_corpus(paths: Sequence[str]) -> list[dict[str, Any]]:
    files: list[Path] = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            files.extend(sorted(p for p in path.rglob("*.json") if p.is_file()))
        else:
            files.append(path)
    traces: list[dict[str, Any]] = []
    for path in files:
        with path.open(encoding="utf-8") as handle:
            traces.extend(_iter_json_payload(json.load(handle)))
    return traces


def compare_traces(traces: Sequence[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    hash_mismatches: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    for index, trace in enumerate(traces):
        try:
            metrics = lab.analyze_trace(trace)
        except Exception as exc:
            invalid.append({"index": index, "error": f"{type(exc).__name__}: {exc}"})
            continue
        source = source_class(trace)
        scenario = scenario_name(trace)
        profile = str(metrics["profile"])
        groups.setdefault((source, profile, scenario), []).append(metrics)
        supplied_hash = trace.get("deterministic_hash") or trace.get("trace_hash")
        if isinstance(supplied_hash, str) and supplied_hash.lower() != metrics["deterministic_hash"]:
            hash_mismatches.append({
                "index": index,
                "source": source,
                "profile": profile,
                "scenario": scenario,
            })

    rows = []
    for (source, profile, scenario), items in sorted(groups.items()):
        rows.append({
            "source": source,
            "profile": profile,
            "scenario": scenario,
            "sample_count": len(items),
            "viewport_compliance_rate": sum(bool(m["viewport_compliant"]) for m in items) / len(items),
            "endpoint_hit_rate": (
                sum(m["endpoint_in_target"] is True for m in items)
                / sum(m["endpoint_in_target"] is not None for m in items)
                if any(m["endpoint_in_target"] is not None for m in items) else None
            ),
            "reported_clip_rate": (
                sum(m["reported_clipped"] is True for m in items)
                / sum(m["reported_clipped"] is not None for m in items)
                if any(m["reported_clipped"] is not None for m in items) else None
            ),
            "metrics": {
                name: _summary(m[name] for m in items if isinstance(m.get(name), (int, float)))
                for name in METRICS
            },
        })

    source_counts = {source: 0 for source in (*SOURCE_CLASSES, UNKNOWN_SOURCE)}
    for row in rows:
        source_counts[row["source"]] += row["sample_count"]
    return {
        "schema": REPORT_SCHEMA,
        "trace_schema": lab.TRACE_SCHEMA,
        "sample_count": sum(source_counts.values()),
        "source_counts": source_counts,
        "groups": rows,
        "invalid_count": len(invalid),
        "invalid": invalid[:50],
        "hash_mismatch_count": len(hash_mismatches),
        "hash_mismatches": hash_mismatches[:50],
        "provenance_note": (
            "production_core is deterministic production-equivalent evidence; device_capture is "
            "physical/runtime evidence; synthetic_reference is generator evidence only and is never human behavior."
        ),
    }


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Compare Human Gesture trace evidence by provenance/profile/scenario")
    p.add_argument("paths", nargs="+", help="trace JSON files or directories")
    return p


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        report = compare_traces(load_corpus(args.paths))
        print(json.dumps(report, indent=2, sort_keys=True, allow_nan=False))
        return 2 if report["invalid_count"] or report["hash_mismatch_count"] else 0
    except (OSError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
