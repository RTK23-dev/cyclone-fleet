from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from cyclone_device_gateway.terminal import release as rel
from cyclone_device_gateway.terminal import updater
from cyclone_device_gateway.terminal.app import TerminalIO, check_for_update, parse
from cyclone_device_gateway.terminal.install import UPDATE_EXIT_CODE, migrate_window_runtime, path_with, path_without, shim_text
from cyclone_device_gateway.terminal.window import app_window_args, find_browser


def _release(tag: str, *, draft: bool = False, package: bool = True, manifest: bool = True) -> dict:
    assets = []
    if package:
        assets.append({"name": f"Cyclone-PC-{tag.lstrip('v')}.zip",
                       "browser_download_url": f"https://github.com/o/r/releases/download/{tag}/Cyclone-PC-{tag.lstrip('v')}.zip"})
    if manifest:
        assets.append({"name": "release-manifest.json", "browser_download_url": f"https://github.com/o/r/releases/download/{tag}/release-manifest.json"})
    return {"tag_name": tag, "draft": draft, "html_url": f"https://github.com/o/r/releases/tag/{tag}", "assets": assets}


def test_versions_order_alphas_then_final():
    assert rel.version_key("5.0.0-alpha.9.dev1") < rel.version_key("5.0.0-alpha.23.dev1") < rel.version_key("v5.0.0")
    assert rel.is_newer("5.0.0-alpha.24.dev1", "5.0.0-alpha.23.dev1")
    assert not rel.is_newer("5.0.0-alpha.23.dev1", "5.0.0-alpha.23.dev1")
    assert not rel.is_newer("garbage", "5.0.0-alpha.1.dev1")


def test_latest_release_skips_drafts_incomplete_and_foreign_tags():
    picked = rel.pick_latest([
        _release("v5.0.0-alpha.9.dev1"),
        _release("v5.0.0-alpha.25.dev1", draft=True),
        _release("v5.0.0-alpha.24.dev1", manifest=False),
        _release("v5.0.0-alpha.26.dev1", package=False),
        _release("v5.0.0-alpha.23.dev1"),
        _release("mobile-v4.8.0"),
    ])
    assert picked is not None and picked.version == "5.0.0-alpha.23.dev1"
    assert picked.package_name == "Cyclone-PC-5.0.0-alpha.23.dev1.zip"


def test_package_is_refused_unless_its_checksum_matches_the_manifest(tmp_path: Path):
    good = b"PK zip bytes"
    found = rel.pick_latest([_release("v5.0.0-alpha.24.dev1")])
    manifest = json.dumps({"sha256": {found.package_name: hashlib.sha256(good).hexdigest()}}).encode()

    def fetch(payload):
        return lambda url, timeout: manifest if url.endswith("release-manifest.json") else payload

    target = updater.download_package(found, tmp_path / "Cyclone-PC.zip", fetch=fetch(good))
    assert target.read_bytes() == good
    assert (tmp_path / "Cyclone-PC.zip.sha256").read_text() == hashlib.sha256(good).hexdigest()
    with pytest.raises(updater.UpdateError):
        updater.download_package(found, tmp_path / "other.zip", fetch=fetch(b"tampered"))
    assert not (tmp_path / "other.zip").exists() and not (tmp_path / "other.part").exists()
    with pytest.raises(updater.UpdateError):
        updater.download_package(found, tmp_path / "x.zip", fetch=lambda url, timeout: b'{"sha256": {}}' if url.endswith(".json") else good)


def test_release_check_is_cached_and_never_blocks_offline(tmp_path: Path):
    cache = tmp_path / "update-check.json"
    calls = []

    def fetch(url, timeout):
        calls.append(url)
        return json.dumps([_release("v5.0.0-alpha.24.dev1")]).encode()

    first = updater.latest_release(cache, fetch=fetch, now=lambda: 1000.0)
    second = updater.latest_release(cache, fetch=fetch, now=lambda: 2000.0)
    assert first == second and len(calls) == 1
    offline = updater.latest_release(tmp_path / "x.json", fetch=lambda u, t: (_ for _ in ()).throw(OSError("offline")))
    assert offline is None


def _io(answers=("",)):
    lines, queue = [], list(answers)
    return TerminalIO(out=lines.append, ask=lambda q: queue.pop(0) if queue else "", interactive=True), lines


def test_update_flow_asks_then_hands_the_verified_installer_to_the_shim(tmp_path: Path, monkeypatch):
    found = rel.pick_latest([_release("v5.0.0-alpha.24.dev1")])
    downloaded = []
    io, lines = _io(["y"])
    code = check_for_update(io, force=False, installed="5.0.0-alpha.23.dev1", runtime_dir=tmp_path, updates_dir=tmp_path / "u",
                            latest=lambda cache, force: found, download=lambda r, t, progress: downloaded.append(t) or t)
    import os
    if os.name == "nt":
        assert code == UPDATE_EXIT_CODE and downloaded
    else:
        assert code is None and any("Download it from" in line for line in lines)
    io, lines = _io(["n"])
    assert check_for_update(io, force=False, installed="5.0.0-alpha.23.dev1", runtime_dir=tmp_path, updates_dir=tmp_path,
                            latest=lambda cache, force: found, download=lambda *a, **k: pytest.fail("declined")) is None
    io, lines = _io()
    assert check_for_update(io, force=True, installed="5.0.0-alpha.24.dev1", runtime_dir=tmp_path, updates_dir=tmp_path,
                            latest=lambda cache, force: found) is None
    assert "newest Cyclone" in lines[-1]


def test_shim_runs_the_runtime_then_the_installer_only_on_the_update_code():
    text = shim_text(Path(r"C:\Users\me\AppData\Local\Cyclone One\CyclonePCRuntime.exe"), Path(r"C:\Users\me\AppData\Local\Cyclone One\updates"))
    assert text.startswith("@echo off\r\n")
    assert '"%CYCLONE_RUNTIME%" terminal %*' in text
    assert f'if not "%CYCLONE_EXIT%"=="{UPDATE_EXIT_CODE}" exit /b %CYCLONE_EXIT%' in text
    update = text.splitlines()[-1]
    # The batch file ends itself before PowerShell may replace it.
    assert update.startswith("(goto) 2>nul & powershell.exe ")
    assert '-File "C:\\Users\\me\\AppData\\Local\\Cyclone One\\install.ps1"' in update
    assert '-Zip "C:\\Users\\me\\AppData\\Local\\Cyclone One\\updates\\Cyclone-PC.zip" -Relaunch' in update
    assert text.index("terminal %*") < text.index("(goto)")
    assert text.isascii()


def test_window_runtime_is_copied_once_and_never_overwrites(tmp_path: Path):
    old = tmp_path / "local" / "com.cyclone.pccompanion" / "runtime"
    (old / "state").mkdir(parents=True)
    (old / "state" / "trust.json").write_text("paired")
    (old / "gateway-token.dpapi").write_text("old-token")
    (old / "diagnostics").mkdir()
    target = tmp_path / "local" / "Cyclone One" / "runtime"
    target.mkdir(parents=True)
    (target / "gateway-token.dpapi").write_text("new-token")
    assert migrate_window_runtime(tmp_path / "local", target) == ["state"]
    assert (target / "state" / "trust.json").read_text() == "paired"
    assert (target / "gateway-token.dpapi").read_text() == "new-token"
    assert not (target / "diagnostics").exists()
    assert migrate_window_runtime(tmp_path / "local", target) == []
    assert migrate_window_runtime(tmp_path / "nowhere", target) == []


def test_terminal_card_says_how_to_stop_start_and_update():
    from cyclone_device_gateway.terminal.banner import run_stop_card

    for fancy in (True, False):
        card = run_stop_card(fancy=fancy)
        text = "\n".join(card)
        assert "Ctrl+C" in text and "cyclone update" in text and "type  cyclone" in text
        assert len({len(line) for line in card}) == 1, "every line of the box is the same width"
    assert "\n".join(run_stop_card(fancy=False)).isascii()


def test_user_path_is_appended_once_and_removed_cleanly():
    bin_dir = r"C:\Users\me\AppData\Local\Cyclone One\bin"
    assert path_with(r"C:\a;C:\b", bin_dir) == rf"C:\a;C:\b;{bin_dir}"
    assert path_with(rf"C:\a;{bin_dir}\\", bin_dir) is None
    assert path_without(rf"C:\a;{bin_dir};C:\b", bin_dir) == r"C:\a;C:\b"
    assert path_without(r"C:\a", bin_dir) is None


def test_glass_window_owns_its_own_profile_and_prefers_an_installed_chromium():
    env = {"ProgramFiles": r"C:\PF", "ProgramFiles(x86)": r"C:\PF86", "LOCALAPPDATA": r"C:\L"}
    edge = Path(r"C:\PF86") / "Microsoft/Edge/Application/msedge.exe"
    assert find_browser(lambda p: p == edge, env) == edge
    assert find_browser(lambda p: False, env) is None
    args = app_window_args(edge, "http://127.0.0.1:8765/glass/#code=abc", Path(r"C:\L\Cyclone One\glass-window"))
    assert args[1] == "--app=http://127.0.0.1:8765/glass/#code=abc"
    assert any(a.startswith("--user-data-dir=") for a in args)


def test_terminal_arguments():
    assert parse([]).command is None
    assert parse(["update"]).command == "update"
    assert parse(["--no-update", "--browser"]).browser


def test_installed_version_comes_from_the_release_metadata_in_a_checkout():
    assert rel.version_key(rel.installed_version()) is not None


def test_overview_names_runtime_phones_and_glass_without_color_codes_when_plain():
    from cyclone_device_gateway.terminal.banner import Overview, phone_lines, render

    phones = phone_lines([{"name": "Pixel 8", "connectionLabel": "Ready", "transport": {"endpoint": "usb"}}, "junk"])
    assert phones == ["Pixel 8 · Ready · USB"]
    lines = render(Overview("5.0.0-alpha.24.dev1", "up to date", "Cyclone One (already running)", "http://127.0.0.1:8765",
                            phones, "open in its own window · closes with this terminal"), color=False)
    text = "\n".join(lines)
    assert "\x1b[" not in text
    for expected in ("5.0.0-alpha.24.dev1", "up to date", "Cyclone One", "127.0.0.1:8765", "Pixel 8 · Ready · USB", "own window"):
        assert expected in text
    empty = "\n".join(render(Overview("5.0.0"), color=True))
    assert "none yet" in empty and "\x1b[" in empty


def test_phone_list_is_empty_not_an_error_when_the_gateway_is_slow():
    from cyclone_device_gateway.terminal.banner import fetch_phones

    def slow(*_a, **_k):
        raise TimeoutError()

    assert fetch_phones("http://127.0.0.1:1", "t", opener=slow) == []
