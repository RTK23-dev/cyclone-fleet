# 07 — Ask compiler

When a prompt names apps and people, compile a **sketch from the atlas**, then execute with eyes. Do not compile a 12-tap macro. Do not fall back to the 4.8 login-status template.

## Steps

1. Keep the sentence. It is law.
2. Bind **places** from the catalog (Facebook → Chrome host if the map says native is absent).
3. Bind clauses to **capabilities** (`which email` → `FIND_SIGNED_IN_IDENTITY`, not `SESSION_STATUS`).
4. Bind names to People memory, else `SEARCH_PERSON`.
5. Show a sketch as stages (presentation only).
6. Execute. On a known route, take the next door directly when the screen matches the expected room (`decisionSource: map`), and check the room after it. Otherwise see-think-act with the sketch in context (`decisionSource: model`). Eyes win if the edge is stale.
7. `needs-secret` → same card as mapping.
8. Critic: goal-drift, dummy-vs-live, payment.

## Louella sketch (example, not a script)

```text
Gmail / FIND_SIGNED_IN_IDENTITY
  → read slot signed-in-email (live, from header, mark which is current)
Chrome · facebook.com
  → if SESSION_STATUS = out: secrets card, then login
  → OPEN_DM / SEARCH_PERSON “Louella”
  → People memory: Louella ≈ that thread
```

HUD:

```text
Gmail — Email found · j***@gmail.com
Facebook in Chrome — Opening Louella’s chat
Needs you — Facebook password     ← only if the wall is real
```

## What to change in 4.8

- Destination authority regex titles (“Checking Instagram login status”, “Checking Chrome · Facebook login status”) must not be the default meaning of account-related English.
- Keep destination-scoped **until** as **proof** (we are on facebook.com, we can see Louella’s thread).
- Skill Compiler: high-confidence atlas edges may replay (`pageKey + sessionId + displayId`). Miss → look. Unchanged → not a second click.
- Fast Path, GATE, overlay yield, Human Gesture completion — unchanged physics.

## Tests that must exist

- Sentence “find the DM of Louella” never presents as a login-status-only run.
- Dummy mapping email is not returned as current Gmail identity.
- Native-missing Facebook binds to Chrome origin when the atlas says so.
- Stale edge: executor looks, does not fire the saved tap list.
- Known route with matching rooms: steps are recorded as `decisionSource: map` and take no model call.
- Every run records room, decision source and cause of death for the [run inspector](11-run-inspector.md).
- Payment screen during an Ask that did not ask to pay: GATE stop.
- Secrets wall: `needs-secret`, not `Couldn't finish`.
