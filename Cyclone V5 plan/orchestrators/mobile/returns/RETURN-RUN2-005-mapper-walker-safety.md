# RETURN — Run 2 Agent 005 — safe mapper walker

**Source PR:** #151  
**Source branch:** `v5/mobile/mapper-walker-safety`  
**Corrected source head included in combined candidate:** `8c0027c501032a3678fa4202efc6f9727dc04cf4`  
**Combined candidate:** `v5/mobile/alpha2-preview1-combined`

## Delivered

- Android mapper decision engine under `mapping/crawl/**`;
- fresh-observation-first decisions;
- exactly one screen-changing mutation per decision;
- mandatory after-observation before continuation;
- authority recheck around mutation;
- pay/send/delete/logout-all/GRANT boundaries are never crossed autonomously;
- needs-secret pause seam contains metadata only;
- immediate human/companion-control stop;
- bounded elapsed time, new screens, no-progress and per-door attempts;
- unchanged landing does not trigger blind repeat click;
- structural Atlas writes are pinned to `persona=mapping`;
- content/person labels are not used as durable room identity.

## Validation history

The source PR failed compilation only because a nullable `mutation.errorCode` was lowercased without a safe call. The combined source includes the null-safe correction. Final acceptance is the combined alpha.2.dev1 CI.

## Integration seam

This checkpoint includes the walker and the Agent-004 session/control plane together. Agent 006's canonical Chrome Place resolver is still absent, so Chrome-host autonomous mapping remains out of scope.

**Physical Pixel status:** UNVERIFIED.
