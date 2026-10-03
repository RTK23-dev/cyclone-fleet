# Cyclone V5 Alpha 101: ID Generator, the native Ports starter

Developer alpha built on Alpha 100, preserving its codes-from-texts update. Mobile/runtime **5.0.0-alpha.101.dev1**, Android version code **246**, Glass **1.0.0-alpha.56**. The retired Cyclone One window is unchanged.

## Native setup in Glass

Open **Command Center → Ports → ID Generator**. Start **MRZ Studio Local 7.2.1 or later**. Glass discovers Studio's local API from its connection settings or the standard localhost address. Allow the four named ports and click **Connect Studio**. The runtime pairs its durable key directly with Studio, runs the conformance checks, and keeps keys out of the browser. Discovery is read-only and continues while Glass is closed.

Generate manually and edit defaults inside the Studio panels in Glass. Studio retains its existing MRZ/date/number rules, manual or randomized document values, cities and custom cities, height, signature fonts and name modes, portrait crop and background removal. Paul Signature remains the default font with the employee's first name; it does not force every signature to say Paul. Templates and Photoshop remain on the PC.

## Agent and developer control

- Set **When to use** and workflow instructions, disable agent usage, or restrict it to app package IDs and routine IDs. Both restrictions apply when both lists are filled. Settings survive runtime restarts and Glass upgrades.
- Approved guidance reaches phone missions like a skill description. The agent selects the workflow when it matches the owner's request; describing a match does not launch a job or grant permission.
- A portrait comes from an explicit owner attachment, at most 4 MB on the phone. Targeted file and structured extension requests reach only the selected plugin, respecting the Port map and consent.
- Status and files are correlated by request ID and output name. Multiple employee requests do not depend on whichever answer arrives first. Pausing, consent changes and scope changes block further access; outstanding generator results are refused after revocation.
- Copy the agent guide or inspect the live request schema. Developer reference: `tools/cyclone-ports-sdk/starters/id-generator/SKILL.md`. Existing signed Ports messages, sealed verification codes, private-screen checks and cancellation remain in place. Model/provider selection is unchanged.

Studio 7.2.1 fixes the real hub's portrait artifact URL compatibility while retaining the SDK test hub format. Studio remains a separate app with its own launcher and updater; Cyclone does not replace templates or install Adobe software.

## Validation and limits

Gateway owner-policy/route tests, full Glass DOM suite and build, Android unit suite, repository guards, and an end-to-end test using the actual Studio plugin plus real hub HTTP passed locally. That protocol test used a synthetic worker returning placeholder images and is **not** a real Photoshop export. The Studio 7.2.1 Windows ZIP also passed fresh-package startup, shutdown, schema, renderer and offline segmentation checks without npm installation.

Real Photoshop export and a physical phone agent mission remain **unverified** in this release. The installed Photoshop host still needs its blocking application screen resolved. Receiving `queued`, a delivered failed value, or a dry-run placeholder is not generation success. Phone delivery currently supports image outputs; PDF/PSD outputs remain available on the PC in Studio.

Publish through the repository's V5 signed developer-alpha lane. The APK retains the rotated update-compatible signing lineage; Windows build/installer smoke checks and signed release provenance are recorded by that workflow.
