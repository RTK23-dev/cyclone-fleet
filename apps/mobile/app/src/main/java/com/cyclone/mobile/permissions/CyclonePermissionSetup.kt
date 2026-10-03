package com.cyclone.mobile.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState

/**
 * One read-only source for the permission setup UI.
 *
 * These checks never grant access. Every grant remains an Android-owned screen or runtime dialog
 * reached only after the user taps the corresponding setup row.
 *
 * Permission introspection is deliberately fail-closed. Some Android/OEM builds can throw while
 * reading system-owned settings. Setup must render in that case instead of crashing the app, so an
 * unavailable check is treated as "not enabled" and the user can still open the Android settings
 * screen from Cyclone.
 */
object CyclonePermissionSetup {
    private const val AGENT_KEYBOARD = "com.mobilerun.portal.input.MobilerunKeyboardIME"

    fun primaryControlEnabled(context: Context): Boolean = accessibilitySettingEnabled(context)

    /**
     * Compatibility mirror for older V3.2 UI code. Cyclone no longer exposes a second Android
     * Accessibility service, so "enhanced" readiness follows the canonical phone-control grant.
     */
    fun enhancedControlEnabled(context: Context): Boolean = primaryControlEnabled(context)

    fun accessibilitySettingEnabled(context: Context): Boolean = safePermissionCheck {
        accessibilityServiceEnabled(context, CycloneAccessibilityService::class.java.name)
    }

    fun accessibilityServiceBound(): Boolean = safePermissionCheck {
        CycloneAccessibilityService.instance != null || DeviceState.accessibilityConnected
    }

    fun phoneControlSnapshot(context: Context): PhoneControlSnapshot = PhoneControlSnapshot(
        settingEnabled = accessibilitySettingEnabled(context),
        serviceBound = accessibilityServiceBound(),
    )

    fun phoneControlReady(context: Context): Boolean = phoneControlSnapshot(context).ready

    fun notificationAccessEnabled(context: Context): Boolean = safePermissionCheck {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun resultNotificationsEnabled(context: Context): Boolean = safePermissionCheck {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun calendarEnabled(context: Context): Boolean = safePermissionCheck {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Plan 29: add the events the owner asks for, without opening a calendar app. */
    fun calendarWriteEnabled(context: Context): Boolean = safePermissionCheck {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    /** Plan 29: look up a person's number or address for a mission, without opening the contacts app. */
    fun contactsEnabled(context: Context): Boolean = safePermissionCheck {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    fun overlayEnabled(context: Context): Boolean = safePermissionCheck {
        Settings.canDrawOverlays(context)
    }

    fun batteryUnrestricted(context: Context): Boolean = safePermissionCheck {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun exactTimingEnabled(context: Context): Boolean = safePermissionCheck {
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun agentKeyboardEnabled(context: Context): Boolean = safePermissionCheck {
        val expected = ComponentName(context.packageName, AGENT_KEYBOARD)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            .orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notificationAccessSettings() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun appDetails(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    fun overlaySettings(context: Context) = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun batteryExemptionRequest(context: Context) = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    fun batteryOptimizationSettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun exactTimingSettings(context: Context) = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    )

    fun keyboardSettings() = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    private fun accessibilityServiceEnabled(context: Context, className: String): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false
        }
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any {
            it.packageName == context.packageName && it.className == className
        }
    }
}

internal inline fun safePermissionCheck(check: () -> Boolean): Boolean =
    runCatching(check).getOrDefault(false)
