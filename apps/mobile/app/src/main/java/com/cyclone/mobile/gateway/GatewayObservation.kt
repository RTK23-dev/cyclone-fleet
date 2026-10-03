package com.cyclone.mobile.gateway

import android.content.Context
import android.content.res.Configuration
import com.cyclone.mobile.AccessibilityRoles
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.fastpath.FastPathTree
import com.cyclone.mobile.mapping.session.MappingPlaneRequest
import com.cyclone.mobile.mapping.session.MappingSessionRuntime
import com.cyclone.mobile.observability.pagecontext.PageContextSummary
import com.cyclone.mobile.observability.pagecontext.PageTextExtractor
import com.cyclone.mobile.places.PlaceResolver
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.max

internal data class GatewayElement(
    val id: String,
    val source: String,
    val label: String,
    val semanticName: String,
    val role: String,
    val evidence: JSONObject,
)

internal data class GatewayObservation(
    val id: String,
    val capturedAt: Long,
    val page: PageContext,
    val payload: JSONObject,
    val elements: Map<String, GatewayElement>,
    val execution: ExecutionContext = ExecutionContext.DEFAULT,
    val generation: Long = 0,
    /**
     * Who produced this screen: [AtlasPersona.LIVE] only for an ordinary capture with no mapping pass running on the
     * plane; [AtlasPersona.MAPPING] while a mapping pass is active; null when that could not be established. Live
     * fact readers (task ledger, clause proof, people memory) accept only LIVE.
     */
    val persona: AtlasPersona? = null,
    /**
     * Current values of ordinary editable fields, for the on-phone Cyclone Mind only. Process-local: never part of
     * [payload], never exported to the PC gateway, Glass or diagnostics. Password, sensitive-hint and browser address
     * fields are never included.
     */
    val fieldValues: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "GatewayObservation(id=$id, package=${page.packageName}, generation=$generation)"
}

internal object GatewayObservationStore {
    private val scoped = com.cyclone.mobile.runtime.session.SessionObservationStore(
        com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.sessions)
    fun current(sessionId: String? = null): GatewayObservation? = scoped.current(sessionId)?.let(::project)
    fun current(execution: ExecutionContext): GatewayObservation? =
        scoped.current(execution.sessionId, execution.displayId)?.let(::project)
    fun replace(observation: GatewayObservation): GatewayObservation = project(
        scoped.publish(observation.execution.sessionId, observation.execution.displayId, observation.id,
            observation, observation.capturedAt))
    private fun project(envelope: com.cyclone.mobile.runtime.session.SessionObservationEnvelope): GatewayObservation {
        val source = envelope.payload as GatewayObservation
        val identity = com.cyclone.mobile.runtime.session.ObservationIdentity.fromPayload(source.id, envelope.generation,
            source.execution, source.capturedAt, source.payload)
        val elements = source.elements.mapValues { (_, element) -> element.copy(evidence = JSONObject(element.evidence.toString())
            .put("observation", identity.toJson()).put("generation", envelope.generation)) }
        val payload = JSONObject(source.payload.toString()).put("observation", identity.toJson()).put("generation", envelope.generation)
        payload.optJSONObject("pageContext")?.put("observation", identity.toJson())
        payload.optJSONObject("screenshot")?.put("observation", identity.toJson())?.put("generation", envelope.generation)
        payload.optJSONArray("semanticControls")?.let { controls ->
            for (i in 0 until controls.length()) controls.optJSONObject(i)?.let { control ->
                control.put("observation", identity.toJson()).put("generation", envelope.generation)
            }
        }
        return source.copy(generation = envelope.generation, elements = elements,
            page = source.page.copy(observation = identity, controls = source.page.controls.map { it.copy(selector = JSONObject(it.selector.toString())) }),
            payload = payload)
    }
    fun clear(sessionId: String? = null) { scoped.clear(sessionId) }
}

internal object GatewayObservationAdapter {
    // Process-local salt lets verification compare editable state without exposing or persisting
    // user-entered text (including passwords/OTPs) or a stable brute-forceable digest.
    private val editableStateSalt = UUID.randomUUID().toString()

    fun capture(
        context: Context,
        args: JSONObject = JSONObject(),
        catalogPersona: AtlasPersona = AtlasPersona.LIVE,
    ): GatewayObservation {
        val merged = ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
        if (args.has("workspaceId") && !merged.has("workspaceId")) merged.put("workspaceId", args.get("workspaceId"))
        if (args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
            merged.put("workspaceGeneration", args.get("workspaceGeneration"))
        }
        val execution = try {
            SessionContract.classify(merged)
            ExecutionRequestScope.bind(merged)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch")
        }
        val background = execution.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        if (background) com.cyclone.mobile.runtime.background.WorkspaceRuntime.requireScope(execution)
        val service = CycloneAccessibilityService.instance
            ?: throw GatewayProtocolException("ACCESSIBILITY_NOT_CONNECTED", "Cyclone Accessibility is not connected")
        PageAwarenessRuntime.initialize(context)
        val planeAtStart = observationPlane(args, execution)
        val executionGenerationAtStart = if (background) com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(execution.sessionId) else null
        fun surface(): com.cyclone.mobile.agent.ObservationSurface {
            if (background) com.cyclone.mobile.runtime.background.WorkspaceRuntime.requireScope(execution)
            val plane = observationPlane(args, execution)
            val workspace = plane.workspaceId?.let { id -> Layer2Workspaces.engine.snapshot().singleOrNull { it.id == id } }
            val profile = com.cyclone.mobile.agent.SemanticCaptureBoundary.workspaceProfile(plane, Layer2Workspaces.engine.holder(), workspace)
            val executionGeneration = if (background) com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(execution.sessionId) else null
            return service.observationSurface(execution.sessionId, execution.displayId,
                plane.toJson().put("executionGeneration", executionGeneration ?: JSONObject.NULL).toString(), profile)
        }
        val includeScreenshot = args.optBoolean("includeScreenshot", false)
        // The mapper retries with longer quiet waits, and its last attempt may tolerate animated content (plan 20).
        val settleQuietMs = args.optLong("settleQuietMs", 0L).coerceIn(0L, 1_000L)
        val settleMaxMs = args.optLong("settleMaxMs", 0L).coerceIn(0L, 6_000L)
        val tolerateContentChange = catalogPersona == AtlasPersona.MAPPING && args.optBoolean("tolerateContentChange", false)
        val captured = try {
            com.cyclone.mobile.agent.SemanticCaptureBoundary.capture(::surface,
                tolerateContentChange = tolerateContentChange,
                settle = {
                    service.waitForUiQuiet()
                    if (settleMaxMs > 0) service.waitForRevisionQuiet(execution.displayId, settleQuietMs.coerceAtLeast(90L), settleMaxMs)
                },
                semantic = { if (background) com.cyclone.mobile.runtime.background.WorkspaceRuntime.observe(execution) else service.observe(markFresh = false) },
                image = if (!includeScreenshot) null else ({
                    val result = com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
                        "observation-image-${UUID.randomUUID()}", "phone.screenshot", JSONObject()
                            .put("sessionId", execution.sessionId).put("displayId", execution.displayId)
                            .put("minCapturedAtMonotonicMs", android.os.SystemClock.uptimeMillis())
                            .put("includeBase64", args.optBoolean("includeScreenshotBase64", false))))
                    if (!result.ok) JSONObject().put("available", false).put("errorCode", "SCREENSHOT_FAILED")
                    else result.payload as? JSONObject ?: JSONObject().put("available", false)
                }))
        } catch (error: Exception) {
            GatewayObservationStore.clear(execution.sessionId)
            if (error is com.cyclone.mobile.agent.CaptureChanged) throw captureChanged()
            throw error
        }
        if (!background) com.cyclone.mobile.DeviceState.markObserved()
        val snapshot = captured.semantic
        // Reduce the trusted browser address-bar value before sanitizing/exporting observation.
        // Full URLs (including paths and queries) never enter PageCard, Atlas or diagnostics here.
        val resolvedPlace = PlaceResolver.resolveObservedSnapshot(snapshot)
        val observedBrowserOrigin = resolvedPlace?.origin
        val captureStart = captured.startMs
        val captureEnd = captured.endMs
        if (snapshot.screenWidth != captured.surface.width || snapshot.screenHeight != captured.surface.height ||
            com.cyclone.mobile.agent.SemanticCaptureBoundary.windowSignature(snapshot.windows) != captured.surface.windowSignature) {
            GatewayObservationStore.clear(execution.sessionId)
            throw captureChanged()
        }
        val screenshot = captured.image
        val raw = snapshot.toJson()
        val safeRaw = GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)
        // Learning and legacy page projection never receive raw editable text. The unsanitized
        // snapshot remains process-local only for salted, non-exported verification state below.
        val learned = PageAwarenessRuntime.capture(context, safeRaw)
        val page = com.cyclone.mobile.agent.tools.ObservationProjections.freshLegacy(safeRaw, learned)
        val observationId = UUID.randomUUID().toString()
        val rawNodes = safeRaw.optJSONArray("nodes") ?: JSONArray()
        val rawTextById = snapshot.nodes.associate { it.id to it.text }
        val elements = linkedMapOf<String, GatewayElement>()
        val semanticControls = JSONArray()
        val controlSignatures = linkedSetOf<String>()

        page.controls.forEach { control ->
            controlSignatures += signature(control)
            val matchingNode = bestNode(control, rawNodes)
            val elementId = "semantic:$observationId:${control.key}"
            val evidence = JSONObject()
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "semantic")
                .put("controlKey", control.key)
                .put("label", control.label)
                .put("semanticName", control.semanticName)
                .put("role", control.role)
                .put("selector", GatewayPrivacy.sanitizeDeep(JSONObject(control.selector.toString())))
                .put("androidActions", JSONArray(control.androidActions))
                .put("risk", control.risk.name)
                .put("expectedEffect", control.expectedEffect ?: JSONObject.NULL)
                .put("confidence", control.confidence)
                .put("resourceId", matchingNode?.optString("resourceId").orEmpty())
                .put("contentDescription", matchingNode?.optString("contentDescription").orEmpty())
                .put("bounds", matchingNode?.optJSONObject("bounds") ?: JSONObject.NULL)
                .put("clickable", matchingNode?.optBoolean("clickable") ?: false)
                .put("longClickable", matchingNode?.optBoolean("longClickable") ?: false)
                .put("scrollable", matchingNode?.optBoolean("scrollable") ?: false)
                .put("editable", matchingNode?.optBoolean("editable") ?: false)
                .put("password", matchingNode?.optBoolean("password") ?: false)
                .put("enabled", matchingNode?.optBoolean("enabled") ?: true)
                .put("selected", matchingNode?.optBoolean("selected") ?: false)
                .put("checked", matchingNode?.optBoolean("checked") ?: false)
                .put("checkable", matchingNode?.optBoolean("checkable") ?: false)
                .put("focused", matchingNode?.optBoolean("focused") ?: false)
                .put("textStateDigest", editableTextState(matchingNode, rawTextById) ?: JSONObject.NULL)
                .put("rawNodeId", matchingNode?.optString("id")?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("rawPath", matchingNode?.optString("path")?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            semanticControls.put(evidence)
            elements[elementId] = GatewayElement(elementId, "semantic", control.label, control.semanticName, control.role, evidence)
        }

        // The canonical semantic store scans at most 450 raw nodes. Surface interactive nodes
        // outside that window as supplemental semantic controls so agents can see the whole page,
        // not only the stored PageContext slice.
        var supplementalCount = 0
        for (index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: continue
            val interactive = node.optBoolean("clickable") || node.optBoolean("longClickable") ||
                node.optBoolean("editable") || node.optBoolean("scrollable") || node.optBoolean("checkable") ||
                node.optString("role") in setOf("button", "tab", "switch", "checkbox", "edit_text", "textbox")
            val bounds = node.optJSONObject("bounds")
            val boundsWidth = if (bounds == null) 0 else bounds.optInt("right") - bounds.optInt("left")
            val boundsHeight = if (bounds == null) 0 else bounds.optInt("bottom") - bounds.optInt("top")
            if (!AccessibilityRoles.isPublishedInteractive(
                    node.optBoolean("visibleToUser", true), interactive, boundsWidth, boundsHeight,
                )
            ) continue
            if (!interactive) continue
            if (signature(node) in controlSignatures) continue
            val ownLabel = node.optString("text").takeUnless { it.isBlank() || it == "<redacted>" }
                ?: node.optString("contentDescription").takeUnless { it.isBlank() || it == "<redacted>" }
                ?: node.optString("resourceId").substringAfterLast('/').replace('_', ' ').takeIf { it.isNotBlank() }
            val inheritedLabel = if (ownLabel == null) descendantLabel(node, rawNodes) else ""
            val label = ownLabel ?: inheritedLabel.takeIf { it.isNotBlank() }
                ?: continue
            if (label.isBlank()) continue
            supplementalCount++
            val elementId = "semantic:$observationId:supp:$supplementalCount"
            val evidence = JSONObject()
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "semantic_supplement")
                .put("controlKey", "supp:$supplementalCount")
                .put("label", label.take(140))
                .put("semanticName", semanticize(label))
                .put("role", node.optString("role"))
                .put("selector", GatewayPrivacy.sanitizeDeep(supplementSelector(node, inheritedLabel)))
                .put("androidActions", node.optJSONArray("actions") ?: JSONArray())
                .put("risk", "SAFE")
                .put("expectedEffect", JSONObject.NULL)
                .put("confidence", 0.55)
                .put("resourceId", node.optString("resourceId"))
                .put("contentDescription", node.optString("contentDescription"))
                .put("bounds", node.optJSONObject("bounds") ?: JSONObject.NULL)
                .put("clickable", node.optBoolean("clickable"))
                .put("longClickable", node.optBoolean("longClickable"))
                .put("scrollable", node.optBoolean("scrollable"))
                .put("editable", node.optBoolean("editable"))
                .put("password", node.optBoolean("password"))
                .put("enabled", node.optBoolean("enabled", true))
                .put("selected", node.optBoolean("selected"))
                .put("checked", node.optBoolean("checked"))
                .put("checkable", node.optBoolean("checkable"))
                .put("focused", node.optBoolean("focused"))
                .put("textStateDigest", editableTextState(node, rawTextById) ?: JSONObject.NULL)
                .put("rawNodeId", node.optString("id").takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("rawPath", node.optString("path").takeIf(String::isNotBlank) ?: JSONObject.NULL)
            semanticControls.put(evidence)
            elements[elementId] = GatewayElement(elementId, "semantic_supplement", label.take(140), semanticize(label), node.optString("role"), evidence)
        }

        for (index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: continue
            val rawId = node.optString("id").ifBlank { "index-$index" }
            val elementId = "raw:$observationId:$rawId"
            val label = node.optString("text").takeUnless { it == "<redacted>" }.orEmpty()
                .ifBlank { node.optString("contentDescription").takeUnless { it == "<redacted>" }.orEmpty() }
                .ifBlank { node.optString("resourceId").substringAfterLast('/').replace('_', ' ') }
            val evidence = JSONObject(node.toString())
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "raw_accessibility")
            elements[elementId] = GatewayElement(
                id = elementId,
                source = "raw_accessibility",
                label = label,
                semanticName = semanticize(label),
                role = node.optString("role"),
                evidence = evidence,
            )
        }

        val indexedControlCount = FastPathTree.assignControlIndices(semanticControls)
        val treeUseful = FastPathTree.treeUseful(rawNodes.length(), indexedControlCount)
        val perceptionMode = FastPathTree.perceptionMode(rawNodes.length(), indexedControlCount)

        val pageText = PageTextExtractor.extract(safeRaw)
        val pageSummary = PageContextSummary.build(
            snapshot = safeRaw,
            pageKey = page.pageKey,
            title = page.title,
            controlCount = page.controls.size + supplementalCount,
            textLineCount = pageText.optInt("lineCount"),
        )
        // Page context is the canonical PC-agent source of truth. Graph/Brain knowledge can
        // contribute only advisory, repeatedly-verified next hops; it cannot authorize an action.
        AppLearnerRuntime.initialize(context)
        val nextHopHints = GatewayRouteEvidence.nextHops(
            page = page,
            accessibilityFingerprint = snapshot.fingerprint,
            graph = AppLearnerRuntime.graph(page.packageName),
            brainSkills = AdaptiveBrainRuntime.reusableMicroSkills(context),
        )
        val windows = safeRaw.optJSONArray("windows") ?: JSONArray()
        val boundedPageEvidence = GatewayRouteEvidence.pageEvidence(
            page = page,
            packageName = snapshot.packageName,
            activity = snapshot.className,
            pageText = pageText,
            pageSummary = pageSummary,
            semanticControls = page.controls.size,
            supplementalControls = supplementalCount,
            rawNodes = rawNodes.length(),
            windows = windows.length(),
            nextHopHints = nextHopHints,
        )
        if (observedBrowserOrigin != null) {
            boundedPageEvidence.put("browserOrigin", observedBrowserOrigin)
                .put("browserOriginSource", "chrome-address-bar")
                .put("browserOriginObservationId", observationId)
        }
        boundedPageEvidence.put("captureStartMonotonicMs", captureStart)
            .put("legacyFreshnessShadow", JSONObject().put("matches", learned.controls.map { it.key } == page.controls.map { it.key })
                .put("currentControls", page.controls.size).put("learnedControls", learned.controls.size))
            .put("captureEndMonotonicMs", captureEnd).put("captureDurationMs", captureEnd - captureStart)
            .put("captureWidth", snapshot.screenWidth).put("captureHeight", snapshot.screenHeight)
            .put("imageState", "unavailable")
        boundedPageEvidence.put("windowSignature", captured.surface.windowSignature)
            .put("rotation", captured.surface.rotation).put("semanticRevision", captured.surface.revision)
            .put("profileId", captured.surface.profileId ?: JSONObject.NULL)
            .put("captureClock", "uptimeMillis")
            .put("imageState", if (screenshot?.optBoolean("available") == true) "current" else "unavailable")
            .put("imageStartMonotonicMs", captured.imageStartMs ?: JSONObject.NULL)
            .put("imageEndMonotonicMs", captured.imageEndMs ?: JSONObject.NULL)
            .put("imageErrorCode", screenshot?.optString("errorCode")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)

        val fullPage = page.toAgentJson(maxControls = page.controls.size)
            .put("structuralKey", page.structuralKey)
            .put("contentKey", page.contentKey)
            .put("firstSeenAt", page.firstSeenAt)
            .put("lastSeenAt", page.lastSeenAt)
            .put("previewPath", page.previewPath ?: JSONObject.NULL)
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        var payload = JSONObject()
            .put("observationId", observationId)
            .put("elementIdScope", "observation-local; IDs are valid only while this observation is current")
            .put("timestamp", snapshot.timestampMs)
            .put("package", snapshot.packageName ?: JSONObject.NULL)
            .put("activity", snapshot.className ?: JSONObject.NULL)
            .put("display", JSONObject()
                .put("width", snapshot.screenWidth)
                .put("height", snapshot.screenHeight)
                .put("orientation", orientation))
            .put("pageKey", page.pageKey)
            .put("pageTitle", page.title)
            .put("semanticFingerprint", page.pageKey)
            .put("accessibilityFingerprint", snapshot.fingerprint)
            .put("pageContext", fullPage)
            .put("semanticControls", semanticControls)
            .put("controlCount", page.controls.size)
            .put("supplementalControlCount", supplementalCount)
            .put("indexedControlCount", indexedControlCount)
            .put("treeUseful", treeUseful)
            .put("perceptionMode", perceptionMode)
            .put("pageText", pageText)
            .put("pageSummary", pageSummary)
            .put("pageEvidence", boundedPageEvidence)
            .put("nextHopHints", nextHopHints)
            .put("screenshot", screenshot ?: JSONObject.NULL)
            .put("windows", windows)
            .put("rawAccessibility", safeRaw)
            .put("rawNodeCount", rawNodes.length())

        payload.put("sessionId", execution.sessionId).put("displayId", execution.displayId)
        if (background) payload.put("executionGeneration", com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(execution.sessionId))
        val plane = try {
            observationPlane(args, execution)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch")
        }
        if (plane != planeAtStart || (background &&
            com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(execution.sessionId) != executionGenerationAtStart)) {
            GatewayObservationStore.clear(execution.sessionId)
            throw captureChanged()
        }
        payload = SessionContract.attach(payload, plane)
        val activeMapping = runCatching {
            MappingSessionRuntime.controller(context).statusForPlane(
                MappingPlaneRequest(plane.sessionId, plane.displayId,
                    plane.workspaceId, plane.workspaceGeneration),
            )
        }
        val job = activeMapping.getOrNull()
        val observedPersona = when {
            activeMapping.isFailure -> null
            job != null && !job.state.terminal -> AtlasPersona.MAPPING
            catalogPersona == AtlasPersona.LIVE -> AtlasPersona.LIVE
            else -> null
        }
        resolvedPlace?.let { place ->
            // Session status, rather than caller JSON, decides which Atlas persona receives a
            // catalog entry. An internal mapping capture without an active job stays unresolved.
            val persona = when {
                activeMapping.isFailure -> null
                job != null && !job.state.terminal -> runCatching { AtlasPersona.fromWire(job.persona) }.getOrNull()
                job != null -> null
                catalogPersona == AtlasPersona.LIVE -> AtlasPersona.LIVE
                else -> null
            }
            persona?.let { runCatching { AtlasRuntime.catalog.recordObserved(place, it) } }
        }
        elements.values.forEach { it.evidence.put("sessionId", execution.sessionId).put("displayId", execution.displayId) }
        val fieldValues = elements.values.mapNotNull { element ->
            val evidence = element.evidence
            if (!evidence.optBoolean("editable") || evidence.optBoolean("password")) return@mapNotNull null
            val resourceId = evidence.optString("resourceId")
            val hints = "$resourceId ${evidence.optString("contentDescription")} ${element.label} ${evidence.optString("role")}"
            if (GatewayPrivacy.isSensitiveHint(hints) || com.cyclone.mobile.places.PlaceResolver.isChromeAddressBarResourceId(resourceId)) {
                return@mapNotNull null
            }
            val rawId = evidence.optString("rawNodeId").takeIf { it.isNotBlank() && it != "null" } ?: evidence.optString("id")
            val value = rawTextById[rawId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            element.id to value.take(500)
        }.toMap()
        return GatewayObservationStore.replace(GatewayObservation(observationId, snapshot.timestampMs, page, payload, elements, execution,
            persona = observedPersona, fieldValues = fieldValues))
    }

    private fun captureChanged() = GatewayProtocolException("OBSERVATION_CHANGED_DURING_CAPTURE",
        "The screen or task scope changed during capture; request a fresh same-scope observation.")

    fun search(observation: GatewayObservation, query: String, limit: Int): JSONArray {
        val normalized = normalize(query)
        if (normalized.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "query is required")
        val ranked = observation.elements.values.mapNotNull { element ->
            val score = score(normalized, element)
            if (score <= 0.0) null else score to element
        }.sortedWith(compareByDescending<Pair<Double, GatewayElement>> { it.first }
            .thenBy { if (it.second.source == "semantic") 0 else 1 }
            .thenBy { it.second.label })
            .take(limit.coerceIn(1, 100))
        return JSONArray().also { out ->
            ranked.forEach { (score, element) ->
                val e = element.evidence
                out.put(JSONObject()
                    .put("elementId", element.id)
                    .put("observationId", observation.id)
                    .put("label", element.label)
                    .put("semanticName", element.semanticName)
                    .put("role", element.role)
                    .put("resourceId", e.optString("resourceId"))
                    .put("contentDescription", e.optString("contentDescription"))
                    .put("bounds", e.optJSONObject("bounds") ?: JSONObject.NULL)
                    .put("actions", e.optJSONArray("androidActions") ?: e.optJSONArray("actions") ?: JSONArray())
                    .put("source", element.source)
                    .put("elementIndex", e.optInt("elementIndex", e.optInt("element_index", -1)).takeIf { it > 0 } ?: JSONObject.NULL)
                    .put("relevance", score))
            }
        }
    }

    fun element(observation: GatewayObservation, elementId: String): JSONObject {
        val embeddedObservation = elementId.split(':').getOrNull(1)
        if (embeddedObservation != null && embeddedObservation != observation.id) {
            throw GatewayProtocolException("STALE_ELEMENT", "Element ID belongs to a previous observation")
        }
        return observation.elements[elementId]?.evidence
            ?: throw GatewayProtocolException("ELEMENT_NOT_FOUND", "Element ID is not present in the current observation")
    }

    private fun observationPlane(args: JSONObject, execution: ExecutionContext): SessionPlane {
        val identity = ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
        if (args.has("workspaceId") && !identity.has("workspaceId")) identity.put("workspaceId", args.get("workspaceId"))
        if (args.has("workspaceGeneration") && !identity.has("workspaceGeneration")) {
            identity.put("workspaceGeneration", args.get("workspaceGeneration"))
        }
        if (!identity.has("sessionId")) identity.put("sessionId", execution.sessionId)
        if (!identity.has("displayId")) identity.put("displayId", execution.displayId)
        val requestedWorkspace = identity.has("workspaceId") && !identity.isNull("workspaceId") &&
            identity.optString("workspaceId").isNotBlank() && identity.optString("workspaceId") != "null"
        val holder = Layer2Workspaces.engine.holder()
        if (!requestedWorkspace &&
            holder != null &&
            execution.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID &&
            execution.displayId == ExecutionSession.DEFAULT_DISPLAY_ID
        ) {
            identity.put("workspaceId", holder.workspaceId)
            identity.put("workspaceGeneration", holder.generation)
        }
        return SessionContract.classify(identity)
    }

    private fun editableTextState(node: JSONObject?, rawTextById: Map<String, String>): String? {
        if (node == null || !node.optBoolean("editable")) return null
        val rawText = rawTextById[node.optString("id")].orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$editableStateSalt|$rawText".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The live value of an ordinary field for the on-phone Mind; null for secrets, the address bar and non-fields. */
    fun fieldValue(observation: GatewayObservation, elementId: String): String? = observation.fieldValues[elementId]

    /** Every semantic control of the observation, for agents that can read more than the page card's shortlist. */
    fun controls(observation: GatewayObservation): JSONArray = JSONArray(observation.payload.optJSONArray("semanticControls")?.toString() ?: "[]")

    /** Equality only, for the live Ask ledger. Never expose editable text or the process-local salt. */
    fun matchesObservedEmail(observation: GatewayObservation, elementId: String, email: String): Boolean {
        if (!Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}").matches(email)) return false
        val current = GatewayObservationStore.current(observation.execution) ?: return false
        if (current.id != observation.id) return false
        val evidence = observation.elements[elementId]?.evidence ?: return false
        if (!evidence.optBoolean("editable") || evidence.optBoolean("password")) return false
        val expected = MessageDigest.getInstance("SHA-256").digest("$editableStateSalt|$email".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return evidence.optString("textStateDigest") == expected
    }

    private fun bestNode(control: PageControl, nodes: JSONArray): JSONObject? {
        val selector = control.selector
        var best: JSONObject? = null
        var bestScore = -1
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            var score = 0
            val resource = selector.optString("resourceId")
            val text = selector.optString("text")
            val description = selector.optString("contentDescription")
            val role = selector.optString("role")
            if (resource.isNotBlank()) score += if (resource == node.optString("resourceId")) 8 else -5
            if (text.isNotBlank()) {
                val nodeText = normalize(node.optString("text"))
                val descText = normalize(node.optString("contentDescription"))
                val wanted = normalize(text)
                if (wanted == nodeText || wanted == descText) score += 5
            }
            if (description.isNotBlank()) score += if (normalize(description) == normalize(node.optString("contentDescription"))) 5 else 0
            if (role.isNotBlank() && role.equals(node.optString("role"), ignoreCase = true)) score += 2
            val clickable = node.optBoolean("clickable")
            val actions = node.optJSONArray("actions")
            var hasClick = clickable
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    if (actions.optString(i) == "ACTION_CLICK") hasClick = true
                }
            }
            val nodeRole = node.optString("role").lowercase(Locale.US)
            if (hasClick || nodeRole in setOf("button", "tab", "row", "textbox", "switch", "checkbox")) score += 6
            if (!hasClick && nodeRole in setOf("text", "generic")) score -= 4
            if (score > bestScore) { bestScore = score; best = node }
        }
        return best?.takeIf { bestScore > 0 }
    }

    private fun score(query: String, element: GatewayElement): Double {
        val label = normalize(element.label)
        val semantic = normalize(element.semanticName)
        val resource = normalize(element.evidence.optString("resourceId").substringAfterLast('/'))
        val description = normalize(element.evidence.optString("contentDescription"))
        val corpus = "$label $semantic $resource $description ${normalize(element.role)}"
        if (label == query || semantic == query || resource == query || description == query) return 1.0
        if (label.contains(query) || semantic.contains(query) || resource.contains(query) || description.contains(query)) return 0.92
        val tokens = query.split(' ').filter { it.isNotBlank() }.distinct()
        // Generic words should not make an unrelated Settings row look like the requested control.
        val specific = tokens.filter { it !in setOf("setting", "settings", "option", "button", "switch", "the", "to", "for") }
        val usable = specific.ifEmpty { tokens }
        if (usable.isEmpty()) return 0.0
        val matched = usable.count(corpus::contains)
        if (matched == 0 || (usable.size >= 3 && matched < 2)) return 0.0
        val ratio = matched.toDouble() / usable.size
        return (0.50 + ratio * 0.35 + if (element.source == "semantic") 0.05 else 0.0).coerceAtMost(0.89)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun semanticize(value: String): String = normalize(value).replace(' ', '_').take(100)

    private fun signature(control: PageControl): String {
        val selector = control.selector
        return buildList {
            selector.optString("resourceId").takeIf(String::isNotBlank)?.let { add("resource:$it") }
            selector.optString("text").takeIf(String::isNotBlank)?.let { add("text:${normalize(it)}") }
            selector.optString("contentDescription").takeIf(String::isNotBlank)?.let { add("desc:${normalize(it)}") }
            selector.optString("role").takeIf(String::isNotBlank)?.let { add("role:${it.lowercase(Locale.US)}") }
        }.joinToString("|")
    }

    private fun signature(node: JSONObject): String = buildList {
        node.optString("resourceId").takeIf(String::isNotBlank)?.let { add("resource:$it") }
        node.optString("text").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { add("text:${normalize(it)}") }
        node.optString("contentDescription").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { add("desc:${normalize(it)}") }
        node.optString("role").takeIf(String::isNotBlank)?.let { add("role:${it.lowercase(Locale.US)}") }
    }.joinToString("|")

    private fun supplementSelector(node: JSONObject, inheritedLabel: String = ""): JSONObject = JSONObject().apply {
        node.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
        node.optString("text").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { put("text", it.take(160)) }
        node.optString("contentDescription").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { put("contentDescription", it.take(160)) }
        inheritedLabel.takeIf { it.isNotBlank() }?.let { put("descendantText", it.take(160)) }
        node.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
        if (node.optBoolean("clickable")) put("clickable", true)
        if (node.optBoolean("editable")) put("editable", true)
        if (node.optBoolean("scrollable")) put("scrollable", true)
    }

    private fun descendantLabel(parent: JSONObject, nodes: JSONArray): String {
        val parentPath = parent.optString("path").trimEnd('/')
        if (parentPath.isBlank()) return ""
        val prefix = "$parentPath/"
        for (index in 0 until nodes.length()) {
            val candidate = nodes.optJSONObject(index) ?: continue
            if (!candidate.optBoolean("visibleToUser", true) || !candidate.optString("path").startsWith(prefix)) continue
            val label = candidate.optString("text").trim()
                .ifBlank { candidate.optString("contentDescription").trim() }
                .ifBlank { candidate.optString("resourceId").substringAfterLast('/').replace('_', ' ').trim() }
            if (label.isNotBlank() && label != "<redacted>") return label.take(160)
        }
        return ""
    }
}
