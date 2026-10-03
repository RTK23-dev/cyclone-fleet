package com.cyclone.mobile.gateway

import android.content.Context
import android.os.Build
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.skill.SkillCompiler
import com.cyclone.mobile.automation.skill.SkillDraftSink
import com.cyclone.mobile.debug.PageDebugSandboxV293
import com.cyclone.mobile.infrastructure.v31.CycloneV31Runtime
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.policy.GatePolicy
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GatewayRuntime {
    @Volatile private var appContext: Context? = null
    @Volatile private var server: GatewaySocketServer? = null
    @Volatile private var listenerError: String? = null
    @Volatile private var lastSafeError: String? = null
    @Volatile private var lastSafeErrorCode: String? = null
    @Volatile private var lastSafeErrorAtMs: Long? = null

    internal object PcSessionTracker {
        const val RECENT_WINDOW_MS = 45_000L
        @Volatile private var lastAuthenticatedAtMs: Long? = null

        fun noteAuthenticated(nowMs: Long = System.currentTimeMillis()) {
            lastAuthenticatedAtMs = nowMs
        }

        fun reset() {
            lastAuthenticatedAtMs = null
        }

        fun lastAuthenticatedAt(): Long? = lastAuthenticatedAtMs

        fun isRecent(nowMs: Long = System.currentTimeMillis()): Boolean {
            val last = lastAuthenticatedAtMs ?: return false
            val age = nowMs - last
            return age in 0..RECENT_WINDOW_MS
        }
    }

    @Synchronized
    fun startPairingBootstrap(context: Context) {
        appContext = context.applicationContext
        startLocked(context.applicationContext)
    }

    @Synchronized
    fun startIfEnabled(context: Context) {
        startPairingBootstrap(context)
    }

    /** V3.3 enablement creates no reusable token; trust/session credentials are protocol-owned. */
    @Synchronized
    fun enable(context: Context): String {
        appContext = context.applicationContext
        GatewaySessionStore.enableTrusted(context)
        startLocked(context.applicationContext)
        clearSafeError()
        return ""
    }

    @Synchronized
    fun disable(context: Context) {
        appContext = context.applicationContext
        server?.disconnectClients()
        GatewayV33TrustManager.disconnectSessions(context)
        GatewayObservationStore.clear()
        GatewaySessionStore.disable(context)
        listenerError = null
        clearSafeError()
        PcSessionTracker.reset()
        startLocked(context.applicationContext)
    }

    /** Explicit one-release legacy transition helper; normal V3.3 uses trust.rotate. */
    @Synchronized
    fun rotateToken(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.rotate(context)
        server?.disconnectClients()
        GatewayV33TrustManager.disconnectSessions(context)
        reportSafeError(
            "LEGACY_CREDENTIAL_ROTATED",
            "Legacy transition credential rotated. Current PC Companions should open a fresh trusted session.",
        )
        return token
    }

    @Synchronized
    fun disconnect() {
        server?.disconnectClients()
        appContext?.let(GatewayV33TrustManager::disconnectSessions)
        clearSafeError()
        PcSessionTracker.reset()
    }

    fun isEnabled(context: Context): Boolean = GatewaySessionStore.enabled(context)

    fun fleetHealth(context: Context): JSONObject {
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        val percent = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val charging = battery?.isCharging == true
        val caps = context.getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
            cm.getNetworkCapabilities(cm.activeNetwork)
        }
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return JSONObject()
            .put("version", 1)
            .put("batteryPercent", percent ?: JSONObject.NULL)
            .put("charging", charging)
            .put("network", network)
            .put("os", "Android ${android.os.Build.VERSION.RELEASE}")
            .put("model", android.os.Build.MODEL)
            .put("permissions", JSONObject().put("accessibility", DeviceState.accessibilityConnected))
    }

    /** Kept only for compatibility callers. V3.3 UI must never expose this value. */
    fun tokenForUser(context: Context): String? = GatewaySessionStore.token(context)

    internal fun reportSafeError(message: String?) {
        reportSafeError("GATEWAY_DEGRADED", message)
    }

    internal fun reportSafeError(code: String?, message: String?) {
        lastSafeErrorCode = code?.take(80)
        lastSafeError = message?.take(240)
        lastSafeErrorAtMs = if (message.isNullOrBlank()) null else System.currentTimeMillis()
    }

    internal fun clearSafeError() {
        lastSafeError = null
        lastSafeErrorCode = null
        lastSafeErrorAtMs = null
    }

    fun status(context: Context): JSONObject {
        val current = GatewayObservationStore.current()
        val follow = FollowMeLearnerRuntime.progress()
        val socket = server
        val enabled = GatewaySessionStore.enabled(context)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val activeClients = socket?.connectedClients() ?: 0
        val recentlyAuthenticated = PcSessionTracker.isRecent()
        val trust = GatewayV33TrustManager.status(context)
        val trustedSessionCount = trust.optInt("activeSessionCount", 0)
        val pcSessionKnown = trustedSessionCount > 0 || recentlyAuthenticated
        val bootstrapListening = socket?.isRunning() == true
        val listening = enabled && bootstrapListening
        val phoneControlSnapshot = CyclonePermissionSetup.phoneControlSnapshot(context)
        val phoneControlReady = phoneControlSnapshot.ready
        val phoneControlNeedsRepair = phoneControlSnapshot.needsRepair
        val semanticState = when {
            !phoneControlReady -> "UNAVAILABLE"
            current == null -> "DEGRADED"
            else -> "READY"
        }
        val authorityState = when {
            !enabled -> "DENIED"
            !GatewayActionAuthorityRegistry.isProductionAuthorityBound() -> "DEGRADED"
            !phoneControlReady -> "DEGRADED"
            else -> "READY"
        }
        val state = when {
            !enabled -> "OFF"
            !listening || listenerError != null -> "ATTENTION_NEEDED"
            trust.optString("trustState") == "CONFIRMATION_REQUIRED" -> "WAITING_FOR_PC"
            !phoneControlReady -> "ATTENTION_NEEDED"
            pcSessionKnown -> "CONNECTED"
            else -> "WAITING_FOR_PC"
        }
        val pendingTrust = trust.optString("trustState") == "CONFIRMATION_REQUIRED" ||
            trust.optBoolean("confirmationRequired")
        val trustedPcCount = trust.optInt("trustedPcCount", 0)
        val nextAction = GatewayReadyDoctor.nextAction(
            gatewayEnabled = enabled,
            socketListening = listening,
            hasListenerError = listenerError != null,
            phoneControlReady = phoneControlReady,
            phoneControlNeedsRepair = phoneControlNeedsRepair,
            pendingTrust = pendingTrust,
            trustedPcCount = trustedPcCount,
            pcSessionKnown = pcSessionKnown,
        )
        return JSONObject()
            .put("protocolVersion", GatewayProtocol.VERSION)
            .put("trustProtocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("supportedProtocolVersions", JSONArray(listOf(GatewayTrustProtocolV33.VERSION)))
            .put("cycloneOneSessionProtocol", "cyclone.one.session.v1")
            .put("appVersion", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
            .put("package", context.packageName)
            .put("gatewayState", state)
            .put("gatewayEnabled", enabled)
            .put("socketLifecycleState", when {
                listenerError != null -> "ERROR"
                bootstrapListening -> "LISTENING"
                else -> "STARTING"
            })
            .put("socketListening", listening)
            .put("pairingBootstrapListening", bootstrapListening)
            .put("pairingActive", GatewayDesktopPairingManager.active())
            .put("socketName", GatewayProtocol.SOCKET_NAME)
            .put("networkListener", false)
            .put("accessibilityConnected", DeviceState.accessibilityConnected)
            .put("fleetHealth", fleetHealth(context))
            .put("phoneControlReady", phoneControlReady)
            .put("phoneControlNeedsRepair", phoneControlNeedsRepair)
            .put("nextAction", nextAction?.let { action ->
                JSONObject()
                    .put("code", action.code)
                    .put("title", action.title)
                    .put("body", action.body)
                    .put("actionLabel", action.actionLabel)
            } ?: JSONObject.NULL)
            .put("semanticObservationState", semanticState)
            .put("actionAuthorityState", authorityState)
            .put("controllerOwner", DeviceState.controller.name)
            .put("teachingActive", follow.active)
            .put("currentPackage", DeviceState.currentPackage ?: JSONObject.NULL)
            .put("currentPageKey", current?.page?.pageKey ?: JSONObject.NULL)
            .put("currentObservationId", current?.id ?: JSONObject.NULL)
            .put("rootAppearsAvailable", rootAppearsAvailable())
            .put("rootShellExposed", false)
            .put("actionAuthorityBinding", GatewayActionAuthorityRegistry.bindingName())
            .put("productionActionAuthorityBound", GatewayActionAuthorityRegistry.isProductionAuthorityBound())
            .put("desktopClipboard", GatewayClipboardAdapter.capability(context))
            .put("trust", trust)
            .put("connectedSession", JSONObject()
                .put("connected", pcSessionKnown)
                .put("clientCount", activeClients)
                .put("trustedSessionCount", trustedSessionCount)
                .put("transport", if (pcSessionKnown || activeClients > 0) "adb-forwarded-localabstract" else JSONObject.NULL)
                .put("lastAuthenticatedAtMs", PcSessionTracker.lastAuthenticatedAt() ?: JSONObject.NULL)
                .put("pcIdentityDetectable", trust.optInt("trustedPcCount", 0) > 0))
            .put("executionSessions", GatewaySessionAdapter.list(context).optJSONArray("sessions") ?: JSONArray())
            .put("capabilities", JSONObject()
                .put("operations", JSONArray(GatewayProtocol.operations.toList()))
                .put("phoneTools", JSONArray(GatewayV33ActionAdapter.allowedTools.toList()))
                .put("manualDesktopKinds", JSONArray(DesktopManualControlContract.allowedKinds.toList()))
                .put("fullSemanticControls", true)
                .put("pageDebugFunnel", true)
                .put("appGraphRetrieval", true)
                .put("adaptiveBrainRecall", true)
                .put("canonicalTeaching", true)
                .put("oneShotScreenshot", true)
                .put("cycloneOneExecutionSessions", true)
                .put("exactSessionSnapshot", true)
                .put("backgroundLiveVideo", false)
                .put("liveVideoOwnedByAndroidBridge", false))
            .put("adbForward", "adb forward tcp:${GatewayProtocol.DEFAULT_FORWARD_PORT} localabstract:${GatewayProtocol.SOCKET_NAME}")
            .put("lastError", listenerError ?: JSONObject.NULL)
            .put("lastSafeError", lastSafeError ?: JSONObject.NULL)
            .put("lastSafeErrorCode", lastSafeErrorCode ?: JSONObject.NULL)
            .put("lastSafeErrorAtMs", lastSafeErrorAtMs ?: JSONObject.NULL)
    }

    private fun rootAppearsAvailable(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk",
    ).any { File(it).exists() } || Build.TAGS.orEmpty().contains("test-keys")

    @Synchronized
    private fun startLocked(context: Context) {
        if (server?.isRunning() == true) return
        try {
            server = GatewaySocketServer { line -> GatewayDispatcher.handle(context, line) }.also { it.start() }
            listenerError = null
        } catch (error: Exception) {
            server = null
            listenerError = (error.message ?: error.javaClass.simpleName).take(240)
            reportSafeError("SOCKET_START_FAILED", "Cyclone USB bridge socket could not start.")
        }
    }
}

internal object GatewayDispatcher {
    private val trustSessionBootstrap = setOf("trust.session.begin", "trust.session.complete")
    private val load = GatewayLoad(GatewaySocketServer.MAX_CLIENT_WORKERS, GatewayLoad.STUCK_MS)

    fun handle(context: Context, line: String): String {
        var id = ""
        return try {
            val request = GatewayProtocol.parse(line)
            id = request.id
            GatewayProtocol.requireKnownOperation(request.op, request.id)
            val unauthenticatedBootstrap = request.op in GatewayProtocol.unauthenticatedOperations
            val enabled = GatewaySessionStore.enabled(context)
            val auth = if (unauthenticatedBootstrap) null else GatewaySessionStore.resolveAuth(context, request.auth)

            when {
                request.op in trustSessionBootstrap && !enabled -> throw GatewayProtocolException(
                    "CAPABILITY_UNAVAILABLE",
                    "Cyclone AI Gateway is disabled on this phone. Complete visible trust or enable it in Cyclone AI.",
                    request.id,
                )
                !unauthenticatedBootstrap && !enabled -> throw GatewayProtocolException(
                    "CAPABILITY_UNAVAILABLE",
                    "PC Gateway is disabled",
                    request.id,
                )
                !unauthenticatedBootstrap && auth == null -> throw GatewayProtocolException(
                    "AUTH_REJECTED",
                    "Trusted session is invalid, expired or revoked",
                    request.id,
                )
                auth?.mode == GatewaySessionAuthMode.LEGACY_READ_ONLY && request.op !in GatewayProtocol.legacyReadOnlyOperations -> {
                    throw GatewayProtocolException(
                        "PROTOCOL_MISMATCH",
                        "Legacy transition credentials are read-only. Update PC Companion and complete Allow this PC trust.",
                        request.id,
                        JSONObject().put("requiredProtocolVersion", GatewayTrustProtocolV33.VERSION),
                    )
                }
                else -> {
                    if (auth != null) GatewayRuntime.PcSessionTracker.noteAuthenticated()
                    val (ticket, busy) = load.enter(request.op, System.currentTimeMillis())
                    if (ticket == null) throw GatewayProtocolException(
                        "PHONE_APP_BUSY",
                        "Cyclone on the phone is still busy. Try again in a moment.",
                        request.id,
                        JSONObject().put("retryAfterMs", GatewayLoad.RETRY_AFTER_MS)
                            .put("busyOp", busy?.op ?: JSONObject.NULL).put("busyForMs", busy?.forMs ?: 0),
                    )
                    val result = try {
                        GatewaySessionExecutionContext.withAuth(auth) {
                            dispatch(context, request)
                        }
                    } finally {
                        load.exit(ticket)
                    }
                    GatewayRuntime.clearSafeError()
                    GatewayProtocol.success(id, result).toString()
                }
            }
        } catch (error: GatewayProtocolException) {
            GatewayRuntime.reportSafeError(error.code, error.message)
            GatewayProtocol.error(error.requestId.ifBlank { id }, error.code, error.message, error.details).toString()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            CycloneProcessDiagnostics.recordNonFatal(context, "gateway.dispatch.boundary", error)
            GatewayRuntime.reportSafeError(
                "INTERNAL_ERROR",
                "Gateway operation failed safely. Open diagnostics or reconnect the USB session.",
            )
            GatewayProtocol.error(id, "INTERNAL_ERROR", "Gateway operation failed").toString()
        }
    }

    private val humanDisplayOps = setOf("manual.execute", "clipboard.get", "clipboard.set")
    private val sessionBindOps = setOf(
        "observe.semantic", "observe.page_debug", "capture.screenshot",
        "ui.search", "ui.element", "action.execute", "skill.run",
        "session.snapshot",
    )

    private fun dispatch(context: Context, request: GatewayRequest): Any {
        // Trust-session IDs belong to authentication. Only execution operations use this scope.
        val bound = bindDispatchIdentity(request)
        LivePhoneMode.check(context, request)
        return when (request.op) {
        "trust.negotiate" -> GatewayV33TrustManager.negotiate(context, request.args)
        "trust.begin" -> GatewayV33TrustManager.beginTrust(context, request.args)
        "trust.complete" -> GatewayV33TrustManager.completeTrust(context, request.args).also {
            GatewaySessionStore.enableTrusted(context)
        }
        "trust.session.begin" -> GatewayV33TrustManager.beginSession(context, request.args)
        "trust.session.complete" -> GatewayV33TrustManager.completeSession(context, request.args)
        "trust.rotate" -> GatewayV33TrustManager.rotate(context, request.auth, request.args)
        "trust.revoke" -> GatewayV33TrustManager.revoke(context, request.auth, request.args)
        "pair.begin" -> GatewayDesktopPairingManager.begin(context, request.args)
        "pair.complete" -> GatewayDesktopPairingManager.complete(context, request.args)
        "pair.qr.complete" -> GatewayDesktopPairingManager.completeQr(context, request.args)
        "pair.revoke" -> GatewayDesktopPairingManager.revoke(context)
        "manual.execute" -> GatewayV33ManualDesktopAdapter.execute(context, request.id, request.args)
        "clipboard.get" -> GatewayClipboardAdapter.capability(context)
        "clipboard.set" -> GatewayV33ClipboardAdapter.set(context, request.id, request.args)
        "bridge.status" -> GatewayRuntime.status(context)
        "session.list" -> GatewaySessionAdapter.list(context)
        "session.start" -> GatewaySessionAdapter.start(context, request.args)
        "session.status" -> GatewaySessionAdapter.status(context, request.args)
        "session.pause" -> GatewaySessionAdapter.pause(context, request.args)
        "session.continue" -> GatewaySessionAdapter.resume(context, request.args)
        "session.resume" -> GatewaySessionAdapter.resume(context, request.args)
        "session.handoff" -> GatewaySessionAdapter.handoff(context, request.args)
        "session.stop" -> GatewaySessionAdapter.stop(request.args)
        "session.snapshot" -> GatewaySessionAdapter.snapshot(context, request.args)
        "observe.semantic" -> GatewayObservationAdapter.capture(context, request.args).payload
        "observe.page_debug" -> GatewayPageDebugAdapter.capture(context, request.args)
        "capture.screenshot" -> GatewayCaptureAdapter.capture(context, request.args)
        "ui.search" -> {
            val observation = currentObservation(bound, request.id)
                ?: GatewayObservationAdapter.capture(context, request.args)
            JSONObject()
                .put("observationId", observation.id)
                .put("elementIdScope", "observation-local")
                .put("query", request.args.optString("query"))
                .put(
                    "candidates",
                    GatewayObservationAdapter.search(
                        observation,
                        request.args.optString("query"),
                        request.args.optInt("limit", 30),
                    ),
                )
        }
        "ui.element" -> {
            val observation = currentObservation(bound, request.id)
                ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic before ui.element")
            val requestedObservationId = request.args.optString("observationId").trim()
            if (requestedObservationId.isNotBlank() && requestedObservationId != observation.id) {
                throw GatewayProtocolException("STALE_OBSERVATION", "Element belongs to an older observation; observe again")
            }
            val elementId = request.args.optString("elementId", request.args.optString("id"))
            if (elementId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "elementId is required")
            GatewayObservationAdapter.element(observation, elementId)
        }
        "app_graph.get" -> GatewayAppGraphAdapter.query(context, request.args)
        "brain.recall" -> GatewayBrainAdapter.recall(context, request.args)
        "action.execute" -> GatewayV33ActionAdapter.execute(context, request.id, request.args)
        "teach.start" -> GatewayTeachingAdapter.start(context)
        "teach.status" -> GatewayTeachingAdapter.status(context)
        "teach.stop" -> GatewayTeachingAdapter.stop(context)
        "debug.snapshot" -> debugSnapshot(context)
        "skill.compile", "skill.run", "skill.match" -> dispatchSkill(context, request)
        "atlas.places", "atlas.get", "secrets.slots", "secrets.request" ->
            GatewayV5ContractAdapter.dispatch(request.op, request.args)
        "atlas.diff", "mapping.start", "mapping.pause", "mapping.stop", "mapping.status" ->
            GatewayV5MappingAdapter.dispatch(context, request.op, request.args)
        "ask.start", "ask.status", "ask.cancel" -> {
            GatewayV5AskAdapter.install(context)
            GatewayV5AskAdapter.dispatch(request.op, request.args)
        }
        "market.catalog", "market.install", "market.remove", "market.run" -> {
            GatewayV5MarketAdapter.install(context)
            GatewayV5MarketAdapter.dispatch(request.op, request.args)
        }
        "skills.list" -> {
            GatewayV5SkillsAdapter.install(context)
            GatewayV5SkillsAdapter.dispatch(request.op, request.args)
        }
        "learn.run" -> {
            GatewayV5LearnAdapter.install(context)
            GatewayV5LearnAdapter.dispatch(request.op, request.args)
        }
        "lab.start", "lab.status", "lab.answer", "lab.record" -> {
            GatewayV5LabAdapter.install(context)
            GatewayV5LabAdapter.dispatch(request.op, request.args)
        }
        "cc.start", "cc.status", "cc.answer", "cc.key", "cc.media" -> {
            GatewayV5CommandAdapter.install(context)
            GatewayV5CommandAdapter.dispatch(request.op, request.args)
        }
        "ports.poll", "ports.blob", "ports.answer", "ports.file" -> {
            GatewayV5PortsAdapter.install(context)
            GatewayV5PortsAdapter.dispatch(request.op, request.args)
        }
        "health.report" -> com.cyclone.mobile.runtime.health.AppHealth.report(context)
        "profiles.list", "profiles.apps", "profiles.switch", "profiles.app" -> {
            GatewayV5ProfilesAdapter.install(context)
            GatewayV5ProfilesAdapter.dispatch(request.op, request.args)
        }
        "signup.maps", "signup.forget" -> {
            GatewayV5SignupAdapter.install(context)
            GatewayV5SignupAdapter.dispatch(request.op, request.args)
        }
        "dictionary.get", "dictionary.edit", "models.list", "manual.get" -> {
            GatewayV5ManualAdapter.install(context)
            GatewayV5ManualAdapter.dispatch(request.op, request.args)
        }
        "numbers.list" -> {
            if (request.args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "numbers.list takes no arguments.", request.id)
            com.cyclone.mobile.codes.AndroidCodes.report(context)
        }
        "apps.list" -> GatewayV5AppsAdapter.dispatch(request.op, request.args)
        "share.status" -> {
            if (request.args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "share.status takes no arguments.", request.id)
            com.cyclone.mobile.share.LanShareRuntime.status(context)
        }
        "share.request" -> {
            val extra = request.args.keys().asSequence().filter { it != "pcLabel" }.toList()
            if (extra.isNotEmpty()) throw GatewayProtocolException("INVALID_REQUEST", "share.request takes only pcLabel.", request.id)
            com.cyclone.mobile.share.LanShareRuntime.request(context, request.args.optString("pcLabel").takeIf { it.isNotBlank() })
        }
        "atlas.here" -> {
            if (request.args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "atlas.here takes no arguments.", request.id)
            com.cyclone.mobile.mapping.crawl.StepLocation.here(context)
        }
        "knowledge.get" -> {
            GatewayV5KnowledgeSummaryAdapter.install(context)
            GatewayV5KnowledgeSummaryAdapter.dispatch(request.op, request.args)
        }
        "atlas.versions", "scenarios.list" -> {
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(context)
            GatewayV5KnowledgeAdapter.dispatch(request.op, request.args)
        }
        "runs.list", "runs.get", "runs.mark" -> {
            com.cyclone.mobile.ai.AgentTraceRuntime.initialize(context)
            GatewayV5RunsAdapter.install(context)
            GatewayV5RunsAdapter.dispatch(request.op, request.args)
        }
        else -> throw GatewayProtocolException(
            "PROTOCOL_MISMATCH",
            "Unsupported gateway operation: ${request.op}",
            request.id,
        )
    }

    }

    private fun bindDispatchIdentity(request: GatewayRequest): ExecutionContext {
        if (request.op !in humanDisplayOps && request.op !in sessionBindOps) return ExecutionContext.DEFAULT
        val merged = ExecutionRequestScope.merge(request.args, request.args.optJSONObject("params") ?: JSONObject())
        if (request.args.has("workspaceId") && !merged.has("workspaceId")) {
            merged.put("workspaceId", request.args.get("workspaceId"))
        }
        if (request.args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
            merged.put("workspaceGeneration", request.args.get("workspaceGeneration"))
        }
        val plane = try {
            if (request.op in humanDisplayOps) {
                ExecutionRequestScope.requireForeground(merged)
                SessionContract.classify(merged)
            } else SessionContract.classify(merged)
        } catch (error: SessionIdentityException) {
            val code = if (
                request.op == "session.snapshot" &&
                error.errorClass == SessionContract.SESSION_DISPLAY_MISMATCH
            ) {
                "PROTOCOL_MISMATCH"
            } else {
                error.errorClass
            }
            throw GatewayProtocolException(
                code,
                error.message ?: "session/display mismatch",
                request.id,
            )
        }
        return ExecutionContext(plane.sessionId, plane.displayId)
    }

    private fun currentObservation(bound: ExecutionContext, requestId: String): GatewayObservation? = try {
        GatewayObservationStore.current(bound)
    } catch (error: SessionIdentityException) {
        throw GatewayProtocolException(
            error.errorClass,
            error.message ?: "session/display mismatch",
            requestId,
        )
    }

    private fun dispatchSkill(context: Context, request: GatewayRequest): JSONObject {
        AutomationRuntime.initialize(context)
        val governor = CycloneV31Runtime.initialize(context).policyGovernor
        val store = AutomationRuntime.store
        val gate = GatePolicy(governor)
        val principal = PolicyPrincipal(PrincipalRef("pc.companion", PrincipalKind.EXTERNAL_GATEWAY))
        val ops = SkillGatewayOps(
            store = store,
            sink = SkillDraftSink(SkillCompiler(store), gate, principal),
            gate = gate,
            principal = principal,
            stepExecutor = SkillStepExecutor { commandId, tool, params ->
                val beforeObs = GatewayObservationStore.current()
                    ?: runCatching { GatewayObservationAdapter.capture(context, JSONObject()) }.getOrNull()
                val native = PhoneToolExecutor.execute(context, PhoneToolRequest(commandId, tool, params))
                val afterObs = runCatching { GatewayObservationAdapter.capture(context, JSONObject()) }.getOrNull()
                    ?: GatewayObservationStore.current()
                SkillStepOutcome(
                    ok = native.ok,
                    pageChanged = beforeObs?.page?.pageKey != afterObs?.page?.pageKey ||
                        native.beforeFingerprint != native.afterFingerprint,
                    beforeFingerprint = native.beforeFingerprint,
                    afterFingerprint = native.afterFingerprint,
                    errorClass = native.error?.code?.name,
                    generation = afterObs?.id ?: beforeObs?.id,
                    before = beforeObs?.let(::skillPageLocation),
                    after = afterObs?.let(::skillPageAfter),
                    delta = JSONObject()
                        .put("pageKeyChanged", beforeObs?.page?.pageKey != afterObs?.page?.pageKey)
                        .put("fingerprintChanged", native.beforeFingerprint != native.afterFingerprint),
                )
            },
            observePage = { GatewayObservationStore.current()?.let(::skillPageCard) },
        )
        return ops.dispatch(request)
    }

    private fun skillPageLocation(observation: GatewayObservation): JSONObject = JSONObject()
        .put("pageKey", observation.page.pageKey)
        .put("package", observation.page.packageName)
        .put("activity", observation.payload.opt("activity") ?: JSONObject.NULL)

    private fun skillPageCard(observation: GatewayObservation): JSONObject = skillPageLocation(observation)
        .put("observationScope", JSONObject().put("id", observation.id))

    private fun skillPageAfter(observation: GatewayObservation): JSONObject {
        val card = skillPageCard(observation)
        return skillPageLocation(observation).put("pageCard", card)
    }

    private fun debugSnapshot(context: Context): JSONObject {
        val observation = GatewayObservationStore.current()
        val latestPageDebug = PageDebugSandboxV293.latest(context)
        return JSONObject()
            .put("status", GatewayRuntime.status(context))
            .put("executionSessions", GatewaySessionAdapter.list(context))
            .put("latestObservation", observation?.payload ?: JSONObject.NULL)
            .put(
                "latestPageDebug",
                latestPageDebug?.let { GatewayPageDebugAdapter.safeExport(it) } ?: JSONObject.NULL,
            )
            .put("teaching", GatewayTeachingAdapter.status(context))
            .put("recentActions", JSONArray().also { out ->
                DeviceState.commandAudit.take(30).forEach { audit ->
                    out.put(
                        JSONObject()
                            .put("commandId", audit.commandId)
                            .put("tool", audit.tool)
                            .put("startedAtMs", audit.startedAtMs)
                            .put("finishedAtMs", audit.finishedAtMs)
                            .put("ok", audit.ok)
                            .put("beforeFingerprint", audit.beforeFingerprint ?: JSONObject.NULL)
                            .put("afterFingerprint", audit.afterFingerprint ?: JSONObject.NULL)
                            .put("errorCode", audit.errorCode ?: JSONObject.NULL),
                    )
                }
            })
            .put(
                "privacy",
                "No session token, private key, pairing code, API key, password, OTP, clipboard content or typed phone.type value is included.",
            )
    }
}
