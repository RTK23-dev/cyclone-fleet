"""Alpha 87 phone care: the PC keeps Cyclone on the phone current and reads why it stopped, within fixed limits.

- The update only ever runs `adb install -r` on this PC's own release build, checked against the release manifest:
  never a downgrade, never an uninstall, never a caller-chosen file or command.
- No model-facing tool can start an update: it is the owner's button in Glass.
- The phone's health report is read-only, registered on both sides, and carries code frames, not content.
- A busy phone app is reported as PHONE_APP_BUSY on every PC path, not as a lost phone.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CARE = ROOT / "apps/device-gateway/cyclone_device_gateway/phone_care"
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_the_update_installs_only_with_replace_and_never_downgrades_or_uninstalls():
    source = "\n".join(text(p) for p in CARE.glob("*.py"))
    runs = re.findall(r"\.run\(\[([^\]]*)\]", source)
    assert runs == ['"install", "-r", str(apk)'], runs
    for forbidden in ('"-d"', '"uninstall"', "subprocess", "os.system", "Popen", "shell=True"):
        assert forbidden not in source, forbidden
    shells = set(re.findall(r"\.shell\(([^)]*)\)", source))
    assert shells == {'"dumpsys", "package", PACKAGE, timeout=8'}, shells
    assert "expected_sha256" in text(CARE / "apk.py") and "sha256_file" in text(CARE / "apk.py")


def test_no_model_facing_tool_can_start_a_phone_update():
    for tools in (ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp"):
        for path in tools.rglob("*.py"):
            body = text(path)
            assert "care/update" not in body and "start_update" not in body, path
    ai = ROOT / "apps/device-gateway/cyclone_device_gateway/command/ai.py"
    assert "care" not in re.findall(r'"name":\s*"([a-z_]+)"', text(ai))


def test_health_report_is_registered_read_only_on_both_sides():
    protocol = text(MOBILE / "gateway/GatewayProtocol.kt")
    read_only = protocol.split("val legacyReadOnlyOperations")[1].split(")")[0]
    assert '"health.report"' in read_only
    assert '"health.report"' in text(ROOT / "apps/device-gateway/cyclone_device_gateway/cyclone_bridge/protocol.py")
    health = "\n".join(text(p) for p in (MOBILE / "runtime/health").glob("*.kt"))
    # Only names of code, times and sizes leave the phone: no screen text, clipboard, input or storage reads.
    for forbidden in ("clipboard", "ClipboardManager", "AccessibilityNodeInfo", "getText", "SharedPreferences", "Vault"):
        assert forbidden not in health, forbidden


def test_a_busy_phone_is_not_a_lost_phone_on_every_pc_path():
    runtime = ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime"
    for name in ("sessions.py", "agent.py", "v5_contract.py"):
        body = text(runtime / name)
        assert "phone_errors.transport(exc)" in body and 'exc.code == "PHONE_APP_BUSY"' in body, name
        assert '"Phone disconnected from Cyclone Gateway."' not in body, name
    assert "class BridgeBusyError(BridgeDisconnectedError)" in text(ROOT / "apps/device-gateway/cyclone_device_gateway/cyclone_bridge/client.py")
    dispatcher = text(MOBILE / "gateway/GatewayRuntime.kt")
    assert "load.enter(request.op" in dispatcher and '"PHONE_APP_BUSY"' in dispatcher
    assert "MainThreadWatchdog.start(app)" in text(MOBILE / "gateway/GatewayInitProvider.kt")
