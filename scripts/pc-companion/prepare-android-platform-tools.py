from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import urllib.request
import zipfile

LOCK_NAME = "android-platform-tools"
EXPECTED_PREFIX = PurePosixPath("platform-tools")
REQUIRED_FILES = {
    "adb.exe",
    "AdbWinApi.dll",
    "AdbWinUsbApi.dll",
}


def _load_lock(repo: Path) -> dict[str, str]:
    lock_path = repo / "packaging" / "pc-companion" / "third-party-binaries.lock.json"
    data = json.loads(lock_path.read_text(encoding="utf-8"))
    matches = [item for item in data.get("binaries", []) if item.get("name") == LOCK_NAME]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one {LOCK_NAME!r} entry in {lock_path}")
    item = matches[0]
    for key in ("version", "official_source", "sha256"):
        value = str(item.get(key, "")).strip()
        if not value:
            raise RuntimeError(f"{LOCK_NAME} lock is missing {key}")
    return {
        "version": str(item["version"]),
        "url": str(item["official_source"]),
        "sha256": str(item["sha256"]).lower(),
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_extract(zip_path: Path, destination: Path) -> None:
    with zipfile.ZipFile(zip_path) as archive:
        files = []
        for info in archive.infolist():
            member = PurePosixPath(info.filename)
            if not member.parts or member.parts[0] != EXPECTED_PREFIX.name:
                continue
            if member.is_absolute() or ".." in member.parts:
                raise RuntimeError(f"Unsafe Platform-Tools archive member: {info.filename}")
            if info.is_dir():
                continue
            relative = Path(*member.parts[1:])
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            files.append(relative.as_posix())

    missing = sorted(name for name in REQUIRED_FILES if not (destination / name).is_file())
    if missing:
        raise RuntimeError(f"Platform-Tools archive is missing required Windows ADB files: {missing}")
    if not files:
        raise RuntimeError("Platform-Tools archive did not contain a platform-tools payload")


def stage(repo: Path) -> Path:
    locked = _load_lock(repo)
    target = repo / "apps" / "pc-companion" / "src-tauri" / "resources" / "android-platform-tools"
    build_root = repo / "build" / "android-platform-tools"
    build_root.mkdir(parents=True, exist_ok=True)
    archive = build_root / f"platform-tools-{locked['version']}-windows.zip"

    if archive.exists() and _sha256(archive) != locked["sha256"]:
        archive.unlink()

    if not archive.exists():
        request = urllib.request.Request(locked["url"], headers={"User-Agent": "Cyclone-One-build/1"})
        with urllib.request.urlopen(request, timeout=120) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)

    actual = _sha256(archive)
    if actual != locked["sha256"]:
        raise RuntimeError(
            f"Platform-Tools SHA-256 mismatch: expected {locked['sha256']}, got {actual}"
        )

    with tempfile.TemporaryDirectory(prefix="cyclone-platform-tools-") as temp_dir:
        staged = Path(temp_dir) / "android-platform-tools"
        staged.mkdir(parents=True)
        _safe_extract(archive, staged)
        version_marker = staged / "CYCLONE_PLATFORM_TOOLS_VERSION.txt"
        version_marker.write_text(
            f"version={locked['version']}\nsha256={locked['sha256']}\nsource={locked['url']}\n",
            encoding="utf-8",
            newline="\n",
        )
        if target.exists():
            shutil.rmtree(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(staged, target)

    return target


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    args = parser.parse_args()
    target = stage(args.repo.resolve())
    print(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
