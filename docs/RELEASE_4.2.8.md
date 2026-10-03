# Cyclone Mobile 4.2.8

Cyclone 4.2.8 is a focused AI-chat and overlay visual hotfix built from the preserved 4.2.7 release line.

## What changed

- Fixes the AI chat/composer jumping excessively upward when the Android keyboard opens.
- Removes the translucent rectangular raster/ghost bands visible inside the composer, user-message cards, and thinking surfaces.
- Adds a clear foreground-work presentation while Cyclone is actively thinking or operating the current phone session.
- Adds an expanded task card with `View progress` and an explicit stop control, plus a compact working state reached by dragging the overlay handle downward.
- Keeps active work visibly alive when compact instead of collapsing to the idle activation affordance.
- Routes explicit search, browse, and look-up requests into phone execution instead of conversation-only refusal loops.
- Cleans raw Markdown emphasis markers from the in-app conversation rendering.
- Adds Android 14+ application-window screenshot fallback so the agent can observe the target app window beneath Cyclone's accessibility overlay without capturing Cyclone's own overlay when a fresh live frame is unavailable.

## Release identity

- Package: `com.cyclone.mobile`
- Version name: `4.2.8`
- Version code: `89`
- Previous mobile release: `4.2.7`
- Minimum SDK: 33
- Target SDK: 35

## Validation

The release workflow accepts only the exact successful Mobile CI artifact produced from `release/cyclone-mobile-v4.2.8`, verifies the artifact checksum and metadata, signs it with the historical update-compatible development signer, and verifies certificate continuity against the published 4.2.7 APK before publication.

Physical Pixel 8 acceptance remains **UNVERIFIED** at publication time. This release is being published for immediate device validation of the keyboard, glass-surface, foreground-work, collapse, routing, and under-overlay perception changes.
