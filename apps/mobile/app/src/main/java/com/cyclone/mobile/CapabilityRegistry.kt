package com.cyclone.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.gesture.HumanGestureRuntimeCapabilities
import org.json.JSONArray
import org.json.JSONObject

object CapabilityRegistry {
    fun snapshot(context: Context): List<CapabilityState> {
        val packageName = context.packageName
        val accessibility = CycloneAccessibilityService.instance != null
        val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)
        val calendar = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val power = context.getSystemService(PowerManager::class.java)
        val batteryExempt = power?.isIgnoringBatteryOptimizations(packageName) == true

        return listOf(
            CapabilityState(
                "accessibility",
                if (accessibility) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING_PERMISSION,
                if (accessibility) null else "Enable Cyclone Mobile in Android Accessibility settings",
            ),
            CapabilityState(
                "notification_listener",
                if (notificationAccess) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING_PERMISSION,
                if (notificationAccess) null else "Grant notification access to Cyclone Mobile",
            ),
            CapabilityState(
                "calendar_read",
                if (calendar) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING_PERMISSION,
                if (calendar) null else "Grant READ_CALENDAR",
            ),
            CapabilityState(
                "screenshot",
                when {
                    Build.VERSION.SDK_INT < 30 -> CapabilityStatus.UNSUPPORTED_ON_DEVICE
                    !accessibility -> CapabilityStatus.MISSING_PERMISSION
                    else -> CapabilityStatus.AVAILABLE
                },
            ),
            CapabilityState("clipboard", CapabilityStatus.AVAILABLE, "Android may restrict clipboard reads when Cyclone is not foreground/active"),
            CapabilityState("app_launch", CapabilityStatus.AVAILABLE),
            CapabilityState("intent_launch", CapabilityStatus.AVAILABLE),
            CapabilityState(
                "media_projection",
                CapabilityStatus.UNSUPPORTED_ON_DEVICE,
                "Not configured in v0; Accessibility screenshot is the primary capture path",
            ),
            CapabilityState(
                "battery_optimization_exempt",
                if (batteryExempt) CapabilityStatus.AVAILABLE else CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                if (batteryExempt) null else "Optional: device is still subject to battery optimization",
            ),
        )
    }

    fun toJson(context: Context): JSONArray {
        val accessibility = CycloneAccessibilityService.instance != null
        return JSONArray().also { array ->
            snapshot(context).forEach { array.put(it.toJson()) }
            array.put(JSONObject()
                .put("name", "human_gesture")
                .put(
                    "status",
                    if (accessibility) CapabilityStatus.AVAILABLE.name else CapabilityStatus.MISSING_PERMISSION.name,
                )
                .put(
                    "detail",
                    if (accessibility) JSONObject.NULL else "Accessibility connection is required for foreground Human Gesture dispatch",
                )
                .put("runtime", HumanGestureRuntimeCapabilities.toJson(accessibility)))
        }
    }
}
