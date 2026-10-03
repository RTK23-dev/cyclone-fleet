package com.cyclone.mobile.policy

/**
 * Plan 33 (C3): while a Command Center task posts a file the owner's PC made, its final "Share" / "Post" /
 * "Publish" / "Upload" tap is a SEND gate, so it waits for the owner's OK like any message. Apps name that button
 * differently ("Share" on Instagram), and a plain "Share" is not a send elsewhere, so this is on only while a posting
 * task runs.
 *
 * Plan 26 §6 (parallel sessions): several tasks can run at once. The gate stays on while **any** posting task runs,
 * for every task (gating one extra "Share" is safe; missing one is not), and starting another task never turns it off.
 */
object PublishGate {
    private val posting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** The front mission's id (the older, one-mission view); used when [running] is not installed. */
    @Volatile var liveMission: () -> String? = { null }
    /** Whether a mission is running (front or behind); the Command Center adapter installs it. */
    @Volatile var running: ((String) -> Boolean)? = null
    private val LABELS = setOf("share", "share now", "post", "post now", "publish", "publish now", "upload", "share post", "share reel", "share video")

    /** The one posting mission (the older view); setting it forgets any other. */
    var missionId: String?
        get() = posting.firstOrNull()
        set(value) {
            posting.clear()
            value?.let(posting::add)
        }

    /** A task started: [publish] turns the gate on for it; a task that does not post is never a posting one. */
    fun mark(missionId: String, publish: Boolean) {
        if (publish) {
            if (posting.size > MAX) posting.removeIf { !isRunning(it) }
            posting += missionId
        } else {
            posting -= missionId
        }
    }

    private fun isRunning(id: String): Boolean = running?.invoke(id) ?: (id == liveMission())

    fun active(): Boolean = posting.any(::isRunning)

    fun gates(label: String): Boolean {
        val normalized = label.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!')
        return normalized in LABELS && active()
    }

    private const val MAX = 20
}
