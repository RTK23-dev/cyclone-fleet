package com.cyclone.mobile.brain.graphv2

import java.security.MessageDigest
import java.util.Base64

enum class AtlasPersona(val wireValue: String) {
    LIVE("live"),
    MAPPING("mapping");

    companion object {
        fun fromWire(value: String): AtlasPersona =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown Atlas persona")
    }
}

enum class AtlasPlaceKind(val wireValue: String) {
    PACKAGE("package"),
    CHROME_ORIGIN("chrome-origin"),
}

enum class AtlasMapStatus(val wireValue: String) {
    UNMAPPED("unmapped"),
    PARTIAL("partial"),
    MAPPED("mapped"),
    STALE("stale"),
}

enum class AtlasDanger(val wireValue: String) {
    NONE("none"),
    AUTHENTICATION("authentication"),
    PAYMENT("payment"),
    SEND_PUBLIC("send-public"),
    DELETE_ACCOUNT("delete-account"),
    LOGOUT_ALL("logout-all"),
    PERMISSION("permission"),
    UNKNOWN("unknown"),
}

data class AtlasPlaceKey(
    val placeId: String,
    val persona: AtlasPersona,
) {
    init {
        require(placeId.startsWith("package:") || placeId.startsWith("chrome:")) {
            "Atlas place identity must use the frozen package:/chrome: form"
        }
    }
}

data class AtlasPlace(
    val id: String,
    val kind: AtlasPlaceKind,
    val label: String,
    val packageName: String? = null,
    val origin: String? = null,
    val persona: AtlasPersona,
    val mapStatus: AtlasMapStatus = AtlasMapStatus.UNMAPPED,
    val lastObservedAtEpochMillis: Long? = null,
    val lastVerifiedAtEpochMillis: Long? = null,
) {
    init {
        AtlasPlaceKey(id, persona)
        require(label.isNotBlank()) { "Atlas place label must not be blank" }
        require((kind == AtlasPlaceKind.PACKAGE) == (packageName != null)) {
            "Package places require a packageName and Chrome places must not carry one"
        }
        require((kind == AtlasPlaceKind.CHROME_ORIGIN) == (origin != null)) {
            "Chrome-origin places require an origin and package places must not carry one"
        }
        require(lastObservedAtEpochMillis == null || lastObservedAtEpochMillis >= 0L)
        require(lastVerifiedAtEpochMillis == null || lastVerifiedAtEpochMillis >= 0L)
    }

    val key: AtlasPlaceKey get() = AtlasPlaceKey(id, persona)

    companion object {
        fun packagePlace(
            packageName: String,
            label: String,
            persona: AtlasPersona,
            mapStatus: AtlasMapStatus = AtlasMapStatus.UNMAPPED,
            lastObservedAtEpochMillis: Long? = null,
            lastVerifiedAtEpochMillis: Long? = null,
        ) = AtlasPlace(
            id = "package:$packageName",
            kind = AtlasPlaceKind.PACKAGE,
            label = label,
            packageName = packageName,
            persona = persona,
            mapStatus = mapStatus,
            lastObservedAtEpochMillis = lastObservedAtEpochMillis,
            lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
        )

        fun chromeOrigin(
            origin: String,
            label: String,
            persona: AtlasPersona,
            mapStatus: AtlasMapStatus = AtlasMapStatus.UNMAPPED,
            lastObservedAtEpochMillis: Long? = null,
            lastVerifiedAtEpochMillis: Long? = null,
        ) = AtlasPlace(
            id = "chrome:$origin",
            kind = AtlasPlaceKind.CHROME_ORIGIN,
            label = label,
            origin = origin,
            persona = persona,
            mapStatus = mapStatus,
            lastObservedAtEpochMillis = lastObservedAtEpochMillis,
            lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
        )
    }
}

/**
 * Describes where/how a fact can be read later. There is intentionally no field for a captured
 * fact value. Ask-time perception must read the value from the current phone state.
 */
data class AtlasFactSlot(
    val name: String,
    val screenId: GraphNodeId,
    val selectorKey: String? = null,
    val purpose: String,
    val confidence: Double,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{1,63}"))) { "Invalid Atlas fact-slot name" }
        require(purpose.isNotBlank()) { "Fact-slot purpose must not be blank" }
        require(confidence in 0.0..1.0)
    }
}

data class AtlasScreenMetadata(
    val screenId: GraphNodeId,
    val purpose: String,
    val capabilities: Set<String> = emptySet(),
    val factSlots: List<AtlasFactSlot> = emptyList(),
    val danger: AtlasDanger = AtlasDanger.NONE,
    val confidence: Double,
    val lastObservedAtEpochMillis: Long,
    val lastVerifiedAtEpochMillis: Long? = null,
    val layoutX: Double,
    val layoutY: Double,
) {
    init {
        require(purpose.isNotBlank()) { "Atlas screen purpose must not be blank" }
        require(confidence in 0.0..1.0)
        require(lastObservedAtEpochMillis >= 0L)
        require(lastVerifiedAtEpochMillis == null || lastVerifiedAtEpochMillis >= 0L)
        require(factSlots.all { it.screenId == screenId }) { "Fact slots must belong to this screen" }
        require(layoutX.isFinite() && layoutY.isFinite()) { "Atlas layout coordinates must be finite" }
    }
}

data class AtlasEdgeMetadata(
    val key: GraphEdgeKey,
    val action: String,
    val selectorKey: String? = null,
    val danger: AtlasDanger = AtlasDanger.NONE,
    val confidence: Double,
    val lastObservedAtEpochMillis: Long,
    val lastVerifiedAtEpochMillis: Long? = null,
) {
    init {
        require(action.isNotBlank()) { "Atlas edge action must not be blank" }
        require(confidence in 0.0..1.0)
        require(lastObservedAtEpochMillis >= 0L)
        require(lastVerifiedAtEpochMillis == null || lastVerifiedAtEpochMillis >= 0L)
    }
}

data class AtlasGraphSnapshot(
    val place: AtlasPlace,
    val nodes: List<GraphNode>,
    val edges: List<TemporalKnowledgeEdge>,
    val screens: List<AtlasScreenMetadata>,
    val edgeMetadata: List<AtlasEdgeMetadata>,
)

data class AtlasPlaceSummary(
    val place: AtlasPlace,
    val screenCount: Int,
    val edgeCount: Int,
)

data class AtlasNavigationHint(
    val place: AtlasPlaceKey,
    val destinationScreen: GraphNodeId,
    val candidatePath: List<GraphNodeId>,
    val capability: String? = null,
    val factSlot: String? = null,
    val confidence: Double,
    val danger: AtlasDanger,
)

/** Stable IDs shared by legacy projection and direct Follow Me promotion. */
object AtlasGraphIds {
    fun encoded(kind: String, raw: String): GraphNodeId {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))
        return GraphNodeId("$kind:$encoded")
    }

    fun selectorDigest(rawSelector: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(rawSelector.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** The public `edgeId` used by atlas.get and atlas.diff for one navigation edge. */
    fun wireEdgeId(key: GraphEdgeKey): String =
        "edge:" + selectorDigest(key.from.value + "|" + key.type.name + "|" + key.to.value)
            .removePrefix("sha256:")

    fun stableLayout(seed: String, ordinal: Int): Pair<Double, Double> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        val lane = (digest[0].toInt() and 0xff) % 4
        val x = lane * 260.0
        val y = ordinal.coerceAtLeast(0) * 180.0
        return x to y
    }
}

object AtlasPrivacy {
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val longSecretishToken = Regex("""\b[A-Za-z0-9_\-]{24,}\b""")
    private val otpLike = Regex("""\b\d{6,8}\b""")

    /**
     * General metadata sanitizer for app labels, host labels and fact-slot descriptions. It removes
     * obvious captured values but is intentionally not sufficient for screen/control structure.
     */
    fun structuralLabel(raw: String, fallback: String): String {
        val trimmed = raw.trim().take(160)
        if (trimmed.isBlank()) return fallback
        if (containsCapturedValue(trimmed)) return fallback
        return trimmed
    }

    /**
     * Screens may be titled with a person, thread subject, order title, email subject, etc. Atlas
     * therefore keeps only a coarse structural category from a frozen UI vocabulary.
     */
    fun structuralScreenLabel(raw: String, fallback: String = "Screen"): String =
        coarseStructure(raw, fallback)

    fun structuralScreenPurpose(raw: String, fallback: String = "Learned app screen"): String =
        coarseStructure(raw, fallback)

    /**
     * Controls can also be content rows ("Louella", "Dinner plans"). Store only structural actions
     * such as Search/Menu/Compose/Settings; content rows collapse to a generic control/navigation.
     */
    fun structuralControlLabel(raw: String, fallback: String = "Control"): String =
        coarseStructure(raw, fallback)

    fun structuralPurpose(raw: String, fallback: String = "Learned app screen"): String {
        val clean = structuralLabel(raw, fallback)
        return clean.takeIf { it.isNotBlank() } ?: fallback
    }

    fun safeNode(node: GraphNode): GraphNode = when (node) {
        is AppNode -> node.copy(displayName = structuralLabel(node.displayName, node.packageName))
        is ActivityNode -> node.copy(
            className = structuralIdentity(node.className, "activity"),
            displayName = structuralLabel(node.displayName, "Activity"),
        )
        is PageNode -> node.copy(
            identity = structuralIdentity(structuralScreenLabel(node.identity, "page"), "page"),
            displayName = structuralScreenLabel(node.displayName, "Screen"),
        )
        is ElementNode -> node.copy(
            semanticName = structuralIdentity(structuralControlLabel(node.semanticName, "control"), "control"),
            displayName = structuralControlLabel(node.displayName, "Control"),
        )
        is SelectorNode -> node.copy(
            selectorKey = if (node.selectorKey.startsWith("sha256:")) node.selectorKey else selectorDigest(node.selectorKey),
            displayName = "Semantic selector",
        )
        is TransitionNode -> node.copy(
            actionName = structuralIdentity(structuralControlLabel(node.actionName, "navigate"), "navigate"),
            displayName = structuralControlLabel(node.displayName, "Navigate"),
        )
        is RoutineNode -> node.copy(
            routineId = structuralIdentity(node.routineId, "routine"),
            displayName = structuralLabel(node.displayName, "Routine"),
        )
        is CapabilityNode -> node.copy(
            capabilityId = structuralIdentity(node.capabilityId, "capability"),
            displayName = structuralLabel(node.displayName, "Capability"),
        )
    }

    private fun coarseStructure(raw: String, fallback: String): String {
        val trimmed = raw.trim().take(160)
        if (trimmed.isBlank() || containsCapturedValue(trimmed)) return fallback
        val normalized = trimmed
            .lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        return STRUCTURAL_TERMS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalized) }
            ?.second
            ?: fallback
    }

    private fun containsCapturedValue(value: String): Boolean =
        email.containsMatchIn(value) ||
            longSecretishToken.containsMatchIn(value) ||
            otpLike.matches(value)

    private fun structuralIdentity(raw: String, fallback: String): String {
        val safe = structuralLabel(raw, fallback)
        return safe.replace(Regex("[^A-Za-z0-9._:/=_-]+"), "_")
            .take(160)
            .ifBlank { fallback }
    }

    private fun selectorDigest(raw: String): String = AtlasGraphIds.selectorDigest(raw)

    private val STRUCTURAL_TERMS = listOf(
        Regex("""\baccount(?:s| switcher)?\b""") to "Account",
        Regex("""\b(sign in|log in|login|authentication|auth)\b""") to "Login",
        Regex("""\b(sign up|signup|register|registration)\b""") to "Signup",
        Regex("""\bhome\b""") to "Home",
        Regex("""\binbox\b""") to "Inbox",
        Regex("""\b(outbox|sent|drafts?)\b""") to "Mail",
        Regex("""\b(compose|new message|write message)\b""") to "Compose",
        Regex("""\b(search|find)\b""") to "Search",
        Regex("""\b(menu|navigation|nav|drawer|more options)\b""") to "Menu",
        Regex("""\b(settings?|preferences?)\b""") to "Settings",
        Regex("""\b(profile|profiles)\b""") to "Profile",
        Regex("""\b(messages?|direct messages?|dms?|chat list|chats?)\b""") to "Messages",
        Regex("""\b(thread|conversation)\b""") to "Conversation",
        Regex("""\b(notifications?|alerts?)\b""") to "Notifications",
        Regex("""\b(feed|timeline)\b""") to "Feed",
        Regex("""\b(explore|discover)\b""") to "Explore",
        Regex("""\b(orders?|order history|purchases?)\b""") to "Orders",
        Regex("""\b(cart|basket)\b""") to "Cart",
        Regex("""\b(checkout|payment)\b""") to "Checkout",
        Regex("""\b(library|downloads?|saved|favorites?|bookmarks?)\b""") to "Library",
        Regex("""\b(help|support)\b""") to "Help",
        Regex("""\b(security|privacy)\b""") to "Security",
        Regex("""\b(permissions?)\b""") to "Permissions",
        Regex("""\b(about|info|information)\b""") to "About",
        Regex("""\b(back|close|cancel|dismiss)\b""") to "Back",
        Regex("""\b(next|continue|proceed)\b""") to "Continue",
        Regex("""\b(save|done|confirm|submit)\b""") to "Confirm",
        Regex("""\b(send|post|publish)\b""") to "Send",
        Regex("""\b(delete|remove|erase)\b""") to "Delete",
        Regex("""\b(log out|logout|sign out)\b""") to "Logout",
        Regex("""\b(tab|tabs)\b""") to "Tab",
        Regex("""\b(dialog|modal|sheet)\b""") to "Dialog",
        Regex("""\b(form|field|textbox|input)\b""") to "Form",
    )
}
