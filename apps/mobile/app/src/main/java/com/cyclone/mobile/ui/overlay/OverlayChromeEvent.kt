package com.cyclone.mobile.ui.overlay

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class OverlayChromeEventKind {
    ASK_CYCLONE,
    CONFIRM,
    COMMERCE,
    VIEW_PROGRESS,
    STOP_TASK,
    TAKE_CONTROL,
    GATE,
    GATE_CONFIRM,
    DONE,
}

/**
 * Cyclone overlay events. Overlay buttons never click host accessibility nodes.
 * Agent 3 sinks GATE / DONE. This slice does not write AutomationStore.
 */
data class OverlayChromeEvent(
    val kind: OverlayChromeEventKind,
    val state: OverlayChromeState,
    val sessionId: String,
    val gateClass: OverlayGateClass? = null,
    val pcAutoApproveIgnored: Boolean = false,
    val requestText: String? = null,
    val userPaused: Boolean = false,
    val clicksHost: Boolean = false,
    val dispatchAccessibilityAction: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", OverlayChromeContract.JSON_TYPE)
        .put("kind", kind.name)
        .put("state", state.name)
        .put("sessionId", sessionId)
        .put("clicksHost", clicksHost)
        .put("dispatchAccessibilityAction", dispatchAccessibilityAction)
        .put("pcAutoApproveIgnored", pcAutoApproveIgnored)
        .put("requestText", requestText ?: JSONObject.NULL)
        .put("userPaused", userPaused)
        .put("gateClass", gateClass?.wire ?: JSONObject.NULL)
}

fun interface OverlayChromeListener {
    fun onOverlayEvent(event: OverlayChromeEvent)
}

object OverlayChromeBus {
    private val listeners = CopyOnWriteArrayList<OverlayChromeListener>()

    fun subscribe(listener: OverlayChromeListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun publish(event: OverlayChromeEvent) {
        listeners.forEach { listener ->
            runCatching { listener.onOverlayEvent(event) }
        }
    }

    fun clearSubscribers() {
        listeners.clear()
    }
}
