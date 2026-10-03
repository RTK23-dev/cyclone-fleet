package com.cyclone.mobile.runtime.health

import org.json.JSONArray
import org.json.JSONObject

/**
 * Why Android ended Cyclone's process, from Android's own record (ApplicationExitInfo). The numbers are Android's
 * REASON_* constants; this file keeps them as plain data so the rules run in JVM tests.
 */
data class AppExit(
    val atMs: Long,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    /** For a freeze Android killed (ANR): the main thread's stack from Android's trace, top first. */
    val mainThread: List<String>,
) {
    val kind: String get() = ExitReasons.kind(reason)

    fun toJson(): JSONObject = JSONObject()
        .put("atMs", atMs)
        .put("kind", kind)
        .put("reason", reason)
        .put("status", status)
        .put("importance", importance)
        .put("pssKb", pssKb)
        .put("rssKb", rssKb)
        .put("description", description ?: JSONObject.NULL)
        .put("mainThread", JSONArray(mainThread))
        .put("unexpected", ExitReasons.unexpected(reason))
}

object ExitReasons {
    const val MAX_EXITS = 5
    const val MAX_TRACE_LINES = 24
    private const val MAX_DESCRIPTION = 160

    /** Android's ApplicationExitInfo.REASON_* values (API 30+). */
    private val KINDS = mapOf(
        0 to "unknown",
        1 to "exit_self",
        2 to "signaled",
        3 to "low_memory",
        4 to "crash",
        5 to "crash_native",
        6 to "anr",
        7 to "initialization_failure",
        8 to "permission_change",
        9 to "excessive_resource_usage",
        10 to "user_requested",
        11 to "user_stopped",
        12 to "dependency_died",
        13 to "other",
        14 to "freezer",
        15 to "package_state_change",
        16 to "package_updated",
    )

    /** Stops the owner didn't ask for: the ones worth telling them about. */
    private val UNEXPECTED = setOf("crash", "crash_native", "anr", "low_memory", "initialization_failure",
        "excessive_resource_usage", "signaled", "dependency_died", "unknown")

    fun kind(reason: Int): String = KINDS[reason] ?: "unknown"

    fun unexpected(reason: Int): Boolean = kind(reason) in UNEXPECTED

    /** Android's description can name a file or an exception message; keep it short and on one line. */
    fun description(raw: String?): String? = raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_DESCRIPTION)?.takeIf { it.isNotEmpty() }

    /**
     * The main thread's frames from an ANR trace ("\"main\" prio=5 tid=1 …" then "  at a.b.C.m(File.kt:12)" lines).
     * Only `at` frames are kept, as class.method:line, so no locals, values or other threads leave the phone.
     */
    fun mainThread(trace: String?): List<String> {
        if (trace.isNullOrBlank()) return emptyList()
        val lines = trace.lineSequence().iterator()
        while (lines.hasNext()) {
            if (lines.next().trimStart().startsWith("\"main\"")) break
        }
        val frames = mutableListOf<String>()
        while (lines.hasNext() && frames.size < MAX_TRACE_LINES) {
            val line = lines.next().trim()
            if (line.isEmpty() || line.startsWith("\"")) break
            FRAME.find(line)?.let { match ->
                val lineNo = match.groupValues[3].toIntOrNull() ?: 0
                frames += Stalls.frame(match.groupValues[1], match.groupValues[2], lineNo)
            }
        }
        return frames
    }

    private val FRAME = Regex("^at ([\\w$.]+)\\.([\\w$<>-]+)\\((?:[^:)]*:(\\d+)|[^)]*)\\)")
}
