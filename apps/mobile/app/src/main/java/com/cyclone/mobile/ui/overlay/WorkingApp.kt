package com.cyclone.mobile.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which app a working status shows (the island, the working card, the in-app task bars): the app Cyclone is working
 * in, never Cyclone's own logo when a real app is known.
 * - A task with a record: the last app its trail saw ([TaskAppTrail]), else its own package when that is a real app.
 * - The on-screen (classic) task, which has no record: the last real app on the main screen.
 * Cyclone itself, the system bars and the home screen are never "the app": they fall back to the last real one.
 * Kept in memory only, like the trail.
 */
object WorkingApp {
    const val CYCLONE = "com.cyclone.mobile"
    private val SYSTEM = setOf("com.android.systemui", "android")

    private val _foreground = MutableStateFlow<String?>(null)
    /** The last real app seen on the main screen. */
    val foreground: StateFlow<String?> = _foreground

    /** A package worth showing: not Cyclone, not the system UI, not the home screen ([home]). */
    fun shows(packageName: String?, home: Set<String> = emptySet()): Boolean =
        !packageName.isNullOrBlank() && packageName != CYCLONE && packageName !in SYSTEM && packageName !in home

    /** The main screen changed to [packageName]; kept only when it is a real app. */
    fun seen(packageName: String?, home: Set<String> = emptySet()) {
        if (shows(packageName, home)) _foreground.value = packageName
    }

    /** The app a recorded task works in, or null before it has opened one. */
    fun forTask(taskId: String, packageName: String?): String? =
        TaskAppTrail.record(taskId, packageName).lastOrNull() ?: packageName?.takeIf { shows(it) }
}
