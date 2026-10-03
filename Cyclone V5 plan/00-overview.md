# 00 — Overview

## One sentence

**App Maps** is a user-started explorer that walks an app (or a website in Chrome), learns what screens exist, what they are for, and how to get between them, then keeps that map fresh so any later Ask can *see* instead of rediscover.

The map is a memory. The live run still looks. If the map is wrong, the eyes win.

## Control loop

```text
                    ┌──────── Cyclone Glass 1.0 (PC browser, local) ────────┐
                    │  Apps · Map · Scenarios · Versions · Runs (autopsy)    │
                    │  Knowledge · Phone (live, take control, Ask)           │
                    │  no agent, no model calls — the developer's eyes       │
                    └───────────────┬────────────────────────────────────────┘
                                    │ gateway 5.0  (no passwords in traces)
                    ┌───────────────▼────────────────────────────────┐
                    │              Cyclone Mobile 5.0                 │
                    │  Atlas (source of truth)  Vault (Keystore)     │
                    │  Mapper  Ask loop  Secrets card  Overlay       │
                    │  PhoneToolExecutor = only hands                │
                    └────────────────────────────────────────────────┘
```

**App Maps** = one button on the phone *or* on Glass.  
**Secrets card** = the only way secrets enter. Same card on both. Storage only on the phone.  
**Ask** = the user’s sentence + atlas whispers + live eyes. Not a macro replay.  
**Glass Maps** = the operator table: a **full Minitap-class canvas** of the atlas so you can look at every mapped place and *feel* the house.  
**Glass Runs** = open any run and see step by step what Cyclone saw, decided and did — and the **cause of death** when it failed.  
**Why maps**: with a mapped route Cyclone knows which room it is in and which door leads to the end result, so it decides almost instantly instead of thinking in the dark.

## The Louella run (why this generation exists)

> open Gmail, check my current logged in email, then go to facebook and find the dm of Louella

4.8 rewrote this into login-status checks and died on a Facebook wall with **Couldn’t finish**.

V5:

1. Goal stays the sentence. Verb: *find the DM*, not *check login*.
2. Retrieve maps: Gmail, Facebook-in-Chrome (because the atlas said native Facebook is absent).
3. Sketch capabilities, not a 12-tap macro.
4. Read the **live** Gmail header slot (`signed-in-email`, which row is current). Dummy mapping email is never this result.
5. Open Chrome because the place catalog said so.
6. If Facebook is a login wall: **secrets card**, not failure.
7. Walk DMs from the map. Match Louella from People memory or search.
8. Prove the thread on screen.

Glass shows the same HUD and the canvas ghost. You can take the mouse at any moment.

## What 4.8 already is (steal this)

| Piece | Where | V5 job |
|---|---|---|
| Hands | `PhoneToolExecutor`, Human Gesture, overlay yield | Mapping taps and Ask taps |
| Physics | GATE, Fast Path settle, Session Kernel, named VD | Never-pay, no double-click, no display-0 rewrite |
| Seed of the atlas | Follow Me, `AppGraphEngine`, Graph v2, `AppGraphRetriever` | Promote from learned traces to **the** map |
| Secret hygiene | `SkillSecrets` strip-on-persist | Not a vault — replace with a real one |
| Ask HUD | Overlay / Ask task panel | Same projection on phone **and** Glass |
| PC | One: JPEG live, handoff, session tiles, MCP, camera | **Glass is a new local web app** (`apps/glass`). One stays One. Glass reuses One's live/handoff path through the gateway |
| Pipe | device-gateway loopback, pairing, `session_id` | New ops: atlas, mapping, secret-lease. No secret **values** on the wire |

Gateway at 4.1.0 while mobile is 4.8.0 is already drift. V5 **bumps the pipe in lockstep** or Glass will lie.

## Three planes (do not collapse)

Same as V4. Glass must label them. Mapping and Ask declare which plane they use.

1. Human foreground — `session_id=default-foreground`, `displayId=0`
2. Session Kernel VD — named `sessionId` + `displayId>0`
3. Layer 2 workspaces — time-sliced mutate lock, not parallel input

## Product split

| Who | Job |
|---|---|
| Phone | Atlas source of truth, vault, mapper process, Ask loop, GATE, overlay |
| Glass | Developer dashboard in the browser: apps + versions, Maps and Scenarios boards, run inspector, knowledge, live phone + control. No intelligence |
| Gateway | Pipe. Replica for 60fps pan. Never a second PhoneToolExecutor |

The **phone canvas** is a list + small graph + Start. The **Glass canvas** is the Minitap-class board. If the operator cannot pan the whole Gmail house on a monitor, Glass V1 is not done.
