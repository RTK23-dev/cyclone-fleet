package com.cyclone.mobile.mapping.crawl

import android.content.Context
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.ai.LoginAutofillPolicy
import com.cyclone.mobile.agent.tools.ObservationProjections
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.gateway.GatewayObservationAdapter
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.places.PlaceResolver
import com.cyclone.mobile.secrets.SecretFillTarget
import com.cyclone.mobile.secrets.SecretPersona
import com.cyclone.mobile.secrets.SecretRequestMetadata
import com.cyclone.mobile.secrets.SecretUseResult
import com.cyclone.mobile.secrets.SecretsPhoneFacade
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Real Run-2 observation adapter over the existing phone-owned gateway capture path.
 *
 * The returned projection deliberately drops ordinary labels/content. Labels are consulted only
 * transiently to classify a control into a frozen structural enum; durable keys/fingerprints are
 * built from resource/class/role/path structure instead.
 */
class GatewayMappingObservationPort(
    context: Context,
    /** One map: the pass's trail, learned into app knowledge when the pass ends (plan 23). */
    private val trail: com.cyclone.mobile.mapping.run.MappingTrailTap? = null,
) : MappingObservationPort {
    private val appContext = context.applicationContext
    @Volatile private var failure: String? = null

    override fun lastFailure(): String? = failure

    override fun freshObservation(session: MappingSessionSnapshot): MappingObservation? {
        // A screen that keeps changing is retried with longer quiet waits; a real failure keeps its cause (plan 20).
        val (observation, cause) = ObservationRetry.run({ attempt -> captureOnce(session, attempt) })
        failure = cause
        return observation
    }

    private fun captureOnce(session: MappingSessionSnapshot, attempt: ObservationRetry.Attempt): MappingObservation =
        run {
            val args = JSONObject()
                .put("sessionId", session.sessionId)
                .put("displayId", session.displayId)
                .apply {
                    session.workspaceId?.let { put("workspaceId", it) }
                    session.workspaceGeneration?.let { put("workspaceGeneration", it) }
                    session.executionGeneration?.let { put("executionGeneration", it) }
                    if (attempt.settleMaxMs > 0) put("settleQuietMs", attempt.settleQuietMs).put("settleMaxMs", attempt.settleMaxMs)
                    if (attempt.tolerateContentChange) put("tolerateContentChange", true)
                }
            val captured = GatewayObservationAdapter.capture(appContext, args, AtlasPersona.MAPPING)
            val page = ObservationProjections.pageCard(captured, "", captured.generation, actionable = true)
            runCatching {
                trail?.observed(captured.id, captured.page, captured.elements.mapValues { (_, element) -> element.label to element.role })
            }
            val observation = MappingStructuralProjection.fromGateway(captured)
                .copy(inPlace = PlaceResolver.matchesCurrent(page, session.placeId))
            // The app dictionary (plan 36 §7): only the app's own words survive the reader; it never fails the pass.
            if (observation.inPlace) {
                com.cyclone.mobile.manual.ManualRuntime.observe(appContext, session.jobId, session.placeId,
                    StructuralRoomClassifier.nodeKey(observation), captured, observation)
            }
            // Map deeper (plan 36 §5.5): doors whose words fit an unanswered goal are tried first. Safety is unchanged.
            val focus = runCatching { com.cyclone.mobile.manual.ManualRuntime.focusDoors(session.jobId, captured, observation) }.getOrDefault(emptySet())
            if (focus.isEmpty()) observation
            else observation.copy(doors = observation.doors.map { if (it.key in focus) it.copy(focus = true) else it })
        }
}

/**
 * The structural room key of what is on screen now, as the mapper would record it. Ask uses it to
 * say "you are here" on the Atlas sketch. No labels or values leave this function.
 */
internal object CurrentRoom {
    fun key(sessionId: String): String? = runCatching {
        val current = GatewayObservationStore.current(sessionId) ?: return null
        StructuralRoomClassifier.nodeKey(MappingStructuralProjection.fromGateway(current))
    }.getOrNull()
}

internal fun MappingStructuralProjection.fromGateway(
    captured: com.cyclone.mobile.gateway.GatewayObservation,
): MappingObservation {
    val raw = captured.elements.values.map { element ->
        RawMappingElement(
            elementId = element.id,
            label = element.label,
            semanticName = element.semanticName,
            role = element.role,
            resourceId = element.evidence.optString("resourceId"),
            className = element.evidence.optString("class"),
            path = element.evidence.optString("path"),
            clickable = element.evidence.optBoolean("clickable"),
            editable = element.evidence.optBoolean("editable"),
            iconOnly = element.evidence.optString("text").let { it.isBlank() || it == "<redacted>" } &&
                element.evidence.optString("contentDescription").let { it.isNotBlank() && it != "<redacted>" },
            enabled = element.evidence.optBoolean("enabled", true),
            visible = element.evidence.optBoolean("visibleToUser", true),
        )
    }
    return project(
        observationId = captured.id,
        sessionId = captured.execution.sessionId,
        displayId = captured.execution.displayId,
        rawFingerprint = captured.payload.optString("accessibilityFingerprint").ifBlank { captured.id },
        elements = raw,
    )
}

/**
 * Backtracking and entry navigation through the sole mutation authority. `phone.back` and
 * `phone.open_app` keep PhoneToolExecutor's session checks, overlay yield and settle.
 */
class PhoneToolMappingNavigationPort(
    context: Context,
    private val trail: com.cyclone.mobile.mapping.run.MappingTrailTap? = null,
) : MappingNavigationPort {
    private val appContext = context.applicationContext
    private val sequence = AtomicLong(0)

    override fun back(session: MappingSessionSnapshot): MappingMutationResult =
        run(session, "phone.back", JSONObject())

    override fun openPlace(session: MappingSessionSnapshot, resetToEntry: Boolean): MappingMutationResult {
        val packageName = session.placeId.removePrefix("package:")
        if (!session.placeId.startsWith("package:") || packageName.isBlank()) {
            return MappingMutationResult(false, false, "PLACE_NOT_LAUNCHABLE")
        }
        return run(
            session,
            "phone.open_app",
            JSONObject().put("package", packageName).put("clearTask", resetToEntry),
        )
    }

    private fun run(session: MappingSessionSnapshot, tool: String, params: JSONObject): MappingMutationResult {
        trail?.navigated()
        params.put("sessionId", session.sessionId).put("displayId", session.displayId)
        session.workspaceId?.let { params.put("workspaceId", it) }
        session.workspaceGeneration?.let { params.put("workspaceGeneration", it) }
        session.executionGeneration?.let { params.put("executionGeneration", it) }
        val result = PhoneToolExecutor.execute(
            appContext,
            PhoneToolRequest(
                commandId = "mapping-nav-${session.jobId}-${sequence.incrementAndGet()}",
                tool = tool,
                params = params,
            ),
        )
        return MappingMutationResult(
            performed = result.ok,
            verifiedByExecutor = result.ok,
            errorCode = result.error?.code?.name,
        )
    }
}

/**
 * Re-resolves the exact current element and delegates consequential-action classification to the
 * existing phone GATE. The crawler never decides pay/send/delete/grant from its durable door key.
 */
class ExistingGateMappingSafetyPort(private val identity: MappingIdentity? = null) : MappingSafetyPort {
    override fun classify(observation: MappingObservation, door: MappingDoor): MappingDanger {
        val current = GatewayObservationStore.current(observation.sessionId)
            ?: return MappingDanger.REVIEW_BOUNDARY
        if (current.id != observation.observationId || current.execution.displayId != observation.displayId) {
            return MappingDanger.REVIEW_BOUNDARY
        }
        val element = current.elements[door.elementId] ?: return MappingDanger.REVIEW_BOUNDARY
        val labels = buildList {
            element.label.takeIf(String::isNotBlank)?.let(::add)
            element.semanticName.takeIf(String::isNotBlank)?.let(::add)
            element.evidence.optString("contentDescription").takeIf(String::isNotBlank)?.let(::add)
            element.evidence.optString("resourceId").takeIf(String::isNotBlank)?.let(::add)
        }
        val normalized = labels.joinToString(" ").lowercase(Locale.US)
        // Mapper safety v2: toggles, rows holding a toggle, state-changing and security doors are never tapped.
        val bounds = element.evidence.optJSONObject("bounds")
        val containsCheckable = bounds != null && current.elements.values.any { other ->
            other.id != element.id && (other.evidence.optBoolean("checkable") || other.role.lowercase(Locale.US) in setOf("switch", "checkbox", "radiobutton", "togglebutton")) &&
                other.evidence.optJSONObject("bounds")?.let { inner -> contains(bounds, inner) } == true
        }
        MapperDoorRisk.classify(
            MapperDoorRisk.Facts(labels, element.role, element.evidence.optBoolean("checkable"), containsCheckable),
            identity,
        )?.let { return it }
        if (LOGOUT_ALL.containsMatchIn(normalized)) return MappingDanger.LOGOUT_ALL
        if (LOGOUT.containsMatchIn(normalized)) return MappingDanger.REVIEW_BOUNDARY

        return when (GateClassifier.classify("phone.click", labels)) {
            GateClass.PAY -> MappingDanger.PAY
            GateClass.SEND -> MappingDanger.SEND_PUBLIC
            GateClass.DELETE -> MappingDanger.DELETE
            GateClass.GRANT -> MappingDanger.GRANT
            null -> MappingDanger.NONE
        }
    }

    private fun contains(outer: JSONObject, inner: JSONObject): Boolean =
        inner.optInt("left") >= outer.optInt("left") && inner.optInt("right") <= outer.optInt("right") &&
            inner.optInt("top") >= outer.optInt("top") && inner.optInt("bottom") <= outer.optInt("bottom") &&
            inner.optInt("right") > inner.optInt("left")

    private companion object {
        val LOGOUT_ALL = Regex("""\b(log\s*out|sign\s*out|logout|signout)\s+(?:of\s+)?all\b""")
        val LOGOUT = Regex("""\b(log\s*out|sign\s*out|logout|signout)\b""")
    }
}

/**
 * Executes one grounded click through the existing sole mutation authority.
 *
 * PhoneToolExecutor already owns mutation serialization, session/display checks, GATE interception,
 * settle behavior and stale-observation grounding. This adapter never injects a second gesture path.
 */
class PhoneToolMappingMutationPort(
    context: Context,
    private val trail: com.cyclone.mobile.mapping.run.MappingTrailTap? = null,
) : MappingMutationPort {
    private val appContext = context.applicationContext
    private val sequence = AtomicLong(0)

    override fun execute(
        session: MappingSessionSnapshot,
        action: MappingAction,
    ): MappingMutationResult {
        val params = JSONObject()
            .put("sessionId", session.sessionId)
            .put("displayId", session.displayId)
            .put("observationId", action.observationId)
            .put("elementId", action.elementId)
            .put("selector", JSONObject().put("elementId", action.elementId))
            .put("humanize", "auto")
            .apply {
                session.workspaceId?.let { put("workspaceId", it) }
                session.workspaceGeneration?.let { put("workspaceGeneration", it) }
                session.executionGeneration?.let { put("executionGeneration", it) }
            }
        val request = PhoneToolRequest(
            commandId = "mapping-${session.jobId}-${sequence.incrementAndGet()}",
            tool = "phone.click",
            params = params,
        )
        val result = PhoneToolExecutor.execute(appContext, request)
        runCatching { trail?.pressed(action.observationId, action.elementId, result.ok) }
        return MappingMutationResult(
            performed = result.ok,
            verifiedByExecutor = result.ok,
            errorCode = result.error?.code?.name,
        )
    }
}

/**
 * Real Run-1 Vault bridge. The mapper sees only a slot request and an observation-scoped target;
 * the secret value remains inside Vault/PhoneToolExecutor.
 *
 * Agent 004 may supply [onResolution] to resume its authoritative job after a verified one-shot
 * fill. Skip/Cancel remain nonterminal because Run-1 reports taskMayResume=false for them.
 */
class Run1MappingSecretsPort(
    context: Context,
    private val onResolution: (SecretUseResult) -> Unit = {},
) : MappingSecretsPort {
    private val appContext = context.applicationContext

    override fun detect(
        session: MappingSessionSnapshot,
        observation: MappingObservation,
    ): MappingSecretWall? {
        val current = GatewayObservationStore.current(observation.sessionId) ?: return null
        if (current.id != observation.observationId || current.execution.displayId != observation.displayId) return null
        val page = ObservationProjections.pageCard(current, "", current.generation, actionable = true)
        if (!PlaceResolver.matchesCurrent(page, session.placeId)) return null
        if (!LoginAutofillPolicy.isLoginWall(current.page)) return null

        val password = current.elements.values.firstOrNull { element ->
            element.evidence.optBoolean("editable") &&
                (element.evidence.optBoolean("password") ||
                    PASSWORD_HINT.containsMatchIn(
                        listOf(
                            element.label,
                            element.semanticName,
                            element.evidence.optString("resourceId"),
                            element.evidence.optString("contentDescription"),
                        ).joinToString(" "),
                    ))
        } ?: return null

        return MappingSecretWall(
            placeId = session.placeId,
            slot = "password",
            reason = "Login required",
            elementId = password.id,
            observationId = current.id,
            sessionId = current.execution.sessionId,
            displayId = current.execution.displayId,
        )
    }

    override fun request(wall: MappingSecretWall) {
        val request = SecretRequestMetadata(
            placeId = wall.placeId,
            persona = SecretPersona.MAPPING,
            slot = wall.slot,
            reason = wall.reason,
        )
        SecretsPhoneFacade.requestForRun(
            context = appContext,
            request = request,
            target = SecretFillTarget(
                elementId = wall.elementId,
                observationId = wall.observationId,
                sessionId = wall.sessionId,
                displayId = wall.displayId,
            ),
            onResolution = onResolution,
        )
    }

    private companion object {
        val PASSWORD_HINT = Regex("""(?i)\b(password|passcode|passwd)\b""")
    }
}

internal data class RawMappingElement(
    val elementId: String,
    val label: String = "",
    val semanticName: String = "",
    val role: String = "",
    val resourceId: String = "",
    val className: String = "",
    val path: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** An icon button: no visible text, only its description ("Add photos and files", "More options"). */
    val iconOnly: Boolean = false,
)

/**
 * Content-resistant structural projection used by the production observation port and unit tests.
 */
internal object MappingStructuralProjection {
    fun project(
        observationId: String,
        sessionId: String,
        displayId: Int,
        rawFingerprint: String,
        elements: List<RawMappingElement>,
    ): MappingObservation {
        val visible = elements.filter { it.visible }
        val candidates = visible
            .filter { it.clickable && it.enabled && !it.editable }
            .map { element -> element to classifyDoor(element) }

        var sampleAssigned = false
        val doors = candidates.mapNotNull { (element, initialKind) ->
            var kind = initialKind
            if (kind == MappingDoorKind.CONTENT_ROW && !sampleAssigned && isSampleShape(element)) {
                kind = MappingDoorKind.STRUCTURAL_SAMPLE
                sampleAssigned = true
            }
            if (element.elementId.isBlank()) return@mapNotNull null
            MappingDoor(
                elementId = element.elementId,
                observationId = observationId,
                key = doorKey(kind, element),
                kind = kind,
                regionKey = regionKey(element),
                enabled = element.enabled,
                visible = element.visible,
            )
        }

        val purpose = inferPurpose(visible, doors)
        val structuralFingerprint = digest(
            visible
                .map(::structuralToken)
                .sorted()
                .joinToString("\n"),
        )
        return MappingObservation(
            observationId = observationId,
            sessionId = sessionId,
            displayId = displayId,
            fingerprint = rawFingerprint.ifBlank { "observation:$observationId" },
            structuralFingerprint = structuralFingerprint,
            fresh = true,
            purpose = purpose,
            doors = doors,
        )
    }

    private fun classifyDoor(element: RawMappingElement): MappingDoorKind {
        val role = normalize(element.role)
        val id = normalize(element.resourceId)
        val structuralRole = role in setOf(
            "button", "imagebutton", "link", "tab", "tabitem", "menuitem",
            "action", "navigation", "toolbar",
        )
        val text = if (structuralRole) {
            normalize(listOf(element.semanticName, element.label).joinToString(" "))
        } else {
            ""
        }
        val signal = "$id $role $text"
        return when {
            role.contains("tab") || TAB.containsMatchIn(signal) -> MappingDoorKind.TAB
            DRAWER.containsMatchIn(signal) -> MappingDoorKind.NAV_DRAWER
            MENU.containsMatchIn(signal) -> MappingDoorKind.MENU
            SETTINGS.containsMatchIn(signal) -> MappingDoorKind.SETTINGS
            SEARCH.containsMatchIn(signal) -> MappingDoorKind.SEARCH
            ACCOUNT.containsMatchIn(signal) -> MappingDoorKind.ACCOUNT
            BACK.containsMatchIn(signal) -> MappingDoorKind.BACK
            HOME.containsMatchIn(signal) -> MappingDoorKind.HOME
            revealer(element, role, id) -> MappingDoorKind.REVEAL
            else -> MappingDoorKind.CONTENT_ROW
        }
    }

    /**
     * A control that opens a panel: an icon button or a floating button whose description or id says add, more,
     * attach, create, new or options. A row of text ("Add to cart", a person) is never a revealer: text rows stay
     * content, and what a revealer offers is judged again by [MapperDoorRisk] before any tap.
     */
    private fun revealer(element: RawMappingElement, role: String, id: String): Boolean {
        val floating = FLOATING.containsMatchIn(normalize(element.className)) || FLOATING.containsMatchIn(id)
        if (!element.iconOnly && !floating && role != "imagebutton") return false
        val words = normalize(element.label.ifBlank { element.semanticName })
        if (words.split(' ').size > 5) return false
        return REVEAL.containsMatchIn("$id $words") || floating
    }

    private fun inferPurpose(
        elements: List<RawMappingElement>,
        doors: List<MappingDoor>,
    ): StructuralScreenPurpose {
        val visibleRoles = elements.map { normalize(it.role) }
        val kinds = doors.mapTo(linkedSetOf()) { it.kind }
        return when {
            visibleRoles.any { it.contains("password") } -> StructuralScreenPurpose.LOGIN
            MappingDoorKind.NAV_DRAWER in kinds -> StructuralScreenPurpose.MENU
            visibleRoles.any { it in setOf("list", "recyclerview", "listview") } ||
                MappingDoorKind.STRUCTURAL_SAMPLE in kinds -> StructuralScreenPurpose.LIST
            else -> StructuralScreenPurpose.UNKNOWN
        }
    }

    private fun doorKey(kind: MappingDoorKind, element: RawMappingElement): String =
        "door:${kind.name.lowercase()}:${digest(selectorToken(element)).take(16)}"

    private fun regionKey(element: RawMappingElement): String =
        "region:${digest(listOf(normalize(element.className), normalize(element.role), normalizedPath(element.path))
            .joinToString("|")).take(16)}"

    private fun structuralToken(element: RawMappingElement): String = listOf(
        normalize(element.resourceId),
        normalize(element.className),
        normalize(element.role),
        normalizedPath(element.path),
        if (element.clickable) "clickable" else "",
        if (element.editable) "editable" else "",
    ).joinToString("|")

    private fun selectorToken(element: RawMappingElement): String = listOf(
        normalize(element.resourceId),
        normalize(element.className),
        normalize(element.role),
        normalizedPath(element.path),
    ).joinToString("|")

    private fun isSampleShape(element: RawMappingElement): Boolean {
        val role = normalize(element.role)
        return role in setOf("listitem", "row", "cell", "item") ||
            normalize(element.className).contains("recycler") ||
            normalize(element.path).isNotBlank()
    }

    private fun normalizedPath(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("""\d+"""), "#")
            .take(180)

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.US).replace('_', ' ').replace('-', ' ')
            .replace(Regex("""\s+"""), " ")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val TAB = Regex("""\btab(?:s)?\b""")
    private val DRAWER = Regex("""\b(drawer|navigation drawer|nav drawer)\b""")
    private val MENU = Regex("""\b(menu|more options|navigation)\b""")
    private val SETTINGS = Regex("""\b(settings?|preferences?)\b""")
    private val SEARCH = Regex("""\b(search|find)\b""")
    private val ACCOUNT = Regex("""\b(account|profile)\b""")
    private val BACK = Regex("""\b(back|close|dismiss)\b""")
    private val HOME = Regex("""\bhome\b""")
    private val REVEAL = Regex("""\b(add|plus|more|overflow|options|attach|attachment|attachments|create|new|compose|actions|expand|share sheet)\b""")
    private val FLOATING = Regex("""floatingactionbutton|\bfab\b|extended fab""")
}

/**
 * Where a run step happened, for the run inspector (run record v2): the structural room key, the app and its
 * installed version. Structural only: no labels, text or values. Chrome pages report the browser package.
 */
internal object StepLocation {
    /** V5 `atlas.here`: the app and structural room on the foreground screen now (Glass Phone page). */
    fun here(context: android.content.Context, sessionId: String = "default-foreground"): org.json.JSONObject {
        val current = runCatching { GatewayObservationStore.current(sessionId) }.getOrNull()
        val room = current?.let { runCatching { StructuralRoomClassifier.nodeKey(MappingStructuralProjection.fromGateway(it)) }.getOrNull() }
        val pkg = current?.page?.packageName?.takeIf { PACKAGE.matches(it) }
        return org.json.JSONObject()
            .put("placeId", pkg?.let { "package:$it" } ?: org.json.JSONObject.NULL)
            .put("roomId", room ?: org.json.JSONObject.NULL)
            .put("appVersion", pkg?.let { versionOf(context, it) } ?: org.json.JSONObject.NULL)
            .put("observedAt", current?.capturedAt ?: org.json.JSONObject.NULL)
    }

    private val versions = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun detail(context: android.content.Context, sessionId: String, after: Boolean): String? = runCatching {
        val current = GatewayObservationStore.current(sessionId) ?: return null
        val room = StructuralRoomClassifier.nodeKey(MappingStructuralProjection.fromGateway(current))
        if (after) return "roomAfter=$room"
        val pkg = current.page.packageName.takeIf { PACKAGE.matches(it) }
        listOfNotNull(
            "room=$room",
            pkg?.let { "place=package:$it" },
            pkg?.let { name -> versionOf(context, name)?.let { "appv=$it" } },
        ).joinToString(" · ")
    }.getOrNull()

    private fun versionOf(context: android.content.Context, packageName: String): String? =
        versions[packageName] ?: runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()?.replace(Regex("[^A-Za-z0-9._+-]"), "")?.take(40)?.takeIf { it.isNotBlank() }
            ?.also { versions[packageName] = it }

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}
