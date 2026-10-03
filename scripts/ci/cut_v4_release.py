#!/usr/bin/env python3
"""Operator helper to cut GitHub tag v4.0.0 + Cyclone One 1.0.0.

Default mode is dry-run: print the merge order and exact commands. It does not
mutate git or GitHub and does not use the network.

--execute runs `gh release create` only after local/GitHub preflight. It never
force-pushes, deletes tags, or overwrites an existing release. Signing stays on
mobile-release.yml / pc-companion-release.yml; this script does not bypass them.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tomllib
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "release" / "version.toml"
NOTES_REL = "docs/RELEASE_V4.md"
NOTES = ROOT / NOTES_REL

TAG = "v4.0.0"
TITLE = "Cyclone 4.0.0 + One 1.0.0"
REQUIRED_MOBILE = "4.0.0"
REQUIRED_ANDROID_VERSION_CODE = 71
REQUIRED_PC_COMPANION = "1.0.0"
RELEASE_BRANCH = "release/cyclone-mobile-v4.0.0"
STAGE5_BRANCH = "grok/cyclone-v4-s5-release"
STAGE4_BRANCH = "grok/cyclone-v4-s4-one"
TYPICAL_ANDROID_ARTIFACT = "Cyclone-Android-4.0.0"
TYPICAL_SIGNED_APK = "Cyclone-4.0.0.apk"
TYPICAL_ONE_INSTALLER = "Cyclone-PC-Companion-1.0.0-Setup.exe"
MERGE_ORDER = (
    "PRs #59 → #60 → #61 → #62 then Stage 5 "
    f"({STAGE5_BRANCH} into {STAGE4_BRANCH} / then main or {RELEASE_BRANCH})"
)

FORBIDDEN_RELEASE_TOKENS = (
    "--force",
    "--force-with-lease",
    "--clobber",
    "release delete",
    "tag -d",
    "push --delete",
    "git push -f",
)


class CommandResult:
    def __init__(self, returncode: int, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class CommandRunner:
    def run(self, argv: Sequence[str]) -> CommandResult:
        completed = subprocess.run(
            list(argv),
            capture_output=True,
            text=True,
            check=False,
        )
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)


def load_metadata(path: Path | None = None) -> dict[str, object]:
    target = path or METADATA
    data = tomllib.loads(target.read_text(encoding="utf-8"))
    components = data.get("components", {})
    release = data.get("release", {})
    if not isinstance(components, dict) or not isinstance(release, dict):
        raise ValueError("release/version.toml is missing [components] or [release]")
    return {
        "mobile": str(components.get("mobile", "")),
        "pc_companion": str(components.get("pc_companion", "")),
        "android_version_code": int(data["android_version_code"]),
        "publication_authorized": bool(release.get("publication_authorized", False)),
        "physical_pixel8": str(release.get("physical_pixel8", "UNVERIFIED")),
        "physical_pixel8_ui_acceptance": str(
            release.get("physical_pixel8_ui_acceptance", "UNVERIFIED")
        ),
    }


def version_errors(metadata: dict[str, object]) -> list[str]:
    errors: list[str] = []
    mobile = str(metadata.get("mobile", ""))
    pc_companion = str(metadata.get("pc_companion", ""))
    version_code = int(metadata.get("android_version_code", -1))
    if mobile != REQUIRED_MOBILE:
        errors.append(f"mobile={mobile!r} expected {REQUIRED_MOBILE!r}")
    if version_code != REQUIRED_ANDROID_VERSION_CODE:
        errors.append(
            f"android_version_code={version_code} expected {REQUIRED_ANDROID_VERSION_CODE}"
        )
    if pc_companion != REQUIRED_PC_COMPANION:
        errors.append(f"pc_companion={pc_companion!r} expected {REQUIRED_PC_COMPANION!r}")
    return errors


def physical_unverified(metadata: dict[str, object]) -> bool:
    pixel = str(metadata.get("physical_pixel8", "UNVERIFIED")).strip().upper()
    ui = str(metadata.get("physical_pixel8_ui_acceptance", "UNVERIFIED")).strip().upper()
    return pixel != "VERIFIED" or ui != "VERIFIED"


def operator_sequence() -> list[str]:
    return [
        "1. Merge stack in order #59, #60, #61, #62, then this Stage 5 PR "
        f"({STAGE5_BRANCH} into {STAGE4_BRANCH} / then main or {RELEASE_BRANCH}).",
        f"2. Push {RELEASE_BRANCH} from the merged SHA (or merge to main) so Mobile CI push run fires.",
        "3. Wait for Cyclone Mobile CI success on that SHA.",
        "4. Dispatch mobile-release.yml with that build_run_id + artifact name. "
        "Copy both from the successful Mobile CI run; do not invent the artifact name "
        f"(typically {TYPICAL_ANDROID_ARTIFACT} if metadata uses versionName {REQUIRED_MOBILE}).",
        "5. Dispatch pc-companion-release.yml for One 1.0.0 NSIS "
        f"({TYPICAL_ONE_INSTALLER}).",
        "6. "
        f'gh release create {TAG} --title "{TITLE}" --notes-file {NOTES_REL} '
        "attaching signed APK (if present) and One installer (if present).",
    ]


def render_command(argv: Sequence[str]) -> str:
    parts: list[str] = []
    for arg in argv:
        if arg == "" or any(ch.isspace() for ch in arg):
            parts.append('"' + arg.replace('"', '\\"') + '"')
        else:
            parts.append(arg)
    return " ".join(parts)


def build_release_create_command(
    *,
    notes_file: str = NOTES_REL,
    target_sha: str | None = None,
    apk: Path | None = None,
    installer: Path | None = None,
) -> list[str]:
    command = [
        "gh",
        "release",
        "create",
        TAG,
        "--title",
        TITLE,
        "--notes-file",
        notes_file,
    ]
    if target_sha:
        command.extend(["--target", target_sha])
    if apk is not None:
        command.append(str(apk))
    if installer is not None:
        command.append(str(installer))
    rendered = " ".join(command)
    for token in FORBIDDEN_RELEASE_TOKENS:
        if token in rendered:
            raise ValueError(f"refusing to build a destructive release command containing {token!r}")
    return command


def execute_blockers(
    metadata: dict[str, object],
    *,
    allow_unverified_physical: bool,
    notes_path: Path,
    working_tree_clean: bool,
    head_sha: str,
    intended_sha: str | None,
    local_tag_exists: bool,
    remote_tag_exists: bool,
    github_release_exists: bool,
) -> list[str]:
    blockers = list(version_errors(metadata))
    if physical_unverified(metadata) and not allow_unverified_physical:
        blockers.append(
            "physical Pixel 8 is UNVERIFIED; pass --allow-unverified-physical for an honest override"
        )
    if not notes_path.is_file():
        blockers.append(f"notes file missing: {NOTES_REL}")
    if not working_tree_clean:
        blockers.append("working tree is not clean")
    if not head_sha:
        blockers.append("could not resolve HEAD SHA")
    if intended_sha and intended_sha != head_sha:
        blockers.append(f"HEAD {head_sha} does not match intended SHA {intended_sha}")
    if local_tag_exists:
        blockers.append(f"local tag {TAG} already exists")
    if remote_tag_exists:
        blockers.append(f"remote tag {TAG} already exists")
    if github_release_exists:
        blockers.append(f"GitHub release {TAG} already exists")
    return blockers


def publication_warnings(metadata: dict[str, object]) -> list[str]:
    warnings: list[str] = []
    if not bool(metadata.get("publication_authorized", False)):
        warnings.append(
            "publication_authorized=false; physical Pixel status is UNVERIFIED unless proven otherwise"
        )
    if physical_unverified(metadata):
        pixel = metadata.get("physical_pixel8")
        ui = metadata.get("physical_pixel8_ui_acceptance")
        warnings.append(
            f"physical Pixel 8 is UNVERIFIED (physical_pixel8={pixel!r}, "
            f"physical_pixel8_ui_acceptance={ui!r})"
        )
    return warnings


def existing_file(path: Path | None) -> Path | None:
    if path is None:
        return None
    resolved = path if path.is_absolute() else (Path.cwd() / path)
    if not resolved.is_file():
        raise FileNotFoundError(f"attachment is not a file: {path}")
    return resolved


def print_operator_plan(
    metadata: dict[str, object],
    *,
    target_sha: str | None,
    apk: Path | None,
    installer: Path | None,
) -> None:
    print("== version.toml ==")
    print(f"mobile={metadata.get('mobile')}")
    print(f"android_version_code={metadata.get('android_version_code')}")
    print(f"pc_companion={metadata.get('pc_companion')}")
    print(f"publication_authorized={metadata.get('publication_authorized')}")
    print(f"physical_pixel8={metadata.get('physical_pixel8')}")
    print(f"physical_pixel8_ui_acceptance={metadata.get('physical_pixel8_ui_acceptance')}")
    print()
    print("== merge order ==")
    print(MERGE_ORDER)
    print()
    print("== operator sequence ==")
    for step in operator_sequence():
        print(step)
    print()
    print("== exact commands (copy after CI is green) ==")
    print(f"git checkout -B {RELEASE_BRANCH} <MERGED_SHA>")
    print(f"git push -u origin {RELEASE_BRANCH}")
    print("# wait for Cyclone Mobile CI push run on that SHA to succeed")
    print(
        "gh workflow run mobile-release.yml "
        f"--ref {RELEASE_BRANCH} "
        "-f build_run_id=<COPY_FROM_MOBILE_CI> "
        "-f artifact_name=<COPY_FROM_MOBILE_CI>"
    )
    print(
        f"# artifact_name is reported by the CI run (typically {TYPICAL_ANDROID_ARTIFACT} "
        f"if versionName is {REQUIRED_MOBILE}); do not invent it"
    )
    print(f"gh workflow run pc-companion-release.yml --ref {RELEASE_BRANCH}")
    print(f"# expect {TYPICAL_ONE_INSTALLER} in the PC companion candidate artifact")
    command = build_release_create_command(
        target_sha=target_sha,
        apk=apk,
        installer=installer,
    )
    print(render_command(command))
    if apk is None:
        print(f"# add signed APK path when present (typically {TYPICAL_SIGNED_APK})")
    if installer is None:
        print(f"# add One installer path when present (typically {TYPICAL_ONE_INSTALLER})")
    print()
    print("Signing stays on mobile-release.yml and pc-companion-release.yml.")
    print("Do not add a one-off version-named publish workflow. Never force-push, delete tags, or overwrite a release.")


def dry_run(
    metadata: dict[str, object],
    *,
    intended_sha: str | None,
    apk: Path | None,
    installer: Path | None,
) -> int:
    print("Cyclone V4 cut helper: dry-run (no git/GitHub mutations, no network)")
    print()
    for warning in publication_warnings(metadata):
        print(f"WARNING: {warning}")
    if publication_warnings(metadata):
        print()
    errors = version_errors(metadata)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        print("Refusing to treat this tree as the v4.0.0 / One 1.0.0 cut until version.toml matches.")
        print()
    if not NOTES.is_file():
        print(f"WARNING: {NOTES_REL} is missing; --execute will refuse until release notes exist.")
        print()
    print_operator_plan(metadata, target_sha=intended_sha, apk=apk, installer=installer)
    print()
    print("Re-run with --execute only after the sequence above, with a clean tree and local artifacts.")
    return 1 if errors else 0


def _stdout(result: CommandResult) -> str:
    return (result.stdout or "").strip()


def _combined(result: CommandResult) -> str:
    return f"{result.stdout}\n{result.stderr}".strip()


def resolve_sha(runner: CommandRunner, spec: str) -> str:
    result = runner.run(["git", "rev-parse", "--verify", f"{spec}^{{commit}}"])
    if result.returncode != 0:
        raise RuntimeError(_combined(result) or f"could not resolve {spec}")
    sha = _stdout(result)
    if len(sha) != 40:
        raise RuntimeError(f"resolved SHA for {spec} is not 40 hex chars: {sha!r}")
    return sha.lower()


def working_tree_is_clean(runner: CommandRunner) -> bool:
    result = runner.run(["git", "status", "--porcelain=v1"])
    if result.returncode != 0:
        raise RuntimeError(_combined(result) or "git status failed")
    return _stdout(result) == ""


def ref_exists(runner: CommandRunner, argv: Sequence[str], *, missing_code: int) -> bool:
    result = runner.run(argv)
    if result.returncode == 0:
        return True
    if result.returncode == missing_code:
        return False
    raise RuntimeError(_combined(result) or " ".join(argv))


def github_release_exists(runner: CommandRunner, tag: str) -> bool:
    result = runner.run(["gh", "release", "view", tag, "--json", "tagName"])
    if result.returncode == 0:
        return True
    text = _combined(result).lower()
    if "not found" in text or "release not found" in text:
        return False
    raise RuntimeError(_combined(result) or f"gh release view {tag} failed")


def execute(
    metadata: dict[str, object],
    *,
    intended_sha: str | None,
    apk: Path | None,
    installer: Path | None,
    allow_unverified_physical: bool,
    runner: CommandRunner | None = None,
) -> int:
    errors = version_errors(metadata)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if physical_unverified(metadata) and not allow_unverified_physical:
        print(
            "ERROR: physical Pixel 8 is UNVERIFIED. "
            "Pass --allow-unverified-physical for an honest override (not a device pass).",
            file=sys.stderr,
        )
        return 1

    if allow_unverified_physical and physical_unverified(metadata):
        print("=" * 72)
        print("HONEST OVERRIDE: --allow-unverified-physical")
        print("Physical Pixel 8 remains UNVERIFIED. This flag does not claim a device pass.")
        print("=" * 72)

    for warning in publication_warnings(metadata):
        print(f"WARNING: {warning}")

    for name in ("git", "gh"):
        if shutil.which(name) is None:
            print(f"ERROR: {name} is required for --execute", file=sys.stderr)
            return 1

    active = runner or CommandRunner()
    try:
        head_sha = resolve_sha(active, "HEAD")
        wanted = resolve_sha(active, intended_sha) if intended_sha else head_sha
        clean = working_tree_is_clean(active)
        local_tag = ref_exists(
            active,
            ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{TAG}"],
            missing_code=1,
        )
        remote_tag = ref_exists(
            active,
            ["git", "ls-remote", "--exit-code", "--tags", "origin", f"refs/tags/{TAG}"],
            missing_code=2,
        )
        release_exists = github_release_exists(active, TAG)
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    blockers = execute_blockers(
        metadata,
        allow_unverified_physical=allow_unverified_physical,
        notes_path=NOTES,
        working_tree_clean=clean,
        head_sha=head_sha,
        intended_sha=wanted,
        local_tag_exists=local_tag,
        remote_tag_exists=remote_tag,
        github_release_exists=release_exists,
    )
    if blockers:
        for blocker in blockers:
            print(f"ERROR: {blocker}", file=sys.stderr)
        return 1

    command = build_release_create_command(
        notes_file=str(NOTES),
        target_sha=head_sha,
        apk=apk,
        installer=installer,
    )
    if apk is None:
        print(f"WARNING: no signed APK attached (typically {TYPICAL_SIGNED_APK})")
    if installer is None:
        print(f"WARNING: no One installer attached (typically {TYPICAL_ONE_INSTALLER})")
    print("Running:", render_command(command))
    created = active.run(command)
    if created.returncode != 0:
        print(_combined(created) or "gh release create failed", file=sys.stderr)
        return created.returncode or 1
    if created.stdout:
        print(created.stdout, end="" if created.stdout.endswith("\n") else "\n")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Print (default) or run the Cyclone v4.0.0 + One 1.0.0 GitHub release cut. "
            "Does not replace mobile-release.yml signing."
        ),
        epilog=f"Merge order: {MERGE_ORDER}",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="run gh release create after preflight; default is dry-run",
    )
    parser.add_argument(
        "--sha",
        dest="sha",
        help="intended commit SHA; --execute requires HEAD to match",
    )
    parser.add_argument("--apk", type=Path, help="signed Android APK to attach if present")
    parser.add_argument(
        "--installer",
        type=Path,
        help=f"Cyclone One NSIS installer to attach if present (typically {TYPICAL_ONE_INSTALLER})",
    )
    parser.add_argument(
        "--allow-unverified-physical",
        action="store_true",
        help="honest override: allow --execute while physical Pixel 8 is still UNVERIFIED",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        metadata = load_metadata()
    except (OSError, tomllib.TOMLDecodeError, KeyError, TypeError, ValueError) as error:
        print(f"ERROR: could not read release/version.toml: {error}", file=sys.stderr)
        return 1

    try:
        apk = existing_file(args.apk) if args.apk else None
        installer = existing_file(args.installer) if args.installer else None
    except FileNotFoundError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if args.execute:
        return execute(
            metadata,
            intended_sha=args.sha,
            apk=apk,
            installer=installer,
            allow_unverified_physical=args.allow_unverified_physical,
        )
    return dry_run(metadata, intended_sha=args.sha, apk=apk, installer=installer)


if __name__ == "__main__":
    raise SystemExit(main())
