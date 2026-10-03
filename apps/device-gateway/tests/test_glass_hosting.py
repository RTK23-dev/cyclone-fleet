"""Cyclone Glass hosting: static bundle, launch-code session, loopback-only, launcher."""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.glass import LaunchCodes, create_glass_router, resolve_glass_dist
from cyclone_device_gateway.glass.launcher import LaunchTarget, run_glass

TOKEN = "gateway-secret-token"
BASE = "http://127.0.0.1:8765"


@pytest.fixture()
def dist(tmp_path: Path) -> Path:
    root = tmp_path / "dist"
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text("<!doctype html><title>Cyclone Glass</title><div id=app></div>", encoding="utf-8")
    (root / "assets" / "index-abc123.js").write_text("console.log('glass')", encoding="utf-8")
    (root / "assets" / "index-abc123.css").write_text("body{}", encoding="utf-8")
    (tmp_path / "secret.txt").write_text("outside the bundle", encoding="utf-8")
    return root


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def make_client(dist: Path | None, codes: LaunchCodes | None = None) -> tuple[TestClient, LaunchCodes]:
    codes = codes or LaunchCodes()
    app = FastAPI()
    app.include_router(create_glass_router(TOKEN, codes, dist))
    return TestClient(app, base_url=BASE), codes


def auth() -> dict[str, str]:
    return {"Authorization": f"Bearer {TOKEN}"}


def test_launch_code_requires_the_bearer_and_is_single_use(dist: Path) -> None:
    client, codes = make_client(dist)
    assert client.post("/v1/glass/launch-code").status_code == 401
    assert client.post("/v1/glass/launch-code", headers={"Authorization": "Bearer nope"}).status_code == 403

    issued = client.post("/v1/glass/launch-code", headers=auth())
    assert issued.status_code == 200
    body = issued.json()
    assert body["path"] == f"/glass/#code={body['code']}"
    assert body["bundle"] is True
    assert issued.headers["cache-control"] == "no-store"
    assert TOKEN not in issued.text

    first = client.post("/v1/glass/session", json={"code": body["code"]})
    assert first.status_code == 200
    assert first.json() == {"token": TOKEN}
    assert first.headers["cache-control"] == "no-store"
    second = client.post("/v1/glass/session", json={"code": body["code"]})
    assert second.status_code == 403
    assert second.json()["detail"]["code"] == "LAUNCH_CODE_REJECTED"
    assert codes.outstanding() == 0


def test_codes_expire_and_are_bounded() -> None:
    clock = Clock()
    codes = LaunchCodes(ttl_seconds=60, clock=clock)
    code = codes.issue()
    clock.now += 61
    assert codes.redeem(code) is False
    many = [codes.issue() for _ in range(40)]
    assert codes.outstanding() == 16
    assert codes.redeem(many[-1]) is True
    assert codes.redeem(many[0]) is False, "oldest codes are evicted first"


def test_repeated_bad_codes_are_rate_limited(dist: Path) -> None:
    client, _ = make_client(dist)
    for _ in range(20):
        assert client.post("/v1/glass/session", json={"code": "x" * 32}).status_code == 403
    limited = client.post("/v1/glass/session", json={"code": "y" * 32})
    assert limited.status_code == 429
    assert limited.json()["detail"]["code"] == "RATE_LIMITED"


def test_session_exchange_rejects_other_hosts_and_cross_site_requests(dist: Path) -> None:
    client, codes = make_client(dist)
    code = codes.issue()
    rebound = TestClient(client.app, base_url="http://evil.example:8765")
    assert rebound.post("/v1/glass/session", json={"code": code}).status_code == 421
    assert client.post("/v1/glass/session", json={"code": code}, headers={"Origin": "https://evil.example"}).status_code == 403
    assert client.post("/v1/glass/session", json={"code": code}, headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
    same = client.post("/v1/glass/session", json={"code": code}, headers={"Origin": BASE, "Sec-Fetch-Site": "same-origin"})
    assert same.status_code == 200
    assert client.post("/v1/glass/session", json={"code": "not valid!"}).status_code == 422


def test_bundle_is_served_with_strict_headers(dist: Path) -> None:
    client, _ = make_client(dist)
    redirect = client.get("/glass", follow_redirects=False)
    assert redirect.status_code == 307
    assert redirect.headers["location"] == "/glass/"

    index = client.get("/glass/")
    assert index.status_code == 200
    assert "Cyclone Glass" in index.text
    csp = index.headers["content-security-policy"]
    for directive in ("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'", "connect-src 'self' ws://127.0.0.1:8765"):
        assert directive in csp
    assert index.headers["x-frame-options"] == "DENY"
    assert index.headers["referrer-policy"] == "no-referrer"
    assert index.headers["cache-control"] == "no-store"

    script = client.get("/glass/assets/index-abc123.js")
    assert script.status_code == 200
    assert script.headers["content-type"].startswith("text/javascript")
    assert "immutable" in script.headers["cache-control"]


def test_bundle_paths_cannot_escape_or_serve_unknown_types(dist: Path) -> None:
    client, _ = make_client(dist)
    assert client.get("/glass/../secret.txt").status_code in {404, 400}
    assert client.get("/glass/%2e%2e/secret.txt").status_code == 404
    assert client.get("/glass/assets/missing.js").status_code == 404
    (dist / "notes.txt").write_text("x", encoding="utf-8")
    assert client.get("/glass/notes.txt").status_code == 404
    assert TestClient(client.app, base_url="http://evil.example").get("/glass/").status_code == 421


def test_missing_bundle_explains_how_to_build(tmp_path: Path) -> None:
    client, _ = make_client(None)
    response = client.get("/glass/")
    assert response.status_code == 503
    assert "npm run build" in response.text
    assert client.post("/v1/glass/launch-code", headers=auth()).json()["bundle"] is False


def test_resolve_glass_dist_prefers_explicit_then_env(tmp_path: Path, dist: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert resolve_glass_dist(dist) == dist.resolve()
    monkeypatch.setenv("CYCLONE_GLASS_DIST", str(dist))
    assert resolve_glass_dist(tmp_path / "missing") == dist.resolve()


def test_desktop_app_mounts_glass(tmp_path: Path, dist: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from cyclone_device_gateway.config import Settings
    from cyclone_device_gateway.desktop_runtime.api import create_desktop_app

    monkeypatch.setenv("CYCLONE_GLASS_DIST", str(dist))

    class Runtime:
        # Routers only bind to these at construction; no phone is touched in this test.
        fleet = object()

        def start(self) -> None: ...
        def stop(self) -> None: ...

    app = create_desktop_app(Settings(TOKEN, None, "adb", tmp_path), Runtime())
    client = TestClient(app, base_url=BASE)
    code = client.post("/v1/glass/launch-code", headers=auth()).json()["code"]
    assert client.post("/v1/glass/session", json={"code": code}).json() == {"token": TOKEN}
    assert client.get("/glass/").status_code == 200


def test_launcher_uses_a_running_gateway_first() -> None:
    opened: list[str] = []
    printed: list[str] = []
    target = LaunchTarget(base_url=BASE, path="/glass/#code=abcdefghijklmnopqrstuvwx")
    code = run_glass(
        connection_loader=lambda: {"token": TOKEN, "url": BASE},
        code_requester=lambda url, token: target if (url, token) == (BASE, TOKEN) else None,
        opener=lambda url: opened.append(url) or True,
        out=printed.append,
        serve=lambda announce: pytest.fail("must not start a second gateway"),
    )
    assert code == 0
    assert opened == [target.url]
    assert printed == [f"Cyclone Glass: {BASE}/glass/"], "the one-time code is not printed by default"


def test_launcher_starts_a_gateway_when_none_answers() -> None:
    started: list[str] = []

    def serve(announce):
        started.append("serve")
        announce(LaunchTarget(base_url=BASE, path="/glass/#code=zzzzzzzzzzzzzzzzzzzzzzzz"))
        return 0

    printed: list[str] = []
    code = run_glass(
        open_browser=False,
        print_url=True,
        connection_loader=lambda: {"token": TOKEN, "url": BASE},
        code_requester=lambda url, token: None,
        opener=lambda url: pytest.fail("browser disabled"),
        out=printed.append,
        serve=serve,
    )
    assert code == 0
    assert started == ["serve"]
    assert printed == [f"{BASE}/glass/#code=zzzzzzzzzzzzzzzzzzzzzzzz"]


def test_launcher_output_is_flushed_for_logs_and_pipes(capsys: pytest.CaptureFixture[str]) -> None:
    # The launcher keeps a gateway running; unflushed output never reaches a log file (CI smoke regression).
    import builtins

    seen: list[bool] = []
    original = builtins.print

    def spy(*args, **kwargs):
        seen.append(bool(kwargs.get("flush")))
        return original(*args, **kwargs)

    builtins.print = spy
    try:
        run_glass(
            open_browser=False,
            connection_loader=lambda: {"token": TOKEN, "url": BASE},
            code_requester=lambda url, token: LaunchTarget(base_url=BASE, path="/glass/#code=abcdefghijklmnopqrstuvwx"),
        )
    finally:
        builtins.print = original
    assert seen == [True]
    assert capsys.readouterr().out.strip() == f"Cyclone Glass: {BASE}/glass/"
