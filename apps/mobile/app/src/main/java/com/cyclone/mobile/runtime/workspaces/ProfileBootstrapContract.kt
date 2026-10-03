package com.cyclone.mobile.runtime.workspaces

/** Only preferences with portable meaning cross users. Never copy task authority or device tokens. */
object ProfileBootstrapContract {
    const val PACKAGE = "com.cyclone.mobile"
    val aiKeys = setOf("openrouter_model", "openrouter_reasoning_effort", "ai_access_profile", "safe_mode")
    val permissions = setOf("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO", "android.permission.READ_CALENDAR")
    const val ACCESSIBILITY = "$PACKAGE/.CycloneAccessibilityService"
    const val LISTENER = "$PACKAGE/.CycloneNotificationListener"
    fun portablePreferences(values: Map<String, *>): Map<String, Any> = values.entries
        .filter { it.key in aiKeys && (it.value is String || it.value is Boolean) }
        .associate { it.key to it.value!! }
    fun userId(uid: Int): Int = uid / 100_000
    fun targetUid(appId: Int, user: Int): Int {
        require(user in 0..21473 && appId in 10000..99999)
        return user * 100_000 + appId
    }
    fun validateTransfer(source: Int, target: Int, current: Int) {
        require(source >= 0 && target > 0 && source != target && target == current) { "Profile transfer identity mismatch" }
    }
    fun mergeServiceList(existing: String, component: String): String {
        require(component in setOf(ACCESSIBILITY, LISTENER))
        return (existing.split(':').filter { it.isNotBlank() && it != "null" } + component).distinct().joinToString(":")
    }
}
