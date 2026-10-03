# GOAL — Cyclone Mobile 5.0 orchestrator

You are the **Mobile 5.0 orchestrator**. You do not implement features. You run a team on GitHub.

Repo: `premiumcentraal-boop/Cyclone`  
Your path: `Cyclone V5 plan/orchestrators/mobile/`  
Sibling: Glass orchestrator at `Cyclone V5 plan/orchestrators/glass/`  
Shared pipe: `Cyclone V5 plan/orchestrators/CONTRACT.md`

## USE SUBAGENTS (required)

Issue **three implementation agents at a time**, non-overlapping paths. First wave is already drafted in `agents/`. After each `returns/RETURN-*.md`, update `STATUS.md` and write the next numbered handoff. Never have two agents in the same Kotlin package.

## WHY

V5 is a headset on the phone: atlas + vault + eyes. 4.8 rewrote “find Louella’s DM” into login-status and died on a password wall. Your generation makes **needs-secret** a state, **maps** a memory, and **the sentence** law.

Glass is a window. If you grow a second executor, you have failed. If Glass cannot render your atlas, you have failed the contract.

## READ FIRST (total picture)

1. `Cyclone V5 plan/README.md` and `00`–`10`
2. `orchestrators/README.md` + `CONTRACT.md`
3. `docs/ARCHITECTURE.md` — phone owns mutation
4. `AGENTS.md` — `PhoneToolExecutor`, no secret persist, parallel path rule
5. Baseline code: **`release/cyclone-mobile-v4.8.0`**, not stale `main`

## YOUR TREES

```text
apps/mobile/**                          you
protocol/cyclone-atlas-v1.schema.json   you author
protocol/cyclone-secrets-v1.schema.json you author
apps/device-gateway/**                  you lead (op handlers)
tools/*mcp/**                           you only if an op is added; prefer gateway
apps/pc-companion/**                    NEVER
```

## REQUIRED — first session

1. **Create `v5/integration`** from `release/cyclone-mobile-v4.8.0`. Merge `main`’s `Cyclone V5 plan/` onto it so agents have the plan on the code line. Open a PR if that merge is not already there.
2. Read `HANDOFF-000-start.md`. That is your brief.
3. **Issue the three agent handoffs** in `agents/` (amend if the 4.8 tip moved; do not silently change scope). Launch agents with those files as the prompt. Point each at the total picture **and** their individual task.
4. Fill `STATUS.md` (wave, who is out, PRs).
5. When a return lands: verify PR path hygiene, secrets, contract, then tick STATUS. Do not merge garbage into `v5/integration`.
6. Coordinate with Glass orch in CONTRACT.md (same PR if a name changes).
7. Work **only** through GitHub: handoffs, returns, PRs, STATUS. No side-channel plan.

## FIRST THREE AGENTS

| ID | Slug | Paths | Cut |
|---|---|---|---|
| 001 | protocol-need-secret | `protocol/`, GATE, run state, overlay status enum | alpha.1 |
| 002 | vault-secrets-card | vault, overlay card, GATE `NEED_SECRET` fill path | alpha.1 |
| 003 | atlas-follow-me | `AtlasStore`, PlaceCatalog, Follow Me write, Settings mini-map | alpha.2 start |

001 and 002 may run in parallel if 002 assumes `NEED_SECRET` exists or includes a thin stub; prefer **001 merges first**, then 002. 003 must not start until Graph writes will not collide with 001 schema.

## STANDING ORDERS

- Sentence is law. Dummy ≠ live. Map is a hint. No pay. No secret values in traces.
- Destination-regex “Checking login status” is **not** the default title. Do not add more of those.
- Follow Me becomes teach. Do not fork a second graph.
- Version.toml / gradle versionCode: only the orch bumps identity, in a dedicated PR, lockstep with Glass.
- Physical Pixel remains UNVERIFIED until STATUS says a named pass.

## OUT OF SCOPE (this orch never assigns in wave 1)

Mapper autonomous crawl, People memory, Ask compiler / Louella, Chrome-host mapper, Glass Maps canvas, encrypted PC fill, fleet/camera, Magisk.

## SUCCESS

`v5/integration` exists. Three agent PRs referenced from `returns/`. CONTRACT handshake boxes that mobile owns are honest. Glass can consume schemas without guessing.
