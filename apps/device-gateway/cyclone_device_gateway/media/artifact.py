from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
from typing import Any

SCRCPY_VERSION = "4.0"
SCRCPY_TAG = "v4.0"
SCRCPY_COMMIT = "2322868e9e256eb5fce0b3d659ab2a409f29bae1"
SCRCPY_SERVER_FILENAME = "scrcpy-server-v4.0"
SCRCPY_SERVER_SHA256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
SCRCPY_SERVER_URL = (
    "https://github.com/Genymobile/scrcpy/releases/download/v4.0/scrcpy-server-v4.0"
)


class ScrcpyArtifactError(RuntimeError):
    pass


@dataclass(frozen=True)
class ScrcpyArtifact:
    path: Path
    version: str = SCRCPY_VERSION
    tag: str = SCRCPY_TAG
    commit: str = SCRCPY_COMMIT
    sha256: str = SCRCPY_SERVER_SHA256
    source_url: str = SCRCPY_SERVER_URL

    def verify(self) -> "ScrcpyArtifact":
        if not self.path.is_file():
            raise ScrcpyArtifactError(
                f"Pinned scrcpy server is missing: {self.path}. "
                "Install/package the exact v4.0 server before starting media."
            )
        digest = hashlib.sha256()
        with self.path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        actual = digest.hexdigest()
        if actual != self.sha256:
            raise ScrcpyArtifactError(
                "Pinned scrcpy server checksum mismatch "
                f"(expected {self.sha256}, got {actual})."
            )
        return self


def default_scrcpy_server_path() -> Path:
    override = os.environ.get("CYCLONE_SCRCPY_SERVER")
    if override:
        return Path(override).expanduser().resolve()
    gateway_root = Path(__file__).resolve().parents[2]
    return gateway_root / "third_party" / "scrcpy" / SCRCPY_SERVER_FILENAME


def resolve_scrcpy_artifact(path: str | os.PathLike[str] | None = None) -> ScrcpyArtifact:
    candidate = Path(path).expanduser().resolve() if path is not None else default_scrcpy_server_path()
    return ScrcpyArtifact(candidate).verify()


def metadata() -> dict[str, Any]:
    return {
        "name": "scrcpy-server",
        "version": SCRCPY_VERSION,
        "tag": SCRCPY_TAG,
        "upstreamCommit": SCRCPY_COMMIT,
        "source": SCRCPY_SERVER_URL,
        "sha256": SCRCPY_SERVER_SHA256,
        "license": "Apache-2.0",
        "runtimeLatestFetchAllowed": False,
    }


def verify_metadata_file(path: str | os.PathLike[str]) -> bool:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    expected = metadata()
    return all(data.get(key) == value for key, value in expected.items())
