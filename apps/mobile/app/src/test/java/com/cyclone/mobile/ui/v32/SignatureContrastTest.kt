package com.cyclone.mobile.ui.v32

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text must remain readable on the protected card even when Android uses a light theme. */
class SignatureContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }

    @Test fun cardAndSecondaryCopyMeetNormalTextContrast() {
        for (background in listOf(SignatureScheme.surface, SignatureScheme.background, SignatureScheme.surfaceVariant)) {
            for (text in listOf(SignatureInk, SignatureMuted)) {
                assertTrue("Body text must meet 4.5:1 contrast", contrast(text, background) >= 4.5f)
            }
            assertTrue("Task backing must be opaque", background.alpha == 1f)
        }
    }

    @Test fun taskStatusAndActionsRemainReadable() {
        val colors = SignatureScheme
        for ((text, background) in listOf(
            colors.onPrimary to colors.primary,
            colors.primary to colors.primaryContainer,
            colors.secondary to colors.secondaryContainer,
            colors.tertiary to colors.tertiaryContainer,
            colors.error to colors.errorContainer,
        )) assertTrue("Status/action text must meet 4.5:1", contrast(text, background) >= 4.5f)
    }
}
