# 05 — Secrets card and vault

The gate. Without this, mapping and Facebook login both die as “Couldn’t finish.”

One UI for every secret, forever: passwords, OTP, backup codes, name/birthday on signup, 2FA, “which account.”

```text
Facebook needs a password
Cyclone will not see it in chat or logs.
[ Enter on phone ]   [ Fill this field for me ]   [ Skip this app ]
```

## Rules

- You type into the card or into the real field.
- Cyclone stores in the **vault** (hardware-backed, per-place, per-persona, never in the map, never in traces, never in Ask text).
- Agent gets a **lease**: “use Facebook password once.” After the field reads back filled, the lease dies.
- Dummy mapping: you give a mapping email / password / number once. That’s a **persona**, tagged `mapping`, not your life account.
- Live Ask: if facebook.com is a login wall, **same card**, your real vault entry — not the dummy.

## Mobile

| Piece | Behavior |
|---|---|
| `SecretsVault` | Android Keystore / StrongBox. Slots, not a password manager UI dump |
| `SecretsCardOverlay` | Sibling of Ask. Pause the run. Overlay hittable for the card; host yield for other agent taps |
| GATE `NEED_SECRET` | Run state `needs-secret`, not failed |
| Settings → Vault | Which slots exist. Delete / replace. No values |
| `SkillSecrets` | Keep as log redaction sieve |

Lease path: card → one `focus_and_input_text` → read-back → revoke. If read-back fails, ask again. Never retry a cached plaintext from RAM beyond the lease.

## Glass

See G3 in [03](03-glass-v1.md).

1. Phone card + Glass “waiting” banner (always ship).
2. Glass card → one-shot encrypted fill over pairing → phone vault. Glass RAM wiped. Not `localStorage`. Not MCP args. Not installer logs.
3. OS password-manager handshake — not V1.

If (2) cannot be proven leak-free, ship only (1) and say so in the release notes.

## Protocol

```text
secrets.slots        → { placeId, persona, slot: boolean, … }   // no values
secrets.request      → { placeId, persona, slot, reason }      // shows card
secrets.lease.ack    → filled | skipped | cancelled
```

**Forbidden:** password, otp, cookie, token in any JSON that lands in Brain, MCP traces, fleet websocket logs, Uvicorn access logs, or Glass disk.

## Mapping + Ask

- Signup dummy: card for email, password, phone, birthday as the host asks. Continue. Don’t fail the map.
- Live Facebook wall: card for `live` persona. Dummy password is the wrong key — critic must refuse the mix.
- Skip this app: place stays `Blocked`. Board shows it. Ask that needs it stops honestly.
