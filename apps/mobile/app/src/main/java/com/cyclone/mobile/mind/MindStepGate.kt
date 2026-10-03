package com.cyclone.mobile.mind

import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The step boundary of a mission (plan 25 §4.2). Every phone tool runs inside [step]; a plane switch calls [pause],
 * which waits for the running tool to finish and then holds the next one until [resume]. Not tied to a thread, so the
 * switch may run on any thread; it must not be called from inside a step (the Mind thread calls plane hooks outside).
 */
class MindStepGate {
    private val permit = Semaphore(1, true)
    private val held = AtomicBoolean(false)

    val paused: Boolean get() = held.get()

    fun <T> step(block: () -> T): T {
        permit.acquire()
        try {
            return block()
        } finally {
            permit.release()
        }
    }

    /** Waits up to [timeoutMs] for the running step to finish; true when the Mind is now held at a boundary. */
    fun pause(timeoutMs: Long): Boolean {
        if (held.get()) return true
        if (!permit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return false
        held.set(true)
        return true
    }

    /** Lets the Mind take its next step. Safe to call when not paused. */
    fun resume() {
        if (held.compareAndSet(true, false)) permit.release()
    }
}

/**
 * How a mission's plane (main screen or a background screen) shows up to the toolbox. Hooks run on the Mind thread
 * outside the step gate, so they may switch planes (which rebinds the toolbox) before or after a tool.
 */
interface MindPlanes {
    /**
     * Before a phone tool. May move the mission to the plane the tool needs. Returns a text for the model when the
     * tool must not run as asked (it is then not run), or null to run it.
     */
    fun before(tool: String, arguments: JSONObject): String? = null

    /** After a phone tool: its result can be a signal (a protected screen, an app that left, a scope refusal). */
    fun after(tool: String, result: MindToolResult) {}
}
