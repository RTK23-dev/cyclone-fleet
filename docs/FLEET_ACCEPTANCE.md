# Fleet acceptance

Code-path checks run on the real Command Center and real SQLite. The phone RPC in those tests is a harness, not a device. A row that was not run on a phone stays UNVERIFIED.

| Check | Result |
| --- | --- |
| Two phones, one request id, one mission | Passed in `test_fleet_acceptance.py`. Not run on a device. |
| Owner Moment listed, fleet does not answer | Passed in `test_fleet_acceptance.py`. The owner still answers on the Approvals tab. Not run on a device. |
| Locked or asleep phone is not shown as running | Passed against a sleeping device record. Not run on a device. |
| Restart with the same request id does not create a second task | Passed across a new orchestrator on the same SQLite file. Not a kill -9 of a running gateway. |
| Broadcast asks before it runs | Passed. Not run on a device. |
| Stop one mission, stop fleet missions, stop everything | Passed. A direct Command Center task survived the fleet stop and died on stop everything. Not run on a device. |
| Canary holds the rest until continued | Passed. Not run on a device. |
| Empty group addresses nobody | Passed. |
| Offline mid-mission on a real phone | UNVERIFIED |
| Gateway kill -9 mid-mission | UNVERIFIED |
| Handoff on a real phone | UNVERIFIED |
| Scheduled run on a real phone | UNVERIFIED |
| Glass at 100 phones in a browser | UNVERIFIED |
| Load numbers | Not measured. |
