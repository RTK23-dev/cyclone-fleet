package com.cyclone.mobile.gateway

import android.content.Context
import android.os.PowerManager
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentSemanticVerifier
import com.cyclone.mobile.agent.contract.SemanticElementState
import com.cyclone.mobile.agent.contract.SemanticObservationState
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.PcRouteOutcomeEvidence
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import org.json.JSONObject

/**
 * V3.3 boundary around the existing canonical GatewayActionAdapter/PhoneToolExecutor path.
 * It adds strict observation freshness, a bounded normalized-tap fallback and explicit separation
 * of Android execution from after-state verification. It is not a second executor.
 */
internal object GatewayV33ActionAdapter {
    private val workspaceTools = setOf(
        "workspace.list", "workspace.register", "workspace.switch", "phone.workspace_switch",
        "workspace.pause", "workspace.release", "workspace.arm", "workspace.next",
    )

    val allowedTools = linkedSetOf<String>().apply {
        addAll(GatewayActionAdapter.allowedTools)
        addAll(workspaceTools)
        add("phone.tap")
    }

    private val mutatingTools = setOf(
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
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.launch_intent",
        "phone.set_clipboard",
    )

    internal val LAUNCH_TOOLS = setOf("phone.open_app", "phone.launch_intent", "phone.set_alarm", "phone.set_timer", "phone.open_settings")

    private val pageTransitionTools = setOf(
        "phone.click", "phone.long_press", "phone.back", "phone.home", "phone.open_app", "phone.launch_intent",
        "phone.set_alarm", "phone.set_timer", "phone.open_settings", "phone.submit_text", "phone.tap_point",
    )

    internal fun requiresMutationObservation(tool: String): Boolean = tool in mutatingTools

    internal fun pageTransitionSettleMs(tool: String): Long =
        if (tool in pageTransitionTools) 1_800L else 0L

    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val tool = args.optString("tool").trim()
        if (tool !in allowedTools) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Tool is not enabled for the current PC gateway", requestId)
        }
        return executeTyped(context, requestId, tool, args, publicCapability = true)
    }

    internal fun executeInternal(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
    ): JSONObject {
        if (tool !in allowedTools && tool != "phone.set_clipboard") {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Internal typed phone capability is unavailable", requestId)
        }
        return executeTyped(context, requestId, tool, args, publicCapability = false)
    }

    private fun executeTyped(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
        publicCapability: Boolean,
    ): JSONObject {
        val bound = bindIdentity(requestId, args)
        val beforeObservation = if (requiresMutationObservation(tool)) {
            requireFreshObservation(requestId, args, bound)
        } else {
            try { GatewayObservationStore.current(bound) } catch (_: SessionIdentityException) { null }
        }
        val normalizedArgs = JSONObject(args.toString())
            .put("sessionId", bound.sessionId)
            .put("displayId", bound.displayId)
        val normalizedParams = ExecutionRequestScope.attach(
            JSONObject((args.optJSONObject("params") ?: JSONObject()).toString()),
            bound,
        )
        if (beforeObservation != null && requiresMutationObservation(tool)) {
            normalizedParams.put("observationId", beforeObservation.id)
            if (bound.sessionId != "default-foreground") normalizedParams.put("executionGeneration", beforeObservation.payload.optLong("executionGeneration", -1))
        }
        normalizedArgs.put("params", normalizedParams)

        val baseResult = when (tool) {
            "phone.tap" -> {
                val observation = beforeObservation
                    ?: throw GatewayProtocolException("STALE_OBSERVATION", "Observe the phone before using coordinate fallback", requestId)
                val display = observation.payload.optJSONObject("display")
                val screenWidth = display?.optInt("width") ?: 0
                val screenHeight = display?.optInt("height") ?: 0
                if (screenWidth <= 0 || screenHeight <= 0) {
                    throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Display metrics are unavailable for coordinate fallback", requestId)
                }
                if (bound.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
                    val snapshot = CycloneAccessibilityService.instance?.observe(markFresh = false)
                        ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Accessibility is unavailable for coordinate fallback", requestId)
                    val expectedFingerprint = observation.payload.optString("accessibilityFingerprint")
                    if (expectedFingerprint.isNotBlank() && snapshot.fingerprint != expectedFingerprint) {
                        throw GatewayProtocolException("STALE_OBSERVATION", "Phone geometry changed; observe again before coordinate fallback", requestId)
                    }
                }
                val x = try {
                    DesktopManualControlContract.normalizedPixel(
                        normalizedParams.optDouble("normalizedX", Double.NaN),
                        screenWidth,
                    )
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "normalizedX must be between 0 and 1", requestId)
                }
                val y = try {
                    DesktopManualControlContract.normalizedPixel(
                        normalizedParams.optDouble("normalizedY", Double.NaN),
                        screenHeight,
                    )
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "normalizedY must be between 0 and 1", requestId)
                }
                normalizedParams.remove("normalizedX")
                normalizedParams.remove("normalizedY")
                normalizedParams.put("x", x).put("y", y)
                normalizedArgs.put("params", normalizedParams)
                executeDirect(context, requestId, tool, normalizedArgs, normalizedParams, bound)
                    .put("coordinateBasisObservationId", observation.id)
                    .put("coordinateSpace", "normalized-current-display")
            }
            "phone.set_clipboard" -> executeDirect(context, requestId, tool, normalizedArgs, normalizedParams, bound)
            in workspaceTools -> executeDirect(context, requestId, tool, normalizedArgs, normalizedParams, bound)
            else -> GatewayActionAdapter.execute(context, requestId, normalizedArgs)
        }

        val execution = baseResult.optJSONObject("execution") ?: JSONObject()
        val result = attachResultPlane(requestId, baseResult, bound, normalizedParams, execution.optJSONObject("payload"))
        if (tool.startsWith("workspace.") || tool == "phone.workspace_switch") {
            val ok = execution.optBoolean("ok", false)
            return result
                .put("androidExecution", JSONObject().put("ok", ok))
                .put("verification", JSONObject().put("ok", ok)
                    .put("status", if (ok) "PASSED" else "FAILED")
                    .put("basis", "WORKSPACE_ENGINE_POSTCONDITION"))
                .put("requiresReobserveBeforeNextMutation", true)
        }
        val error = execution.optJSONObject("error")
        val errorCode = error?.optString("code").orEmpty()
        val executorReportedOk = execution.optBoolean("ok", false)
        val verificationFailedInExecutor = errorCode == "ASSERTION_FAILED"
        val androidExecutionOk = executorReportedOk || verificationFailedInExecutor
        val afterObservation = if (tool in mutatingTools && androidExecutionOk) {
            captureAfterAction(context, tool, normalizedParams, beforeObservation, bound)
        } else null
        val expect = normalizedParams.optJSONObject("expect")
        val goalLabel = args.optString("goal")
        val clickedLabel = clickedNodeLabel(normalizedParams, beforeObservation)
        val afterHaystack = observationHaystack(afterObservation)
        val sharedVerification = verifyAfterState(
            tool = tool,
            expectedPackage = normalizedParams.optString("package"),
            goalLabel = goalLabel,
            beforeObservation = beforeObservation,
            afterObservation = afterObservation,
            androidExecutionOk = androidExecutionOk,
            executorAssertionFailed = verificationFailedInExecutor,
            explicitExpectation = expect != null && execution.optJSONObject("payload")?.optBoolean("expectationVerified") == true,
            expectedUri = normalizedParams.optString("uri"),
        )
        val afterStateVerified = sharedVerification.passed
        val verification = when {
            verificationFailedInExecutor -> JSONObject()
                .put("ok", false)
                .put("status", "FAILED")
                .put("code", "VERIFICATION_FAILED")
                .put("message", "Android performed the action but the requested after-state assertion did not pass.")
            !androidExecutionOk -> JSONObject()
                .put("ok", false)
                .put("status", "NOT_RUN")
                .put("code", JSONObject.NULL)
            tool !in mutatingTools -> JSONObject()
                .put("ok", true)
                .put("status", "NOT_REQUIRED")
                .put("code", JSONObject.NULL)
            afterObservation == null -> JSONObject()
                .put("ok", false)
                .put("status", "DEGRADED")
                .put("code", "VERIFICATION_FAILED")
                .put("message", "Action executed but a fresh after-observation could not be captured.")
            expect != null && sharedVerification.basis == "EXPLICIT_EXPECTATION" -> JSONObject()
                .put("ok", true)
                .put("status", "PASSED")
                .put("code", JSONObject.NULL)
                .put("semanticSuccessClaimed", true)
            afterStateVerified -> JSONObject()
                .put("ok", true)
                .put("status", "PASSED")
                .put("code", JSONObject.NULL)
                .put("semanticSuccessClaimed", true)
                .put("basis", sharedVerification.basis ?: "FRESH_AFTER_STATE_CHANGED")
            else -> JSONObject()
                .put("ok", false)
                .put("status", "OBSERVED")
                .put("code", "NO_SEMANTIC_PROGRESS")
                .put("message", "The action was dispatched, but the observed page did not prove the intended transition. Re-observe; do not repeat the same mutation blindly.")
                .put("semanticSuccessClaimed", false)
        }

        val routeLearning = recordVerifiedRouteOutcome(
            context = context,
            goal = args.optString("goal").ifBlank { tool.removePrefix("phone.").replace('_', ' ') },
            tool = tool,
            params = normalizedParams,
            before = beforeObservation,
            after = afterObservation,
            transportOk = true,
            androidExecutionOk = androidExecutionOk,
            verification = verification,
        )

        return result
            .put("transport", JSONObject().put("ok", true).put("protocol", GatewayProtocol.VERSION))
            .put("androidExecution", JSONObject()
                .put("ok", androidExecutionOk)
                .put("executorReportedOk", executorReportedOk)
                .put("errorCode", errorCode.takeIf(String::isNotBlank) ?: JSONObject.NULL))
            .put("afterState", afterObservation?.let(::compactAfterState) ?: JSONObject.NULL)
            .put("verification", verification)
            .put("routeLearning", routeLearning)
            .put("requiresReobserveBeforeNextMutation", requiresMutationObservation(tool))
            .put("publicCapability", publicCapability)
    }

    internal fun captureAfterAction(
        context: Context,
        tool: String,
        params: JSONObject,
        before: GatewayObservation?,
        bound: ExecutionContext? = null,
    ): GatewayObservation? {
        val identity = bound ?: ExecutionRequestScope.bind(params)
        val captureArgs = ExecutionRequestScope.attach(JSONObject(params.toString()), identity)
        val capture = { GatewayObservationAdapter.capture(context, captureArgs) }
        if (tool !in pageTransitionTools) {
            // No page change expected: one good capture is enough, but a capture refused mid-change is retried.
            repeat(4) { attempt ->
                runCatching(capture).getOrNull()?.let { return it }
                if (attempt < 3) Thread.sleep(120L)
            }
            return null
        }
        val launch = tool in LAUNCH_TOOLS
        com.cyclone.mobile.agent.settle.SettleBudgets.attach(context.filesDir)
        val budgetKey = params.optString("package").ifBlank { before?.page?.packageName.orEmpty() }
        val outcome = com.cyclone.mobile.agent.settle.SettleController.run(
            beforeFingerprint = before?.payload?.optString("accessibilityFingerprint"),
            budget = com.cyclone.mobile.agent.settle.SettleBudgets.budgetFor(budgetKey,
                com.cyclone.mobile.agent.settle.SettleBudget(fastMs = pageTransitionSettleMs(tool))),
            capture = capture,
            sample = { settleSample(it, launch) },
            targetReached = { after ->
                verifiedByAfterState(
                    tool,
                    params.optString("package"),
                    before?.page?.pageKey.orEmpty(),
                    before?.payload?.optString("accessibilityFingerprint").orEmpty(),
                    after.page.packageName,
                    after.page.pageKey,
                    after.payload.optString("accessibilityFingerprint"),
                    expectedUri = params.optString("uri"),
                    afterHaystack = observationHaystack(after),
                )
            },
            // A launch passes through splash and first layout; a tap is judged on its first changed frame.
            requireStable = launch,
        )
        com.cyclone.mobile.agent.settle.SettleRecorder.record(identity.sessionId, outcome)
        if (outcome.ready) outcome.value?.page?.packageName?.let { com.cyclone.mobile.agent.settle.SettleBudgets.record(it, outcome.waitedMs) }
        return outcome.value
    }

    internal fun settleSample(observation: GatewayObservation, launch: Boolean): com.cyclone.mobile.agent.settle.SettleSample {
        val elements = observation.elements.values.map { element ->
            Triple(element.role, element.evidence.optString("resourceId"), element.label)
        }
        val actionable = observation.elements.values.count { element ->
            element.evidence.optBoolean("clickable") || element.evidence.optBoolean("editable") ||
                element.evidence.optBoolean("scrollable") || element.evidence.optBoolean("longClickable")
        }
        return com.cyclone.mobile.agent.settle.SettleSample(
            packageName = observation.page.packageName,
            fingerprint = observation.payload.optString("accessibilityFingerprint").ifBlank { observation.page.pageKey },
            actionableControls = actionable,
            loadingEvidence = com.cyclone.mobile.agent.settle.ScreenStateClassifier.loadingEvidence(
                observation.page.packageName, elements, actionable, launch),
        )
    }

    internal fun verifyAfterState(
        tool: String,
        expectedPackage: String,
        goalLabel: String,
        beforeObservation: GatewayObservation?,
        afterObservation: GatewayObservation?,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean = false,
        explicitExpectation: Boolean = false,
        expectedUri: String = "",
    ): AgentSemanticVerification {
        if (beforeObservation != null && afterObservation != null &&
            (beforeObservation.execution != afterObservation.execution || beforeObservation.id == afterObservation.id ||
                afterObservation.capturedAt < beforeObservation.capturedAt)) {
            return AgentSemanticVerification(com.cyclone.mobile.agent.contract.AgentVerificationStatus.FAILED,
                false, false, "OBSERVATION_IDENTITY_MISMATCH", "A different or stale execution surface cannot verify this action.")
        }
        return AgentSemanticVerifier.verify(
        tool = tool,
        androidExecutionOk = androidExecutionOk,
        executorAssertionFailed = executorAssertionFailed,
        explicitExpectation = explicitExpectation,
        expectedPackage = expectedPackage,
        goalLabel = goalLabel,
        before = beforeObservation?.let(::semanticState),
        after = afterObservation?.let(::semanticState),
        expectedUri = expectedUri,
    )
    }

    internal fun verifiedByAfterState(
        tool: String,
        expectedPackage: String,
        beforePageKey: String,
        beforeFingerprint: String,
        afterPackage: String,
        afterPageKey: String,
        afterFingerprint: String,
        goalLabel: String = "",
        clickedLabel: String = "",
        afterHaystack: String = "",
        beforeObservation: GatewayObservation? = null,
        afterObservation: GatewayObservation? = null,
        expectedUri: String = "",
    ): Boolean {
        val beforeState = beforeObservation?.let(::semanticState) ?: SemanticObservationState(
            packageName = "",
            pageKey = beforePageKey,
            accessibilityFingerprint = beforeFingerprint,
            haystack = "",
            elements = emptyList(),
        )
        val afterState = afterObservation?.let(::semanticState) ?: SemanticObservationState(
            packageName = afterPackage,
            pageKey = afterPageKey,
            accessibilityFingerprint = afterFingerprint,
            haystack = afterHaystack,
            elements = emptyList(),
        )
        return AgentSemanticVerifier.verify(
            tool = tool,
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = false,
            expectedPackage = expectedPackage,
            goalLabel = goalLabel,
            before = beforeState,
            after = afterState,
            expectedUri = expectedUri,
        ).passed
    }

    internal fun samePageProgress(
        before: GatewayObservation?,
        after: GatewayObservation?,
        goalLabel: String = "",
    ): Boolean {
        if (before == null || after == null) return false
        val beforeState = semanticState(before)
        val afterState = semanticState(after)
        return AgentSemanticVerifier.samePageSemanticProgress(beforeState, afterState) != null ||
            AgentSemanticVerifier.goalLabelAppeared(beforeState.haystack, afterState.haystack, goalLabel)
    }

    private fun semanticState(observation: GatewayObservation): SemanticObservationState = SemanticObservationState(
        packageName = observation.page.packageName,
        pageKey = observation.page.pageKey,
        accessibilityFingerprint = observation.payload.optString("accessibilityFingerprint"),
        haystack = observationHaystack(observation),
        elements = observation.elements.values.map { element ->
            val evidence = element.evidence
            val stableKey = evidence.optString("controlKey").ifBlank {
                listOf(
                    element.semanticName,
                    element.role,
                    evidence.optString("resourceId"),
                ).joinToString("|")
            }
            SemanticElementState(
                stableKey = stableKey,
                label = element.label,
                role = element.role,
                selected = evidence.optBoolean("selected"),
                checked = evidence.optBoolean("checked"),
                focused = evidence.optBoolean("focused"),
                editableTextState = evidence.optString("textStateDigest")
                    .takeIf { it.isNotBlank() && it != "null" && it != "<redacted>" },
            )
        },
    )

    internal fun labelStillPresent(goalLabel: String, clickedLabel: String, haystack: String): Boolean {
        if (haystack.isBlank()) return false
        return listOf(clickedLabel, goalLabel).any { needle ->
            needle.trim().isNotEmpty() && haystack.contains(needle.trim(), ignoreCase = true)
        }
    }

    internal fun clickedNodeLabel(params: JSONObject, before: GatewayObservation?): String {
        val elementId = params.optString("elementId").ifBlank {
            params.optJSONObject("selector")?.optString("elementId").orEmpty()
                .ifBlank { params.optJSONObject("selector")?.optString("id").orEmpty() }
        }
        if (elementId.isBlank() || before == null) return ""
        return before.elements[elementId]?.label.orEmpty()
    }

    internal fun observationHaystack(observation: GatewayObservation?): String {
        observation ?: return ""
        val parts = mutableListOf<String>()
        parts += observation.page.title
        parts += observation.page.pageKey
        observation.elements.values.forEach { parts += it.label }
        when (val pageText = observation.payload.opt("pageText")) {
            is String -> parts += pageText
            is JSONObject -> {
                parts += pageText.optString("text")
                val lines = pageText.optJSONArray("lines")
                if (lines != null) {
                    for (index in 0 until lines.length()) {
                        val line = lines.optJSONObject(index)
                        parts += line?.optString("text").orEmpty()
                    }
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun executeDirect(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
        params: JSONObject,
        bound: ExecutionContext,
    ): JSONObject {
        val observationId = args.optString("currentObservationId").trim()
        val authorityRequest = GatewayActionAuthorityRequest(
            requestId = requestId,
            capability = tool,
            parameters = JSONObject(params.toString()),
            currentObservationId = observationId,
            source = "PC_CODEX",
            goal = args.optString("goal").ifBlank { tool.removePrefix("phone.").replace('_', ' ') },
            missionMetadata = JSONObject((args.optJSONObject("missionMetadata") ?: JSONObject()).toString()),
        )
        val decision = GatewayActionAuthorityRegistry.authorize(context, authorityRequest)
        decision.requireAuthorized(requestId)
        val scopedParams = ExecutionRequestScope.attach(JSONObject(params.toString()), bound)
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest(requestId, tool, scopedParams))
        val safeParams = if (tool == "phone.set_clipboard") {
            JSONObject().put("text", "[REDACTED]").put("redacted", true)
        } else {
            GatewayPrivacy.redactActionParams(tool, params)
        }
        return attachResultPlane(
            requestId,
            JSONObject()
                .put("source", "PC_CODEX")
                .put("tool", tool)
                .put("authority", JSONObject()
                    .put("binding", GatewayActionAuthorityRegistry.bindingName())
                    .put("outcome", decision.outcome.name)
                    .put("reasonCode", decision.reasonCode))
                .put("sanitizedParams", safeParams)
                .put("execution", GatewayPrivacy.sanitizeDeep(result.toJson())),
            bound,
            scopedParams,
            result.payload as? JSONObject,
        )
    }

    private fun bindIdentity(requestId: String, args: JSONObject): ExecutionContext = try {
        val merged = ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
        if (args.has("workspaceId") && !merged.has("workspaceId")) merged.put("workspaceId", args.get("workspaceId"))
        if (args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
            merged.put("workspaceGeneration", args.get("workspaceGeneration"))
        }
        val plane = SessionContract.classify(merged)
        ExecutionContext(plane.sessionId, plane.displayId)
    } catch (error: SessionIdentityException) {
        throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch", requestId)
    }

    private fun attachResultPlane(
        requestId: String,
        result: JSONObject,
        bound: ExecutionContext,
        params: JSONObject,
        payload: JSONObject? = null,
    ): JSONObject {
        val identity = JSONObject(params.toString())
            .put("sessionId", bound.sessionId)
            .put("displayId", bound.displayId)
        if (payload != null) {
            if (payload.has("workspaceId") && !payload.isNull("workspaceId") &&
                (!identity.has("workspaceId") || identity.isNull("workspaceId"))
            ) {
                identity.put("workspaceId", payload.get("workspaceId"))
            }
            if (payload.has("workspaceGeneration") && !payload.isNull("workspaceGeneration") &&
                (!identity.has("workspaceGeneration") || identity.isNull("workspaceGeneration"))
            ) {
                identity.put("workspaceGeneration", payload.get("workspaceGeneration"))
            }
        }
        return try {
            SessionContract.attach(result, SessionContract.classify(identity))
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch", requestId)
        }
    }

    private fun requireFreshObservation(requestId: String, args: JSONObject, bound: ExecutionContext): GatewayObservation {
        val current = try {
            GatewayObservationStore.current(bound)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException("STALE_OBSERVATION", error.message ?: "Call observe.semantic before a mutating phone action", requestId)
        } ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic before a mutating phone action", requestId)
        val requested = args.optString("currentObservationId").trim()
        if (requested.isBlank() || requested != current.id ||
            current.execution.sessionId != bound.sessionId || current.execution.displayId != bound.displayId
        ) {
            throw GatewayProtocolException(
                "STALE_OBSERVATION",
                "Mutating actions require the exact current observationId; observe again after page changes.",
                requestId,
            )
        }
        return current
    }

    private fun compactAfterState(observation: GatewayObservation): JSONObject = JSONObject()
        .put("observationId", observation.id)
        .put("package", observation.page.packageName)
        .put("pageKey", observation.page.pageKey)
        .put("structuralKey", observation.page.structuralKey)
        .put("contentKey", observation.page.contentKey)
        .put("accessibilityFingerprint", observation.payload.optString("accessibilityFingerprint"))
        .put("capturedAtMs", observation.capturedAt)

    internal fun recordVerifiedRouteOutcome(
        context: Context,
        goal: String,
        tool: String,
        params: JSONObject,
        before: GatewayObservation?,
        after: GatewayObservation?,
        transportOk: Boolean,
        androidExecutionOk: Boolean,
        verification: JSONObject,
        brainSource: String = "PC_CODEX_VERIFIED_ROUTE",
    ): JSONObject {
        val outcome = PcRouteOutcomeEvidence(
            transportOk = transportOk,
            androidExecutionOk = androidExecutionOk,
            verificationStatus = verification.optString("status"),
            before = before?.page,
            after = after?.page,
        )
        if (!outcome.isVerifiedPageOutcome) {
            return JSONObject()
                .put("recorded", false)
                .put("reason", "Transport/executor success is not a verified semantic route outcome")
        }
        val appGraph = runCatching {
            AppLearnerRuntime.recordVerifiedPcRoute(context, tool, params, outcome)
        }.getOrElse { error ->
            return JSONObject().put("recorded", false).put("reason", "App Graph persistence failed safely: ${error.javaClass.simpleName}")
        }
        if (!appGraph.recorded) {
            return JSONObject().put("recorded", false).put("reason", appGraph.reason)
        }
        val brainSignature = runCatching {
            AdaptiveBrainRuntime.recordToolOutcome(
                context = context,
                goal = goal,
                tool = tool,
                params = params,
                before = brainState(before),
                after = brainState(after),
                ok = true,
                source = brainSource,
            ).also { signature ->
                AdaptiveBrainRuntime.recordRunPath(context, goal, listOf(signature), success = true)
            }
        }.getOrNull()
        return JSONObject()
            .put("recorded", true)
            .put("reason", appGraph.reason)
            .put("appGraph", JSONObject()
                .put("fromPageKey", appGraph.fromPageKey ?: JSONObject.NULL)
                .put("toPageKey", appGraph.toPageKey ?: JSONObject.NULL)
                .put("transitionState", appGraph.transitionState?.name ?: JSONObject.NULL))
            .put("brainSignature", brainSignature ?: JSONObject.NULL)
            .put("verifiedOutcome", true)
    }

    private fun brainState(observation: GatewayObservation?): JSONObject = JSONObject()
        .put("currentPackage", observation?.page?.packageName.orEmpty())
        .put("fingerprint", observation?.payload?.optString("accessibilityFingerprint").orEmpty())
}

internal object GatewayV33ManualDesktopAdapter {
    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val kind = args.optString("kind")
        if (kind !in DesktopManualControlContract.allowedKinds) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        if (kind == "wake") {
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isInteractive == true) {
                return JSONObject().put("ok", true).put("kind", "wake").put("status", "ALREADY_AWAKE")
            }
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Safe wake is unavailable on this build", requestId)
        }
        val observation = GatewayObservationAdapter.capture(context, JSONObject())
        val toolArgs = JSONObject()
            .put("source", "PC_CODEX")
            .put("currentObservationId", observation.id)
            .put("goal", "User-owned desktop $kind control")
            .put("params", JSONObject())
        val params = toolArgs.getJSONObject("params")
        when (kind) {
            "tap" -> {
                toolArgs.put("tool", "phone.tap")
                params.put("normalizedX", args.optDouble("x", Double.NaN))
                params.put("normalizedY", args.optDouble("y", Double.NaN))
                // Desktop input must land at the selected pixel, without AI gesture drift.
                params.put("humanize", "off")
                params.put("waitForChangeMs", 0)
            }
            "swipe" -> {
                val snapshot = CycloneAccessibilityService.instance?.observe(markFresh = false)
                    ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Accessibility is unavailable for swipe control", requestId)
                fun pixel(name: String, size: Int): Int = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble(name, Double.NaN), size)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "$name must be between 0 and 1", requestId)
                }
                toolArgs.put("tool", "phone.swipe")
                params.put("humanize", "off")
                params.put("x1", pixel("x1", snapshot.screenWidth))
                params.put("y1", pixel("y1", snapshot.screenHeight))
                params.put("x2", pixel("x2", snapshot.screenWidth))
                params.put("y2", pixel("y2", snapshot.screenHeight))
                params.put("durationMs", args.optLong("durationMs", 350L).coerceIn(100L, 3000L))
                params.put("waitForChangeMs", 0)
            }
            "back" -> {
                toolArgs.put("tool", "phone.back")
                params.put("waitForChangeMs", 0)
            }
            "home" -> {
                toolArgs.put("tool", "phone.home")
                params.put("waitForChangeMs", 0)
            }
            "scroll_up" -> {
                toolArgs.put("tool", "phone.scroll")
                params.put("direction", "backward")
                params.put("waitForChangeMs", 0)
            }
            "scroll_down" -> {
                toolArgs.put("tool", "phone.scroll")
                params.put("direction", "forward")
                params.put("waitForChangeMs", 0)
            }
            "text" -> {
                val value = args.optString("text")
                if (value.isBlank() || value.length > 4096) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "text batch is invalid", requestId)
                }
                toolArgs.put("tool", "phone.type")
                params.put("value", value)
                params.put("waitForChangeMs", 0)
            }
        }
        return PhoneToolExecutor.withHumanDesktopControl {
            GatewayV33ActionAdapter.execute(context, requestId, toolArgs)
        }
            .put("manualDesktopKind", kind)
            .put("typedValueRedacted", kind == "text")
    }
}

internal object GatewayV33ClipboardAdapter {
    fun set(context: Context, requestId: String, args: JSONObject): JSONObject {
        if (!GatewayDesktopPreferences.clipboardEnabled(context)) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Clipboard sync is disabled on the phone", requestId)
        }
        val value = args.optString("text")
        if (value.isBlank() || value.length > 16_384 || DesktopClipboardPolicy.looksSensitive(value)) {
            throw GatewayProtocolException("POLICY_DENIED", "Clipboard content was rejected by the privacy filter", requestId)
        }
        val observation = GatewayObservationStore.current() ?: GatewayObservationAdapter.capture(context, JSONObject())
        val actionArgs = JSONObject()
            .put("source", "PC_CODEX")
            .put("currentObservationId", observation.id)
            .put("goal", "Set user-approved PC to phone clipboard")
            .put("params", JSONObject().put("text", value).put("waitForChangeMs", 0))
        return GatewayV33ActionAdapter.executeInternal(context, requestId, "phone.set_clipboard", actionArgs)
            .put("updated", true)
            .put("mode", "PC_TO_PHONE")
            .put("contentRedacted", true)
    }
}
