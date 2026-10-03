"""Alpha 88 connection reliability: one truth, the broken link named, connecting that stays connected — within limits.

- A trusted session belongs to the USB session it was opened on; a replug or a rejected token is restored, never assumed.
- The PC asks "Connect this PC?" by itself at most once per plug-in, only over USB, never again for a day after Not
  now, and can be turned off (CYCLONE_AUTO_CONNECT=0). The owner's Allow on the phone stays the only way to trust.
- The medic runs fixed commands only: it never grants a permission, changes a setting, types or taps.
- The phone's wake receiver is guarded by android.permission.DUMP, so only adb or the system can send it.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime"
MOBILE = ROOT / "apps/mobile/app/src/main"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_trusted_sessions_are_bound_to_their_usb_session():
    trust = text(RUNTIME / "trust_v33.py")
    assert 'active.usb_session_id == str(getattr(session, "usb_session_id", "") or "")' in trust
    assert "getattr(session, \"credential\", None) == active.token" in trust
    assert "active_ready = self._is_current(active, session)" in trust


def test_the_automatic_ask_is_bounded_and_never_allows_on_its_own():
    trust = text(RUNTIME / "trust_v33.py")
    ask = trust.split("def _maybe_auto_ask")[1].split("\n    def ")[0]
    for rule in ('self._asked_usb.get(device_id) == usb', "AUTO_ASK_DECLINE_COOLDOWN_MS", 'not in {"USB", "CLOUD"}', "self.auto_ask"):
        assert rule in ask, rule
    assert 'os.getenv("CYCLONE_AUTO_CONNECT", "1")' in trust
    # Plan 44: a stray LAN address is never asked; only USB and the cloud phones the owner added with a provider key.
    assert '"LAN"' not in ask
    prompt = text(MOBILE / "java/com/cyclone/mobile/gateway/GatewayTrustPrompt.kt")
    # Opening the Allow card is all it does: the decision is still the owner's tap there.
    assert "completeTrust" not in prompt and "allow(" not in prompt.lower().replace("allowed", "")


def test_the_medic_runs_fixed_commands_only():
    medic = text(RUNTIME / "connection_medic.py")
    shells = re.findall(r"\.shell\(([^)]*)\)", medic)
    assert set(shells) <= {'"pidof", CYCLONE_PACKAGE, timeout=3', "*WAKE, timeout=8", "*OPEN_APP, timeout=8", "*OPEN_ACCESSIBILITY, timeout=8"}, shells
    # Command words that would grant, change or type something: none may appear as a command argument.
    for forbidden in ('"settings"', '"pm"', '"appops"', '"input"', '"uninstall"', '"su"', "subprocess", "enabled_accessibility_services"):
        assert forbidden not in medic, forbidden


def test_the_wake_receiver_is_only_for_adb_and_the_system():
    manifest = text(MOBILE / "AndroidManifest.xml")
    block = manifest.split('android:name=".gateway.GatewayWakeReceiver"')[1].split("</receiver>")[0]
    assert 'android:permission="android.permission.DUMP"' in block
    receiver = text(MOBILE / "java/com/cyclone/mobile/gateway/GatewayWakeReceiver.kt")
    assert "startPairingBootstrap" in receiver and "startActivity" not in receiver


def test_every_surface_reads_the_same_verdict():
    readiness = text(RUNTIME / "readiness.py")
    assert 'public["connection"] = diagnose_session(' in readiness
    glass = text(ROOT / "apps/glass/src/services/devices.ts")
    assert "parseConnection(record.connection)" in glass


def test_the_accessibility_keeper_only_puts_cyclone_back_where_the_owner_had_it():
    """Alpha 91: after Android removes Cyclone's Accessibility (a force-stop), the PC restores that one setting, only
    on a phone where it saw it on before, bounded, recorded, and with an off switch."""
    keeper = text(RUNTIME / "accessibility_keeper.py")
    shells = re.findall(r"\.shell\(([^)]*)\)", keeper)
    # Alpha 93: the owner's Repair (repair_now) uses the same three commands; nothing else ever runs.
    assert set(shells) == {'"settings", "get", "secure", KEY, timeout=5',
                           '"settings", "put", "secure", KEY, f"\'{restored(value',
                           '"settings", "put", "secure", "accessibility_enabled", "1", timeout=5'}, shells
    medic = text(RUNTIME / "connection_medic.py")
    assert medic.count("repair_now(") == 1 and 'elif action == "open_accessibility":' in medic, \
        "only the owner's own Repair press skips the seen-on rule"
    assert "MAX_OWNER_REPAIRS_PER_HOUR = 6" in keeper
    assert 'KEY = "enabled_accessibility_services"' in keeper
    assert "if not enabled() or not self._seen.get(device_id)" in keeper
    assert "MAX_PER_HOUR = 3" in keeper and 'CYCLONE_AUTO_REPAIR_ACCESSIBILITY", "1"' in keeper
    assert "kept + [SERVICE]" in keeper  # only Cyclone's own service is ever added
    for forbidden in ("pm grant", '"pm"', '"appops"', '"input"', "subprocess", "global"):
        assert forbidden not in keeper, forbidden
