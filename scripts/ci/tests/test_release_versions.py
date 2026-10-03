import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "release_versions.py"
SPEC = importlib.util.spec_from_file_location("release_versions", SCRIPT)
release_versions = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(release_versions)


class ReleaseVersionsTest(unittest.TestCase):
    def coherent_values(self):
        return {
            "product": "3.9.0",
            "expectedMobile": "3.9.0",
            "expectedPc": "3.8.4",
            "expectedGatewayPython": "3.8.4",
            "expectedMcpPython": "3.8.4",
            "androidVersionName": "3.9.0",
            "androidVersionCode": 54,
            "gatewayPython": "3.8.4",
            "mcpPython": "3.8.4",
            "agentMcpPython": "3.8.4",
            "pcPackage": "3.8.4",
            "pcPackageLock": "3.8.4",
            "pcCargo": "3.8.4",
            "pcTauri": "3.8.4",
            "expectedAndroidVersionCode": 54,
        }

    def test_current_component_versions_are_allowed_to_be_independent(self):
        self.assertEqual([], release_versions.check(self.coherent_values()))

    def test_mobile_version_drift_is_rejected(self):
        values = self.coherent_values()
        values["androidVersionName"] = "3.8.9"
        self.assertIn(
            "androidVersionName='3.8.9' expected '3.9.0'",
            release_versions.check(values),
        )

    def test_version_code_must_advance_past_preserved_3_5_1(self):
        values = self.coherent_values()
        values["androidVersionCode"] = 37
        values["expectedAndroidVersionCode"] = 37
        self.assertIn(
            "Distributed Cyclone Android builds must remain above the preserved 3.5.1 versionCode 37",
            release_versions.check(values),
        )

    def test_glass_has_its_own_version_line(self):
        values = {**self.coherent_values(), "expectedGlass": "1.0.0-alpha.1", "glassPackage": "1.0.0-alpha.1"}
        self.assertEqual([], release_versions.check(values))
        values["glassPackage"] = "1.6.0-alpha.7"
        self.assertIn("glassPackage='1.6.0-alpha.7' expected '1.0.0-alpha.1'", release_versions.check(values))
        values["expectedGlass"] = ""
        self.assertIn(
            "components.glass is required: Cyclone Glass (apps/glass) has its own version line",
            release_versions.check(values),
        )

    def test_live_tree_reports_glass(self):
        values = release_versions.collect()
        self.assertTrue(values["expectedGlass"])
        self.assertEqual(values["expectedGlass"], values["glassPackage"])


if __name__ == "__main__":
    unittest.main()
