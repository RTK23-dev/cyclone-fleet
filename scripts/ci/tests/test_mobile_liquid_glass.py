from pathlib import Path
import unittest
import tomllib

ROOT = Path(__file__).resolve().parents[3]
APP_BUILD = ROOT / "apps/mobile/app/build.gradle.kts"
RENDERER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneKyantLiquidGlass.kt"
OVERRIDES = ROOT / "apps/mobile/app/src/main/java/androidx/compose/material3/CycloneLiquidGlassOverrides.kt"
THEME = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32DesignSystem.kt"


class MobileLiquidGlassGuards(unittest.TestCase):
    def test_release_identity_and_runtime_contract_are_preserved(self):
        build = APP_BUILD.read_text(encoding="utf-8")
        metadata = tomllib.loads((ROOT / "release/version.toml").read_text(encoding="utf-8"))
        self.assertIn(f'versionCode = {metadata["android_version_code"]}', build)
        self.assertIn(f'versionName = "{metadata["components"]["mobile"]}"', build)
        self.assertIn('minSdk = 33', build)
        self.assertIn('targetSdk = 35', build)
        self.assertIn('compileSdk = 36', build)
        self.assertIn('implementation("io.github.kyant0:backdrop:1.0.0")', build)
        self.assertIn('implementation("io.github.kyant0:capsule:2.1.1")', build)

    def test_renderer_keeps_upstream_liquid_button_recipe(self):
        source = RENDERER.read_text(encoding="utf-8")
        for token in (
            "ContinuousCapsule",
            "vibrancy()",
            "blur(2f.dp.toPx())",
            "lens(12f.dp.toPx(), 24f.dp.toPx())",
            "BlendMode.Hue",
            "RuntimeShader",
            "tanh(initialDerivative * offset.x / maxOffset)",
            "tanh(initialDerivative * offset.y / maxOffset)",
        ):
            self.assertIn(token, source)

    def test_material_action_families_route_through_liquid_layer(self):
        source = OVERRIDES.read_text(encoding="utf-8")
        for component in (
            "fun Button(",
            "fun FilledTonalButton(",
            "fun ElevatedButton(",
            "fun OutlinedButton(",
            "fun TextButton(",
            "fun IconButton(",
            "fun FilledIconButton(",
            "fun FilledTonalIconButton(",
            "fun OutlinedIconButton(",
        ):
            self.assertIn(component, source)
        self.assertIn("surfaceColor = surface", source)
        self.assertIn("private fun FallbackButton(", source)
        self.assertIn("private fun FallbackIconButton(", source)

    def test_custom_material_shape_and_padding_still_resolve_to_kyant_glass(self):
        source = OVERRIDES.read_text(encoding="utf-8")
        self.assertIn("shape: Shape", source)
        self.assertIn("contentPadding: PaddingValues", source)
        self.assertIn("contentPadding = contentPadding", source)
        renderer = RENDERER.read_text(encoding="utf-8")
        self.assertIn("shape = { ContinuousCapsule }", renderer)
        self.assertIn(".padding(contentPadding)", renderer)

    def test_nested_option_bars_do_not_draw_a_second_dark_box(self):
        chrome = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneLiquidChrome.kt").read_text(encoding="utf-8")
        selectors = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneLiquidSelectors.kt").read_text(encoding="utf-8")
        overlay = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt").read_text(encoding="utf-8")
        self.assertIn("LocalCycloneInsideLiquidHost", chrome)
        self.assertIn("chromaticAberration = true", chrome)
        self.assertNotIn("chromaticAberration = false", chrome)
        self.assertIn("LocalCycloneOverlayChrome", chrome)
        self.assertIn("0.88f", chrome)
        self.assertIn("0.84f", chrome)
        self.assertIn("blur(14f.dp.toPx())", chrome)
        self.assertNotIn("Color.White.copy(alpha = 0.22f)", chrome)
        self.assertNotIn("Color.Black.copy(alpha = 0.035f)", chrome)
        self.assertIn("LocalCycloneInsideLiquidHost.current", selectors)
        self.assertIn("CycloneSignatureGlass(", overlay)
        self.assertIn("SignatureInk", overlay)
        self.assertIn("textured = false", overlay)
        self.assertNotIn("chromaticAberration = false", overlay)
        self.assertNotIn("OverlayGlassRim", overlay)

    def test_theme_glass_samples_the_canvas_without_a_captured_layer(self):
        # alpha.22: re-blurring a captured layer of the animated canvas on every frame was slow and
        # refracted the dot matrix into noise. The theme draws one canvas and glass re-renders it
        # analytically (Modifier.tealGlass), so no recursive or per-frame backdrop capture exists.
        source = THEME.read_text(encoding="utf-8")
        self.assertNotIn("rememberLayerBackdrop(", source)
        self.assertNotIn("layerBackdrop(", source)
        self.assertIn("LocalCycloneLiquidBackdrop provides null", source)
        self.assertIn("LocalTealMatrixField provides true", source)
        self.assertIn("TealMatrixBackdrop(Modifier.fillMaxSize())", source)
        field = (THEME.parent / "CycloneTealMatrixField.kt").read_text(encoding="utf-8")
        self.assertIn("fun Modifier.tealGlass(", field)

    def test_only_known_lint_crashes_are_suppressed(self):
        build = APP_BUILD.read_text(encoding="utf-8")
        expected = {
            'disable += "RememberInComposition"',
            'disable += "NullSafeMutableLiveData"',
            'disable += "FrequentlyChangingValue"',
            'disable += "AutoboxingStateCreation"',
        }
        disabled = {
            line.strip()
            for line in build.splitlines()
            if line.strip().startswith("disable +=")
        }
        self.assertEqual(disabled, expected)
        self.assertNotIn("abortOnError = false", build)
        self.assertNotIn("checkReleaseBuilds = false", build)


if __name__ == "__main__":
    unittest.main()
