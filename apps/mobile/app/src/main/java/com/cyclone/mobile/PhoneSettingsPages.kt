package com.cyclone.mobile

/**
 * The Android Settings pages Cyclone may open directly (phone.open_settings). Opening a page only navigates; any
 * change on it is a separate, observed and policy-checked action. Keys are stable wire names; values are the public
 * Settings intent actions.
 */
object PhoneSettingsPages {
    data class Page(val action: String, val needsPackage: Boolean = false, val description: String)

    val pages: Map<String, Page> = linkedMapOf(
        "main" to Page("android.settings.SETTINGS", description = "Settings home"),
        "wifi" to Page("android.settings.WIFI_SETTINGS", description = "Wi-Fi networks"),
        "bluetooth" to Page("android.settings.BLUETOOTH_SETTINGS", description = "Bluetooth devices"),
        "network" to Page("android.settings.WIRELESS_SETTINGS", description = "Network and internet"),
        "airplane_mode" to Page("android.settings.AIRPLANE_MODE_SETTINGS", description = "Airplane mode"),
        "mobile_data" to Page("android.settings.DATA_ROAMING_SETTINGS", description = "Mobile network"),
        "data_usage" to Page("android.settings.DATA_USAGE_SETTINGS", description = "Data usage"),
        "nfc" to Page("android.settings.NFC_SETTINGS", description = "NFC"),
        "cast" to Page("android.settings.CAST_SETTINGS", description = "Screen cast"),
        "display" to Page("android.settings.DISPLAY_SETTINGS", description = "Display, brightness, dark theme"),
        "sound" to Page("android.settings.SOUND_SETTINGS", description = "Sound and vibration"),
        "do_not_disturb" to Page("android.settings.ZEN_MODE_PRIORITY_SETTINGS", description = "Do Not Disturb"),
        "notifications" to Page("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", description = "Notifications for all apps"),
        "app_notifications" to Page("android.settings.APP_NOTIFICATION_SETTINGS", needsPackage = true, description = "Notifications of one app"),
        "apps" to Page("android.settings.APPLICATION_SETTINGS", description = "Apps"),
        "app_details" to Page("android.settings.APPLICATION_DETAILS_SETTINGS", needsPackage = true, description = "App info of one app (storage, permissions, force stop)"),
        "default_apps" to Page("android.settings.MANAGE_DEFAULT_APPS_SETTINGS", description = "Default apps"),
        "battery" to Page("android.settings.BATTERY_SAVER_SETTINGS", description = "Battery saver"),
        "storage" to Page("android.settings.INTERNAL_STORAGE_SETTINGS", description = "Storage"),
        "location" to Page("android.settings.LOCATION_SOURCE_SETTINGS", description = "Location"),
        "security" to Page("android.settings.SECURITY_SETTINGS", description = "Security"),
        "privacy" to Page("android.settings.PRIVACY_SETTINGS", description = "Privacy"),
        "accounts" to Page("android.settings.SYNC_SETTINGS", description = "Accounts and sync"),
        "date_time" to Page("android.settings.DATE_SETTINGS", description = "Date and time"),
        "language" to Page("android.settings.LOCALE_SETTINGS", description = "Languages"),
        "keyboard" to Page("android.settings.INPUT_METHOD_SETTINGS", description = "Keyboards"),
        "accessibility" to Page("android.settings.ACCESSIBILITY_SETTINGS", description = "Accessibility"),
        "developer" to Page("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", description = "Developer options"),
        "about_phone" to Page("android.settings.DEVICE_INFO_SETTINGS", description = "About phone"),
        "internet_panel" to Page("android.settings.panel.action.INTERNET_CONNECTIVITY", description = "Quick internet panel"),
        "volume_panel" to Page("android.settings.panel.action.VOLUME", description = "Quick volume panel"),
    )

    fun page(key: String): Page? = pages[key.trim().lowercase()]

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    fun validPackage(value: String): Boolean = PACKAGE.matches(value)
}
