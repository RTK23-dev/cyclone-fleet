# Cyclone Mobile 4.2.9

Cyclone 4.2.9 is a Profiles and overlay-control release built from the preserved 4.2.8 release line.

## What changed

- Redesigns Profiles around Android identities rather than per-app workspaces: Active, All profiles, and app Groups.
- Active identities show real app icons, live task state, View progress, and a whole-profile Open action.
- Opening Profile A (the main Android user) no longer requires a secondary-user registry record.
- All-profile cards show friendly names, app counts, and real application icons instead of opaque root-profile IDs.
- Groups are app-centric and report how many identities contain each app.
- Rebuilds profile creation so the operator names the identity first, then selects apps, with the friendly label persisted separately from the opaque journal id.
- Profile management uses compact Open actions and real app icons instead of the legacy per-app green bars.
- Adds a compact model selector to the Cyclone overlay intelligence control. The in-app Ask Cyclone composer does not duplicate that pill inside Settings.

## Release identity

- Package: `com.cyclone.mobile`
- Version name: `4.2.9`
- Version code: `90`
- Previous mobile release: `4.2.8`
- Minimum SDK: 33
- Target SDK: 35

## Validation

The release workflow accepts only the exact successful Mobile CI artifact produced from `release/cyclone-mobile-v4.2.9`, verifies the artifact checksum and metadata, signs it with the historical update-compatible development signer, and verifies certificate continuity against the published 4.2.8 APK before publication.

Physical Pixel 8 acceptance remains **UNVERIFIED** at publication time. The user authorized this cut without a physical-device gate. This release is published for immediate device validation of identity-centric Profiles, friendly naming, and overlay model selection.

No Windows component versions are advanced by this release. Cyclone One remains 1.1.2 and device gateway / MCP remain 4.1.0.
