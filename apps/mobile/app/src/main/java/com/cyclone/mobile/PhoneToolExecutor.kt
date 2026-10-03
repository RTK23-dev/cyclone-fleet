package com.cyclone.mobile

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.cyclone.mobile.fastpath.FastPathLoop
import com.cyclone.mobile.fastpath.FastPathSettleResult
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.gesture.HumanGestureRuntimePolicy
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.RuntimeGestureKind
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import com.cyclone.mobile.ui.overlay.GateBlockedException
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PhoneToolExecutor {
    private const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val MAX_TIMEOUT_MS = 30_000L
    private const val DUPLICATE_WINDOW_MS = 350L

    private val mutatingTools = setOf(
        "phone.click", "phone.long_press", "phone.tap", "phone.type", "phone.replace_text",
        "phone.scroll", "phone.swipe", "phone.back", "phone.home", "phone.open_app",
        "phone.open_notification", "phone.set_clipboard", "phone.share", "phone.launch_intent",
        "phone.set_alarm", "phone.set_timer",
    )
    private val touchHumanizeTools = setOf("phone.tap", "phone.long_press", "phone.swipe", "phone.scroll")
    private val humanizeAwareTools = touchHumanizeTools + "phone.click"
    private val mutationLock = com.cyclone.mobile.runtime.workspaces.Layer2Workspaces.engine.mutationLock
    // Only the authenticated manual.execute adapter enters this scope. It lets the desktop human
    // use the canonical executor while the phone remains in HUMAN mode; AI actions stay blocked.
    private val humanDesktopControl = ThreadLocal.withInitial { false }

    internal fun <T> withHumanDesktopControl(action: () -> T): T {
        val previous = humanDesktopControl.get()
        humanDesktopControl.set(true)
        return try { action() } finally { humanDesktopControl.set(previous) }
    }

    internal fun humanDesktopControlActive(): Boolean = humanDesktopControl.get() == true

    private fun foregroundInputAllowed(): Boolean =
        DeviceState.controller == DeviceState.Controller.AGENT || humanDesktopControlActive()
    private val resultCache = object : LinkedHashMap<String, PhoneToolResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhoneToolResult>?): Boolean = size > 250
    }
    private val recentActions = LinkedHashMap<String, Long>()

    /**
     * Read-only tools may run concurrently. Phone mutations remain strictly serialized, but a
     * wait/screenshot/read can no longer hold one global monitor and block unrelated observation.
     */
    fun execute(context: Context, request: PhoneToolRequest): PhoneToolResult {
        val layer2 = com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
        val management = request.tool.startsWith("workspace.") || request.tool == "phone.workspace_switch"
        if (management || request.tool in mutatingTools) return synchronized(mutationLock) {
            try {
                val plane = SessionContract.classify(request.params)
                if (management) {
                    check(plane.kind == SessionPlaneKind.FOREGROUND || plane.kind == SessionPlaneKind.LAYER2_WORKSPACE) {
                        "Layer 2 requires default-foreground / display 0"
                    }
                    if (request.tool != "workspace.list") {
                        synchronized(resultCache) { resultCache.clear() }
                        recentActions.clear()
                    }
                    return@synchronized withPlane(layer2.command(context, request), plane)
                }
                layer2.requireMutation(context, request)
                if (layer2.engine.selectedId() != null) {
                    check(plane.kind == SessionPlaneKind.FOREGROUND || plane.kind == SessionPlaneKind.LAYER2_WORKSPACE) {
                        "MUTATE_LOCK: display-0 workspace owns input"
                    }
                }
                executeScoped(context, request, plane)
            } catch (error: Exception) { scopeFailure(request, error) }
        }
        return executeScoped(context, request)
    }


    /**
     * Internal Vault-only input boundary. This is deliberately not represented by a
     * PhoneToolRequest or GatewayProtocol operation, so plaintext cannot enter wire args,
     * result caches, or diagnostics. It reuses the observation-scoped PhoneTypeEngine and
     * Accessibility ACTION_SET_TEXT path used by phone.type while permitting the
     * human-authorized Secrets Card to fill a sensitive field exactly once.
     */
    internal fun fillVaultSecretOnce(
        context: Context,
        target: com.cyclone.mobile.secrets.SecretFillTarget,
        secret: CharArray,
    ): com.cyclone.mobile.secrets.SecretFillExecution = synchronized(mutationLock) {
        fun fail(code: String) = com.cyclone.mobile.secrets.SecretFillExecution(false, false, code)

        if (secret.isEmpty() || secret.size > PhoneTypeEngine.MAX_VALUE_CHARS) {
            return@synchronized fail("INVALID_SECRET_LENGTH")
        }
        val scope = com.cyclone.mobile.runtime.session.ExecutionContext(target.sessionId, target.displayId)
        val foreground = scope.sessionId == "default-foreground"
        if (foreground && scope.displayId != 0) return@synchronized fail("DISPLAY_SESSION_MISMATCH")
        if (!foreground && scope.displayId <= 0) return@synchronized fail("DISPLAY_SESSION_MISMATCH")

        val service = CycloneAccessibilityService.instance
            ?: return@synchronized fail("ACCESSIBILITY_NOT_CONNECTED")
        val observation = GatewayObservationStore.current(scope)
            ?: return@synchronized fail("STALE_OBSERVATION")
        if (observation.id != target.observationId) {
            return@synchronized fail("STALE_OBSERVATION")
        }

        val snapshot = if (foreground) {
            if (DeviceState.controller != DeviceState.Controller.AGENT) {
                return@synchronized fail("HUMAN_HAS_CONTROL")
            }
            if (DeviceState.requireFreshObservation) {
                return@synchronized fail("FRESH_OBSERVATION_REQUIRED")
            }
            service.observe(markFresh = false)
        } else {
            val runtime = com.cyclone.mobile.runtime.background.WorkspaceRuntime
            val generation = observation.payload.optLong("executionGeneration", -1L)
            if (generation < 0L) return@synchronized fail("STALE_SESSION")
            try {
                runtime.authorizeTouch(scope, generation)
            } catch (_: Exception) {
                return@synchronized fail("WORKSPACE_INPUT_NOT_AUTHORIZED")
            }
            val observed = try {
                runtime.observe(scope)
            } catch (_: Exception) {
                return@synchronized fail("STALE_SESSION")
            }
            if (observed.fingerprint != observation.payload.optString("accessibilityFingerprint")) {
                return@synchronized fail("STALE_OBSERVATION")
            }
            observed
        }

        if (snapshot.fingerprint != observation.payload.optString("accessibilityFingerprint")) {
            return@synchronized fail("STALE_OBSERVATION")
        }

        val catalog = PhoneTypeEngine.catalog(
            observationId = observation.id,
            evidenceElements = observation.elements.values.map {
                PhoneTypeEngine.ObservationElementInput(it.id, it.source, it.role, it.evidence)
            },
            snapshot = snapshot,
        )
        val element = catalog.elements[target.elementId]
            ?: return@synchronized fail("STALE_ELEMENT")
        if (!element.enabled) return@synchronized fail("ACTION_FAILED")
        if (!element.editable) return@synchronized fail("INVALID_TARGET")
        val rawNodeId = element.rawNodeId ?: return@synchronized fail("STALE_ELEMENT")
        val path = element.path ?: return@synchronized fail("STALE_ELEMENT")

        // CharBuffer is a mutable CharSequence view over the lease array. Android
        // ACTION_SET_TEXT accepts CharSequence, so this path avoids an immutable plaintext String.
        val leasedValue = java.nio.CharBuffer.wrap(secret)
        val plan = PhoneTypeEngine.ExecutePlan(
            elementId = element.elementId,
            rawNodeId = rawNodeId,
            path = path,
            needsFocus = !element.focused,
            valueLength = secret.size,
            valueDigest = PhoneTypeEngine.digest(leasedValue),
        )
        val live = try {
            leasedValue.position(0)
            if (foreground) {
                com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough.withHostPassthrough {
                    service.typeEditable(plan, leasedValue, redactObservedText = true)
                }
            } else {
                val session = com.cyclone.mobile.runtime.background.WorkspaceRuntime.requireScope(scope)
                val targetPackage = session.targetPackage?.takeIf { it.isNotBlank() }
                    ?: return@synchronized fail("TARGET_PACKAGE_MISSING")
                service.typeEditableOnDisplay(plan, leasedValue, scope.displayId, targetPackage)
            }
        } catch (_: Exception) {
            return@synchronized fail("FILL_EXCEPTION")
        } finally {
            GatewayObservationStore.clear(scope.sessionId)
        }
        com.cyclone.mobile.secrets.SecretFillExecution(
            performed = live.setTextPerformed,
            verified = live.ok && live.afterStateVerified,
            errorCode = live.error?.code?.name,
        )
    }

    private fun executeScoped(context: Context, request: PhoneToolRequest, plane: SessionPlane? = null): PhoneToolResult {
        // Validate before cache lookup AND before observing the human display.
        val resolved = try { plane ?: SessionContract.classify(request.params) }
        catch (error: IllegalArgumentException) { return scopeFailure(request, error) }
        validateHumanize(request, resolved)?.let { return it }
        val scope = com.cyclone.mobile.runtime.session.ExecutionContext(resolved.sessionId, resolved.displayId)
        if (scope.sessionId != "default-foreground") {
            return synchronized(mutationLock) { withPlane(executeWorkspace(context, request, scope), resolved) }
        }
        if (scope.displayId != 0) return scopeFailure(request, IllegalArgumentException("Display/session mismatch"))
        cached(cacheKey(request))?.let { return withPlane(it, resolved) }
        val result = if (request.tool in mutatingTools) {
            synchronized(mutationLock) {
                cached(cacheKey(request)) ?: executeInternal(context, request, mutating = true)
            }
        } else {
            executeInternal(context, request, mutating = false)
        }
        return withPlane(result, resolved)
    }

    private fun validateHumanize(request: PhoneToolRequest, plane: SessionPlane): PhoneToolResult? {
        if (request.tool !in humanizeAwareTools || !request.params.has("humanize")) return null
        val raw = request.params.optString("humanize")
        return try {
            HumanizePreference.parse(raw)
            null
        } catch (error: IllegalArgumentException) {
            val now = System.currentTimeMillis()
            PhoneToolResult(
                commandId = request.commandId,
                tool = request.tool,
                ok = false,
                startedAtMs = now,
                finishedAtMs = now,
                payload = JSONObject().put(
                    "humanGesture",
                    JSONObject()
                        .put("requestedHumanize", raw)
                        .put("resolvedProfile", JSONObject.NULL)
                        .put("profileSource", "invalid_request")
                        .put("dispatchMode", "none")
                        .put("interactionMode", "none")
                        .put("correctedOrRejected", true)
                        .put("reason", error.message ?: "invalid humanize")
                        .put("sessionId", plane.sessionId)
                        .put("displayId", plane.displayId),
                ),
                error = PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, error.message ?: "invalid humanize"),
            )
        }
    }

    private fun withPlane(result: PhoneToolResult, plane: SessionPlane): PhoneToolResult {
        val payload = result.payload
        if (!result.ok || payload !is JSONObject) return result
        if (payload.optJSONObject("plane") != null) return result
        if (result.tool != "phone.observe" && !result.tool.startsWith("workspace.") && result.tool != "phone.workspace_switch") {
            return result
        }
        return result.copy(payload = SessionContract.attach(payload, plane))
    }

    private fun scopeErrorCode(error: Exception): PhoneToolErrorCode = PhoneToolScopeErrors.code(error)

    private fun scopeFailure(request: PhoneToolRequest, error: Exception): PhoneToolResult {
        val now = System.currentTimeMillis()
        return PhoneToolResult(
            request.commandId, request.tool, false, now, now,
            error = PhoneToolError(scopeErrorCode(error), PhoneToolScopeErrors.message(error)),
        )
    }

    private fun executeWorkspace(context: Context, request: PhoneToolRequest,
        scope: com.cyclone.mobile.runtime.session.ExecutionContext): PhoneToolResult {
        val started = System.currentTimeMillis()
        return try {
            val runtime = com.cyclone.mobile.runtime.background.WorkspaceRuntime
            val session = runtime.requireScope(scope)
            val p = request.params
            val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
            if (request.tool !in mutatingTools) {
                val payload: Any = when (request.tool) {
                    "phone.observe" -> runtime.observe(scope).toJson().put("sessionId", scope.sessionId).put("displayId", scope.displayId)
                    "phone.get_current_app" -> JSONObject().put("package", session.targetPackage).put("sessionId", scope.sessionId).put("displayId", scope.displayId)
                    "phone.screenshot" -> {
                        val artifact = com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.capture(service.cacheDir, sessionId = scope.sessionId,
                                minCapturedAtMonotonicMs = p.optLong("minCapturedAtMonotonicMs", -1).takeIf { it >= 0 })
                            ?: error("FRAME_STREAM_STALLED: no fresh frame from the requested display")
                        artifact.toJson().apply {
                            if (p.optBoolean("includeBase64")) put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
                        }
                    }
                    "phone.find" -> JSONArray(SelectorEngine.resolve(runtime.observe(scope), requireSelector(p), 20).map { it.toJson() })
                    else -> error("UNSUPPORTED: this workspace capability is unavailable")
                }
                return PhoneToolResult(request.commandId, request.tool, true, started, System.currentTimeMillis(), payload = payload)
            }
            val observation = GatewayObservationStore.current(scope.sessionId) ?: error("STALE_OBSERVATION")
            check(p.optString("observationId") == observation.id) { "STALE_OBSERVATION: observe this workspace again" }
            val generation = p.optLong("executionGeneration", -1)
            check(generation == observation.payload.optLong("executionGeneration", -2)) { "STALE_SESSION" }
            val snapshot = runtime.observe(scope)
            check(snapshot.fingerprint == observation.payload.optString("accessibilityFingerprint")) { "STALE_OBSERVATION" }
            val policy = com.cyclone.mobile.ai.CycloneAiAccessPolicy.evaluate(
                com.cyclone.mobile.ai.CycloneAiAccessProfileStore.read(context), request.tool, p)
            if (!policy.allowed) {
                runtime.pause(scope.sessionId, com.cyclone.mobile.runtime.background.WorkspaceState.BACKGROUND_NEEDS_HANDOFF)
                error("POLICY_DENIED: ${policy.safeMessage}")
            }
            val selector = p.optJSONObject("selector")?.let(ElementSelector::fromJson)
            val chosen = selector?.let { SelectorEngine.resolve(snapshot, it, 1).firstOrNull()?.node }
            fun guardedPoint(): UiNodeSnapshot {
                check(selector == null || chosen != null) { "STALE_OBSERVATION: semantic target is no longer present" }
                val node = chosen ?: snapshot.nodes.filter {
                    val x = p.optDouble("x"); val y = p.optDouble("y")
                    x >= it.bounds.left && x < it.bounds.right && y >= it.bounds.top && y < it.bounds.bottom && it.clickable
                }.minByOrNull { it.bounds.width * it.bounds.height } ?: error("UNSUPPORTED: a grounded control is required")
                workspaceGate(scope, request.tool, com.cyclone.mobile.ui.overlay.ClickGateIntercept.labelsFor(node, node, selector),
                    node.id, snapshot.fingerprint)
                return node
            }
            val commands = com.cyclone.mobile.runtime.background.WorkspaceCommands
            val humanize = humanizePreference(p)
            val viewport = runtime.authorizeTouch(scope, generation)
            var typed: JSONObject? = null
            when (request.tool) {
                "phone.click", "phone.tap", "phone.long_press" -> {
                    val node = guardedPoint()
                    val x = node.bounds.centerX; val y = node.bounds.centerY
                    val landed = if (request.tool == "phone.long_press") {
                        HumanGestureDispatch.longPress(
                            service, x, y, 650L, humanize, RuntimeGestureKind.LONG_PRESS,
                            request.commandId, node.bounds, scope.displayId, viewport,
                        ) || shellGesture(request.commandId, scope, generation, floatArrayOf(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 650f))
                    } else {
                        HumanGestureDispatch.tap(
                            service, x, y, humanize,
                            if (request.tool == "phone.tap") RuntimeGestureKind.COORDINATE_TAP else RuntimeGestureKind.FALLBACK_TAP,
                            request.commandId, node.bounds, scope.displayId, viewport,
                        ) || shellGesture(request.commandId, scope, generation, floatArrayOf(x.toFloat(), y.toFloat()))
                    }
                    workspaceTouchFailure(request, scope, snapshot, started, landed)?.let { return it }
                }
                "phone.scroll" -> {
                    val node = chosen?.takeIf { it.scrollable } ?: snapshot.nodes.firstOrNull { it.scrollable }
                        ?: error("UNSUPPORTED: no scrollable control")
                    val x = node.bounds.centerX
                    val top = node.bounds.top + node.bounds.height * 0.25f
                    val bottom = node.bounds.top + node.bounds.height * 0.75f
                    val backwards = p.optString("direction") == "backward"
                    val y1 = if (backwards) top else bottom
                    val y2 = if (backwards) bottom else top
                    val landed = HumanGestureDispatch.swipe(
                        service, x, y1, x, y2,
                        350L, humanize, RuntimeGestureKind.SCROLL, request.commandId, scope.displayId, viewport,
                    ) || shellGesture(request.commandId, scope, generation, floatArrayOf(x.toFloat(), y1, x.toFloat(), y2, 350f))
                    workspaceTouchFailure(request, scope, snapshot, started, landed)?.let { return it }
                }
                "phone.swipe" -> {
                    val x1 = p.optDouble("x1").toFloat(); val y1 = p.optDouble("y1").toFloat()
                    val x2 = p.optDouble("x2").toFloat(); val y2 = p.optDouble("y2").toFloat()
                    check(snapshot.nodes.any {
                        it.scrollable && it.bounds.contains(x1.toInt(), y1.toInt()) && it.bounds.contains(x2.toInt(), y2.toInt())
                    }) { "UNSUPPORTED: workspace swipes require a scrollable target" }
                    // Plan 26 (A42-3): sideways swipes (carousels, tabs) are allowed; swipe-to-delete or slide-to-pay
                    // under the start point asks the owner first, like a tap on it would.
                    if (kotlin.math.abs(x2 - x1) > kotlin.math.abs(y2 - y1)) {
                        snapshot.nodes.filter { it.clickable && it.bounds.contains(x1.toInt(), y1.toInt()) }
                            .minByOrNull { it.bounds.width * it.bounds.height }?.let { under ->
                                workspaceGate(scope, request.tool, com.cyclone.mobile.ui.overlay.ClickGateIntercept.labelsFor(under, under, null),
                                    under.id, snapshot.fingerprint)
                            }
                    }
                    val duration = p.optLong("durationMs", 350)
                    val landed = HumanGestureDispatch.swipe(
                        service, x1, y1, x2, y2, duration,
                        humanize, RuntimeGestureKind.SWIPE, request.commandId, scope.displayId, viewport,
                    ) || shellGesture(request.commandId, scope, generation, floatArrayOf(x1, y1, x2, y2, duration.coerceIn(100L, 3000L).toFloat()))
                    workspaceTouchFailure(request, scope, snapshot, started, landed)?.let { return it }
                }
                "phone.back" -> runtime.input(scope, generation, commands.BACK)
                "phone.type", "phone.replace_text" -> {
                    // Plan 26 (A42-3): the same delivery as on the main screen (set-text, read-back, paste), on this
                    // display, for any ordinary field or the focused one. Secret fields stay with the Secrets Card.
                    val catalog = PhoneTypeEngine.catalog(observation.id, observation.elements.values.map {
                        PhoneTypeEngine.ObservationElementInput(it.id, it.source, it.role, it.evidence)
                    }, snapshot)
                    when (val decision = PhoneTypeEngine.decide(p, catalog)) {
                        is PhoneTypeEngine.Decision.Reject -> return PhoneToolResult(request.commandId, request.tool, false, started,
                            System.currentTimeMillis(), error = PhoneToolError(decision.deny.code, decision.deny.message))
                        is PhoneTypeEngine.Decision.Execute -> {
                            val live = service.typeEditableOnDisplay(decision.plan, PhoneTypeEngine.typedValue(p).orEmpty(), scope.displayId,
                                session.targetPackage.orEmpty(), redact = false)
                            if (!live.ok) return PhoneToolResult(request.commandId, request.tool, false, started, System.currentTimeMillis(),
                                error = live.error ?: PhoneToolError(PhoneToolErrorCode.ACTION_FAILED, "Type failed"))
                            typed = live.toPayload()
                        }
                    }
                }
                "phone.open_app" -> check(p.optString("package") == session.targetPackage) { "UNSUPPORTED: open another workspace for a different app" }
                // Plan 26 (A42-3): a link the background app itself handles opens on its background screen.
                "phone.launch_intent" -> runtime.view(scope.sessionId, p.optString("uri"))
                else -> error("UNSUPPORTED: this operation cannot safely target a workspace")
            }
            if (request.tool in humanizeAwareTools) {
                com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.mutationFinished(scope.sessionId)
            }
            GatewayObservationStore.clear(scope.sessionId)
            val settleGeneration = DeviceState.uiGeneration()
            val settle = FastPathLoop.settle(
                beforeFingerprint = snapshot.fingerprint,
                sleepMs = { ms -> DeviceState.awaitUiEventAfter(settleGeneration, ms) },
                observeFingerprint = { runtime.observe(scope).fingerprint },
            )
            val after = runtime.observe(scope)
            val payload = JSONObject()
                .put("performed", true)
                .put("verified", settle.verified)
                .put("screenChanged", settle.changed ?: JSONObject.NULL)
                .put("fastPath", settle.toJson())
                .put("sessionId", scope.sessionId)
                .put("displayId", scope.displayId)
            typed?.let { t -> t.keys().forEach { key -> if (!payload.has(key)) payload.put(key, t.get(key)) } }
            if (request.tool in humanizeAwareTools) {
                payload.put("humanGesture", workspaceGestureEvidence(request, scope, HumanGestureDispatch.consumeTrace(request.commandId)))
            }
            PhoneToolResult(request.commandId, request.tool, true, started, System.currentTimeMillis(),
                beforeFingerprint = snapshot.fingerprint,
                afterFingerprint = settle.afterFingerprint ?: after.fingerprint,
                payload = payload)
        } catch (error: Exception) {
            if (request.tool in mutatingTools) runCatching { GatewayObservationStore.clear(scope.sessionId) }
            PhoneToolResult(request.commandId, request.tool, false, started, System.currentTimeMillis(),
                // Plan 28: keep the reason code (never typed text) so the plane can tell a missed tap from a lost screen.
                error = PhoneToolError(scopeErrorCode(error), "Background screen: ${workspaceReason(error)}"))
        }
    }

    /**
     * Pay, send, delete and the other approval boundaries on a background screen. A Mind mission gets exactly the main
     * screen's approval: the overlay card, then a one-shot grant for this action on this control, and the background
     * screen stays as it is while the owner decides (plan 28: pausing it here left it paused after the approval, so
     * the approved tap could never run). Other background sessions keep their own confirmation and hand-off.
     */
    /**
     * Plan 28: when an Accessibility gesture does not land on the background display (some phones do not route them to
     * a private display), the same gesture goes once through the helper's display-bound input: a tap for two
     * coordinates, a swipe (or a long press, as a swipe that stays put) for five. The authority checks run again.
     * Only when Android never queued the gesture: a cancelled or timed-out one may have reached the app, and a second
     * tap is never sent after it.
     */
    private fun shellGesture(
        commandId: String?,
        scope: com.cyclone.mobile.runtime.session.ExecutionContext,
        generation: Long,
        coordinates: FloatArray,
    ): Boolean = runCatching {
        if (HumanGestureDispatch.peekTrace(commandId)?.reason != HumanGestureDispatch.REASON_NOT_QUEUED) return false
        val commands = com.cyclone.mobile.runtime.background.WorkspaceCommands
        com.cyclone.mobile.runtime.background.WorkspaceRuntime.input(scope, generation,
            if (coordinates.size == 2) commands.TAP else commands.SWIPE, coordinates)
            .getBoolean("performed")
    }.getOrDefault(false)

    /** The failure's reason for the model and the plane: its code and first words, one line, bounded. */
    internal fun workspaceReason(error: Exception): String =
        (error.message ?: error.javaClass.simpleName).lineSequence().first().trim().take(160).ifBlank { "ACTION_FAILED" }

    private fun workspaceGate(
        scope: com.cyclone.mobile.runtime.session.ExecutionContext,
        tool: String,
        labels: List<String>,
        nodeId: String,
        fingerprint: String,
    ) {
        val runtime = com.cyclone.mobile.runtime.background.WorkspaceRuntime
        val gate = com.cyclone.mobile.policy.GateClassifier.classify(tool, labels) ?: return
        if (runtime.consumeConfirmation(scope.sessionId, tool, nodeId, fingerprint, gate.jsonKey)) return
        if (com.cyclone.mobile.runtime.plane.MissionPlanes.ui.value?.backgroundSessionId == scope.sessionId) {
            val overlay = com.cyclone.mobile.ui.overlay.ClickGateIntercept.overlayClass(gate)
            if (com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.consumeGateApproval(overlay, tool, labels)) return
            com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.registerGateChallenge(overlay, tool, labels)
            com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.enterGate(overlay)
            error("POLICY_DENIED: GATE human review is required")
        }
        runtime.requestConfirmation(scope.sessionId, tool, nodeId, fingerprint, gate.jsonKey)
        runtime.pause(scope.sessionId, com.cyclone.mobile.runtime.background.WorkspaceState.BACKGROUND_NEEDS_HANDOFF)
        error("POLICY_DENIED: human review is required")
    }

    private fun workspaceTouchFailure(
        request: PhoneToolRequest,
        scope: com.cyclone.mobile.runtime.session.ExecutionContext,
        snapshot: com.cyclone.mobile.UiSnapshot,
        started: Long,
        landed: Boolean,
    ): PhoneToolResult? {
        if (landed) return null
        com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.mutationFinished(scope.sessionId)
        val trace = HumanGestureDispatch.consumeTrace(request.commandId)
        val timeout = trace?.reason == HumanGestureDispatch.REASON_TIMEOUT
        return PhoneToolResult(
            commandId = request.commandId,
            tool = request.tool,
            ok = false,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            beforeFingerprint = snapshot.fingerprint,
            payload = JSONObject().put("humanGesture", workspaceGestureEvidence(request, scope, trace)),
            error = PhoneToolError(
                if (timeout) PhoneToolErrorCode.TIMEOUT else PhoneToolErrorCode.ACTION_FAILED,
                when (trace?.reason) {
                    HumanGestureDispatch.REASON_TIMEOUT ->
                        "Human gesture was queued on the named display but did not complete. Re-observe; do not repeat this mutation."
                    HumanGestureDispatch.REASON_NOT_QUEUED ->
                        "Named display did not accept a Human Gesture. Re-observe before trying again."
                    else ->
                        "Human gesture did not complete on the named display. Re-observe; do not repeat this mutation."
                },
            ),
        )
    }

    private fun executeInternal(context: Context, request: PhoneToolRequest, mutating: Boolean): PhoneToolResult {
        val started = System.currentTimeMillis()
        val service = CycloneAccessibilityService.instance
        // Reuse the authoritative current gateway frame whenever possible. The old executor rebuilt
        // the Accessibility tree before every command, even phone.observe itself.
        val before = if (mutating) service?.observe(markFresh = false)?.fingerprint else null

        if (mutating && !foregroundInputAllowed()) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Human currently owns device input"))
        }
        if (mutating && DeviceState.requireFreshObservation && !humanDesktopControlActive()) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED, "Run phone.observe after returning control before issuing actions"))
        }
        val requestedObservation = request.params.optString("observationId").ifBlank {
            request.params.optString("currentObservationId") }
        if (mutating && requestedObservation.isNotBlank() &&
            com.cyclone.mobile.fastpath.MutationGrounding.requiredFor(request.tool)
        ) {
            val observation = GatewayObservationStore.current()
            if (!com.cyclone.mobile.fastpath.MutationGrounding.matches(requestedObservation, observation?.id,
                    observation?.payload?.optString("accessibilityFingerprint"), before,
                    "default-foreground", 0, observation?.execution?.sessionId, observation?.execution?.displayId)) {
                return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.STALE_ELEMENT,
                    "STALE_OBSERVATION: the screen or observation changed. Observe again before choosing another target."))
            }
        }
        if (mutating && isDuplicateAction(request)) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.DUPLICATE_ACTION, "Duplicate action suppressed"))
        }

        val outcome = runCatching { dispatch(context, request, service, before) }
            .getOrElse { err ->
                if (err is PhoneToolException) Outcome(error = err.error)
                else if (err is EmptySelectorException) Outcome(error = PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, err.message ?: "empty selector"))
                else if (err is GateBlockedException) Outcome(error = PhoneToolError(PhoneToolErrorCode.POLICY_DENIED, err.message ?: "GATE requires confirmation"))
                else errorResult(PhoneToolErrorCode.INTERNAL_ERROR, err.message ?: err.javaClass.simpleName)
            }
        if (mutating) com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.mutationFinished()
        val after = when {
            !mutating -> null
            outcome.afterFingerprint != null -> outcome.afterFingerprint
            else -> CycloneAccessibilityService.instance?.observe(markFresh = false)?.fingerprint
        }
        return finish(request, started, before, after, outcome.payload, outcome.error, outcome.attempts)
    }

    private fun currentFingerprint(service: CycloneAccessibilityService?): String? {
        val cached = GatewayObservationStore.current()
            ?.payload
            ?.optString("accessibilityFingerprint")
            ?.takeIf(String::isNotBlank)
        return cached ?: service?.observe(markFresh = false)?.fingerprint
    }

    private fun cacheKey(request: PhoneToolRequest): String = listOf(request.commandId, request.tool,
        request.params.optString("sessionId", "default-foreground"), request.params.optInt("displayId", 0),
        request.params.optString("workspaceId"), request.params.optLong("workspaceGeneration", -1),
        DeviceState.controllerEpoch()).joinToString("|")

    private fun cached(commandId: String): PhoneToolResult? = synchronized(resultCache) { resultCache[commandId] }

    private data class Outcome(
        val payload: Any? = null,
        val error: PhoneToolError? = null,
        val attempts: Int = 1,
        val afterFingerprint: String? = null,
    )

    private fun requireSelector(params: JSONObject): ElementSelector {
        val selector = ElementSelector.fromJson(params.optJSONObject("selector") ?: params)
        if (selector.isEmpty()) {
            throw PhoneToolException(PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, "empty selector"))
        }
        return selector
    }

    private fun humanizePreference(params: JSONObject): HumanizePreference =
        HumanizePreference.parse(params.optString("humanize").takeIf { params.has("humanize") })

    private fun dispatch(context: Context, request: PhoneToolRequest, service: CycloneAccessibilityService?, before: String?): Outcome {
        val p = request.params
        val humanize = if (request.tool in humanizeAwareTools) humanizePreference(p) else HumanizePreference.AUTO
        return when (request.tool) {
            "phone.observe" -> {
                val s = service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
                Outcome(s.observe(markFresh = true).toJson())
            }
            "phone.capabilities" -> Outcome(CapabilityRegistry.toJson(context))
            "phone.get_current_app" -> Outcome(JSONObject()
                .put("package", DeviceState.currentPackage ?: JSONObject.NULL)
                .put("class", DeviceState.currentClassName ?: JSONObject.NULL)
                .put("controller", DeviceState.controller.name.lowercase()))
            "phone.find" -> {
                val s = service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
                val selector = ElementSelector.fromJson(p.optJSONObject("selector") ?: p)
                val limit = p.optInt("limit", 20).coerceIn(1, 100)
                val matches = s.find(selector, limit)
                Outcome(JSONArray().also { array -> matches.forEach { array.put(it.toJson()) } })
            }
            "phone.screenshot" -> screenshot(service, p)
            "phone.click" -> actionWithConfirmation(service, request, before) {
                val selector = requireSelector(p)
                service?.click(selector, humanize, request.commandId) == true
            }
            "phone.long_press" -> {
                val outcome = actionWithConfirmation(service, request, before) {
                    val selector = requireSelector(p)
                    service?.longPress(selector, p.optLong("durationMs", 650L), humanize, request.commandId) == true
                }
                val trace = HumanGestureDispatch.consumeTrace(request.commandId)
                attachGestureEvidence(
                    outcome,
                    gestureEvidence(
                        request = request,
                        preference = humanize,
                        kind = RuntimeGestureKind.LONG_PRESS,
                        trace = trace,
                        dispatchMode = trace?.dispatchMode ?: if (outcome.error == null) "semantic_action" else "not_dispatched",
                        interactionMode = if (trace == null && outcome.error == null) "semantic" else "coordinate",
                    ),
                )
            }
            "phone.tap" -> {
                val outcome = actionWithConfirmation(service, request, before) {
                    service?.tap(
                        p.optDouble("x").toFloat(),
                        p.optDouble("y").toFloat(),
                        humanize,
                        request.commandId,
                    ) == true
                }
                val trace = HumanGestureDispatch.consumeTrace(request.commandId)
                attachGestureEvidence(
                    outcome,
                    gestureEvidence(request, humanize, RuntimeGestureKind.COORDINATE_TAP, trace,
                        trace?.dispatchMode ?: "not_dispatched", "coordinate"),
                )
            }
            "phone.type", "phone.replace_text" -> typeEditable(service, request)
            "phone.scroll" -> foregroundScroll(service, request, before, humanize)
            "phone.swipe" -> {
                val outcome = actionWithConfirmation(service, request, before) {
                    // Guarded swipes (Cyclone Mind) get the approval check of whatever sits under the start point:
                    // swipe-to-archive, swipe-to-delete and slide-to-pay are consequential.
                    if (p.optBoolean("guard")) service?.guardPoint("phone.swipe", p.optDouble("x1").toFloat(), p.optDouble("y1").toFloat())
                    service?.swipe(
                        p.optDouble("x1").toFloat(), p.optDouble("y1").toFloat(),
                        p.optDouble("x2").toFloat(), p.optDouble("y2").toFloat(),
                        p.optLong("durationMs", 350L),
                        humanize,
                        request.commandId,
                    ) == true
                }
                val trace = HumanGestureDispatch.consumeTrace(request.commandId)
                attachGestureEvidence(
                    outcome,
                    gestureEvidence(request, humanize, RuntimeGestureKind.SWIPE, trace,
                        trace?.dispatchMode ?: "not_dispatched", "coordinate"),
                )
            }
            "phone.back" -> actionWithConfirmation(service, request, before) {
                (service?.goBack() == true).also { if (it) TraceFieldSignals.navigated() }
            }
            "phone.home" -> actionWithConfirmation(service, request, before) {
                (service?.goHome() == true).also { if (it) TraceFieldSignals.navigated() }
            }
            "phone.open_app" -> {
                val requested = p.optString("package")
                if (requested.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "package is required")
                val resolved = com.cyclone.mobile.fastpath.FastPathLanding.launchCandidates(requested).firstNotNullOfOrNull { pkg ->
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let { pkg to it }
                } ?: return errorResult(PhoneToolErrorCode.APP_NOT_FOUND, "No launchable app for $requested")
                val packageName = resolved.first
                val intent = resolved.second
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName == "com.android.settings") {
                    intent.action = Intent.ACTION_MAIN
                    intent.addCategory(Intent.CATEGORY_LAUNCHER)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                // Mapping returns to the app's entry room instead of resuming a deep screen.
                if (p.optBoolean("clearTask", false)) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val eventGeneration = DeviceState.uiGeneration()
                context.startActivity(intent)
                TraceFieldSignals.navigated()
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("package", packageName).put("launched", true))
            }
            "phone.get_notifications" -> Outcome(notificationJson())
            // Plan 26 (A42-6, tier 0): reply through the message's own notification. No screen is touched, so it runs
            // while the owner uses the phone; it is a send, so the caller has the owner's approval for this exact text.
            "phone.reply_notification" -> replyNotification(context, p.optString("key").takeIf { it.isNotBlank() }, p.optString("text"))
            // Plan 29 (direct first): no screen at all: Android's calendar and contacts, and the clock's contract.
            "phone.direct_calendar_find" -> direct(com.cyclone.mobile.direct.DirectActions.calendarFind(context, p))
            "phone.direct_calendar_add" -> direct(com.cyclone.mobile.direct.DirectActions.calendarAdd(context, p))
            "phone.direct_contacts_find" -> direct(com.cyclone.mobile.direct.DirectActions.contactsFind(context, p))
            "phone.direct_alarm" -> direct(com.cyclone.mobile.direct.DirectActions.alarm(context, p))
            "phone.direct_timer" -> direct(com.cyclone.mobile.direct.DirectActions.timer(context, p))
            "phone.direct_flashlight" -> direct(com.cyclone.mobile.direct.DirectActions.flashlight(context, p))
            "phone.direct_volume" -> direct(com.cyclone.mobile.direct.DirectActions.volume(context, p))
            "phone.direct_media" -> direct(com.cyclone.mobile.direct.DirectActions.media(context, p))
            "phone.open_notification" -> {
                val generation = DeviceState.uiGeneration()
                val opened = openNotification(p.optString("key").takeIf { it.isNotBlank() })
                if (opened.error != null) opened else launchedOutcome(service, before, p, generation, JSONObject().put("opened", true))
            }
            "phone.get_clipboard" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                Outcome(JSONObject().put("text", text))
            }
            "phone.set_clipboard" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: return errorResult(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, "Clipboard service unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("Cyclone", p.optString("text")))
                Outcome(JSONObject().put("updated", true))
            }
            "phone.share" -> {
                val text = p.optString("text")
                if (text.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "text is required")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = p.optString("mimeType", "text/plain")
                    putExtra(Intent.EXTRA_TEXT, text)
                    p.optString("package").takeIf { it.isNotBlank() }?.let(::setPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val generation = DeviceState.uiGeneration()
                context.startActivity(intent)
                launchedOutcome(service, before, p, generation, JSONObject().put("started", true))
            }
            "phone.launch_intent" -> {
                // Plan 42 (Instant): the camera, ready to take a photo (the front one for a selfie). Nothing is captured
                // here; the shutter is a separate, visible tap.
                if (p.optString("action") == "camera") {
                    val front = p.optBoolean("front", false)
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (front) {
                        intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    }
                    val eventGeneration = DeviceState.uiGeneration()
                    try {
                        context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        return errorResult(PhoneToolErrorCode.ACTION_FAILED, "No camera app on this phone")
                    }
                    return launchedOutcome(service, before, p, eventGeneration, JSONObject().put("camera", if (front) "front" else "back").put("started", true))
                }
                val uri = p.optString("uri")
                if (uri.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "uri is required")
                val parsed = Uri.parse(uri)
                if (parsed.scheme !in setOf("http", "https", "geo", "mailto", "tel", "sms", "market")) {
                    return errorResult(PhoneToolErrorCode.SECURITY_RESTRICTION, "URI scheme is not allowed")
                }
                val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                p.optString("package").takeIf { it.isNotBlank() }?.let(intent::setPackage)
                val eventGeneration = DeviceState.uiGeneration()
                context.startActivity(intent)
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("uri", uri).put("started", true))
            }
            "phone.set_alarm", "phone.set_timer" -> {
                // Android's own AlarmClock contract: the clock app creates the alarm/timer and shows it (never
                // SKIP_UI), so the owner sees what was set and the agent proves it on the live Clock screen.
                val intent = if (request.tool == "phone.set_alarm") {
                    val hour = p.optInt("hour", -1)
                    val minute = p.optInt("minute", -1)
                    if (hour !in 0..23 || minute !in 0..59) {
                        return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "hour 0-23 and minute 0-59 are required")
                    }
                    Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                        .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                } else {
                    val seconds = p.optInt("seconds", -1)
                    if (seconds !in 1..86_400) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "seconds 1-86400 is required")
                    Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                        .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                }
                intent.putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                p.optString("label").trim().take(60).takeIf { it.isNotBlank() }
                    ?.let { intent.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
                val eventGeneration = DeviceState.uiGeneration()
                try {
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    return errorResult(PhoneToolErrorCode.APP_NOT_FOUND, "No clock app on this phone accepts this request")
                } catch (_: SecurityException) {
                    return errorResult(PhoneToolErrorCode.SECURITY_RESTRICTION, "The clock app refused the request")
                }
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("started", true).put("tool", request.tool))
            }
            "phone.open_settings" -> {
                // Navigation only: an allowlisted Settings page. Any change on it is its own observed action.
                val key = p.optString("page", "main").trim().lowercase()
                val page = PhoneSettingsPages.page(key) ?: return errorResult(PhoneToolErrorCode.INVALID_REQUEST,
                    "Unknown settings page. Allowed: ${PhoneSettingsPages.pages.keys.joinToString()}")
                val app = p.optString("app").trim()
                val intent = Intent(page.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (page.needsPackage) {
                    if (!PhoneSettingsPages.validPackage(app)) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "app (a package name) is required for $key")
                    if (key == "app_details") intent.data = Uri.fromParts("package", app, null)
                    else intent.putExtra("android.provider.extra.APP_PACKAGE", app)
                }
                val eventGeneration = DeviceState.uiGeneration()
                try {
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    return errorResult(PhoneToolErrorCode.APP_NOT_FOUND, "This phone has no $key settings page")
                } catch (_: SecurityException) {
                    return errorResult(PhoneToolErrorCode.SECURITY_RESTRICTION, "Android refused to open the $key settings page")
                }
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("page", key).put("started", true))
            }
            "phone.tap_point" -> actionWithConfirmation(service, request, before) {
                if (!p.has("x") || !p.has("y")) throw PhoneToolException(PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, "x and y are required"))
                service?.tapPoint(p.optDouble("x").toFloat(), p.optDouble("y").toFloat(), humanize, request.commandId) == true
            }
            "phone.submit_text" -> actionWithConfirmation(service, request, before) {
                // The keyboard's action key (Enter / Search / Go) on the grounded editable field.
                val selector = requireSelector(p)
                service?.imeEnter(selector) == true
            }
            "phone.wait_for" -> waitFor(service, p, assertOnly = false)
            "phone.assert" -> waitFor(service, p, assertOnly = true)
            else -> errorResult(PhoneToolErrorCode.UNKNOWN_TOOL, "Unknown tool ${request.tool}")
        }
    }

    private fun foregroundScroll(
        service: CycloneAccessibilityService?,
        request: PhoneToolRequest,
        before: String?,
        humanize: HumanizePreference,
    ): Outcome {
        val p = request.params
        var semanticSucceeded = false
        var fallbackAttempted = false
        var fallbackUnavailableReason: String? = null
        val outcome = actionWithConfirmation(service, request, before) {
            val s = service ?: return@actionWithConfirmation false
            val selector = p.optJSONObject("selector")?.let(ElementSelector::fromJson)
            val forward = p.optString("direction", "forward") != "backward"
            if (s.scroll(selector, forward)) {
                semanticSucceeded = true
                return@actionWithConfirmation true
            }
            val snapshot = s.observe(markFresh = false)
            val selected = selector?.let { SelectorEngine.resolve(snapshot, it, 1).firstOrNull()?.node }
            val node = selected?.takeIf { it.scrollable } ?: snapshot.nodes.firstOrNull { it.scrollable }
            if (node == null) {
                fallbackUnavailableReason = "no grounded scrollable control for coordinate fallback"
                return@actionWithConfirmation false
            }
            if (node.bounds.width < 8 || node.bounds.height < 96) {
                fallbackUnavailableReason = "scrollable bounds are too small for a safe bounded fallback"
                return@actionWithConfirmation false
            }
            fallbackAttempted = true
            val x = node.bounds.centerX
            val top = node.bounds.top + node.bounds.height * 0.25f
            val bottom = node.bounds.top + node.bounds.height * 0.75f
            s.swipe(
                x,
                if (forward) bottom else top,
                x,
                if (forward) top else bottom,
                350L,
                humanize,
                request.commandId,
            )
        }
        val trace = HumanGestureDispatch.consumeTrace(request.commandId)
        val dispatchMode = when {
            semanticSucceeded -> "semantic_action"
            trace != null -> trace.dispatchMode
            fallbackAttempted -> "coordinate_fallback_rejected"
            else -> "fallback_unavailable"
        }
        val evidence = gestureEvidence(
            request = request,
            preference = humanize,
            kind = RuntimeGestureKind.SCROLL,
            trace = trace,
            dispatchMode = dispatchMode,
            interactionMode = if (semanticSucceeded) "semantic" else "coordinate",
            correctedOrRejected = fallbackUnavailableReason != null || (fallbackAttempted && outcome.error != null),
            reason = fallbackUnavailableReason ?: trace?.reason,
        )
        return attachGestureEvidence(outcome, evidence)
    }

    private fun workspaceGestureEvidence(
        request: PhoneToolRequest,
        scope: com.cyclone.mobile.runtime.session.ExecutionContext,
        trace: HumanGestureDispatchTrace?,
    ): JSONObject {
        val preference = humanizePreference(request.params)
        val kind = gestureKind(request.tool)
        return gestureEvidence(
            request = request,
            preference = preference,
            kind = kind,
            trace = trace,
            dispatchMode = trace?.dispatchMode ?: "not_dispatched",
            interactionMode = "coordinate",
            correctedOrRejected = trace?.accepted != true,
            reason = trace?.reason,
        )
            .put("sessionId", scope.sessionId)
            .put("displayId", scope.displayId)
            .put("completed", trace?.accepted == true)
    }

    private fun gestureKind(tool: String): RuntimeGestureKind = when (tool) {
        "phone.tap" -> RuntimeGestureKind.COORDINATE_TAP
        "phone.long_press" -> RuntimeGestureKind.LONG_PRESS
        "phone.scroll" -> RuntimeGestureKind.SCROLL
        "phone.swipe" -> RuntimeGestureKind.SWIPE
        else -> RuntimeGestureKind.PRECISION
    }

    private fun gestureEvidence(
        request: PhoneToolRequest,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        trace: HumanGestureDispatchTrace?,
        dispatchMode: String,
        interactionMode: String,
        correctedOrRejected: Boolean = trace?.accepted == false,
        reason: String? = trace?.reason,
    ): JSONObject {
        val resolved = HumanGestureRuntimePolicy.resolve(preference, kind)
        return baseGestureEvidence(request, preference, resolved)
            .put("appliedProfile", trace?.profile?.name?.lowercase() ?: if (interactionMode == "semantic") "none" else JSONObject.NULL)
            .put("dispatchMode", dispatchMode)
            .put("interactionMode", interactionMode)
            .put("correctedOrRejected", correctedOrRejected)
            .put("completed", trace?.accepted == true && !correctedOrRejected)
            .put("reason", reason ?: JSONObject.NULL)
            .put("durationMs", trace?.durationMs ?: JSONObject.NULL)
            .put("sessionId", "default-foreground")
            .put("displayId", 0)
    }

    private fun baseGestureEvidence(
        request: PhoneToolRequest,
        preference: HumanizePreference,
        resolved: HumanizeProfile,
    ): JSONObject {
        val explicit = request.params.has("humanize")
        return JSONObject()
            .put("requestedHumanize", if (explicit) request.params.optString("humanize").lowercase() else "auto")
            .put("resolvedProfile", resolved.name.lowercase())
            .put(
                "profileSource",
                when {
                    !explicit -> "default_auto"
                    preference == HumanizePreference.AUTO -> "explicit_auto_policy"
                    else -> "explicit"
                },
            )
    }

    private fun attachGestureEvidence(outcome: Outcome, evidence: JSONObject): Outcome {
        val payload = (outcome.payload as? JSONObject) ?: JSONObject()
        payload.put("humanGesture", evidence)
        return outcome.copy(payload = payload)
    }

    private fun typeEditable(service: CycloneAccessibilityService?, request: PhoneToolRequest): Outcome {
        if (service == null) {
            return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        }
        val epoch = DeviceState.controllerEpoch()
        if (!foregroundInputAllowed() || epoch != DeviceState.controllerEpoch()) {
            return errorResult(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Controller changed while action was queued")
        }
        val snapshot = service.observe(markFresh = false)
        val observation = GatewayObservationStore.current()
        val catalog = PhoneTypeEngine.catalog(
            observationId = observation?.id,
            evidenceElements = observation?.elements?.values?.map { element ->
                PhoneTypeEngine.ObservationElementInput(element.id, element.source, element.role, element.evidence)
            } ?: emptyList(),
            snapshot = snapshot,
        )
        return when (val decision = PhoneTypeEngine.decide(request.params, catalog)) {
            is PhoneTypeEngine.Decision.Reject -> errorResult(decision.deny.code, decision.deny.message)
            is PhoneTypeEngine.Decision.Execute -> {
                val value = PhoneTypeEngine.typedValue(request.params).orEmpty()
                val live = service.typeEditable(decision.plan, value)
                if (!live.ok) {
                    Outcome(error = live.error ?: PhoneToolError(PhoneToolErrorCode.ACTION_FAILED, "Type failed"))
                } else {
                    Outcome(payload = live.toPayload())
                }
            }
        }
    }

    private fun actionWithConfirmation(
        service: CycloneAccessibilityService?,
        request: PhoneToolRequest,
        before: String?,
        action: () -> Boolean,
    ): Outcome {
        if (service == null) return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val epoch = DeviceState.controllerEpoch()
        // Retries apply only when Android rejected the action. Unchanged UI after a performed
        // click must never dispatch a second click channel (Fast Path / ClosePaw soft-success).
        val retries = request.params.optInt("retries", 1).coerceIn(0, 3)
        var attempts = 0
        repeat(retries + 1) {
            attempts++
            if (!foregroundInputAllowed() || epoch != DeviceState.controllerEpoch()) {
                return errorResult(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Controller changed while action was queued", attempts)
            }
            val eventGeneration = DeviceState.uiGeneration()
            if (action()) {
                val (afterSnapshot, settle) = settleAfterMutation(service, before, request.params, eventGeneration)
                val expected = request.params.optJSONObject("expect")
                if (expected != null && afterSnapshot == null) return Outcome(error = PhoneToolError(PhoneToolErrorCode.ASSERTION_FAILED, "Fresh after-state unavailable"), attempts = attempts)
                if (expected != null && afterSnapshot != null) {
                    val verification = evaluateCondition(afterSnapshot, expected)
                    if (!verification.first) {
                        return Outcome(
                            error = PhoneToolError(PhoneToolErrorCode.ASSERTION_FAILED, verification.second),
                            attempts = attempts,
                            afterFingerprint = afterSnapshot.fingerprint,
                        )
                    }
                }
                val payload = JSONObject()
                    .put("performed", true)
                    .put("screenChanged", settle.changed ?: JSONObject.NULL)
                    .put("verified", com.cyclone.mobile.fastpath.MutationGrounding.verifiedTransition(true, settle.changed, expected != null))
                    .put("expectationVerified", expected != null)
                    .put("postconditionVerified", expected != null)
                    .put("fastPath", settle.toJson())
                settle.warning?.let { payload.put("warning", it) }
                return Outcome(
                    payload = payload,
                    attempts = attempts,
                    afterFingerprint = settle.afterFingerprint ?: afterSnapshot?.fingerprint,
                )
            }
            val gesture = HumanGestureDispatch.peekTrace(request.commandId)
            if (HumanGestureDispatch.incomplete(gesture)) {
                val reason = gesture?.reason
                val timeout = reason == HumanGestureDispatch.REASON_TIMEOUT
                return Outcome(
                    error = PhoneToolError(
                        if (timeout) PhoneToolErrorCode.TIMEOUT else PhoneToolErrorCode.ACTION_FAILED,
                        when (reason) {
                            HumanGestureDispatch.REASON_TIMEOUT ->
                                "Human gesture was queued but did not complete. Re-observe; do not repeat this mutation."
                            HumanGestureDispatch.REASON_CANCELLED ->
                                "Human gesture was cancelled. Re-observe; do not repeat this mutation."
                            HumanGestureDispatch.REASON_NOT_QUEUED ->
                                "Human gesture was not accepted by Android. Re-observe before trying again."
                            else ->
                                "Human gesture did not complete. Re-observe; do not repeat this mutation."
                        },
                    ),
                    attempts = attempts,
                )
            }
            if (attempts <= retries) DeviceState.awaitUiEventAfter(eventGeneration, 100L * attempts)
        }
        return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Android rejected or could not perform the action", attempts)
    }

    private fun launchedOutcome(
        service: CycloneAccessibilityService?,
        before: String?,
        params: JSONObject,
        eventGeneration: Long,
        base: JSONObject,
    ): Outcome {
        if (service == null) return Outcome(base.put("verified", false).put("performed", true))
        val (after, settle) = settleAfterMutation(service, before, params, eventGeneration)
        val expectedPackage = params.optString("package")
        val packageVerified = expectedPackage.isNotBlank() && after?.packageName == expectedPackage
        base.put("performed", true)
            .put("verified", if (expectedPackage.isNotBlank()) packageVerified else settle.verified)
            .put("postconditionVerified", packageVerified)
            .put("screenChanged", settle.changed ?: JSONObject.NULL)
            .put("fastPath", settle.toJson())
        settle.warning?.let { base.put("warning", it) }
        return Outcome(payload = base, afterFingerprint = settle.afterFingerprint)
    }

    /**
     * Default Fast Path: settle 300ms + fingerprint, then +500/+1000 if Unchanged.
     * Explicit waitForChangeMs (including 0 for desktop live controls) keeps the legacy single wait.
     * The settle ladder only re-observes; it never re-clicks.
     */
    private fun settleAfterMutation(
        service: CycloneAccessibilityService,
        before: String?,
        params: JSONObject,
        eventGeneration: Long,
    ): Pair<UiSnapshot?, FastPathSettleResult> {
        if (params.has("waitForChangeMs") && !params.optBoolean("fastPath", false)) {
            val waitForChangeMs = params.optLong("waitForChangeMs", 900L).coerceIn(0L, 5_000L)
            if (waitForChangeMs > 0L) DeviceState.awaitUiEventAfter(eventGeneration, waitForChangeMs)
            val afterSnapshot = if (before != null || params.optJSONObject("expect") != null) {
                service.observe(markFresh = false)
            } else null
            val changed = if (before == null || afterSnapshot == null) null else afterSnapshot.fingerprint != before
            return afterSnapshot to FastPathSettleResult(
                changed = changed,
                verified = changed == true,
                observations = if (afterSnapshot == null) 0 else 1,
                elapsedMs = waitForChangeMs,
                warning = if (changed == false) FastPathTimings.UNCHANGED_WARNING else null,
                afterFingerprint = afterSnapshot?.fingerprint,
            )
        }
        var lastSnapshot: UiSnapshot? = null
        val settle = FastPathLoop.settle(
            beforeFingerprint = before,
            sleepMs = { ms -> DeviceState.awaitUiEventAfter(eventGeneration, ms) },
            observeFingerprint = {
                lastSnapshot = service.observe(markFresh = false)
                lastSnapshot?.fingerprint
            },
        )
        return lastSnapshot to settle
    }

    private fun screenshot(service: CycloneAccessibilityService?, params: JSONObject): Outcome {
        service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val crop = params.optJSONObject("crop")?.let {
            UiBounds(it.optInt("left"), it.optInt("top"), it.optInt("right"), it.optInt("bottom"))
        }
        com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.capture(service.cacheDir, crop,
            minCapturedAtMonotonicMs = params.optLong("minCapturedAtMonotonicMs", -1).takeIf { it >= 0 })?.let { artifact ->
            return Outcome(artifact.toJson().apply {
                if (params.optBoolean("includeBase64", false)) {
                    put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
                }
            })
        }
        val latch = CountDownLatch(1)
        var captured: Result<CycloneAccessibilityService.ScreenshotArtifact>? = null
        service.takeScreenshot(crop) { result -> captured = result; latch.countDown() }
        if (!latch.await(8, TimeUnit.SECONDS)) return errorResult(PhoneToolErrorCode.TIMEOUT, "Screenshot timed out")
        val artifact = captured?.getOrElse { return errorResult(PhoneToolErrorCode.ACTION_FAILED, it.message ?: "Screenshot failed") }
            ?: return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Screenshot did not return a result")
        val json = artifact.toJson()
        if (params.optBoolean("includeBase64", false)) {
            json.put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
        }
        return Outcome(json)
    }

    private fun waitFor(service: CycloneAccessibilityService?, params: JSONObject, assertOnly: Boolean): Outcome {
        service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val condition = params.optJSONObject("condition") ?: params
        val timeout = if (assertOnly) 0L else params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(0L, MAX_TIMEOUT_MS)
        val heartbeat = params.optLong("pollMs", 250L).coerceIn(50L, 1_000L)
        val started = System.currentTimeMillis()
        var attempts = 0
        do {
            val generation = DeviceState.uiGeneration()
            attempts++
            val snapshot = service.observe(markFresh = false)
            val result = evaluateCondition(snapshot, condition)
            if (result.first) return Outcome(JSONObject().put("matched", true).put("snapshot", snapshot.toJson()), attempts = attempts)
            val elapsed = System.currentTimeMillis() - started
            if (assertOnly || elapsed >= timeout) {
                return errorResult(if (assertOnly) PhoneToolErrorCode.ASSERTION_FAILED else PhoneToolErrorCode.TIMEOUT, result.second, attempts)
            }
            DeviceState.awaitUiEventAfter(generation, minOf(heartbeat, timeout - elapsed))
        } while (true)
    }

    private fun evaluateCondition(snapshot: UiSnapshot, condition: JSONObject): Pair<Boolean, String> {
        return when (condition.optString("type", "selector_exists")) {
            "selector_exists" -> {
                val matches = SelectorEngine.resolve(snapshot, ElementSelector.fromJson(condition.optJSONObject("selector") ?: condition), 1)
                (matches.isNotEmpty()) to "Expected selector was not found"
            }
            "selector_absent" -> {
                val matches = SelectorEngine.resolve(snapshot, ElementSelector.fromJson(condition.optJSONObject("selector") ?: condition), 1)
                matches.isEmpty() to "Selector is still present"
            }
            "package_equals" -> (snapshot.packageName == condition.optString("package")) to "Current package ${snapshot.packageName} does not match ${condition.optString("package")}" 
            "text_contains" -> {
                val q = condition.optString("text").lowercase()
                snapshot.nodes.any { it.text.lowercase().contains(q) || it.contentDescription.lowercase().contains(q) } to "Text '$q' not found"
            }
            "fingerprint_changed" -> (snapshot.fingerprint != condition.optString("from")) to "Screen fingerprint has not changed"
            else -> false to "Unknown condition type ${condition.optString("type")}" 
        }
    }

    private fun notificationJson(): JSONArray = JSONArray().also { array ->
        DeviceState.notificationSnapshot().forEach { sbn ->
            val extras = sbn.notification.extras
            array.put(JSONObject()
                .put("key", sbn.key)
                .put("package", sbn.packageName)
                .put("postTime", sbn.postTime)
                .put("title", extras.getCharSequence("android.title")?.toString().orEmpty())
                .put("text", extras.getCharSequence("android.text")?.toString().orEmpty())
                .put("hasContentIntent", sbn.notification.contentIntent != null)
                .put("actions", JSONArray().also { actions ->
                    sbn.notification.actions.orEmpty().forEach { action -> actions.put(action.title?.toString().orEmpty()) }
                }))
        }
    }

    private fun openNotification(key: String?): Outcome {
        val sbn = DeviceState.notification(key) ?: return errorResult(PhoneToolErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found")
        val intent = sbn.notification.contentIntent ?: return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Notification has no content intent")
        return try {
            intent.send()
            Outcome(JSONObject().put("key", sbn.key).put("opened", true))
        } catch (e: PendingIntent.CanceledException) {
            errorResult(PhoneToolErrorCode.ACTION_FAILED, "Notification intent is no longer valid")
        }
    }

    private fun replyNotification(context: Context, key: String?, text: String): Outcome {
        if (text.isBlank() || text.length > 2_000) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "Reply text must be 1-2000 characters")
        if (com.cyclone.mobile.mind.PhoneMindToolbox.sensitive(text)) {
            return errorResult(PhoneToolErrorCode.POLICY_DENIED, "Replies never carry passwords, codes or card numbers")
        }
        val sbn = DeviceState.notification(key) ?: return errorResult(PhoneToolErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found")
        val action = sbn.notification.actions.orEmpty().firstOrNull { action ->
            action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
        } ?: return errorResult(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, "This notification has no reply action")
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        val fill = Intent()
        android.app.RemoteInput.addResultsToIntent(action.remoteInputs, fill, android.os.Bundle().apply { putCharSequence(input.resultKey, text) })
        return try {
            action.actionIntent.send(context, 0, fill)
            Outcome(JSONObject().put("key", sbn.key).put("replied", true).put("package", sbn.packageName).put("charCount", text.length))
        } catch (e: PendingIntent.CanceledException) {
            errorResult(PhoneToolErrorCode.ACTION_FAILED, "The reply action is no longer valid")
        }
    }

    private fun direct(result: com.cyclone.mobile.direct.DirectResult): Outcome = if (result.ok) Outcome(result.payload ?: JSONObject())
        else errorResult(when (result.code) {
            com.cyclone.mobile.direct.DirectResult.PERMISSION -> PhoneToolErrorCode.SECURITY_RESTRICTION
            com.cyclone.mobile.direct.DirectResult.INVALID -> PhoneToolErrorCode.INVALID_REQUEST
            com.cyclone.mobile.direct.DirectResult.UNAVAILABLE -> PhoneToolErrorCode.CAPABILITY_UNAVAILABLE
            else -> PhoneToolErrorCode.ACTION_FAILED
        }, "${result.code}: ${result.message}")

    private fun errorResult(code: PhoneToolErrorCode, message: String, attempts: Int = 1): Outcome =
        Outcome(error = PhoneToolError(code, message), attempts = attempts)

    @Synchronized
    private fun isDuplicateAction(request: PhoneToolRequest): Boolean {
        val now = System.currentTimeMillis()
        val signature = if (request.tool == "phone.type" || request.tool == "phone.replace_text") {
            PhoneTypeEngine.duplicateSignature(request.tool, request.params)
        } else {
            val paramsForIdentity = JSONObject(request.params.toString()).apply { remove("humanize") }
            "${request.tool}|$paramsForIdentity"
        }
        val previous = recentActions[signature]
        recentActions[signature] = now
        recentActions.entries.removeIf { now - it.value > 5_000L }
        return previous != null && now - previous < DUPLICATE_WINDOW_MS
    }

    @Synchronized
    private fun finish(
        request: PhoneToolRequest,
        started: Long,
        before: String?,
        after: String?,
        payload: Any? = null,
        error: PhoneToolError? = null,
        attempts: Int = 1,
    ): PhoneToolResult {
        val result = PhoneToolResult(
            commandId = request.commandId,
            tool = request.tool,
            ok = error == null,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            attempts = attempts,
            beforeFingerprint = before,
            afterFingerprint = after,
            payload = payload,
            error = error,
        )
        synchronized(resultCache) { resultCache[cacheKey(request)] = result }
        DeviceState.addAudit(DeviceState.CommandAuditRecord(
            commandId = request.commandId,
            tool = request.tool,
            startedAtMs = result.startedAtMs,
            finishedAtMs = result.finishedAtMs,
            ok = result.ok,
            beforeFingerprint = before,
            afterFingerprint = after,
            errorCode = error?.code?.name,
        ))
        if (error?.code == PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED) {
            SetupReminderState.request(
                SetupNeed.PHONE_CONTROL,
                "Enable Cyclone phone control before using screen-reading or UI-action features.",
            )
        }
        DeviceState.addLog("${request.tool} ${if (result.ok) "OK" else "FAIL:${error?.code}"}")
        return result
    }
}

private class PhoneToolException(val error: PhoneToolError) : RuntimeException(error.message)

/** Visual-only: tells the user's Trace Field a new screen is coming. Never affects the action result. */
private object TraceFieldSignals {
    fun navigated() {
        runCatching { com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.navigated() }
    }
}
