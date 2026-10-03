package com.cyclone.mobile.ui.overlay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Yield Cyclone overlay windows for an already-authorized host
 * [android.accessibilityservice.AccessibilityService.dispatchGesture].
 *
 * TYPE_ACCESSIBILITY_OVERLAY still receives accessibility gestures when it is visible and
 * important-for-accessibility, even with FLAG_NOT_TOUCHABLE. The idle ball and expanded Ask
 * card must leave the hit tree (GONE + not important) for the stroke, then come back.
 * Overlay buttons still never click host nodes. Stop is unusable only for that stroke.
 */
object OverlayGesturePassthrough {
    private val depth = AtomicInteger(0)
    @Volatile private var enabled = false
    @Volatile private var applyFlags: ((Boolean) -> Unit)? = null

    fun active(): Boolean = enabled

    /**
     * Alpha 91: when Cyclone's own injected gesture last ended. A touch on Cyclone's buttons during a gesture or just
     * after it is that gesture landing on Cyclone's glass (the panel could not step aside in time), never the owner:
     * a Mind run paused itself that way and then sat idle until it timed out.
     */
    @Volatile private var lastEndedAtMs = 0L

    fun injectedRecently(windowMs: Long = INJECTED_ECHO_MS, nowMs: Long = System.nanoTime() / 1_000_000): Boolean =
        enabled || nowMs - lastEndedAtMs in 0..windowMs

    const val INJECTED_ECHO_MS = 700L

    /**
     * Alpha.78: Cyclone's other windows (Drive's voice panel and button) yield to an injected gesture too, so a swipe or
     * tap Cyclone makes lands on the app, never on its own glass. A follower returns once its windows let touches through.
     */
    private val followers = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun follow(listener: (Boolean) -> Unit): () -> Unit {
        followers += listener
        return { followers -= listener }
    }

    fun bind(applyFlags: (Boolean) -> Unit) {
        this.applyFlags = applyFlags
    }

    fun unbind() {
        applyFlags = null
    }

    fun <T> withHostPassthrough(block: () -> T): T {
        val entered = depth.getAndIncrement() == 0
        if (entered) {
            enabled = true
            applyFlags?.invoke(true)
            followers.forEach { runCatching { it(true) } }
        }
        try {
            return block()
        } finally {
            if (depth.decrementAndGet() == 0) {
                lastEndedAtMs = System.nanoTime() / 1_000_000
                enabled = false
                applyFlags?.invoke(false)
                followers.forEach { runCatching { it(false) } }
            }
        }
    }

    internal fun resetForTests() {
        lastEndedAtMs = 0L
        depth.set(0)
        enabled = false
        applyFlags = null
        followers.clear()
    }
}
