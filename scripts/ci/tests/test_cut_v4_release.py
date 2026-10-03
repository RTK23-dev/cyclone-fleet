import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "cut_v4_release.py"
SPEC = importlib.util.spec_from_file_location("cut_v4_release", SCRIPT)
cut = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(cut)

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"


class CutV4ReleaseTest(unittest.TestCase):
    def ready_metadata(self) -> dict[str, object]:
        return {
            "mobile": "4.0.0",
            "pc_companion": "1.0.0",
            "android_version_code": 71,
            "publication_authorized": False,
            "physical_pixel8": "UNVERIFIED",
            "physical_pixel8_ui_acceptance": "UNVERIFIED",
        }

    def test_required_identity_is_v4_and_one_1(self):
        self.assertEqual("v4.0.0", cut.TAG)
        self.assertEqual("4.0.0", cut.REQUIRED_MOBILE)
        self.assertEqual(71, cut.REQUIRED_ANDROID_VERSION_CODE)
        self.assertEqual("1.0.0", cut.REQUIRED_PC_COMPANION)
        self.assertEqual("Cyclone 4.0.0 + One 1.0.0", cut.TITLE)
        self.assertEqual("docs/RELEASE_V4.md", cut.NOTES_REL)

    def test_ready_identity_has_no_version_errors(self):
        self.assertEqual([], cut.version_errors(self.ready_metadata()))

    def test_alpha_identity_is_rejected(self):
        metadata = self.ready_metadata()
        metadata["mobile"] = "4.0.0-alpha.4"
        metadata["android_version_code"] = 70
        metadata["pc_companion"] = "4.0.0-alpha.4"
        errors = cut.version_errors(metadata)
        self.assertIn("mobile='4.0.0-alpha.4' expected '4.0.0'", errors)
        self.assertIn("android_version_code=70 expected 71", errors)
        self.assertIn("pc_companion='4.0.0-alpha.4' expected '1.0.0'", errors)

    def test_publication_authorized_false_is_a_warning_not_a_version_error(self):
        metadata = self.ready_metadata()
        self.assertEqual([], cut.version_errors(metadata))
        warnings = cut.publication_warnings(metadata)
        self.assertTrue(any("publication_authorized=false" in item for item in warnings))
        self.assertTrue(any("UNVERIFIED" in item for item in warnings))

    def test_physical_verified_clears_unverified_gate(self):
        metadata = self.ready_metadata()
        self.assertTrue(cut.physical_unverified(metadata))
        metadata["physical_pixel8"] = "VERIFIED"
        metadata["physical_pixel8_ui_acceptance"] = "VERIFIED"
        self.assertFalse(cut.physical_unverified(metadata))

    def test_operator_sequence_documents_stack_ci_and_copy_artifact_name(self):
        steps = "\n".join(cut.operator_sequence())
        self.assertIn("#59", steps)
        self.assertIn("#60", steps)
        self.assertIn("#61", steps)
        self.assertIn("#62", steps)
        self.assertIn("grok/cyclone-v4-s5-release", steps)
        self.assertIn("grok/cyclone-v4-s4-one", steps)
        self.assertIn("release/cyclone-mobile-v4.0.0", steps)
        self.assertIn("mobile-release.yml", steps)
        self.assertIn("do not invent the artifact name", steps)
        self.assertIn("Cyclone-Android-4.0.0", steps)
        self.assertIn("pc-companion-release.yml", steps)
        self.assertIn("Cyclone-PC-Companion-1.0.0-Setup.exe", steps)
        self.assertIn('gh release create v4.0.0 --title "Cyclone 4.0.0 + One 1.0.0"', steps)
        self.assertIn("docs/RELEASE_V4.md", steps)

    def test_release_create_command_never_overwrites(self):
        command = cut.build_release_create_command(
            target_sha="a" * 40,
            apk=Path("Cyclone-4.0.0.apk"),
            installer=Path("Cyclone-PC-Companion-1.0.0-Setup.exe"),
        )
        self.assertEqual(
            [
                "gh",
                "release",
                "create",
                "v4.0.0",
                "--title",
                "Cyclone 4.0.0 + One 1.0.0",
                "--notes-file",
                "docs/RELEASE_V4.md",
                "--target",
                "a" * 40,
                "Cyclone-4.0.0.apk",
                "Cyclone-PC-Companion-1.0.0-Setup.exe",
            ],
            command,
        )
        rendered = cut.render_command(command)
        self.assertIn('--title "Cyclone 4.0.0 + One 1.0.0"', rendered)
        for token in cut.FORBIDDEN_RELEASE_TOKENS:
            self.assertNotIn(token, rendered)

    def test_script_source_never_force_pushes_or_deletes_releases(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('["git", "rev-parse"', source)
        self.assertIn('["git", "status"', source)
        self.assertIn('["git", "show-ref"', source)
        self.assertIn('["git", "ls-remote"', source)
        self.assertIn('["gh", "release", "view"', source)
        self.assertNotIn('["git", "push"', source)
        self.assertNotIn('["git", "tag"', source)
        self.assertNotIn('["gh", "release", "delete"', source)
        self.assertNotIn('["gh", "release", "upload"', source)
        self.assertNotIn("gh release", (WORKFLOWS / "mobile-release.yml").read_text(encoding="utf-8"))

    def test_dry_run_does_not_use_subprocess_or_network(self):
        stdout = io.StringIO()
        with patch.object(cut.subprocess, "run", side_effect=AssertionError("dry-run must not spawn")):
            with patch("sys.stdout", stdout):
                code = cut.dry_run(self.ready_metadata(), intended_sha="a" * 40, apk=None, installer=None)
        self.assertEqual(0, code)
        self.assertIn("--target " + "a" * 40, stdout.getvalue())

    def test_execute_blockers_cover_preflight_gates(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        notes = Path(temporary.name) / "RELEASE_V4.md"
        notes.write_text("# v4\n", encoding="utf-8")
        metadata = self.ready_metadata()
        sha = "b" * 40
        clean = cut.execute_blockers(
            metadata,
            allow_unverified_physical=True,
            notes_path=notes,
            working_tree_clean=True,
            head_sha=sha,
            intended_sha=sha,
            local_tag_exists=False,
            remote_tag_exists=False,
            github_release_exists=False,
        )
        self.assertEqual([], clean)

        blocked = cut.execute_blockers(
            metadata,
            allow_unverified_physical=False,
            notes_path=Path("/missing/RELEASE_V4.md"),
            working_tree_clean=False,
            head_sha="c" * 40,
            intended_sha="d" * 40,
            local_tag_exists=True,
            remote_tag_exists=True,
            github_release_exists=True,
        )
        joined = "\n".join(blocked)
        self.assertIn("UNVERIFIED", joined)
        self.assertIn("notes file missing", joined)
        self.assertIn("working tree is not clean", joined)
        self.assertIn("does not match intended SHA", joined)
        self.assertIn("local tag v4.0.0 already exists", joined)
        self.assertIn("remote tag v4.0.0 already exists", joined)
        self.assertIn("GitHub release v4.0.0 already exists", joined)

    def test_dry_run_exits_zero_when_identity_matches_even_if_unpublished(self):
        metadata = self.ready_metadata()
        stdout = io.StringIO()
        with patch.object(cut, "NOTES", Path("/missing/RELEASE_V4.md")):
            with patch("sys.stdout", stdout):
                code = cut.dry_run(metadata, intended_sha=None, apk=None, installer=None)
        self.assertEqual(0, code)
        text = stdout.getvalue()
        self.assertIn("dry-run", text)
        self.assertIn("publication_authorized=false", text)
        self.assertIn("no git/GitHub mutations, no network", text)
        self.assertIn("gh workflow run mobile-release.yml", text)
        self.assertNotIn("ERROR:", text)

    def test_dry_run_refuses_when_identity_is_not_v4(self):
        metadata = self.ready_metadata()
        metadata["mobile"] = "4.0.0-alpha.4"
        stdout = io.StringIO()
        with patch("sys.stdout", stdout):
            code = cut.dry_run(metadata, intended_sha=None, apk=None, installer=None)
        self.assertEqual(1, code)
        self.assertIn("mobile='4.0.0-alpha.4' expected '4.0.0'", stdout.getvalue())
        self.assertIn("operator sequence", stdout.getvalue())

    def test_execute_refuses_unverified_physical_without_honest_flag(self):
        stderr = io.StringIO()
        with patch("sys.stderr", stderr):
            code = cut.execute(
                self.ready_metadata(),
                intended_sha=None,
                apk=None,
                installer=None,
                allow_unverified_physical=False,
            )
        self.assertEqual(1, code)
        self.assertIn("UNVERIFIED", stderr.getvalue())
        self.assertIn("--allow-unverified-physical", stderr.getvalue())

    def test_default_cli_is_dry_run(self):
        args = cut.build_parser().parse_args([])
        self.assertFalse(args.execute)
        self.assertFalse(args.allow_unverified_physical)


if __name__ == "__main__":
    unittest.main()
