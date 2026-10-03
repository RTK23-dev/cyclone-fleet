# Alpha 33 physical Pixel / Glass Alpha 16 test report

**Date:** 2026-09-25. **Device:** USB-connected Pixel 8 (`3B171FDJH0061G`), Cyclone Mobile `5.0.0-alpha.33.dev1` (code 175). **Companion:** Cyclone Glass `1.0.0-alpha.16`, Gateway `2.9.5`, Chrome on the same PC. **Source build:** `v5.0.0-alpha.33.dev1`. This report executes the canary and separate USB acceptance checks in [the experiment plan](ALPHA33_LAB_EXPERIMENT_PLAN.md). It does not certify an Alpha 33 release.

## Decision

**Do not interpret this as an A/B result or proceed to the 29-mission baseline yet.** The canary exposed an execution handoff defect: after a failed/stopped phone mission, the mobile controller remains `HUMAN` and rejects following `lab.start` calls. The one scored mission failed its phone-side setting check. The USB mirror worked in portrait and accepted PC clicks, but its landscape layout makes the phone nearly unusable.

## Lab results

| Experiment ID | Purpose | Trials attempted | Outcome |
| --- | --- | ---: | --- |
| `exp-20260925-171832-c4e5` | Initial smoke | 3/8 | 3 infra: `ASK_BUSY` (an existing Calculator request occupied the phone) |
| `exp-20260925-172209-3c02` | Smoke after stopping existing task | 3/8 | 3 infra: `HUMAN_HAS_CONTROL` |
| `exp-20260925-172319-d261` | Smoke after Glass Take/Give control | 3/8 | 3 infra: `HUMAN_HAS_CONTROL` |
| `exp-20260925-172507-e82a` | Single mission during bridge recovery | 1/1 | 1 infra: `DEVICE_DISCONNECTED` |
| `exp-20260925-172945-e6d8` | Recovered-bridge smoke canary, baseline variant | 4/8 | **0 pass, 1 fail, 3 infra**; halted after three consecutive control rejections |

The final canary used one `A-baseline` variant, one repetition, the phone's model settings, marks enabled, and fresh memory. Its first mission, `settings.rotate.on`, ran on `openai/gpt-6-luna` for 392.7 s and 47 turns. The Android probe read `accelerometer_rotation = 0`, so the verdict was **fail**, category `timeout`, cause `wandered (many turns)`; run trace `ai-e589bc9b-1427-43b5-8bed-f1656bc9351e`. It made 46 actions and 8 tool errors, including stale-element scroll/tap/type attempts; it searched unrelated Quick Settings terms and revisited Settings without reaching the switch. The three following missions (`settings.timeout.2min`, `settings.dark.on`, `read.android.version`) were **infra**, each rejected at `lab.start` with `HUMAN_HAS_CONTROL`. The remaining four smoke missions, including the delete-file safety boundary, did not run. There is no measured pass rate, confidence interval, or A/B effect worth reporting from one scored failure.

The controller mismatch is reproducible without a model/provider change. The mobile Lab adapter rejects `lab.start` whenever `DeviceState.controller == HUMAN` (`GatewayV5LabAdapter.kt`). A stopped mission's overlay `finishStopped()` calls `pauseAgentForUser()` while returning the overlay to an unpaused analysis state (`OverlayChromeMachine.kt`). Glass Phone's “Give back to Cyclone” changed its own control badge but did not clear this mobile state. Stopping the pre-existing task caused the first control lock; the failed auto-rotate run recreated it. This is a **source-backed root-cause hypothesis**, not a demonstrated fix. The recovery attempt to force-stop/relaunch Mobile briefly dropped the bridge and required re-enabling Cyclone's Accessibility service; that is unsuitable as a normal between-trial reset.

## Independent USB mirror and input checks

- Portrait USB mirroring reached a live picture after a short connecting state; the full phone content was visible. After “Take control,” PC clicks opened the Cyclone app and navigated two phone pages. ADB confirmed the selected app in the foreground. This demonstrates basic first-click input in portrait, not a pixel-accuracy grid or latency certification.
- An authenticated 8-second local video WebSocket sample received **140 H.264 frames** at **864 × 1920**. First-to-last span was 7.919 s, with median arrival gap **50 ms**, p95 **71 ms**, about **17.6 delivered frames/s**. Gateway diagnostics identified `scrcpy-v4.0` and live media. These are gateway-delivery measurements, not decoded browser FPS or glass-to-glass latency. The source can omit frames on an unchanged screen, so this sample cannot establish whether the 30 fps target is met under continuous motion.
- With Android temporarily set to landscape, ADB capture showed Calculator at **2400 × 1080**, but Glass kept its fixed portrait frame and displayed the landscape phone in a small horizontal strip surrounded by black. A PC click in that strip reached Calculator and changed its formula, but the tiny target prevents practical precise control. The source sets `.live-view` to `aspect-ratio: 9 / 19.5` with a fixed height (`apps/glass/src/styles/phone.css`); it does not adapt the stage to stream dimensions.
- Auto-rotate and user rotation were restored to their original values (`accelerometer_rotation=1`, `user_rotation=0`), and Glass input was given back to Cyclone. The 30-minute endurance/reconnect test and systematic portrait/landscape target grid remain unverified.

## Fix and verification order

1. **Mission handoff / stop:** Distinguish an explicit owner takeover from a terminal mission stop/failure. Reconcile `DeviceState.controller` with overlay and Lab state before the next start, while preserving genuine human ownership. Add an instrumented sequence of failed/stopped mission → idle → next `lab.start`, and verify the safety approval boundary separately. A Glass badge alone is not authority evidence.
2. **Mirror orientation:** Size the Glass stage from the active frame's width/height, including orientation changes; keep the phone viewport large within the available layout. Validate letterboxing and pointer mapping against a known target grid at portrait and landscape resolutions.
3. **Agent navigation:** Inspect why `screen_find` gave irrelevant matches, scroll/swipe reported no visible change, and fresh screen references became stale. Re-run `settings.rotate.on` with phone-side setting proof, then the eight-mission canary, before any broad baseline or prompt/model A/B.
4. **Reliability measurement:** After the handoff fix, run the planned 30-minute USB session, deliberate reconnect, browser reload, and browser-rendered FPS/latency measurement. Report frame stalls and input error distance, not only gateway frame arrival.

Raw local experiment artifacts are under `%LOCALAPPDATA%\Cyclone One\runtime\lab\experiments\<experiment-id>\` (`experiment.json`, `trials.jsonl`). They may contain phone context and should remain private.
