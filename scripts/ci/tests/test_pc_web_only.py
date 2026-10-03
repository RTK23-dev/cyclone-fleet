"""Web-only PC guard (plan 31, alpha.47).

Cyclone for Windows is the runtime + Glass + the `cyclone` command, installed per user from one verified zip. These
rules keep it that way: no desktop window is built, every package and update is checked against the release
manifest, the PC routes need the bearer, and the installer never closes the owner's shell or touches other programs.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
PUBLISH = ROOT / ".github/workflows/v5-publish.yml"
INSTALL = ROOT / "packaging/pc/install.ps1"
BUILD = ROOT / "scripts/pc/build-pc-package.ps1"
PC_API = ROOT / "apps/device-gateway/cyclone_device_gateway/pc/api.py"
SHIM = ROOT / "apps/device-gateway/cyclone_device_gateway/terminal/install.py"
UPDATER = ROOT / "apps/device-gateway/cyclone_device_gateway/terminal/updater.py"
SETUP = ROOT / "packaging/pc/cyclone-setup.nsi"


class WebOnlyPcGuards(unittest.TestCase):
    def test_the_publisher_builds_the_package_not_a_desktop_window(self):
        text = PUBLISH.read_text(encoding="utf-8")
        self.assertNotIn("tauri build", text)
        self.assertIn("scripts/pc/build-pc-package.ps1", text)
        self.assertIn("needs: pc-package", text)
        # The manifest lists the package and the installer, which `cyclone` and install.ps1 check against.
        self.assertRegex(text, r"for p in \(apk, glass, pc, installer, setup\)")
        self.assertIn(r"gateway\.env$|fleet\.dpapi$", text)

    def test_the_package_build_proves_install_and_serving_on_windows(self):
        text = BUILD.read_text(encoding="utf-8")
        for needed in ("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP = '1'", "install.ps1') -Zip", "version 2>&1", "/glass/", "/v1/pc/welcome", "answered without the bearer"):
            self.assertIn(needed, text)
        self.assertIn("'gateway.env', 'fleet.dpapi'", text)
        self.assertNotIn("CycloneLivePhone.spec", text)

    def test_the_installer_verifies_and_never_exits_the_owners_shell(self):
        text = INSTALL.read_text(encoding="utf-8")
        # Windows PowerShell 5.1 reads a BOM-less script as ANSI: any non-ASCII character corrupts it.
        self.assertTrue(text.isascii())
        self.assertTrue(BUILD.read_text(encoding="utf-8").isascii())
        self.assertIn("Get-FileHash -Algorithm SHA256", text)
        self.assertIn("release-manifest.json", text)
        exits = [line.strip() for line in text.splitlines() if re.search(r"(^\s*|[{;]\s*)exit\b", line) and not line.strip().startswith("#")]
        self.assertEqual(exits, ["if ($PSCommandPath) { exit ([int](-not $ok)) }"])
        # Only Cyclone's own adb/fastboot are stopped.
        self.assertIn('Where-Object { $_.Path -like "$Root\\android-platform-tools\\*" }', text)
        self.assertNotRegex(text, r"(?i)HKLM:|RunAs|Set-ExecutionPolicy")

    def test_the_setup_wraps_install_ps1_per_user_and_is_proven_on_windows(self):
        text = SETUP.read_text(encoding="utf-8")
        self.assertTrue(text.isascii())
        self.assertIn("RequestExecutionLevel user", text)
        # A silent install (/S, the CI test and scripted installs) shows no page, so $PLUGINSDIR must be created.
        self.assertLess(text.index("InitPluginsDir"), text.index('SetOutPath "$PLUGINSDIR"'))
        self.assertNotRegex(text, r"(?i)HKLM|RequestExecutionLevel admin")
        # The same verified install as the one-line install; the setup adds shortcuts and Apps & features.
        self.assertIn('install.ps1" -Zip "$PLUGINSDIR\\Cyclone-PC.zip" -NoStart', text)
        self.assertIn('InstallDir "$LOCALAPPDATA\\Cyclone One"', text)
        # install.ps1 runs any uninstall.exe as the retired window's uninstaller: ours must never take that name.
        self.assertIn('!define UNINSTALLER "Uninstall Cyclone.exe"', text)
        self.assertNotRegex(text, r'WriteUninstaller "\$INSTDIR\\uninstall\.exe"')
        # Uninstalling keeps the owner's data folders.
        for kept in ("runtime", "mcp-tunnel", "chatgpt-attach"):
            self.assertNotIn(f'RMDir /r "$INSTDIR\\{kept}"', text)
        build = BUILD.read_text(encoding="utf-8")
        for needed in ("cyclone-setup.nsi", '"/S /D=$InstallDir"', "Uninstall Cyclone.exe", '"/S _?=$InstallDir"',
                       "owner-data.txt", "Apps & features", "Cyclone-PC.zip.sha256') -Value $Hash"):
            self.assertIn(needed, build)
        publish = PUBLISH.read_text(encoding="utf-8")
        self.assertIn('sha256sum --check "Cyclone-Setup-$PRODUCT.exe.sha256"', publish)
        # Started from a PowerShell 7 terminal, Windows PowerShell inherits PowerShell 7's module path and cannot load
        # Get-FileHash (alpha.48 release run #10): the setup clears it for install.ps1, and install.ps1 repairs it.
        self.assertLess(text.index('SetEnvironmentVariable(t "PSModulePath", p 0)'), text.index("nsExec::ExecToLog"))
        install = INSTALL.read_text(encoding="utf-8")
        self.assertLess(install.index("$env:PSModulePath = "), install.index("Get-FileHash -Algorithm"))
        # A failed setup says why: its log, with install.ps1's transcript, is printed by the package build.
        self.assertIn('-Log "${SETUP_LOG}"', text)
        self.assertIn("Start-Transcript -Path $Log", install)
        self.assertIn("Get-Content $SetupLog", build)

    def test_every_pc_route_needs_the_bearer(self):
        text = PC_API.read_text(encoding="utf-8")
        routes = re.findall(r"@router\.(get|post|put|delete)\((.*)\)\s*$", text, re.M)
        self.assertGreaterEqual(len(routes), 15)
        for method, args in routes:
            self.assertIn("dependencies=[Depends(auth)]", args, f"{method} {args}")

    def test_updates_are_verified_and_the_shim_ends_before_replacing_itself(self):
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn("expected_sha256(manifest, release.package_name)", updater)
        self.assertIn("if actual != expected", updater)
        self.assertIn("(goto) 2>nul & powershell.exe", SHIM.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
