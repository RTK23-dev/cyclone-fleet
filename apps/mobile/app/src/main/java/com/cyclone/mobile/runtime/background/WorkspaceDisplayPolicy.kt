package com.cyclone.mobile.runtime.background

/**
 * AOSP DisplayManager virtual-display flag bits (API 34+).
 * PUBLIC is incompatible with OWN_DISPLAY_GROUP and is the ColorOS default-DisplayGroup escape.
 */
object WorkspaceDisplayPolicy {
    const val PUBLIC = 1 shl 0
    const val PRESENTATION = 1 shl 1
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val OWN_FOCUS = 1 shl 14
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 15

    const val PREFERRED_ISOLATION_FLAGS =
        TRUSTED or OWN_DISPLAY_GROUP or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED or
            DESTROY_CONTENT_ON_REMOVAL or OWN_CONTENT_ONLY or PRESENTATION

    val REQUIRED_VIRTUAL_DISPLAY_FLAG_FIELDS = listOf(
        "VIRTUAL_DISPLAY_FLAG_TRUSTED",
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
        "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
        "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
        "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
        "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
        "VIRTUAL_DISPLAY_FLAG_PRESENTATION",
    )

    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

    fun isolationFlags(): Int = PREFERRED_ISOLATION_FLAGS

    fun resolveFlags(lookup: (String) -> Int?): Int {
        if (lookup("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") == null ||
            lookup("VIRTUAL_DISPLAY_FLAG_TRUSTED") == null
        ) {
            error("BACKGROUND_MODE_UNAVAILABLE: TRUSTED and OWN_DISPLAY_GROUP virtual display flags are required")
        }
        var flags = 0
        for (name in REQUIRED_VIRTUAL_DISPLAY_FLAG_FIELDS) {
            val value = lookup(name) ?: error("BACKGROUND_MODE_UNAVAILABLE: $name is required")
            flags = flags or value
        }
        check(flags and PUBLIC == 0) { "BACKGROUND_MODE_UNAVAILABLE: PUBLIC virtual displays are not isolated" }
        return flags
    }

    fun launchCommand(displayId: Int, component: String): List<String> {
        require(displayId > 0) { "Launch requires a nonzero display" }
        require(component.matches(COMPONENT)) { "Invalid launch component" }
        return listOf("/system/bin/am", "start", "-W", "--display", displayId.toString(), "-n", component)
    }
}
