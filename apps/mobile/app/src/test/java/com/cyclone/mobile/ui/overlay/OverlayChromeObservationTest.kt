package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.UiWindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayChromeObservationTest {
    @Test
    fun siblingFilterKeepsApplicationAndAccessibilityOverlay() {
        assertTrue(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                OverlayChromeObservation.APPLICATION_WINDOW_TYPE,
                "com.google.android.apps.nbu.files",
                isWebish = false,
            ),
        )
        assertFalse(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE,
                "com.cyclone.mobile",
                isWebish = false,
            ),
        )
        assertTrue(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE,
                "com.cyclone.mobile",
                isWebish = false,
                includeAccessibilityOverlay = true,
            ),
        )
        assertTrue(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                3,
                "com.android.chrome",
                isWebish = true,
            ),
        )
        assertFalse(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE,
                "com.android.systemui",
                isWebish = false,
            ),
        )
        assertFalse(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                3,
                "com.google.android.apps.nbu.files",
                isWebish = false,
            ),
        )
        assertFalse(
            OverlayChromeObservation.shouldCollectSiblingWindow(
                OverlayChromeObservation.APPLICATION_WINDOW_TYPE,
                "",
                isWebish = false,
            ),
        )
    }

    @Test
    fun idleChromeIsNotInjectedIntoHostSnapshot() {
        val host = filesSheet()
        val idle = OverlayChromeSnapshot()
        assertEquals(OverlayChromeState.IDLE, idle.state)
        val merged = OverlayChromeObservation.merge(host, idle)
        assertEquals(host.nodes, merged.nodes)
        assertFalse(OverlayChromeObservation.hasGate(merged))
        assertTrue(merged.windows.none { it.type == OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE })
    }

    @Test
    fun gateFromIdleInjectsExactCopyAndOverlayWindow() {
        val host = filesSheet()
        val machine = OverlayChromeMachine()
        machine.enterGate(OverlayGateClass.DELETE)
        val merged = OverlayChromeObservation.merge(host, machine.snapshot())
        assertTrue(OverlayChromeObservation.hasGate(merged))
        assertTrue(merged.nodes.any { it.text == OverlayCopy.GATE })
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
        assertTrue(merged.nodes.any { it.text == OverlayCopy.CONFIRM })
        assertTrue(merged.windows.any { it.type == OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE })
        assertTrue(host.nodes.none { OverlayCopy.GATE in it.text })
    }

    @Test
    fun doesNotDuplicateGateCopyWhenOverlayWindowAlreadyInTree() {
        val machine = OverlayChromeMachine()
        machine.enterGate(OverlayGateClass.DELETE)
        val withChrome = OverlayChromeObservation.merge(filesSheet(), machine.snapshot())
        val again = OverlayChromeObservation.merge(withChrome, machine.snapshot())
        val gateNodes = again.nodes.filter { OverlayCopy.GATE in it.text }
        assertEquals(1, gateNodes.size)
    }

    private fun filesSheet(): UiSnapshot {
        val bin = UiNodeSnapshot(
            id = "bin",
            path = "0/1",
            parentId = "0",
            childIds = emptyList(),
            depth = 1,
            windowId = 1,
            className = "android.widget.Button",
            role = "button",
            text = "Move to bin",
            contentDescription = "",
            resourceId = "",
            bounds = UiBounds(0, 0, 10, 10),
            clickable = true,
            longClickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
            selected = false,
            checked = false,
            checkable = false,
            focused = false,
            focusable = true,
            visibleToUser = true,
            actions = listOf("ACTION_CLICK"),
        )
        return UiSnapshot(
            packageName = "com.google.android.apps.nbu.files",
            className = "Files",
            screenWidth = 1080,
            screenHeight = 2400,
            timestampMs = 1,
            fingerprint = "fp",
            controller = "agent",
            windows = listOf(
                UiWindowSnapshot(
                    id = 1,
                    title = "Files",
                    type = OverlayChromeObservation.APPLICATION_WINDOW_TYPE,
                    layer = 0,
                    active = true,
                    focused = true,
                    bounds = UiBounds(0, 0, 1080, 2400),
                ),
            ),
            nodes = listOf(bin),
        )
    }
}
