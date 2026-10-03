package com.cyclone.mobile.mind.mission

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.provider.Settings
import com.cyclone.mobile.mind.SettingGoals

/**
 * Alpha 92: reads the few phone settings [SettingGoals] can check, straight from Android (no screen, no permission
 * beyond reading). A value that can't be read stays null, and then the goal is never called met.
 */
object PhoneSettingReader {
    fun read(context: Context, targets: List<SettingGoals.Target>): SettingGoals.Reading {
        val resolver = context.contentResolver
        fun system(name: String): Int? = runCatching { Settings.System.getInt(resolver, name) }.getOrNull()
        val values = targets.associate { target ->
            target.key to when (target.key) {
                SettingGoals.Key.ROTATION -> system(Settings.System.ACCELEROMETER_ROTATION)?.let { it == 1 }
                SettingGoals.Key.TOUCH_VIBRATION -> system(Settings.System.HAPTIC_FEEDBACK_ENABLED)?.let { it == 1 }
                SettingGoals.Key.ADAPTIVE_BRIGHTNESS -> system(Settings.System.SCREEN_BRIGHTNESS_MODE)
                    ?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
                SettingGoals.Key.TIMEOUT_MS -> runCatching { Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
                SettingGoals.Key.DARK -> runCatching {
                    context.getSystemService(UiModeManager::class.java)?.nightMode?.let { it == UiModeManager.MODE_NIGHT_YES }
                }.getOrNull()
                SettingGoals.Key.DND -> runCatching {
                    context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                        ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
                }.getOrNull()
            }
        }
        return SettingGoals.Reading(values)
    }

    /** The check for one mission's goal, or a check that is never met when the goal is not only settings. */
    fun checkFor(context: Context, goal: String): () -> String? {
        val targets = SettingGoals.parse(goal) ?: return { null }
        return { SettingGoals.met(targets, read(context, targets)) }
    }
}
