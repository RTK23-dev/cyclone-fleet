"""When to renew a link, how long to wait after a failure, and the local port each phone keeps. Pure."""
from __future__ import annotations

import hashlib
from typing import Callable, Iterable

from .models import AdbLink

# Renew when a fifth of the lease is left (a 7-day VMOS key renews after about 5.6 days), never later than 15 minutes
# before it ends.
RENEW_FRACTION = 0.2
RENEW_MIN_MS = 15 * 60_000
BACKOFF_S = (5, 15, 60, 300)
# A failure the owner has to fix (a wrong key, a missing address) is retried rarely, in case it was fixed elsewhere.
NEEDS_YOU_RETRY_S = 30 * 60
PORT_BASE, PORT_SPAN = 19_000, 1_000


def renew_at_ms(link: AdbLink) -> int | None:
    if link.expires_at_ms is None:
        return None
    lifetime = max(0, link.expires_at_ms - link.issued_at_ms)
    return link.expires_at_ms - max(int(lifetime * RENEW_FRACTION), RENEW_MIN_MS)


def backoff_s(attempt: int) -> int:
    return BACKOFF_S[min(max(attempt, 1), len(BACKOFF_S)) - 1]


def stable_port(key: str, used: Iterable[int], free: Callable[[int], bool]) -> int | None:
    """The same local port for a phone every time it's free, so it keeps one fleet identity."""
    taken = set(used)
    seed = int(hashlib.sha256(key.encode("utf-8")).hexdigest()[:8], 16)
    for offset in range(PORT_SPAN):
        port = PORT_BASE + (seed + offset) % PORT_SPAN
        if port not in taken and free(port):
            return port
    return None


def in_words(ms: int) -> str:
    minutes = max(0, ms) // 60_000
    if minutes >= 2 * 24 * 60:
        return f"{minutes // (24 * 60)} days"
    if minutes >= 120:
        return f"{minutes // 60} hours"
    if minutes >= 2:
        return f"{minutes} min"
    return "a minute"
