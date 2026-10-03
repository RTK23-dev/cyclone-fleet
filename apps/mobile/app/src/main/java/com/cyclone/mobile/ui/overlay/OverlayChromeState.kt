package com.cyclone.mobile.ui.overlay

/**
 * Frozen V4 overlay chrome states. One Compose overlay, not five screens and not a seventh tab.
 */
enum class OverlayChromeState {
    IDLE,
    ANALYSIS,
    WORKING,
    LIVE,
    GATE,
    DONE,
    ;

    companion object {
        val NAMES: List<String> = entries.map { it.name }

        fun parse(raw: String): OverlayChromeState {
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException("Unknown overlay state: $raw")
        }
    }
}

enum class OverlayAnalysisCta {
    CONFIRM,
    COMMERCE,
}

enum class OverlayGateClass(val wire: String) {
    PAY("pay"),
    SEND("send"),
    DELETE("delete"),
    GRANT("grant"),
    ;

    companion object {
        fun parse(raw: String): OverlayGateClass {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wire == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown gate class: $raw")
        }
    }
}

enum class OverlayUserAction {
    ASK_CYCLONE,
    CONFIRM,
    COMMERCE,
    VIEW_PROGRESS,
    STOP_TASK,
    TAKE_CONTROL,
    GATE_CONFIRM,
    MINIMIZE,
    EXIT,
}

data class OverlayChromeSnapshot(
    val state: OverlayChromeState = OverlayChromeState.IDLE,
    val sessionId: String = "",
    val bullets: List<String> = emptyList(),
    val analysisCta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    val gateClass: OverlayGateClass? = null,
    val idleChipVisible: Boolean = true,
    val pcAutoApproveIgnored: Boolean = false,
    val minimized: Boolean = false,
    /** True only for the tiny triple-tap launcher. minimized=true + launcherCollapsed=false is the usable composer. */
    val launcherCollapsed: Boolean = false,
    val userPaused: Boolean = false,
    val composerText: String = "",
    val voiceListening: Boolean = false,
    val voiceMessage: String? = null,
    val statusMessage: String? = null,
)
