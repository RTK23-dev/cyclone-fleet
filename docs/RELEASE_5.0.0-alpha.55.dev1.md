# Cyclone V5 Alpha 55: Command Center C2, sealed delivery

Developer alpha for owner testing. It builds on Alpha 54 (the vault) and includes it.
- **Mobile:** `5.0.0-alpha.55.dev1` (version code 199).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.55.dev1.exe`.
- **Glass:** `1.0.0-alpha.30`.

This is the third Command Center release (plan 33, **C2: sealed delivery**). A task can now sign in with a password
from your vault, on a phone that has never had it. Only you and that one phone ever see the password. The PC passes
on sealed bytes it cannot open.

## What changed

**1. Trust a phone once (Command Center → Vault → Phones).**
- **Get its key:** the phone makes a key pair in Android Keystore (StrongBox when it has one). The private half never
  leaves the chip.
- **Compare:** Glass shows the key's fingerprint, worked out in your browser from the key itself. On the phone, open
  **Settings → Vault → Command Center key** and compare the letters.
- **Trust:** press **They match, trust this phone**. If the phone's key ever changes, it is untrusted again and any
  password waiting for it is revoked.

**2. Give a task a vault login (Command Center → Tasks).**
- Pick the phone, the account and **Sign in with: Vault login**. Only the account's own logins and authenticators are
  offered, never notes.
- The task waits with "Waiting for the vault" until the password has been sent.

**3. Glass seals it (Vault tab, unlocked).**
- "Passwords for tasks" lists the tasks that are waiting.
- With **Send automatically** (on by default), Glass seals each one as soon as the vault is unlocked and the tab is
  open. **Seal and send** does one by hand.
- What is sealed: the login's password and, if the item has one, its authenticator seed.
- Each sealed copy is bound to five things: this task, this phone's key, this app or website, the slot (password or
  code) and an expiry. The expiry is 30 minutes after the task is due, and never more than 24 hours.

**4. The phone uses it once.**
- The task starts on the phone with the sealed copy. The phone checks every bound field and opens it inside
  Keystore, before the task starts.
- The value is held in memory for that one task only.
- When Cyclone reaches the password field **on that app or site**, the value fills once, straight from a one-use
  lease into the field. It never passes through the Secrets Card. The code for an authenticator is worked out on the
  phone at fill time.
- The phone reports only the outcome (used, failed, unused or expired) and forgets the value. Anything unused is
  wiped when the task ends.

**Watch it:** Tasks show "Signs in with the vault · password used". The Vault tab lists recent sealed copies with
their state, and a copy that hasn't been sent yet can be **revoked**.

## Safety

- **The PC can't read passwords.** The gateway stores and relays HPKE envelopes (RFC 9180: DHKEM P-256, HKDF-SHA256,
  AES-256-GCM). It holds no private key and has no decryption code; a CI guard checks that.
- **Bound to one place.** Beyond plan 33, each sealed copy names the app or website it is for. On any other app or
  site the phone keeps it and falls back to the Secrets Card, so a page that tricks the Mind still can't get the
  password typed into the wrong site. Websites match their host or its subdomains, over https only.
- **One use:**
  - Every copy has a lease id. The phone remembers ids it has accepted (ids only) and refuses one it has seen, so a
    replayed envelope doesn't work.
  - The gateway also refuses reused ids, and ids made more than 10 minutes ago.
  - An expired copy is refused on both sides.
- **The model never sees it.** The Mind is told a password was sent and uses `vault_fill` on the field. The value
  goes from the lease to the field. The Mind learns only whether the fill worked.
- **Glass checks the key itself.** It recomputes the phone key's fingerprint from the key bytes before sealing,
  refuses a key that doesn't match, and wipes the plaintext bytes after sealing.
- **Audit:** getting a key, trusting, untrusting, sealing, sending, used, failed, unused, expired, rejected and
  revoked are all appended to the Command Center's hash chain. Only names and ids are recorded.

## Validation and limits

Tests that pass:
- **Phone** (JVM):
  - `SealedDeliveryTest`: HPKE opens RFC 9180 appendix A.3.1, and opens the envelope Glass sealed (a shared fixture);
    tampered bound data fails.
  - A copy opens once, for its task only, and fills only on its app. Replay, the wrong task, another phone's key,
    expiry and the wrong device key are all refused.
  - Unused values are wiped when the mission ends; site matching; the RFC 6238 code.
  - `cc.start` opens envelopes before the mission and refuses bad ones.
  - The full phone suite passes.
- **Gateway** (`test_command_delivery.py`):
  - trust needs the right fingerprint;
  - a vault task needs its account, a trusted phone and its own item;
  - without a copy the task waits and is listed for Glass;
  - bound data must match the task, phone, app and slot, and stale or reused ids are refused;
  - the envelope rides with the task once, and the phone reports it used;
  - a changed phone key revokes waiting copies;
  - a refused envelope fails the task, and expired copies aren't sent;
  - cancel and revoke.
  - The full gateway suite passes.
- **Glass:**
  - HPKE matches RFC 9180 A.3.1, and a byte-for-byte fixture is shared with the phone.
  - Sealing: the envelope opens with the phone's key and tampered bound data does not; a mismatched key or moved item
    is refused; expiry limits.
  - The task form offers the vault login.
  - All Glass tests pass.
- **End to end:** the real runtime and Glass in Chromium, with a scripted phone holding a real P-256 key that opens
  envelopes with a third, independent HPKE (Python `cryptography`):
  - create a vault and login, get the phone's key, compare, trust;
  - create a task, and Glass seals automatically;
  - the gateway sends the copies with the task, and the phone opens both (password and seed) and checks every bound
    field;
  - the task succeeded and both copies were reported used;
  - the canary password, passphrase and seed were in **none** of the runtime's files or logs.

Limits:
- **Physical: UNVERIFIED.** The C2 exit test (a task signs in on a phone that never had the password) has not run on a
  real phone. The Android Keystore key agreement, including StrongBox and the fallback to the TEE, runs only on a
  device. The fill itself is the existing vault fill, which was tested earlier on the phone.
- **Glass must be open and unlocked** for a waiting task to get its password. Sealing copies in advance for routines
  that run while you are away (plan 33's pre-authorised leases) is the next step.
- **"Remember on this phone"** is not in this release. Delivered values are always one use.
- **The app or site comes from the account's service:** an app package (`com.example.app`) or a website
  (`example.com`). Check the account's service before trusting a task with it.
- **Two-step codes:** authenticator seeds are covered. SMS and email codes still ask you on the phone.
