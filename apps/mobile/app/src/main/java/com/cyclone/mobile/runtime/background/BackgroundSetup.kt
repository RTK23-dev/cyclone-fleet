package com.cyclone.mobile.runtime.background

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import rikka.shizuku.Shizuku

data class BackgroundReadiness(
    val android: Boolean, val installed: Boolean, val running: Boolean, val authorized: Boolean,
    val accessibility: Boolean, val notifications: Boolean, val humanScreen: Boolean,
) {
    val setupFailure: String? get() = when {
        !android -> "Android version: background tasks need Android 15 or later."
        !installed -> "Shizuku: install it from Background tasks setup."
        !running -> "Shizuku: open it, pair with wireless debugging and tap Start."
        !authorized -> "Shizuku access: allow Cyclone in Background tasks setup."
        !accessibility -> "Accessibility: enable or repair Cyclone phone control."
        !notifications -> "Task notifications: allow Cyclone notifications."
        else -> null
    }
    val failure: String? get() = setupFailure ?: if (!humanScreen)
        "Main screen: leave Cyclone and the target app, then start from the Ask bar." else null
    val ready get() = failure == null
}

object BackgroundSetup {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    fun foregroundPackage(): String? = runCatching {
            CycloneAccessibilityService.instance?.windowsOnAllDisplays?.get(0).orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }.firstOrNull()?.root?.packageName?.toString()
        }.getOrNull()

    fun read(context: Context, target: String? = null): BackgroundReadiness {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val humanPackage = foregroundPackage()
        return BackgroundReadiness(
            Build.VERSION.SDK_INT >= 35,
            runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false),
            running,
            running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            CyclonePermissionSetup.phoneControlReady(context),
            CyclonePermissionSetup.resultNotificationsEnabled(context) && runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false),
            !humanPackage.isNullOrBlank() && humanPackage != context.packageName && humanPackage != target,
        )
    }
    fun failure(context: Context, target: String, error: Exception): String = read(context, target).failure ?: when {
        error.message.orEmpty().contains("FOREGROUND_REQUIRED") || error.message.orEmpty().contains("main screen") ->
            "Target app: close it on your main screen, including its recent task, then try again."
        error.message.orEmpty().contains("DISPLAY") || error.message.orEmpty().contains("display", true) ->
            "Background workspace: this phone or app could not provide an isolated screen."
        else -> "Background workspace: the app did not become ready. Open Background tasks setup to recheck access."
    }
}
