# HANDOFF-00N — <slug>

**From:** orchestrator `mobile` | `glass`  
**To:** one implementation agent  
**Wave:** alpha.1 / alpha.2 / …  
**Branch:** `v5/<team>/<slug>` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):**  
**Do not touch:**

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../README.md)
2. [`orchestrators/CONTRACT.md`](../CONTRACT.md)
3. (slice spec, e.g. `05-secrets-vault.md`)

You are one room in a house. Do not rebuild the house.

## Your individual task

- …

## Required

1. Implement only the task.
2. Tests / guards for the slice.
3. No secret values in logs, fixtures, Graph, MCP.
4. Open a PR. Wait for CI you can run.
5. Write `orchestrators/<team>/returns/RETURN-00N-<slug>.md` using [`AGENT-RETURN.md`](AGENT-RETURN.md).
6. Link the PR URL and commit SHAs in that return. Ping the orchestrator by updating nothing else.

## Out of scope

Anything not in “Your individual task.” Mapper crawl, Louella compiler, encrypted Glass fill, fleet rewrite — unless this handoff names them.

## Success

PR open + return handoff with GitHub evidence + leftover listed honestly.
