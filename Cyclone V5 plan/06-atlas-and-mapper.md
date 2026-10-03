# 06 — Atlas and mapper

## What a map actually is

Not every email. Not every Reel. **Places and doors.**

```text
Place
  Gmail                          package: com.google.android.gm
  Facebook in Chrome             chrome + https://facebook.com

Screen (node)
  fingerprint   activity / host / a few landmark labels
  purpose       inbox | account-switcher | login | signup | dm-list | thread | settings
  facts it can yield
                signed-in-email (the one marked current, not the list)
                conversation-list
  doors         Account photo, Compose, Search, Menu
  danger        payment | send-public | delete-account | logout-all

Edge
  from A → B
  English: “open account photo”
  last-seen target as a hint (id / text / bounds), not a law
  confidence, last verified

Capability
  FIND_SIGNED_IN_IDENTITY
  OPEN_COMPOSE
  SEARCH_PERSON
  OPEN_DM
  CREATE_ACCOUNT
  SESSION_STATUS     (logged in / logged out — never the whole job)
```

Dummy run fills **structure**.  
Your real session later fills **your world**. Louella lives in **People memory**, not in the dummy atlas.

If you mix those, the map will think the logged-in Gmail is the dummy. Design that out on day one.

Reuse Graph v2 types in `brain/graphv2/GraphV2Contracts.kt` (`APP`, `ACTIVITY`, `PAGE`, `ELEMENT`, `TRANSITION`, `CAPABILITY` + edges `NAVIGATES_TO`, `OPENS`, `SUBMITS`, `REQUIRES`, `SCROLL_REVEALS`). Add purpose, fact slots, danger, persona, layout. Do not fork a second graph.

## Place catalog

Settings → App Maps (phone) and Glass → Maps left rail.

- Mirror launcher apps.
- Add Chrome origins visited or operator-added.
- Install state: native missing → offer “Map website in Chrome.”
- Status, last mapped, coverage, stale.

## One-button mapping

User picks Gmail → Start (phone or Glass).

1. **Enter** — current session, or sign in, or create dummy. Secrets card if needed. Never invent identity.
2. **Classify the room** — see → think → one action → look. First screen gets a purpose.
3. **Walk doors, not content** — menus, tabs, FABs, settings, search. Do **not** open 400 emails. Sample one if you must learn “thread,” then back. Budget: N new screens or T minutes, then stop and say what’s still dark.
4. **Never** — pay, buy, subscribe, send to a real person, post public, delete account, wipe device. Payment UI = wall, mark `danger: payment`, leave.
5. **Secrets mid-walk** — phone number, birthday, 2FA — card again. Continue.
6. **Name the facts** — on the account header: *this label is the signed-in address; these others are extra accounts.* Store the **slot**, not the demo string as truth for later Asks.
7. **Chrome places** — Facebook not installed → map `facebook.com` in Chrome. Record that. Ask-time will not open Play Store by accident.
8. **Stop with a picture** — Glass canvas of screens. Coverage: Account 100%, Inbox 80%, DMs unmapped. **Keep mapping** is another button, not 100 chats.

Follow Me stays as the **teach** path: you walk it once, same graph. Mapping is Follow Me with the agent holding the phone.

## People memory

- Live persona only.
- Display name, aliases, last thread landmark, last place.
- Filled when a live mapping pass (or a live Ask) sees a conversation list — **not** from dummy.
- Ask: *Louella* → memory hit or `SEARCH_PERSON` on the DM screen.

## Freshness

- App version / Chrome origin fingerprint changed → **stale**, offer Refresh (re-walk changed regions).
- Live Ask: edge failed → that edge loses confidence; node stale; idle remap of **that room only**.
- Periodic light pass: open app, confirm 5 landmarks. Don’t full crawl every night.
- Operator pin: “this is DMs.” Agent will not rename it.

The visual map is the same object the agent reads. No second spreadsheet.
