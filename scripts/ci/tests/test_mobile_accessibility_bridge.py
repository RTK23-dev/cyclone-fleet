from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class MobileAccessibilityBridgeGuards(unittest.TestCase):
    def _service(self, manifest_path: Path, class_name: str) -> ET.Element:
        root = ET.parse(manifest_path).getroot()
        application = root.find("application")
        self.assertIsNotNone(application, manifest_path)
        for service in application.findall("service"):
            if service.get(f"{ANDROID}name") == class_name:
                return service
        self.fail(f"Missing accessibility service {class_name} in {manifest_path}")

    def _assert_system_bindable(self, service: ET.Element) -> None:
        self.assertEqual("true", service.get(f"{ANDROID}exported"))
        self.assertEqual(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.get(f"{ANDROID}permission"),
        )

    def test_primary_cyclone_accessibility_is_system_bindable(self):
        service = self._service(
            ROOT / "apps/mobile/app/src/main/AndroidManifest.xml",
            ".CycloneAccessibilityService",
        )
        self._assert_system_bindable(service)

    def test_primary_accessibility_does_not_subscribe_to_type_all_mask(self):
        config = ROOT / "apps/mobile/app/src/main/res/xml/accessibility_service_config.xml"
        root = ET.parse(config).getroot()
        events = root.get(f"{ANDROID}accessibilityEventTypes", "")
        self.assertNotIn("typeAllMask", events, config)
        self.assertIn("typeWindowStateChanged", events, config)

    def test_embedded_module_is_diagnostics_only_and_offline_buildable(self):
        gradle = (ROOT / "apps/mobile/mobilerun-embedded/build.gradle.kts").read_text(encoding="utf-8")
        manifest_path = ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"
        manifest = ET.parse(manifest_path).getroot()
        diagnostics = ROOT / "apps/mobile/mobilerun-embedded/src/main/java/com/mobilerun/portal/diagnostics/CycloneProcessDiagnostics.kt"

        for retired in (
            "upstreamMobilerun",
            "materializeMobilerunUpstream",
            "prepareMobilerunSources",
            "MOBILERUN_UPSTREAM_URL",
            "webrtc-sdk",
            "Java-WebSocket",
        ):
            self.assertNotIn(retired, gradle)
        self.assertIsNone(manifest.find("application"))
        self.assertEqual([], manifest.findall("uses-permission"))
        self.assertTrue(diagnostics.is_file())

    def test_primary_accessibility_callback_boundary_cannot_run_heavy_init_directly(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt").read_text(encoding="utf-8")
        self.assertIn("super.onServiceConnected()", source)
        self.assertIn("runtimeInitExecutor.execute", source)
        self.assertIn("automationRuntimeReady", source)
        self.assertIn("appLearnerRuntimeReady", source)
        self.assertIn("primary.accessibility.event.boundary", source)
        self.assertIn("CycloneProcessDiagnostics.recordNonFatal", source)
        connected_body = source.split("override fun onServiceConnected()", 1)[1].split("override fun onAccessibilityEvent", 1)[0]
        before_executor = connected_body.split("runtimeInitExecutor.execute", 1)[0]
        self.assertNotIn("AutomationRuntime.initialize(this)", before_executor)
        self.assertNotIn("AppLearnerRuntime.initialize(this)", before_executor)

    def test_process_crash_journal_captures_uncaught_and_historical_exit_reason(self):
        source = (ROOT / "apps/mobile/mobilerun-embedded/src/main/java/com/mobilerun/portal/diagnostics/CycloneProcessDiagnostics.kt").read_text(encoding="utf-8")
        self.assertIn("setDefaultUncaughtExceptionHandler", source)
        self.assertIn("getHistoricalProcessExitReasons", source)
        self.assertIn("setProcessStateSummary", source)
        self.assertIn("process-crash-journal.log", source)
        self.assertIn("REASON_CRASH_NATIVE", source)
        self.assertIn("REASON_ANR", source)

    def test_retired_core_transport_cannot_start_or_forward_data(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/BridgeClient.kt").read_text(encoding="utf-8")
        for retired in ("okhttp", "newWebSocket", "coreWsUrl", "coreToken", "PhoneToolExecutor", "SetupReminderState"):
            self.assertNotIn(retired, source)
        for filename in ("MainActivity.kt", "CycloneAccessibilityService.kt"):
            entry = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile" / filename).read_text()
            self.assertNotIn("BridgeClient.start", entry)

    def test_desktop_pairing_does_not_auto_start_fleet_video_and_has_crash_capture(self):
        fleet = (ROOT / "apps/pc-companion/src/pages/fleetPage.ts").read_text(encoding="utf-8")
        live = (ROOT / "apps/pc-companion/src/ui/livePhoneView.ts").read_text(encoding="utf-8")
        pairing = (ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/pairing.py").read_text(encoding="utf-8")
        diagnostics = (ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/diagnostics.py").read_text(encoding="utf-8")
        api = (ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/api.py").read_text(encoding="utf-8")
        adb = (ROOT / "apps/device-gateway/cyclone_device_gateway/adb/client.py").read_text(encoding="utf-8")
        self.assertIn("autoStart: false", fleet)
        self.assertIn("options.autoStart === false", live)
        self.assertIn("_verify_post_pair_health", pairing)
        self.assertIn("_capture_pairing_diagnostics", pairing)
        self.assertIn("pair.complete.pc_submit", pairing)
        self.assertIn("FleetDiagnosticSupervisor", diagnostics)
        self.assertIn("ADB_READ_ONLY_PROCESS_MONITOR", diagnostics)
        self.assertIn("self.live_diagnostics.start()", api)
        self.assertIn("collect_cyclone_crash_diagnostics", adb)
        self.assertIn('"dumpsys", "activity", "exit-info"', adb)
        self.assertIn('"logcat", "-b", "crash"', adb)

    def test_pair_begin_is_protocol_only_and_gateway_boundary_contains_nonfatal_throwables(self):
        pairing = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayDesktopRuntime.kt").read_text(encoding="utf-8")
        runtime = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayRuntime.kt").read_text(encoding="utf-8")
        begin = pairing.split("fun begin(context: Context, args: JSONObject)", 1)[1].split("fun complete", 1)[0]
        self.assertNotIn("android.widget.Toast", pairing)
        self.assertNotIn("Toast.makeText", begin)
        self.assertNotIn("android.os.Handler", pairing)
        self.assertNotIn("Handler(", begin)
        self.assertIn("protocol-only", begin)
        self.assertIn("catch (error: Throwable)", runtime)
        self.assertIn("gateway.dispatch.boundary", runtime)
        self.assertIn("VirtualMachineError", runtime)

    def test_gateway_socket_worker_avoids_android_isclosed_crash_and_contains_transport_failures(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewaySocketServer.kt").read_text(encoding="utf-8")
        self.assertNotIn("socket.isClosed", source)
        self.assertIn("while (running.get())", source)
        self.assertIn("catch (error: Throwable)", source)
        self.assertIn("rethrowFatal(error)", source)
        self.assertIn("VirtualMachineError", source)

    def test_windows_release_hides_all_console_windows_without_breaking_agent_stdio(self):
        main = (ROOT / "apps/pc-companion/src-tauri/src/main.rs").read_text(encoding="utf-8")
        runtime = (ROOT / "apps/pc-companion/src-tauri/src/lib.rs").read_text(encoding="utf-8")
        pc_runtime = (ROOT / "packaging/pc-companion/pyinstaller/CyclonePCRuntime.spec").read_text(encoding="utf-8")
        agent = (ROOT / "packaging/pc-companion/pyinstaller/CycloneAgentMCP.spec").read_text(encoding="utf-8")
        self.assertIn('windows_subsystem = "windows"', main)
        # The runtime is also the `cyclone` terminal command: console subsystem, owned console hidden early.
        self.assertIn("console=True", pc_runtime)
        self.assertIn('hide_console="hide-early"', pc_runtime)
        self.assertIn("console=True", agent)
        self.assertIn('hide_console="hide-early"', agent)
        self.assertIn("monitor-pc-console\\.ps1", runtime)
        self.assertIn("CREATE_NO_WINDOW", runtime)
        self.assertIn('Command::new("powershell.exe")', runtime)
        self.assertNotIn('Command::new("cmd.exe")', runtime)

    def test_packaged_agent_mcp_serves_canonical_locate_and_skill_surface(self):
        entrypoint = (ROOT / "scripts/pc-companion/entrypoints/agent_mcp.py").read_text(encoding="utf-8")
        build = (ROOT / "scripts/pc-companion/build-sidecars.ps1").read_text(encoding="utf-8")
        spec = (ROOT / "packaging/pc-companion/pyinstaller/CycloneAgentMCP.spec").read_text(encoding="utf-8")
        phone_server = (ROOT / "tools/codex-phone-mcp/cyclone_phone_mcp/mcp_server.py").read_text(encoding="utf-8")
        self.assertIn("from cyclone_phone_mcp.mcp_server import McpServer", entrypoint)
        self.assertIn('sys.argv[1] == "serve"', entrypoint)
        self.assertIn("McpServer().serve_stdio()", entrypoint)
        self.assertIn("tools\\codex-phone-mcp", build)
        self.assertIn('"codex-phone-mcp"', spec)
        for tool in ("phone_status", "phone_locate", "phone_act", "phone_skill_save", "phone_skill_run"):
            self.assertIn(f'"{tool}"', phone_server)


if __name__ == "__main__":
    unittest.main()
