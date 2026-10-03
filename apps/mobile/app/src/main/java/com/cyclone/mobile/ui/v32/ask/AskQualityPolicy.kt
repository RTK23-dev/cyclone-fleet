package com.cyclone.mobile.ui.v32.ask

/**
 * How much optical work the glass does (R5). [FULL] is the designed look: every panel blurs and lenses the rain.
 * [LITE] keeps the same look with less work: one shared blur for all content panels, the lens only on navigation
 * glass, no cast shadow on content cards, and the rain at 20 instead of 30 frames a second. Words, layout, colours,
 * rims and the shine are the same in both.
 */
enum class GlassQuality { FULL, LITE }

/** The owner's choice in Settings › Appearance › Visual quality. */
enum class QualityMode(val label: String) { AUTO("Auto"), FULL("Full"), LITE("Lite") }

/** The phone facts Auto decides on. Pure, so the choice is tested without a device. */
data class DeviceClass(
    val lowRam: Boolean,
    val totalRamGb: Double,
    val cores: Int,
    /** Android's media performance class (0 when the phone does not declare one, 31+ when it does). */
    val performanceClass: Int,
)

object QualityPolicy {
    /**
     * Auto picks LITE on phones that are clearly older or budget: Android's low-RAM flag, under 4.5 GB of RAM, fewer
     * than 6 cores, or no declared performance class together with under 6 GB. Everything else starts FULL, and
     * [JankWindow] can still step it down while it runs.
     */
    fun autoTier(device: DeviceClass): GlassQuality = when {
        device.lowRam -> GlassQuality.LITE
        device.totalRamGb < 4.5 -> GlassQuality.LITE
        device.cores < 6 -> GlassQuality.LITE
        device.performanceClass == 0 && device.totalRamGb < 6.0 -> GlassQuality.LITE
        else -> GlassQuality.FULL
    }

    /** The owner's choice wins; Auto uses the device and, once this session stepped down, stays LITE. */
    fun resolve(mode: QualityMode, device: DeviceClass, steppedDown: Boolean): GlassQuality = when (mode) {
        QualityMode.FULL -> GlassQuality.FULL
        QualityMode.LITE -> GlassQuality.LITE
        QualityMode.AUTO -> if (steppedDown) GlassQuality.LITE else autoTier(device)
    }

    /** The line Settings shows under Auto. */
    fun explain(mode: QualityMode, resolved: GlassQuality, device: DeviceClass, steppedDown: Boolean): String = when {
        mode != QualityMode.AUTO -> if (resolved == GlassQuality.FULL) "Full glass on every panel." else "Lite: the same look with less work."
        steppedDown -> "Auto switched to Lite after some slow frames on this phone."
        resolved == GlassQuality.LITE -> "Auto chose Lite for this phone (${"%.1f".format(device.totalRamGb)} GB, ${device.cores} cores)."
        else -> "Auto chose Full for this phone."
    }
}

/**
 * Frame times over a rolling window. Auto steps down to LITE when more than [lateShare] of at least [minFrames]
 * frames in the last [windowMs] ran over 1.5 × the display's frame budget. Pure and tested.
 */
class JankWindow(
    private val windowMs: Long = 3_000,
    private val minFrames: Int = 90,
    private val lateShare: Double = 0.12,
) {
    private val times = ArrayDeque<Long>()
    private val late = ArrayDeque<Boolean>()
    private var lateCount = 0

    /** Records one frame ([nowMs], its duration and the display budget); true when the window is too slow. */
    fun record(nowMs: Long, frameMs: Double, budgetMs: Double): Boolean {
        val isLate = frameMs > budgetMs * 1.5
        times.addLast(nowMs); late.addLast(isLate); if (isLate) lateCount++
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) {
            times.removeFirst(); if (late.removeFirst()) lateCount--
        }
        return times.size >= minFrames && lateCount.toDouble() / times.size > lateShare
    }

    fun reset() { times.clear(); late.clear(); lateCount = 0 }
}

/**
 * While a finger moves a list the eye is on the content, so the rain holds still (R5): the shared blur stays cached
 * and scrolling costs almost nothing. It resumes [RESUME_MS] after the last scroll.
 */
object AskMotion {
    const val RESUME_MS = 350L
    @Volatile var lastScrollMs = 0L
    fun scrolled(nowMs: Long) { lastScrollMs = nowMs }
    fun holding(nowMs: Long): Boolean = nowMs - lastScrollMs < RESUME_MS
}
