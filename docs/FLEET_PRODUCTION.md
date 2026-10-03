# Fleet production notes

Phases 8-12 are in the code. Phases 13 and 14 are not verified.

Restart-safe: pause, do-not-target, and the mission spend cap are stored in fleet.db. Retry updates the same mission and stops at 3 tries. Canary phones start first; the rest stay on that mission until continue, and a failed canary does not start them. skipDeviceIds drops warned phones. The same request id still returns the same mission.

One scheduler: scenes still run through dispatch. Command Center routines remain the clock. Groups still address no phones when empty.

Owner view: /v1/fleet/health reports pause, queue depth, excluded phones, spend cap, and event subscribers. Export is JSON. export_csv returns a CSV string. Needs-you still goes to the existing approval card. The fleet does not answer it.

Glass: phone search and a 40-row window. Not measured at 100 phones in a browser this session.

Backup: copy fleet.db and the Command Center database. Restore those two files, then start the gateway. Open tasks stay in the Command Center database, so a killed gateway does not invent a second task for the same request id.

Not measured: dispatch latency, event lag, CPU, and memory on 10, 30, or 100 devices. Caps stay 32, 16, and 5,000.

Not run on a phone: Owner Moment, locked, offline, gateway restart, broadcast, stops, handoff, scheduled run. Those stay UNVERIFIED.
