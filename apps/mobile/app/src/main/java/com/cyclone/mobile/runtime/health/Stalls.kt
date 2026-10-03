package com.cyclone.mobile.runtime.health

import org.json.JSONArray
import org.json.JSONObject

/**
 * One time Cyclone's main thread stopped answering (a freeze the owner sees as a stuck screen and the PC sees as a
 * silent phone). [suspect] is the Cyclone code the main thread was running for most of the freeze; [frames] is one
 * representative stack, top first. Only class and method names are kept: never values, text or arguments.
 */
data class Stall(
    val startedAtMs: Long,
    val durationMs: Long,
    val suspect: String?,
    val frames: List<String>,
    val samples: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("startedAtMs", startedAtMs)
        .put("durationMs", durationMs)
        .put("suspect", suspect ?: JSONObject.NULL)
        .put("frames", JSONArray(frames))
        .put("samples", samples)

    companion object {
        fun fromJson(value: JSONObject): Stall? {
            val started = value.optLong("startedAtMs", -1)
            val duration = value.optLong("durationMs", -1)
            if (started < 0 || duration < 0) return null
            val frames = value.optJSONArray("frames")?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
                .orEmpty()
            return Stall(started, duration, value.optString("suspect").takeIf { it.isNotBlank() && it != "null" },
                frames.take(Stalls.MAX_FRAMES), value.optInt("samples", 0))
        }
    }
}

/** Pure rules for turning main-thread stack samples into a [Stall], and for the bounded list the phone keeps. */
object Stalls {
    /** A main thread that doesn't answer for this long is a freeze worth recording. */
    const val THRESHOLD_MS = 500L
    const val MAX_FRAMES = 14
    const val MAX_KEPT = 20
    private const val APP_PREFIX = "com.cyclone.mobile."

    /** Frames that only say "the main thread is waiting for work", not what froze it. */
    private val IDLE = listOf("android.os.MessageQueue.nativePollOnce", "android.os.MessageQueue.next")

    fun frame(className: String, method: String, line: Int): String =
        if (line > 0) "$className.$method:$line" else "$className.$method"

    /**
     * The freeze from the stacks sampled while it lasted. The suspect is the Cyclone frame seen in the most samples
     * (the innermost one when tied), so a freeze inside Android caused by Cyclone code still names that code.
     */
    fun of(startedAtMs: Long, durationMs: Long, samples: List<List<String>>): Stall {
        val useful = samples.filter { it.isNotEmpty() && !idle(it) }
        val counts = LinkedHashMap<String, Int>()
        useful.forEach { stack -> stack.filter { it.startsWith(APP_PREFIX) }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val best = counts.maxByOrNull { it.value }?.value
        val suspect = best?.let { top -> useful.asSequence().flatMap { it.asSequence() }.firstOrNull { counts[it] == top } }
        val representative = suspect?.let { s -> useful.firstOrNull { s in it } } ?: useful.firstOrNull() ?: samples.firstOrNull().orEmpty()
        return Stall(startedAtMs, durationMs, suspect ?: representative.firstOrNull(), representative.take(MAX_FRAMES), samples.size)
    }

    /** Newest last, at most [MAX_KEPT]. */
    fun append(kept: List<Stall>, stall: Stall): List<Stall> = (kept + stall).takeLast(MAX_KEPT)

    fun encode(stalls: List<Stall>): String = JSONArray(stalls.map { it.toJson() }).toString()

    fun decode(raw: String?): List<Stall> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(Stall::fromJson) }.takeLast(MAX_KEPT)
    }.getOrDefault(emptyList())

    private fun idle(stack: List<String>): Boolean = IDLE.any { stack.first().startsWith(it) }
}
