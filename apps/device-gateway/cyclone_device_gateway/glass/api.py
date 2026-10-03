from __future__ import annotations

import hmac
import os
import secrets
import threading
import time
from pathlib import Path
from typing import Callable

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, Field

from ..auth import verify_bearer

GLASS_MOUNT = "/glass"
CODE_TTL_SECONDS = 60.0
MAX_OUTSTANDING_CODES = 16
MAX_FAILED_EXCHANGES_PER_MINUTE = 20
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "[::1]"}

_ASSET_TYPES = {
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".map": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
}


def resolve_glass_dist(explicit: str | Path | None = None) -> Path | None:
    """Find the built Glass bundle: explicit path, env, packaged copy, then the repo checkout."""
    here = Path(__file__).resolve()
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    env = os.getenv("CYCLONE_GLASS_DIST", "").strip()
    if env:
        candidates.append(Path(env))
    candidates.append(here.parent / "static")  # wheel / PyInstaller copy
    candidates.append(here.parents[3] / "glass" / "dist")  # apps/device-gateway/../glass/dist in a checkout
    for candidate in candidates:
        try:
            if (candidate / "index.html").is_file():
                return candidate.resolve()
        except OSError:
            continue
    return None


class LaunchCodes:
    """Single-use, short-lived codes that let one browser tab obtain the gateway bearer."""

    def __init__(self, ttl_seconds: float = CODE_TTL_SECONDS, clock: Callable[[], float] = time.monotonic):
        self._ttl = ttl_seconds
        self._clock = clock
        self._codes: dict[str, float] = {}
        self._failures: list[float] = []
        self._lock = threading.Lock()

    def issue(self) -> str:
        code = secrets.token_urlsafe(24)
        with self._lock:
            self._prune()
            while len(self._codes) >= MAX_OUTSTANDING_CODES:
                self._codes.pop(next(iter(self._codes)))
            self._codes[code] = self._clock() + self._ttl
        return code

    def redeem(self, code: str) -> bool:
        now = self._clock()
        with self._lock:
            self._prune()
            self._failures = [at for at in self._failures if now - at < 60.0]
            if len(self._failures) >= MAX_FAILED_EXCHANGES_PER_MINUTE:
                raise HTTPException(status_code=429, detail={"code": "RATE_LIMITED", "message": "Too many launch attempts. Wait a minute."})
            match = next((known for known in self._codes if hmac.compare_digest(known, code)), None)
            if match is None:
                self._failures.append(now)
                return False
            del self._codes[match]
            return True

    def outstanding(self) -> int:
        with self._lock:
            self._prune()
            return len(self._codes)

    @property
    def ttl_seconds(self) -> float:
        return self._ttl

    def _prune(self) -> None:
        now = self._clock()
        for code in [code for code, expires in self._codes.items() if expires <= now]:
            del self._codes[code]


class SessionExchange(BaseModel):
    code: str = Field(min_length=16, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


def _require_loopback_host(request: Request) -> None:
    """Refuse DNS-rebinding style requests: Glass is only served to 127.0.0.1 / localhost."""
    host = (request.headers.get("host") or "").strip().lower()
    name = host.rsplit(":", 1)[0] if not host.startswith("[") else host.split("]", 1)[0] + "]"
    if name not in LOOPBACK_HOSTS:
        raise HTTPException(status_code=421, detail={"code": "HOST_REJECTED", "message": "Glass is served on this PC only."})


def _require_same_origin(request: Request) -> None:
    site = (request.headers.get("sec-fetch-site") or "").lower()
    if site and site not in {"same-origin", "none"}:
        raise HTTPException(status_code=403, detail={"code": "CROSS_SITE_REJECTED", "message": "Glass sessions start from Glass itself."})
    origin = request.headers.get("origin")
    if origin:
        host = request.headers.get("host") or ""
        if origin.rstrip("/").lower() not in {f"http://{host}".lower(), f"https://{host}".lower()}:
            raise HTTPException(status_code=403, detail={"code": "CROSS_SITE_REJECTED", "message": "Glass sessions start from Glass itself."})


def _security_headers(request: Request, *, cache: str) -> dict[str, str]:
    host = request.headers.get("host") or "127.0.0.1"
    csp = "; ".join(
        [
            "default-src 'self'",
            f"connect-src 'self' ws://{host}",
            "img-src 'self' data: blob:",
            "style-src 'self'",
            "script-src 'self'",
            "font-src 'self'",
            "frame-src http://127.0.0.1:* http://[::1]:*",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ]
    )
    return {
        "Content-Security-Policy": csp,
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cache-Control": cache,
    }


def create_glass_router(token: str, codes: LaunchCodes, dist: Path | None) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.post("/v1/glass/launch-code", dependencies=[Depends(auth)])
    def launch_code() -> JSONResponse:
        code = codes.issue()
        return JSONResponse(
            {"code": code, "path": f"{GLASS_MOUNT}/#code={code}", "expiresInSeconds": int(codes.ttl_seconds), "bundle": dist is not None},
            headers={"Cache-Control": "no-store"},
        )

    @router.post("/v1/glass/session", dependencies=[Depends(_require_loopback_host), Depends(_require_same_origin)])
    def session(body: SessionExchange) -> JSONResponse:
        if not codes.redeem(body.code):
            raise HTTPException(status_code=403, detail={"code": "LAUNCH_CODE_REJECTED", "message": "This launch link was used or has expired."})
        return JSONResponse({"token": token}, headers={"Cache-Control": "no-store", "Pragma": "no-cache"})

    @router.get(GLASS_MOUNT, include_in_schema=False, dependencies=[Depends(_require_loopback_host)])
    def glass_root() -> RedirectResponse:
        return RedirectResponse(f"{GLASS_MOUNT}/", status_code=307)

    @router.get(GLASS_MOUNT + "/{path:path}", include_in_schema=False, dependencies=[Depends(_require_loopback_host)])
    def glass_file(path: str, request: Request) -> Response:
        if dist is None:
            return HTMLResponse(
                "<!doctype html><title>Cyclone Glass</title><p>The Glass web app is not built on this PC. "
                "Run <code>npm ci && npm run build</code> in <code>apps/glass</code>, then open Glass again.</p>",
                status_code=503,
                headers=_security_headers(request, cache="no-store"),
            )
        if path in {"", "index.html"}:
            return FileResponse(dist / "index.html", media_type="text/html; charset=utf-8", headers=_security_headers(request, cache="no-store"))
        target = (dist / path).resolve()
        if not target.is_relative_to(dist) or not target.is_file() or target.suffix not in _ASSET_TYPES:
            raise HTTPException(status_code=404, detail={"code": "NOT_FOUND", "message": "Not part of Glass."})
        # Vite fingerprints everything under assets/, so those can be cached for good.
        cache = "public, max-age=31536000, immutable" if path.startswith("assets/") else "no-cache"
        return FileResponse(target, media_type=_ASSET_TYPES[target.suffix], headers=_security_headers(request, cache=cache))

    return router
