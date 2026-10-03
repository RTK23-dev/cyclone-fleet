package com.cyclone.mobile.ui

import androidx.compose.runtime.Composable
import com.cyclone.mobile.ui.v32.CycloneTheme as CycloneIdentityTheme

/**
 * Compatibility alias so secondary activities share the same identity theme as Home.
 * Dynamic Material / wallpaper colors were a second visual language and made result
 * and teaching screens look like a different product.
 */
@Composable
fun CycloneTheme(content: @Composable () -> Unit) {
    CycloneIdentityTheme(drawBackground = true, content = content)
}
