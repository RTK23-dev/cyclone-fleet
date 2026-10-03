param(
    [string]$TargetTriple = "x86_64-pc-windows-msvc"
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Lock = Get-Content (Join-Path $Repo 'packaging\pc-companion\sidecar-build.lock.json') -Raw | ConvertFrom-Json
$BuildVenv = Join-Path $Repo 'build\pc-sidecar-venv'
$BuildPython = Join-Path $BuildVenv 'Scripts\python.exe'
if (-not (Test-Path $BuildPython)) {
    python -m venv $BuildVenv
}

& $BuildPython (Join-Path $Repo 'scripts\pc-companion\prepare-scrcpy-server.py') --repo $Repo
& $BuildPython (Join-Path $Repo 'scripts\pc-companion\prepare-android-platform-tools.py') --repo $Repo
& $BuildPython -m pip install --disable-pip-version-check "pyinstaller==$($Lock.pyinstaller)"
& $BuildPython -m pip install --disable-pip-version-check (Join-Path $Repo 'apps\device-gateway') (Join-Path $Repo 'tools\cyclone-agent-mcp') (Join-Path $Repo 'tools\codex-phone-mcp')
& $BuildPython (Join-Path $Repo 'scripts\pc-companion\prepare-live-bridge.py')
# Cyclone Glass (apps/glass) ships inside CyclonePCRuntime so One's local gateway serves it at /glass/.
$Glass = Join-Path $Repo 'apps\glass'
Push-Location $Glass
try {
    npm ci --no-audit --no-fund
    npm run build
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $Glass 'dist\index.html'))) { throw "Cyclone Glass bundle was not built: $Glass\dist" }
$Dist = Join-Path $Repo 'dist\pc-companion'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
& $BuildPython -m PyInstaller --clean --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\agent') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CycloneAgentMCP.spec')
& $BuildPython -m PyInstaller --clean --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\runtime') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CyclonePCRuntime.spec')
& $BuildPython -m PyInstaller --clean --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\live-phone') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CycloneLivePhone.spec')

$TauriBinaries = Join-Path $Repo 'apps\pc-companion\src-tauri\binaries'
New-Item -ItemType Directory -Force -Path $TauriBinaries | Out-Null
foreach ($Name in @('CyclonePCRuntime', 'CycloneAgentMCP', 'CycloneLivePhone')) {
    $Source = Join-Path $Dist "$Name.exe"
    if (-not (Test-Path $Source)) { throw "Missing sidecar output: $Source" }
    Copy-Item $Source (Join-Path $TauriBinaries "$Name-$TargetTriple.exe") -Force
}

$BundledAdb = Join-Path $Repo 'apps\pc-companion\src-tauri\resources\android-platform-tools\adb.exe'
if (-not (Test-Path $BundledAdb)) { throw "Bundled Android Platform-Tools were not staged: $BundledAdb" }
$AdbVersion = (& $BundledAdb version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $AdbVersion -notmatch 'Version\s+37\.0\.1') {
    throw "Bundled adb runtime failed version verification. Output: $AdbVersion"
}

Write-Host "Cyclone sidecars and Android Platform-Tools 37.0.1 staged for Tauri target $TargetTriple"
