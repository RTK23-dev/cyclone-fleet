# 01 — Headset and laws

We are not building a restrictive harness. We are building a flexible **tech headset**.

A harness compiles the user’s sentence into a preset checklist (login-status, waypoints, regex titles) and then walks the list. That is how 4.8 turned “find Louella’s DM” into “Checking Chrome · Facebook login status.”

A headset gives the agent **eyes, hands, a memory, and a critic**, then lets it improvise toward the **unchanged goal sentence**.

## Headset parts

| Part | Job |
|---|---|
| Goal | Immutable English sentence. Never rewritten into an enum. |
| Tools | Physical phone verbs: tap, swipe, type, open app, open URL, wait, screenshot, a11y tree. |
| Memory | Atlas (doors and rooms), vault (keys), people (Louella). Hints, not rails. |
| Loop | See → think → one action → look. |
| Critic | Goal-drift, dummy-vs-live, payment, stale edge. |
| Human | First-class tool: secrets card, Take control, pin “this is DMs.” |

Minitap’s Mini still **looks every step**. Steal that. Cyclone goes further: a mapped route lets it skip the *thinking*, never the *looking*.

## Laws (or V5 is 4.8 with a graph sticker)

1. **Map drives, eyes confirm.** On a known route Cyclone takes the next door without asking the model when the screen matches the expected room, and checks the room after every door. Mismatch or failed door → look and think. Never replay doors blind.
2. **Sentence is law.** Maps do not rewrite “find DM” into “check login.”
3. **See or it isn’t real.** Slot values are read **now**, not remembered from mapping day.
4. **Secrets only through the card.** Not chat, not screenshots, not Download logs, not MCP, not Glass disk.
5. **No pay.** Hard GATE. Mapping and Ask.
6. **Doors, not infinite scroll.** Feeds are one node with a content region, not a thousand pages.
7. **Places, not packages only.** Chrome + host is a place. Native-missing is data.
8. **Dummy ≠ you.** Two personas. Mix them and the product is wrong.
9. **Phone thinks and mutates.** Glass has no agent, no planner, no model calls and no PhoneToolExecutor. It shows, inspects and sends commands.
10. **Proof is pixels / a11y.** Transport success is not task success. Unchanged is not a second click.
11. **Every run is inspectable.** Any run can be opened step by step with its cause of death. If you can't see why it failed, you can't make the map better.

## How we stop the checklist reflex in code

- No intent enum that every account sentence falls into (`LOGIN`, `SESSION_STATUS` as default).
- Destination-scoped **until** is a **proof**, not a title template.
- `AppGraphRetriever.findBestPath` returns a **sketch** shown as stages. The executor still decides the next tap from the current screen.
- Tests must reward unexpected successful paths (Gmail identity via search, not only via avatar).
- Mapping budget is “new doors,” not “complete the built-in story list.”

## Elon-simple test

If you cannot explain a V5 behavior in one sentence to the operator, it is not smart enough to ship.

- *“I mapped Gmail. The board is the house. Ask still looks.”*
- *“Facebook wants a password. Type it in the card. Cyclone does not keep it in chat.”*
- *“Louella is a person in your live Facebook, not in the dummy account.”*
