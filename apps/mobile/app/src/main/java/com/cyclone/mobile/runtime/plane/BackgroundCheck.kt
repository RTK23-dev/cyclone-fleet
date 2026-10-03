package com.cyclone.mobile.runtime.plane

import android.content.Context
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.HumanGestureDispatch
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.RuntimeGestureKind
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.WorkspaceCommands
import com.cyclone.mobile.runtime.background.WorkspaceRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plan 28: the Background Check. It runs the real background path on this phone, on a harmless app, one step at a
 * time, and says which step fails and what Android answered. Nothing the owner sees changes: the app opens on a hidden
 * screen, scrolls once and closes. The result decides whether Automatic uses the background (a phone that fails the
 * check works on the screen and says why) and is kept for diagnostics. No content is kept, only step results.
 */
enum class CheckStep(val label: String, val engine: Boolean) {
    ANDROID("Android 15 or later", false),
    HELPER("Shizuku helper running and allowed", false),
    CONTROL("Cyclone phone control on", false),
    SERVICE("Background service starts", true),
    SCREEN("Background screen opens with an app", true),
    FRAMES("Background screen shows the app", true),
    READ("Cyclone can read the app there", true),
    ACT("Cyclone can scroll the app there", true),
    ISOLATED("Your screen stays yours", true),
    CLOSE("Background screen closes cleanly", true),
    ;

    /** One next step for the owner when this step fails. */
    val advice: String get() = when (this) {
        ANDROID -> "Background screens need Android 15. Cyclone works on your screen on this phone."
        HELPER -> "Open Background setup and finish the Shizuku step (after a restart: open Shizuku and tap Start)."
        CONTROL -> "Turn on Cyclone phone control in Accessibility settings."
        SERVICE -> "Open Shizuku, tap Stop and Start, then check again."
        SCREEN -> "Android refused a private background screen on this phone. Cyclone works on your screen."
        FRAMES -> "The background screen stayed empty. Restart the phone and check again."
        READ -> "Cyclone's Accessibility cannot see the background screen on this phone. Cyclone works on your screen."
        ACT -> "Cyclone's gestures do not reach the background screen on this phone. Cyclone works on your screen."
        ISOLATED -> "The app came to your screen instead of staying behind it. Cyclone works on your screen."
        CLOSE -> "The background screen did not close cleanly. Restart Cyclone and check again."
    }
}

enum class CheckResult { PASSED, FAILED, SKIPPED }

data class CheckStepResult(val step: CheckStep, val result: CheckResult, val detail: String, val tookMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("step", step.name).put("result", result.name).put("detail", detail).put("ms", tookMs)

    companion object {
        fun fromJson(json: JSONObject): CheckStepResult? = runCatching {
            CheckStepResult(CheckStep.valueOf(json.getString("step")), CheckResult.valueOf(json.getString("result")),
                json.optString("detail"), json.optLong("ms"))
        }.getOrNull()
    }
}

data class BackgroundCheckReport(
    val atMs: Long,
    val versionCode: Long,
    val app: String?,
    val steps: List<CheckStepResult>,
    /** How Cyclone's gestures reached the background screen ("accessibility" or "helper"), when the ACT step ran. */
    val touchRoute: String? = null,
) {
    val failure: CheckStepResult? get() = steps.firstOrNull { it.result == CheckResult.FAILED }
    val passed: Boolean get() = failure == null && steps.any { it.step == CheckStep.CLOSE && it.result == CheckResult.PASSED }

    /** In the owner's words. */
    val headline: String get() = failure?.let { "Background check stopped at \"${it.step.label}\": ${it.step.advice}" }
        ?: if (passed) "Background check passed: Cyclone can work behind your screen on this phone."
        else "Background check did not finish."

    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("versionCode", versionCode).put("app", app ?: JSONObject.NULL)
        .put("touchRoute", touchRoute ?: JSONObject.NULL).put("steps", JSONArray(steps.map { it.toJson() }))

    companion object {
        fun fromJson(json: JSONObject): BackgroundCheckReport? = runCatching {
            val steps = json.getJSONArray("steps")
            BackgroundCheckReport(json.getLong("at"), json.optLong("versionCode"), json.optString("app").takeUnless { json.isNull("app") },
                (0 until steps.length()).mapNotNull { CheckStepResult.fromJson(steps.getJSONObject(it)) },
                json.optString("touchRoute").takeUnless { json.isNull("touchRoute") || it.isBlank() })
        }.getOrNull()

        /**
         * Whether the last check keeps Automatic off the background: it failed in the background engine itself (not a
         * setup step the capability already reports) on this build, in the last [TRUST_MS]. A newer build or an older
         * result is checked again rather than trusted.
         */
        fun blocks(report: BackgroundCheckReport?, versionCode: Long, nowMs: Long): String? {
            val failed = report?.failure ?: return null
            if (!failed.step.engine || report.versionCode != versionCode || nowMs - report.atMs > TRUST_MS) return null
            return "Background check stopped at \"${failed.step.label}\". ${failed.step.advice}"
        }

        const val TRUST_MS = 7L * 24 * 60 * 60_000L
    }
}

object BackgroundCheck {
    /** Harmless apps to open on the hidden screen, in order; one that is not open on the owner's phone is used. */
    val PROBE_APPS = listOf("com.android.settings", "com.google.android.deskclock", "com.google.android.calculator",
        "com.android.deskclock", "com.sec.android.app.clockpackage")

    private val state = MutableStateFlow<BackgroundCheckReport?>(null)
    /** The last report (loaded on first read), and live progress while a check runs. */
    val report: StateFlow<BackgroundCheckReport?> = state
    @Volatile var running = false
        private set

    private fun file(context: Context) = File(context.applicationContext.filesDir, "Cyclone Brain/Planes/background-check.json")

    fun last(context: Context): BackgroundCheckReport? = state.value ?: runCatching {
        file(context).takeIf { it.exists() }?.readText()?.let { BackgroundCheckReport.fromJson(JSONObject(it)) }
    }.getOrNull()?.also { state.value = it }

    /** Why Automatic should not use the background right now, from the last check; null when nothing speaks against it. */
    fun blocker(context: Context): String? =
        BackgroundCheckReport.blocks(last(context), BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())

    /** Run on a worker thread (never the main thread: the service binding answers there). False when one is running. */
    fun start(context: Context): Boolean {
        if (running) return false
        val app = context.applicationContext
        Thread({ runCatching { run(app) } }, "cyclone-background-check").start()
        return true
    }

    /**
     * After an install or update, once the background is set up: check it once, while no mission runs, so the first
     * background task does not find out the hard way.
     */
    fun maybeAuto(context: Context) {
        val last = last(context)
        if (running || last?.versionCode == BuildConfig.VERSION_CODE.toLong()) return
        if (!MissionPlanes.capability(context).ready || com.cyclone.mobile.mind.mission.MindMissions.isLive()) return
        start(context)
    }

    @Synchronized
    fun run(context: Context): BackgroundCheckReport {
        running = true
        val steps = mutableListOf<CheckStepResult>()
        var app: String? = null
        var route: String? = null
        fun publish() {
            state.value = BackgroundCheckReport(System.currentTimeMillis(), BuildConfig.VERSION_CODE.toLong(), app, steps.toList(), route)
        }
        fun step(step: CheckStep, block: () -> String): Boolean {
            if (steps.any { it.result == CheckResult.FAILED }) {
                steps += CheckStepResult(step, CheckResult.SKIPPED, "Not run: an earlier step failed.", 0)
                publish(); return false
            }
            val started = System.currentTimeMillis()
            val outcome = runCatching(block)
            steps += CheckStepResult(step, if (outcome.isSuccess) CheckResult.PASSED else CheckResult.FAILED,
                outcome.fold({ it }, { (it.message ?: it.javaClass.simpleName).lineSequence().first().take(200) }),
                System.currentTimeMillis() - started)
            publish()
            return outcome.isSuccess
        }
        var sessionId: String? = null
        try {
            val setup = BackgroundSetup.read(context)
            step(CheckStep.ANDROID) { check(setup.android) { "Android ${android.os.Build.VERSION.RELEASE}" }; "Android ${android.os.Build.VERSION.RELEASE}" }
            step(CheckStep.HELPER) {
                check(setup.installed) { "Shizuku is not installed" }
                check(setup.running) { "Shizuku is not running" }
                check(setup.authorized) { "Shizuku has not allowed Cyclone" }
                "Shizuku running and allowed"
            }
            step(CheckStep.CONTROL) { check(setup.accessibility) { "Accessibility is off" }; "On" }
            step(CheckStep.SERVICE) { WorkspaceRuntime.connect(context); "Connected" }
            var scope: ExecutionContext? = null
            step(CheckStep.SCREEN) {
                val probe = PROBE_APPS.firstOrNull { pkg ->
                    runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() != null &&
                        WorkspaceRuntime.holder(context, pkg) == TargetHolder.NOBODY
                } ?: error("Settings, Clock and Calculator are all open on your phone; close one from Recents")
                app = probe
                val session = WorkspaceRuntime.create(context, probe)
                sessionId = session.sessionId
                scope = ExecutionContext(session.sessionId, session.displayId)
                "Display ${session.displayId} with $probe"
            }
            step(CheckStep.FRAMES) {
                val id = sessionId!!
                val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
                while (!LiveVisionRuntime.hasFrame(id) && System.currentTimeMillis() < deadline) Thread.sleep(100)
                check(LiveVisionRuntime.hasFrame(id)) { "FRAME_STREAM_STALLED: no frame in ${FRAME_WAIT_MS / 1000} s" }
                "First frame arrived"
            }
            var before: com.cyclone.mobile.UiSnapshot? = null
            step(CheckStep.READ) {
                val deadline = System.currentTimeMillis() + READ_WAIT_MS
                var snapshot = runCatching { WorkspaceRuntime.observe(scope!!) }
                while ((snapshot.getOrNull()?.nodes.isNullOrEmpty()) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                    snapshot = runCatching { WorkspaceRuntime.observe(scope!!) }
                }
                val seen = snapshot.getOrThrow()
                check(seen.nodes.isNotEmpty()) { "The app's controls were not visible to Accessibility" }
                before = seen
                "${seen.nodes.size} controls in ${seen.packageName}"
            }
            step(CheckStep.ACT) {
                val seen = before!!
                val list = seen.nodes.filter { it.scrollable && it.bounds.height > 200 }.maxByOrNull { it.bounds.width * it.bounds.height }
                    ?: return@step "Skipped: nothing to scroll in ${seen.packageName}".also { route = "none" }
                val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
                val generation = WorkspaceRuntime.generation(scope!!.sessionId)
                val viewport = WorkspaceRuntime.authorizeTouch(scope!!, generation)
                val x = list.bounds.centerX
                val y1 = list.bounds.top + list.bounds.height * 0.75f
                val y2 = list.bounds.top + list.bounds.height * 0.3f
                val commandId = "background-check-${System.nanoTime()}"
                val landed = HumanGestureDispatch.swipe(service, x, y1, x, y2, 350L, HumanizePreference.AUTO, RuntimeGestureKind.SCROLL,
                    commandId, scope!!.displayId, viewport)
                route = "accessibility"
                if (!landed) {
                    LiveVisionRuntime.mutationFinished(scope!!.sessionId)
                    WorkspaceRuntime.input(scope!!, WorkspaceRuntime.generation(scope!!.sessionId), WorkspaceCommands.SWIPE,
                        floatArrayOf(x, y1, x, y2, 350f))
                    route = "helper"
                } else {
                    LiveVisionRuntime.mutationFinished(scope!!.sessionId)
                }
                HumanGestureDispatch.consumeTrace(commandId)
                val deadline = System.currentTimeMillis() + ACT_WAIT_MS
                var changed = false
                while (!changed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(150)
                    changed = runCatching { WorkspaceRuntime.observe(scope!!).fingerprint != seen.fingerprint }.getOrDefault(false)
                }
                check(changed) { "The scroll did not move the list (${route} route)" }
                "Scrolled with the $route route"
            }
            step(CheckStep.ISOLATED) {
                val (service, present, _) = WorkspaceRuntime.probe(sessionId!!)
                check(service && present) { "The app did not stay on the background screen" }
                check(BackgroundSetup.foregroundPackage() != app) { "The app appeared on your screen" }
                "The app stayed behind your screen"
            }
            step(CheckStep.CLOSE) {
                WorkspaceRuntime.close(sessionId!!)
                sessionId = null
                "Closed"
            }
        } finally {
            sessionId?.let { runCatching { WorkspaceRuntime.close(it) } }
            publish()
            val done = state.value!!
            runCatching { file(context).apply { parentFile?.mkdirs() }.writeText(done.toJson().toString()) }
            running = false
        }
        return state.value!!
    }

    const val FRAME_WAIT_MS = 4_000L
    const val READ_WAIT_MS = 4_000L
    const val ACT_WAIT_MS = 2_000L
}
