from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[3]
CHECK = ROOT / "scripts" / "vmos" / "check-prerequisites.ps1"
INSTALL = ROOT / "scripts" / "vmos" / "install-mobile.ps1"
SYNC_FLEET = (
    ROOT
    / "apps"
    / "pc-companion"
    / "src-tauri"
    / "resources"
    / "chatgpt-attach"
    / "scripts"
    / "Sync-VmosFleet.ps1"
)


def test_vmos_operator_scripts_exist_and_use_canonical_mobile_identity():
    check = CHECK.read_text(encoding="utf-8")
    install = INSTALL.read_text(encoding="utf-8")

    assert "getprop ro.build.version.sdk" in check
    assert "API 33" in check
    assert "Local Debugging" in check
    assert "adb" in check.lower()

    assert "install -r" in install
    assert "com.cyclone.mobile/.MainActivity" in install
    assert "pm path com.cyclone.mobile" in install
    assert "pidof com.cyclone.mobile" in install
    assert "API 33" in install


@pytest.mark.skipif(os.name != "nt", reason="PowerShell syntax gate runs on the repository Windows CI")
def test_vmos_operator_scripts_parse_in_powershell():
    pwsh = shutil.which("pwsh")
    assert pwsh, "Windows CI must provide pwsh for VMOS operator-script validation"

    for path in (CHECK, INSTALL, SYNC_FLEET):
        command = (
            "$errors=$null; "
            f"[System.Management.Automation.Language.Parser]::ParseFile('{path}', [ref]$null, [ref]$errors) | Out-Null; "
            "if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_.Message }; exit 1 }"
        )
        completed = subprocess.run([pwsh, "-NoProfile", "-Command", command], capture_output=True, text=True)
        assert completed.returncode == 0, completed.stderr or completed.stdout


def test_sync_vmos_fleet_does_not_assign_reserved_pid():
    text = SYNC_FLEET.read_text(encoding="utf-8")
    assert "$mobilePid" in text
    assert re.search(r"\$mobilePid\s*=", text)
    assert not re.search(r"\$pid\s*=", text, re.I)
    assert "em dash" not in text.lower()
    assert "\u2014" not in text
    assert "\u2013" not in text
