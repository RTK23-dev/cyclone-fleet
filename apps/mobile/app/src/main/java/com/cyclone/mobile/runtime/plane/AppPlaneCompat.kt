package com.cyclone.mobile.runtime.plane

import org.json.JSONObject
import java.io.File

/** What Cyclone knows about running an app in the background (plan 25 §4.4). */
enum class BackgroundCompat(val wire: String, val needsScreen: Boolean, val why: String) {
    UNKNOWN("unknown", false, "has not been tried in the background yet"),
    OK("ok", false, "works in the background"),
    SECURE("secure", true, "shows a protected screen that can't be seen in the background"),
    REFUSES("refuses", true, "closes itself on a background screen"),
    UNSTABLE("unstable", true, "has not worked reliably in the background"),
    ;

    companion object {
        fun fromWire(raw: String?): BackgroundCompat = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
    }
}

/** How one background attempt ended, for [AppPlaneCompat.record]. */
enum class PlaneOutcome { WORKED, SECURE_CONTENT, LEFT_DISPLAY, SWITCH_FAILED }

data class CompatRecord(val status: BackgroundCompat, val worked: Int, val failed: Int, val lastReason: String?, val updatedAtMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("status", status.wire).put("worked", worked).put("failed", failed)
        .put("lastReason", lastReason ?: JSONObject.NULL).put("updatedAt", updatedAtMs)

    companion object {
        fun fromJson(json: JSONObject): CompatRecord = CompatRecord(BackgroundCompat.fromWire(json.optString("status")),
            json.optInt("worked"), json.optInt("failed"), json.optString("lastReason").takeUnless { json.isNull("lastReason") || it.isBlank() },
            json.optLong("updatedAt"))
    }
}

/**
 * Per app and version: does it work in the background? Written by every switch, rollback and escalation; read by
 * [PlanePolicy] so an app that needs the screen is a one-time discovery. A new app version starts from what the
 * previous version showed, except that a success on the new version clears an old failure.
 */
class AppPlaneCompat(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    fun status(packageName: String, version: String?): BackgroundCompat = synchronized(lock) {
        val all = read()
        (all[key(packageName, version)] ?: all.entries.filter { it.key.startsWith("$packageName|") }.maxByOrNull { it.value.updatedAtMs }?.value)
            ?.status ?: seed(packageName)
    }

    fun record(packageName: String, version: String?, outcome: PlaneOutcome, reason: String? = null): CompatRecord = synchronized(lock) {
        val all = read().toMutableMap()
        val k = key(packageName, version)
        val before = all[k] ?: CompatRecord(BackgroundCompat.UNKNOWN, 0, 0, null, 0)
        val next = when (outcome) {
            PlaneOutcome.WORKED -> before.copy(status = BackgroundCompat.OK, worked = before.worked + 1)
            PlaneOutcome.SECURE_CONTENT -> before.copy(status = BackgroundCompat.SECURE, failed = before.failed + 1, lastReason = reason)
            PlaneOutcome.LEFT_DISPLAY -> before.copy(status = BackgroundCompat.REFUSES, failed = before.failed + 1, lastReason = reason)
            PlaneOutcome.SWITCH_FAILED -> {
                val failed = before.failed + 1
                // One failed switch can be bad luck; two without a success in between mark the app unstable.
                val status = if (failed - before.worked >= 2 && before.status != BackgroundCompat.OK) BackgroundCompat.UNSTABLE else before.status
                before.copy(status = status, failed = failed, lastReason = reason)
            }
        }.copy(updatedAtMs = clock())
        all[k] = next
        write(all)
        next
    }

    /** Owner override from the pill's long-press ("Always run this app in the background"). */
    fun allow(packageName: String, version: String?) = synchronized(lock) {
        val all = read().toMutableMap()
        all[key(packageName, version)] = (all[key(packageName, version)] ?: CompatRecord(BackgroundCompat.UNKNOWN, 0, 0, null, 0))
            .copy(status = BackgroundCompat.OK, lastReason = "Allowed by you", updatedAtMs = clock())
        write(all)
    }

    fun all(): Map<String, CompatRecord> = synchronized(lock) { read() }

    // ---- Plan 26 (A42-4): the owner's per-app choice, and whether the app opens a second window ------------------

    private val choicesFile = File(file.parentFile, "app-plane-choices.json")

    /** The owner's choice for [packageName]; banking, camera and game apps start as "always on my screen". */
    fun override(packageName: String): PlaneOverride = synchronized(lock) {
        readChoices().optJSONObject(packageName)?.optString("override")?.let(PlaneOverride::fromWire) ?: seedOverride(packageName)
    }

    fun setOverride(packageName: String, choice: PlaneOverride) = synchronized(lock) {
        val all = readChoices()
        all.put(packageName, (all.optJSONObject(packageName) ?: JSONObject()).put("override", choice.wire))
        writeChoices(all)
    }

    /** Apps the owner chose for, or that Cyclone met, with their current choice. */
    fun choices(): Map<String, PlaneOverride> = synchronized(lock) {
        val all = readChoices()
        val seen = all.keys().asSequence().toSet() + read().keys.map { it.substringBefore('|') }
        seen.associateWith { override(it) }
    }

    /** Null until Cyclone has tried to open a second window of this app on a background screen. */
    fun secondWindow(packageName: String): Boolean? = synchronized(lock) {
        readChoices().optJSONObject(packageName)?.takeIf { it.has("secondWindow") }?.optBoolean("secondWindow")
    }

    fun recordSecondWindow(packageName: String, worked: Boolean) = synchronized(lock) {
        val all = readChoices()
        all.put(packageName, (all.optJSONObject(packageName) ?: JSONObject()).put("secondWindow", worked))
        writeChoices(all)
    }

    private fun readChoices(): JSONObject = runCatching { JSONObject(choicesFile.readText()) }.getOrDefault(JSONObject())

    private fun writeChoices(json: JSONObject) {
        choicesFile.parentFile?.mkdirs()
        val tmp = File(choicesFile.parentFile, choicesFile.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(choicesFile)) { choicesFile.writeText(tmp.readText()); tmp.delete() }
    }

    private fun key(packageName: String, version: String?) = "$packageName|${version ?: "?"}"

    private fun read(): Map<String, CompatRecord> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { CompatRecord.fromJson(json.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    private fun write(all: Map<String, CompatRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().also { out -> all.forEach { (k, v) -> out.put(k, v.toJson()) } }.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        private val MONEY = Regex("(?i)(bank|banking|\\.ing\\.|rabo|abnamro|knab|bunq|revolut|paypal|wallet|finance|invest|broker|crypto)")

        /** Money apps start on the owner's screen: they approve what they see. Cameras and games cannot run behind it. */
        fun seedOverride(packageName: String): PlaneOverride =
            if (MONEY.containsMatchIn(packageName) || seed(packageName).needsScreen) PlaneOverride.SCREEN else PlaneOverride.AUTO

        /** Classes of apps that need the screen before anyone tries: cameras and games (known virtual-display trouble). */
        fun seed(packageName: String): BackgroundCompat {
            val p = packageName.lowercase()
            return if (p.contains(".camera") || p.endsWith("camera") || p.contains(".game") || p.startsWith("com.supercell.") ||
                p.startsWith("com.mojang.")) BackgroundCompat.REFUSES else BackgroundCompat.UNKNOWN
        }
    }
}
