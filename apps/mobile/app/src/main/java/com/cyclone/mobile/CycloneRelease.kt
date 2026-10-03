package com.cyclone.mobile

/**
 * One release identity for the Android app.
 *
 * Keep the actual version in app/build.gradle.kts only. Product UI, diagnostics,
 * and gateway surfaces reference BuildConfig.VERSION_NAME through this helper.
 */
object CycloneRelease {
    val version: String get() = BuildConfig.VERSION_NAME
    val label: String get() = "Cyclone $version"
}
