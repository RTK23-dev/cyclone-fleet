import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "mobile_metadata.py"
SPEC = importlib.util.spec_from_file_location("mobile_metadata", SCRIPT)
mobile_metadata = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(mobile_metadata)


class MobileMetadataTest(unittest.TestCase):
    def read(self, gradle: str, *, extra_manifest: str = "", extra_executor: tuple[str, str] | None = None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = root / "src"
        source.mkdir()
        (source / "PhoneToolExecutor.kt").write_text("public object PhoneToolExecutor {}", encoding="utf-8")
        if extra_executor:
            (source / extra_executor[0]).write_text(extra_executor[1], encoding="utf-8")
        manifest = root / "AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
            '<activity android:name=".MainActivity"><intent-filter>'
            '<action android:name="android.intent.action.MAIN"/>'
            '<category android:name="android.intent.category.LAUNCHER"/>'
            f'</intent-filter></activity>{extra_manifest}</application></manifest>',
            encoding="utf-8",
        )
        gradle_file = root / "build.gradle.kts"
        gradle_file.write_text(gradle, encoding="utf-8")
        with patch.multiple(mobile_metadata, GRADLE=gradle_file, MANIFEST=manifest, SOURCES=source):
            return mobile_metadata.read_metadata()

    def test_plain_semver_is_canonical(self):
        metadata = self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\n')
        self.assertEqual("Cyclone-2.9.5.apk", metadata["apk_name"])
        self.assertEqual("Cyclone-Android-2.9.5", metadata["artifact_name"])

    def test_decorated_semver_is_supported(self):
        metadata = self.read('applicationId = "com.cyclone.mobile"\nversionCode = 18\nversionName = "3.0.0-beta.1"\n')
        self.assertEqual("3.0.0-beta.1", metadata["version_name"])

    def test_duplicate_version_assignment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one versionName"):
            self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\nversionName = "3.0.0"\n')

    def test_malformed_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "malformed versionName"):
            self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "release-v3"\n')

    def test_second_launcher_activity_or_alias_is_rejected(self):
        gradle = 'applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\n'
        for extra in (
            '<activity android:name=".Other"><intent-filter><action android:name="android.intent.action.MAIN"/>'
            '<category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>',
            '<activity-alias android:name=".Alias" android:targetActivity=".MainActivity"><intent-filter>'
            '<action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>'
            '</intent-filter></activity-alias>',
        ):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "one .MainActivity launcher"):
                self.read(gradle, extra_manifest=extra)

    def test_modified_kotlin_and_java_executor_duplicates_are_detected(self):
        gradle = 'applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\n'
        with self.assertRaisesRegex(ValueError, "one canonical PhoneToolExecutor"):
            self.read(
                gradle,
                extra_executor=("PhoneToolExecutor.java", "public final class PhoneToolExecutor {}"),
            )


if __name__ == "__main__":
    unittest.main()
