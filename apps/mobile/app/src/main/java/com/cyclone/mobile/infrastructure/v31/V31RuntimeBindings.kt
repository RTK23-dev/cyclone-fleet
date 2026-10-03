package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.infrastructure.v3.DecisionEvidence
import com.cyclone.mobile.infrastructure.v3.TrustedPolicyTargetResolver
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.modules.ModuleHealthReport
import com.cyclone.mobile.platform.modules.ModuleOperationResult
import com.cyclone.mobile.platform.modules.TrustedModuleRuntime
import com.cyclone.mobile.policy.PolicyTarget
import com.cyclone.mobile.runtime.update.ManifestRejection
import com.cyclone.mobile.runtime.update.ManifestVerification
import com.cyclone.mobile.runtime.update.RuntimeCandidateHealthPreflight
import com.cyclone.mobile.runtime.update.RuntimeHealthDecision
import com.cyclone.mobile.runtime.update.RuntimeManifestVerifier
import com.cyclone.mobile.runtime.update.RuntimePayloadRead
import com.cyclone.mobile.runtime.update.RuntimePayloadSource
import com.cyclone.mobile.runtime.update.RuntimeResourceDescriptor
import com.cyclone.mobile.runtime.update.RuntimeResourceSchemaValidator
import com.cyclone.mobile.runtime.update.SchemaValidation
import com.cyclone.mobile.runtime.update.SignedRuntimeManifest

enum class V31ExternalModule(val moduleId: ModuleId) {
    PAGE_AWARENESS(ModuleId("core.page-awareness")),
    APP_GRAPH(ModuleId("core.app-graph")),
    AUTOMATION(ModuleId("core.automation")),
    AI(ModuleId("core.ai")),
    VISION(ModuleId("core.vision")),
    GATEWAY(ModuleId("core.gateway")),
}

/** Agent 2/3 lifecycle/health adapter. It cannot execute a phone mutation. */
interface V31ExternalModuleBinding {
    val bindingId: String
    fun start(): ModuleOperationResult = ModuleOperationResult.Success
    fun stop(): ModuleOperationResult = ModuleOperationResult.Success
    fun health(): ModuleHealthReport
}

/**
 * Bind-once integration registry for already-existing Cyclone runtimes. The V3.1 backbone never
 * starts a second Brain/AppLearner/Automation implementation; it only supervises the binding.
 */
class V31RuntimeBindings {
    private val bindings = linkedMapOf<V31ExternalModule, V31ExternalModuleBinding>()

    @Synchronized
    fun bind(module: V31ExternalModule, binding: V31ExternalModuleBinding): Boolean {
        require(binding.bindingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) {
            "Binding id must be a bounded opaque identifier"
        }
        if (module in bindings) return bindings[module] === binding
        bindings[module] = binding
        return true
    }

    @Synchronized
    fun isBound(module: V31ExternalModule): Boolean = module in bindings

    @Synchronized
    fun health(module: V31ExternalModule): ModuleHealthReport = safeHealth(bindings[module])

    @Synchronized
    internal fun runtime(module: V31ExternalModule): TrustedModuleRuntime = object : TrustedModuleRuntime {
        override fun start(): ModuleOperationResult = synchronized(this@V31RuntimeBindings) {
            val binding = bindings[module] ?: return@synchronized ModuleOperationResult.Success
            safeOperation { binding.start() }
        }

        override fun stop(): ModuleOperationResult = synchronized(this@V31RuntimeBindings) {
            val binding = bindings[module] ?: return@synchronized ModuleOperationResult.Success
            safeOperation { binding.stop() }
        }

        override fun health(): ModuleHealthReport = synchronized(this@V31RuntimeBindings) {
            safeHealth(bindings[module])
        }
    }

    private fun safeHealth(binding: V31ExternalModuleBinding?): ModuleHealthReport {
        if (binding == null) return ModuleHealthReport.degraded("integration-binding-pending")
        return try {
            binding.health()
        } catch (_: Exception) {
            ModuleHealthReport.failed("integration-health-probe-failed")
        }
    }

    private fun safeOperation(operation: () -> ModuleOperationResult): ModuleOperationResult = try {
        operation()
    } catch (_: Exception) {
        ModuleOperationResult.Failure("integration-lifecycle-hook-failed")
    }
}

/** Bind-once trusted Page Awareness target resolver. Caller-controlled action params never fill it. */
class V31PolicyTargetResolverBridge : TrustedPolicyTargetResolver {
    @Volatile
    private var resolver: TrustedPolicyTargetResolver? = null

    @Synchronized
    fun bind(resolver: TrustedPolicyTargetResolver): Boolean {
        if (this.resolver != null) return this.resolver === resolver
        this.resolver = resolver
        return true
    }

    override fun resolve(actionId: String, evidence: DecisionEvidence): PolicyTarget? =
        resolver?.resolve(actionId, evidence)
}

/**
 * Fail-closed bind-once runtime-update inputs. Until Agent 3/integration supplies trusted adapters,
 * signed data cannot be downloaded, validated or considered healthy enough for activation.
 */
class V31RuntimeUpdateBindings {
    @Volatile private var manifestVerifier: RuntimeManifestVerifier? = null
    @Volatile private var payloadSource: RuntimePayloadSource? = null
    @Volatile private var schemaValidator: RuntimeResourceSchemaValidator? = null
    @Volatile private var healthPreflight: RuntimeCandidateHealthPreflight? = null

    @Synchronized
    fun bindManifestVerifier(value: RuntimeManifestVerifier): Boolean = bindOnce(manifestVerifier, value) {
        manifestVerifier = it
    }

    @Synchronized
    fun bindPayloadSource(value: RuntimePayloadSource): Boolean = bindOnce(payloadSource, value) {
        payloadSource = it
    }

    @Synchronized
    fun bindSchemaValidator(value: RuntimeResourceSchemaValidator): Boolean = bindOnce(schemaValidator, value) {
        schemaValidator = it
    }

    @Synchronized
    fun bindHealthPreflight(value: RuntimeCandidateHealthPreflight): Boolean = bindOnce(healthPreflight, value) {
        healthPreflight = it
    }

    internal val manifestVerifierProxy = RuntimeManifestVerifier { signed: SignedRuntimeManifest ->
        manifestVerifier?.verify(signed) ?: ManifestVerification.Rejected(ManifestRejection.UNKNOWN_SIGNER)
    }

    internal val payloadSourceProxy = RuntimePayloadSource { resource: RuntimeResourceDescriptor ->
        payloadSource?.read(resource) ?: throw IllegalStateException("runtime-payload-source-unbound")
    }

    internal val schemaValidatorProxy = RuntimeResourceSchemaValidator { resource, bytes ->
        schemaValidator?.validate(resource, bytes) ?: SchemaValidation.Invalid("SCHEMA_VALIDATOR_UNBOUND")
    }

    internal val healthPreflightProxy = RuntimeCandidateHealthPreflight { candidate ->
        healthPreflight?.check(candidate) ?: RuntimeHealthDecision.Unhealthy("HEALTH_PREFLIGHT_UNBOUND")
    }

    @Synchronized
    fun readiness(): V31RuntimeUpdateReadiness = V31RuntimeUpdateReadiness(
        manifestVerifier = manifestVerifier != null,
        payloadSource = payloadSource != null,
        schemaValidator = schemaValidator != null,
        healthPreflight = healthPreflight != null,
    )

    private fun <T : Any> bindOnce(current: T?, value: T, assign: (T) -> Unit): Boolean {
        if (current != null) return current === value
        assign(value)
        return true
    }
}

data class V31RuntimeUpdateReadiness(
    val manifestVerifier: Boolean,
    val payloadSource: Boolean,
    val schemaValidator: Boolean,
    val healthPreflight: Boolean,
) {
    val ready: Boolean get() = manifestVerifier && payloadSource && schemaValidator && healthPreflight
}
