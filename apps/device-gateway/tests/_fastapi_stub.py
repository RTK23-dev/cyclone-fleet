"""A stand-in for the few fastapi names `fleet_api.py` uses, so its route handlers can be called in a sandbox that
cannot install fastapi. It is NOT fastapi: it does no HTTP, no validation and no dependency injection. It only records
`(method, path, handler)` so a test can call the real handler function directly.

`install()` does nothing when the real fastapi is importable, so CI (which has fastapi) runs against the real thing.
"""
from __future__ import annotations

import importlib.util
import sys
import types
from typing import Any


class HTTPException(Exception):
    def __init__(self, status_code: int, detail: Any = None, headers: Any = None) -> None:
        super().__init__(str(detail))
        self.status_code = status_code
        self.detail = detail


class _Route:
    def __init__(self, path: str, methods: set[str], endpoint: Any) -> None:
        self.path, self.methods, self.endpoint = path, methods, endpoint


class APIRouter:
    def __init__(self, *_a: Any, **_k: Any) -> None:
        self.routes: list[_Route] = []

    def _verb(self, method: str):
        def decorator(path: str, **_kw: Any):
            def wrap(fn: Any) -> Any:
                self.routes.append(_Route(path, {method}, fn))
                return fn
            return wrap
        return decorator

    def get(self, path: str, **kw: Any):
        return self._verb("GET")(path, **kw)

    def post(self, path: str, **kw: Any):
        return self._verb("POST")(path, **kw)

    def websocket(self, path: str, **kw: Any):
        return self._verb("WS")(path, **kw)


def _marker(*_a: Any, **kw: Any) -> Any:
    return kw.get("default")


def install() -> bool:
    """True when the stand-in was installed (real fastapi is absent)."""
    if "fastapi" in sys.modules:
        return getattr(sys.modules["fastapi"], "__cyclone_stub__", False)   # already imported: stub or real
    if importlib.util.find_spec("fastapi") is not None:
        return False
    module = types.ModuleType("fastapi")
    module.__cyclone_stub__ = True
    module.APIRouter, module.HTTPException = APIRouter, HTTPException
    module.Body = module.Depends = module.Header = module.Query = _marker
    for name in ("FastAPI", "WebSocket", "WebSocketDisconnect"):
        setattr(module, name, type(name, (), {}))
    sys.modules["fastapi"] = module
    return True


def endpoint(router: Any, method: str, path: str) -> Any:
    for route in router.routes:
        if route.path == path and method in route.methods:
            return route.endpoint
    raise KeyError(f"{method} {path}")
