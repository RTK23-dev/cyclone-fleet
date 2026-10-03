# Handoff — device test of Cyclone V5 alpha.23 (reliability release)

**For:** the owner or a coding agent on the owner's PC with the phone connected. **Goal:** the device gate in
[`13-alpha23-reliability-plan.md`](../13-alpha23-reliability-plan.md). Nothing below is verified on a phone yet.

## Install

From GitHub prerelease **`v5.0.0-alpha.23.dev1`**: `Cyclone-5.0.0-alpha.23.dev1.apk` (versionCode 164) and
`Cyclone-PC-Companion-1.6.0-alpha.23-Setup.exe`. Install the APK as an update (`adb install -r`), never uninstall. If the
prerelease is missing, stop and report — do not sideload a self-built APK.

Optional before testing: Cyclone → AI settings → **When the main model is busy** → pick a backup model.

## Run each sentence once (Ask on the phone or Glass Ask), export the run diagnostic

| # | Sentence | Pass when |
|---|---|---|
| 1 | `check my current logged in gmail and make a Facebook account with that Gmail on chrome Facebook.com` | Clause 1 in Gmail proves the email; clause 2 on facebook.com; stops for approval before creating the account. No Chrome↔Gmail ping-pong. |
| 2 | `open clock and set an alarm for 5 minutes` | Clock shows a new enabled alarm at now+5 min; diagnostic "Completion basis: Enabled alarm at HH:MM observed". Opening Clock alone must not complete. |
| 3 | `set a timer for 5 minutes` | A running countdown near 5:00. |
| 4–8 | the five sentences in `12-navigation-eval.md` | the evidence listed there |
| 9 | `open YouTube` | completes quickly on landing (Fast Path kept) |
| 10 | `open Instagram` on a cold start | diagnostic shows a WAIT, not "Fresh after-state unavailable" |

For every run record from the diagnostic header: **Fresh after-state observations** vs launches, **Screen waits**,
**Deferred proofs**, **Model request latency ms**, **Completion basis**, final status. Any "COMPLETED" without a real
completion basis is a release blocker.

## Safety (unchanged)

Test accounts only; never approve pay/send/delete/account-creation prompts during this test; passwords only via the
Secrets Card.
