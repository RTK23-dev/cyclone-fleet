package com.cyclone.mobile.runtime.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Notices when Cyclone's main thread stops answering and records what it was running, so a freeze arrives at the PC
 * with its cause instead of looking like a lost connection.
 *
 * A background thread posts a tick to the main thread and waits [Stalls.THRESHOLD_MS]. If the tick hasn't run by
 * then, the main thread's stack is sampled every [SAMPLE_MS] until it answers again; the freeze is then kept (the
 * newest [Stalls.MAX_KEPT]) and logged as `CycloneStall`. Cost when healthy: one tiny message every half second.
 */
object MainThreadWatchdog {
    private const val TAG = "CycloneStall"
    private const val SAMPLE_MS = 200L
    private const val MAX_SAMPLES = 40
    private const val FILE = "health/stalls.json"

    private val started = AtomicBoolean(false)
    private val lastAnswerAt = AtomicLong(0)
    @Volatile private var file: File? = null
    private val lock = Any()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        file = File(context.applicationContext.filesDir, FILE)
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        lastAnswerAt.set(SystemClock.uptimeMillis())
        Thread({ watch(main, mainThread) }, "cyclone-main-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }.start()
    }

    /** The freezes kept on this phone, oldest first. */
    fun recent(): List<Stall> = synchronized(lock) { Stalls.decode(file?.takeIf { it.isFile }?.readText()) }

    private fun watch(main: Handler, mainThread: Thread) {
        val answer = Runnable { lastAnswerAt.set(SystemClock.uptimeMillis()) }
        while (true) {
            val postedAt = SystemClock.uptimeMillis()
            main.post(answer)
            sleep(Stalls.THRESHOLD_MS)
            if (lastAnswerAt.get() >= postedAt) continue
            // Frozen: sample until the tick runs.
            val samples = mutableListOf<List<String>>()
            while (lastAnswerAt.get() < postedAt) {
                if (samples.size < MAX_SAMPLES) samples.add(runCatching { stack(mainThread) }.getOrDefault(emptyList()))
                sleep(SAMPLE_MS)
            }
            val duration = lastAnswerAt.get() - postedAt
            if (duration >= Stalls.THRESHOLD_MS) record(Stalls.of(System.currentTimeMillis() - duration, duration, samples))
        }
    }

    private fun stack(thread: Thread): List<String> =
        thread.stackTrace.take(Stalls.MAX_FRAMES * 3).map { Stalls.frame(it.className, it.methodName, it.lineNumber) }

    private fun record(stall: Stall) {
        runCatching {
            Log.w(TAG, "Main thread froze ${stall.durationMs} ms; suspect ${stall.suspect ?: "unknown"}")
            synchronized(lock) {
                val target = file ?: return
                target.parentFile?.mkdirs()
                val kept = Stalls.append(Stalls.decode(target.takeIf { it.isFile }?.readText()), stall)
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(Stalls.encode(kept))
                temp.renameTo(target)
            }
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
