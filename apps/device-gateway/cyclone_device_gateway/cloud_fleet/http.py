"""One JSON call to a provider API, with the failures named in plain words. The transport is injectable for tests."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Callable

from .models import ProviderError

USER_AGENT = "Cyclone-PC"
TIMEOUT_S = 15.0

# (method, url, headers, body, timeout) -> (status, body)
Transport = Callable[[str, str, dict[str, str], bytes, float], tuple[int, bytes]]


def urllib_transport(method: str, url: str, headers: dict[str, str], body: bytes, timeout: float) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body if method != "GET" else None, method=method,
                                     headers={"User-Agent": USER_AGENT, **headers})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - https provider API
            return response.status, response.read(1_000_000)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(64_000) if exc.fp else b""


OK_CODES = {0, 200, "0", "200"}


def call_json(transport: Transport, method: str, url: str, headers: dict[str, str], body: bytes, *,
              provider: str) -> Any:
    """The `data` of a provider's `{code, msg, data}` answer; a ProviderError for anything else."""
    try:
        status, raw = transport(method, url, headers, body, TIMEOUT_S)
    except (OSError, urllib.error.URLError) as exc:
        raise ProviderError("PROVIDER_UNREACHABLE", f"Couldn't reach {provider}. Check this PC's internet connection.") from exc
    if status in {401, 403}:
        raise ProviderError("PROVIDER_AUTH", f"{provider} didn't accept the API key. Check it in the account settings.", retryable=False)
    if status == 429:
        raise ProviderError("PROVIDER_BUSY", f"{provider} asked Cyclone to slow down. It tries again shortly.")
    if status >= 500:
        raise ProviderError("PROVIDER_DOWN", f"{provider} had a problem ({status}). Cyclone tries again shortly.")
    if status >= 400:
        raise ProviderError("PROVIDER_REJECTED", f"{provider} refused the request ({status}).", retryable=False)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProviderError("PROVIDER_ANSWER", f"{provider} answered in a way Cyclone doesn't understand.") from exc
    if not isinstance(value, dict):
        raise ProviderError("PROVIDER_ANSWER", f"{provider} answered in a way Cyclone doesn't understand.")
    code = value.get("code", 200)
    if code not in OK_CODES and value.get("success") is not True:
        words = str(value.get("msg") or value.get("message") or "no reason given")[:160]
        lower = words.lower()
        auth = str(code) in {"401", "403"} or "signature" in lower or "unauthori" in lower or "api key" in lower
        raise ProviderError("PROVIDER_AUTH" if auth else "PROVIDER_REFUSED", f"{provider}: {words}", retryable=not auth)
    return value.get("data")
