from __future__ import annotations
import importlib.util, json
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]

def load_script(name: str):
    path = REPO / "scripts" / "pc-companion" / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module

def test_sidecar_lock_is_exact_and_specs_exist():
    lock = json.loads((REPO / "packaging/pc-companion/sidecar-build.lock.json").read_text())
    assert lock["python"] == "3.13.7"
    assert lock["pyinstaller"] == "6.22.2"
    assert lock["mcp"] == "2.0.0"
    assert lock["websockets"] == "15.0.1"
    assert {x["name"] for x in lock["sidecars"]} == {"CyclonePCRuntime.exe", "CycloneAgentMCP.exe", "CycloneLivePhone.exe"}
    assert all((REPO / x["spec"]).is_file() for x in lock["sidecars"])

def test_frozen_gateway_declares_websocket_runtime():
    gateway = (REPO / "apps/device-gateway/pyproject.toml").read_text()
    build = (REPO / "scripts/pc-companion/build-sidecars.ps1").read_text()
    lock = json.loads((REPO / "packaging/pc-companion/sidecar-build.lock.json").read_text())
    assert f'"websockets=={lock["websockets"]}"' in gateway
    assert build.count("PyInstaller --clean --noconfirm") == 3

def test_pinned_scrcpy_notice_is_deterministic_and_complete(tmp_path):
    script = load_script("generate-third-party-notices.py")
    data = script.load_lock(REPO / "packaging/pc-companion/third-party-binaries.lock.json")
    a = script.render(data)
    b = script.render(data)
    assert a == b
    assert "scrcpy-server 4.0" in a
    assert "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a" in a
    assert "Apache-2.0" in a
    assert "latest" not in a.lower()

def test_invalid_unpinned_third_party_binary_is_rejected(tmp_path):
    script = load_script("generate-third-party-notices.py")
    p = tmp_path / "lock.json"
    p.write_text(json.dumps({"schema_version": 1, "binaries": [{"name":"scrcpy","version":"latest"}]}))
    try:
        script.load_lock(p)
    except ValueError:
        pass
    else:
        raise AssertionError("unpinned incomplete binary was accepted")

def test_release_metadata_has_checksums_source_and_no_secret_values(tmp_path, monkeypatch):
    script = load_script("generate-release-metadata.py")
    artifact = tmp_path / "artifacts"; artifact.mkdir()
    (artifact / "CycloneAgentMCP.exe").write_bytes(b"fixture")
    import sys
    old = sys.argv
    sys.argv = ["x", "--artifact-dir", str(artifact), "--source-sha", "a"*40, "--version", "1.0.0", "--third-party-lock", str(REPO / "packaging/pc-companion/third-party-binaries.lock.json")]
    try: assert script.main() == 0
    finally: sys.argv = old
    assert (artifact / "SHA256SUMS.txt").is_file()
    assert (artifact / "source-sha.txt").read_text().strip() == "a"*40
    prov = (artifact / "release-provenance.json").read_text()
    assert "fixture" not in prov
    assert "token" not in prov.lower()

def test_release_workflow_is_manual_and_never_publishes_release():
    text = (REPO / ".github/workflows/pc-companion-release.yml").read_text()
    lower = text.lower()
    assert "workflow_dispatch:" in text
    assert "pull_request:" not in text
    assert "runs-on: windows-latest" in text
    assert "default: false" in text
    for forbidden in ("gh release", "softprops/action-gh-release", "actions/create-release", "release: write"):
        assert forbidden not in lower
    assert "actions/upload-artifact" in text

def test_ci_runs_focused_mcp_tests_and_windows_sidecars():
    text = (REPO / ".github/workflows/pc-companion-ci.yml").read_text()
    assert "runs-on: windows-latest" in text
    assert "tools/cyclone-agent-mcp/tests" in text
    assert "build-sidecars.ps1" in text
    assert "actions/upload-artifact" in text
