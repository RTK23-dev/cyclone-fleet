# Cyclone Mobile 4.6.6

Android versionCode 126; builds on published 4.6.5.

This release refreshes the Ask Cyclone page with a quieter, full-screen visual system while preserving the existing agent and phone-control behavior.

## Ask Cyclone

- Replaces the old full-page blue gradient with a clean black/white canvas that follows system light and dark mode.
- Adds a slow blue/cyan/lilac dot field that breathes through subtle size and opacity changes.
- Shapes the dot field as a soft lower-page bowl so the center stays visually quiet while the sides rise gently without a hard U edge.
- Keeps the animated field behind the floating Ask Cyclone composer all the way to the bottom of the page.
- Uses a compact Cyclone orb, time-aware greeting, and “What can I do for you?” empty-state hierarchy.
- Keeps the existing Ask Cyclone destination, composer, attachments, voice mode, model controls, task cards, routing, and phone mutation paths unchanged.

The Ask-page visual contract tests were updated to match the new empty state and dot-field canvas. Mobile CI must still pass product/repository guards, gateway/MCP checks, Android unit tests, lint, release-candidate assembly, provenance, signing, and update-continuity verification before publication.

Physical Pixel 8 visual acceptance remains UNVERIFIED for this release.
