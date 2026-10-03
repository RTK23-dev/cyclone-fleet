"""Phone care (alpha 87): keep Cyclone on the phone current and understand why it stopped.

- `versions`: which Cyclone the phone has (read with a fixed `dumpsys package` read, so it works before pairing).
- `apk`: the phone build for this PC's release, downloaded from GitHub and refused unless its SHA-256 is in the
  release manifest (the same rule `cyclone update` uses).
- `install_errors`: Android's install failures in plain words, each with one thing to do.
- `updater`: one owner-started update job per phone (`adb install -r`, never a downgrade, never an uninstall).
- `health`: the phone's `health.report` (why Android ended Cyclone, the freezes it caught), kept per phone and in
  the diagnostics session.
- `verdict`: all of it as one calm answer for Glass, with the raw evidence under `details` for developers.
"""
