package com.cyclone.mobile.market

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Skills the owner saved from their own runs ("Save skill"). Each is an ordinary recipe listing published by "You":
 * the run's goal, the apps it used, and the same boundaries as a typed Ask. Stored beside the installs; never shared.
 */
class OwnerSkills(private val file: File) {
    private val lock = Any()

    fun list(): List<MarketListing> = synchronized(lock) { read() }

    /**
     * Saves [goal] as the owner's skill and returns it. Saving the same goal again returns the existing skill; a newer
     * [anchor] (where the run worked on the app's map) replaces the old one, which is how a skill is re-grounded.
     */
    fun save(goal: String, apps: List<String>, anchor: SkillAnchor? = null): MarketListing = synchronized(lock) {
        val listing = draft(goal, apps)
        val current = read()
        anchor?.let { writeAnchor(listing.id, it) }
        current.firstOrNull { it.id == listing.id }?.let { return it }
        write(current + listing)
        listing
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val current = read()
        val next = current.filterNot { it.id == id }
        if (next.size != current.size) write(next)
        if (next.size != current.size) anchorsWithout(id)
        next.size != current.size
    }

    /** Where the skill lives on the map; null for skills saved before alpha.39 or from runs outside any app. */
    fun anchor(id: String): SkillAnchor? = synchronized(lock) { readAnchors()[id] }

    private val anchorFile: File get() = File(file.parentFile, file.nameWithoutExtension + "-anchors.json")

    private fun readAnchors(): Map<String, SkillAnchor> = runCatching {
        val json = JSONObject(anchorFile.readText())
        json.keys().asSequence().mapNotNull { id -> SkillAnchor.fromJson(json.optJSONObject(id))?.let { id to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeAnchor(id: String, anchor: SkillAnchor) = writeAnchors(readAnchors() + (id to anchor))

    private fun anchorsWithout(id: String) = readAnchors().let { if (id in it) writeAnchors(it - id) }

    private fun writeAnchors(anchors: Map<String, SkillAnchor>) {
        anchorFile.parentFile?.mkdirs()
        val out = JSONObject().also { json -> anchors.forEach { (id, anchor) -> json.put(id, anchor.toJson()) } }
        val tmp = File(anchorFile.parentFile, anchorFile.name + ".tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(anchorFile)) { anchorFile.writeText(tmp.readText()); tmp.delete() }
    }

    private fun read(): List<MarketListing> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
            .filter { runCatching { MarketRules.validate(it) }.isSuccess }
    }.getOrDefault(emptyList())

    private fun write(items: List<MarketListing>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        val YOU = Publisher("owner", "You", verified = false)
        const val CATEGORY = "Your skills"

        /** The listing a goal becomes. Throws [MarketError] for a goal that cannot be a skill (secret-shaped, too long). */
        fun draft(goal: String, apps: List<String>): MarketListing {
            val clean = goal.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) throw MarketError("This run has no goal to save.")
            // A saved goal is replayed as-is, so braces would read as inputs; keep it literal.
            val literal = clean.replace("{", "(").replace("}", ")")
            val listing = MarketListing(
                id = "you." + sha(literal.lowercase()).take(12),
                kind = ListingKind.RECIPE, version = "1.0.0",
                name = name(literal), publisher = YOU,
                summary = if (literal.length <= 160) literal else literal.take(157).trimEnd() + "…",
                category = CATEGORY, glyph = "★", goal = literal,
                apps = apps.distinct().take(6),
                does = listOf("Runs your saved goal as a Cyclone mission", "Asks before sending, deleting or paying"),
            )
            try { MarketRules.validate(listing) } catch (error: MarketError) {
                throw MarketError(if (MarketRules.SECRET_SHAPE.containsMatchIn(literal)) "Goals that mention secrets are not saved as skills." else "This goal cannot be saved as a skill.")
            }
            return listing
        }

        private fun name(goal: String): String {
            val words = goal.trimEnd('.', '!', '?').split(' ')
            val short = words.take(6).joinToString(" ")
            val named = if (words.size > 6) "$short…" else short
            return named.take(60).replaceFirstChar { it.uppercase() }
        }

        private fun sha(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun fromJson(json: JSONObject): MarketListing? = runCatching {
            fun strings(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            MarketListing(
                id = json.getString("id"), kind = ListingKind.RECIPE, version = json.optString("version", "1.0.0"),
                name = json.getString("name"), publisher = YOU, summary = json.getString("summary"),
                category = CATEGORY, glyph = json.optString("glyph", "★"), goal = json.getString("goal"),
                apps = strings("apps"), does = strings("does"),
            )
        }.getOrNull()?.takeIf { it.id.startsWith("you.") && json.optJSONArray("inputs")?.length() in listOf(null, 0) }
    }
}
