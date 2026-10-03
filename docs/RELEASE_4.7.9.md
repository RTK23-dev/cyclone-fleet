# Cyclone 4.7.9 — Ask panel UI polish

UI-only update on 4.7.8. Android versionCode 139.

- Tap the inner task panel to expand or collapse progress, with a subtle chevron and accessible expansion state.
- New task panels start expanded. Completion and attention updates preserve your expansion choice.
- Removed Show less, Details, View details, and the sideways Open/Clear rail from the Ask task card.
- Completed, failed, and attention cards can show existing checkpoints inline. Retry, app opening, takeover, and actionable confirmation review remain available.
- Task-card updates no longer clear composer focus or hide the keyboard. The existing keyboard-aware layout is preserved.
- Current-operation text wraps instead of truncating to one line. Existing teal signature glass is preserved.

This patch does not change automation, planning, authentication, execution verification, or telemetry. Multi-app stage naming and inline token/log diagnostics are a separate follow-up.

## Validation

Publication is gated by exact-source Mobile CI unit tests, lint, repository guards, and release assembly through the existing publisher. Local Android testing was blocked by the environment's inability to download Gradle. Physical-device tap, keyboard, accessibility, and animation verification remains UNVERIFIED; CI is not a substitute for it.
