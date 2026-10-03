package com.cyclone.mobile.runtime.update

interface RuntimeSlotStore {
    fun activeKnownGood(): RuntimeSlotSnapshot
    fun candidate(): RuntimeSlotSnapshot?
    fun beginCandidate(updateId: String, manifestSha256: String)
    fun stageResource(updateId: String, resource: RuntimeResourceDescriptor, bytes: ByteArray)
    fun markCandidateComplete(updateId: String)
    fun markCandidateFailed(updateId: String)
    fun markActivationRequested(updateId: String, request: RuntimeActivationRequest)
}

/**
 * Deterministic local state implementation suitable for an Android-owned persistence adapter.
 * Payloads never escape through snapshots. A is immutable; every mutating method is scoped to B.
 */
class InMemoryRuntimeSlotStore(
    activeUpdateId: String,
    activeManifestSha256: String,
    activeResources: List<StagedResourceMetadata> = emptyList(),
) : RuntimeSlotStore {
    private data class CandidateState(
        val updateId: String,
        val manifestSha256: String,
        var state: RuntimeSlotState,
        val resources: LinkedHashMap<String, Pair<StagedResourceMetadata, ByteArray>> = linkedMapOf(),
        var activationRequest: RuntimeActivationRequest? = null,
    )

    private val active = RuntimeSlotSnapshot(
        slot = RuntimeSlotId.A,
        state = RuntimeSlotState.ACTIVE_KNOWN_GOOD,
        updateId = activeUpdateId,
        manifestSha256 = activeManifestSha256,
        resources = activeResources.sortedBy { it.path },
    )
    private var candidateState: CandidateState? = null

    @Synchronized
    override fun activeKnownGood(): RuntimeSlotSnapshot = active.copy(resources = active.resources.toList())

    @Synchronized
    override fun candidate(): RuntimeSlotSnapshot? = candidateState?.snapshot()

    @Synchronized
    override fun beginCandidate(updateId: String, manifestSha256: String) {
        candidateState = CandidateState(updateId, manifestSha256, RuntimeSlotState.STAGING)
    }

    @Synchronized
    override fun stageResource(updateId: String, resource: RuntimeResourceDescriptor, bytes: ByteArray) {
        val candidate = requireCandidate(updateId, RuntimeSlotState.STAGING)
        candidate.resources[resource.path] = StagedResourceMetadata(
            path = resource.path,
            kind = resource.kind,
            sha256 = resource.sha256.lowercase(),
            sizeBytes = resource.sizeBytes,
            schemaId = resource.schemaId,
            schemaVersion = resource.schemaVersion,
        ) to bytes.copyOf()
    }

    @Synchronized
    override fun markCandidateComplete(updateId: String) {
        requireCandidate(updateId, RuntimeSlotState.STAGING).state = RuntimeSlotState.STAGED_COMPLETE
    }

    @Synchronized
    override fun markCandidateFailed(updateId: String) {
        val candidate = candidateState ?: return
        if (candidate.updateId == updateId && candidate.state != RuntimeSlotState.ACTIVATION_REQUESTED) {
            candidate.state = RuntimeSlotState.FAILED
        }
    }

    @Synchronized
    override fun markActivationRequested(updateId: String, request: RuntimeActivationRequest) {
        val candidate = requireCandidate(updateId, RuntimeSlotState.STAGED_COMPLETE)
        require(request.candidateSlot == RuntimeSlotId.B && request.activeKnownGoodSlot == RuntimeSlotId.A)
        candidate.activationRequest = request
        candidate.state = RuntimeSlotState.ACTIVATION_REQUESTED
    }

    @Synchronized
    fun readCandidateResourceForTest(path: String): ByteArray? =
        candidateState?.resources?.get(path)?.second?.copyOf()

    private fun requireCandidate(updateId: String, requiredState: RuntimeSlotState): CandidateState {
        val candidate = requireNotNull(candidateState) { "Candidate B is not initialized" }
        require(candidate.updateId == updateId) { "Candidate update id mismatch" }
        require(candidate.state == requiredState) { "Candidate B must be $requiredState" }
        return candidate
    }

    private fun CandidateState.snapshot(): RuntimeSlotSnapshot = RuntimeSlotSnapshot(
        slot = RuntimeSlotId.B,
        state = state,
        updateId = updateId,
        manifestSha256 = manifestSha256,
        resources = resources.values.map { it.first }.sortedBy { it.path },
        activationRequest = activationRequest,
    )
}
