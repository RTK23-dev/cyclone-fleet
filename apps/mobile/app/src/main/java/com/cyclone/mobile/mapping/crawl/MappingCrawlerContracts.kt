package com.cyclone.mobile.mapping.crawl

/**
 * Agent-005's narrow consumption seam for the Run-2 mapping control plane.
 *
 * The implementation of this interface belongs to Agent 004. The crawler never owns mapping
 * session state, input leases, control revisions, or budgets; it only asks the authoritative
 * phone session for a snapshot and reports verified outcomes.
 */
interface MappingSessionPort {
    fun snapshot(): MappingSessionSnapshot
    fun reportCurrentNode(nodeKey: String)
    fun pauseNeedsSecret(reason: String)
    fun pauseHumanControl(reason: String)
    fun markDanger(doorKey: String, danger: MappingDanger)
    fun recordVerifiedProgress(newScreen: Boolean)
    fun recordNoProgress(doorKey: String)
    fun completePartial(reason: String)
    fun fail(reason: String)
}

data class MappingSessionSnapshot(
    val jobId: String,
    val placeId: String,
    val sessionId: String,
    val displayId: Int,
    val plane: String,
    val controlRevision: Long,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
    val executionGeneration: Long? = null,
    val startedAtMs: Long,
    val budget: MappingBudget,
    val authority: MappingAuthority = MappingAuthority.OWNED,
    val newScreens: Int = 0,
    val consecutiveNonProgress: Int = 0,
    /** Whose account the pass maps with; the owner's own account is look only. */
    val identity: MappingIdentity = MappingIdentity.OWN,
)

/**
 * Whose account a mapping pass uses. OWN ("my account, look only") never walks account or sign-in doors and ends at a
 * sign-in wall. TEST may walk sign-in and onboarding screens; credentials only ever come from the Secrets Card.
 */
enum class MappingIdentity(val wire: String) {
    OWN("own"),
    TEST("test");

    companion object {
        fun fromWire(value: String?): MappingIdentity? = entries.firstOrNull { it.wire == value }
    }
}

data class MappingBudget(
    val maxNewScreens: Int,
    val maxElapsedMs: Long,
    val maxConsecutiveNonProgress: Int,
    val maxAttemptsPerDoor: Int,
) {
    init {
        require(maxNewScreens > 0)
        require(maxElapsedMs > 0)
        require(maxConsecutiveNonProgress > 0)
        require(maxAttemptsPerDoor > 0)
    }
}

enum class MappingAuthority {
    OWNED,
    SESSION_MISSING,
    DISPLAY_MISMATCH,
    HUMAN_CONTROL,
    COMPANION_CONTROL,
    PLANE_CHANGED,
    STALE_CONTROL_REVISION,
}

/**
 * Safe, structural observation projection. Adapters may derive it from AgentPageCard / gateway
 * observations, but ordinary user content must not be copied into these fields.
 */
data class MappingObservation(
    val observationId: String,
    val sessionId: String,
    val displayId: Int,
    val fingerprint: String,
    val structuralFingerprint: String,
    val fresh: Boolean,
    val purpose: StructuralScreenPurpose? = null,
    val doors: List<MappingDoor> = emptyList(),
    /** False when the current foreground package/origin is not the job's place. */
    val inPlace: Boolean = true,
)

enum class StructuralScreenPurpose {
    HOME,
    SETTINGS,
    ACCOUNT,
    SEARCH,
    MENU,
    LIST,
    DETAIL,
    LOGIN,
    PERMISSIONS,
    UNKNOWN,
}

enum class MappingDoorKind {
    TAB,
    MENU,
    NAV_DRAWER,
    SETTINGS,
    SEARCH,
    ACCOUNT,
    BACK,
    HOME,
    /**
     * Opens a panel over the current screen ("+", ⋯, attach, create, a floating button): plan 36 §5.1's reveal. The
     * pass opens it, reads what it offers in the app's own words and goes back; items inside are tapped only when they
     * are doors of their own (a tab, a menu, settings), never a choice.
     */
    REVEAL,
    STRUCTURAL_SAMPLE,
    CONTENT_ROW,
    UNKNOWN,
}

data class MappingDoor(
    val elementId: String,
    val observationId: String,
    val key: String,
    val kind: MappingDoorKind,
    val regionKey: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** Map deeper: this door's words fit a goal the manual could not answer, so it is tried first (still judged first). */
    val focus: Boolean = false,
)

/**
 * Atlas hints are data only. There is deliberately no executable path/macro field.
 */
data class MappingAtlasHint(
    val currentNodeKey: String? = null,
    val knownDoorKeys: Set<String> = emptySet(),
    val darkDoorKeys: Set<String> = emptySet(),
)

data class VerifiedStructure(
    val fromNodeKey: String,
    val toNodeKey: String,
    val doorKey: String,
    val doorKind: MappingDoorKind,
    val beforeStructuralFingerprint: String,
    val afterStructuralFingerprint: String,
)

interface MappingObservationPort {
    /** Must return a newly captured observation for exactly the bound session/display. */
    fun freshObservation(session: MappingSessionSnapshot): MappingObservation?

    /** Why the last [freshObservation] returned null (a short code for the report), or null. */
    fun lastFailure(): String? = null
}

interface MappingAtlasPort {
    /** Agent 005 always requests and writes the mapping persona. */
    fun hint(placeId: String, persona: String, observation: MappingObservation): MappingAtlasHint
    fun recordVerified(placeId: String, persona: String, structure: VerifiedStructure)
    fun markDanger(placeId: String, persona: String, nodeKey: String, doorKey: String, danger: MappingDanger)
    fun markPartial(placeId: String, persona: String, reason: String)
}

enum class MappingDanger {
    NONE,
    PAY,
    SEND_PUBLIC,
    DELETE,
    LOGOUT_ALL,
    GRANT,
    REVIEW_BOUNDARY,
    /** A toggle, or a row holding one: tapping it changes a setting. */
    SETTING_CHANGE,
    /** Follow, like, join, install, block…: tapping it changes state. */
    STATE_CHANGE,
    /** Password, two-factor, passkeys, recovery, logged-in devices, deactivate. */
    SECURITY,
    /** Account and sign-in doors while mapping with the owner's own account (look only). */
    ACCOUNT,
}

/**
 * Production wiring must delegate to the existing GATE / boundary semantics. Agent 005 does not
 * invent a second payment/send/delete/grant classifier.
 */
interface MappingSafetyPort {
    /**
     * Production wiring must re-resolve [MappingDoor.elementId] against the exact current
     * observation and call the existing GateClassifier/grounded policy. Never infer safety from
     * the durable door key alone.
     */
    fun classify(observation: MappingObservation, door: MappingDoor): MappingDanger
}

data class MappingAction(
    val observationId: String,
    val elementId: String,
    val doorKey: String,
    val kind: MappingDoorKind,
)

data class MappingMutationResult(
    val performed: Boolean,
    val verifiedByExecutor: Boolean,
    val errorCode: String? = null,
)

/**
 * Production wiring must execute exactly one [MappingAction] through PhoneToolExecutor and return
 * only after its existing settle/grounding behavior. The crawler never batches actions.
 */
interface MappingMutationPort {
    fun execute(session: MappingSessionSnapshot, action: MappingAction): MappingMutationResult
}

data class MappingSecretWall(
    val placeId: String,
    val slot: String,
    val reason: String,
    val elementId: String,
    val observationId: String,
    val sessionId: String,
    val displayId: Int,
)

/**
 * Metadata-only secret boundary. The implementation may invoke Run-1 SecretsPhoneFacade, but no
 * plaintext or secret-bearing value is representable here.
 */
interface MappingSecretsPort {
    fun detect(session: MappingSessionSnapshot, observation: MappingObservation): MappingSecretWall?
    fun request(wall: MappingSecretWall)
}

sealed class MappingStepResult {
    data class Progress(
        val action: MappingAction,
        val fromNodeKey: String,
        val toNodeKey: String,
    ) : MappingStepResult()

    data class NoProgress(
        val action: MappingAction,
        val reason: String,
    ) : MappingStepResult()

    data class Paused(
        val reason: PauseReason,
    ) : MappingStepResult()

    data class CompletedPartial(
        val reason: String,
    ) : MappingStepResult()

    data class Failed(
        val reason: String,
    ) : MappingStepResult()

    /** Every safe door in the current room is known, dark or dangerous. The job stays running. */
    data class RoomExhausted(
        val nodeKey: String,
    ) : MappingStepResult()

    /** The phone is no longer showing the job's place. The job stays running. */
    data class LeftPlace(
        val reason: String,
    ) : MappingStepResult()
}

/**
 * Non-exploring navigation used only by the driver to backtrack or return to the place entry.
 * Implementations go through PhoneToolExecutor like every other mutation.
 */
interface MappingNavigationPort {
    fun back(session: MappingSessionSnapshot): MappingMutationResult
    fun openPlace(session: MappingSessionSnapshot, resetToEntry: Boolean): MappingMutationResult
}

enum class PauseReason {
    NEEDS_SECRET,
    HUMAN_CONTROL,
    DANGER,
}

/**
 * Fixed by Run-2 law: autonomous/dummy crawl output is never relabelled as live.
 */
internal const val MAPPING_PERSONA = "mapping"
