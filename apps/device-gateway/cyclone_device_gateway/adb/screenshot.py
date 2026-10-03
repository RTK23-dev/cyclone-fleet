from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
import struct
import time

from .client import ADBClient


def is_png(data: bytes) -> bool:
    """Return True when the payload is a complete-enough PNG (signature + IHDR)."""
    return len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR"


@dataclass(frozen=True)
class ScreenshotMeta:
    screenshot_id: str
    path: str
    sha256: str
    width: int | None
    height: int | None
    timestamp: float


def png_dimensions(data: bytes) -> tuple[int | None, int | None]:
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        return struct.unpack(">II", data[16:24])
    return None, None


class ScreenshotStore:
    def __init__(self, root: Path):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def capture(self, adb: ADBClient) -> ScreenshotMeta:
        return self.save(adb.exec_out("screencap", "-p", timeout=20))

    def save(self, data: bytes) -> ScreenshotMeta:
        digest = sha256(data).hexdigest()
        path = self.root / f"{digest}.png"
        if not path.exists():
            path.write_bytes(data)
        width, height = png_dimensions(data)
        return ScreenshotMeta(digest[:20], str(path), digest, width, height, time.time())
