package com.cyclone.mobile.runtime.update

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdaterTest {
    private val api = RuntimeApiVersion(3, 0)
    private val clock = RuntimeUpdateClock { 1_700_000_000_000L }

    @Test
    fun verifiedHealthyCandidateRequestsExactBAndPreservesKnownGoodA() {
        val workflow = "{\"steps\":[]}".toByteArray()
        val routing = "{\"provider\":\"local\"}".toByteArray()
        val fixture = fixture(
            resources = listOf(
                resource("routing/config.json", RuntimeResourceKind.MODEL_ROUTING_CONFIG, routing),
                resource("workflows/open.json", RuntimeResourceKind.WORKFLOW_DEFINITION, workflow),
            ),
            payloads = mapOf("routing/config.json" to routing, "workflows/open.json" to workflow),
        )

        val outcome = fixture.updater.prepare(fixture.signed)

        assertTrue(outcome is RuntimeUpdateOutcome.ActivationRequested)
        val request = (outcome as RuntimeUpdateOutcome.ActivationRequested).request
        assertEquals(RuntimeSlotId.A, request.activeKnownGoodSlot)
        assertEquals(RuntimeSlotId.B, request.candidateSlot)
        assertEquals(listOf("routing/config.json", "workflows/open.json"), request.resources.map { it.path })
        assertEquals("active-1", fixture.store.activeKnownGood().updateId)
        assertEquals(RuntimeSlotState.ACTIVE_KNOWN_GOOD, fixture.store.activeKnownGood().state)
        assertEquals(RuntimeSlotState.ACTIVATION_REQUESTED, fixture.store.candidate()?.state)
        assertEquals(listOf(request), fixture.activationRequests)
    }

    @Test
    fun corruptHashCannotActivate() {
        val expected = "trusted".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("policy/rules.json", RuntimeResourceKind.POLICY_DATA, expected)),
            payloads = mapOf("policy/rules.json" to "bad".toByteArray()),
        )

        val outcome = fixture.updater.prepare(fixture.signed)

        assertRejected(outcome, RuntimeUpdateFailureCode.SIZE_MISMATCH)
        assertTrue(fixture.activationRequests.isEmpty())
        assertEquals(RuntimeSlotState.FAILED, fixture.store.candidate()?.state)
        assertEquals("active-1", fixture.store.activeKnownGood().updateId)
    }

    @Test
    fun matchingSizeButCorruptHashCannotActivate() {
        val expected = "trusted".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("policy/rules.json", RuntimeResourceKind.POLICY_DATA, expected)),
            payloads = mapOf("policy/rules.json" to "xxxxxxx".toByteArray()),
        )

        assertRejected(fixture.updater.prepare(fixture.signed), RuntimeUpdateFailureCode.HASH_MISMATCH)
        assertTrue(fixture.activationRequests.isEmpty())
    }

    @Test
    fun incompatibleRuntimeIsRejectedBeforeAnyDownloadOrStaging() {
        val reads = AtomicInteger()
        val bytes = "{}".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("skills/metadata.json", RuntimeResourceKind.SKILL_METADATA, bytes)),
            payloads = mapOf("skills/metadata.json" to bytes),
            compatibility = RuntimeApiCompatibility(RuntimeApiVersion(4, 0), RuntimeApiVersion(5, 0)),
            onRead = { reads.incrementAndGet() },
        )

        assertRejected(fixture.updater.prepare(fixture.signed), RuntimeUpdateFailureCode.INCOMPATIBLE_RUNTIME_API)
        assertEquals(0, reads.get())
        assertEquals(null, fixture.store.candidate())
    }

    @Test
    fun explicitPartialDownloadCannotActivate() {
        val bytes = "template".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("prompts/base.txt", RuntimeResourceKind.PROMPT_TEMPLATE, bytes)),
            payloads = mapOf("prompts/base.txt" to bytes),
            complete = false,
        )

        assertRejected(fixture.updater.prepare(fixture.signed), RuntimeUpdateFailureCode.PARTIAL_DOWNLOAD)
        assertTrue(fixture.activationRequests.isEmpty())
        assertEquals("active-1", fixture.store.activeKnownGood().updateId)
    }

    @Test
    fun interruptedSecondResourceLeavesAActiveAndBFailed() {
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        val fixture = fixture(
            resources = listOf(
                resource("assets/first.txt", RuntimeResourceKind.SIGNED_STATIC_ASSET, first),
                resource("assets/second.txt", RuntimeResourceKind.SIGNED_STATIC_ASSET, second),
            ),
            payloads = mapOf("assets/first.txt" to first, "assets/second.txt" to second),
            throwOnPath = "assets/second.txt",
        )

        assertRejected(fixture.updater.prepare(fixture.signed), RuntimeUpdateFailureCode.DOWNLOAD_INTERRUPTED)
        assertEquals(RuntimeSlotState.FAILED, fixture.store.candidate()?.state)
        assertNotNull(fixture.store.readCandidateResourceForTest("assets/first.txt"))
        assertEquals(RuntimeSlotState.ACTIVE_KNOWN_GOOD, fixture.store.activeKnownGood().state)
        assertTrue(fixture.activationRequests.isEmpty())
    }

    @Test
    fun forbiddenExecutableAndTraversalPathsAreRejected() {
        val bytes = byteArrayOf(1, 2, 3)
        val executable = fixture(
            resources = listOf(resource("runtime/payload.dex", RuntimeResourceKind.SIGNED_RUNTIME_ASSET, bytes)),
            payloads = mapOf("runtime/payload.dex" to bytes),
        )
        val traversal = fixture(
            resources = listOf(resource("../policy.json", RuntimeResourceKind.POLICY_DATA, bytes)),
            payloads = mapOf("../policy.json" to bytes),
        )

        assertRejected(executable.updater.prepare(executable.signed), RuntimeUpdateFailureCode.FORBIDDEN_RESOURCE)
        assertRejected(traversal.updater.prepare(traversal.signed), RuntimeUpdateFailureCode.INVALID_RESOURCE_PATH)
        assertTrue(executable.activationRequests.isEmpty())
        assertTrue(traversal.activationRequests.isEmpty())
    }

    @Test
    fun unknownWireKindCannotEnterTypedManifest() {
        assertEquals(null, RuntimeResourceKind.fromWireName("android-permission"))
        assertEquals(null, RuntimeResourceKind.fromWireName("kotlin-dex"))
        assertEquals(null, RuntimeResourceKind.fromWireName("script"))
    }

    @Test
    fun signaturePolicyDecisionIsRequiredBeforeManifestOrPayloadIsTrusted() {
        val reads = AtomicInteger()
        val bytes = "{}".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("rules/apps.json", RuntimeResourceKind.APP_LEARNING_RULES, bytes)),
            payloads = mapOf("rules/apps.json" to bytes),
            verification = ManifestVerification.Rejected(ManifestRejection.UNKNOWN_SIGNER),
            onRead = { reads.incrementAndGet() },
        )

        assertRejected(fixture.updater.prepare(fixture.signed), RuntimeUpdateFailureCode.MANIFEST_NOT_VERIFIED)
        assertEquals(0, reads.get())
        assertEquals(null, fixture.store.candidate())
    }

    @Test
    fun schemaAndHealthFailuresCannotRequestActivation() {
        val bytes = "{}".toByteArray()
        val schemaFailure = fixture(
            resources = listOf(resource("workflows/bad.json", RuntimeResourceKind.WORKFLOW_DEFINITION, bytes)),
            payloads = mapOf("workflows/bad.json" to bytes),
            schemaValidation = SchemaValidation.Invalid("fixture-invalid"),
        )
        val healthFailure = fixture(
            resources = listOf(resource("workflows/bad.json", RuntimeResourceKind.WORKFLOW_DEFINITION, bytes)),
            payloads = mapOf("workflows/bad.json" to bytes),
            health = RuntimeHealthDecision.Unhealthy("fixture-unhealthy"),
        )

        assertRejected(schemaFailure.updater.prepare(schemaFailure.signed), RuntimeUpdateFailureCode.SCHEMA_INVALID)
        assertRejected(healthFailure.updater.prepare(healthFailure.signed), RuntimeUpdateFailureCode.HEALTH_PREFLIGHT_FAILED)
        assertTrue(schemaFailure.activationRequests.isEmpty())
        assertTrue(healthFailure.activationRequests.isEmpty())
    }

    @Test
    fun auditRecordsCannotContainPayloadOrSignatureBytes() {
        val secretPayload = "DO-NOT-AUDIT-PAYLOAD".toByteArray()
        val secretSignature = "DO-NOT-AUDIT-SIGNATURE".toByteArray()
        val fixture = fixture(
            resources = listOf(resource("prompts/base.txt", RuntimeResourceKind.PROMPT_TEMPLATE, secretPayload)),
            payloads = mapOf("prompts/base.txt" to secretPayload),
            signature = secretSignature,
        )

        fixture.updater.prepare(fixture.signed)
        val renderedAudit = fixture.auditRecords.joinToString("\n")

        assertFalse(renderedAudit.contains(String(secretPayload)))
        assertFalse(renderedAudit.contains(String(secretSignature)))
        assertTrue(fixture.auditRecords.all { it.activeSlot == RuntimeSlotId.A && it.candidateSlot == RuntimeSlotId.B })
    }

    @Test
    fun sameSignedUpdateIsIdempotentAndConcurrentCallsDoNotDuplicateActivation() {
        val bytes = "{}".toByteArray()
        val reads = AtomicInteger()
        val fixture = fixture(
            resources = listOf(resource("policy/rules.json", RuntimeResourceKind.POLICY_DATA, bytes)),
            payloads = mapOf("policy/rules.json" to bytes),
            onRead = { reads.incrementAndGet() },
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<RuntimeUpdateOutcome>())
        val threads = List(2) {
            Thread {
                ready.countDown()
                start.await()
                outcomes += fixture.updater.prepare(fixture.signed)
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, outcomes.count { it is RuntimeUpdateOutcome.ActivationRequested })
        assertEquals(1, outcomes.count { it is RuntimeUpdateOutcome.AlreadyRequested })
        assertEquals(1, reads.get())
        assertEquals(1, fixture.activationRequests.size)
    }

    private fun assertRejected(outcome: RuntimeUpdateOutcome, code: RuntimeUpdateFailureCode) {
        assertTrue("Expected rejection $code but got $outcome", outcome is RuntimeUpdateOutcome.Rejected)
        assertEquals(code, (outcome as RuntimeUpdateOutcome.Rejected).code)
    }

    private fun resource(
        path: String,
        kind: RuntimeResourceKind,
        bytes: ByteArray,
    ) = RuntimeResourceDescriptor(
        path = path,
        kind = kind,
        sha256 = sha256(bytes),
        sizeBytes = bytes.size.toLong(),
        schemaId = "cyclone.fixture",
        schemaVersion = 1,
    )

    private fun fixture(
        resources: List<RuntimeResourceDescriptor>,
        payloads: Map<String, ByteArray>,
        compatibility: RuntimeApiCompatibility = RuntimeApiCompatibility(api, RuntimeApiVersion(4, 0)),
        verification: ManifestVerification? = null,
        complete: Boolean = true,
        throwOnPath: String? = null,
        schemaValidation: SchemaValidation = SchemaValidation.Valid,
        health: RuntimeHealthDecision = RuntimeHealthDecision.Healthy,
        signature: ByteArray = "fixture-signature".toByteArray(),
        onRead: () -> Unit = {},
    ): Fixture {
        val manifest = RuntimeUpdateManifest(
            schemaVersion = 1,
            updateId = "runtime-2026-08-20",
            compatibleRuntimeApi = compatibility,
            resources = resources,
            issuedAtEpochMillis = 1_699_999_999_000L,
        )
        val signed = SignedRuntimeManifest(
            canonicalPayload = "canonical-runtime-2026-08-20".toByteArray(),
            signature = signature,
            keyId = "release-key-1",
            algorithm = "fixture-ed25519",
        )
        val store = InMemoryRuntimeSlotStore(
            activeUpdateId = "active-1",
            activeManifestSha256 = "a".repeat(64),
        )
        val activationRequests = mutableListOf<RuntimeActivationRequest>()
        val auditRecords = mutableListOf<RuntimeUpdateAuditRecord>()
        val trusted = verification ?: ManifestVerification.Verified(
            manifest,
            VerifiedSigner("release-key-1", "fixture-ed25519", "fixture-trust-policy"),
        )
        val updater = RuntimeUpdater(
            runtimeApiVersion = api,
            manifestVerifier = RuntimeManifestVerifier { trusted },
            payloadSource = RuntimePayloadSource { descriptor ->
                onRead()
                if (descriptor.path == throwOnPath) throw IllegalStateException("simulated interruption")
                RuntimePayloadRead(requireNotNull(payloads[descriptor.path]).copyOf(), complete)
            },
            slotStore = store,
            schemaValidator = RuntimeResourceSchemaValidator { _, _ -> schemaValidation },
            healthPreflight = RuntimeCandidateHealthPreflight { health },
            activationSink = RuntimeActivationRequestSink { request ->
                activationRequests += request
                ActivationRequestDecision.Accepted
            },
            auditSink = RuntimeUpdateAuditSink { auditRecords += it },
            clock = clock,
        )
        return Fixture(updater, signed, store, activationRequests, auditRecords)
    }

    private data class Fixture(
        val updater: RuntimeUpdater,
        val signed: SignedRuntimeManifest,
        val store: InMemoryRuntimeSlotStore,
        val activationRequests: MutableList<RuntimeActivationRequest>,
        val auditRecords: MutableList<RuntimeUpdateAuditRecord>,
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
