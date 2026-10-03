"""The paired publisher must reject unsigned or unaccepted release bytes."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "verify_v5_combined_release.py"
SPEC = importlib.util.spec_from_file_location("paired_release", SCRIPT)
assert SPEC and SPEC.loader
paired = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(paired)
SOURCE = "a" * 40
SIGNER = "b" * 64


class PairedReleaseGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.source_root = root / "source"
        (self.source_root / "release").mkdir(parents=True)
        (self.source_root / "release/version.toml").write_text('''
product_version = "5.0.0-alpha.2.dev3"
android_version_code = 143
[components]
mobile = "5.0.0-alpha.2.dev3"
pc_companion = "1.6.0-alpha.2"
[release]
publication_authorized = true
''')
        self.mobile = root / "mobile"
        self.glass = root / "glass"
        self.mobile.mkdir()
        self.glass.mkdir()
        self.evidence = root / "v5-physical-acceptance.json"
        apk = self.mobile / "Cyclone-5.0.0-alpha.2.dev3.apk"
        apk.write_bytes(b"signed app")
        self.apk_digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (self.mobile / (apk.name + ".sha256")).write_text(f"{self.apk_digest}  {apk.name}\n")
        (self.mobile / "source-sha.txt").write_text(SOURCE + "\n")
        (self.mobile / "run-id.txt").write_text("101\n")
        (self.mobile / "signing-state.txt").write_text("SIGNED_PROTECTED_ENVIRONMENT_ROTATED\n")
        (self.mobile / "signing-lineage.bin").write_bytes(b"lineage")
        (self.mobile / "signing-lineage.txt").write_text("cert chain\n")
        (self.mobile / "mobile-metadata.json").write_text(json.dumps({
            "application_id": "com.cyclone.mobile", "version_name": "5.0.0-alpha.2.dev3",
            "version_code": 143, "apk_name": apk.name,
            "artifact_name": "Cyclone-Android-5.0.0-alpha.2.dev3",
        }))
        installer = self.glass / "Cyclone-PC-Companion-1.6.0-alpha.2-Setup.exe"
        installer.write_bytes(b"installed Glass candidate")
        self.glass_digest = hashlib.sha256(installer.read_bytes()).hexdigest()
        self.physical = {
            "schema": 1, "status": "PASS", "publication_authorized": True,
            "source_sha": SOURCE, "mobile_version": "5.0.0-alpha.2.dev3",
            "mobile_version_code": 143, "glass_version": "1.6.0-alpha.2",
            "mobile_ci_run_id": 101, "signing_run_id": 202, "glass_ci_run_id": 303,
            "signed_apk_sha256": self.apk_digest,
            "glass_installer_sha256": self.glass_digest,
            "signed_apk_cert_sha256": SIGNER, "device_model": "Pixel 8",
            "device_serial_suffix": "AB1234",
            **{key: True for key in paired.REQUIRED_PHYSICAL},
        }
        self.evidence.write_text(json.dumps(self.physical))
        self.root_patch = patch.object(paired, "ROOT", self.source_root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def check(self):
        return paired.verify(self.mobile, self.glass, self.evidence, SOURCE, 101, 202, 303, SIGNER)

    def test_accepts_exact_signed_and_physical_evidence(self):
        manifest = self.check()
        self.assertEqual(manifest["signed_apk_sha256"], self.apk_digest)
        self.assertEqual(manifest["glass_installer_sha256"], self.glass_digest)

    def test_alpha_waiver_requires_no_device_evidence(self):
        manifest = paired.verify(self.mobile, self.glass, None, SOURCE, 101, 202, 303, SIGNER, True)
        self.assertEqual(manifest["physical_acceptance"], "NOT_TESTED_USER_WAIVED")
        self.assertIsNone(manifest["physical_evidence"])

    def test_alpha_waiver_does_not_bypass_signing(self):
        (self.mobile / "signing-state.txt").write_text("UNSIGNED_VERIFIED_CANDIDATE\n")
        with self.assertRaisesRegex(ValueError, "not protected-environment signed"):
            paired.verify(self.mobile, self.glass, None, SOURCE, 101, 202, 303, SIGNER, True)

    def test_no_implicit_waiver(self):
        with self.assertRaisesRegex(ValueError, "explicit alpha testing waiver"):
            paired.verify(self.mobile, self.glass, None, SOURCE, 101, 202, 303, SIGNER)

    def test_refuses_signed_byte_change(self):
        (self.mobile / "Cyclone-5.0.0-alpha.2.dev3.apk").write_bytes(b"other app")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.check()

    def test_refuses_incomplete_physical_or_signer_change(self):
        self.physical["glass_real_source_matches_phone"] = False
        self.evidence.write_text(json.dumps(self.physical))
        with self.assertRaisesRegex(ValueError, "Physical acceptance step"):
            self.check()
        self.physical["glass_real_source_matches_phone"] = True
        self.evidence.write_text(json.dumps(self.physical))
        with self.assertRaisesRegex(ValueError, "signer"):
            paired.verify(self.mobile, self.glass, self.evidence, SOURCE, 101, 202, 303, "c" * 64)

    def test_refuses_unapproved_source_or_wrong_run_id(self):
        (self.source_root / "release/version.toml").write_text(
            (self.source_root / "release/version.toml").read_text().replace("publication_authorized = true", "publication_authorized = false")
        )
        with self.assertRaisesRegex(ValueError, "not authorized"):
            self.check()
        (self.source_root / "release/version.toml").write_text(
            (self.source_root / "release/version.toml").read_text().replace("publication_authorized = false", "publication_authorized = true")
        )
        with self.assertRaisesRegex(ValueError, "run identity"):
            paired.verify(self.mobile, self.glass, self.evidence, SOURCE, 101, 202, 304, SIGNER)


if __name__ == "__main__":
    unittest.main()
