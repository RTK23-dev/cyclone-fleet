# Human Gesture Recorded Templates V1.5

Status: **design direction only; not required for Round 1 runtime**

## Goal

Allow Cyclone to replay a small bank of real human touch trajectories without ML, network calls, or
screen-specific recordings, while retaining deterministic replay, target safety and phone-side
execution authority.

The key design choice is to separate the **portable stored template** from the **viewport-normalized
analysis trace**.

## Do not store templates as raw screen `(x, y)`

Raw pixels bind a recording to one resolution, orientation and gesture length. Even viewport
normalized `(u, v)` retains aspect-ratio coupling and carries absolute screen location that is not
usually useful for a reusable stroke.

For the template bank, canonicalize a stroke in a start-to-end local frame:

```text
s   = longitudinal progress along the chord
n   = signed lateral offset / chord length
tau = normalized time
```

A template sample is therefore approximately:

```json
{"s": 0.42, "n": -0.031, "tau": 0.36}
```

This tangent/normal representation is orientation independent, resolution independent and easy to
warp to a requested start/end pair. The ordinary lab trace remains `(u, v, t)` because that format
is better for viewport bounds and cross-screen diagnostics.

## Proposed template record

```json
{
  "schema": "cyclone.human_gesture.template.v1_5",
  "template_id": "swipe-feed-007",
  "family": "swipe",
  "subtype": "feed_medium",
  "capture": {
    "sample_rate_hz": 120,
    "source": "consented_local_recording",
    "quality_version": 1
  },
  "shape": [
    {"s": 0.0, "n": 0.0, "tau": 0.0},
    {"s": 0.31, "n": -0.022, "tau": 0.24},
    {"s": 0.70, "n": -0.034, "tau": 0.59},
    {"s": 1.0, "n": 0.0, "tau": 1.0}
  ],
  "quality": {
    "path_chord_ratio": 1.006,
    "max_deviation_chord_ratio": 0.034,
    "curvature_sign_changes": 0
  }
}
```

No app package, screenshot, UI text, account identifier or typed value is needed in a template.

## Capture pipeline

1. Record a consented local touch stroke with high-enough temporal resolution.
2. Reject or trim stationary pre/post contact noise using documented thresholds.
3. Normalize time to `[0, 1]` while retaining source duration as calibration metadata, not identity.
4. Compute start/end chord and convert every point to `(s, n)`.
5. Resample to a bounded sample count using arc/time-preserving interpolation.
6. Calculate lab metrics.
7. Reject templates with NaN/Infinity, pathological loops, accidental clipping, implausible duration,
   or severe curvature oscillation for their declared family.
8. Store only the normalized stroke and bounded quality metadata.

## Deterministic selection

Template choice must be replayable. A simple deterministic selector is enough:

```text
index = hash(engine_version, family, size_bucket, seed) mod eligible_template_count
```

Do not use current wall-clock time or global mutable RNG state. If filtering changes the eligible
set, bump the template bank version so old run traces remain explainable.

## Warping to a requested gesture

Given start `A`, end `B`, chord vector `D = B-A`, length `L` and perpendicular unit vector `N`:

```text
P(s, n) = A + s * D + n * L * N
```

Then:

1. apply requested/engine duration scaling to `tau`;
2. transform to the target display coordinate space;
3. validate against the active display's safe region;
4. if the template would clip, choose a safer eligible template or reduce lateral amplitude within a
   declared bounded adapter;
5. if safety still fails, degrade to the procedural engine / LIGHT / OFF as appropriate.

Never silently clamp many points to the screen edge; that destroys the template topology while
making the resulting trace appear valid. The engine should report whether it adapted or rejected a
template.

## Target-relative taps

A tap template should not become a decorative loop. For target-aware tap fallback, the important
human variation is primarily:

- safe landing position inside the grounded hit region;
- very small approach/micro-motion only if Android's input backend can represent it reliably;
- bounded down/up duration if the backend exposes that concept.

Semantic Android clicks remain preferable when available.

## Duration model

Keep template shape and duration separable. A recorded `tau` curve can preserve acceleration shape,
while absolute duration is selected by the phone-side engine from gesture distance/profile and then
bounded by runtime policy.

Do not stretch a 120 ms recording to several seconds and call it the same template. Define family
specific duration-scale limits and fall back when a request is outside them.

## Multi-phase gestures

Future drag/hold/release behaviors should use explicit phases instead of forcing one polyline to
encode semantics implicitly:

```text
approach? -> contact -> hold? -> move -> release
```

Round 1.5 should only introduce phases when the Android execution backend can preserve them. A
contract that claims phase fidelity while a backend only supports start/end/duration is worse than a
truthful capability downgrade.

## Orientation and handedness

The `(s, n)` frame naturally rotates to any gesture direction. Do not create separate left/right/up/
down copies unless measured data demonstrates a meaningful shape difference.

Handedness may be optional metadata for locally recorded template banks, but should not be inferred
from user identity. Prefer a neutral mixed bank until consented calibration data proves that a
handedness option improves motion quality.

## Safe adaptation hierarchy

When replaying a template:

1. exact eligible template;
2. bounded lateral-amplitude reduction;
3. another template in the same family/size bucket;
4. procedural NORMAL/LIGHT synthesis;
5. OFF compatibility path.

Safety and target compliance outrank preserving a recorded shape.

## Privacy/storage

- Store normalized geometry only.
- No raw screen coordinates are required after canonicalization.
- No screenshots or app/page labels.
- No text entry values.
- No credentials or account identifiers.
- Keep the bank local and versioned.
- Make user-recorded banks deletable as a unit.

## Quality gates before enabling templates

A template bank should not ship merely because traces were recorded. Require:

- deterministic selection/replay;
- finite and bounded geometry;
- no unexpected topology changes after warping;
- target/safe-region compliance;
- acceptable metric distributions across multiple screen sizes/aspect ratios;
- explicit fallback behavior;
- Android backend fidelity verified on device;
- no regression to semantic-action preference or Fast Path.

## Round 2 experiment

Record a small consented corpus (for example 20-50 strokes per broad navigation family), canonicalize
it, and compare its metric distributions against procedural LIGHT/NORMAL using the V1 lab. The
purpose is not to derive a fake human score. The useful question is whether recorded templates add
stable variation or timing characteristics that the small procedural model lacks.
