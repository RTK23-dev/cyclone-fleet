package com.cyclone.mobile.ui.overlay
import org.junit.Assert.*
import org.junit.Test
class SheetDismissalTest {
    @Test fun shortDragSettlesAndDeliberateDragDismissesAcrossSheetSizes() {
        for (height in listOf(100f, 360f, 800f)) {
            assertFalse(SheetDismissal.shouldDismiss(height * .1f, height))
            assertTrue(SheetDismissal.shouldDismiss(height * .4f, height))
        }
        assertFalse(SheetDismissal.shouldDismiss(-10f, 100f))
        assertFalse(SheetDismissal.shouldDismiss(10f, 0f))
    }
}
