package com.cyclone.mobile.voice

import kotlin.math.roundToInt

/** The owner's Drive settings (Settings → Driver mode and Settings → Voice). Stored by [DriverMode]; no secrets. */
data class DriverSettings(
    val enabled: Boolean = false,
    /** The AI button's size in dp. */
    val buttonDp: Int = DEFAULT_BUTTON_DP,
    /** Null: picked from the live list. */
    val sttModel: String? = null,
    val ttsModel: String? = null,
    val fastModel: String? = null,
    val voice: String? = null,
    /** Android's own recognizer instead of OpenRouter (offline, private). */
    val onDeviceStt: Boolean = false,
    /** "auto", or a language name the models understand ("English", "Dutch"). */
    val language: String = "auto",
    /** Trailing silence that ends a request. */
    val endSilenceMs: Int = 700,
    /** "Best voice": the most natural voice the live list has, a little slower and dearer; otherwise the fastest. */
    val bestVoice: Boolean = false,
    /** JEV watches each request next to the understanding model (it never decides); its agreement shows in Settings. */
    val jevWatch: Boolean = true,
    /** Read incoming messages aloud while Driver mode is on (plan 32 D3). Off until the owner turns it on. */
    val announce: Boolean = false,
    /** The chat apps whose messages are announced (package names). */
    val announceApps: Set<String> = emptySet(),
    /** Only these senders, by name; empty means everyone in the allowed apps. */
    val announceContacts: Set<String> = emptySet(),
    /** Listen through a connected car kit or headset when there is one; the phone's own microphone otherwise. */
    val bluetoothMic: Boolean = true,
) {
    companion object {
        const val DEFAULT_BUTTON_DP = 84
        val BUTTON_SIZES = listOf(72, 84, 96)
        val LANGUAGES = listOf("auto", "English", "Dutch", "German", "French", "Spanish")
        val END_SILENCE = listOf(500, 700, 900, 1_200)
    }
}

/**
 * Where the AI button sits, per orientation (plan 32: hold it one second, then drag it anywhere; remembered).
 * [xFraction] and [yFraction] are the button's centre as fractions of the screen, so the spot survives a rotation,
 * a font or a bar change; [corner] keeps the whole button on screen however the screen changes.
 */
data class ButtonSpot(val xFraction: Float = 1f, val yFraction: Float = 0.62f) {
    /** The button's top-left corner on a [width] x [height] screen: wholly on screen, [margin] from the side edges. */
    fun corner(width: Int, height: Int, button: Int, margin: Int): Pair<Int, Int> {
        val maxX = (width - button - margin).coerceAtLeast(margin.coerceAtMost(width - button).coerceAtLeast(0))
        val minX = margin.coerceAtMost(maxX)
        val x = (xFraction * width - button / 2f).roundToInt().coerceIn(minX, maxX)
        val y = (yFraction * height - button / 2f).roundToInt().coerceIn(0, (height - button).coerceAtLeast(0))
        return x to y
    }

    companion object {
        /** Where a dropped button stays: exactly where it was let go, clear of the very top and bottom. */
        fun at(centreX: Float, centreY: Float, width: Float, height: Float): ButtonSpot = ButtonSpot(
            xFraction = if (width <= 0f) 1f else (centreX / width).coerceIn(0f, 1f),
            yFraction = if (height <= 0f) 0.62f else (centreY / height).coerceIn(0.06f, 0.94f),
        )
    }
}
