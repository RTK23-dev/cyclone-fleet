package com.cyclone.mobile.gateway

import android.content.Context
import org.json.JSONObject

/**
 * Narrow policy/action authority seam for the USB gateway.
 *
 * The gateway is only a transport adapter. It passes a typed proposal to this authority and may
 * hand the proposal to PhoneToolExecutor only after AUTHORIZED_HANDOFF. The V3.1 integration owner
 * must bind the real Policy Governor/action-composition adapter here; this file deliberately does
 * not import infrastructure/v31 or implement policy itself.
 */
data class GatewayActionAuthorityRequest(
    val requestId: String,
    val capability: String,
    val parameters: JSONObject,
    val currentObservationId: String?,
    val source: String,
    val goal: String,
    val missionMetadata: JSONObject,
)

enum class GatewayActionAuthorityOutcome {
    AUTHORIZED_HANDOFF,
    POLICY_DENIED,
    STALE_OBSERVATION,
    CAPABILITY_UNAVAILABLE,
    VALIDATION_FAILURE,
}

data class GatewayActionAuthorityDecision(
    val outcome: GatewayActionAuthorityOutcome,
    val reasonCode: String,
    val safeMessage: String,
)

fun interface GatewayActionAuthority {
    fun authorize(context: Context, request: GatewayActionAuthorityRequest): GatewayActionAuthorityDecision
}

/**
 * Fail-closed compatibility adapter used until the V3.1 composition binds the real authority.
 *
 * It authorizes only the existing non-mutating executor helpers. It never grants a mutating phone
 * action, never evaluates semantic consequence risk, and never invents a user approval. This keeps
 * older startup wiring compilable while making a missing V3 authority obvious and safe.
 */
internal object GatewayCompatibilityActionAuthority : GatewayActionAuthority {
    private val readOnlyCapabilities = setOf("phone.observe", "phone.find", "phone.wait_for")

    override fun authorize(
        context: Context,
        request: GatewayActionAuthorityRequest,
    ): GatewayActionAuthorityDecision {
        if (request.source != "PC_CODEX") {
            return GatewayActionAuthorityDecision(
                GatewayActionAuthorityOutcome.VALIDATION_FAILURE,
                "INVALID_SOURCE",
                "Gateway action source must be PC_CODEX.",
            )
        }
        if (request.capability in readOnlyCapabilities) {
            return GatewayActionAuthorityDecision(
                GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF,
                "COMPATIBILITY_READ_ONLY",
                "Read-only compatibility handoff authorized.",
            )
        }
        if (request.currentObservationId.isNullOrBlank()) {
            return GatewayActionAuthorityDecision(
                GatewayActionAuthorityOutcome.STALE_OBSERVATION,
                "FRESH_OBSERVATION_REQUIRED",
                "A current observation witness is required before a mutating phone action.",
            )
        }
        return GatewayActionAuthorityDecision(
            GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE,
            "V31_ACTION_AUTHORITY_NOT_BOUND",
            "Cyclone action authority is not bound yet; mutating PC actions remain disabled.",
        )
    }
}

object GatewayActionAuthorityRegistry {
    @Volatile
    private var authority: GatewayActionAuthority = GatewayCompatibilityActionAuthority

    @Volatile
    private var bindingName: String = "COMPATIBILITY_FAIL_CLOSED"

    @Synchronized
    fun bind(name: String, authority: GatewayActionAuthority) {
        require(name.isNotBlank()) { "Gateway action authority binding name is required" }
        this.authority = authority
        bindingName = name.take(80)
    }

    internal fun authorize(
        context: Context,
        request: GatewayActionAuthorityRequest,
    ): GatewayActionAuthorityDecision = authority.authorize(context, request)

    fun bindingName(): String = bindingName

    fun isProductionAuthorityBound(): Boolean = bindingName != "COMPATIBILITY_FAIL_CLOSED"

    @Synchronized
    internal fun resetForTests() {
        authority = GatewayCompatibilityActionAuthority
        bindingName = "COMPATIBILITY_FAIL_CLOSED"
    }
}

internal fun GatewayActionAuthorityDecision.requireAuthorized(requestId: String) {
    if (outcome == GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF) return
    val code = when (outcome) {
        GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF -> return
        GatewayActionAuthorityOutcome.POLICY_DENIED -> "POLICY_DENIED"
        GatewayActionAuthorityOutcome.STALE_OBSERVATION -> "STALE_OBSERVATION"
        GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE -> "CAPABILITY_UNAVAILABLE"
        GatewayActionAuthorityOutcome.VALIDATION_FAILURE -> "PROTOCOL_MISMATCH"
    }
    throw GatewayProtocolException(code, safeMessage, requestId)
}
