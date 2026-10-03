package com.cyclone.mobile.agent.settle

import org.json.JSONObject

/**
 * Is the screen ready after a page-changing action? One answer for every caller, built from evidence rather than a
 * fixed sleep. The controller only captures and waits; it never taps, so "unchanged" is never answered with a second
 * click (Fast Path invariant).
 */
enum class SettleState(val wire: String) {
    /** A capture was refused because the window changed mid-capture, or the screen still changes between captures. */
    MOVING("moving"),
    /** Spinner, progress bar, "Loading…", splash or an empty app surface. */
    LOADING("loading"),
    /** The expected after-state is on screen and stable. */
    READY("ready"),
    /** Identical to the before-state with no sign of loading. */
    UNCHANGED("unchanged"),
    /** Something changed, but not into the expected after-state, and nothing is still happening. */
    OTHER("other"),
}

/** A privacy-safe summary of one capture: no labels leave the phone through this type. */
data class SettleSample(
    val packageName: String,
    val fingerprint: String,
    val actionableControls: Int,
    val loadingEvidence: Boolean,
)

data class SettleOutcome<T>(
    val value: T?,
    val state: SettleState,
    val waitedMs: Long,
    val captures: Int,
    val captureErrors: Int,
    /** True when the controller waited past the fast ladder because the screen showed it was still busy. */
    val extended: Boolean,
    val reason: String,
) {
    val ready: Boolean get() = state == SettleState.READY
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.wire).put("waitedMs", waitedMs).put("captures", captures)
        .put("captureErrors", captureErrors).put("extended", extended).put("reason", reason)
}

object ScreenStateClassifier {
    private val LOADING_TEXT = Regex(
        "(?i)^\\s*(loading|loading\\.\\.\\.|loading…|please wait|one moment|just a moment|even geduld|laden|bezig met laden|" +
            "signing in|connecting|preparing|opening)\\b",
    )
    private val LOADING_ID = Regex("(?i)progress|loading|spinner|shimmer|skeleton|splash")
    private const val CYCLONE_PACKAGE = "com.cyclone.mobile"

    /** Loading evidence from the element list of one capture: roles, resource ids and short status texts. */
    fun loadingEvidence(
        packageName: String, elements: List<Triple<String, String, String>>, actionableControls: Int, launch: Boolean,
    ): Boolean {
        // elements: (role, resourceId, label)
        if (elements.any { (role, _, _) -> role == "progress" }) return true
        if (elements.any { (_, resourceId, _) -> resourceId.isNotBlank() && LOADING_ID.containsMatchIn(resourceId.substringAfterLast('/')) }) return true
        if (elements.any { (_, _, label) -> label.length <= 40 && LOADING_TEXT.containsMatchIn(label) }) return true
        // A launching app shows its splash (logo only) before its first actionable control. After an ordinary tap an
        // empty surface can be the real page, so this signal is used for launches only.
        return launch && packageName.isNotBlank() && packageName != CYCLONE_PACKAGE && actionableControls == 0
    }

    fun classify(
        before: String?, previous: SettleSample?, current: SettleSample?, captureFailed: Boolean, targetReached: Boolean,
        requireStable: Boolean = true,
    ): SettleState = when {
        captureFailed || current == null -> SettleState.MOVING
        targetReached && current.loadingEvidence -> SettleState.LOADING
        targetReached && !requireStable -> SettleState.READY
        targetReached && previous != null && previous.fingerprint == current.fingerprint -> SettleState.READY
        targetReached -> SettleState.MOVING // reached but not yet stable: one more look
        current.loadingEvidence -> SettleState.LOADING
        previous != null && previous.fingerprint != current.fingerprint -> SettleState.MOVING
        !before.isNullOrBlank() && current.fingerprint == before -> SettleState.UNCHANGED
        else -> SettleState.OTHER
    }
}

data class SettleBudget(
    /** The ordinary window every page-changing action gets (Fast Path ladder plus launch slack). */
    val fastMs: Long = 1_800L,
    /** How long to keep waiting while the screen shows it is busy. */
    val extendedMs: Long = 8_000L,
    val pollMs: Long = 150L,
    val extendedPollMs: Long = 250L,
)

object SettleController {
    /**
     * Capture until the expected after-state is ready and stable, the screen stops showing activity, or the budget
     * runs out. A capture that throws counts as "still moving" (a launch animation changes the window signature).
     * Returns the last successful capture even when it is not ready, so callers can still reason about it.
     */
    fun <T> run(
        beforeFingerprint: String?,
        budget: SettleBudget,
        capture: () -> T?,
        sample: (T) -> SettleSample,
        targetReached: (T) -> Boolean,
        requireStable: Boolean = true,
        nowMs: () -> Long = System::currentTimeMillis,
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
        cancelled: () -> Boolean = { false },
    ): SettleOutcome<T> {
        val started = nowMs()
        var captures = 0
        var errors = 0
        var last: T? = null
        var lastSample: SettleSample? = null
        var state = SettleState.MOVING
        var extended = false
        while (true) {
            val value = runCatching { capture() }.getOrNull()
            captures += 1
            if (value == null) errors += 1
            val current = value?.let(sample)
            val reached = value != null && runCatching { targetReached(value) }.getOrDefault(false)
            state = ScreenStateClassifier.classify(beforeFingerprint, lastSample, current, value == null, reached, requireStable)
            if (value != null) {
                last = value
                lastSample = current
            }
            val elapsed = nowMs() - started
            if (state == SettleState.READY) return outcome(last, state, elapsed, captures, errors, extended, "expected after-state is stable")
            if (cancelled()) return outcome(last, state, elapsed, captures, errors, extended, "cancelled")
            val busy = state == SettleState.MOVING || state == SettleState.LOADING
            if (elapsed >= budget.fastMs) {
                if (!busy) return outcome(last, state, elapsed, captures, errors, extended,
                    if (state == SettleState.UNCHANGED) "screen did not change" else "screen stopped changing")
                if (elapsed >= budget.fastMs + budget.extendedMs) return outcome(last, state, elapsed, captures, errors, extended,
                    "still ${state.wire} when the wait budget ran out")
                extended = true
            }
            sleepMs(if (extended) budget.extendedPollMs else budget.pollMs)
        }
    }

    private fun <T> outcome(value: T?, state: SettleState, elapsed: Long, captures: Int, errors: Int, extended: Boolean, reason: String) =
        SettleOutcome(value, state, elapsed, captures, errors, extended, reason)
}

/** Last settle per session so the agent can put a WAIT event in the run trace (numbers only). */
object SettleRecorder {
    private val last = java.util.concurrent.ConcurrentHashMap<String, SettleOutcome<*>>()
    fun record(sessionId: String, outcome: SettleOutcome<*>) { last[sessionId] = outcome.copy(value = null) }
    fun take(sessionId: String): SettleOutcome<*>? = last.remove(sessionId)
}

/**
 * How long screens of each app usually take to settle on this phone, learned from verified steps. Durations only:
 * no labels, text or values. Budget = clamp(p90 × 1.5, 2 s, 15 s), never below the default window.
 */
object SettleBudgets {
    private const val KEEP = 20
    private const val CEILING_MS = 15_000L
    private val samples = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()
    @Volatile private var file: java.io.File? = null
    @Volatile private var loaded = false

    fun attach(dir: java.io.File) {
        if (file != null) return
        file = java.io.File(dir, "Cyclone Brain/Settle/budgets.json")
    }

    fun record(packageName: String, waitedMs: Long) {
        if (packageName.isBlank() || waitedMs !in 0..60_000) return
        load()
        val ring = samples.getOrPut(packageName) { ArrayDeque() }
        synchronized(ring) {
            ring.addLast(waitedMs)
            while (ring.size > KEEP) ring.removeFirst()
        }
        save()
    }

    /** Learned typical settle time (p90) for this app, or null before enough evidence. */
    fun p90(packageName: String): Long? {
        load()
        val ring = samples[packageName] ?: return null
        val sorted = synchronized(ring) { ring.toList() }.sorted()
        if (sorted.size < 3) return null
        return sorted[((sorted.size - 1) * 0.9).toInt()]
    }

    fun budgetFor(packageName: String, base: SettleBudget): SettleBudget {
        val learned = p90(packageName)?.let { (it * 1.5).toLong().coerceIn(2_000L, CEILING_MS) } ?: return base
        val total = maxOf(base.fastMs + base.extendedMs, learned).coerceAtMost(CEILING_MS)
        return base.copy(extendedMs = (total - base.fastMs).coerceAtLeast(0))
    }

    fun forget(packageName: String) { samples.remove(packageName); save() }

    internal fun reset() { samples.clear(); loaded = true }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val source = file?.takeIf { it.isFile && it.length() < 64_000 } ?: return@runCatching
                val json = JSONObject(source.readText())
                json.keys().forEach { pkg ->
                    val values = json.optJSONArray(pkg) ?: return@forEach
                    samples[pkg] = ArrayDeque((0 until minOf(values.length(), KEEP)).map { values.optLong(it) }.filter { it in 0..60_000 })
                }
            }
            loaded = true
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val json = JSONObject()
            samples.forEach { (pkg, ring) -> json.put(pkg, org.json.JSONArray(synchronized(ring) { ring.toList() })) }
            target.parentFile?.mkdirs()
            val temp = java.io.File(target.parentFile, "budgets.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }
}
