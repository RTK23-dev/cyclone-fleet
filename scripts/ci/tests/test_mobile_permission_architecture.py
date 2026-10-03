from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Auto-granted infrastructure permissions that keep the app alive and connected but do not
# expose user data or device capabilities; they intentionally have no setup row.
INFRASTRUCTURE_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
    "android.permission.KILL_BACKGROUND_PROCESSES",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    # Normal foreground-service permission; the task service declares its special-use subtype.
    # It has no runtime grant dialog and does not grant access to another app's data.
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    # Normal Live Update eligibility permission, without a runtime grant dialog. Android/user
    # notification settings still control promotion; POST_NOTIFICATIONS retains its setup row.
    "android.permission.POST_PROMOTED_NOTIFICATIONS",
    # Normal install-time permission for AlarmClock intents (phone.set_alarm / phone.set_timer). No runtime dialog;
    # it only lets Cyclone ask the clock app to create an alarm/timer, which the clock app shows to the owner.
    "com.android.alarm.permission.SET_ALARM",
    # Normal foreground-service permission for Drive (plan 32). No runtime dialog and no data of its own: the
    # microphone itself is RECORD_AUDIO, which keeps its setup row. The service runs only while Cyclone listens
    # after the owner taps the AI button.
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
}

# Every permission in this set must appear in the Cyclone setup UI as a row that maps to the
# capability it backs. Add new permissions here only together with a real setup row.
SETUP_ROW_PERMISSIONS = {
    "moe.shizuku.manager.permission.API_V23",  # Existing Background tasks → Authorize Shizuku row
    "android.permission.REQUEST_INSTALL_PACKAGES",  # User-requested, pinned helper installer in Background setup

    "android.permission.POST_NOTIFICATIONS",  # Result notifications
    "android.permission.READ_CALENDAR",  # Calendar (read)
    # Plan 29 (direct first): the Calendar row also adds the events the owner asks for, and the Contacts row looks up a
    # number or address for a mission. Both are asked for by Android's own dialog, and both rows show and revoke them.
    "android.permission.WRITE_CALENDAR",
    "android.permission.READ_CONTACTS",
    "android.permission.RECORD_AUDIO",  # Voice requests
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",  # Unrestricted battery
    "android.permission.SYSTEM_ALERT_WINDOW",  # Display over apps
    "android.permission.SCHEDULE_EXACT_ALARM",  # Precise timing
    # Plan 49: Settings → Permissions → Codes. Read texts only for one code step, in memory; never receive or send.
    "android.permission.READ_SMS",
    "android.permission.READ_PHONE_NUMBERS",
}

# READ_CONTACTS left this list in alpha.45 by the owner's decision (plan 29): it now has a setup row and is asked for
# only when a mission needs it. Writing contacts stays out.
FORBIDDEN_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.WRITE_CONTACTS",
    "android.permission.RECEIVE_SMS",
}


def manifest_root(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def declared_permissions(path: Path) -> set[str]:
    return {
        node.get(f"{ANDROID}name", "")
        for node in manifest_root(path).findall("uses-permission")
    }


def application_node(path: Path) -> ET.Element | None:
    return manifest_root(path).find("application")


def service_nodes(path: Path) -> dict[str, ET.Element]:
    application = application_node(path)
    if application is None:
        return {}
    return {
        service.get(f"{ANDROID}name", ""): service
        for service in application.findall("service")
    }


def receiver_names(path: Path) -> set[str]:
    application = application_node(path)
    if application is None:
        return set()
    return {
        receiver.get(f"{ANDROID}name", "")
        for receiver in application.findall("receiver")
    }


def service_actions(service: ET.Element) -> set[str]:
    return {
        action.get(f"{ANDROID}name", "")
        for intent_filter in service.findall("intent-filter")
        for action in intent_filter.findall("action")
    }


class MobilePermissionArchitectureGuards(unittest.TestCase):
    def setUp(self):
        self.app_manifest = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
        self.diagnostics_manifest = ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"

    def test_core_setup_permissions_are_declared_by_canonical_app(self):
        app = declared_permissions(self.app_manifest)
        for permission in SETUP_ROW_PERMISSIONS:
            self.assertIn(permission, app)
        self.assertEqual(set(), declared_permissions(self.diagnostics_manifest))

    def test_final_apk_exposes_only_native_cyclone_control_endpoints(self):
        app = service_nodes(self.app_manifest)
        embedded = service_nodes(self.diagnostics_manifest)

        canonical_accessibility = app[".CycloneAccessibilityService"]
        canonical_notifications = app[".CycloneNotificationListener"]
        self.assertIn(
            "android.accessibilityservice.AccessibilityService",
            service_actions(canonical_accessibility),
        )
        self.assertIn(
            "android.service.notification.NotificationListenerService",
            service_actions(canonical_notifications),
        )
        self.assertEqual(set(), set(embedded))
        self.assertFalse(any(name.startswith("com.mobilerun.portal.") for name in app))

    def test_diagnostics_library_cannot_expand_final_manifest(self):
        root = manifest_root(self.diagnostics_manifest)
        self.assertEqual([], root.findall("uses-permission"))
        self.assertIsNone(root.find("application"))
        self.assertEqual([], root.findall("queries"))

    def test_main_shell_respects_android_status_bar_inset(self):
        main_activity = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("statusBarsPadding()", main_activity)
        self.assertIn("Box(Modifier.fillMaxSize().statusBarsPadding())", main_activity)

    def test_legacy_enhanced_control_row_is_not_rendered(self):
        components = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Components.kt"
        ).read_text(encoding="utf-8")
        self.assertIn(
            'private const val LEGACY_ENHANCED_CONTROL_ROW = "Enhanced control engine"',
            components,
        )
        self.assertIn("if (title == LEGACY_ENHANCED_CONTROL_ROW) return", components)

    def test_enhanced_control_compatibility_uses_primary_grant(self):
        permission_setup = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/permissions/CyclonePermissionSetup.kt"
        ).read_text(encoding="utf-8")
        self.assertNotIn("MobilerunAccessibilityService", permission_setup)
        self.assertIn(
            "fun enhancedControlEnabled(context: Context): Boolean = primaryControlEnabled(context)",
            permission_setup,
        )

    def test_no_manifest_can_silently_expand_into_sensitive_domains(self):
        for manifest in (self.app_manifest, self.diagnostics_manifest):
            permissions = declared_permissions(manifest)
            self.assertFalse(
                FORBIDDEN_PERMISSIONS & permissions,
                f"{manifest.name}: {FORBIDDEN_PERMISSIONS & permissions}",
            )

    def test_helper_install_is_explicit_pinned_and_narrowly_shared(self):
        source = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background"
        installer = (source / "BackgroundSetupActivity.kt").read_text(encoding="utf-8")
        download = (source / "OfficialHelperDownload.kt").read_text(encoding="utf-8")
        policy = (source / "OfficialHelperInstallPolicy.kt").read_text(encoding="utf-8")
        self.assertIn("ACTION_MANAGE_UNKNOWN_APP_SOURCES", installer)
        self.assertIn("canRequestPackageInstalls()", installer)
        self.assertIn("installer.launch", installer)
        self.assertIn("BackHandler", installer)
        self.assertIn("TextButton(onClick = onBack", installer)
        self.assertIn('"Back"', installer)
        self.assertNotIn("market://", installer)
        self.assertNotIn("play.google.com", installer)
        self.assertIn("github.com/RikkaApps/Shizuku/releases/download/", policy)
        self.assertIn("shizuku-v13.6.0", policy)
        self.assertIn("6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f", policy)
        self.assertRegex(policy, r"[0-9a-f]{64}")
        self.assertNotIn("import android.", policy)
        self.assertIn("digest(part) == SHA256", download)
        self.assertIn("info?.packageName == BackgroundSetup.SHIZUKU_PACKAGE", download)
        self.assertIn("OfficialHelperInstallPolicy.URL", download)
        self.assertIn("OfficialHelperInstallPolicy.SHA256", download)
        self.assertIn("OfficialHelperInstallPolicy.PACKAGE", download)
        self.assertIn("You can retry or go Back.", installer)
        screen_start = installer.find("fun InstallerScreen(")
        self.assertNotEqual(-1, screen_start)
        screen = installer[screen_start:]
        back_button = screen.find('TextButton(onClick = onBack')
        back_label = screen.find('Text("Back")')
        when_phase = screen.find("when (phase)")
        self.assertNotEqual(-1, back_button)
        self.assertNotEqual(-1, back_label)
        self.assertNotEqual(-1, when_phase)
        self.assertLess(
            back_button,
            when_phase,
            "visible Back must stay outside when (phase) so INSTALLING/BLOCKED/failure keep it",
        )
        self.assertLess(back_label, when_phase)
        self.assertNotIn("market://", screen)
        self.assertNotIn("play.google.com", screen)
        provider = next(p for p in application_node(self.app_manifest).findall("provider")
                        if p.get(f"{ANDROID}name") == "androidx.core.content.FileProvider")
        self.assertEqual("false", provider.get(f"{ANDROID}exported"))
        paths = ET.parse(ROOT / "apps/mobile/app/src/main/res/xml/setup_helper_paths.xml").getroot()
        self.assertEqual([("cache-path", "setup-helper/")], [(p.tag, p.get("path")) for p in paths])

    def test_helper_and_profile_setup_never_send_users_to_play_store(self):
        roots = [
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background",
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces",
        ]
        files = [path for root in roots for path in root.rglob("*.kt")]
        profile_surfaces = [
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/RootFeaturesCard.kt",
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/ProfileSetup429.kt",
        ]
        files.extend(profile_surfaces)
        self.assertTrue(files)
        for path in files:
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("market://", text, f"{path}: helper/profile setup must not open Play")
            self.assertNotIn("play.google.com", text, f"{path}: helper/profile setup must not open Play")
        profile = "\n".join(path.read_text(encoding="utf-8") for path in profile_surfaces)
        self.assertIn('Icon(Icons.Rounded.ArrowBack, "Back"', profile)
        self.assertNotIn("Shelter", profile)
        self.assertNotIn("Island", profile)

    def test_sms_trigger_receiver_is_not_exposed_anywhere(self):
        for manifest in (self.app_manifest, self.diagnostics_manifest):
            self.assertNotIn(
                "com.mobilerun.portal.triggers.TriggerSmsReceiver",
                receiver_names(manifest),
                manifest.name,
            )

    def test_shizuku_runtime_permission_has_existing_authorization_row(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/BackgroundSetupActivity.kt").read_text()
        self.assertIn("InstallStep.AUTHORIZE_SHIZUKU", source)
        self.assertIn("Shizuku.requestPermission", source)
        self.assertIn("moe.shizuku.manager.permission.API_V23", declared_permissions(self.app_manifest))

    def test_every_declared_permission_is_infrastructure_or_has_a_setup_row(self):
        declared = declared_permissions(self.app_manifest) | declared_permissions(self.diagnostics_manifest)
        unexplained = declared - INFRASTRUCTURE_PERMISSIONS - SETUP_ROW_PERMISSIONS
        self.assertEqual(
            set(),
            unexplained,
            f"Declared permissions without a setup row or infrastructure exemption: {unexplained}",
        )


if __name__ == "__main__":
    unittest.main()
