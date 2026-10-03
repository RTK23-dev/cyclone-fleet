package com.cyclone.mobile.infrastructure.v31

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.ai.CycloneAiAccessPolicy
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.vision.VisionProvider
import com.cyclone.mobile.ai.vision.VisionRouter
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.v31.V31GraphLearningBridge
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.run.FileRoutineRunStore
import com.cyclone.mobile.automation.run.RoutineRunController
import com.cyclone.mobile.brain.graphv2.InMemoryTemporalGraphStore
import com.cyclone.mobile.brain.v31.V31MemoryBridge
import com.cyclone.mobile.gateway.GatewayActionAuthority
import com.cyclone.mobile.gateway.GatewayActionAuthorityDecision
import com.cyclone.mobile.gateway.GatewayActionAuthorityOutcome
import com.cyclone.mobile.gateway.GatewayActionAuthorityRegistry
import com.cyclone.mobile.gateway.GatewayActionAuthorityRequest
import com.cyclone.mobile.gateway.GatewayRuntime
import com.cyclone.mobile.gateway.GatewaySessionExecutionContext
import com.cyclone.mobile.platform.modules.ModuleHealthReport
import com.cyclone.mobile.platform.modules.ModuleOperationResult
import com.cyclone.mobile.policy.ActionRisk
import com.cyclone.mobile.policy.ActionScope
import com.cyclone.mobile.policy.AuthorityClaim
import com.cyclone.mobile.policy.AuthorityGrant
import com.cyclone.mobile.policy.AuthorityOrigin
import com.cyclone.mobile.policy.PolicyAuthorizationClaimResult
import com.cyclone.mobile.policy.PolicyAuthorizationClaimer
import com.cyclone.mobile.policy.PolicyDecision
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PolicyRequest
import com.cyclone.mobile.policy.PolicyTarget
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import java.io.File

/**
 * Final V3.1 composition seam between the proven Cyclone product runtimes and Infrastructure V3.
 * It is additive: legacy stores remain readable while new V3 services are live and supervised.
 */
data class CycloneV31ProductServices(
    val memoryBridge: V31MemoryBridge,
    val graphStore: InMemoryTemporalGraphStore,
    val graphLearningBridge: V31GraphLearningBridge,
    val visionRouter: VisionRouter,
    val routineRuns: RoutineRunController,
)

object CycloneV31ProductIntegration {
    @Volatile private var installed = false
    @Volatile private var productServices: CycloneV31ProductServices? = null

    @Synchronized
    fun install(context: Context, services: CycloneV31Services): CycloneV31ProductServices {
        productServices?.let { return it }
        val appContext = context.applicationContext

        val graphStore = InMemoryTemporalGraphStore()
        val product = CycloneV31ProductServices(
            memoryBridge = V31MemoryBridge(services.memoryService),
            graphStore = graphStore,
            graphLearningBridge = V31GraphLearningBridge(graphStore),
            visionRouter = VisionRouter(emptyList<VisionProvider>()),
            routineRuns = RoutineRunController(
                FileRoutineRunStore(File(appContext.filesDir, "cyclone-v31/routine-runs")),
            ),
        )

        bindExistingProductModules(services)
        GatewayActionAuthorityRegistry.bind(
            "V31_POLICY_GOVERNOR",
            V31GatewayPolicyAuthority(appContext, services),
        )
        GatewayRuntime.startIfEnabled(appContext)

        productServices = product
        installed = true
        services.refreshHealth()
        return product
    }

    fun servicesOrNull(): CycloneV31ProductServices? = productServices
    fun isInstalled(): Boolean = installed

    /**
     * Mark startup healthy only after the existing product bindings are installed. Accessibility is
     * a user permission, not evidence that the canonical phone executor itself is broken.
     */
    fun finalizeStartupWhenReady(services: CycloneV31Services) {
        val health = services.refreshHealth()
        if (health.policyReady && health.phoneExecutorReady) {
            services.recordSuccessfulStartup()
        }
    }

    private fun bindExistingProductModules(services: CycloneV31Services) {
        services.bindings.bind(
            V31ExternalModule.PAGE_AWARENESS,
            ExistingRuntimeBinding("page-awareness.production") {
                if (CycloneAccessibilityService.instance != null) ModuleHealthReport.healthy()
                else ModuleHealthReport.degraded("accessibility-permission-off")
            },
        )
        services.bindings.bind(
            V31ExternalModule.APP_GRAPH,
            ExistingRuntimeBinding("app-graph.production") {
                runCatching { AppLearnerRuntime.learnedApps() }
                    .fold({ ModuleHealthReport.healthy() }, { ModuleHealthReport.failed("app-graph-runtime-failed") })
            },
        )
        services.bindings.bind(
            V31ExternalModule.AUTOMATION,
            ExistingRuntimeBinding("automation.production") {
                runCatching { AutomationRuntime.store.listAutomations() }
                    .fold({ ModuleHealthReport.healthy() }, { ModuleHealthReport.failed("automation-runtime-failed") })
            },
        )
        services.bindings.bind(V31ExternalModule.AI, ExistingRuntimeBinding("ai.production") { ModuleHealthReport.healthy() })
        services.bindings.bind(
            V31ExternalModule.VISION,
            ExistingRuntimeBinding("vision.v31-router") { ModuleHealthReport.degraded("vision-fallback-on-demand") },
        )
        services.bindings.bind(
            V31ExternalModule.GATEWAY,
            ExistingRuntimeBinding("gateway.production") { ModuleHealthReport.healthy() },
        )
    }
}

private class ExistingRuntimeBinding(
    override val bindingId: String,
    private val healthProbe: () -> ModuleHealthReport,
) : V31ExternalModuleBinding {
    override fun start(): ModuleOperationResult = ModuleOperationResult.Success
    override fun stop(): ModuleOperationResult = ModuleOperationResult.Success
    override fun health(): ModuleHealthReport = healthProbe()
}

/**
 * Android remains the authority for PC-originated mutations. Enabling the USB Gateway is treated as
 * a bounded standing user rule for the current authenticated V3.3 session. Only routine/privacy-
 * safe capability classes are eligible; high-risk semantic targets still require a local flow.
 */
private class V31GatewayPolicyAuthority(
    private val context: Context,
    private val services: CycloneV31Services,
) : GatewayActionAuthority {
    private var activeSessionId: String? = null
    private var activeGrantId: String? = null

    override fun authorize(
        context: Context,
        request: GatewayActionAuthorityRequest,
    ): GatewayActionAuthorityDecision {
        if (request.source != "PC_CODEX") return reject(
            GatewayActionAuthorityOutcome.VALIDATION_FAILURE,
            "INVALID_SOURCE",
            "This request did not come from the Cyclone PC bridge.",
        )
        if (!GatewayRuntime.isEnabled(this.context)) return reject(
            GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE,
            "GATEWAY_DISABLED",
            "Turn on PC Gateway in Cyclone first.",
        )
        if (request.capability in READ_ONLY_CAPABILITIES) {
            return allow("READ_ONLY_GATEWAY_CAPABILITY")
        }
        if (request.capability !in MUTATING_CAPABILITIES) return reject(
            GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE,
            "CAPABILITY_NOT_ALLOWED",
            "That phone capability is not available through the PC Gateway.",
        )
        val profileDecision = CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfileStore.read(this.context),
            request.capability,
            request.parameters,
        )
        if (!profileDecision.allowed) return reject(
            GatewayActionAuthorityOutcome.POLICY_DENIED,
            profileDecision.reasonCode,
            profileDecision.safeMessage,
        )
        val observationId = request.currentObservationId?.takeIf(String::isNotBlank)
            ?: return reject(
                GatewayActionAuthorityOutcome.STALE_OBSERVATION,
                "FRESH_OBSERVATION_REQUIRED",
                "Observe the current phone screen again before acting.",
            )

        if (isHighRisk(request)) return reject(
            GatewayActionAuthorityOutcome.POLICY_DENIED,
            "LOCAL_CONFIRMATION_REQUIRED",
            "Cyclone requires a local confirmation for this sensitive action.",
        )

        val authenticatedSession = GatewaySessionExecutionContext.currentTrusted()
            ?: return reject(
                GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE,
                "TRUSTED_SESSION_UNAVAILABLE",
                "Open a fresh trusted Cyclone PC session and retry.",
            )
        val sessionId = authenticatedSession.sessionId?.takeIf(String::isNotBlank)
            ?: return reject(
                GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE,
                "TRUSTED_SESSION_UNAVAILABLE",
                "The authenticated Cyclone session has no usable identity.",
            )
        val now = System.currentTimeMillis()
        val grantId = ensureSessionGrant(sessionId, now)
            ?: return reject(
                GatewayActionAuthorityOutcome.POLICY_DENIED,
                "SESSION_GRANT_UNAVAILABLE",
                "Cyclone could not authorize this PC session.",
            )

        services.observationAuthority.recordCurrent(observationId, now)

        val risk = if (request.capability in setOf("phone.type", "phone.set_clipboard")) {
            ActionRisk.PRIVACY_SENSITIVE
        } else {
            ActionRisk.ROUTINE
        }
        val target = PolicyTarget(targetType = "gateway-session", targetId = sessionId)
        val evaluation = services.policyGovernor.evaluate(
            PolicyRequest(
                actionId = safeActionId(request.requestId),
                capability = request.capability,
                risk = risk,
                principal = PolicyPrincipal(GATEWAY_PRINCIPAL),
                requestedAtEpochMillis = now,
                target = target,
                grantId = grantId,
            ),
        )
        val authorization = evaluation.authorization
            ?: return reject(
                GatewayActionAuthorityOutcome.POLICY_DENIED,
                "POLICY_${evaluation.audit.reason.name}",
                if (evaluation.decision == PolicyDecision.ASK) "Cyclone needs your confirmation for this action." else "Cyclone blocked this action.",
            )
        val claimer = services.policyGovernor as? PolicyAuthorizationClaimer
            ?: return reject(
                GatewayActionAuthorityOutcome.POLICY_DENIED,
                "AUTHORIZATION_CLAIMER_UNAVAILABLE",
                "Cyclone could not validate the action authorization.",
            )
        return when (claimer.claimAuthorization(authorization)) {
            PolicyAuthorizationClaimResult.Claimed -> allow("V31_POLICY_AUTHORIZED")
            is PolicyAuthorizationClaimResult.Rejected -> reject(
                GatewayActionAuthorityOutcome.POLICY_DENIED,
                "AUTHORIZATION_REJECTED",
                "Cyclone rejected an expired or already-used authorization.",
            )
        }
    }

    @Synchronized
    private fun ensureSessionGrant(sessionId: String, now: Long): String? {
        if (activeSessionId == sessionId) {
            activeGrantId?.let { id ->
                val snapshot = services.policyGovernor.inspectGrant(id)
                if (snapshot != null && !snapshot.revoked && snapshot.remainingUses > 0 && now < snapshot.grant.expiresAtEpochMillis) {
                    return id
                }
            }
        }
        activeGrantId?.let { services.policyGovernor.revokeGrant(it) }
        val grantId = "gateway.$sessionId.$now"
        return runCatching {
            services.policyGovernor.issueGrant(
                AuthorityGrant(
                    grantId = grantId,
                    subject = GATEWAY_PRINCIPAL,
                    authority = AuthorityClaim(
                        AuthorityOrigin.STANDING_USER_RULE,
                        "gateway-enabled:$sessionId",
                    ),
                    scope = ActionScope(
                        capabilities = MUTATING_CAPABILITIES,
                        targetType = "gateway-session",
                        targetId = sessionId,
                    ),
                    allowedRisks = setOf(ActionRisk.ROUTINE, ActionRisk.PRIVACY_SENSITIVE),
                    issuedAtEpochMillis = now,
                    expiresAtEpochMillis = now + SESSION_GRANT_LIFETIME_MS,
                    maximumUses = 1_000,
                ),
            )
            activeSessionId = sessionId
            activeGrantId = grantId
            grantId
        }.getOrNull()
    }

    private fun isHighRisk(request: GatewayActionAuthorityRequest): Boolean {
        val declared = request.parameters.optString("_gatewayRisk").uppercase()
        if (declared in setOf(
                "HIGH",
                "AUTHENTICATION",
                "FINANCIAL",
                "DESTRUCTIVE",
                "SECURITY_CRITICAL",
                "EXTERNAL_COMMUNICATION",
            )
        ) return true
        if (request.capability != "phone.type") return false
        val selector = request.parameters.optJSONObject("selector")?.toString().orEmpty().lowercase()
        return SENSITIVE_SELECTOR_WORDS.any(selector::contains)
    }

    private fun allow(reason: String) = GatewayActionAuthorityDecision(
        GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF,
        reason,
        "Authorized by Cyclone.",
    )

    private fun reject(
        outcome: GatewayActionAuthorityOutcome,
        reason: String,
        message: String,
    ) = GatewayActionAuthorityDecision(outcome, reason, message)

    private fun safeActionId(raw: String): String {
        val normalized = raw.filter { it.isLetterOrDigit() || it in "_.:-" }.take(100)
        return normalized.ifBlank { "gateway.action.${System.nanoTime()}" }
    }

    private companion object {
        val GATEWAY_PRINCIPAL = PrincipalRef("cyclone.gateway.pc", PrincipalKind.EXTERNAL_GATEWAY)
        val READ_ONLY_CAPABILITIES = setOf("workspace.list", "phone.observe", "phone.find", "phone.wait_for")
        val MUTATING_CAPABILITIES = setOf(
            "workspace.register",
            "workspace.switch",
            "phone.workspace_switch",
            "workspace.pause",
            "workspace.release",
            "workspace.arm",
            "workspace.next",

            "phone.click",
            "phone.long_press",
            "phone.tap",
            "phone.swipe",
            "phone.scroll",
            "phone.type",
            "phone.set_clipboard",
            "phone.back",
            "phone.home",
            "phone.open_app",
        )
        val SENSITIVE_SELECTOR_WORDS = setOf(
            "password", "passcode", "otp", "one time", "verification code", "pin", "token", "secret", "cvv", "card number",
        )
        const val SESSION_GRANT_LIFETIME_MS = 8L * 60L * 60L * 1000L
    }
}
