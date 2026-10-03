# V5 orchestrators

Two orchestrators. They do **not** write product code. They read the generation plan, issue **three agent handoffs**, track returns, and keep GitHub organized.

| Orchestrator | Owns | Path |
|---|---|---|
| **Mobile 5.0** | Phone: vault, atlas, mapper, Ask compiler, overlay, GATE. Protocol *schemas* (source of truth). Gateway *server* ops. | [`mobile/`](mobile/ORCHESTRATOR.md) |
| **Glass 1.0** | Local web dev dashboard (`apps/glass`): Apps, **Maps + Scenarios**, **Run inspector**, Knowledge, Phone control. Never a second brain. See [03](../03-glass-v1.md). | [`glass/`](glass/ORCHESTRATOR.md) |

The **connection** is not a third team. It is [`CONTRACT.md`](CONTRACT.md). Both orchestrators must keep it true. Neither side ships if the contract is a lie.

## GitHub layout (do not invent a parallel tree)

```text
Cyclone V5 plan/orchestrators/
  README.md                 ← you are here
  CONTRACT.md               ← shared pipe; both update, neither forks
  TEMPLATES/
    AGENT-HANDOFF.md
    AGENT-RETURN.md
  mobile/
    ORCHESTRATOR.md         ← team prompt (standing orders)
    HANDOFF-000-start.md    ← first orch brief
    STATUS.md               ← living board; orch updates after every return
    agents/                 ← orch writes numbered handoffs here
    returns/                ← agents write RETURN-*.md here (with PR links)
  glass/
    (same shape)
```

Code does **not** live in this folder. Code lives in `apps/mobile`, `apps/glass` (Glass web app; `apps/pc-companion` is Cyclone One), `apps/device-gateway`, `protocol/`, `tools/*mcp`. This folder is command and memory.

## Branch rules

| Kind | Name | Base | Lands |
|---|---|---|---|
| Integration | `v5/integration` | `release/cyclone-mobile-v4.8.0` + this plan from `main` | Orchestrators create this first |
| Mobile agent | `v5/mobile/<slug>` | `v5/integration` | PR into `v5/integration` |
| Glass agent | `v5/glass/<slug>` | `v5/integration` | PR into `v5/integration` |
| Handoff-only docs | `docs/v5-orch-…` | `main` | Only if no code |

**Do not branch from stale `main` for product code.** `main` may lag 4.8. Do not commit into the other team's `apps/` tree. Gateway op *handlers* are mobile-led; Glass owns the TypeScript client.

## Loop

```text
Orch writes agents/HANDOFF-00N-….md
  → agent implements on v5/<team>/<slug>
  → agent opens PR, pushes
  → agent writes returns/RETURN-00N-….md (PR URL + SHAs + leftover)
  → orch updates STATUS.md
  → orch writes the next handoff (004…) or a fix-forward
```

Agents always: (1) read the **total picture** linked in the handoff, (2) do **only** their individual task, (3) return with GitHub evidence.

## First wave (already drafted — orch issues or amends, then launches)

Mobile: [001](mobile/agents/HANDOFF-001-protocol-need-secret.md) · [002](mobile/agents/HANDOFF-002-vault-secrets-card.md) · [003](mobile/agents/HANDOFF-003-atlas-follow-me.md)

Glass: [001](glass/agents/HANDOFF-001-shell-ask-secret.md) · [002](glass/agents/HANDOFF-002-maps-canvas.md) · [003](glass/agents/HANDOFF-003-atlas-client.md)

## Picture

Generation plan: [`../README.md`](../README.md). Cuts: [`../09-cuts-and-milestones.md`](../09-cuts-and-milestones.md). This wave is **alpha.1** plus the **start of alpha.2** (atlas store + read-only Maps board). Mapper crawl and Louella compiler are **not** this wave.
