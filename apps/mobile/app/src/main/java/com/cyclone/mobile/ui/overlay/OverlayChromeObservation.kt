package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.UiWindowSnapshot

/**
 * Overlay chrome lives in a TYPE_ACCESSIBILITY_OVERLAY window. Host snapshots prefer the
 * foreground app and previously skipped overlay siblings, so GATE copy never reached yaml.
 * Merge visible overlay strings into the observation so hasGate can see them.
 */
object OverlayChromeObservation {
    /** AccessibilityWindowInfo.TYPE_APPLICATION */
    const val APPLICATION_WINDOW_TYPE = 1

    /** AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY */
    const val ACCESSIBILITY_OVERLAY_WINDOW_TYPE = 4

    const val OVERLAY_WINDOW_ID = 0x0C4C0E
    const val OVERLAY_WINDOW_TITLE = "Cyclone overlay"

    fun hasGate(nodes: List<UiNodeSnapshot>): Boolean =
        nodes.any { OverlayCopy.GATE in it.text || OverlayCopy.GATE in it.contentDescription }

    fun hasGate(snapshot: UiSnapshot): Boolean = hasGate(snapshot.nodes)

    fun shouldCollectSiblingWindow(
        type: Int,
        packageName: String,
        isWebish: Boolean,
        includeAccessibilityOverlay: Boolean = false,
    ): Boolean {
        if (packageName.isBlank() || packageName == "com.android.systemui") return false
        return type == APPLICATION_WINDOW_TYPE ||
            (type == ACCESSIBILITY_OVERLAY_WINDOW_TYPE && includeAccessibilityOverlay) ||
            isWebish
    }

    fun chromeVisibleIn(nodes: List<UiNodeSnapshot>, overlay: OverlayChromeSnapshot): Boolean {
        if (overlay.state == OverlayChromeState.GATE) return hasGate(nodes)
        val visible = OverlayCopy.visibleFor(overlay)
        if (visible.isEmpty()) return true
        return visible.all { copy ->
            nodes.any { copy in it.text || copy in it.contentDescription }
        }
    }

    fun merge(host: UiSnapshot, overlay: OverlayChromeSnapshot): UiSnapshot {
        val nodes = host.nodes.toMutableList()
        val windows = host.windows.toMutableList()
        appendChrome(nodes, windows, overlay, host.screenWidth, host.screenHeight)
        return host.copy(nodes = nodes, windows = windows)
    }

    fun appendChrome(
        nodes: MutableList<UiNodeSnapshot>,
        windows: MutableList<UiWindowSnapshot>,
        overlay: OverlayChromeSnapshot,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        // Only GATE chrome belongs in the host observation. Pulling the full animated Compose
        // tree into every snapshot can exhaust the ref budget and hide host controls such as
        // Clock tabs. GATE remains explicit and observable without crowding ordinary pages.
        if (overlay.state != OverlayChromeState.GATE || overlay.minimized) return
        if (!chromeVisibleIn(nodes, overlay)) {
            nodes += nodesFor(overlay, screenWidth, screenHeight)
        }
        ensureWindow(windows, overlay, screenWidth, screenHeight)
    }

    fun nodesFor(overlay: OverlayChromeSnapshot, screenWidth: Int, screenHeight: Int): List<UiNodeSnapshot> {
        val copies = OverlayCopy.visibleFor(overlay)
        if (copies.isEmpty() || overlay.state != OverlayChromeState.GATE || overlay.minimized) return emptyList()
        val width = screenWidth.coerceAtLeast(8)
        val height = screenHeight.coerceAtLeast(8)
        val line = 10
        val top0 = (height - copies.size * line - 8).coerceAtLeast(0)
        return copies.mapIndexed { index, copy ->
            val isConfirm = copy == OverlayCopy.CONFIRM
            val isGateCopy = copy == OverlayCopy.GATE
            val publishHost = isConfirm || isGateCopy
            val top = (top0 + index * line).coerceAtMost(height - 2)
            val bottom = (top + 8).coerceAtMost(height).coerceAtLeast(top + 1)
            UiNodeSnapshot(
                id = "overlay-chrome-$index",
                path = "overlay/0/$index",
                parentId = "overlay-chrome",
                childIds = emptyList(),
                depth = 1,
                windowId = OVERLAY_WINDOW_ID,
                className = if (isConfirm) "android.widget.Button" else "android.widget.TextView",
                role = if (isConfirm) "button" else "text",
                text = copy,
                contentDescription = copy,
                resourceId = "com.cyclone.mobile:id/overlay_chrome_$index",
                bounds = UiBounds(4, top, (width - 4).coerceAtLeast(5), bottom),
                clickable = publishHost,
                longClickable = false,
                editable = false,
                scrollable = false,
                enabled = true,
                selected = false,
                checked = false,
                checkable = false,
                focused = false,
                focusable = publishHost,
                visibleToUser = true,
                actions = if (publishHost) listOf("ACTION_CLICK") else emptyList(),
            )
        }
    }

    private fun ensureWindow(
        windows: MutableList<UiWindowSnapshot>,
        overlay: OverlayChromeSnapshot,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (overlay.state != OverlayChromeState.GATE || overlay.minimized) return
        if (windows.any { it.type == ACCESSIBILITY_OVERLAY_WINDOW_TYPE || it.id == OVERLAY_WINDOW_ID }) return
        val height = screenHeight.coerceAtLeast(1)
        val width = screenWidth.coerceAtLeast(1)
        windows += UiWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            title = OVERLAY_WINDOW_TITLE,
            type = ACCESSIBILITY_OVERLAY_WINDOW_TYPE,
            layer = Int.MAX_VALUE,
            active = false,
            focused = false,
            bounds = UiBounds(0, (height * 3 / 4).coerceAtLeast(0), width, height),
        )
    }
}
