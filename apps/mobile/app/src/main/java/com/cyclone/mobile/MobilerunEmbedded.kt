package com.cyclone.mobile

import android.content.Context

/**
 * Compatibility shim for older Cyclone UI/runtime call sites.
 *
 * Cyclone 3.9.1 no longer embeds or starts the Mobilerun Portal application runtime. Native Cyclone
 * Accessibility, notification, overlay, automation, typing, screenshot and gateway code are the
 * only active control plane. Keep these methods temporarily so an old screen cannot crash while the
 * remaining version-suffixed UI is retired; none of them can start a second service stack.
 */
@Deprecated("Mobilerun runtime retired in Cyclone 3.9.1; use native Cyclone services")
object MobilerunEmbedded {
    fun accessibilityConnected(): Boolean = CycloneAccessibilityService.instance != null

    fun portalServiceRunning(): Boolean = false

    fun startPortalService(context: Context) {
        DeviceState.addLog("Ignored legacy Mobilerun Portal start request; native Cyclone runtime is authoritative")
    }

    fun stopPortalService(context: Context) {
        DeviceState.addLog("Ignored legacy Mobilerun Portal stop request; no embedded Portal service exists")
    }

    fun openPortalDashboard(context: Context) = openCyclone(context, "dashboard")
    fun openPortalSettings(context: Context) = openCyclone(context, "settings")
    fun openTriggers(context: Context) = openCyclone(context, "triggers")
    fun openTaskHistory(context: Context) = openCyclone(context, "task history")

    private fun openCyclone(context: Context, legacySurface: String) {
        DeviceState.addLog("Legacy Mobilerun $legacySurface request redirected to Cyclone")
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }
}
