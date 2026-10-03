package com.cyclone.mobile.runtime.update

class RuntimeUpdater(
    private val runtimeApiVersion: RuntimeApiVersion,
    private val manifestVerifier: RuntimeManifestVerifier,
    private val payloadSource: RuntimePayloadSource,
    private val slotStore: RuntimeSlotStore,
    private val schemaValidator: RuntimeResourceSchemaValidator,
    private val healthPreflight: RuntimeCandidateHealthPreflight,
    private val activationSink: RuntimeActivationRequestSink,
    private val auditSink: RuntimeUpdateAuditSink,
    private val clock: RuntimeUpdateClock,
) {
    @Synchronized
    fun prepare(signedManifest: SignedRuntimeManifest): RuntimeUpdateOutcome {
        val manifestSha256 = RuntimeUpdateValidation.sha256(signedManifest.canonicalPayload)
        val verification = try {
            manifestVerifier.verify(signedManifest)
        } catch (_: Exception) {
            ManifestVerification.Rejected(ManifestRejection.MALFORMED_SIGNED_PAYLOAD)
        }
        if (verification is ManifestVerification.Rejected) {
            audit(RuntimeUpdateAuditEvent.MANIFEST_REJECTED, manifestSha256 = manifestSha256,
                failure = RuntimeUpdateFailureCode.MANIFEST_NOT_VERIFIED)
            return RuntimeUpdateOutcome.Rejected(RuntimeUpdateFailureCode.MANIFEST_NOT_VERIFIED)
        }
        verification as ManifestVerification.Verified
        val manifest = verification.manifest

        val existing = slotStore.candidate()
        if (existing?.updateId == manifest.updateId &&
            existing.manifestSha256 == manifestSha256 &&
            existing.state == RuntimeSlotState.ACTIVATION_REQUESTED
        ) {
            val request = requireNotNull(existing.activationRequest)
            audit(RuntimeUpdateAuditEvent.ACTIVATION_ALREADY_REQUESTED, manifest, manifestSha256,
                signerKeyId = verification.signer.keyId)
            return RuntimeUpdateOutcome.AlreadyRequested(request)
        }

        RuntimeUpdateValidation.validateManifest(manifest, runtimeApiVersion)?.let { failure ->
            audit(RuntimeUpdateAuditEvent.PREFLIGHT_REJECTED, manifest, manifestSha256, failure,
                verification.signer.keyId)
            return RuntimeUpdateOutcome.Rejected(failure)
        }

        try {
            slotStore.beginCandidate(manifest.updateId, manifestSha256)
        } catch (_: Exception) {
            audit(RuntimeUpdateAuditEvent.CANDIDATE_FAILED, manifest, manifestSha256,
                RuntimeUpdateFailureCode.STORAGE_FAILURE, verification.signer.keyId)
            return RuntimeUpdateOutcome.Rejected(RuntimeUpdateFailureCode.STORAGE_FAILURE)
        }
        audit(RuntimeUpdateAuditEvent.STAGING_STARTED, manifest, manifestSha256,
            signerKeyId = verification.signer.keyId)

        for (resource in manifest.resources.sortedBy { it.path }) {
            val read = try {
                payloadSource.read(resource)
            } catch (_: Exception) {
                return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.DOWNLOAD_INTERRUPTED)
            }
            if (!read.complete) {
                return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.PARTIAL_DOWNLOAD)
            }
            if (read.bytes.size.toLong() != resource.sizeBytes) {
                return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.SIZE_MISMATCH)
            }
            val payloadHash = RuntimeUpdateValidation.sha256(read.bytes)
            if (!payloadHash.equals(resource.sha256, ignoreCase = true)) {
                return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.HASH_MISMATCH)
            }
            try {
                slotStore.stageResource(manifest.updateId, resource, read.bytes)
            } catch (_: Exception) {
                return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.STORAGE_FAILURE)
            }
            val schemaDecision = try {
                schemaValidator.validate(resource, read.bytes)
            } catch (_: Exception) {
                SchemaValidation.Invalid("validator-error")
            }
            when (schemaDecision) {
                SchemaValidation.Valid -> Unit
                is SchemaValidation.Invalid -> return failCandidate(
                    manifest, manifestSha256, verification.signer.keyId,
                    RuntimeUpdateFailureCode.SCHEMA_INVALID,
                )
            }
        }

        val candidate = try {
            slotStore.markCandidateComplete(manifest.updateId)
            requireNotNull(slotStore.candidate())
        } catch (_: Exception) {
            return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                RuntimeUpdateFailureCode.STORAGE_FAILURE)
        }
        val expectedResources = manifest.resources.map { resource ->
            StagedResourceMetadata(
                path = resource.path,
                kind = resource.kind,
                sha256 = resource.sha256.lowercase(),
                sizeBytes = resource.sizeBytes,
                schemaId = resource.schemaId,
                schemaVersion = resource.schemaVersion,
            )
        }.sortedBy { it.path }
        if (candidate.slot != RuntimeSlotId.B ||
            candidate.state != RuntimeSlotState.STAGED_COMPLETE ||
            candidate.updateId != manifest.updateId ||
            candidate.manifestSha256 != manifestSha256 ||
            candidate.resources != expectedResources
        ) {
            return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                RuntimeUpdateFailureCode.STORAGE_FAILURE)
        }
        audit(RuntimeUpdateAuditEvent.CANDIDATE_COMPLETE, manifest, manifestSha256,
            signerKeyId = verification.signer.keyId)
        val healthDecision = try {
            healthPreflight.check(candidate)
        } catch (_: Exception) {
            RuntimeHealthDecision.Unhealthy("preflight-error")
        }
        if (healthDecision is RuntimeHealthDecision.Unhealthy) {
            return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                RuntimeUpdateFailureCode.HEALTH_PREFLIGHT_FAILED)
        }

        val request = RuntimeActivationRequest(
            updateId = manifest.updateId,
            activeKnownGoodSlot = RuntimeSlotId.A,
            candidateSlot = RuntimeSlotId.B,
            runtimeApiVersion = runtimeApiVersion,
            manifestSha256 = manifestSha256,
            resources = candidate.resources.sortedBy { it.path },
            requestedAtEpochMillis = clock.nowEpochMillis(),
        )
        val decision = try {
            activationSink.requestActivation(request)
        } catch (_: Exception) {
            ActivationRequestDecision.Rejected("sink-error")
        }
        if (decision is ActivationRequestDecision.Rejected) {
            return failCandidate(manifest, manifestSha256, verification.signer.keyId,
                RuntimeUpdateFailureCode.ACTIVATION_REQUEST_REJECTED)
        }
        try {
            slotStore.markActivationRequested(manifest.updateId, request)
        } catch (_: Exception) {
            // The sink's Accepted decision is the durable cross-service commit. It must be
            // idempotent, so a later retry may safely reconcile this local bookkeeping failure.
            audit(RuntimeUpdateAuditEvent.ACTIVATION_STATE_PERSIST_FAILED, manifest, manifestSha256,
                RuntimeUpdateFailureCode.STORAGE_FAILURE, verification.signer.keyId)
            return RuntimeUpdateOutcome.ActivationRequested(request)
        }
        audit(RuntimeUpdateAuditEvent.ACTIVATION_REQUESTED, manifest, manifestSha256,
            signerKeyId = verification.signer.keyId)
        return RuntimeUpdateOutcome.ActivationRequested(request)
    }

    private fun failCandidate(
        manifest: RuntimeUpdateManifest,
        manifestSha256: String,
        signerKeyId: String,
        failure: RuntimeUpdateFailureCode,
    ): RuntimeUpdateOutcome.Rejected {
        try {
            slotStore.markCandidateFailed(manifest.updateId)
        } catch (_: Exception) {
            // Failure reporting remains fail-closed even if candidate bookkeeping is unavailable.
        }
        audit(RuntimeUpdateAuditEvent.CANDIDATE_FAILED, manifest, manifestSha256, failure, signerKeyId)
        return RuntimeUpdateOutcome.Rejected(failure)
    }

    private fun audit(
        event: RuntimeUpdateAuditEvent,
        manifest: RuntimeUpdateManifest? = null,
        manifestSha256: String? = null,
        failure: RuntimeUpdateFailureCode? = null,
        signerKeyId: String? = null,
    ) {
        try {
            auditSink.record(
                RuntimeUpdateAuditRecord(
                event = event,
                updateId = manifest?.updateId,
                manifestSha256 = manifestSha256,
                activeSlot = RuntimeSlotId.A,
                candidateSlot = RuntimeSlotId.B,
                resourceCount = manifest?.resources?.size ?: 0,
                failureCode = failure,
                signerKeyId = signerKeyId,
                timestampEpochMillis = clock.nowEpochMillis(),
                ),
            )
        } catch (_: Exception) {
            // Audit transport cannot make an otherwise safe update path less deterministic.
        }
    }
}
