# Cyclone Human Gesture Lab

Stdlib-only tooling for objective gesture trace analysis and deterministic lab calibration.
It does **not** execute Android input and does not become a second phone mutation authority.

## Trace contract

The lab consumes `cyclone.human_gesture.trace.v1`: viewport-normalized `(u, v, t)` points,
absolute gesture duration, profile, seed, and optional normalized target bounds. UI labels,
selectors, credentials, screenshots, and typed secrets do not belong in a motion trace.

See `trace_schema_v1.json` and `docs/HUMAN_GESTURE_CALIBRATION_V1.md`.

## Commands

```bash
python tools/human-gesture-lab/gesture_lab.py fixture --seed 42 --profile NORMAL > /tmp/trace.json
python tools/human-gesture-lab/gesture_lab.py validate /tmp/trace.json
python tools/human-gesture-lab/gesture_lab.py analyze /tmp/trace.json
python tools/human-gesture-lab/gesture_lab.py fuzz --count 50000 --seed 20260908
python tools/human-gesture-lab/gesture_lab.py calibrate --count 30000 --seed 20260908
python tools/human-gesture-lab/gesture_lab.py benchmark --count 20000 --seed 20260908
```

`fixture`, `fuzz`, and `calibrate` use a deterministic **synthetic reference generator**. That
generator exists to test the lab and establish provisional acceptance windows; it is not the
production Android Human Gesture Engine. Once Agent 1 exports real traces, run the same analyzer
against those traces and replace provisional calibration evidence with engine evidence.

## Tests

```bash
python -m unittest discover -s tools/human-gesture-lab/tests -v
```

The lab intentionally has no Android, network, ML, or third-party Python dependency.
