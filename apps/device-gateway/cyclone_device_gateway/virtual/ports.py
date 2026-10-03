from __future__ import annotations

import socket
import threading


class LoopbackPortAllocator:
    """Allocates an Android Emulator console/ADB pair on loopback only."""

    def __init__(self, start: int = 5554, end: int = 5682):
        if start % 2:
            start += 1
        self.start = start
        self.end = end
        self._lock = threading.Lock()
        self._leased: set[int] = set()

    def allocate_emulator_pair(self, reserved: set[int] | None = None) -> tuple[int, int]:
        reserved = reserved or set()
        with self._lock:
            for console in range(self.start, self.end + 1, 2):
                adb = console + 1
                if console in reserved or adb in reserved:
                    continue
                if console in self._leased or adb in self._leased:
                    continue
                if self._available(console) and self._available(adb):
                    self._leased.update((console, adb))
                    return console, adb
        raise RuntimeError("No safe loopback Android Emulator port pair is available")

    def release_emulator_pair(self, console: int, adb: int | None = None) -> None:
        """Release a pair previously allocated by this allocator.

        Releasing is explicit so a failed provider create or a deleted instance does not
        permanently consume ports for the lifetime of the gateway process.
        """
        with self._lock:
            self._leased.discard(console)
            self._leased.discard(adb if adb is not None else console + 1)

    @staticmethod
    def _available(port: int) -> bool:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False
        finally:
            sock.close()
