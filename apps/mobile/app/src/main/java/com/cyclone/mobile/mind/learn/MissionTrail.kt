package com.cyclone.mobile.mind.learn

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.graphv2.AtlasPrivacy
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one mission saw and did, kept so the owner can press **Learn** afterwards: every distinct screen with every
 * control on it (label, role, selector, what it can do, how risky), and every step (screen before → control → screen
 * after, and whether it worked). Structure only: no typed text, no field values, no message contents, no secrets.
 */
data class TrailControl(
    val key: String,
    val label: String,
    val semanticName: String,
    val role: String,
    val selector: JSONObject,
    val androidActions: List<String>,
    val risk: ActionRisk,
    val confidence: Double,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("label", label).put("semanticName", semanticName).put("role", role)
        .put("selector", selector).put("androidActions", JSONArray(androidActions)).put("risk", risk.name).put("confidence", confidence)

    companion object {
        fun fromJson(json: JSONObject) = TrailControl(
            json.optString("key"), json.optString("label"), json.optString("semanticName"), json.optString("role"),
            json.optJSONObject("selector") ?: JSONObject(),
            json.optJSONArray("androidActions")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            runCatching { ActionRisk.valueOf(json.optString("risk")) }.getOrDefault(ActionRisk.UNKNOWN),
            json.optDouble("confidence", 0.6),
        )
    }
}

data class TrailScreen(
    val pageKey: String,
    val packageName: String,
    val className: String?,
    val title: String,
    val structuralKey: String,
    val controls: List<TrailControl>,
    val firstSeenAt: Long,
    val sightings: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().put("pageKey", pageKey).put("package", packageName).put("class", className ?: JSONObject.NULL)
        .put("title", title).put("structuralKey", structuralKey).put("firstSeenAt", firstSeenAt).put("sightings", sightings)
        .put("controls", JSONArray().also { out -> controls.forEach { out.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject) = TrailScreen(
            json.optString("pageKey"), json.optString("package"), json.optString("class").takeUnless { json.isNull("class") || it.isBlank() },
            json.optString("title"), json.optString("structuralKey"),
            json.optJSONArray("controls")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TrailControl::fromJson) } }.orEmpty(),
            json.optLong("firstSeenAt"), json.optInt("sightings", 1),
        )
    }
}

/** One thing the Mind did. `controlKey` is null for actions without a control (open app, back, swipe, tap_point). */
data class TrailStep(
    val tool: String,
    val fromPageKey: String?,
    val controlKey: String?,
    val toPageKey: String?,
    val ok: Boolean,
    val atMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("from", fromPageKey ?: JSONObject.NULL)
        .put("control", controlKey ?: JSONObject.NULL).put("to", toPageKey ?: JSONObject.NULL).put("ok", ok).put("at", atMs)

    companion object {
        fun fromJson(json: JSONObject) = TrailStep(
            json.optString("tool"), json.optNullable("from"), json.optNullable("control"), json.optNullable("to"),
            json.optBoolean("ok"), json.optLong("at"),
        )

        private fun JSONObject.optNullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

data class MissionTrail(val missionId: String, val screens: List<TrailScreen>, val steps: List<TrailStep>) {
    fun toJson(): JSONObject = JSONObject().put("schema", SCHEMA).put("missionId", missionId)
        .put("screens", JSONArray().also { out -> screens.forEach { out.put(it.toJson()) } })
        .put("steps", JSONArray().also { out -> steps.forEach { out.put(it.toJson()) } })

    val apps: Set<String> get() = screens.map { it.packageName }.toSet()

    companion object {
        const val SCHEMA = "cyclone-mission-trail-v1"
        fun fromJson(json: JSONObject): MissionTrail? = runCatching {
            require(json.optString("schema") == SCHEMA)
            MissionTrail(
                json.getString("missionId"),
                json.optJSONArray("screens")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TrailScreen::fromJson) } }.orEmpty(),
                json.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TrailStep::fromJson) } }.orEmpty(),
            )
        }.getOrNull()
    }
}

/** Keeps only what describes the app, never what the owner or other people wrote. */
object TrailPrivacy {
    private const val MAX_LABEL = 60
    private const val MAX_WORDS = 8
    /** Cyclone's own overlay and the launcher's shortcuts are not "the app". */
    private val IGNORED_PACKAGES = setOf("com.cyclone.mobile", "com.android.systemui")

    fun keepScreen(page: PageContext): Boolean = page.packageName.isNotBlank() && page.packageName !in IGNORED_PACKAGES && page.packageName != "unknown"

    fun title(raw: String): String = AtlasPrivacy.structuralLabel(raw.take(MAX_LABEL), "Screen").let { if (it.split(' ').size > MAX_WORDS) "Screen" else it }

    /** A control is kept when its label reads like part of the app (a button, a tab), not like content. */
    fun control(control: PageControl): TrailControl? {
        val editable = control.selector.optBoolean("editable")
        val selector = JSONObject(control.selector.toString())
        // A field's text is what someone typed; the field is identified by its id, hint or role instead.
        if (editable) { selector.remove("text"); selector.remove("descendantText") }
        val label = if (editable) {
            control.selector.optString("contentDescription").ifBlank { control.selector.optString("resourceId").substringAfterLast('/').replace('_', ' ') }
                .ifBlank { "Text field" }
        } else control.label
        val clean = label.trim()
        if (clean.isBlank() || clean.length > MAX_LABEL || clean.split(Regex("\\s+")).size > MAX_WORDS) return null
        if (AtlasPrivacy.structuralLabel(clean, "").isBlank()) return null
        listOf("text", "contentDescription", "descendantText").forEach { key ->
            val value = selector.optString(key)
            if (value.isNotBlank() && (value.length > MAX_LABEL || AtlasPrivacy.structuralLabel(value, "").isBlank())) selector.remove(key)
        }
        if (selector.length() == 0 || (selector.keys().asSequence().all { it in setOf("role", "clickable", "editable", "scrollable") })) return null
        return TrailControl(control.key, clean, control.semanticName, control.role, selector, control.androidActions, control.risk, control.confidence)
    }
}

/**
 * Collects the trail while a mission runs. Bounded so a long mission never grows without limit: 80 screens, 160
 * controls per screen, 600 steps.
 */
class MindTrailRecorder(private val missionId: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val screens = linkedMapOf<String, TrailScreen>()
    private val steps = mutableListOf<TrailStep>()

    fun screen(page: PageContext?) {
        page ?: return
        if (!TrailPrivacy.keepScreen(page)) return
        synchronized(lock) {
            val known = screens[page.pageKey]
            if (known != null) {
                // The same screen can show more controls on a later look (after a scroll); keep the union.
                val merged = (known.controls + page.controls.mapNotNull(TrailPrivacy::control)).distinctBy { it.key }.take(MAX_CONTROLS)
                screens[page.pageKey] = known.copy(controls = merged, sightings = known.sightings + 1)
                return
            }
            if (screens.size >= MAX_SCREENS) return
            screens[page.pageKey] = TrailScreen(page.pageKey, page.packageName, page.className, TrailPrivacy.title(page.title),
                page.structuralKey, page.controls.mapNotNull(TrailPrivacy::control).take(MAX_CONTROLS), clock())
        }
    }

    /** Records one action. The control is matched on the screen it was taken from by label and role. */
    fun step(tool: String, before: PageContext?, label: String?, role: String?, after: PageContext?, ok: Boolean) {
        synchronized(lock) {
            if (steps.size >= MAX_STEPS) return
            val from = before?.takeIf(TrailPrivacy::keepScreen)
            val control = if (from != null && label != null) {
                from.controls.firstOrNull { it.label.equals(label, true) && (role == null || it.role.equals(role, true)) }
                    ?: from.controls.firstOrNull { it.label.equals(label, true) }
            } else null
            steps += TrailStep(tool, from?.pageKey, control?.key?.takeIf { key -> screens[from?.pageKey]?.controls?.any { it.key == key } == true },
                after?.takeIf(TrailPrivacy::keepScreen)?.pageKey, ok, clock())
        }
    }

    fun snapshot(): MissionTrail = synchronized(lock) { MissionTrail(missionId, screens.values.toList(), steps.toList()) }

    companion object {
        const val MAX_SCREENS = 80
        const val MAX_CONTROLS = 160
        const val MAX_STEPS = 600
    }
}
