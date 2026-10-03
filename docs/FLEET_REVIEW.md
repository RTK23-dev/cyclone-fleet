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

## Severe pass on alpha.102

| # | Severity | Bug | Fix | Verified |
|---|---|---|---|---|
| 12 | High | A full change queue dropped the newest task update and left Glass wrong until the socket died. | The queue drops the oldest and keeps the newest. Glass resyncs every 5 seconds even while the socket is up. Health reports `changesDropped`. | `test_full_change_queue_keeps_the_newest` passed. |
| 13 | Medium | Open-mission protection walked every stored mission. | Open task ids are looked up in `fleet_task`, 500 at a time. Held-back canary rows are still protected. | Code path used by trim. No phone. |
| 14 | Medium | The ghost sweep had no test of its own. | `test_empty_mission_is_swept_on_start`. Undoing `delete_empty()` makes that test fail. | Failed when the sweep was removed, passed when restored. |
| 15 | Low | Health did not say how long since the Command Center ticked. | The loop records `_last_tick_ms`. Health returns `lastTickAgeMs` and `loopAlive`. | Field is present. Loop age was not measured on a running gateway. |

Stop fleet missions already cancels only open fleet tasks. A command of 30 or 100 phones is refused by the 16-phone cap before any task is created. That refusal was run. It is not a latency number.

## Step 2 on alpha.102

| # | Severity | Bug | Fix | Verified |
|---|---|---|---|---|
| 16 | High | A mission row with bad JSON raised and broke the list. | `page()` treats bad notes or assignments as empty. | `test_a_poisoned_row_does_not_break_the_list` passed. |
| 17 | High | A corrupt `fleet.db` stopped the gateway from opening the store. | The file is renamed `.broken` and a new store is opened. `open_error` says why. | `test_a_corrupt_database_is_quarantined` passed. |
| 18 | Low | A dispatch failure was re-raised with no log line. | `log.exception` includes the mission id and no goal. | Log call is on the existing re-raise path. |

Caps stay 16 per command and 32 phones, from `FLEET_MAX_PER_COMMAND` and `FLEET_MAX_PHONES`. They were not raised. 30 and 100 phones were not measured on devices. Dispatch still creates tasks inside the request. The orchestrator was not split. `kill -9` of a running gateway was not run. Glass fleet view has no `innerHTML`.

Gateway and CI suites exited 0 before this pass. The new tests exited 0.

No phone, no emulator, no Glass browser, no `npm test` on this pass. Owner Moment, locked, offline, restart, and broadcast stay UNVERIFIED. Dispatch latency at 30 and 100 phones was not measured because the cap refuses them.
