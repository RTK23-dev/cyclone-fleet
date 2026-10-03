# Cyclone V5 Alpha 54: Command Center C1, the vault

Developer alpha for owner testing. It builds on Alpha 53 (Drive in the car) and includes it.
- **Mobile:** `5.0.0-alpha.54.dev1` (version code 198). The phone has no changes of its own in this release.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.54.dev1.exe`. The runtime stores the vault's ciphertext.
- **Glass:** `1.0.0-alpha.29`.

This is the second Command Center release (plan 33, **C1: the vault**). A password vault in Glass, for the accounts
you own. It is zero-knowledge: everything is encrypted in your browser, and this PC stores only ciphertext.

Phones don't receive passwords from the vault yet. That is C2 (sealed delivery). Until then, a phone still asks you
for a password on its own screen.

## What changed

**Command Center → Vault**, a new tab.

- **Create:**
  - Pick a passphrase (at least 12 characters; four or five unrelated words work well).
  - Glass shows a **recovery key** once, with **Copy** and **Download recovery kit**.
  - You tick "I saved my recovery key" to continue.
- **Items:**
  - Four kinds: logins (name, username, password, website, optional authenticator seed, notes), authenticators,
    recovery codes and secure notes.
  - Link an item to an account from the Accounts tab. The Accounts tab now shows how many encrypted items each account
    has.
  - A **generator** makes 20-character passwords (unbiased random, all character groups).
- **Show, Copy and Code:**
  - Secrets are masked. **Show** reveals one for 20 seconds.
  - **Copy** puts it on the clipboard and clears the clipboard 30 seconds later.
  - **Code** copies the current 6-digit authenticator code.
  - Any of these asks for your passphrase again if you last proved it more than 2 minutes ago.
- **Health:** counts of weak, reused and older-than-a-year passwords, with a label on each item.
- **Import:**
  - From Bitwarden (JSON, not encrypted) or a CSV export (Chrome, Edge, 1Password, Firefox and others).
  - The file is read in the tab and each item is encrypted before it is saved.
  - Payment cards and identities are never imported.
- **Backup and keys:**
  - **Download encrypted backup**: the ciphertext file, which opens only with your passphrase or recovery key.
  - **Restore**: a backup goes into an empty vault on any PC.
  - **Change passphrase**: prove it's you with the current passphrase or the recovery key. Items are not
    re-encrypted.
  - **New recovery key**: the old one stops working.
- **Locking:**
  - The vault locks with **Lock**, after 5 minutes without use, or when you leave the tab.
  - Locking drops the key and every opened item from memory.
  - Forgot both the passphrase and the recovery key? **Delete the vault** (type DELETE VAULT). Nothing can be
    recovered then.

## How the encryption works

- **Vault key:**
  - 32 random bytes, made in the browser.
  - It is wrapped with a key derived from your passphrase (PBKDF2-SHA256, 600,000 iterations, 16-byte salt), and a
    second time with your recovery key (32 random bytes).
  - Wrapping uses AES-256-GCM.
- **Each item:**
  - Every version gets its own random key, wrapped with the vault key.
  - The fields are encrypted with AES-256-GCM.
  - The item's id, kind and version are bound in, so ciphertext moved to another item or version does not open.
- **Account links:**
  - The account an item was saved for is inside the encryption too.
  - If the plain link on the PC is changed outside Glass, the item shows **Account changed outside Glass**.
- **Keys:**
  - Keys in the browser are non-extractable WebCrypto keys, held in memory only.
  - Glass keeps nothing in local or session storage.
- **The PC** stores ciphertext, the salt and iteration count, each item's kind, its account link and the audit
  chain. The gateway checks shapes and sizes, and keeps versions strictly increasing so a stale window can't
  overwrite a newer item. It cannot decrypt anything.
- **Why not Argon2id and XChaCha20**, as plan 33 first said: Glass has no runtime dependencies (the Glass guard), and
  WebCrypto provides PBKDF2 and AES-GCM natively. The iteration count follows OWASP's guidance for PBKDF2-SHA256.

## Safety

- **The PC never sees a secret:** no passphrase, recovery key, password, username or item name.
  - Tested in the Glass suite: every request Glass sends is checked for them.
  - Tested end to end against the real runtime in Chromium: after creating a vault, saving items, backing up and
    restoring, a canary password, the passphrase, the recovery key and the username were searched for in the
    database files, the logs, the backup file, and the live memory of both runtime processes. There were **no hits**.
- **Audit:** create, save, delete, restore, reset, re-key, unlock, failed unlock, show, copy, export, import and lock
  are appended to the Command Center's hash chain. Only names and ids are recorded, never values.
- **The AI can't reach the vault:** the agent MCP servers never call the Command Center routes, which a CI guard
  checks. Plan 33 D4 still holds: models will only ever see `vault:` references.
- **A CI guard checks:**
  - the vault table has no plaintext columns, and the vault store never logs;
  - Glass uses AES-GCM with bound data, 600,000 iterations and non-extractable keys;
  - nothing is kept in browser storage;
  - the gateway calls never carry a passphrase or recovery key;
  - idle lock and step-up exist.

## Validation and limits

Tests that pass:
- **Gateway:**
  - `test_command_vault.py` (9 tests): a vault is made once with a strong KDF; items are ciphertext with increasing
    versions; account links, and blocking account removal while items are linked; rewrap leaves items alone;
    restore goes into an empty vault only, and a broken backup leaves nothing; reset needs the words and is audited;
    client audit takes names only; routes need the bearer.
  - The full gateway suite passes.
- **Glass:**
  - `vault.test.mjs`: passphrase, recovery key and wrong keys; items round-trip, and a box moved to another id,
    version or kind fails to open; a moved account link is flagged; re-keying keeps items readable; generator and
    health; the RFC 6238 TOTP test vectors; Bitwarden JSON and CSV import (quotes, commas and newlines; cards
    skipped).
  - `vaultView.test.mjs`: create, recovery key, save, show, lock, wrong passphrase, unlock, and no secret in any
    request.
  - All 164 Glass tests pass.
- **Rendered and end to end in Chromium** against two real runtimes:
  - create, save, weak-password health, download backup, lock;
  - restore on the second runtime, unlock with the recovery key, show the password.

Limits:
- **Physical: UNVERIFIED** on your own PC.
- **No passkey unlock yet.** Glass runs at `127.0.0.1`, and WebAuthn passkeys need a domain name. They come with the
  hosted Command Center (C6) or a local name.
- **One owner.** There are no members or roles until C6.
- **Phones don't get vault passwords yet.** Next: **C2, sealed delivery**. A phone will get a device key at pairing,
  and a task's password will be sealed to that one phone, for one task, used once.
- **The clipboard is cleared by writing an empty value after 30 seconds.** A clipboard manager may still keep a copy.
