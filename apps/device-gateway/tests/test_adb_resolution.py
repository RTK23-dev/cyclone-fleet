from __future__ import annotations

from pathlib import Path

import cyclone_device_gateway.config as config


def test_explicit_adb_path_wins(monkeypatch, tmp_path):
    explicit = tmp_path / "custom-adb.exe"
    explicit.write_bytes(b"custom")
    monkeypatch.setenv("ADB_PATH", str(explicit))
    monkeypatch.setattr(config.shutil, "which", lambda _: "C:/system/adb.exe")

    assert config.resolve_adb_path() == str(explicit)


def test_bundled_adb_wins_over_system_path(monkeypatch, tmp_path):
    runtime = tmp_path / "CyclonePCRuntime.exe"
    runtime.write_bytes(b"runtime")
    bundled = tmp_path / "android-platform-tools" / "adb.exe"
    bundled.parent.mkdir()
    bundled.write_bytes(b"adb")

    monkeypatch.delenv("ADB_PATH", raising=False)
    monkeypatch.setattr(config.sys, "executable", str(runtime))
    monkeypatch.setattr(config.shutil, "which", lambda _: "C:/system/adb.exe")

    assert Path(config.resolve_adb_path()) == bundled


def test_localappdata_one_bundle_is_used_for_python_launch(monkeypatch, tmp_path):
    local = tmp_path / "Local"
    bundled = local / "Cyclone One" / "android-platform-tools" / "adb.exe"
    bundled.parent.mkdir(parents=True)
    bundled.write_bytes(b"adb")

    monkeypatch.delenv("ADB_PATH", raising=False)
    monkeypatch.setenv("LOCALAPPDATA", str(local))
    monkeypatch.setattr(config.sys, "executable", str(tmp_path / "python.exe"))
    monkeypatch.setattr(config.shutil, "which", lambda _: None)

    assert Path(config.resolve_adb_path()) == bundled


def test_system_adb_remains_development_fallback(monkeypatch, tmp_path):
    monkeypatch.delenv("ADB_PATH", raising=False)
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.delenv("USERPROFILE", raising=False)
    monkeypatch.setattr(config.sys, "executable", str(tmp_path / "python.exe"))
    monkeypatch.setattr(config.shutil, "which", lambda _: "C:/system/adb.exe")

    assert config.resolve_adb_path() == "C:/system/adb.exe"
