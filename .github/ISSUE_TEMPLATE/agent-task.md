---
name: Cyclone agent task
about: Contract-first task for one coding agent
labels: agent-task
---

# Mission

## Fast-path classification

- Change class (docs / PC UI / gateway / packaging / MCP / Android / shared contract / release):
- Distributable impact (none / APK / installer / both):
- Focused first test:
- Full gate required once on final SHA:
- Existing CI run/artifact that can be reused:
- Additional agents needed and why independent work exists (default: none):

Read root `AGENTS.md` and `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`, then load only the
owner-lane context.

## Exact base

`<commit SHA>`

## Branch

`agent/<lane>/<task>`

## Owner lane

- [ ] Android control/perception
- [ ] Learning/automation/Brain
- [ ] Mobile AI/UX
- [ ] Android Gateway
- [ ] PC Device Gateway
- [ ] Codex MCP
- [ ] Desktop/Core
- [ ] Integration/release

## Owned paths

```text

```

## Forbidden paths

```text

```

## Input contract

<!-- What does this agent consume? Include fixtures/schema. -->

## Output contract

<!-- What must downstream code be able to rely on? -->

## Acceptance

- [ ] Regression/unit tests
- [ ] Contract tests
- [ ] Relevant CI
- [ ] Physical Android check if required
- [ ] Unchanged green lanes were not rerun without a written reason
- [ ] The same source SHA was not rebuilt or manually transferred unnecessarily

## APK impact

- [ ] No Android artifact impact, or `Cyclone Mobile CI` is required
- [ ] `versionCode` increments if the APK will be distributed
- [ ] `versionName` changes only for a new named release/channel
- [ ] Agent will use `apps/mobile/gradlew`, not add/copy a version workflow
- Required artifact evidence: run ID, artifact name, source SHA, APK SHA-256, signing and physical status

## Non-negotiable invariants

Read `docs/agent-system/DECISIONS.md` when the task changes an invariant or cross-layer architecture;
do not preload it for an unrelated focused patch.

## Required handoff

Return branch, exact base/head SHA, files, tests, contract changes, physical-device status, known limitations and integration instructions.
