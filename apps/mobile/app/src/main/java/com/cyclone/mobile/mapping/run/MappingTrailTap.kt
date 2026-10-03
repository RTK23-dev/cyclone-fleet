package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.mind.learn.MindTrailRecorder
import com.cyclone.mobile.mind.learn.MissionTrail

/**
 * One map (plan 23): what a mapping pass walks becomes the same app knowledge Learn writes, so runs can route on it.
 *
 * The mapper's Atlas stays structural (digests only, for Glass). This tap records the pass the way a Mind mission is
 * recorded: every screen it read (through [MindTrailRecorder], so the same privacy filter applies: labels that read
 * like the app, never content or typed values) and every door it pressed (screen before → control → screen after).
 * At the end of the pass the trail is learned exactly like a Learn press.
 */
class MappingTrailTap(jobId: String, clock: () -> Long = System::currentTimeMillis) {
    private val recorder = MindTrailRecorder("mapping-$jobId", clock)
    private val lock = Any()
    /** Recent observations: the page and each element's label and role, by element id. Bounded. */
    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?): Boolean = size > MAX_OBSERVATIONS
    }
    private var pending: Pending? = null
    private var presses = 0

    private data class Seen(val page: PageContext, val elements: Map<String, Pair<String, String>>)
    private data class Pending(val before: PageContext, val label: String, val role: String)

    /** A fresh observation of the phone. Closes the door pressed just before it, if any. */
    fun observed(observationId: String, page: PageContext?, elements: Map<String, Pair<String, String>>) {
        page ?: return
        synchronized(lock) {
            seen[observationId] = Seen(page, elements)
            recorder.screen(page)
            pending?.let { door -> recorder.step("phone.click", door.before, door.label, door.role, page, ok = true) }
            pending = null
        }
    }

    /** The mapper pressed [elementId] on [observationId]. The move is recorded when the next screen is read. */
    fun pressed(observationId: String, elementId: String, ok: Boolean) {
        synchronized(lock) {
            pending = null
            if (!ok) return
            val at = seen[observationId] ?: return
            val (label, role) = at.elements[elementId] ?: return
            if (label.isBlank()) return
            pending = Pending(at.page, label, role)
            presses++
        }
    }

    /** Back or a fresh open: not a door of the app, so nothing is recorded as a move. */
    fun navigated() = synchronized(lock) { pending = null }

    val doorPresses: Int get() = synchronized(lock) { presses }

    fun snapshot(): MissionTrail = synchronized(lock) { recorder.snapshot() }

    companion object {
        const val MAX_OBSERVATIONS = 32
    }
}
