package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneTealGlassV2Test {
    private val frame = 1_000_000_000L / 60

    @Test fun livingBackdropIsCappedAt24FpsAndNeverJumpsOnResume() {
        val (c0, s0) = TealMatrixField.advance(10f, 0L, 5_000L)
        assertEquals(10f, c0, 0f)
        assertEquals(5_000L, s0)
        // Two 60 Hz vsyncs later: below the 24 fps interval, clock holds.
        val (c1, s1) = TealMatrixField.advance(c0, s0, s0 + 2 * frame)
        assertEquals(10f, c1, 0f)
        assertEquals(s0, s1)
        // Three vsyncs: advances by the real elapsed time.
        val (c2, s2) = TealMatrixField.advance(c1, s1, s1 + 3 * frame)
        assertEquals(10f + 3f / 60f, c2, 1e-4f)
        assertEquals(s1 + 3 * frame, s2)
        // Returning after 5 minutes in the background advances at most 0.1 s.
        val (c3, _) = TealMatrixField.advance(c2, s2, s2 + 300_000_000_000L)
        assertEquals(c2 + 0.1f, c3, 1e-4f)
    }

    @Test fun removeAnimationsFreezesTheField() {
        assertTrue(TealMatrixField.motionReduced(0f))
        assertFalse(TealMatrixField.motionReduced(1f))
        assertFalse(TealMatrixField.motionReduced(0.5f))
    }

    @Test fun matrixDotsAreFixedAndOnlyBrightnessBreathes() {
        val sksl = TealMatrixField.SOURCE
        assertTrue(sksl.contains("float2 cell = floor(xy / cellPx);"))
        // Dot position depends on the pixel grid only; time only modulates brightness.
        val placement = sksl.substringAfter("float2 cell = floor(xy / cellPx);").substringBefore("float lit")
        assertFalse(Regex("\\bt\\b").containsMatchIn(placement))
        // Dither is static: no per-frame grain shimmering under the glass.
        assertTrue(sksl.contains("col += (h(xy) - 0.5) / 255.0;"))
        assertFalse(sksl.contains("h(xy + t)"))
    }

    @Test fun tealGlassRendersTheCanvasItselfWithoutLayerCaptureOrBlur() {
        val glass = source("CycloneSignatureGlass.kt")
        assertTrue(glass.contains("val live = refract && !solidBacking && LocalTealMatrixField.current"))
        assertTrue(glass.contains("Modifier.tealGlass(cornerRadius, press = { focusLight.value * 0.7f })"))
        assertTrue(glass.contains("if (textured && !live)"))
        assertFalse(glass.contains("drawBackdrop("))
        assertFalse(glass.contains("blur("))
        val design = source("CycloneV32DesignSystem.kt")
        assertFalse(design.contains("rememberLayerBackdrop"))
        assertFalse(design.contains("layerBackdrop("))
        assertTrue(design.contains("LocalCycloneLiquidBackdrop provides null,"))
        // Controls layer only; content cards stay matte.
        assertTrue(source("CycloneLiquidChrome.kt").contains("refract = true,"))
        assertTrue(source("CycloneHomeComposer.kt").contains("refract = true,"))
        val matrix = source("CycloneTealMatrix.kt")
        assertTrue(matrix.substringAfter("fun CycloneMatrixQuickAction(").contains("refract = true,"))
        assertTrue(matrix.substringAfter("fun CycloneMatrixCard(").substringBefore("fun CycloneMatrixIconTile(").contains("solidBacking = true,"))
    }

    @Test fun glassShaderHasFrostLensAndSpecularButNoDots() {
        val sksl = TealMatrixField.GLASS_SOURCE
        assertTrue(sksl.contains("float3 frosted(float2 p)"))
        assertTrue(sksl.contains("float2 p = origin + xy - n * bend * band * 1.1;"))
        assertTrue(sksl.contains("float key = max(dot(n, normalize(float2(-0.45, -0.89))), 0.0);"))
        assertTrue(sksl.contains("float3 sceneBody("))
        assertFalse(sksl.contains("cellPx"))
        // Scene and glass share the exact same scene code.
        assertTrue(TealMatrixField.SOURCE.contains("float3 sceneBody("))
    }

    @Test fun shadersCompileOffTheMainThreadAtStartAndCapAt24Fps() {
        assertEquals(24f, TealMatrixField.FPS_CAP, 0f)
        val main = sequenceOf(File("src/main/java/com/cyclone/mobile/MainActivity.kt"), File("apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt")).first { it.isFile }.readText()
        assertTrue(main.contains("TealMatrixShaders.warmAsync()"))
        val field = source("CycloneTealMatrixField.kt")
        assertTrue(field.contains("Thread({ runCatching { field(); glass() } }, \"teal-matrix-shaders\")"))
        assertTrue(field.contains("TealMatrixStaticBackdrop(modifier)"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative")).first { it.isFile }.readText()
    }
}
