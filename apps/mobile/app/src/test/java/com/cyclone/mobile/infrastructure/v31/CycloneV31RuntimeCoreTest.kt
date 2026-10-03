package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.brain.memory.providers.LocalTieredMemoryProvider
import com.cyclone.mobile.infrastructure.v3.ActionCompositionDecision
import com.cyclone.mobile.infrastructure.v3.AuthorizedPhoneActionProposal
import com.cyclone.mobile.infrastructure.v3.CycloneV3ActionComposition
import com.cyclone.mobile.infrastructure.v3.DecisionEvidence
import com.cyclone.mobile.infrastructure.v3.DecisionSource
import com.cyclone.mobile.infrastructure.v3.KnownGoodSnapshotSource
import com.cyclone.mobile.infrastructure.v3.RuntimeUpdateRecoveryBridge
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.context.InMemoryContextLedgerPersistence
import com.cyclone.mobile.platform.modules.ModuleHealthReport
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.SupervisorCommandResult
import com.cyclone.mobile.policy.ActionRisk
import com.cyclone.mobile.policy.ActionScope
import com.cyclone.mobile.policy.AuthorityClaim
import com.cyclone.mobile.policy.AuthorityGrant
import com.cyclone.mobile.policy.AuthorityOrigin
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PolicyAuthorizationClaimResult
import com.cyclone.mobile.policy.PolicyClock
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PolicyRequest
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import com.cyclone.mobile.runtime.recovery.InMemoryRecoveryStateStore
import com.cyclone.mobile.runtime.recovery.RecoveryActivationDecision
import com.cyclone.mobile.runtime.recovery.RecoveryManager
import com.cyclone.mobile.runtime.recovery.RecoveryModuleSnapshot
import com.cyclone.mobile.runtime.recovery.RecoverySnapshot
import com.cyclone.mobile.runtime.recovery.RuntimeIdentity
import com.cyclone.mobile.runtime.update.ActivationRequestDecision
import com.cyclone.mobile.runtime.update.RuntimeActivationRequest
import com.cyclone.mobile.runtime.update.RuntimeApiVersion
import com.cyclone.mobile.runtime.update.RuntimeResourceKind
import com.cyclone.mobile.runtime.update.RuntimeSlotId
import com.cyclone.mobile.runtime.update.RuntimeUpdater
import com.cyclone.mobile.runtime.update.StagedResourceMetadata
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV31RuntimeCoreTest {
    private val principal = PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT)
    private val fixedClock = V31Clock { 100L }

    @Test
    fun initializationCellIsIdempotent() {
        val cell = V31IdempotentCell<Any>()
        val creations = AtomicInteger()
        val first = cell.getOrCreate { creations.incrementAndGet(); Any() }
        val second = cell.getOrCreate { creations.incrementAndGet(); Any() }

        assertSame(first, second)
        assertEquals(1, creations.get())
    }

    @Test
    fun deniedPolicyNeverReachesCanonicalExecutor() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val observation = V31ObservationAuthority(fixedClock)
        observation.recordCurrent("observation.current", 100L)
        var executorCalls = 0
        val executor = CycloneAuthorizedActionExecutor(
            governor,
            observation,
            CanonicalPhoneExecutionDelegate { request -> executorCalls++; success(request) },
            fixedClock,
        )
        val composition = CycloneV3ActionComposition(
            governor,
            executor,
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
            evidenceValidator = observation,
        )

        val decision = composition.propose(
            policyRequest(grantId = null),
            clickRequest(),
            evidence("observation.current"),
        )

        assertTrue(decision is ActionCompositionDecision.Blocked)
        assertEquals(0, executorCalls)
    }

    @Test
    fun staleEvidenceIsRejectedBeforeGrantConsumptionOrExecutor() {
        val governor = governorWithGrant(maximumUses = 1)
        val observation = V31ObservationAuthority(fixedClock)
        observation.recordCurrent("observation.current", 100L)
        var executorCalls = 0
        val executor = CycloneAuthorizedActionExecutor(
            governor,
            observation,
            CanonicalPhoneExecutionDelegate { request -> executorCalls++; success(request) },
            fixedClock,
        )
        val composition = CycloneV3ActionComposition(
            governor,
            executor,
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
            evidenceValidator = observation,
        )

        assertEquals(
            ActionCompositionDecision.Blocked("STALE_SELECTOR"),
            composition.propose(policyRequest(), clickRequest(), evidence("observation.stale")),
        )
        assertEquals(0, governor.inspectGrant("grant.action")!!.usesConsumed)
        assertEquals(0, executorCalls)
    }

    @Test
    fun issuedAuthorizationIsClaimedOnceAndExecutionStaysSeparateFromVerification() {
        val governor = governorWithGrant(maximumUses = 1)
        val authorization = requireNotNull(governor.evaluate(policyRequest()).authorization)
        val observation = V31ObservationAuthority(fixedClock)
        observation.recordCurrent("observation.1", 100L)
        var executorCalls = 0
        val executor = CycloneAuthorizedActionExecutor(
            governor,
            observation,
            CanonicalPhoneExecutionDelegate { request -> executorCalls++; success(request) },
            fixedClock,
        )
        val proposal = AuthorizedPhoneActionProposal(clickRequest(), authorization, evidence("observation.1"))

        val first = executor.propose(proposal)
        val firstRecord = requireNotNull(executor.executionRecord(first))
        assertEquals(1, executorCalls)
        assertEquals(V31ExecutionState.SUCCEEDED, firstRecord.executionState)
        assertEquals(V31VerificationState.PENDING, firstRecord.verificationState)
        assertTrue(executor.recordVerification(first, true))
        assertEquals(V31VerificationState.VERIFIED, executor.executionRecord(first)!!.verificationState)

        val replay = executor.propose(proposal)
        assertEquals(1, executorCalls)
        assertEquals("AUTHORIZATION_REPLAY", executor.executionRecord(replay)!!.rejectionCode)
    }

    @Test
    fun forgedAuthorizationThatWasNeverIssuedCannotReachExecutor() {
        val governor = governorWithGrant(maximumUses = 1)
        val real = requireNotNull(governor.evaluate(policyRequest()).authorization)
        val forged = real.copy(authorizationId = "grant.action:999:action.open")
        assertTrue(governor.claimAuthorization(forged) is PolicyAuthorizationClaimResult.Rejected)
    }

    @Test
    fun capabilityBootstrapRegistersExactV31Surface() {
        val bindings = V31RuntimeBindings()
        val memory = memoryProvider()
        val supervisor = V31Bootstrap.createModuleSupervisor(bindings, { true }, memory)
        supervisor.startAll(100L)
        val bootstrap = V31Bootstrap.registerCapabilities(supervisor)

        assertTrue(bootstrap.failures.isEmpty())
        assertEquals(
            V31Bootstrap.REQUIRED_CAPABILITY_IDS,
            bootstrap.registry.snapshot().map { it.provider.capabilityId.value }.sorted(),
        )
        assertTrue("phone.long_press" in V31Bootstrap.REQUIRED_CAPABILITY_IDS)
        assertTrue("phone.open_app" in V31Bootstrap.REQUIRED_CAPABILITY_IDS)
        assertTrue("phone.wait_for" in V31Bootstrap.REQUIRED_CAPABILITY_IDS)
    }

    @Test
    fun criticalModuleCannotDisableAndFailedOptionalModuleDoesNotTakeDownRuntime() {
        val bindings = V31RuntimeBindings()
        val secretBindingId = "fixture-secret-token"
        bindings.bind(V31ExternalModule.VISION, object : V31ExternalModuleBinding {
            override val bindingId = secretBindingId
            override fun health() = ModuleHealthReport.failed("fixture-optional-failure")
        })
        val memory = memoryProvider()
        val supervisor = V31Bootstrap.createModuleSupervisor(bindings, { true }, memory)
        supervisor.startAll(100L)
        val recovery = RecoveryManager(InMemoryRecoveryStateStore())
        val startup = CycloneV31StartupRecovery(supervisor, recovery, fixedClock)
        startup.prepareStartup()
        val reporter = V31HealthReporter(
            supervisor,
            bindings,
            recovery,
            memory,
            accessibilityReady = { true },
            phoneExecutorReady = { true },
            capabilityBootstrapFailures = emptyList(),
        )

        assertTrue(supervisor.disable(V31Bootstrap.CORE_PHONE) is SupervisorCommandResult.Rejected)
        assertEquals(ModuleState.FAILED, supervisor.status(V31Bootstrap.CORE_VISION)!!.state)
        val health = reporter.snapshot()
        assertTrue(health.runtimeReady)
        assertEquals(V31ServiceState.FAILED, health.visionState)
        assertTrue(V31Bootstrap.CORE_VISION.value in health.degradedModules)
        assertFalse(health.toString().contains(secretBindingId))
    }

    @Test
    fun recoveryOwnsActivationHandoffAndKnownGoodIsNotSelfPromotedByUpdaterBridge() {
        val recovery = RecoveryManager(InMemoryRecoveryStateStore())
        val knownGood = recoverySnapshot()
        recovery.initializeKnownGood(knownGood, 10L)
        val bridge = RuntimeUpdateRecoveryBridge(
            KnownGoodSnapshotSource { slot -> if (slot == RuntimeSlotId.A) knownGood else null },
            recovery,
        )
        val resource = StagedResourceMetadata(
            path = "routing/config.json",
            kind = RuntimeResourceKind.MODEL_ROUTING_CONFIG,
            sha256 = "d".repeat(64),
            sizeBytes = 2L,
            schemaId = "cyclone.routing",
            schemaVersion = 1,
        )
        val request = RuntimeActivationRequest(
            updateId = "update-1",
            activeKnownGoodSlot = RuntimeSlotId.A,
            candidateSlot = RuntimeSlotId.B,
            runtimeApiVersion = RuntimeApiVersion(3, 1),
            manifestSha256 = "b".repeat(64),
            resources = listOf(resource),
            requestedAtEpochMillis = 20L,
        )

        assertEquals(ActivationRequestDecision.Accepted, bridge.requestActivation(request))
        val state = recovery.state()
        assertNotNull(state.candidate)
        assertEquals(knownGood.runtime, state.lastKnownGood!!.runtime)
        assertFalse(RuntimeUpdater::class.java.methods.any {
            it.name.contains("promote", ignoreCase = true) || it.name.contains("rollback", ignoreCase = true)
        })
    }

    @Test
    fun runtimeHealthIsDeterministicAndContainsNoBindingSecrets() {
        val bindings = V31RuntimeBindings()
        listOf(V31ExternalModule.PAGE_AWARENESS, V31ExternalModule.APP_GRAPH, V31ExternalModule.AUTOMATION).forEach { module ->
            bindings.bind(module, object : V31ExternalModuleBinding {
                override val bindingId = "secret-value-${module.name.lowercase()}"
                override fun health() = ModuleHealthReport.healthy()
            })
        }
        val memory = memoryProvider()
        val supervisor = V31Bootstrap.createModuleSupervisor(bindings, { true }, memory)
        val recovery = RecoveryManager(InMemoryRecoveryStateStore())
        val startup = CycloneV31StartupRecovery(supervisor, recovery, fixedClock)
        startup.prepareStartup()
        val reporter = V31HealthReporter(
            supervisor,
            bindings,
            recovery,
            memory,
            accessibilityReady = { true },
            phoneExecutorReady = { true },
            capabilityBootstrapFailures = emptyList(),
        )

        val first = reporter.snapshot()
        val second = reporter.snapshot()
        assertEquals(first, second)
        assertTrue(first.runtimeReady)
        assertTrue(first.policyReady)
        assertTrue(first.phoneExecutorReady)
        assertTrue(first.graphReady)
        assertTrue(first.automationReady)
        assertFalse(first.toString().contains("secret-value"))
        assertEquals(first.degradedModules.sorted(), first.degradedModules)
        assertEquals(first.criticalFailures.sorted(), first.criticalFailures)
    }

    private fun governorWithGrant(maximumUses: Int): InMemoryPolicyGovernor =
        InMemoryPolicyGovernor(PolicyClock { 100L }).also { governor ->
            governor.issueGrant(
                AuthorityGrant(
                    grantId = "grant.action",
                    subject = principal,
                    authority = AuthorityClaim(AuthorityOrigin.DIRECT_USER_MISSION, "mission.user"),
                    scope = ActionScope(setOf("phone.click"), missionId = "mission.settings"),
                    allowedRisks = setOf(ActionRisk.ROUTINE),
                    issuedAtEpochMillis = 50L,
                    expiresAtEpochMillis = 1_000L,
                    maximumUses = maximumUses,
                ),
            )
        }

    private fun policyRequest(grantId: String? = "grant.action") = PolicyRequest(
        actionId = "action.open",
        capability = "phone.click",
        risk = ActionRisk.ROUTINE,
        principal = PolicyPrincipal(principal),
        requestedAtEpochMillis = 100L,
        missionId = "mission.settings",
        grantId = grantId,
    )

    private fun clickRequest() = PhoneToolRequest(
        commandId = "action.open",
        tool = "phone.click",
        params = JSONObject().put("selector", JSONObject().put("text", "Settings")),
    )

    private fun evidence(observationId: String) = DecisionEvidence(
        goalId = "goal.open",
        knowledgeReferences = emptyList(),
        pageObservationId = observationId,
        selectorObservationId = observationId,
        decisionSource = DecisionSource.AI,
    )

    private fun success(request: PhoneToolRequest) = PhoneToolResult(
        commandId = request.commandId,
        tool = request.tool,
        ok = true,
        startedAtMs = 100L,
        finishedAtMs = 101L,
        beforeFingerprint = "before",
        afterFingerprint = "after",
    )

    private fun memoryProvider() = LocalTieredMemoryProvider(Files.createTempDirectory("cyclone-v31-test"))

    private fun recoverySnapshot() = RecoverySnapshot(
        snapshotId = "known.good",
        capturedAtEpochMillis = 0L,
        runtime = RuntimeIdentity("cyclone-v31", "3.1", "a".repeat(64)),
        configurationSha256 = "c".repeat(64),
        modules = listOf(
            RecoveryModuleSnapshot(V31Bootstrap.CORE_PHONE, V31Bootstrap.MODULE_VERSION, enabled = true, essential = true),
            RecoveryModuleSnapshot(V31Bootstrap.CORE_POLICY, V31Bootstrap.MODULE_VERSION, enabled = true, essential = true),
        ),
        schemas = emptyList(),
        lastUpdateId = null,
    )
}
