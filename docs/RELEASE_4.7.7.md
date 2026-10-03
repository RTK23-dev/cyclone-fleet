# Cyclone Mobile 4.7.7 — One conversation panel

Android versionCode 137, based on 4.7.6.

Ask Cyclone now groups chat messages and automation progress inside one rounded teal glass panel, both in the app and in the global overlay. User messages and task cards remain smaller elements inside that surface; assistant text sits directly on the shared panel. The Ask capsule stays separate and anchored below it.

The overlay uses one scroll viewport for conversation, progress, queue, sharing status and tools. The viewport receives the space remaining after the composer and drag handle are measured. Its overall height is bounded by the screen and keyboard, so additional context stays scrollable instead of pushing later sections outside the window. Long task requests and acknowledgments are no longer truncated to two or three lines. In-app conversations retain their lazy scrolling inside the same shared panel.

Task execution, confirmations, notification actions, keyboard lifting and the existing collapse gesture are retained. Physical-device visual, gesture and keyboard acceptance remains UNVERIFIED. Repository guards, unit tests, lint and release assembly gate publication; signing continuity is checked against 4.7.6.
