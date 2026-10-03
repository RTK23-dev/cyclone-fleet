import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"


class MobileWorkflowArchitectureTest(unittest.TestCase):
    def text(self, name: str) -> str:
        return (WORKFLOWS / name).read_text(encoding="utf-8")

    def test_only_reusable_mobile_lane_assembles_android(self):
        assemblers = []
        for path in WORKFLOWS.glob("*.yml"):
            text = path.read_text(encoding="utf-8")
            if ":app:assembleDebug" in text or ":app:assembleRelease" in text:
                assemblers.append(path.name)
        self.assertEqual(["_mobile-build.yml"], assemblers)

    def test_build_is_single_product_and_guards_run_before_toolchain(self):
        workflow = self.text("_mobile-build.yml")
        guard = workflow.index("mobile_metadata.py")
        for later in ("actions/setup-java", "android-actions/setup-android", "gradle/actions/setup-gradle"):
            self.assertLess(guard, workflow.index(later))
        self.assertEqual(1, workflow.count("./apps/mobile/gradlew"))
        self.assertIn("./apps/mobile/gradlew -p apps/mobile", workflow)
        self.assertIn(":app:testDebugUnitTest :app:lintDebug :app:assembleRelease", workflow)
        self.assertIn("outputs/apk/release/app-release-unsigned.apk", workflow)
        self.assertIn("UNSIGNED_VERIFIED_CANDIDATE", workflow)
        self.assertIn("repository_security_guard.py", workflow)
        self.assertIn("testOnly", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertNotIn("apps/teamwork-sniper", workflow)
        self.assertNotIn("git submodule", workflow)
        self.assertLess(workflow.index("wrapper-validation"), workflow.index("./apps/mobile/gradlew"))
        self.assertIn("Upload Android test and lint reports\n        if: always()", workflow)
        self.assertIn("apps/mobile/app/build/test-results/", workflow)

    def test_release_reuses_verifies_and_signs_without_publication(self):
        workflow = self.text("mobile-release.yml")
        self.assertIn("actions/download-artifact", workflow)
        self.assertIn("sha256sum --check", workflow)
        self.assertNotIn("gradlew", workflow)
        self.assertNotIn("gh release", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("environment: mobile-release-approval", workflow)
        self.assertIn(".github/workflows/mobile-ci.yml", workflow)
        self.assertIn(".conclusion", workflow)
        self.assertIn("'.event'", workflow)
        self.assertIn('= "push"', workflow)
        self.assertIn("EXPECTED_HEAD_SHA", workflow)
        self.assertIn("run-id.txt", workflow)
        self.assertIn("'.head_repository.full_name'", workflow)
        self.assertIn("release/cyclone-mobile-v*", workflow)
        self.assertNotIn("main|release/cyclone-mobile-v*", workflow)
        self.assertIn("UNSIGNED_VERIFIED_CANDIDATE", workflow)
        self.assertIn("CYCLONE_ANDROID_KEYSTORE_B64", workflow)
        self.assertIn("CYCLONE_ANDROID_STORE_PASSWORD", workflow)
        self.assertIn("CYCLONE_ANDROID_KEY_ALIAS", workflow)
        self.assertIn("CYCLONE_ANDROID_KEY_PASSWORD", workflow)
        self.assertIn("apksigner", workflow)
        self.assertIn("SIGNED_PROTECTED_ENVIRONMENT", workflow)
        self.assertNotIn("teamwork-sniper", workflow)

    def test_authoritative_ci_tracks_only_current_product_paths(self):
        workflow = self.text("mobile-ci.yml")
        self.assertIn("\n  push:", workflow)
        self.assertIn("\n  pull_request:", workflow)
        self.assertIn("paths:", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        for required in (
            "'apps/mobile/**'",
            "'apps/device-gateway/**'",
            "'tools/codex-phone-mcp/**'",
            "'tools/cyclone-agent-mcp/**'",
            "'scripts/ci/**'",
            "'release/version.toml'",
        ):
            self.assertIn(required, workflow)
        for retired in ("teamwork-sniper", "third_party/mobilerun-portal", "feature/cyclone-3.5", "agent/352"):
            self.assertNotIn(retired, workflow)

    def test_retired_mobile_workflows_are_absent(self):
        retired = (
            "android-mobile.yml",
            "mobile-fast-apk.yml",
            "mobile-v2-embedded.yml",
            "cyclone-v2.9.4-full-gateway.yml",
            "cyclone-v2.9.5-original-ui-gateway.yml",
            "apply-v295-ui-polish.yml",
            "mobile-ai-runtime.yml",
            "mobile-backend.yml",
        )
        for name in retired:
            self.assertFalse((WORKFLOWS / name).exists(), name)

    def test_external_workflow_actions_are_immutable_sha_pinned(self):
        for name in ("_mobile-build.yml", "mobile-release.yml"):
            for action, ref in re.findall(r"uses:\s*([^\s@]+)@([^\s#]+)", self.text(name)):
                if action.startswith("./"):
                    continue
                self.assertRegex(ref, r"^[0-9a-f]{40}$", f"{name}: {action}")


if __name__ == "__main__":
    unittest.main()
