package com.cyclone.mobile.runtime.plane

import org.json.JSONObject

/**
 * Planes (plan 25): where a task is running right now. The task (a Mind mission) is the stable thing; its plane can
 * change while it runs. The main screen is display 0 (`default-foreground`); a background plane is a Cyclone-owned
 * isolated virtual display with its own session id.
 */
enum class PlaneKind(val wire: String, val label: String) {
    SCREEN("screen", "On screen"),
    BACKGROUND("background", "In the background"),
    ;

    val other: PlaneKind get() = if (this == SCREEN) BACKGROUND else SCREEN

    companion object {
        fun fromWire(raw: String?): PlaneKind? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

sealed class TaskPlane {
    abstract val kind: PlaneKind
    abstract val sessionId: String
    abstract val displayId: Int

    data object Screen : TaskPlane() {
        override val kind = PlaneKind.SCREEN
        override val sessionId = SCREEN_SESSION
        override val displayId = 0
    }

    data class Background(override val sessionId: String, override val displayId: Int) : TaskPlane() {
        init {
            require(sessionId != SCREEN_SESSION && sessionId.isNotBlank()) { "A background plane has its own session" }
            require(displayId > 0) { "A background plane never uses display 0" }
        }
        override val kind = PlaneKind.BACKGROUND
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.wire).put("sessionId", sessionId).put("displayId", displayId)

    companion object {
        const val SCREEN_SESSION = "default-foreground"

        fun fromJson(json: JSONObject?): TaskPlane? = json?.let {
            when (PlaneKind.fromWire(it.optString("kind"))) {
                PlaneKind.SCREEN -> Screen
                PlaneKind.BACKGROUND -> runCatching { Background(it.getString("sessionId"), it.getInt("displayId")) }.getOrNull()
                null -> null
            }
        }
    }
}
