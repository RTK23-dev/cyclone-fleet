package com.cyclone.mobile.runtime.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.cyclone.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's health report for the PC (op `health.report`): this app's version and uptime, why Android ended its
 * last processes (Android's own exit record, with the main thread's stack when a freeze was killed), and the freezes
 * the [MainThreadWatchdog] caught. Read-only; nothing typed, shown or stored by the owner is in it.
 */
object AppHealth {
    /** Process start, for uptime. */
    private val startedAtElapsed = SystemClock.elapsedRealtime()

    fun report(context: Context): JSONObject {
        val app = context.applicationContext
        return JSONObject()
            .put("app", JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("pid", Process.myPid())
                .put("uptimeMs", SystemClock.elapsedRealtime() - startedAtElapsed)
                .put("processStartedAtMs", System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsed)))
            .put("exits", JSONArray(exits(app).map { it.toJson() }))
            .put("stalls", JSONArray(MainThreadWatchdog.recent().takeLast(10).map { it.toJson() }))
            .put("watchdog", JSONObject().put("thresholdMs", Stalls.THRESHOLD_MS))
            // Alpha 89: how requests are decided (counts and times only, never request text).
            .put("decisions", decisions(app) ?: JSONObject.NULL)
    }

    private fun decisions(context: Context): JSONObject? = runCatching {
        val bar = com.cyclone.mobile.mind.pilot.FastMode.settings(context).sureness.bar
        val use = com.cyclone.mobile.mind.modes.CycloneModes.settings(context).phoneModel
        com.cyclone.mobile.mind.decide.PhoneBrain.stats(context, bar, use)
            .put("speed", com.cyclone.mobile.mind.modes.CycloneModes.settings(context).speed.wire)
    }.getOrNull()

    fun exits(context: Context): List<AppExit> = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        manager.getHistoricalProcessExitReasons(context.packageName, 0, ExitReasons.MAX_EXITS).map { info ->
            AppExit(
                atMs = info.timestamp,
                reason = info.reason,
                status = info.status,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                description = ExitReasons.description(info.description),
                mainThread = if (info.reason == ApplicationExitInfo.REASON_ANR) anrMainThread(info) else emptyList(),
            )
        }
    }.getOrDefault(emptyList())

    private fun anrMainThread(info: ApplicationExitInfo): List<String> = runCatching {
        info.traceInputStream?.use { stream ->
            // The trace can be megabytes; the main thread comes first, so the head is enough.
            val head = ByteArray(256 * 1024)
            var read = 0
            while (read < head.size) {
                val n = stream.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            ExitReasons.mainThread(String(head, 0, read, Charsets.UTF_8))
        }.orEmpty()
    }.getOrDefault(emptyList())
}
