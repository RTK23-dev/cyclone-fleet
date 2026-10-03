# Cyclone Mobile 4.3.1 — Ask Cyclone + PC Gateway visual refresh

Base: published v4.3.0, a573a59d3e76b4ba054150adba71619006277ee0.
Mobile version 4.3.1 / versionCode 92. Other component versions unchanged.

Publication authorized by the user on September 10, 2026. The visual implementation
passed Cyclone Mobile CI on its release-candidate branch in run 34480093706. The exact
release source on `release/cyclone-mobile-v4.3.1` must independently pass the same
unit-test, lint, release-assembly, provenance, APK-identity and update-signing checks
before GitHub publishes the release.

## Ask Cyclone overlay

The resting overlay is visually tighter and no longer presents the previous nested
box-inside-box settings treatment. The Tune control opens one compact transforming
settings pill above the composer instead of expanding a large settings panel inside
the overlay.

The settings sequence is deliberately simple:

1. Model — shows the current model and opens the model selector.
2. Intelligence — Low / Medium / High.
3. Phone autonomy — Ask often / Balanced / Independent.
4. Returns to Model after the autonomy choice is saved.

Pressing Tune again closes the pill. Existing model, reasoning and phone-autonomy
stores remain authoritative; this release changes presentation rather than creating a
second settings system.

## PC Gateway

The PC Gateway is rebuilt around Cyclone's current compact card language and removes
the old text-heavy instructional hierarchy from the primary surface.

Live Phone is labeled for Cloud AI agents rather than one vendor and exposes a clear
ON / PAUSED / OFF state with direct controls. USB bridge, Phone control and Cyclone AI
trust are shown as separate status rows so a ready USB/control path is not presented as
a connection failure merely because AI trust has not yet been granted.

When USB bridge and Phone control are already ready, missing Cyclone AI trust is shown
as the remaining setup step. Clipboard controls, trusted-PC revocation, fallback
pairing, diagnostics and existing safety boundaries remain available, with secondary
setup and technical information collapsed away from the main control surface.

## Safety and architecture boundaries

PhoneToolExecutor + GATE remain the phone mutation authority. This release does not add
a Windows-side mutation engine, does not merge the four execution planes, and does not
weaken confirmation requirements for sensitive phone actions.

Physical Pixel / USB / ADB UI acceptance: UNVERIFIED at release cut unless separately
recorded after installation. GitHub CI verifies code, lint, release assembly, artifact
identity, checksum, provenance and signing continuity; it does not substitute for
physical UI acceptance on the owner's device.
