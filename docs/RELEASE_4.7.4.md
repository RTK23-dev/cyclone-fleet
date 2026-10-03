# Cyclone Mobile 4.7.4 — A shared glass conversation

Android versionCode 134, based on 4.7.3.

The signature teal glass theme now extends above the Ask bar: user and assistant messages, running/finished task cards, foreground status, queued work and the full task-detail screen. Cards use a protected opaque teal backing so launcher icons cannot bleed through text. Primary and secondary text, status pills, checkpoints and action buttons share a dark palette even when Android uses light mode. Status colors still distinguish progress, completion, attention and failure.

The in-app AI destination now matches the floating Ask bar with a dark teal canvas, quieter teal dots, glass composer and matching navigation colors. Permanent model/intelligence labels have been removed from both in-app composer sizes; the selector remains in the plus menu. Chat Stop reply, task controls, model persistence, task routing, confirmations and secure live previews retain their existing behavior.

## Verification

Repository guards and 84 Python tests passed locally. Android unit tests, lint and release assembly are required by exact-source Mobile CI; the publisher verifies APK provenance and signing continuity with 4.7.3. Contrast tests cover body/secondary copy and state/action colors. Production previews cover task states, light-device context, narrow width and large type.

Physical-device appearance and keyboard/drag behavior remain UNVERIFIED for this release. Previews are provided for inspection, not claimed as device screenshots. The material is drawn natively and does not sample other apps for live refraction. Other app destinations keep their existing theme.
