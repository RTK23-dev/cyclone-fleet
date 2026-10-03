# HANDOFF-000 — Mobile orchestrator start

**To:** Mobile 5.0 orchestrator  
**From:** generation plan (2026-09-21)  
**Standing prompt:** [`ORCHESTRATOR.md`](ORCHESTRATOR.md)

This is your first brief. You do not write Kotlin in this handoff.

## Total picture

Cyclone Mobile 4.8.0 can tap, overlay, GATE, Follow Me, Graph v2. It cannot: pause for a password without failing, remember an app as a house, or keep “find Louella” as the verb.

V5: vault + atlas + mapper + Ask compiler. Cuts in [`../../09-cuts-and-milestones.md`](../../09-cuts-and-milestones.md). You are executing **alpha.1** and the **atlas foundation of alpha.2**. Glass is doing the operator board in parallel. [`../CONTRACT.md`](../CONTRACT.md) is the pipe.

## Your individual task (now)

1. Create GitHub branch **`v5/integration`** from `release/cyclone-mobile-v4.8.0`. Merge `Cyclone V5 plan/` from `main` if it is missing on that line. Push. Record the SHA in [`STATUS.md`](STATUS.md).
2. Issue **three** agents using the drafts in [`agents/`](agents/) as their prompts. If you amend, commit the amendment **before** they start, on a docs commit to `v5/integration` or `main`.
3. Each agent prompt must include: link to the generation README, CONTRACT, their HANDOFF file, allowed paths, branch name `v5/mobile/<slug>`, PR target `v5/integration`.
4. Track them in STATUS: assigned / PR / blocked.
5. Do not implement 001–003 yourself. Do not start HANDOFF-004 until at least two returns exist or an agent is blocked on you.

## Launch order

```text
001 protocol-need-secret     first (schemas + NEED_SECRET type)
002 vault-secrets-card       after 001 is on integration, or parallel with a stub agreed in STATUS
003 atlas-follow-me          after schema names in CONTRACT are stable
```

Glass 001 (Ask waiting-for-secret) needs your `needs-secret` presentation snapshot. Glass 002 (Maps canvas) can mock until 003 can serve `atlas.get`. Tell Glass orch that in STATUS.

## Success

- `v5/integration` URL on GitHub
- Three `agents/HANDOFF-00N-*.md` files treated as issued (unchanged or amended + committed)
- `STATUS.md` lists the three agents and their branches
- A one-line note in CONTRACT handshake if you and Glass agreed a stub

## Return (you, when wave 1 is in)

When 001–003 have returns, write `returns/RETURN-000-orch-wave1.md`: links to the three PRs, what Glass is unblocked to do, what HANDOFF-004 should be (likely mapper session **or** Ask compiler — not both).
