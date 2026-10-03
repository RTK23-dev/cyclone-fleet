#!/usr/bin/env python3
"""Operator helper to cut GitHub tag one-1.1.0 (Cyclone One only).

Default mode is dry-run: print the merge order and exact commands. It does not
mutate git or GitHub and does not use the network.

--execute runs `gh release create` only after local/GitHub preflight. It never
force-pushes, deletes tags, or overwrites an existing release. Signing stays on
pc-companion-release.yml; this script does not bypass it. Mobile 4.0.4 is already
published; do not dispatch mobile-ci.yml / mobile-release.yml as part of this cut.
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
NOTES_REL = "docs/RELEASE_ONE_1.1.md"
NOTES = ROOT / NOTES_REL

TAG = "one-1.1.0"
TITLE = "Cyclone One 1.1.0"
REQUIRED_MOBILE = "4.0.4"
REQUIRED_ANDROID_VERSION_CODE = 75
REQUIRED_PC_COMPANION = "1.1.0"
REQUIRED_DEVICE_GATEWAY = "4.1.0"
REQUIRED_MCP = "4.1.0"
RELEASE_BRANCH = "release/cyclone-one-v1.1.0"
STAGE5_BRANCH = "grok/one-1.1-s5-release"
STAGE4_BRANCH = "grok/one-1.1-s4-operator"
TYPICAL_ONE_INSTALLER = "Cyclone-PC-Companion-1.1.0-Setup.exe"
TYPICAL_CANDIDATE_ARTIFACT = "cyclone-pc-release-candidate-<sha>"
MERGE_ORDER = (
    "PRs #66 → #68 → #70 → #72 then this A5 PR "
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
        "device_gateway": str(components.get("device_gateway", "")),
        "mcp": str(components.get("mcp", "")),
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
    device_gateway = str(metadata.get("device_gateway", ""))
    mcp = str(metadata.get("mcp", ""))
    version_code = int(metadata.get("android_version_code", -1))
    if mobile != REQUIRED_MOBILE:
        errors.append(f"mobile={mobile!r} expected {REQUIRED_MOBILE!r}")
    if version_code != REQUIRED_ANDROID_VERSION_CODE:
        errors.append(
            f"android_version_code={version_code} expected {REQUIRED_ANDROID_VERSION_CODE}"
        )
    if pc_companion != REQUIRED_PC_COMPANION:
        errors.append(f"pc_companion={pc_companion!r} expected {REQUIRED_PC_COMPANION!r}")
    if device_gateway != REQUIRED_DEVICE_GATEWAY:
        errors.append(
            f"device_gateway={device_gateway!r} expected {REQUIRED_DEVICE_GATEWAY!r}"
        )
    if mcp != REQUIRED_MCP:
        errors.append(f"mcp={mcp!r} expected {REQUIRED_MCP!r}")
    return errors


def physical_unverified(metadata: dict[str, object]) -> bool:
    pixel = str(metadata.get("physical_pixel8", "UNVERIFIED")).strip().upper()
    ui = str(metadata.get("physical_pixel8_ui_acceptance", "UNVERIFIED")).strip().upper()
    return pixel != "VERIFIED" or ui != "VERIFIED"


def operator_sequence() -> list[str]:
    return [
        "1. Merge stack in order #66, #68, #70, #72, then this A5 PR "
        f"({STAGE5_BRANCH} into {STAGE4_BRANCH} / then main or {RELEASE_BRANCH}).",
        f"2. Push {RELEASE_BRANCH} from the merged SHA for provenance. "
        "Do NOT push release/cyclone-mobile-v* for this One-only cut "
        "(that would fire Mobile CI for an already-published 4.0.4 APK).",
        "3. Do NOT dispatch mobile-ci.yml / mobile-release.yml as part of this One 1.1.0 cut. "
        "Mobile 4.0.4 is already published (tag v4.0.4). Mobile 4.1.0 is PR #73 / B5, out of scope. "
        "Do not merge other PRs.",
        "4. Dispatch pc-companion-release.yml "
        f"(gh workflow run pc-companion-release.yml --ref {RELEASE_BRANCH}). "
        "That Windows job validates the PC stack, builds NSIS, stages "
        f"{TYPICAL_ONE_INSTALLER} (name comes from apps/pc-companion/package.json version), "
        "optional Authenticode if secrets exist (windows_signing remains CI_UNSIGNED if they do not).",
        "5. Wait for that workflow success. Download the candidate artifact "
        f"{TYPICAL_CANDIDATE_ARTIFACT}. Copy the exact Setup.exe name from the artifact; "
        f"do not invent it (typically {TYPICAL_ONE_INSTALLER} if package.json is {REQUIRED_PC_COMPANION}).",
        "6. "
        f'gh release create {TAG} --title "{TITLE}" --notes-file {NOTES_REL} '
        "attaching the Setup.exe if present.",
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
    installer: Path | None,
) -> None:
    print("== version.toml ==")
    print(f"mobile={metadata.get('mobile')}")
    print(f"android_version_code={metadata.get('android_version_code')}")
    print(f"pc_companion={metadata.get('pc_companion')}")
    print(f"device_gateway={metadata.get('device_gateway')}")
    print(f"mcp={metadata.get('mcp')}")
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
    print("# Do NOT push release/cyclone-mobile-v* (would fire Mobile CI for published 4.0.4)")
    print("# Do NOT dispatch mobile-ci.yml / mobile-release.yml as part of this One 1.1.0 cut")
    print(f"gh workflow run pc-companion-release.yml --ref {RELEASE_BRANCH}")
    print(
        f"# wait for {TYPICAL_CANDIDATE_ARTIFACT}; copy the exact Setup.exe name "
        f"(typically {TYPICAL_ONE_INSTALLER} if package.json is {REQUIRED_PC_COMPANION}); do not invent it"
    )
    command = build_release_create_command(
        target_sha=target_sha,
        installer=installer,
    )
    print(render_command(command))
    if installer is None:
        print(f"# add One installer path when present (typically {TYPICAL_ONE_INSTALLER})")
    print()
    print("Signing stays on pc-companion-release.yml. Do not attach a mobile APK.")
    print("Do not add a one-off version-named publish workflow. Never force-push, delete tags, or overwrite a release.")


def dry_run(
    metadata: dict[str, object],
    *,
    intended_sha: str | None,
    installer: Path | None,
) -> int:
    print("Cyclone One 1.1.0 cut helper: dry-run (no git/GitHub mutations, no network)")
    print()
    for warning in publication_warnings(metadata):
        print(f"WARNING: {warning}")
    if publication_warnings(metadata):
        print()
    errors = version_errors(metadata)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        print("Refusing to treat this tree as the one-1.1.0 / gateway-MCP 4.1.0 cut until version.toml matches.")
        print()
    if not NOTES.is_file():
        print(f"WARNING: {NOTES_REL} is missing; --execute will refuse until release notes exist.")
        print()
    print_operator_plan(metadata, target_sha=intended_sha, installer=installer)
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
        installer=installer,
    )
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
            "Print (default) or run the Cyclone One 1.1.0 GitHub release cut. "
            "Does not replace pc-companion-release.yml signing. "
            "Does not dispatch mobile-ci.yml / mobile-release.yml."
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
        installer = existing_file(args.installer) if args.installer else None
    except FileNotFoundError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if args.execute:
        return execute(
            metadata,
            intended_sha=args.sha,
            installer=installer,
            allow_unverified_physical=args.allow_unverified_physical,
        )
    return dry_run(metadata, intended_sha=args.sha, installer=installer)


if __name__ == "__main__":
    raise SystemExit(main())
