package com.cyclone.mobile.codes

/** One text message, read in memory for a moment. Never stored or logged. */
class SmsLine(val from: String, val body: String, val atMs: Long, val subscriptionId: Int = -1)

/** The phone's texts received since [sinceMs] (Android: the SMS database). Empty when Cyclone may not read them. */
fun interface SmsInbox {
    fun since(sinceMs: Long): List<SmsLine>
}

/**
 * Plan 49 §3: waits for a code to arrive by text on this phone and picks the right one. Pure; [inbox] is Android's.
 *
 * - **The window:** texts received at or after [Ask.sinceMs] (the code was asked for, minus a short margin).
 * - **The right one:** a text that names the app (sender or text) wins; the newest of those. With no name, a code counts
 *   only when it is the only code in the window and has waited [UNNAMED_GRACE_MS] for a named one (another service's
 *   code can land just before the app's own). Two different unnamed codes are ambiguous: no guess.
 * - **Never the same code twice:** a code the app refused is passed in [Ask.refused].
 *
 * The text itself never leaves this class; only the code does, and the caller fills it at once.
 */
class CodeCatcher(
    private val inbox: SmsInbox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class Ask(
        /** Words that name the app: its label and its package's own words ("instagram"). */
        val names: List<String>,
        val sinceMs: Long,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val refused: Set<String> = emptySet(),
        /** The SIM the number belongs to, when known; texts to the other SIM don't count. */
        val subscriptionId: Int = -1,
    )

    sealed class Result {
        class Caught(val code: String, val named: Boolean) : Result()
        data class Missed(val reason: String) : Result()
    }

    fun await(ask: Ask, cancelled: () -> Boolean = { false }): Result {
        val deadline = clock() + ask.timeoutMs
        var ambiguous = false
        while (true) {
            when (val pick = pick(ask)) {
                is Pick.One -> return Result.Caught(pick.code, pick.named)
                Pick.Ambiguous -> ambiguous = true
                Pick.None -> Unit
            }
            if (cancelled()) return Result.Missed("the run stopped")
            if (clock() >= deadline) {
                return Result.Missed(if (ambiguous) "more than one code arrived and none named the app" else "no text with a code arrived in time")
            }
            sleep(POLL_MS)
        }
    }

    private sealed class Pick {
        class One(val code: String, val named: Boolean) : Pick()
        data object Ambiguous : Pick()
        data object None : Pick()
    }

    private fun pick(ask: Ask): Pick {
        val names = ask.names.map { it.lowercase().trim() }.filter { it.length >= 3 }.distinct()
        val found = runCatching { inbox.since(ask.sinceMs) }.getOrDefault(emptyList())
            .filter { it.atMs >= ask.sinceMs && (ask.subscriptionId < 0 || it.subscriptionId < 0 || it.subscriptionId == ask.subscriptionId) }
            .mapNotNull { line ->
                val code = CodeExtractor.extract(line.body) ?: return@mapNotNull null
                if (code in ask.refused) return@mapNotNull null
                val named = names.any { n -> line.from.lowercase().contains(n) || line.body.lowercase().contains(n) }
                Triple(code, named, line.atMs)
            }
        if (found.isEmpty()) return Pick.None
        found.filter { it.second }.maxByOrNull { it.third }?.let { return Pick.One(it.first, true) }
        val codes = found.map { it.first }.distinct()
        if (codes.size > 1) return Pick.Ambiguous
        return if (clock() - found.maxOf { it.third } >= UNNAMED_GRACE_MS) Pick.One(codes.single(), false) else Pick.None
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        /** A code asked for just before the code page showed still counts. */
        const val MARGIN_MS = 90_000L
        const val UNNAMED_GRACE_MS = 15_000L
        private const val POLL_MS = 1_000L
    }
}
