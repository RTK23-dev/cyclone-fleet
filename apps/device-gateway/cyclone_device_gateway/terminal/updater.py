"""Check GitHub for a newer Cyclone and download its package, verified against the release's manifest (plan 31)."""

from __future__ import annotations

import json
import time
import urllib.request
from pathlib import Path
from typing import Callable

from . import release as rel

CHECK_TTL_S = 6 * 3600
USER_AGENT = "cyclone-terminal"


def _get(url: str, timeout: float) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - fixed https GitHub URLs
        return response.read()


def latest_release(cache_file: Path, *, force: bool = False, timeout: float = 2.5,
                   fetch: Callable[[str, float], bytes] = _get, now: Callable[[], float] = time.time) -> rel.Release | None:
    """Newest release, at most one network check per few hours (the terminal must open instantly)."""
    if not force:
        try:
            cached = json.loads(cache_file.read_text(encoding="utf-8"))
            if now() - float(cached.get("checkedAt", 0)) < CHECK_TTL_S:
                data = cached.get("release")
                return rel.Release(**data) if isinstance(data, dict) else None
        except (OSError, ValueError, TypeError):
            pass
    try:
        found = rel.pick_latest(json.loads(fetch(rel.RELEASES_API, timeout).decode("utf-8")))
    except Exception:  # offline, rate-limited, GitHub down: never block opening Glass
        return None
    try:
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        cache_file.write_text(json.dumps({"checkedAt": now(), "release": found.__dict__ if found else None}), encoding="utf-8")
    except OSError:
        pass
    return found


class UpdateError(Exception):
    pass


def download_package(release: rel.Release, target: Path, *, fetch: Callable[[str, float], bytes] = _get,
                     progress: Callable[[str], None] = lambda _line: None) -> Path:
    """Download the release's Cyclone-PC zip and refuse it unless its SHA-256 matches the release manifest."""
    try:
        manifest = json.loads(fetch(release.manifest_url, 30.0).decode("utf-8", "replace"))
    except ValueError as exc:
        raise UpdateError("The release manifest could not be read; not installing.") from exc
    expected = rel.expected_sha256(manifest, release.package_name)
    if expected is None:
        raise UpdateError(f"{release.package_name} is not listed in the release manifest; not installing it.")
    progress(f"Downloading {release.package_name}…")
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(".part")
    partial.write_bytes(fetch(release.package_url, 600.0))
    actual = rel.sha256_file(partial)
    if actual != expected:
        partial.unlink(missing_ok=True)
        raise UpdateError("The download does not match the release checksum; it was deleted and nothing was installed.")
    partial.replace(target)
    # install.ps1 checks the package once more against this before unpacking it.
    target.with_name(target.name + ".sha256").write_text(expected, encoding="ascii")
    progress(f"Verified SHA-256 {actual[:12]}…")
    return target
