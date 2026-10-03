# HANDOFF-000 — Glass orchestrator start

**To:** Glass 1.0 orchestrator  
**From:** generation plan (2026-09-21)  
**Standing prompt:** [`ORCHESTRATOR.md`](ORCHESTRATOR.md)

This is your first brief. You do not write TypeScript in this handoff.

## Total picture

One 1.5.5: fleet, JPEG live, handoff, MCP, camera. Missing: Ask on the desk, a **full mapping canvas** like MiniTest, a vault that never stores passwords on the PC.

Glass 1.0 is that HUD. Mobile holds the atlas and the keys. You show the house. Spec: [`../../04-app-maps-canvas.md`](../../04-app-maps-canvas.md). Pipe: [`../CONTRACT.md`](../CONTRACT.md).

## Your individual task (now)

1. Wait for / confirm **`v5/integration`** (Mobile orch creates it from 4.8.0 + this plan). Put the SHA in [`STATUS.md`](STATUS.md). If it does not exist after you start, open a GitHub issue `v5-orch-sync` and still issue agents against `v5/integration` as the intended base.
2. Issue **three** agents from [`agents/`](agents/). Amend on GitHub before launch if One’s nav files moved.
3. Each agent prompt: generation README, CONTRACT, **04-app-maps-canvas.md** for 002, their HANDOFF, allowed paths, branch `v5/glass/<slug>`, PR target `v5/integration`.
4. Track in STATUS. Tell Mobile orch (STATUS note) that 001 needs `needs-secret` in the snapshot and 003 needs `atlas.get`.
5. Do not implement 001–003 yourself. Do not start mapping-live-cursor (alpha.3) in this wave.

## Launch order

```text
001 shell-ask-secret     first (nav + Ask + waiting-for-secret + Vault stub)
002 maps-canvas          parallel OK with a mock graph module 001 only mounts
003 atlas-client         after 001 nav exists; swaps mock for atlasClient
```

Mobile 001/002 unblock real `needs-secret`. Mobile 003 unblocks real Gmail house. Until then 002’s mock must still look like Mini (dotted board, cards, edges, zoom, inspector).

## Success

- STATUS names `v5/integration`
- Three handoffs issued
- Agreement with Mobile on snapshot field and `atlas.get` shape (CONTRACT boxes)

## Return (you, when wave 1 is in)

`returns/RETURN-000-orch-wave1.md`: three PR links, whether the board can pan a mock **and** a real `atlas.get`, what 004 is (likely mapping cursor + Start, **after** mobile mapper exists — do not assign 004 early).
