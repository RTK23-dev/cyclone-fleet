package com.mobilerun.portal.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Process-level diagnostics shared by Cyclone and the embedded Mobilerun runtime.
 *
 * This is intentionally local-only. It records lifecycle stages, non-fatal callback failures,
 * uncaught Java exceptions, and Android's historical process-exit reason so a real-device crash can
 * be explained on the next launch even when the process died before a normal UI could render.
 */
object CycloneProcessDiagnostics {
    private const val DIR = "cyclone-diagnostics"
    private const val FILE = "process-crash-journal.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_STACK_CHARS = 24_000
    private const val PREFS = "cyclone_process_diagnostics"
    private const val LAST_EXIT_TS = "last_exit_timestamp"

    private val lock = Any()
    @Volatile private var installed = false
    @Volatile private var lastStateSummary = ""
    @Volatile private var lastStateSummaryAtMs = 0L

    fun install(context: Context) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val app = context.applicationContext
            captureHistoricalExitInfo(app)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    write(
                        app,
                        "UNCAUGHT",
                        "thread=${safe(thread.name, 120)} type=${throwable.javaClass.name} message=${safe(throwable.message, 500)}\n" +
                            throwable.stackTraceToString().take(MAX_STACK_CHARS),
                    )
                    setProcessStateSummary(app, "uncaught:${throwable.javaClass.simpleName}", force = true)
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
            installed = true
            markStage(app, "process.diagnostics.ready")
        }
    }

    fun markStage(context: Context, stage: String) {
        val app = context.applicationContext
        val normalized = safeStage(stage)
        write(app, "STAGE", normalized)
        setProcessStateSummary(app, normalized)
    }

    fun recordNonFatal(context: Context, stage: String, error: Throwable) {
        val app = context.applicationContext
        val normalized = safeStage(stage)
        write(
            app,
            "NON_FATAL",
            "stage=$normalized type=${error.javaClass.name} message=${safe(error.message, 500)}\n" +
                error.stackTraceToString().take(MAX_STACK_CHARS),
        )
        setProcessStateSummary(app, "nonfatal:$normalized")
    }

    fun recentText(context: Context, maxChars: Int = 40_000): String {
        val file = journalFile(context.applicationContext)
        return runCatching {
            if (!file.isFile) return@runCatching ""
            val text = file.readText(Charsets.UTF_8)
            text.takeLast(maxChars.coerceIn(1_000, 120_000))
        }.getOrDefault("")
    }

    fun journalPath(context: Context): String = journalFile(context.applicationContext).absolutePath

    private fun captureHistoricalExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRecorded = prefs.getLong(LAST_EXIT_TS, 0L)
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        }.getOrDefault(emptyList())
        var newest = lastRecorded
        exits.sortedBy { it.timestamp }.forEach { exit ->
            if (exit.timestamp <= lastRecorded) return@forEach
            newest = maxOf(newest, exit.timestamp)
            val state = exit.processStateSummary?.let { String(it, Charsets.UTF_8) }?.let { safe(it, 128) }.orEmpty()
            write(
                context,
                "PREVIOUS_EXIT",
                buildString {
                    append("reason=").append(exitReasonName(exit.reason))
                    append(" reasonCode=").append(exit.reason)
                    append(" status=").append(exit.status)
                    append(" importance=").append(exit.importance)
                    append(" pssKb=").append(exit.pss)
                    append(" rssKb=").append(exit.rss)
                    append(" timestampMs=").append(exit.timestamp)
                    if (state.isNotBlank()) append(" lastStage=").append(state)
                    exit.description?.takeIf { it.isNotBlank() }?.let { append(" description=").append(safe(it, 300)) }
                },
            )
        }
        if (newest > lastRecorded) prefs.edit().putLong(LAST_EXIT_TS, newest).apply()
    }

    private fun setProcessStateSummary(context: Context, stage: String, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val normalized = safeStage(stage).take(120)
        val now = System.currentTimeMillis()
        if (!force && normalized == lastStateSummary && now - lastStateSummaryAtMs < 1_000L) return
        // Android documents a 128-byte maximum and may throttle excessive calls.
        val payload = normalized.toByteArray(Charsets.UTF_8).let { if (it.size <= 128) it else it.copyOf(128) }
        runCatching { context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(payload) }
        lastStateSummary = normalized
        lastStateSummaryAtMs = now
    }

    private fun write(context: Context, kind: String, detail: String) {
        synchronized(lock) {
            runCatching {
                val file = journalFile(context)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_BYTES) {
                    val rotated = File(file.parentFile, "$FILE.previous")
                    runCatching { rotated.delete() }
                    runCatching { file.renameTo(rotated) }
                }
                file.appendText("${timestamp()} [$kind] ${detail.trim()}\n", Charsets.UTF_8)
            }
        }
    }

    private fun journalFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun safeStage(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        .take(120)
        .ifBlank { "unknown" }

    private fun safe(value: String?, max: Int): String = value.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(max)

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "REASON_$reason"
    }
}
