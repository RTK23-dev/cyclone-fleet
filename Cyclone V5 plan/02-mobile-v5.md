# 02 — Cyclone Mobile 5.0

Seven workstreams. Order is the dependency graph. Skip one and the next becomes a harness.

Insertion root: `apps/mobile/app/src/main/java/com/cyclone/mobile/`

## M0 — Contract (week 0)

Freeze names so Glass and MCP do not invent a second vocabulary.

| Name | Meaning |
|---|---|
| Place | `package` **or** `chrome\|origin` (Facebook not installed → website place) |
| Persona | `live` \| `mapping`. Never mix slot values |
| Screen | Graph v2 page + purpose, danger, confidence, lastVerified |
| Edge | English door + hint target + confidence |
| Capability | Queryable index (`FIND_SIGNED_IN_IDENTITY`, `OPEN_DM`, `SEARCH_PERSON`) |
| FactSlot | How to read a fact on a screen, not the mapping-day string |
| Run state | Add **`needs-secret`** (not failed) |

**Ship:** `protocol/cyclone-atlas-v1.schema.json`, `protocol/cyclone-secrets-v1.schema.json`. Version matrix row for 5.0.

Critic rules in the contract: don’t rewrite the user’s verb; don’t treat dummy email as live identity; don’t pay.

## M1 — Secrets card + vault

Without this, mapping and Facebook login both die as “Couldn’t finish.”

| Build | Notes |
|---|---|
| `SecretsVault` | Android Keystore / StrongBox. Per place, per persona. Slots: password, otp, totp, phone, name, birthday. **Values never in Graph, Ask, traces, MCP, Glass disk.** |
| `SecretsCardOverlay` | Same visual language as Ask. Pause the run. Type here **or** in the host field. |
| Lease | One fill → `focus_and_input_text` → read-back → lease dies. |
| GATE | `NEED_SECRET`. Overlay stays hittable for the card; host yield still applies to agent taps. |
| Settings | “Vault” shows **which slots exist**, not the secrets. Delete / replace. |

Reuse `automation/skill/SkillSecrets.kt` as the redaction sieve for logs. It is not storage.

**Ask already works better** even before maps: login wall → card, not failure.

## M2 — Atlas store (promote Graph v2)

Stop treating Follow Me output as a side cache.

| Build | Notes |
|---|---|
| `AtlasStore` | Phone-local, per place. Graph v2 nodes + purpose, fact slots, danger, lastVerified, appVersion, canvas layout |
| `PlaceCatalog` | Launcher apps + Chrome origins. Install-state. `facebook.com` is a row even without `com.facebook.katana` |
| Fact slots | `signed-in-email` is **how to read** (account header, which row is current) |
| `PeopleMemory` | Louella-class: display name, aliases, last thread landmark. **Live persona only.** Dummy crawl must not write people |
| Retriever | `capability + slot`, not only bag-of-words. `findBestPath` → sketch, never an executable macro |

Reuse / extend:

- `applearner/AppGraphEngine.kt`
- `applearner/graphv2/`
- `brain/graphv2/GraphV2Contracts.kt`
- `brain/graphv2/GraphV2Queries.kt`
- Follow Me learner (`FollowMeGestureIntelligenceV292.kt`, `FollowMeLearnerRuntime.kt`)

Follow Me becomes **Teach**: your finger writes the same atlas.

**Phone UI:** Settings → App Maps: catalog + **small** graph + Start. Full Minitap-class board is Glass (see [04](04-app-maps-canvas.md)).

## M3 — Mapper (the one button)

Same Cortex loop, different goal: *explore doors, don’t live the app.*

| Build | Notes |
|---|---|
| `MappingSession` | Start/pause/stop from Settings or Glass. Overlay peek: *Mapping Gmail · Account menu* |
| `ExplorationPolicy` | Budget (new screens / minutes). Skip feeds (one sample). **Hard never:** pay, purchase, send to a real person, public post, delete-account |
| Enter | Current session **or** sign-in **or** create dummy — all via secrets card |
| Write path | Every successful look commits a node/edge. Failed edge → confidence down, don’t delete the room |
| Chrome | `open_link` + origin wait. Record “native absent” |
| Layout | Region clustering written into atlas so Glass and phone agree |

Dummy mapping fills **structure**. Live mapping (optional second pass) fills **your** people/labels. Two graphs, one canvas with a persona toggle.

## M4 — Ask compiler (atlas in, rails out)

See [07](07-ask-compiler.md). Replace 4.8 destination-regex titles (“Checking login status”) with capability copy from the atlas. Keep destination-scoped **until** as proof, not as a login template.

Skill Compiler stays: a high-confidence edge may replay. Miss → look. Unchanged → not a second click.

## M5 — Freshness

- Package/version or WebView origin hash change → place **stale**, offer Refresh (remap rooms, not the universe).
- Live Ask fails an edge → that edge stale; idle remap of **one room**.
- Nightly optional landmark check (5 doors), never a full crawl.
- User pin: “this is DMs.”

## M6 — Overlay / Ask HUD

- Expansion contract from 4.7.9 / 4.8 stays (no Open rail, no reset on phase change).
- New states: Mapping, Needs secret, Sketch (Gmail → Facebook in Chrome → DMs).
- Peek chip during mapping.
- Secrets card is a sibling of Ask, not a webview stuffed in chat.

## M7 — Engine insight for Glass (owner charter, 2026-09-23)

Glass shows what the phone knows and did. The phone must expose it:

- **Run record v2** on top of `AgentTraceStore`: room and expected room per step, `decisionSource: map | model`, scenario id, app version, redacted before/after frames (optional, bounded, TTL), mapping runs recorded as runs. Spec: [11](11-run-inspector.md).
- **Cause-of-death classifier** on the phone, shared by the phone's diagnostic export and Glass.
- **Scenarios**: routes to end results derived from successful runs, mapping and developer pins; health from runs. See [04](04-app-maps-canvas.md#scenarios-lens).
- **Map-driven steps** (law 1): on a known route take the next door without a model call when the room matches; verify the room after every door.
- **Per-version maps**: needs-remap when the installed app version has no verified map.
- Gateway handlers: `apps.list`, `atlas.versions`, `scenarios.*`, `runs.*` ([08](08-protocol-gateway.md)).

## Settings insertion

`ui/v32/CycloneSettings426.kt` gains a first-class **App Maps** row (and Glass has the real board). Do not hide mapping behind Brain-only copy.
