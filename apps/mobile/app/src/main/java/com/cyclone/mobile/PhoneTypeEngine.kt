package com.cyclone.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Observation-scoped phone.type / phone.replace_text planning, live ACTION_SET_TEXT, and
 * after-state verification. Typed plaintext never appears in plans, reports, or messages.
 *
 * Type is ID-only: text/fuzzy/bounds/coordinate selectors are rejected. Android policy remains
 * authoritative; user_authorized=true is required at this boundary.
 */
object PhoneTypeEngine {
    /** Plan 21 (Hands): long drafts are delivered by paste; set-text stays the first try up to [PASTE_FIRST_CHARS]. */
    const val MAX_VALUE_CHARS = 20_000
    const val PASTE_FIRST_CHARS = 4_000
    private const val REDACTED = "<redacted>"
    private val FORBIDDEN_SELECTOR_KEYS = setOf(
        "text", "textContains", "contentDescription", "contentDescriptionContains",
        "fuzzyText", "x", "y", "bounds", "relativeToText", "relativeDirection",
        "ancestorText", "descendantText", "resourceId", "class", "role",
    )
    private val SENSITIVE_HINT = Regex(
        "(?i)(?:^|[^a-z0-9])(password|passcode|passwd|secret|otp|one.?time|verification.?code|" +
            "cvv|cvc|card.?number|pan|iban|ssn|pin|payment|credit.?card|cardholder|expiry)(?:$|[^a-z0-9])",
    )

    data class ObservationElementInput(
        val elementId: String,
        val source: String,
        val role: String,
        val evidence: JSONObject,
    )

    data class CatalogElement(
        val elementId: String,
        val observationId: String,
        val rawNodeId: String?,
        val path: String?,
        val role: String,
        val className: String,
        val resourceId: String,
        val contentDescription: String,
        val editable: Boolean,
        val focused: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
        val password: Boolean,
        val actions: List<String>,
    )

    data class Catalog(
        val observationId: String,
        val elements: Map<String, CatalogElement>,
    )

    data class ExecutePlan(
        val elementId: String,
        val rawNodeId: String,
        val path: String,
        val needsFocus: Boolean,
        val valueLength: Int,
        val valueDigest: String,
    )

    data class Deny(
        val code: PhoneToolErrorCode,
        val message: String,
    )

    sealed class Decision {
        data class Execute(val plan: ExecutePlan) : Decision()
        data class Reject(val deny: Deny) : Decision()
    }

    data class LiveView(
        val rawNodeId: String,
        val path: String,
        val editable: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val textLength: Int,
        val textDigest: String,
        val actions: List<String>,
        val password: Boolean = false,
    )

    interface LiveHost {
        fun resolve(plan: ExecutePlan): Any?
        fun view(handle: Any, redactText: Boolean = false): LiveView?
        fun focus(handle: Any): Boolean
        fun click(handle: Any): Boolean
        fun setText(handle: Any, value: CharSequence): Boolean
        fun matchesText(handle: Any, value: CharSequence): Boolean = false
        fun refresh(handle: Any): Any?
        /** The field's current text for an in-process read-back (never exported); "" while it shows its hint; null if unreadable. */
        fun readText(handle: Any): CharSequence? = null
        /** Deliver [value] through the clipboard and ACTION_PASTE, replacing the field's text. False when not possible. */
        fun paste(handle: Any, value: CharSequence): Boolean = false
        /** Alpha 93: a short wait for a field that shows its new text a frame late (Settings search). Off the main thread. */
        fun settle() {}
    }

    data class LiveResult(
        val ok: Boolean,
        val error: PhoneToolError? = null,
        val focusRecovered: Boolean = false,
        val setTextPerformed: Boolean = false,
        val afterStateVerified: Boolean = false,
        val charCount: Int = 0,
        val textDigest: String? = null,
        val elementId: String? = null,
        val rawNodeId: String? = null,
        /** How the text went in: set_text or paste (plan 21). */
        val method: String = "set_text",
        /** The field was read back and holds exactly the text (normalised). False: Cyclone could not prove it. */
        val textVerified: Boolean = false,
    ) {
        fun toPayload(): JSONObject = JSONObject()
            .put("performed", ok)
            .put("setText", setTextPerformed)
            .put("action", if (method == "paste") "ACTION_PASTE" else "ACTION_SET_TEXT")
            .put("method", method)
            .put("textVerified", textVerified)
            .put("focusRecovered", focusRecovered)
            .put("afterStateVerified", afterStateVerified)
            .put("charCount", charCount)
            .put("textDigest", textDigest ?: JSONObject.NULL)
            .put("elementId", elementId ?: JSONObject.NULL)
            .put("rawNodeId", rawNodeId ?: JSONObject.NULL)
            .put("value", REDACTED)
    }

    fun catalog(
        observationId: String?,
        evidenceElements: List<ObservationElementInput>,
        snapshot: UiSnapshot?,
    ): Catalog {
        val obsId = observationId.orEmpty()
        val nodesByRawId = snapshot?.nodes?.associateBy { it.id } ?: emptyMap()
        val elements = linkedMapOf<String, CatalogElement>()

        evidenceElements.forEach { input ->
            val evidence = input.evidence
            val rawId = evidence.opt("rawNodeId").let { raw ->
                when {
                    raw == null || raw == JSONObject.NULL -> null
                    else -> raw.toString().takeIf { it.isNotBlank() }
                }
            } ?: evidence.optString("id").takeIf { it.isNotBlank() && input.source == "raw_accessibility" }
            val snap = rawId?.let(nodesByRawId::get) ?: matchSnapshot(evidence, snapshot)
            val path = evidence.optString("path").ifBlank { snap?.path.orEmpty() }.takeIf { it.isNotBlank() }
            val actions = jsonStringList(evidence.optJSONArray("androidActions") ?: evidence.optJSONArray("actions"))
                .ifEmpty { snap?.actions ?: emptyList() }
            elements[input.elementId] = CatalogElement(
                elementId = input.elementId,
                observationId = obsId,
                rawNodeId = rawId ?: snap?.id,
                path = path,
                role = input.role.ifBlank { snap?.role.orEmpty() },
                className = evidence.optString("class").ifBlank { snap?.className.orEmpty() },
                resourceId = evidence.optString("resourceId").ifBlank { snap?.resourceId.orEmpty() },
                contentDescription = evidence.optString("contentDescription").ifBlank { snap?.contentDescription.orEmpty() },
                editable = if (evidence.has("editable")) evidence.optBoolean("editable") else snap?.editable == true,
                focused = if (evidence.has("focused")) evidence.optBoolean("focused") else snap?.focused == true,
                focusable = if (evidence.has("focusable")) evidence.optBoolean("focusable") else snap?.focusable == true,
                enabled = if (evidence.has("enabled")) evidence.optBoolean("enabled") else snap?.enabled != false,
                password = if (evidence.has("password")) evidence.optBoolean("password") else snap?.password == true,
                actions = actions,
            )
        }

        snapshot?.nodes?.forEach { node ->
            val scoped = if (obsId.isNotBlank()) "raw:$obsId:${node.id}" else node.id
            if (scoped !in elements) elements[scoped] = fromSnapshot(node, obsId)
        }

        return Catalog(obsId, elements)
    }

    fun decide(params: JSONObject, catalog: Catalog?): Decision {
        if (!isUserAuthorized(params)) {
            return reject(PhoneToolErrorCode.POLICY_DENIED, "phone.type requires user_authorized=true")
        }
        forbiddenSelectorReason(params)?.let { reason ->
            return reject(PhoneToolErrorCode.INVALID_REQUEST, reason)
        }
        val value = typedValue(params)
        if (value == null) {
            return reject(PhoneToolErrorCode.INVALID_REQUEST, "value is required")
        }
        if (value.length > MAX_VALUE_CHARS) {
            return reject(PhoneToolErrorCode.INVALID_REQUEST, "value exceeds the bounded type length")
        }
        if (elementId(params).isNullOrBlank() && params.optBoolean("focused") && catalog != null) return decideFocused(value, catalog)
        val elementId = elementId(params)
        if (elementId.isNullOrBlank()) {
            return reject(
                PhoneToolErrorCode.INVALID_REQUEST,
                "phone.type requires a current observation-scoped elementId",
            )
        }
        if (catalog == null) {
            return reject(PhoneToolErrorCode.STALE_ELEMENT, "A current observation is required before typing")
        }
        val requestedObservation = params.optString("currentObservationId").takeIf { it.isNotBlank() }
        if (requestedObservation != null && catalog.observationId.isNotBlank() && requestedObservation != catalog.observationId) {
            return reject(PhoneToolErrorCode.STALE_ELEMENT, "Element ID belongs to a previous observation")
        }
        val embeddedObservation = observationIdFromElementId(elementId)
        if (embeddedObservation != null && catalog.observationId.isNotBlank() && embeddedObservation != catalog.observationId) {
            return reject(PhoneToolErrorCode.STALE_ELEMENT, "Element ID belongs to a previous observation")
        }
        val element = catalog.elements[elementId]
            ?: return reject(PhoneToolErrorCode.STALE_ELEMENT, "Element ID is not present in the current observation")
        if (!element.enabled) {
            return reject(PhoneToolErrorCode.ACTION_FAILED, "Editable target is disabled")
        }
        if (!element.editable) {
            return reject(PhoneToolErrorCode.INVALID_REQUEST, "Target is not an editable field")
        }
        if (isSensitiveField(element)) {
            return reject(PhoneToolErrorCode.POLICY_DENIED, "Typing into a sensitive field is denied by Android policy")
        }
        val rawNodeId = element.rawNodeId
        val path = element.path
        if (rawNodeId.isNullOrBlank() || path.isNullOrBlank()) {
            return reject(PhoneToolErrorCode.STALE_ELEMENT, "Editable target is missing a live accessibility path")
        }
        return Decision.Execute(
            ExecutePlan(
                elementId = element.elementId,
                rawNodeId = rawNodeId,
                path = path,
                needsFocus = !element.focused,
                valueLength = value.length,
                valueDigest = digest(value),
            ),
        )
    }

    fun perform(
        plan: ExecutePlan,
        value: CharSequence,
        host: LiveHost,
        redactObservedText: Boolean = false,
    ): LiveResult {
        if (digest(value) != plan.valueDigest || value.length != plan.valueLength) {
            return fail(plan, PhoneToolErrorCode.INTERNAL_ERROR, "Type plan does not match the authorized value")
        }
        var handle = host.resolve(plan)
            ?: return fail(plan, PhoneToolErrorCode.STALE_ELEMENT, "Live editable node could not be resolved")
        var view = host.view(handle, redactObservedText)
            ?: return fail(plan, PhoneToolErrorCode.STALE_ELEMENT, "Live editable node has no current view")
        if (!view.editable) {
            return fail(plan, PhoneToolErrorCode.INVALID_REQUEST, "Target is not an editable field")
        }
        if (!view.enabled) {
            return fail(plan, PhoneToolErrorCode.ACTION_FAILED, "Editable target is disabled")
        }

        var focusRecovered = false
        if (!view.focused) {
            focusRecovered = host.focus(handle)
            handle = host.refresh(handle) ?: handle
            view = host.view(handle, redactObservedText) ?: view
            if (!view.focused) {
                if (host.click(handle)) {
                    focusRecovered = true
                    handle = host.refresh(handle) ?: handle
                    host.focus(handle)
                    handle = host.refresh(handle) ?: handle
                    view = host.view(handle, redactObservedText) ?: view
                }
            } else {
                focusRecovered = true
            }
        }

        val beforeDigest = view.textDigest
        val beforeLength = view.textLength
        // Plan 21 (Hands): the executor owns delivery. Set-text, then read the field back; when it does not hold the
        // text, paste it and read again. Long drafts go straight to paste. Secret fields keep the set-text-only path.
        val order = when {
            redactObservedText -> listOf("set_text")
            value.length > PASTE_FIRST_CHARS -> listOf("paste", "set_text")
            else -> listOf("set_text", "paste")
        }
        var set = false
        var method = order.first()
        var afterHandle = handle
        var after: LiveView? = null
        var strict = false
        for (attempt in order) {
            val performed = if (attempt == "paste") host.paste(afterHandle, value) else host.setText(afterHandle, value)
            if (!performed && attempt == "paste") continue
            set = set || performed
            method = attempt
            afterHandle = host.refresh(afterHandle) ?: afterHandle
            after = host.view(afterHandle, redactObservedText)
            fun holds(view: LiveView?, at: Any) = !redactObservedText && view != null &&
                ((view.textDigest == plan.valueDigest && view.textLength == plan.valueLength) ||
                    host.readText(at)?.let { normalized(it) == normalized(value) } == true)
            strict = holds(after, afterHandle)
            if (!strict && performed && !redactObservedText) {
                // Alpha 93: read once more after a moment before calling the text missing (or pasting over it).
                host.settle()
                afterHandle = host.refresh(afterHandle) ?: afterHandle
                after = host.view(afterHandle, redactObservedText) ?: after
                strict = holds(after, afterHandle)
            }
            if (strict) break
        }
        val stillEditableFocused = after != null && after.editable && after.focused
        val textChanged = after != null && (after.textDigest != beforeDigest || after.textLength != beforeLength)
        val unchangedAsLabel = after != null && after.textDigest == beforeDigest && after.textLength == beforeLength
        val exactRedactedMatch = redactObservedText && after != null && host.matchesText(afterHandle, value)
        val maskedPasswordFilled = redactObservedText &&
            after?.password == true &&
            beforeLength != after.textLength &&
            after.textLength == value.length
        // Legacy acceptance (a field whose accessibility text never reflects its content) stays possible, but it is
        // reported as unverified text, never as proof that the text is in.
        val verified = strict || stillEditableFocused && if (redactObservedText) {
            exactRedactedMatch || maskedPasswordFilled
        } else {
            textChanged || unchangedAsLabel
        }
        if (!set) {
            return LiveResult(
                ok = false,
                error = PhoneToolError(PhoneToolErrorCode.ACTION_FAILED, "ACTION_SET_TEXT was rejected"),
                focusRecovered = focusRecovered,
                setTextPerformed = false,
                afterStateVerified = false,
                charCount = after?.textLength ?: 0,
                textDigest = after?.textDigest,
                elementId = plan.elementId,
                rawNodeId = plan.rawNodeId,
                method = method,
            )
        }
        if (!verified) {
            return LiveResult(
                ok = false,
                error = PhoneToolError(
                    PhoneToolErrorCode.ASSERTION_FAILED,
                    "The text was sent to the field but the field does not hold it",
                ),
                focusRecovered = focusRecovered,
                setTextPerformed = true,
                afterStateVerified = false,
                charCount = after?.textLength ?: 0,
                textDigest = after?.textDigest,
                elementId = plan.elementId,
                rawNodeId = plan.rawNodeId,
                method = method,
            )
        }
        return LiveResult(
            ok = true,
            focusRecovered = focusRecovered,
            setTextPerformed = true,
            afterStateVerified = true,
            charCount = if (redactObservedText) 0 else plan.valueLength,
            textDigest = if (redactObservedText) REDACTED else plan.valueDigest,
            elementId = plan.elementId,
            rawNodeId = plan.rawNodeId,
            method = method,
            textVerified = strict || exactRedactedMatch,
        )
    }

    /** Fields may trim, fold line endings or add invisible marks; the owner's text is the same text. */
    fun normalized(text: CharSequence): String = text.toString()
        .replace("\r\n", "\n").replace(Regex("[\u200B-\u200D\uFEFF]"), "")
        .replace(Regex("[ \t]+"), " ").trim()

    /**
     * Plan 21 (Hands): type into the one text box that has input focus, as a person does after tapping it. It must be
     * editable, enabled, not a password and not sensitive-looking; several focused representations of one node count once.
     */
    private fun decideFocused(value: String, catalog: Catalog): Decision {
        val focused = catalog.elements.values.filter { it.focused && it.editable && !it.rawNodeId.isNullOrBlank() && !it.path.isNullOrBlank() }
            .distinctBy { it.rawNodeId }
        val element = when (focused.size) {
            0 -> return reject(PhoneToolErrorCode.STALE_ELEMENT, "No text box has focus. Tap the box first, then type with focused=true.")
            1 -> focused.single()
            else -> return reject(PhoneToolErrorCode.STALE_ELEMENT, "More than one text box reports focus; type into one by its ref.")
        }
        if (!element.enabled) return reject(PhoneToolErrorCode.ACTION_FAILED, "Editable target is disabled")
        if (isSensitiveField(element)) return reject(PhoneToolErrorCode.POLICY_DENIED, "Typing into a sensitive field is denied by Android policy")
        return Decision.Execute(ExecutePlan(element.elementId, element.rawNodeId!!, element.path!!, needsFocus = false,
            valueLength = value.length, valueDigest = digest(value)))
    }

    fun isUserAuthorized(params: JSONObject): Boolean {
        if (params.optBoolean("user_authorized", false)) return true
        if (params.optBoolean("userAuthorized", false)) return true
        val selector = params.optJSONObject("selector")
        return selector?.optBoolean("user_authorized", false) == true
    }

    fun elementId(params: JSONObject): String? {
        params.optString("elementId").takeIf { it.isNotBlank() }?.let { return it }
        val selector = params.optJSONObject("selector") ?: return null
        return selector.optString("elementId").ifBlank { selector.optString("id") }.takeIf { it.isNotBlank() }
    }

    fun typedValue(params: JSONObject): String? {
        val value = params.optString("value")
        if (value.isNotEmpty() || params.has("value")) return value
        val text = params.optString("text")
        if (params.has("text") && params.optJSONObject("selector")?.has("text") != true) return text
        return null
    }

    fun digest(value: CharSequence): String {
        val encoded = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(value))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } finally {
            bytes.fill(0)
        }
    }

    fun duplicateSignature(tool: String, params: JSONObject): String {
        val id = elementId(params).orEmpty()
        val value = typedValue(params).orEmpty()
        return "$tool|$id|${digest(value)}|${params.optBoolean("user_authorized", false)}"
    }

    fun redactedParams(params: JSONObject): JSONObject {
        val out = JSONObject()
        elementId(params)?.let { out.put("elementId", it) }
        params.optJSONObject("selector")?.let { selector ->
            val safe = JSONObject()
            if (selector.has("elementId")) safe.put("elementId", selector.optString("elementId"))
            if (selector.has("id")) safe.put("id", selector.optString("id"))
            out.put("selector", safe)
        }
        if (params.has("value") || params.has("text")) out.put("value", REDACTED)
        if (params.has("text")) out.put("text", REDACTED)
        out.put("user_authorized", isUserAuthorized(params))
        listOf("retries", "waitForChangeMs", "timeoutMs", "currentObservationId").forEach { key ->
            if (params.has(key)) out.put(key, params.opt(key))
        }
        return out
    }

    fun reportContainsPlaintext(report: String, value: String): Boolean =
        value.isNotEmpty() && report.contains(value)

    internal fun observationIdFromElementId(elementId: String): String? {
        val parts = elementId.split(':')
        if (parts.size >= 3 && parts[0] in setOf("semantic", "raw")) return parts[1]
        return null
    }

    private fun forbiddenSelectorReason(params: JSONObject): String? {
        val selector = params.optJSONObject("selector") ?: return null
        val forbidden = FORBIDDEN_SELECTOR_KEYS.filter(selector::has)
        if (forbidden.isEmpty()) return null
        return "phone.type does not accept text, fuzzy, bounds, or coordinate selectors"
    }

    private fun isSensitiveField(element: CatalogElement): Boolean {
        if (element.password) return true
        val hints = listOf(element.resourceId, element.contentDescription, element.role, element.className)
            .joinToString(" ")
        return SENSITIVE_HINT.containsMatchIn(hints)
    }

    private fun matchSnapshot(evidence: JSONObject, snapshot: UiSnapshot?): UiNodeSnapshot? {
        snapshot ?: return null
        val path = evidence.optString("path")
        if (path.isNotBlank()) {
            snapshot.nodes.firstOrNull { it.path == path }?.let { return it }
        }
        val resourceId = evidence.optString("resourceId")
        if (resourceId.isBlank()) return null
        val wantEditable = if (evidence.has("editable")) evidence.optBoolean("editable") else true
        val matches = snapshot.nodes.filter { it.resourceId == resourceId && it.editable == wantEditable }
        return matches.singleOrNull() ?: matches.firstOrNull()
    }

    private fun fromSnapshot(node: UiNodeSnapshot, observationId: String): CatalogElement = CatalogElement(
        elementId = if (observationId.isNotBlank()) "raw:$observationId:${node.id}" else node.id,
        observationId = observationId,
        rawNodeId = node.id,
        path = node.path,
        role = node.role,
        className = node.className,
        resourceId = node.resourceId,
        contentDescription = node.contentDescription,
        editable = node.editable,
        focused = node.focused,
        focusable = node.focusable,
        enabled = node.enabled,
        password = node.password,
        actions = node.actions,
    )

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun reject(code: PhoneToolErrorCode, message: String): Decision =
        Decision.Reject(Deny(code, message))

    private fun fail(plan: ExecutePlan, code: PhoneToolErrorCode, message: String): LiveResult = LiveResult(
        ok = false,
        error = PhoneToolError(code, message),
        elementId = plan.elementId,
        rawNodeId = plan.rawNodeId,
    )
}
