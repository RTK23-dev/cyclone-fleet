# Fleet limits

Defaults, overridable by environment. Invalid values fall back to the default.

| Name | Default | Meaning |
| --- | --- | --- |
| FLEET_MAX_PHONES | 32 | Phones the fleet layer will address |
| FLEET_MAX_PER_COMMAND | 16 | Phones in one command |
| store cap | 5000 | Stored missions, open ones kept |

Mission store is `fleet.db` next to the old `missions.json`. Copy that file to back up. A restart reads it again and does not create a second task for the same request id.

Upgrade from alpha.95: leave `missions.json` in place. The first start imports it and renames it `.migrated`.

# Trust

Fleet targeting uses the existing pairing and trust_v33 session. A phone that is unpaired mid-mission is not reported as running and is not given a new task. Approvals stay on the Command Center. This layer does not answer them.

# Acceptance

Device checklist items in FLEET_ACCEPTANCE.md that were not run on a phone stay UNVERIFIED.
