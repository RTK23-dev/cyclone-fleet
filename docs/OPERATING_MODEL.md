# Operating model — Grok on Agent PC + subagents

Same discipline as V4. **Do not start stages until the orchestrator explicitly kicks one.**

## Machine

- Host: `DESKTOP-2FRBSC3` / user `Agent`
- Grok CLI: `C:\Users\Agent\.grok\bin\grok.exe`
- Pattern worktrees: `C:\Users\Agent\Cyclone-one-1.1-sN-*`, `C:\Users\Agent\Cyclone-mobile-4.1-sN-*`
- Phone: Pixel 8 serial `3B171FDJH0061G`
- Repo: `https://github.com/premiumcentraal-boop/Cyclone/`

## Per-stage loop (orchestrator)

1. Write / refresh stage prompt `docs/GROK_ONE11_S*.md` or `docs/GROK_MOBILE41_S*.md` in the stage branch (or pass as Grok prompt file).
2. Create branch from the agreed base (see build plans).
3. Run **one** Grok headless build on the PC for that stage only.
4. **Require subagents** inside the Grok prompt (parallelize tests/docs/UI/MCP/gateway).
5. Wait for PR open + CI signal; do **not** auto-start the next stage.
6. Pixel smoke only when the stage's acceptance needs hardware; mark UNVERIFIED otherwise.
7. Optional: 30m watch routine once a sprint is armed (quiet when unchanged).

## Hard rules

- One stage → one branch → one PR (stack on previous stage branch until merge).
- No Big Bang across One + Mobile in a single Grok run.
- Phone remains authority; PC is glass/MCP only (no second `PhoneToolExecutor` on Windows).
- Never claim physical Pixel pass from CI green.
- Cap Build usage ~12–15% per stage when possible.
- Subagents allowed and **encouraged** for depth/speed inside a stage; orchestrator still advances stages serially.
