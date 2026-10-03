# 44 — Cloud fleet: VMOS, DuoPlus and other cloud phones, connected on Cyclone's terms

Goal: a fleet of cloud phones (VMOS Cloud, DuoPlus, any remote-ADB phone) that Cyclone installs, sets up, keeps
connected and teaches, without depending on the provider's temporary links.

Principle: **the provider is only the way in.** Its API opens a remote-ADB link once (and repairs things when needed);
the lasting connection is Cyclone's own. `vmos/architecture.py` already fixes the rest: `PhoneToolExecutor` on the
phone stays the only thing that acts, no provider-native taps, no generic shell for the model.

## What the providers give (from their docs; confirm in run 0)

| | VMOS Cloud | DuoPlus |
|---|---|---|
| Auth | OpenAPI access key + secret key, HMAC-SHA256 signed (`armcloud-paas`) | `DuoPlus-API-Key` header |
| ADB | `padApi/adb` → SSH command + key + `expireTime`; `expireMinutes` up to 7 days (console: 24 h) | ADB address, open only to a whitelist of ≤ 10 IPs |
| Commands | `padApi/asyncCmd` | `/api/v1/cloudPhone/command` (≤ 20 phones, ≤ 10 s) |
| Install / root | `installApp`, `uploadFileV3`, `switchRoot` (per package or global) | batch root |
| View by URL | H5/RTC via `stsToken`; share link 2 days | share link + password |

Cyclone needs Android 13+ (minSdk 33): VMOS Android 13/15 images; not DuoPlus Android 10/11.

## The runs

0. **Probe (the PC session, not a release):** call each provider API once with a test phone, record the answers
   with keys removed as fixtures; confirm the VMOS signature, the list path, the ADB answer and DuoPlus's list fields.
1. **alpha.90 — Cloud phones in, kept alive** (built, below).
2. **alpha.91 — Set up in one go + enrollment:** a fixed provisioning catalog (accessibility, `pm grant`, appops,
   battery whitelist, stay awake) through adb or the provider command API with read-back; per-package root only;
   `FleetEnrollReceiver` (DUMP-guarded) takes a one-time grant bound to this PC's key, so trust needs no tap on the
   cloud phone; fleet mode skips onboarding cards.
3. **alpha.92 — Our own link, relay + PC:** `apps/fleet-relay` (websockets, TLS via Caddy, per-device tokens, no
   logging); `cyclone_bridge` relay transport with the same line protocol; `usb_session_id` → `link_id`.
4. **alpha.93 — Our own link, phone:** `RelayLink.kt` (OkHttp WebSocket, heartbeat 20 s, dead at 60 s, backoff
   1–60 s with jitter, network callback, WorkManager watchdog) feeding `GatewaySocketServer`'s `onLine`; end-to-end
   Keystore ECDH + HKDF + AES-GCM with a counter; repair ladder (wake 60 s → restart 3 min → provider restart 10 min).
5. **alpha.94 — Fleet Carry:** `carry.pack` / `carry.absorb` sealed to each phone's enrollment key; versioned packs
   with app/Android versions; golden phone; skills proven on 3 phones are pushed to all; people memory and the phone
   model opt-in; keys, tokens, pairing, vault, chat never.
6. **alpha.95 — Run the fleet:** staged updates (canary → 10% → all, stop on health drop), fleet Lab, live view in a
   tab (never Cyclone's hands), MCP asks which phone, approvals name the phone.

## As built: alpha.90 (run 1)

Gateway `cloud_fleet/`:
- `providers/vmos.py` — signed OpenAPI calls; phone list; `open_adb` asks for 7 days and reads the SSH line
  (user, host, port, `-L` target), the key and the expiry. The expiry used is the earlier of VMOS's (read as Beijing
  time when it has no zone) and the 7 days asked for, so a misread time only renews early.
- `providers/duoplus.py` — phone list; the ADB address DuoPlus lists or the owner pasted (plus this PC's IP in the
  DuoPlus whitelist). `providers/remote_adb.py` — any `host:port`.
- Paths are defaults; an account can carry `endpoints` / `baseUrl` overrides, so a provider change found in run 0 is a
  settings fix, not a release.
- `vault.py` — accounts and keys, DPAPI for the Windows user (memory only elsewhere).
- `tunnel.py` — Windows' `ssh.exe`, foreground, `127.0.0.1:<stable port>`, `ExitOnForwardFailure`, keep-alives; the
  key reaches ssh only through SSH_ASKPASS (the runtime prints it in askpass mode), never argv or a plain file.
- `lease.py` — renew with a fifth of the lease left (≥ 15 min early), backoff 5/15/60/300 s, 30 min for "needs you",
  a stable local port per phone (the fleet identity survives renewals).
- `service.py` — the keeper: open → tunnel → `adb connect` → connected; renews in place; a dead tunnel restarts; a
  refused key or three dead tunnels get a new key; three failed connects get a fresh link; a stopped phone says so;
  a phone without Cyclone gets it through phone care (verified build); `metadata_for_serial` marks them `CLOUD`.
- `api.py` — `/v1/cloud` (status), accounts (add, remove, refresh), a phone (keep, address), remote-ADB addresses.
  No field takes a command, argv or URL to call; keys never come back.
- Fleet: source `CLOUD`; trust's automatic "Connect this PC?" also for cloud phones (the owner taps Allow in the
  provider's viewer until run 2); the doctor says "reopening its link", not "cable"; Live Phone may target them.

Glass: Devices → **Cloud phones**: add an account (VMOS keys, DuoPlus key, or an ADB address), keep phones connected,
one status line each, a DuoPlus address field, Remove with confirm. A connected phone joins the list above.

Checks: `test_cloud_fleet.py` (29), `test_cloud_fleet_guard.py` (5), Glass `cloud.test.mjs` (5).

Not in run 1: provisioning, enrollment, the relay, Fleet Carry, provider power control, more than 32 phones (the
fleet's limit). Physical acceptance with real VMOS/DuoPlus accounts: UNVERIFIED (this build had no network access to
either provider).
