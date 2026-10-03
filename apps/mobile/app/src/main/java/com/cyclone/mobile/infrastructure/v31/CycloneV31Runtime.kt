package com.cyclone.mobile.infrastructure.v31

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.brain.memory.api.DefaultCycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryApprovalVerifier
import com.cyclone.mobile.brain.memory.api.MemoryPolicyDecision
import com.cyclone.mobile.brain.memory.api.MemoryPolicyResult
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryWritePolicyGate
import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.brain.memory.providers.LocalTieredMemoryProvider
import com.cyclone.mobile.infrastructure.v3.CycloneV3ActionComposition
import com.cyclone.mobile.infrastructure.v3.KnownGoodSnapshotSource
import com.cyclone.mobile.infrastructure.v3.RuntimeUpdateRecoveryBridge
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.context.InMemoryContextLedgerPersistence
import com.cyclone.mobile.platform.capability.ServiceFirstCapabilityRegistry
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PolicyGovernor
import com.cyclone.mobile.runtime.recovery.FileRecoveryStateStore
import com.cyclone.mobile.runtime.recovery.RecoveryCommand
import com.cyclone.mobile.runtime.recovery.RecoveryDecision
import com.cyclone.mobile.runtime.recovery.RecoveryFailureReason
import com.cyclone.mobile.runtime.recovery.RecoveryManager
import com.cyclone.mobile.runtime.update.InMemoryRuntimeSlotStore
import com.cyclone.mobile.runtime.update.RuntimeApiVersion
import com.cyclone.mobile.runtime.update.RuntimeSlotId
import com.cyclone.mobile.runtime.update.RuntimeUpdateAuditRecord
import com.cyclone.mobile.runtime.update.RuntimeUpdateAuditSink
import com.cyclone.mobile.runtime.update.RuntimeUpdateClock
import com.cyclone.mobile.runtime.update.RuntimeUpdater
import java.io.File
import java.nio.file.Path

/** Read-only service container. Existing product runtimes bind into it; it never creates duplicates. */
class CycloneV31Services internal constructor(
    val capabilityRegistry: ServiceFirstCapabilityRegistry,
    val moduleSupervisor: ModuleSupervisor,
    val policyGovernor: PolicyGovernor,
    val memoryService: CycloneMemoryService,
    val tieredMemoryProvider: LocalTieredMemoryProvider,
    val contextLedger: ContextLedger,
    val runtimeUpdater: RuntimeUpdater,
    val recoveryManager: RecoveryManager,
    val actionComposition: CycloneV3ActionComposition,
    val authorizedActionExecutor: CycloneAuthorizedActionExecutor,
    val observationAuthority: V31ObservationAuthority,
    val policyTargetResolver: V31PolicyTargetResolverBridge,
    val bindings: V31RuntimeBindings,
    val runtimeUpdateBindings: V31RuntimeUpdateBindings,
    private val startupRecovery: CycloneV31StartupRecovery,
    private val healthReporter: V31HealthReporter,
    private val updateAuditJournal: V31RuntimeUpdateAuditJournal,
    private val clock: V31Clock,
) {
    @Volatile private var startupPreparation: V31StartupPreparation? = null
    @Volatile private var startupRecordedHealthy = false

    /** Idempotent startup hook; initialize() calls this automatically on first construction. */
    @Synchronized
    fun prepareStartup(): V31StartupPreparation {
        startupPreparation?.let { return it }
        return startupRecovery.prepareStartup().also { startupPreparation = it }
    }

    /** Call once after legacy product runtimes and V3.1 bindings are ready. Replays are harmless. */
    @Synchronized
    fun recordSuccessfulStartup(): RecoveryDecision {
        if (startupRecordedHealthy) return RecoveryDecision.NoChange("startup-already-recorded")
        val health = refreshHealth()
        val decision = startupRecovery.recordSuccessfulStartup(health)
        startupRecordedHealthy = true
        return decision
    }

    @Synchronized
    fun recordFailedStartup(
        reason: RecoveryFailureReason = RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY,
    ): RecoveryDecision = startupRecovery.recordFailedStartup(reason)

    /** Resume/status hook. It retries due optional modules, rechecks health, and returns safe data. */
    @Synchronized
    fun refreshHealth(): CycloneV31Health {
        val now = clock.nowEpochMillis()
        moduleSupervisor.restartDue(now)
        moduleSupervisor.refreshHealth(now)
        return healthSnapshot()
    }

    fun healthSnapshot(): CycloneV31Health {
        val base = healthReporter.snapshot()
        val startupFailure = (startupPreparation as? V31StartupPreparation.Failed)?.failureCode
        return if (startupFailure == null) base else base.copy(
            runtimeReady = false,
            criticalFailures = (base.criticalFailures + startupFailure).distinct().sorted(),
        )
    }

    fun pendingExternalRecoveryCommand(): RecoveryCommand? = startupRecovery.pendingExternalRecoveryCommand()

    fun runtimeUpdateAudit(): List<RuntimeUpdateAuditRecord> = updateAuditJournal.snapshot()
}

/**
 * Process singleton for the V3.1 backbone. MainActivity should call initialize(context) exactly as
 * an additive runtime initialization; no UI or launcher state is created here.
 */
object CycloneV31Runtime {
    private val cell = V31IdempotentCell<CycloneV31Services>()

    @JvmStatic
    fun initialize(context: Context): CycloneV31Services = cell.getOrCreate {
        create(context.applicationContext).also { it.prepareStartup() }
    }

    fun servicesOrNull(): CycloneV31Services? = cell.valueOrNull()

    private fun create(context: Context): CycloneV31Services {
        val clock = V31Clock(System::currentTimeMillis)
        val root = File(context.filesDir, "cyclone-v31")
        require(root.exists() || root.mkdirs()) { "Could not create Cyclone runtime directory" }

        val bindings = V31RuntimeBindings()
        val updateBindings = V31RuntimeUpdateBindings()
        val observationAuthority = V31ObservationAuthority(clock)
        val targetResolver = V31PolicyTargetResolverBridge()
        val policy = InMemoryPolicyGovernor()

        val memoryRoot: Path = File(root, "memory").toPath()
        val memoryProvider = LocalTieredMemoryProvider(memoryRoot)
        val memoryService = DefaultCycloneMemoryService(
            provider = memoryProvider,
            policyGate = productionMemoryPolicy(),
            approvalVerifier = MemoryApprovalVerifier { _, _ -> false },
            auditJournal = InMemoryMemoryAuditJournal(),
        )
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        val recovery = RecoveryManager(
            FileRecoveryStateStore(File(root, "recovery/recovery-state-v1.json")),
        )
        val supervisor = V31Bootstrap.createModuleSupervisor(
            bindings = bindings,
            // Core phone health means the canonical PhoneToolExecutor is structurally present.
            // Accessibility permission is reported independently by V31HealthReporter below.
            accessibilityReady = { true },
            memoryProvider = memoryProvider,
        )
        val capabilityBootstrap = V31Bootstrap.registerCapabilities(supervisor)
        val canonicalExecutor = CycloneAuthorizedActionExecutor(
            authorizationClaimer = policy,
            observationAuthority = observationAuthority,
            canonicalExecutor = CanonicalPhoneExecutionDelegate { request -> PhoneToolExecutor.execute(context, request) },
            clock = clock,
        )
        val actionComposition = CycloneV3ActionComposition(
            policyGovernor = policy,
            executorProposalSink = canonicalExecutor,
            ledger = ledger,
            memory = memoryService,
            targetResolver = targetResolver,
            evidenceValidator = observationAuthority,
        )
        val startupRecovery = CycloneV31StartupRecovery(supervisor, recovery, clock)
        val updateAudit = V31RuntimeUpdateAuditJournal()
        val runtimeSlotStore = InMemoryRuntimeSlotStore(
            activeUpdateId = "compiled-v31",
            activeManifestSha256 = startupRecovery.compiledKnownGoodSnapshot().runtime.manifestSha256,
        )
        val recoveryActivationBridge = RuntimeUpdateRecoveryBridge(
            knownGood = KnownGoodSnapshotSource { slot ->
                if (slot == RuntimeSlotId.A) recovery.state().lastKnownGood else null
            },
            recovery = recovery,
        )
        val updater = RuntimeUpdater(
            runtimeApiVersion = RuntimeApiVersion(3, 1),
            manifestVerifier = updateBindings.manifestVerifierProxy,
            payloadSource = updateBindings.payloadSourceProxy,
            slotStore = runtimeSlotStore,
            schemaValidator = updateBindings.schemaValidatorProxy,
            healthPreflight = updateBindings.healthPreflightProxy,
            activationSink = recoveryActivationBridge,
            auditSink = updateAudit.sink,
            clock = RuntimeUpdateClock { clock.nowEpochMillis() },
        )
        val healthReporter = V31HealthReporter(
            supervisor = supervisor,
            bindings = bindings,
            recovery = recovery,
            memoryProvider = memoryProvider,
            accessibilityReady = { CycloneAccessibilityService.instance != null },
            phoneExecutorReady = { true },
            capabilityBootstrapFailures = capabilityBootstrap.failures,
        )
        return CycloneV31Services(
            capabilityRegistry = capabilityBootstrap.registry,
            moduleSupervisor = supervisor,
            policyGovernor = policy,
            memoryService = memoryService,
            tieredMemoryProvider = memoryProvider,
            contextLedger = ledger,
            runtimeUpdater = updater,
            recoveryManager = recovery,
            actionComposition = actionComposition,
            authorizedActionExecutor = canonicalExecutor,
            observationAuthority = observationAuthority,
            policyTargetResolver = targetResolver,
            bindings = bindings,
            runtimeUpdateBindings = updateBindings,
            startupRecovery = startupRecovery,
            healthReporter = healthReporter,
            updateAuditJournal = updateAudit,
            clock = clock,
        )
    }

    private fun productionMemoryPolicy(): MemoryWritePolicyGate = MemoryWritePolicyGate { request ->
        when {
            request.sensitivity == MemorySensitivity.RESTRICTED ->
                MemoryPolicyResult(MemoryPolicyDecision.DENY, "RESTRICTED_MEMORY_DENIED")
            request.actor.sourceKind in setOf(
                MemorySourceKind.AI_PROPOSAL,
                MemorySourceKind.GATEWAY,
                MemorySourceKind.IMPORT,
            ) -> MemoryPolicyResult(MemoryPolicyDecision.REQUIRE_APPROVAL, "USER_APPROVAL_REQUIRED")
            else -> MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "RUNTIME_MEMORY_ALLOWED")
        }
    }
}

internal class V31IdempotentCell<T : Any> {
    @Volatile private var value: T? = null

    fun getOrCreate(factory: () -> T): T {
        value?.let { return it }
        return synchronized(this) {
            value ?: factory().also { value = it }
        }
    }

    fun valueOrNull(): T? = value
}

internal class V31RuntimeUpdateAuditJournal(
    private val maxRecords: Int = 256,
) {
    private val records = mutableListOf<RuntimeUpdateAuditRecord>()

    init {
        require(maxRecords in 16..2_048)
    }

    val sink = RuntimeUpdateAuditSink { record -> append(record) }

    @Synchronized
    private fun append(record: RuntimeUpdateAuditRecord) {
        records += record
        while (records.size > maxRecords) records.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<RuntimeUpdateAuditRecord> = records.map { it.copy() }
}
