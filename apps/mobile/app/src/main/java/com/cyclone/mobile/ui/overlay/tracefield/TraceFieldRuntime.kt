package com.cyclone.mobile.ui.overlay.tracefield

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** User preference for the working indicator. */
object TraceFieldPrefs {
    private const val FILE = "cyclone_trace_field"
    private const val KEY_MODE = "mode"
    private const val KEY_STYLE = "style"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun mode(context: Context): TraceFieldMode = TraceFieldMode.parse(prefs(context).getString(KEY_MODE, null))
    fun setMode(context: Context, mode: TraceFieldMode) {
        prefs(context).edit().putString(KEY_MODE, mode.wire).apply()
    }
    fun style(context: Context): TraceFieldStyle = TraceFieldStyle.parse(prefs(context).getString(KEY_STYLE, null))
    fun setStyle(context: Context, style: TraceFieldStyle) {
        prefs(context).edit().putString(KEY_STYLE, style.wire).apply()
    }
}

/**
 * Owns the Trace Field window on the accessibility service. Hooks elsewhere call the small signal
 * functions below; every one is a no-op when the field is not attached, and none can throw into the
 * agent's action path.
 */
object TraceFieldRuntime {
    private const val TAG = "CycloneTraceField"
    private const val HIDE_FAILSAFE_MS = 120L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var view: TraceFieldView? = null
    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var stateJob: Job? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun isAttached(): Boolean = view != null

    /** Best-effort, any thread: whether the field currently has pixels on screen. */
    fun isShowing(): Boolean = view?.visibility == android.view.View.VISIBLE

    fun attach(context: Context) {
        main.post { attachOnMain(context) }
    }

    private fun attachOnMain(context: Context) {
        if (view != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val field = TraceFieldView(context) { failure ->
            Log.w(TAG, "Trace Field disabled: shader unavailable", failure)
            detach()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            TraceFieldPolicy.windowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Cyclone Trace Field"
            setFitInsetsTypes(0)
        }
        try {
            wm.addView(field, params)
        } catch (failure: Exception) {
            Log.w(TAG, "Trace Field window unavailable", failure)
            return
        }
        view = field
        windowManager = wm
        appContext = context.applicationContext
        applySettings(context)
        TraceFieldCaptureGate.surface = FailsafeSurface(field)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings(context) }
            .also { TraceFieldPrefs.prefs(context).registerOnSharedPreferenceChangeListener(it) }

        stateJob = scope.launch {
            var previous = TracePresence.NONE
            combine(WorkspaceTasks.state, OverlayChromeRuntime.activity) { task, chrome ->
                TraceFieldPolicy.presence(task?.foreground, task?.phase, chrome)
            }.distinctUntilChanged().collect { next ->
                val metrics = context.resources.displayMetrics
                TraceFieldPolicy.transitions(previous, next, metrics.widthPixels / 2f, metrics.heightPixels.toFloat())
                    .forEach { field.publish(it) }
                previous = next
            }
        }
    }

    fun detach() {
        main.post {
            stateJob?.cancel(); stateJob = null
            val context = appContext
            prefsListener?.let { listener -> context?.let { TraceFieldPrefs.prefs(it).unregisterOnSharedPreferenceChangeListener(listener) } }
            prefsListener = null
            TraceFieldCaptureGate.surface = null
            TraceFieldBackdrop.clear()
            view?.let { field ->
                field.release()
                runCatching { windowManager?.removeViewImmediate(field) }
            }
            view = null
            windowManager = null
            appContext = null
        }
    }

    private fun applySettings(context: Context) {
        val field = view ?: return
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val chosen = TraceFieldPrefs.mode(context)
        field.mode = if (powerSave && chosen == TraceFieldMode.FIELD) TraceFieldMode.EDGE else chosen
        field.style = TraceFieldPrefs.style(context)
        field.reduceMotion = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    // ---- Signals from the action path (any thread). ----

    fun observed(fingerprint: String) = publish(TraceEvent.Observe(fingerprint))

    fun targeted(bounds: UiBounds, key: String) = publish(
        TraceEvent.Target(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), key),
    )

    fun acted(kind: TraceActKind, x: Float, y: Float, dx: Float = 0f, dy: Float = 0f) =
        publish(TraceEvent.Act(kind, x, y, dx, dy))

    fun recovering() = publish(TraceEvent.Recover)

    /** Opened an app or went back/home: the field draws First light. */
    fun navigated() = publish(TraceEvent.Navigate)

    /**
     * Screen bounds of Cyclone's own Ask bar and work panel (null when hidden). The field keeps a soft
     * cut-out there, so its digits never show through Cyclone's translucent glass.
     */
    fun chromeBounds(bounds: android.graphics.RectF?) {
        main.post { view?.chromeBounds = bounds }
    }

    private fun publish(event: TraceEvent) {
        runCatching { view?.publish(event) }
    }

    /**
     * Blocking variant for capture paths that read frames from a stream instead of a callback.
     * Returns a release function; never waits on the main thread.
     */
    fun hideForCaptureBlocking(timeoutMs: Long = 150): () -> Unit {
        if (TraceFieldCaptureGate.surface == null) return {}
        val latch = CountDownLatch(1)
        val lock = Any()
        var release: (() -> Unit)? = null
        var releasedEarly = false
        TraceFieldCaptureGate.hold { r ->
            val lateRelease = synchronized(lock) { if (releasedEarly) true else { release = r; false } }
            if (lateRelease) r()
            latch.countDown()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return {
            val r = synchronized(lock) { releasedEarly = true; release.also { release = null } }
            r?.invoke()
        }
    }

    /** Settings preview: plays the full choreography without a task. */
    fun preview(): Boolean {
        val field = view ?: return false
        val context = appContext ?: return false
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val d = metrics.density
        val steps = listOf<Pair<Long, TraceEvent>>(
            0L to TraceEvent.Wake(w / 2f, h),
            700L to TraceEvent.Observe("preview:home"),
            2_300L to TraceEvent.Target(w * 0.12f, h * 0.30f, w * 0.88f, h * 0.30f + 56f * d, "preview:search"),
            3_200L to TraceEvent.Act(TraceActKind.TAP, w / 2f, h * 0.30f + 28f * d),
            3_700L to TraceEvent.Observe("preview:results"),
            5_200L to TraceEvent.Act(TraceActKind.SCROLL, w / 2f, h * 0.6f, 0f, -h * 0.3f),
            6_000L to TraceEvent.Observe("preview:scrolled"),
            7_600L to TraceEvent.Done,
        )
        steps.forEach { (delay, event) -> main.postDelayed({ field.publish(event) }, delay) }
        return true
    }

    /** Guarantees capture never waits on a stalled frame: after the failsafe, capture proceeds anyway. */
    private class FailsafeSurface(private val field: TraceFieldView) : TraceFieldCaptureGate.Surface {
        override fun hide(onHidden: () -> Unit) {
            val once = AtomicBoolean(false)
            val fire = { if (once.compareAndSet(false, true)) onHidden() }
            main.postDelayed({ fire() }, HIDE_FAILSAFE_MS)
            field.hide { fire() }
        }

        override fun restore() = field.restore()
    }
}
