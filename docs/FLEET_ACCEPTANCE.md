# Fleet acceptance

Run on real phones. Anything not run on a device stays UNVERIFIED.

| Check | Result |
| --- | --- |
| Health report has no message content | Code only. Phone report is version 1 facts. Not run on a device. |
| Preflight warns on low battery or camera off, owner can proceed | Code only. Not run on a device. |
| Two or more real phones | UNVERIFIED |
| Owner Moment sent by the owner | UNVERIFIED |
| One phone locked or asleep stays queued | Code path only. UNVERIFIED on a device. |
| One phone offline mid-mission | UNVERIFIED |
| Gateway restart mid-mission | UNVERIFIED |
| Broadcast with confirmation | Code path only. UNVERIFIED on a device. |
| Stop one mission / stop fleet missions / stop everything | Code path only. UNVERIFIED on a device. |
| Handoff pipeline | Owner-started route. UNVERIFIED on a device. |
| Canary rollout | Code path only. UNVERIFIED on a device. |
| Scheduled run | UNVERIFIED |
| 100+ phones in Glass | Windowed list only. Not measured in a browser this session. |
| Load numbers (CPU, memory, latency) | Not measured. No numbers reported. |
