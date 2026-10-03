# Human Gesture Calibration V0.3

## Purpose

V0.3 separates evidence provenance so production-core fixtures, future physical-device captures and synthetic reference traces can be compared without treating them as equivalent evidence.

No V0.3 Agent 3 change retunes Human Gesture motion constants.

## Evidence provenance

The comparison tool recognizes:

| Source | Meaning | May justify production tuning alone? |
|---|---|---|
| `production_core` | deterministic trace exported from production-equivalent planner/core | No; proves math/replay, not device success |
| `device_capture` | evidence captured from actual Android runtime/device execution | Potentially, when acceptance gates are met |
| `synthetic_reference` | Human Gesture Lab generator data | No |
| `unclassified` | missing/unknown provenance | No |

Unknown values are never promoted to device evidence.

Synthetic reference output must not be described as human behavior.

## Tooling

V0.3 adds:

```text
tools/human-gesture-lab/evidence_compare.py
```

Usage:

```bash
python tools/human-gesture-lab/evidence_compare.py \
  path/to/production-fixtures \
  path/to/device-captures \
  path/to/reference-traces
```

Inputs may be:

- one `cyclone.human_gesture.trace.v1` JSON object;
- a JSON array of trace objects;
- a bundle with `{"traces": [...]}`;
- a directory recursively containing `.json` files.

The report schema is:

```text
cyclone.human_gesture.evidence_compare.v1
```

It groups by:

```text
source
profile
scenario
```

and reports:

- sample count;
- viewport compliance;
- target endpoint hit rate when target evidence exists;
- reported clipping rate when present;
- path/chord ratio p50/p95/p99/mean;
- max perpendicular deviation/chord p50/p95/p99/mean;
- duration p50/p95/p99/mean;
- peak velocity position p50/p95/p99/mean;
- invalid trace count;
- supplied deterministic-hash mismatch count.

## Deterministic hash verification

If an input trace carries `deterministic_hash` or `trace_hash`, the compare tool recomputes the existing motion-relevant `gesture_lab.stable_hash()` and flags mismatches.

Provenance is intentionally not part of the existing motion hash. The same motion may therefore be compared across a production fixture and a captured evidence record without changing its motion identity.

## Device evidence acceptance rules

A parameter-change recommendation should not be made from one attractive trace or from a synthetic distribution.

### 1. Provenance gate

The tuning evidence must contain explicit `device_capture` records tied to:

- exact Mobile source SHA/build;
- device model;
- Android/API level;
- execution plane/backend;
- requested and resolved profile;
- action type/scenario.

Production-core fixtures should accompany the capture set for replay comparison.

### 2. Correctness gate

Before considering aesthetics, device evidence must show:

- no regression in target hit success relative to the current control/baseline;
- no unexplained viewport exits/clipping;
- no increase in Android gesture dispatch rejection;
- no increase in stale/GATE/ownership failures attributable to Human Gesture wiring;
- no execution-plane identity mismatch.

A parameter set that looks more natural but reduces correctness is rejected.

### 3. Coverage gate

For a global LIGHT/NORMAL tuning recommendation, collect evidence across:

- more than one physical device or screen geometry when practical;
- at least foreground Accessibility execution and every backend the proposed change claims to affect;
- vertical and horizontal swipes;
- short and long strokes;
- edge-near strokes;
- small and large targets for fallback taps;
- long-press fallback where applicable.

A backend that only receives endpoint/duration input must be analyzed separately from full-fidelity curved-path execution.

### 4. Distribution gate

Compare by profile/scenario, not one aggregate score.

Required distributions:

- max deviation/chord;
- path/chord;
- duration;
- peak velocity position;
- endpoint inset/hit where available.

Look for repeatable shifts, tail failures and scenario-specific regressions. Do not create a fake composite "human score."

### 5. Semantic/fallback gate

Record the frequency of:

```text
semantic_native
synthesized_touch
legacy_touch
downgraded
unsupported
```

Path-shape calibration must use actual synthesized-touch executions. A semantic click/scroll does not provide curve evidence and must not be mixed into path distributions.

### 6. Downgrade gate

Track:

- requested profile;
- resolved profile;
- downgrade reason;
- backend/plane.

A higher downgrade frequency may mean the proposed parameters consume too much edge capacity or are unsupported by a backend.

### 7. Replay/hash gate

Where Android supplies a trace hash:

- identical deterministic fixture/capture motion should match expected hash rules;
- hash mismatches must be investigated before using the trace for tuning;
- no page text, selector, typed content or secret may enter hash inputs.

### 8. Sample-size discipline

There is no single magic count that proves "human-likeness."

For a global constant change, use enough device captures that per-profile/scenario p95/p99 tails are meaningful rather than relying on a handful of runs. If only a small exploratory set exists, label the conclusion exploratory and do not tune global production constants from it.

## V0.2 baseline observations retained

The prior production-core distribution work remains useful as deterministic planner evidence. It is not physical device success evidence.

In particular:

- OFF remains the straight/legacy compatibility baseline;
- LIGHT/NORMAL bow distributions from V0.2 describe production planner output;
- pixel bow caps and edge-aware capacity intentionally reduce relative bow on some long or edge-near strokes;
- such reductions are not automatically profile failures.

## Current V0.3 evidence status

At Agent 3 implementation time:

- no Agent 2 V0.3 device-capture handoff has landed on this branch;
- no physical Android run is claimed by Agent 3;
- production constants remain unchanged;
- the lab is ready to ingest Agent 1 production fixtures and Agent 2 device traces after integration.

## Recommended post-integration procedure

1. build the combined V0.3 Mobile runtime;
2. run Agent 2's controlled-device harness;
3. export normalized traces with explicit `device_capture` provenance;
4. export Agent 1 production-core fixtures with `production_core` provenance;
5. run `evidence_compare.py` over both corpora plus optional synthetic reference data;
6. inspect correctness gates first, then profile/scenario distributions;
7. only propose parameter changes when device evidence is repeatable and no safety/correctness regression exists.
