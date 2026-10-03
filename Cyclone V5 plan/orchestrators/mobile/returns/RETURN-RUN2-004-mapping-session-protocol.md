# RETURN — Run 2 Agent 004 — mapping session + protocol

**Source PR:** #152  
**Source branch:** `v5/mobile/mapping-session-protocol`  
**Source head:** `bc5766b853ddedc73238b863a2581618536ed0b8`  
**Combined candidate:** `v5/mobile/alpha2-preview1-combined`

## Delivered

- phone-authoritative mapping session state machine;
- existing Session Kernel / workspace / controller authority reused instead of a parallel lock;
- `atlas.diff` structural journal with phone-issued cursors and resync behavior;
- Android operations: `atlas.diff`, `mapping.start`, `mapping.pause`, `mapping.stop`, `mapping.status`;
- Device Gateway validation/forwarding only;
- exact session/display/workspace generation binding;
- nonterminal `needs-secret` / human-control mapping states;
- bounded mapping budgets and honest `partial` completion semantics;
- readonly MCP remains unable to start mapping.

## Wire

Mapping session states:

`idle | running | paused | needs-secret | human-control | completed | stopped | failed`

The full request/response contract is merged into `orchestrators/CONTRACT.md`.

## Validation history

Source Mobile CI #1105 and PC Companion CI #531 both passed. Final acceptance for integration is the combined alpha.2.dev1 CI.

**Physical Pixel status:** UNVERIFIED.
