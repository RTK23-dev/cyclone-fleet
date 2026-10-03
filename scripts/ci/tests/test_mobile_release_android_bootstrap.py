from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / ".github/workflows/_mobile-build.yml"
PUBLISH = ROOT / ".github/workflows/mobile-publish-v3910.yml"
V5_SIGN = ROOT / ".github/workflows/mobile-release.yml"


class MobileReleaseAndroidBootstrapGuards(unittest.TestCase):
    def test_ci_and_publisher_never_request_retired_android_tools_package(self):
        build = BUILD.read_text(encoding="utf-8")
        publish = PUBLISH.read_text(encoding="utf-8")

        self.assertIn("packages: platform-tools", build)
        self.assertIn("packages: platform-tools", publish)

        signing_setup = publish.split("- name: Set up Android signing tools", 1)[1].split(
            "- name: Install Android build tools", 1
        )[0]
        self.assertIn("packages: platform-tools", signing_setup)
        self.assertNotIn("packages: tools", signing_setup)

    def test_v5_rotated_candidate_covers_mobile_min_sdk_and_exports_lineage_file(self):
        signing = V5_SIGN.read_text(encoding="utf-8")
        mobile = (ROOT / "apps/mobile/app/build.gradle.kts").read_text(encoding="utf-8")
        sdk_setup = signing.split("- name: Set up Android SDK signing tools", 1)[1].split(
            "- name: Install Android build tools", 1
        )[0]
        self.assertIn("packages: platform-tools", sdk_setup)
        self.assertIn("minSdk = 33", mobile)
        self.assertIn("--rotation-min-sdk-version 33", signing)
        self.assertIn('verify --min-sdk-version 33 --verbose --print-certs "signed/$APK_NAME"', signing)
        self.assertIn('cp "$lineage" signed/signing-lineage.bin', signing)
        self.assertIn('lineage --in signed/signing-lineage.bin --print-certs -v', signing)
        self.assertNotIn('lineage --in "signed/$APK_NAME"', signing)


if __name__ == "__main__":
    unittest.main()
