package com.cyclone.mobile.ui.v32.ask

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The glass quality the current page draws with; FULL outside the glass world. */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.FULL }

/** The owner's choice and what it resolves to, app-wide. The choice is a plain preference; nothing else is kept. */
object VisualQuality {
    private const val PREFS = "cyclone_ui"
    private const val KEY_MODE = "visual_quality"
    private const val KEY_STEPPED = "visual_quality_auto_stepped_down"

    private val _mode = MutableStateFlow(QualityMode.AUTO)
    val mode: StateFlow<QualityMode> = _mode
    private val _resolved = MutableStateFlow(GlassQuality.FULL)
    val resolved: StateFlow<GlassQuality> = _resolved
    @Volatile var device: DeviceClass = DeviceClass(lowRam = false, totalRamGb = 8.0, cores = 8, performanceClass = 31)
        private set
    @Volatile var steppedDown = false
        private set
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = prefs(context)
        _mode.value = runCatching { QualityMode.valueOf(prefs.getString(KEY_MODE, QualityMode.AUTO.name)!!) }.getOrDefault(QualityMode.AUTO)
        steppedDown = prefs.getBoolean(KEY_STEPPED, false)
        device = readDevice(context)
        loaded = true
        publish()
    }

    fun setMode(context: Context, mode: QualityMode) {
        load(context)
        _mode.value = mode
        // Choosing Auto again gives the phone a fresh chance at Full.
        if (mode == QualityMode.AUTO) {
            steppedDown = false
            prefs(context).edit().putBoolean(KEY_STEPPED, false).apply()
        }
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        publish()
    }

    /** Called by the frame watch: in Auto, a slow phone moves to LITE and stays there until the owner picks again. */
    fun stepDown(context: Context) {
        if (_mode.value != QualityMode.AUTO || steppedDown) return
        steppedDown = true
        prefs(context).edit().putBoolean(KEY_STEPPED, true).apply()
        publish()
    }

    fun explain(): String = QualityPolicy.explain(_mode.value, _resolved.value, device, steppedDown)

    private fun publish() {
        _resolved.value = QualityPolicy.resolve(_mode.value, device, steppedDown)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readDevice(context: Context): DeviceClass {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        return DeviceClass(
            lowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false),
            totalRamGb = info.totalMem / 1_073_741_824.0,
            cores = Runtime.getRuntime().availableProcessors(),
            performanceClass = runCatching { Build.VERSION.MEDIA_PERFORMANCE_CLASS }.getOrDefault(0),
        )
    }
}

