# Cyclone Glass 1.0.0-alpha.1

**Tag:** `glass-1.0.0-alpha.1`  
**Branch:** `v5/integration`  
**Companion:** `1.6.0-alpha.1`  
**Baseline live path:** Cyclone One 1.5.5 on Mobile **4.8.0**  
**Prerelease.** Not a Mobile APK. Does not replace `v4.8.0`.

Glass is the PC HUD. The phone still mutates. This cut does not add a PC `PhoneToolExecutor`.

## What you can do

- Open **Ask**, **Maps**, and **Vault** as primary nav next to Control.
- See a password wall as **Needs you — Facebook password** (wait state, not a crash). Type the secret on the phone overlay, never in Glass.
- Open **Maps** and read Gmail’s rooms (Account, Inbox, Message, Compose, Settings) on a pan/zoom board with doors and an inspector. Live vs Dummy are different maps.
- Vault lists slot **presence** only (`Facebook password: set`).
- Phones below Mobile 5.0 get an honest **update the phone** banner instead of a fake atlas.

## What this is not

- Ask **Send** stays off until the phone Ask transport exists. Glass will not run goals on the PC.
- Maps currently renders the **mock** Gmail/Facebook house. Live `atlas.get` from the phone waits on Mobile Follow Me (#146).
- **Start mapping** is disabled (`phone alpha.3`).
- No Windows installer asset in this tag (`windows_signing = CI_UNSIGNED`). Source + tests only.
- Mobile / Gateway / MCP versions are unchanged (`4.8.0` / `4.1.0`).

## Agent PRs

| PR | What |
|---|---|
| [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | Shell, Ask, Vault, needs-secret wait |
| [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) | Minitap-class Maps canvas |
| [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | atlas/secrets client |
| [#156](https://github.com/premiumcentraal-boop/Cyclone/pull/156) | Mount Maps in the shell |
| [#158](https://github.com/premiumcentraal-boop/Cyclone/pull/158) | Honest wait copy + atlas→Maps adapter |
| [#157](https://github.com/premiumcentraal-boop/Cyclone/pull/157) | 1.6.0-alpha.1 identity |

## Tests

```text
cd apps/pc-companion && npm test
# 156 pass, 0 fail
```
