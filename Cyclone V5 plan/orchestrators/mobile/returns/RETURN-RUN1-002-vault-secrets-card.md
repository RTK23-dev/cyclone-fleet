# RETURN-RUN1-002 — Vault + Secrets Card

## Identity

- Agent: Run 1 / Agent 002
- Branch: `v5/mobile/vault-secrets-card`
- PR: https://github.com/premiumcentraal-boop/Cyclone/pull/145
- Integration base consumed: `c980b43e3245016b5fb29fe524ab20d4b7dd6737`
- Validated implementation head: `7517f264d244b1cd81d9532e93a2675d816f08bb`
- Agent 001 dependency consumed: merged `needs-secret` / V5 gateway contract from PR #144
- Physical-device verification: **UNVERIFIED**

## Result

Run 1 Agent 002 is implemented on the phone.

A native-app credential wall can now:

1. remain the same existing task;
2. project as Agent 001's `TaskInterruption.needsSecret()` / consumer `needs-secret` state;
3. identify the exact current password element, observation, session and display without reading a password value into agent/model context;
4. show the Secrets Card as a sibling of Ask in the existing secure Accessibility overlay;
5. accept a new value or use an existing encrypted slot;
6. decrypt only inside a single-use lease;
7. fill through the canonical `PhoneToolExecutor` path;
8. verify locally without exporting plaintext;
9. zero/revoke the lease;
10. capture a fresh observation and resume the same suspended agent/task only after verified fill.

Skip/Cancel closes the card but does not fail the task. Safe request/target metadata remains reopenable; invoking Cyclone again while the task is still `needs-secret` reopens the pending card. No secret value is retained for this reopen path.

## Vault design

### Storage

- Android Keystore AES-256-GCM.
- StrongBox-backed key is requested first.
- `StrongBoxUnavailableException` falls back to a non-exportable normal Android Keystore key.
- SharedPreferences contains only:
  - safe slot metadata;
  - IV;
  - ciphertext;
  - slot index.
- Ciphertext is authenticated with AAD derived from the canonical slot identity.
- Slot identity is exactly:
  - `placeId`
  - `persona` (`live` or `mapping`)
  - `slotName`

No general plaintext getter exists.

### Lease/fill boundary

`SecretsVault.useOnce(...)` decrypts to a bounded `CharArray`, wraps it in `OneShotSecretLease`, calls the internal Vault-only `PhoneToolExecutor.fillVaultSecretOnce(...)`, and zeroes/revokes on success, failure, or exception.

The fill path is deliberately not a `PhoneToolRequest` / Gateway operation, so the raw value cannot enter normal JSON args, request caches, traces, duplicate-action signatures, PC transport, or Glass.

The leased value is passed as a mutable `CharBuffer` / `CharSequence`; the Vault path does not create an immutable plaintext `String`.

Verification is local:

- ordinary exposed text can use an exact in-process character match;
- Android password fields that expose mask glyphs may verify only when the field is password-flagged and its filled length changed from the pre-fill state to the expected length;
- a same-length no-op does not verify;
- redacted secret verification exports neither secret digest nor secret length.

Foreground filling uses the existing overlay host-yield boundary. Workspace filling proves exact session/display/generation authority through the existing workspace runtime before resolving the live Accessibility node.

## Observation/privacy hardening

The Run-1 secret path also closes the post-fill observation boundary:

- `UiNodeSnapshot` carries a boolean `password` sensitivity marker.
- Password/OTP/card-like sensitive editable nodes are blanked before raw Accessibility snapshots can expose their text.
- Gateway semantic evidence preserves only the boolean password marker, never the password text.
- PageAwareness and legacy page projection now consume `GatewayPrivacy.sanitizeAccessibilitySnapshot(...)`, not the unsanitized snapshot.
- Existing process-local salted editable-state verification remains available for ordinary non-sensitive fields.
- Generic `phone.type` remains policy-denied for password/OTP/payment fields; Vault has its own narrow internal fill path.

## Agent 001 integration

The implementation consumes the merged Agent-001 types rather than creating a second state machine.

### Foreground

`OverlayChromeRuntime.handleAgentResult(...)` checks the active adaptive agent's safe login-wall descriptor. When present it updates the existing task to:

- `TaskPhase.REVIEW`
- `TaskInterruption.needsSecret()`
- `resumable = true`

It does not hand control to HUMAN first, because that would invalidate the exact current fill target. The model agent is already suspended at the human boundary; the only mutation allowed by this path is the human-authorized one-shot Vault fill. The callback verifies task ID + `controlRevision` + interruption kind before resuming the same agent.

### Background workspace

`WorkspaceTaskService.finishTask(...)` uses the same safe descriptor. It does not revoke the workspace lease before the Vault fill. After verified fill it:

- checks the same task/revision;
- verifies the workspace still owns input;
- captures a fresh exact-session observation;
- clears the interruption;
- resumes the same existing adaptive-agent instance.

Stale callbacks are ignored.

## Real credential-wall detector

`SecretWallDetector.passwordForLogin(...)` reuses the existing `LoginAutofillPolicy` classifier and exact current `AgentPageCard`.

For a native app it returns only:

- safe `SecretRequestMetadata`;
- exact observation-scoped element ID;
- observation ID;
- session ID;
- display ID.

It does not return or inspect a password value.

Chrome automatic run targeting is deliberately not guessed as `package:com.android.chrome`, because the frozen V5 identity rule requires `chrome:<origin>`. The gateway metadata APIs accept proper Chrome-origin place IDs, but automatic Chrome secret-wall targeting remains a later hook for the canonical Chrome-origin/place resolver rather than inventing duplicate identity logic in Agent 002.

## Secrets Card

The card is mounted inside the existing Ask/overlay window rather than a second overlay window.

Properties:

- existing Cyclone signature glass language;
- `FLAG_SECURE` inherited from the existing overlay window;
- identifies only safe place/persona/slot/reason metadata;
- password visual transformation for entered values;
- can save/replace;
- can use an existing stored slot only when there is a concrete run-scoped fill target;
- metadata-only gateway requests never gain a fake “Use saved and resume” action;
- Skip/Cancel is nonterminal;
- dismissed waiting card is reopenable;
- stored values are never rendered;
- overlay host-yield semantics remain shared with the existing `OverlayGesturePassthrough` path.

## Vault Settings hook

Agent 002 intentionally did not edit the root Settings navigation file during parallel Run 1.

Mount:

```kotlin
VaultSettingsPanel()
```

The panel is self-contained and shows only:

- place/persona;
- slot name;
- Present/Missing;
- Replace;
- Delete.

Replace opens the metadata-only Secrets Card. Delete removes the encrypted record. Values are never displayed.

## Safe public Kotlin interfaces

These are the intended phone-side integration seams.

### Slot metadata

```kotlin
object SecretsVaultRuntime {
    fun slotCatalog(context: Context): SecretSlotCatalog
    fun allSlots(context: Context): List<SecretSlotMetadata>
    fun delete(context: Context, key: SecretSlotKey): Boolean
}

interface SecretSlotCatalog {
    fun metadata(key: SecretSlotKey): SecretSlotMetadata?
    fun slots(placeId: String, persona: SecretPersona): List<SecretSlotMetadata>
    fun allSlots(): List<SecretSlotMetadata>
    fun hasSlot(key: SecretSlotKey): Boolean
}
```

No plaintext getter is exposed.

### Agent / task card seam

```kotlin
object SecretsPhoneFacade {
    fun slotPresence(
        context: Context,
        placeId: String,
        persona: SecretPersona,
    ): Map<String, Boolean>

    fun requestCard(
        context: Context,
        request: SecretRequestMetadata,
        onResolution: (SecretUseResult) -> Unit = {},
    )

    fun requestForRun(
        context: Context,
        request: SecretRequestMetadata,
        target: SecretFillTarget,
        onResolution: (SecretUseResult) -> Unit,
    )
}
```

`requestForRun` is phone-local only. Its observation-scoped target must not be added to the V5 wire schema.

### Card reopen

```kotlin
SecretsCardRuntime.reopenWaiting(): Boolean
```

This reopens only a retained metadata/target request; no secret value is retained.

### Consumer result

```kotlin
data class SecretUseResult(
    val status: SecretUseStatus,
    val verified: Boolean = false,
    val errorCode: String? = null,
) {
    val taskMayResume: Boolean
    val taskTerminal: Boolean
}
```

Only a verified `FILLED` result makes `taskMayResume == true`. `SKIPPED`, `CANCELLED`, `STORED`, failures, and missing slots do not resume a blocked run.

### Settings

```kotlin
@Composable
fun VaultSettingsPanel(modifier: Modifier = Modifier)

@Composable
fun VaultSettingsSection(
    slots: List<SecretSlotMetadata>,
    onReplace: (SecretSlotKey) -> Unit,
    onDelete: (SecretSlotKey) -> Unit,
    modifier: Modifier = Modifier,
)
```

### Agent-001 gateway source

Agent 002 installs a phone-owned implementation through the Agent-001 seam:

```kotlin
GatewayV5ContractSources.installSecrets(VaultGatewayV5SecretsSource(...))
```

The source returns only `Map<String, Boolean>` slot presence and accepts only Agent-001 safe request metadata.

Glass/PC should continue using Agent 001's `secrets.slots` and `secrets.request` contract; it should never call or receive the one-shot fill value.

## Changed implementation files

- `apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolExecutor.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolProtocol.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneTypeEngine.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OverlayChromeController.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayInitProvider.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayObservation.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/AndroidSecretsVault.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/PhoneToolSecretFillExecutor.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretModels.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretWallDetector.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretsCardController.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretsCardOverlay.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretsPhoneFacade.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretsVault.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/VaultGatewayV5SecretsSource.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/VaultSettingsSection.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/PhoneTypeEngineTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/secrets/SecretWallDetectorTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/secrets/SecretsCardControllerTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/secrets/SecretsVaultTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/secrets/VaultGatewayV5SecretsSourceTest.kt`

## Required test coverage

Agent-002-specific tests cover all required cases:

1. save -> slot present;
2. delete -> slot absent;
3. live/mapping personas do not collide;
4. StrongBox-unavailable fallback;
5. one-shot lease;
6. revoke/zero after success and failure;
7. generated plaintext probe absent from diagnostics/store projections;
8. card input plus overlay host-yield behavior;
9. Skip/Cancel nonterminal;
10. stored value never rendered to card/Settings.

Additional regression coverage includes:

- metadata-only request cannot pretend it can fill a saved slot;
- store-only request cannot resume a task;
- Skip -> reopen -> verified fill -> resume;
- Agent-001 gateway returns presence booleans only;
- gateway safe request contains no value field;
- native login wall resolves exact observation/session/display target;
- Chrome package is not misidentified as a Chrome-origin place;
- generic type denies password flag / OTP / payment fields;
- redacted Vault verification exports no secret digest/length;
- masked password fill requires a real length transition;
- same-length no-op does not verify.

## Validation

Definitive CI:

- Workflow: **Cyclone Mobile CI**
- Run: **#1074**
- Run id: `35669722840`
- Validated SHA: `7517f264d244b1cd81d9532e93a2675d816f08bb`
- Result: **SUCCESS**

The workflow completed successfully:

- repository metadata/guards;
- PC Gateway + MCP contract tests;
- Windows gateway dry-run;
- Gradle wrapper validation;
- JDK/Android SDK setup;
- `:app:testDebugUnitTest`;
- `:app:lintDebug`;
- `:app:assembleRelease`;
- Android report upload;
- provenance packaging;
- unsigned release-candidate upload.

Exact Gradle command:

```bash
./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --stacktrace
```

CI log result:

```text
> Task :app:testDebugUnitTest
> Task :app:lintDebug
> Task :app:assembleRelease
BUILD SUCCESSFUL in 8m 18s
```

## Architecture invariants checked

- Phone remains Vault authority.
- PC/Glass receives only safe metadata / boolean presence.
- Secret values never cross Gateway V5 contract.
- No second task-state machine was added.
- Agent 001's `needs-secret` state is reused.
- Generic `phone.type` remains denied for sensitive fields.
- Vault mutation still goes through `PhoneToolExecutor`.
- One screen-changing mutation ownership remains serialized by the existing mutation lock.
- Foreground overlay yield remains centralized in `OverlayGesturePassthrough`.
- Background fill is exact-session/display/generation scoped.
- Stale task revisions cannot resume from a late secret callback.
- Skip/Cancel is nonterminal and reopenable.
- Stored secret values are never rendered back.
- Root Settings navigation was not edited during parallel Run 1.
- No release/version bump was made.
- Physical-device behavior has **not** been claimed as verified.

## Next integration hook

After Agent 002 merges, Agent 003 / final mobile integration may mount `VaultSettingsPanel()` in the root Settings surface alongside Atlas without reimplementing Vault state.

For automatic Chrome secret-wall use, consume the canonical Chrome-origin/place resolver when it exists and construct `SecretRequestMetadata(placeId = "chrome:<origin>", ...)`. Do not substitute the browser package as place identity.
