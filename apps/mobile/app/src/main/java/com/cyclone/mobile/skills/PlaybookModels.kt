package com.cyclone.mobile.skills

import com.cyclone.mobile.automation.skill.SkillSecrets
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * V4 Stage 3 Skill Compiler models.
 *
 * Distinct from [com.cyclone.mobile.automation.skill.SkillCompiler], which writes disabled
 * AutomationStore drafts for MCP `phone_skill_save`. This package learns NL playbooks from
 * verified Fast Path runs, compiles stable sequences into deterministic PhoneToolExecutor
 * routes, and replays them before spending an LLM turn.
 */

enum class PlaybookSource {
    FAST_PATH,
    USER_OVERRIDE,
}

enum class SkillEscalateTo {
    FAST_PATH_LLM,
    VISION,
}

enum class SkillMissReason {
    NO_MATCH,
    PAGE_MISMATCH,
    SELECTOR_MISS,
    UNCHANGED,
    AFTER_STATE_MISMATCH,
    CROSS_SESSION,
    DISPLAY_MISMATCH,
    DISPLAY_ZERO_WORKSPACE,
    GATE_REQUIRED,
    POLICY,
    SECRET_STRIPPED,
    COORDINATE_SELECTOR,
    UNSAFE_TOOL,
    VISION_ESCALATE,
    EMPTY_TREE,
}

data class SemanticSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val contentDescription: String? = null,
    val contentDescriptionContains: String? = null,
    val role: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val uri: String? = null,
) {
    init {
        require(isSemantic) { "Selector must carry at least one semantic field" }
    }

    val isSemantic: Boolean
        get() = listOf(
            resourceId, text, textContains, contentDescription, contentDescriptionContains,
            role, className, packageName, uri,
        ).any { !it.isNullOrBlank() }

    val fingerprint: String
        get() = listOf(
            resourceId.orEmpty(),
            text.orEmpty(),
            textContains.orEmpty(),
            contentDescription.orEmpty(),
            contentDescriptionContains.orEmpty(),
            role.orEmpty(),
            className.orEmpty(),
            packageName.orEmpty(),
            uri.orEmpty(),
        ).joinToString("|")

    fun toMap(): Map<String, String> = buildMap {
        resourceId?.takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
        text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
        textContains?.takeIf { it.isNotBlank() }?.let { put("textContains", it) }
        contentDescription?.takeIf { it.isNotBlank() }?.let { put("contentDescription", it) }
        contentDescriptionContains?.takeIf { it.isNotBlank() }?.let { put("contentDescriptionContains", it) }
        role?.takeIf { it.isNotBlank() }?.let { put("role", it) }
        className?.takeIf { it.isNotBlank() }?.let { put("className", it) }
        packageName?.takeIf { it.isNotBlank() }?.let { put("package", it) }
        uri?.takeIf { it.isNotBlank() }?.let { put("uri", it) }
    }

    fun toJson(): JSONObject = JSONObject().also { json ->
        toMap().forEach { (key, value) -> json.put(key, value) }
        json.put("coordinateFree", true)
    }

    fun matches(available: SemanticSelector): Boolean {
        fun same(a: String?, b: String?) = a.isNullOrBlank() || a == b
        fun contains(needle: String?, haystack: String?) =
            needle.isNullOrBlank() || (!haystack.isNullOrBlank() && haystack.contains(needle, ignoreCase = true))
        return same(resourceId, available.resourceId) &&
            same(text, available.text) &&
            contains(textContains, available.text) &&
            same(contentDescription, available.contentDescription) &&
            contains(contentDescriptionContains, available.contentDescription) &&
            same(role, available.role) &&
            same(className, available.className) &&
            same(packageName, available.packageName) &&
            same(uri, available.uri)
    }

    companion object {
        val SEMANTIC_KEYS = setOf(
            "resourceId", "text", "textContains", "contentDescription", "contentDescriptionContains",
            "role", "className", "package", "packageName", "uri",
        )
        val COORDINATE_KEYS = setOf("x", "y", "x1", "y1", "x2", "y2", "bounds")

        fun fromMap(raw: Map<String, String>): SemanticSelector? {
            val cleaned = raw.filterKeys { it in SEMANTIC_KEYS }.mapValues { it.value.trim() }.filterValues { it.isNotBlank() }
            if (cleaned.isEmpty()) return null
            return SemanticSelector(
                resourceId = cleaned["resourceId"],
                text = cleaned["text"],
                textContains = cleaned["textContains"],
                contentDescription = cleaned["contentDescription"],
                contentDescriptionContains = cleaned["contentDescriptionContains"],
                role = cleaned["role"],
                className = cleaned["className"],
                packageName = cleaned["package"] ?: cleaned["packageName"],
                uri = cleaned["uri"],
            )
        }

        fun fromJson(raw: JSONObject?): SemanticSelector? {
            if (raw == null || raw.length() == 0) return null
            if (COORDINATE_KEYS.any { raw.has(it) && raw.opt(it).toString().isNotBlank() && it !in SEMANTIC_KEYS }) {
                val onlyCoordinates = SEMANTIC_KEYS.none { key -> raw.optString(key).isNotBlank() }
                if (onlyCoordinates) return null
            }
            val map = buildMap {
                SEMANTIC_KEYS.forEach { key ->
                    raw.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it.take(180)) }
                }
            }
            return fromMap(map)
        }
    }
}

data class PlaybookHintStep(
    val nl: String,
    val tool: String,
    val selector: SemanticSelector,
    val beforePageKey: String,
    val afterPageKey: String,
    val expectedPageChange: Boolean,
    val params: Map<String, String> = emptyMap(),
) {
    init {
        require(nl.isNotBlank())
        require(tool.startsWith("phone."))
        require(beforePageKey.isNotBlank() && afterPageKey.isNotBlank())
    }

    val fingerprint: String
        get() = listOf(tool, selector.fingerprint, beforePageKey, afterPageKey, expectedPageChange.toString()).joinToString(">")

    fun toJson(): JSONObject = JSONObject()
        .put("nl", nl)
        .put("tool", tool)
        .put("selector", selector.toJson())
        .put("beforePageKey", beforePageKey)
        .put("afterPageKey", afterPageKey)
        .put("expectedPageChange", expectedPageChange)
        .put("params", JSONObject(params))
}

data class PlaybookHint(
    val packageName: String,
    val goal: String,
    val goalSignature: String,
    val startPageKey: String,
    val sessionId: String,
    val displayId: Int,
    val steps: List<PlaybookHintStep>,
    val nlPlaybook: String,
    val successCount: Int,
    val source: PlaybookSource,
    val lastSuccessAtMs: Long,
    val userOverride: Boolean = source == PlaybookSource.USER_OVERRIDE,
) {
    init {
        require(packageName.isNotBlank())
        require(goal.isNotBlank() && goalSignature.isNotBlank())
        require(startPageKey.isNotBlank())
        require(sessionId.isNotBlank())
        require(displayId >= 0)
        require(steps.size >= 2) { "A playbook needs 2+ verified steps" }
        require(successCount >= 1)
        require(nlPlaybook.isNotBlank())
        if (sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            require(displayId > 0) { "Named workspace playbooks cannot bind display 0" }
        }
    }

    val mergeKey: String
        get() = listOf(
            packageName,
            goalSignature,
            startPageKey,
            sessionId,
            displayId.toString(),
            steps.joinToString(">>") { it.fingerprint },
        ).joinToString("::")

    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("goal", goal)
        .put("goalSignature", goalSignature)
        .put("startPageKey", startPageKey)
        .put("sessionId", sessionId)
        .put("displayId", displayId)
        .put("nlPlaybook", nlPlaybook)
        .put("successCount", successCount)
        .put("source", source.name)
        .put("lastSuccessAtMs", lastSuccessAtMs)
        .put("userOverride", userOverride)
        .put("steps", JSONArray().also { array -> steps.forEach { array.put(it.toJson()) } })
}

data class CompiledSkillStep(
    val id: String,
    val nl: String,
    val tool: String,
    val selector: SemanticSelector,
    val beforePageKey: String,
    val afterPageKey: String,
    val expectedPageChange: Boolean,
    val params: Map<String, String> = emptyMap(),
) {
    fun toPhoneParams(): JSONObject {
        val out = JSONObject(params)
        out.put("selector", selector.toJson())
        selector.packageName?.let { out.put("package", it) }
        selector.uri?.let { out.put("uri", it) }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("nl", nl)
        .put("tool", tool)
        .put("selector", selector.toJson())
        .put("beforePageKey", beforePageKey)
        .put("afterPageKey", afterPageKey)
        .put("expectedPageChange", expectedPageChange)
        .put("params", JSONObject(params))
}

data class CompiledSkillRoute(
    val id: String,
    val packageName: String,
    val goal: String,
    val goalSignature: String,
    val startPageKey: String,
    val sessionId: String,
    val displayId: Int,
    val steps: List<CompiledSkillStep>,
    val nlPlaybook: String,
    val compiledFromSuccesses: Int,
    val compiledAtMs: Long,
) {
    init {
        require(id.isNotBlank())
        require(packageName.isNotBlank())
        require(steps.size >= 2)
        require(sessionId.isNotBlank())
        require(displayId >= 0)
        if (sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            require(displayId > 0) { "Compiled workspace routes cannot bind display 0" }
        }
    }

    val isWorkspace: Boolean
        get() = sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("packageName", packageName)
        .put("goal", goal)
        .put("goalSignature", goalSignature)
        .put("startPageKey", startPageKey)
        .put("sessionId", sessionId)
        .put("displayId", displayId)
        .put("nlPlaybook", nlPlaybook)
        .put("compiledFromSuccesses", compiledFromSuccesses)
        .put("compiledAtMs", compiledAtMs)
        .put("mutationEngine", "PhoneToolExecutor")
        .put("steps", JSONArray().also { array -> steps.forEach { array.put(it.toJson()) } })
}

data class SkillActOutcome(
    val ok: Boolean,
    val selectorResolved: Boolean,
    val pageChanged: Boolean,
    val fingerprintChanged: Boolean,
    val afterPageKey: String?,
    val afterPackage: String?,
    val perceptionMode: String = "a11y",
    val treeUseful: Boolean = true,
    val gateRequired: Boolean = false,
    val policyDenied: Boolean = false,
    val unchangedWarning: Boolean = false,
)

sealed interface SkillReplayResult {
    val usedLlm: Boolean
    val usedVision: Boolean

    data class Hit(
        val routeId: String,
        val stepsRun: Int,
        val nlPlaybook: String,
        val sessionId: String,
        val displayId: Int,
    ) : SkillReplayResult {
        override val usedLlm: Boolean get() = false
        override val usedVision: Boolean get() = false
    }

    data class Miss(
        val reason: SkillMissReason,
        val escalateTo: SkillEscalateTo,
        val detail: String,
        val routeId: String? = null,
        val stepsRun: Int = 0,
    ) : SkillReplayResult {
        override val usedLlm: Boolean get() = escalateTo == SkillEscalateTo.FAST_PATH_LLM
        override val usedVision: Boolean get() = escalateTo == SkillEscalateTo.VISION
    }
}

data class SkillCompileResult(
    val route: CompiledSkillRoute?,
    val rejected: String? = null,
) {
    val compiled: Boolean get() = route != null
}

object PlaybookGoal {
    fun signature(goal: String): String = goal
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun nlPlaybook(steps: List<PlaybookHintStep>): String =
        steps.joinToString(" → ") { step -> step.nl.trim() }.ifBlank { "verified Fast Path path" }
}

object PlaybookSafety {
    val SAFE_TOOLS = setOf(
        "phone.click",
        "phone.open_app",
        "phone.launch_intent",
        "phone.scroll",
        "phone.back",
        "phone.wait_for",
        "phone.type",
        "phone.replace_text",
        "phone.home",
    )
    val CONSEQUENTIAL_TOOLS = setOf(
        "phone.share",
        "phone.set_clipboard",
    )
    private val UNSAFE_GOAL = Regex(
        "(?i)\\b(pay|purchase|buy|send money|transfer|delete|uninstall|wipe|factory reset|password|otp|pin code|credit card)\\b",
    )

    fun toolAllowed(tool: String): Boolean = tool in SAFE_TOOLS

    fun goalAllowed(goal: String): Boolean = !UNSAFE_GOAL.containsMatchIn(goal)

    fun paramsSafe(params: Map<String, String>): Boolean =
        params.none { (key, value) -> SkillSecrets.isSecretKey(key) || SkillSecrets.isSecretValue(value) }

    fun stripParams(params: Map<String, String>): Map<String, String> = SkillSecrets.strip(params)

    fun workspaceDisplayLegal(sessionId: String, displayId: Int): Boolean {
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) return displayId == ExecutionSession.DEFAULT_DISPLAY_ID
        return displayId > 0
    }
}

object CompiledSkillIds {
    const val PREFIX = "compiled-skill."

    fun of(packageName: String, goalSignature: String, startPageKey: String, sessionId: String, displayId: Int): String {
        val slug = listOf(packageName, goalSignature, startPageKey, sessionId, displayId.toString()).joinToString(".") { part ->
            part.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "x" }
        }
        return PREFIX + slug
    }
}
