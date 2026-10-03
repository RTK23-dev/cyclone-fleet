"""The phone build that belongs to this PC's release: downloaded from the release on GitHub and used only when its
SHA-256 matches the release manifest (the same rule `cyclone update` and install.ps1 use for the PC package)."""
from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Callable

from ..terminal import release as rel
from ..terminal.updater import _get

Fetch = Callable[[str, float], bytes]


class ApkError(Exception):
    """The download can't be trusted or reached; nothing is installed."""


def apk_name(version: str) -> str:
    return f"Cyclone-{version}.apk"


def release_urls(version: str) -> tuple[str, str]:
    base = f"https://github.com/{rel.REPO}/releases/download/v{version}"
    return f"{base}/{apk_name(version)}", f"{base}/{rel.MANIFEST}"


class ApkSource:
    """Keeps the verified phone build for each release under `root/<version>/`, newest two only."""

    KEEP = 2

    def __init__(self, root: Path, fetch: Fetch = _get):
        self.root = root
        self.fetch = fetch

    def ensure(self, version: str, progress: Callable[[str], None] = lambda _stage: None) -> Path:
        if rel.version_key(version) is None:
            raise ApkError(f"{version} is not a Cyclone release version.")
        folder = self.root / version
        target = folder / apk_name(version)
        package_url, manifest_url = release_urls(version)
        progress("checking")
        try:
            manifest = json.loads(self.fetch(manifest_url, 30.0).decode("utf-8", "replace"))
        except ValueError as exc:
            raise ApkError("The release manifest could not be read, so nothing was installed.") from exc
        except Exception as exc:
            raise ApkError("Couldn't reach GitHub to get the phone update. Check this PC's internet connection.") from exc
        expected = rel.expected_sha256(manifest, apk_name(version))
        if expected is None:
            raise ApkError(f"{apk_name(version)} isn't listed in the release manifest, so it wasn't installed.")
        if target.is_file() and rel.sha256_file(target) == expected:
            return target
        progress("downloading")
        folder.mkdir(parents=True, exist_ok=True)
        partial = target.with_suffix(".part")
        try:
            partial.write_bytes(self.fetch(package_url, 600.0))
        except Exception as exc:
            partial.unlink(missing_ok=True)
            raise ApkError("The download stopped before it finished. Check this PC's internet connection and try again.") from exc
        progress("verifying")
        if rel.sha256_file(partial) != expected:
            partial.unlink(missing_ok=True)
            raise ApkError("The download doesn't match the release checksum. It was deleted and nothing was installed.")
        partial.replace(target)
        self._prune(keep=version)
        return target

    def forget(self, version: str) -> None:
        """Drops a cached build Android couldn't read, so the next try downloads it again."""
        shutil.rmtree(self.root / version, ignore_errors=True)

    def _prune(self, keep: str) -> None:
        try:
            folders = [p for p in self.root.iterdir() if p.is_dir() and rel.version_key(p.name) is not None]
        except OSError:
            return
        folders.sort(key=lambda p: rel.version_key(p.name) or (), reverse=True)
        for old in folders[self.KEEP:]:
            if old.name != keep:
                shutil.rmtree(old, ignore_errors=True)
