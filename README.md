# Cyclone

**Cyclone is an Android agent that can observe a phone, decide what to do, act through constrained native tools, verify the result, recover from failures and learn reusable app knowledge.**

This repository is intentionally kept as a current-product launchpad. Historical sprint plans, old control planes, one-off release workflows and retired version folders belong in Git history and GitHub Releases—not in the active tree.

## Current baseline — Cyclone 4.0.0 (V4 Session OS) + Cyclone One 1.1.0 (this branch)

Mobile identity on the published V4 cut is `4.0.0` / versionCode `71` on the published **3.9.12** Background Intelligence foundation plus Stage 1 Fast Path, Stage 2 Session Kernel, Stage 3 Skill Compiler and Stage 4 Cyclone One glass (one Shizuku workspace, Take control / Continue, GATE confirm; a11y-first Page Cards, 300ms settle + fingerprint ladder; display-scoped `sessionId` + `displayId`; learn→compile→replay; JPEG-first live view, human/AI handoff, session tiles bound to `session_id`, MCP observe/act require `session_id`). Phone remains the mutation engine. Cyclone One **1.0.0** remains the last published glass/MCP companion. This branch is the One **1.1.0** PC sprint on mobile **4.0.4** / versionCode **75** with gateway/MCP **4.1.0**. Tag `one-1.1.0` is operator-after-CI and is not claimed. Physical Pixel 8 remains **UNVERIFIED**. See [One 1.1 Stage 5 release lane](docs/ONE_1.1_STAGE5_RELEASE.md), [One 1.1.0 product notes](docs/RELEASE_ONE_1.1.md), [V4 Stage 5 release lane](docs/V4_STAGE5_RELEASE.md), [V4 4.0.0 release notes](docs/RELEASE_V4.md), [V4 Stage 4 One glass](docs/V4_STAGE4_ONE_GLASS.md), [V4 Stage 3 Skill Compiler](docs/V4_STAGE3_SKILL_COMPILER.md), [V4 Stage 2 Session Kernel](docs/V4_STAGE2_SESSION_KERNEL.md) and [V4 Stage 1 Fast Path](docs/V4_STAGE1_FASTPATH.md).

Cyclone 3.9.12 connected Ask Cyclone to isolated background app workspaces, with a compact running card, live View Progress, exact-task Take Control / Continue, local confirmation cards and preserved completed pages. Background work requires Android 15+, Shizuku and a compatible app; the PC companion remains optional. See [the 3.9.12 release notes](docs/RELEASE_3.9.12.md) for that behavior and device-testing limits.

## Existing reliability foundation

Cyclone 3.9.9 focuses on trustworthy standalone execution, cancellation safety, completion grounding and developer-grade failure evidence:

- **Ask Cyclone** — compact floating composer with attachments/settings on the left, microphone/send on the right, and a sheet that follows downward dragging.
- **Model compatibility** — shared portable requests, live endpoint capability filtering and an account-specific model access check. Reasoning uses provider defaults; Contributor identity and account privacy settings are preserved.
- **Goal Contracts** — common goals compile into independently verifiable semantic effects, so model confidence alone cannot mark a task complete.
- **Bounded completion recovery** — rejected `DONE` claims trigger stronger local verification/escalation instead of an expensive repeated-DONE spiral.
- **Structured + Free Mode agent** — Cyclone starts with reliable semantic/learned routes, then changes strategy when verified progress stalls; GATE and policy boundaries remain mandatory.
- **Authoritative target perception** — active execution minimizes Cyclone's own overlay and keeps target-app state separate from Cyclone chrome so the model does not plan against its own UI.
- **Standalone provider execution** — internal API models run directly from the Android app; PC pairing is optional and provider/auth/network failures terminate with clear user-facing reasons instead of malformed-plan loops.
- **Cancellation and deadline boundaries** — Stop and task timeout are rechecked after blocking observation/provider calls and before mutations, so late model plans cannot act after cancellation.
- **Grounded website completion** — simple verified host navigation can finish locally without another provider turn, while intent dispatch or Cyclone's own echoed goal text never counts as proof that a website loaded.
- **Richer model context** — compact scene, route, semantic-control, action-history and runtime-recovery evidence gives capable models a clearer picture of what Android actually shows.
- **Progress-bounded recovery** — changing Android fingerprints do not count as task progress, repeated failures are bounded, and only verified semantic progress resets no-progress budgets.
- **Strict phone-tool contracts** — app launches require resolvable packages and browser navigation can use the allowlisted Android HTTPS intent path as a deterministic route.
- **Brain → Recent runs** — durable run history with sanitized `.txt` diagnostics, split tool/verification failure metrics, completion/recovery telemetry and up to 1 MiB of useful trace evidence.
- **Privacy-first interruption handling** — cookie/consent surfaces are treated semantically without persisting raw secret input, screenshot pixels/Base64 or full accessibility trees in diagnostics.
- **Aurora** — unobtrusive bottom-center persistent activation overlay with a small touch target.
- **Teach + Routines** — reusable app knowledge and repeatable phone workflows.
- **PC integration** — optional Device Gateway, Windows Companion and constrained MCP adapters without creating a second phone-control engine.

Android package: `com.cyclone.mobile`  
Minimum Android: 13 (API 33); isolated background displays require Android 15+
Current mobile identity: `4.0.4` / versionCode `75` on this tree (V4 Session OS remains `4.0.0` / 71 + One 1.0.0 pairing; this branch is the One 1.1 PC sprint)

The product has two deliverables: the Android APK and the optional Windows PC companion.
Internal API models run from the phone with internet access and an API key; PC pairing is not
required. Core/Hermes and the separate Teamwork Sniper app are retired integrations.

Cyclone 3.9.9 is promoted only from the exact Mobile CI artifact after unit tests, lint, repository
and security guards pass. Physical Pixel 8 acceptance remains a separate evidence gate and must not
be inferred from a green CI build. See the [3.9.9 release audit](docs/RELEASE_3.9.9.md) and the
[broader reliability acceptance plan](docs/DUO_RELIABILITY_REVIEW.md).

## Repository

```text
apps/
  mobile/              Android product
  device-gateway/      PC ↔ phone gateway
  pc-companion/        Windows companion

tools/
  codex-phone-mcp/     constrained PC agent tools
  cyclone-agent-mcp/   generic Cyclone MCP adapter

scripts/
  ci/                  product/version guards
  phone-gateway/       gateway setup and acceptance helpers
  pc-companion/        companion tooling

docs/                  current architecture/development/release docs
release/version.toml    product/component version source (this branch: One/gateway/MCP 1.1.0 / 4.1.0; mobile stays 4.0.4)
.github/workflows/      current CI and candidate verification
```

## Build the Android app

Requirements: JDK 17 and Android SDK 35.

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Use GitHub Actions for release candidates so APK provenance, checksum and source SHA stay connected.

## Development rules

Read [`AGENTS.md`](AGENTS.md) before substantial work and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before changing runtime boundaries.

Core invariants:

- one Android package and launcher;
- one canonical phone mutation engine (`PhoneToolExecutor`);
- semantic evidence before coordinate/vision fallback;
- re-observe and verify after page-changing actions;
- explicit approval boundaries for consequential actions;
- no credentials or raw typed secrets in Brain/run diagnostics;
- CI evidence and physical-device evidence are reported separately.

## History

Old Cyclone versions, experiments and retired architecture remain available through Git history, tags, branches and GitHub Releases. They are deliberately not duplicated in the current working tree.

## License

Proprietary. Third-party components remain under their respective licenses; see [`docs/OPEN_SOURCE_COMPONENTS.md`](docs/OPEN_SOURCE_COMPONENTS.md).
