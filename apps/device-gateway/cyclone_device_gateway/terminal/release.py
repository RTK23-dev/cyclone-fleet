"""Which Cyclone release is newest, and is it newer than this install. Pure functions; no network here."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

REPO = "premiumcentraal-boop/Cyclone"
RELEASES_API = f"https://api.github.com/repos/{REPO}/releases?per_page=30"
_VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+)(?:\.dev(\d+))?)?$")
# Plan 31: the web-only PC ships as one zip per release, verified against the release's manifest.
_PACKAGE = re.compile(r"^Cyclone-PC-[0-9A-Za-z.\-]+\.zip$")
MANIFEST = "release-manifest.json"
_SHA = re.compile(r"^[0-9a-f]{64}$")


def version_key(value: str) -> tuple[int, ...] | None:
    """Order 5.0.0-alpha.22.dev1 < 5.0.0-alpha.23.dev1 < 5.0.0. None for anything else."""
    match = _VERSION.match(value.strip())
    if not match:
        return None
    major, minor, patch, alpha, dev = match.groups()
    # A final release sorts after every alpha of the same number.
    return (int(major), int(minor), int(patch), int(alpha) if alpha is not None else 10**9, int(dev or 0))


@dataclass(frozen=True)
class Release:
    tag: str
    version: str
    page_url: str
    package_name: str
    package_url: str
    manifest_url: str


def pick_latest(releases: Iterable[dict[str, Any]]) -> Release | None:
    best: tuple[tuple[int, ...], Release] | None = None
    for item in releases:
        if not isinstance(item, dict) or item.get("draft"):
            continue
        tag = str(item.get("tag_name") or "")
        key = version_key(tag)
        if key is None:
            continue
        assets = {str(a.get("name")): str(a.get("browser_download_url") or "") for a in item.get("assets") or [] if isinstance(a, dict)}
        package = next((name for name in assets if _PACKAGE.match(name)), None)
        manifest = assets.get(MANIFEST)
        if not package or not manifest or not assets[package].startswith("https://github.com/") or not manifest.startswith("https://github.com/"):
            continue
        release = Release(tag, tag.lstrip("v"), str(item.get("html_url") or ""), package, assets[package], manifest)
        if best is None or key > best[0]:
            best = (key, release)
    return best[1] if best else None


def is_newer(latest: str, installed: str) -> bool:
    a, b = version_key(latest), version_key(installed)
    return a is not None and b is not None and a > b


def expected_sha256(manifest: Any, name: str) -> str | None:
    """The package's SHA-256 from the release manifest (`{"sha256": {name: hex}}`)."""
    table = manifest.get("sha256") if isinstance(manifest, dict) else None
    value = table.get(name) if isinstance(table, dict) else None
    return value.lower() if isinstance(value, str) and _SHA.match(value.lower()) else None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def installed_version() -> str:
    """The product version this runtime was built as (baked in by the PyInstaller spec), else the checkout's."""
    here = Path(__file__).resolve()
    baked = here.parent / "product_version.txt"
    try:
        value = baked.read_text(encoding="utf-8").strip()
        if version_key(value):
            return value
    except OSError:
        pass
    for parent in here.parents:
        toml = parent / "release" / "version.toml"
        if toml.is_file():
            match = re.search(r'^product_version\s*=\s*"([^"]+)"', toml.read_text(encoding="utf-8"), re.MULTILINE)
            if match:
                return match.group(1)
    return "0.0.0"
