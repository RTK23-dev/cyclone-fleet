# Cyclone V5 Alpha 58 dev2: mapping reads screens that keep moving

A fix release on Alpha 58. It includes everything in Alpha 58 (the API maker and cards).
- **Mobile:** `5.0.0-alpha.58.dev2` (version code 203).
- **Glass:** `1.0.0-alpha.34`.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.58.dev2.exe`. The runtime is unchanged (`5.0.0-alpha.58.dev1`);
  the package carries the new Glass.

## The problem

Mapping some apps (seen on ChatGPT, "My account · look only") stopped at once with:

> Mapping stopped: OBSERVATION_MISSING. The pass stopped with an error (OBSERVATION_MISSING). Nothing was changed on
> the phone.

## Where it was

- **The capture.** The phone reads a screen, then checks that the screen did not change while it was being read. Every
  accessibility event counts as a change. Apps that animate, stream text or update often (ChatGPT does all three) keep
  changing, so the check fails and the capture is refused (`SemanticCaptureBoundary`).
- **The Mind** already retried a refused capture up to 5 times.
- **The mapper did not retry at all.** On the first refusal, `GatewayMappingObservationPort.freshObservation` threw
  the error away (`runCatching { … }.getOrNull()`). `SafeMapperWalker` then saw no observation and failed the pass
  with the bare code `OBSERVATION_MISSING`, which hid the real cause.
- **The settle wait** before a capture waited at most 300 ms and watched only one event clock, so it did not wait for
  the screen being mapped to go quiet.

## What changed

- **The mapper retries a moving screen.** It makes up to 5 attempts. Each one waits longer for that display to stop
  changing (up to 1, 2, then 3 seconds of waiting for a quiet moment), with a short pause between attempts.
- **The last attempt accepts a moving screen, for mapping only.** It keeps a capture where only the content moved,
  provided the windows, screen size, rotation and scope are the same before and after. Mapping never types or pays,
  and each tap still goes through PhoneToolExecutor's own freshness checks. The Mind and every other capture keep the
  strict check.
- **Real failures stop at once**, without retrying: Accessibility off, the display gone, or a scope change.
- **The report names the cause.** The code now reads, for example, `OBSERVATION_MISSING_SCREEN_KEPT_CHANGING` or
  `OBSERVATION_MISSING_ACCESSIBILITY_OFF`. Glass adds a plain sentence about what to do.

## Validation and limits

Tests that pass:
- **Phone:**
  - `ObservationRetryTest` (new, 4 tests):
    - a screen that keeps changing is retried with longer quiet waits, and only the last attempt tolerates moving
      content;
    - the first good capture wins, with no extra waiting;
    - a real failure (Accessibility off) stops after one try and keeps its cause;
    - a screen that never settles, even tolerated, reports `SCREEN_KEPT_CHANGING` after 5 attempts.
  - `SafeMapperWalkerTest` adds `anUnreadableScreenReportsWhy`;
  - `SemanticCaptureBoundaryTest` adds a test that a content-only change is accepted only when asked for, and a
    screen-size change is refused even then.
  - The full phone unit suite passes (2008 tests).
- **Glass:** a test for the plain-words hint. All 196 Glass tests pass.

Limits:
- **Physical: UNVERIFIED.** The cause was found by reading the code, not on the phone. Mapping ChatGPT again on the
  Pixel is the test.
- **If it still fails,** the report now says why. `SCREEN_KEPT_CHANGING` means the app moved for the whole wait (for
  example a video playing).
- **Slower on busy screens:** a screen that keeps changing can take up to about 11 seconds before the pass gives up.
  Screens that settle normally are read as fast as before.
