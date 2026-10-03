# V5 alpha.2 physical acceptance — prepared, not passed

**Status:** UNVERIFIED / BLOCKED. No physical acceptance claim is made by this return.

## Read-only discovery on 2026-09-23

- Target named device: **Pixel 8**, package `com.cyclone.mobile`. The test must record the exact ADB serial suffix, reported model, Android version, installed version and candidate version.
- `adb devices -l` showed **zero** devices. `adb mdns services` found none. No install, launch, phone input, gateway forwarding, or phone content read occurred.
- No V5 candidate APK was present in this worktree. The older APKs in Downloads are not evidence for this release.
- [Mobile CI build](../../../../.github/workflows/_mobile-build.yml) publishes `Cyclone-Android-<version>` with an **unsigned** `Cyclone-<version>.apk`, SHA-256 sidecar, `source-sha.txt`, `run-id.txt`, `signing-state.txt`, and `mobile-metadata.json`. Its `UNSIGNED_VERIFIED_CANDIDATE` cannot be installed as an update. Obtain an update-compatible signed APK with a record linking its SHA-256 to the exact CI source/artifact before installing. Never uninstall the current app to bypass a signer mismatch; that would discard phone-local Atlas/Vault data.
- Glass's real Maps path is `app.ts` in `service.mode === "real"` with a focused ready phone, V5 mobile version and gateway bearer. It mounts `createGlassRuntime` with `useDemoGraph: false`; the loader calls phone `atlas.places` and `atlas.get` for `live` and `mapping` through the gateway. `service.mode === "mock"` is demo and cannot satisfy this gate.

## Repeatable, bounded run

1. Freeze one **candidate source SHA** after implementation and green Mobile + PC/Glass CI. Download the Mobile CI artifact for that exact SHA. Check its source SHA, run ID, version, checksum, and unsigned signing state. Obtain a separately signed update-compatible APK and signing record; record its SHA-256 and signer certificate fingerprint. The signing record must tie that signed APK to the exact CI artifact. Do not substitute a local debug APK or an older download.
2. Connect the Pixel 8 and run [the read-only preflight](../../../../scripts/phone-gateway/v5-alpha2-readonly-preflight.py) with `--serial`, `--apk`, `--apk-sha256`, `--ci-provenance`, `--source-sha`, `--version`, and `--version-code`. Keep its JSON report in the release evidence folder. Resolve every blocker; `PREFLIGHT_READY` only authorizes the next test step, it is not a physical pass. Review the installed signer against the candidate before an update.
3. Coordinate exclusive phone use with the integration lead. Install the reviewed signed APK using `adb -s <exact-serial> install -r <signed-apk>`. Preserve app data. Re-run preflight and confirm installed package versionCode/versionName match the candidate. Record install result, candidate hash, signer, source SHA, and named device.
4. Open Cyclone and check normal boot. In root Settings, reach **App Maps** and **Vault**. Confirm Vault shows slot presence/absence only; do not enter or export a secret. Confirm no unsupported autonomous mapping control is presented as working.
5. Start **Follow Me** in `live` persona and teach one ordinary, non-dangerous app. Prefer Calculator if that package is present; make two harmless navigation/actions that show distinct structural rooms/doors. Do not use accounts, messages, payments, permissions, private text, or credential fields. Stop teaching and inspect the phone App Maps entry. Record package Place ID, persona, map status, counts and revision, without raw page content.
6. Force-close/reopen Cyclone and verify the same phone Atlas entry persists. Inspect it again through the phone-owned `atlas.places` / `atlas.get` gateway path, recording only identity, persona, status, revision and screen/edge counts. Do not capture raw response bodies or bearer tokens in evidence.
7. Start the matching PC/Glass candidate and select this focused Pixel/session in **real** mode. Open Maps and select `live`. Confirm no demo/sample badge and that the Place ID, status, revision/room count correspond to the phone Atlas. Have the operator identify at least two learned rooms and a door between them **from Glass alone**; record their short structural description, not a raw log.
8. Inspect only the sanitised UI/export evidence for privacy: no secret value and no ordinary user content in Atlas labels, Glass, or exported HUD log. A result is **PASS** only if every preceding gate has concrete evidence from the named physical device and exact candidate. If any gate fails, record **FAIL** with a bounded reproduction; if the phone or signed candidate is unavailable, record **BLOCKED**.

## Evidence record for the actual run

| Field | Result |
| --- | --- |
| Candidate source SHA / Mobile CI run / PC CI run | PENDING |
| Signed APK SHA-256 / signer / versionCode / versionName | PENDING |
| Named Pixel 8 serial suffix / Android release | PENDING |
| Upgrade install and boot | PENDING |
| Settings App Maps and Vault | PENDING |
| Follow Me real app, live Place ID, rooms/doors, revision | PENDING |
| Atlas persistence after Cyclone reopen | PENDING |
| Glass real source and phone Atlas match | PENDING |
| Operator explanation from Glass, without logs | PENDING |
| Privacy/secret scan | PENDING |
| Overall physical status | **UNVERIFIED / BLOCKED** |

Evidence must never include a gateway bearer, phone session token, password, OTP, API key, raw `dumpsys`, full Atlas body, screenshot of private content, or full accessibility tree. The preflight captures identity fields only.
