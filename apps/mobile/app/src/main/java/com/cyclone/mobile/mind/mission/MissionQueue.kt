package com.cyclone.mobile.mind.mission

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plan 26 (A42-8): a second request while a mission runs. Words that correct or add to the running mission steer it
 * (as before); a request that is clearly a separate task waits as "Runs next" and starts when the mission ends.
 * Persisted, so it survives a restart; at most [CAPACITY] waiting.
 */
class MissionQueue(private val file: File) {
    data class Next(val id: String, val goal: String, val queuedAtMs: Long)

    private val lock = Any()

    fun all(): List<Next> = synchronized(lock) { read() }

    fun add(goal: String, now: Long = System.currentTimeMillis()): Next? = synchronized(lock) {
        val list = read()
        if (list.size >= CAPACITY || goal.isBlank()) return null
        val next = Next("q" + now.toString(36) + list.size, goal.trim().take(2_000), now)
        write(list + next)
        next
    }

    fun take(): Next? = synchronized(lock) {
        val list = read()
        val first = list.firstOrNull() ?: return null
        write(list.drop(1))
        first
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val list = read()
        write(list.filterNot { it.id == id })
        list.any { it.id == id }
    }

    private fun read(): List<Next> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i -> array.getJSONObject(i).let { Next(it.getString("id"), it.getString("goal"), it.optLong("at")) } }
    }.getOrDefault(emptyList())

    private fun write(list: List<Next>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { array -> list.forEach { array.put(JSONObject().put("id", it.id).put("goal", it.goal).put("at", it.queuedAtMs)) } }.toString())
    }

    companion object {
        const val CAPACITY = 5
        private val NEW_TASK = Regex(
            "(?i)(^|\\b)(in the background|after (this|that|you're done|you are done)|afterwards|when you('| a)re done|" +
                "next task|new task|then also|later|op de achtergrond|daarna|hierna|nieuwe taak)\\b")
        private val STEER = Regex("(?i)^\\s*(no|stop|wait|instead|actually|not that|use|try|nee|stop|wacht|niet)\\b")

        /** Clearly a separate task, not a correction of the running one. */
        fun isNewTask(text: String): Boolean = !STEER.containsMatchIn(text) && NEW_TASK.containsMatchIn(text)
    }
}
