# Navigation evaluation

Keep these sentences unchanged between builds. Start each run through Glass Ask and inspect it
in Runs → Goals. Record build/source SHA, device/session, map coverage, outcome, duration,
map/model steps, first failed clause and its proof. Unit tests use fake screens and actions;
they do not establish physical-device success.

| Sentence | Required evidence |
| --- | --- |
| open Gmail and tell me which Gmail I am logged in with | The current account address is read from live account UI; inbox senders and mapping values are insufficient. |
| open Gmail and check which Gmail I am logged in with, then open Chrome and go to instagram.com sign-up with that email | Gmail identity proof precedes Chrome; the same live address is visible in the Instagram sign-up email field. Stop for phone approval before creating/submitting the account. |
| open the clock app and set a timer for 5 minutes | A running five-minute timer is visible; opening Clock or entering a duration alone is insufficient. |
| find the DM of Louella on Facebook | Louella's conversation is open, not just a search result. Native missing binds to Facebook in Chrome. A real login wall requests the Secrets Card. |
| open Settings, then Wi-Fi, then tell me the connected network name | The connected network is read from current Wi-Fi UI; nearby network names are insufficient. |

For mapped routes, confirm each direct door has `decisionSource=map`, an expected room and
a fresh observed room after execution. Exercise a missing selector, an ambiguous selector,
a stale/wrong-room landing and an unchanged screen. None may trigger blind replay.

Facts crossing clauses must identify their live source and appear masked in exported reports.
Dummy/mapping runs cannot supply identity or people. Passwords, OTPs and payment details never
enter the task ledger or reports. Pay/send/delete/permission/authentication boundaries retain
the phone GATE and secrets are entered only through the Secrets Card.

## Evidence log

First build carrying this work: **v5.0.0-alpha.21.dev1** (versionCode 162, Glass 1.0.0-alpha.13). It adds, after review:
learned App Learner routes stop at the same sign-up boundary as model taps; observations record their producer, and an
active mapping pass never feeds the ledger, clause proof or people; plain "open X" sentences keep Stage 1 Fast Path;
single-clause runs may replay compiled skills (clause proof still decides completion); hour-long timers are provable.

**alpha.22 device runs (2026-09-24, owner's phone):** Gmail→Facebook sentence failed twice (after-screen lost on every
launch, one clause bound to Chrome, 429 / 17 s model turns); "open clock and set an alarm for 5 minutes" falsely
completed. Analysis and fixes: `13-alpha23-reliability-plan.md`. Build to retest: **v5.0.0-alpha.23.dev1**.

Physical evaluation of alpha.23: **not run**. No connected phone is required to develop or push checkpoints.
Record real results here or in the navigation return handoff; do not infer them from fake tests.
