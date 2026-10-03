# Fleet code review — alpha.99 fleet layer (review of RTK23-dev/cyclone-fleet main)

Method: ran the original fleet test suite against this code, read the new store/orchestrator/API, wrote a reproduction for every
suspected bug against the real Command Center and real SQLite (only the phone RPC is faked), fixed it, and re-checked that the
test fails when the fix is undone. Not run here: real FastAPI, pytest-only files, `npm run build`, any phone.

## Bugs found and fixed (all reproduced first)
| # | Severity | Bug | Fix |
|---|---|---|---|
| 1 | High | `rollout()` raised `KeyError: 'error'` on every normal use (held-back rows had no `error` key). The half-written mission then made `GET /missions`, `queue()`, `health()` raise for every client. | Rows carry `error`; `_snapshot` reads rows defensively; held-back phones show as QUEUED with a reason, not FAILED. |
| 2 | High | `continue_rollout` ignored Pause and Do-not-target, and did not check the phone was paired/connected. | Refuses when paused; skips excluded/unavailable phones with a stated reason. |
| 3 | High | Front task showed "QUEUED, queued behind 1" as soon as a *later* task was added. | `_queued_behind` counts only tasks ahead; command-center open-task query now tie-breaks on `rowid`. |
| 4 | Medium | A crash between reserve and commit left an empty ghost mission on disk; the owner's retry returned "dispatched" with zero phones, and cleanup protected the ghost forever. | Placeholder stays in memory only; startup sweeps empty ghosts (`delete_empty`). |
| 5 | Medium | Retried and rolled-out tasks were never bound to their mission: no events, no spend-cap accounting for them. A refusal mid-batch also left memory and disk disagreeing. | `bind_task` on retry/rollout; per-phone refusal recorded and the batch continues; state saved in `finally`. |
| 6 | Medium | N+1: one `list_tasks` query per phone row on every poll, and preflight capped at 200 open tasks. | One open-task index per request; limit 5000. |
| 7 | Medium | CSV export: formula injection from phone-written summaries; commas/newlines broke columns. | `csv` writer + leading `= + - @ \t \r` neutralised. |
| 8 | Medium | `trim()` used `NOT IN (?,…)` with every protected id (SQLite variable limit) and never removed `fleet_task` rows; `_open_mission_ids` scanned the whole table on every dispatch. | Chunked deletes; task bindings removed with their mission; trim throttled to once a minute. |
| 9 | Low | Nickname route accepted any string as a phone id. | 404 unless the fleet knows the phone (or it already has a nickname). |
| 10 | Medium (UX) | Preflight warnings (battery, permission, busy phone) were computed, forced a confirm, and were then dropped by Glass. | Glass parses and shows them in the confirm card. |
| 11 | Process | The previous commit deleted the fleet parser/orchestrator/scenes/nickname tests, the fleet CI guard and Glass fleet tests, leaving one 47-line file. | Restored and adapted (see `tests/`), guard tightened to match calls/POST routes rather than the word "approvals". |

## Still open (not fixed here)
- `_open_mission_ids()` still walks all missions (now at most once a minute); index it (open task id -> mission) instead.
- `stop_fleet_missions` tries to cancel every task id of every stored mission; intersect with open tasks first.
- `dispatch` still creates every task inside the HTTP request; measure at 30/100 phones, then move to a background worker if slow.
- `/v1/fleet/health` lacks loop liveness and last-tick age.
- The placeholder-in-memory + startup-sweep fixes are two independent defences; only the sweep has its own test.
- Everything above is sandbox-verified. Phone acceptance is still UNVERIFIED.
