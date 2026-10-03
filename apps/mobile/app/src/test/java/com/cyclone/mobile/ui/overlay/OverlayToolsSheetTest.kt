package com.cyclone.mobile.ui.overlay

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayToolsSheetTest {
    @After fun reset() = OverlayToolsSheetState.close()

    @Test fun plusTogglesTheDrawerAndModelIsAPageInsideIt() {
        OverlayToolsSheetState.toggle(ComposerAccessory.ATTACHMENTS)
        assertEquals(ComposerAccessory.ATTACHMENTS, OverlayToolsSheetState.page.value)
        OverlayToolsSheetState.show(ComposerAccessory.MODEL)
        assertEquals(ComposerAccessory.MODEL, OverlayToolsSheetState.page.value)
        OverlayToolsSheetState.close()
        assertEquals(ComposerAccessory.NONE, OverlayToolsSheetState.page.value)
        OverlayToolsSheetState.toggle(ComposerAccessory.ATTACHMENTS)
        OverlayToolsSheetState.toggle(ComposerAccessory.ATTACHMENTS)
        assertEquals(ComposerAccessory.NONE, OverlayToolsSheetState.page.value)
    }

    @Test fun dragPastAThirdOrAFlickDownCloses() {
        val h = 1_000f
        assertFalse(OverlayToolsSheetPhysics.shouldDismiss(dragPx = 200f, velocityPxPerS = 0f, sheetHeightPx = h))
        assertTrue(OverlayToolsSheetPhysics.shouldDismiss(dragPx = 300f, velocityPxPerS = 0f, sheetHeightPx = h))
        assertTrue(OverlayToolsSheetPhysics.shouldDismiss(dragPx = 20f, velocityPxPerS = 1_500f, sheetHeightPx = h))
        // A fast upward fling keeps it open even after a long drag.
        assertFalse(OverlayToolsSheetPhysics.shouldDismiss(dragPx = 600f, velocityPxPerS = -1_500f, sheetHeightPx = h))
    }

    @Test fun openFractionDrivesBlurAndDimAndUpwardDragIsResisted() {
        assertEquals(1f, OverlayToolsSheetPhysics.openFraction(0f, 800f), 0f)
        assertEquals(0.5f, OverlayToolsSheetPhysics.openFraction(400f, 800f), 1e-4f)
        assertEquals(0f, OverlayToolsSheetPhysics.openFraction(900f, 800f), 0f)
        assertEquals(0f, OverlayToolsSheetPhysics.openFraction(0f, 0f), 0f)
        assertEquals(-18f, OverlayToolsSheetPhysics.resistedOffset(0f, -100f), 1e-4f)
        assertEquals(150f, OverlayToolsSheetPhysics.resistedOffset(100f, 50f), 1e-4f)
    }

    @Test fun drawerIsItsOwnBlurredWindowNotPartOfTheWorkPanel() {
        val chrome = source("ui/overlay/OverlayChrome.kt")
        assertFalse(chrome.contains("OverlayAppleToolsMenu("))
        assertTrue(chrome.contains("OverlayToolsSheetState.toggle(ComposerAccessory.ATTACHMENTS)"))
        assertFalse(chrome.contains("accessory != ComposerAccessory.NONE)"))
        val controller = source("ai/OverlayChromeController.kt")
        assertTrue(controller.contains("private fun syncToolsSheet("))
        assertTrue(controller.contains("FLAG_BLUR_BEHIND"))
        assertTrue(controller.contains("blurBehindRadius"))
        assertTrue(controller.contains("KEYCODE_BACK"))
        val sheet = source("ui/overlay/OverlayToolsSheet.kt")
        assertTrue(sheet.contains("orientation = Orientation.Vertical"))
        assertTrue(sheet.contains("contentDescription = \"Tools drawer handle\""))
    }

    @Test fun workingStateHasNoScreenOutline() {
        val controller = source("ai/OverlayChromeController.kt")
        assertFalse(controller.contains("renderActiveBorder"))
        assertFalse(controller.contains("0xFFA5C8FF"))
    }

    @Test fun traceFieldDigitsAreCalmAndNeverReshuffleOnPageChange() {
        val shader = source("ui/overlay/tracefield/TraceFieldShader.kt").replace("\r\n", "\n")
        assertTrue(shader.contains("void cellAt("))
        val cell = shader.substringAfter("void cellAt(").substringBefore("\n}\n")
        // Layout, cadence and glyph identity use the seed-free hash only.
        assertFalse(cell.contains("h21("))
        assertTrue(cell.contains("float rate = 0.25 + 0.7 * n21(c + 3.1) + scramble * 0.9;"))
        assertTrue(shader.contains("swap = smoothstep(0.0, 0.4, age);"))
        assertTrue(shader.contains("core = mix(atlasA(gPrev, local, 0.0), core, swap);"))
        assertFalse(shader.contains("floor(clock * 2.0)"))
    }

    @Test fun traceFieldFillsTheWholeGridSoAnySpotCanLightUp() {
        val shader = source("ui/overlay/tracefield/TraceFieldShader.kt").replace("\r\n", "\n")
        val cell = shader.substringAfter("void cellAt(").substringBefore("\n}\n")
        // No column or cell is ever left empty; a single grid keeps the highlight even.
        assertFalse(cell.contains("return false"))
        assertFalse(shader.contains("colGate"))
        assertFalse(shader.contains("cell * 0.72"))
    }

    @Test fun traceFieldDrawsTideScenesWithSmoothNoiseUnderTheChrome() {
        val shader = source("ui/overlay/tracefield/TraceFieldShader.kt").replace("\r\n", "\n")
        // Square value noise read as moving rectangles; scenes use smooth gradient noise instead.
        assertFalse(shader.contains("vnoise("))
        assertFalse(shader.contains("aurora("))
        assertTrue(shader.contains("float gnoise(float2 p)"))
        assertTrue(shader.contains("float scene(float id, float2 uv, float t, float asp)"))
        assertFalse(shader.lowercase().contains("gear"))
        // Scene brightness is sampled at the digit's centre (even digits) with a fixed halftone threshold.
        assertTrue(shader.contains("float2 uv = cellCentre / res;"))
        assertTrue(shader.contains("n21(cellId + 17.3)"))
        // Cyclone's own chrome keeps a soft cut-out: the field never shows through the Ask bar or work panel.
        assertTrue(shader.contains("if (dExcl < 0.0) return half4(0.0);"))
        assertTrue(shader.contains("m *= chromeFade;"))
    }

    @Test fun theStackIsPillThenCardThenAskBarAndChromeReportsItsBounds() {
        val drawer = source("ui/overlay/SignatureOverlayDrawer.kt")
        val column = drawer.substringAfter("horizontalAlignment = Alignment.CenterHorizontally,")
        // Plan 27: the plane pill on top, 10 dp, the card, 14 dp, then the Ask bar (or the island).
        val pill = column.indexOf("top()")
        val pillGap = column.indexOf("OverlayStackGeometry.PILL_GAP_DP")
        val card = column.indexOf("content = content")
        val cardGap = column.indexOf("OverlayStackGeometry.CARD_GAP_DP")
        val composer = column.indexOf("composer()")
        assertTrue(pill in 0 until pillGap)
        assertTrue(pillGap < card && card < cardGap && cardGap < composer)
        val chrome = source("ui/overlay/OverlayChrome.kt")
        assertTrue(chrome.contains("TraceFieldRuntime.chromeBounds("))
        assertTrue(chrome.contains("onSizeChanged { workCardPx = it.height }"))
    }

    private fun source(path: String): String {
        val relative = "src/main/java/com/cyclone/mobile/$path"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative")).first { it.isFile }.readText()
    }
}
