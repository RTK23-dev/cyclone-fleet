"""Release-stage guards for the exact Windows CI installer payload."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "scripts/pc-companion/verify-v5-release-artifact.py"
SPEC = importlib.util.spec_from_file_location("glass_stage", MODULE)
assert SPEC and SPEC.loader
glass_stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(glass_stage)
SHA = "a" * 40
VERSION = tomllib.loads((ROOT / "release/version.toml").read_text(encoding="utf-8"))["components"]["pc_companion"]


class GlassReleaseStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.installer = self.root / f"Cyclone-PC-Companion-{VERSION}-Setup.exe"
        self.installer.write_bytes(b"representative installer")
        digest = hashlib.sha256(self.installer.read_bytes()).hexdigest()
        self.digest = digest
        (self.root / "SHA256SUMS.txt").write_text(f"{digest}  {self.installer.name}\n")
        (self.root / "source-sha.txt").write_text(SHA + "\n")
        (self.root / "THIRD_PARTY_NOTICES").write_text("Dependency notices\n")
        (self.root / "release-provenance.json").write_text(json.dumps({
            "product": "Cyclone One", "version": VERSION, "source_sha": SHA,
            "artifacts": [{"name": self.installer.name, "sha256": digest, "size_bytes": self.installer.stat().st_size}],
        }))
        acceptance = {key: True for key in glass_stage.ACCEPTANCE_KEYS}
        acceptance["bundled_adb_version"] = "37.0.1"
        (self.root / "installer-acceptance.json").write_text(json.dumps(acceptance))

    def test_accepts_exact_complete_ci_payload(self):
        self.assertEqual(glass_stage.verify(self.root, SHA, VERSION), (self.installer, self.digest))

    def test_refuses_tampered_installer(self):
        self.installer.write_bytes(b"different installer")
        with self.assertRaisesRegex(ValueError, "checksum"):
            glass_stage.verify(self.root, SHA, VERSION)

    def test_refuses_missing_installed_acceptance(self):
        path = self.root / "installer-acceptance.json"
        acceptance = json.loads(path.read_text())
        acceptance["gateway_authenticated"] = False
        path.write_text(json.dumps(acceptance))
        with self.assertRaisesRegex(ValueError, "acceptance"):
            glass_stage.verify(self.root, SHA, VERSION)

    def test_refuses_wrong_source_or_additional_file(self):
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            glass_stage.verify(self.root, "b" * 40, VERSION)
        (self.root / "unexpected.exe").write_bytes(b"untrusted")
        with self.assertRaisesRegex(ValueError, "unexpected"):
            glass_stage.verify(self.root, SHA, VERSION)


if __name__ == "__main__":
    unittest.main()
