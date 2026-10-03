# Fleet phase log

`main` is the only branch. Old `fleet-phase-*` tags were removed. Do not recreate them.

Code on main includes phases 1–6 and the later hardening: live events, queued phones, health report, preflight, persisted pause and do-not-target, retry, canary hold, queued-behind, spend cap, and owner approval display. The fleet does not answer an approval.

Phase 7 device acceptance is not run. Those rows stay UNVERIFIED in `docs/FLEET_ACCEPTANCE.md`.

Events carry `missionId`, `taskId`, `deviceId`, and `status` only. Goals, summaries, and secrets are dropped before publish.

## Phase 2 numbers (50 phones, real broker the socket drains)

- Publish to subscriber: 0.358 ms for 50 task updates. The poll this replaces is 5000 ms.
- Glass apply of those 50 updates: 0.691 ms, 629 µs user CPU.
- A single task finish is on the open Fleet tab from the socket frame, without a page refresh.
- Closing the socket schedules the 5s poll and reconnects at 1s, then 2s, up to 30s.
