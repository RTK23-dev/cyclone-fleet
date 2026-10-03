<#
Cyclone for Windows (plan 31, web-only): installs or updates Cyclone for this user, no admin needed.

  Install:  irm https://github.com/premiumcentraal-boop/Cyclone/releases/download/<tag>/install.ps1 | iex
  Update:   run by `cyclone` itself with -Zip <verified package> -Relaunch

What it does, in order:
  1. Finds the newest release with a Cyclone-PC zip and checks the zip's SHA-256 against that release's manifest.
  2. Stops Cyclone's own processes (never another program's adb).
  3. Keeps your data: copies the old Cyclone One window's runtime (pairing) next to the new one, never overwriting.
  4. Removes the retired Cyclone One desktop window if it is installed (its uninstaller, silently; data stays).
  5. Unpacks into %LOCALAPPDATA%\Cyclone One (the same folder as before, so AI connections keep working).
  6. Adds the `cyclone` command to your user PATH, then starts Cyclone.
#>
[CmdletBinding()]
param(
    [string]$Zip = "",
    [switch]$Relaunch,
    [switch]$NoStart,
    # The setup passes its log file; everything shown here is added to it.
    [string]$Log = ""
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell started from PowerShell 7 (a pwsh terminal, the setup under CI) inherits PowerShell 7's module
# path and then cannot load its own Get-FileHash, Expand-Archive and friends. Use its own modules first.
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $env:PSModulePath = (@("$PSHOME\Modules", "$env:ProgramFiles\WindowsPowerShell\Modules",
        [Environment]::GetEnvironmentVariable('PSModulePath', 'Machine'),
        (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'WindowsPowerShell\Modules')) | Where-Object { $_ }) -join ';'
}
$ProgressPreference = 'SilentlyContinue'
$Repo = 'premiumcentraal-boop/Cyclone'
$Root = Join-Path $env:LOCALAPPDATA 'Cyclone One'
$LegacyRuntime = Join-Path $env:LOCALAPPDATA 'com.cyclone.pccompanion\runtime'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ("cyclone-install-" + [guid]::NewGuid().ToString('N'))

function Say([string]$Text) { Write-Host "  $Text" }
# Errors throw instead of `exit`: under `irm | iex`, exit would close the owner's PowerShell window.
function Fail([string]$Text) { throw "CYCLONE: $Text" }

function Get-VersionKey([string]$Tag) {
    if ($Tag -notmatch '^v?(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+)(?:\.dev(\d+))?)?$') { return $null }
    $alpha = if ($Matches[4]) { [int]$Matches[4] } else { 1000000000 }
    $dev = if ($Matches[5]) { [int]$Matches[5] } else { 0 }
    return '{0:D6}.{1:D6}.{2:D6}.{3:D10}.{4:D6}' -f [int]$Matches[1], [int]$Matches[2], [int]$Matches[3], $alpha, $dev
}

function Get-Package {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $headers = @{ 'User-Agent' = 'cyclone-install'; 'Accept' = 'application/vnd.github+json' }
    Say "Looking for the newest Cyclone..."
    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases?per_page=30" -Headers $headers
    $best = $null; $bestKey = $null
    foreach ($release in $releases) {
        if ($release.draft) { continue }
        $key = Get-VersionKey $release.tag_name
        if (-not $key) { continue }
        $package = $release.assets | Where-Object { $_.name -match '^Cyclone-PC-[0-9A-Za-z.\-]+\.zip$' } | Select-Object -First 1
        $manifest = $release.assets | Where-Object { $_.name -eq 'release-manifest.json' } | Select-Object -First 1
        if (-not $package -or -not $manifest) { continue }
        if (-not $package.browser_download_url.StartsWith('https://github.com/')) { continue }
        if ($null -eq $bestKey -or $key -gt $bestKey) { $best = @{ Tag = $release.tag_name; Package = $package; Manifest = $manifest }; $bestKey = $key }
    }
    if (-not $best) { Fail "no release has a Cyclone-PC package yet." }
    Say "Cyclone $($best.Tag.TrimStart('v'))"
    $manifest = Invoke-RestMethod -Uri $best.Manifest.browser_download_url -Headers $headers
    $expected = $manifest.sha256.($best.Package.name)
    if (-not $expected -or $expected -notmatch '^[0-9a-fA-F]{64}$') { Fail "$($best.Package.name) is not listed in the release manifest." }
    New-Item -ItemType Directory -Force -Path $Temp | Out-Null
    $path = Join-Path $Temp $best.Package.name
    Say "Downloading $($best.Package.name)..."
    Invoke-WebRequest -Uri $best.Package.browser_download_url -OutFile $path -UseBasicParsing -Headers @{ 'User-Agent' = 'cyclone-install' }
    Test-Checksum $path $expected
    return $path
}

function Test-Checksum([string]$Path, [string]$Expected) {
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        Remove-Item -Force $Path -ErrorAction SilentlyContinue
        Fail "the download does not match the release checksum; it was deleted."
    }
    Say "Verified SHA-256 $($actual.Substring(0, 12))..."
}

function Stop-Cyclone {
    foreach ($name in @('Cyclone One', 'CyclonePCRuntime', 'CycloneAgentMCP', 'CycloneLivePhone')) {
        Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    }
    $stopTunnel = Join-Path $Root 'mcp-tunnel\scripts\stop-tunnel.ps1'
    if (Test-Path $stopTunnel) { try { & powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $stopTunnel | Out-Null } catch { } }
    $adb = Join-Path $Root 'android-platform-tools\adb.exe'
    if (Test-Path $adb) { try { & $adb kill-server 2>$null | Out-Null } catch { } }
    # Only adb/fastboot that live in Cyclone's own folder; Android Studio's adb is never touched.
    Get-Process adb, fastboot -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Root\android-platform-tools\*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
}

function Copy-Missing([string]$From, [string]$To) {
    if (-not (Test-Path $From)) { return 0 }
    New-Item -ItemType Directory -Force -Path $To | Out-Null
    $count = 0
    foreach ($item in Get-ChildItem -Force -LiteralPath $From) {
        if ($item.Name -in @('diagnostics', 'logs')) { continue }
        $target = Join-Path $To $item.Name
        if (Test-Path -LiteralPath $target) { continue }
        Copy-Item -LiteralPath $item.FullName -Destination $target -Recurse -Force
        $count++
    }
    return $count
}

function Remove-DesktopWindow {
    $uninstaller = Join-Path $Root 'uninstall.exe'
    if (-not (Test-Path $uninstaller)) { return }
    Say "Removing the old Cyclone One window (your data stays)..."
    # Keep the owner's secrets safe across the uninstaller, whatever it removes.
    $backup = Join-Path $Temp 'keep'
    foreach ($relative in @('mcp-tunnel\config', 'chatgpt-attach', 'runtime')) {
        $source = Join-Path $Root $relative
        if (Test-Path $source) { New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $backup $relative)) | Out-Null; Copy-Item -LiteralPath $source -Destination (Join-Path $backup $relative) -Recurse -Force }
    }
    # _?= runs the uninstaller in place and waits for it; /S is silent. It keeps app data unless asked otherwise.
    $options = @{ FilePath = $uninstaller; ArgumentList = @('/S', "_?=$Root"); Wait = $true }
    if ($PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows) { $options.WindowStyle = 'Hidden' }
    Start-Process @options
    Remove-Item -Force $uninstaller -ErrorAction SilentlyContinue
    foreach ($relative in @('mcp-tunnel\config', 'chatgpt-attach', 'runtime')) {
        $null = Copy-Missing (Join-Path $backup $relative) (Join-Path $Root $relative)
    }
}

function Install-Cyclone {
    try {
        Write-Host ""
        Write-Host "  Cyclone for Windows" -ForegroundColor Cyan
        Write-Host ""
        if ($Zip) {
            if (-not (Test-Path $Zip)) { Fail "the update package is missing: $Zip" }
            $sidecar = "$Zip.sha256"
            if (Test-Path $sidecar) { Test-Checksum $Zip (Get-Content -Raw $sidecar).Trim() }
            $package = $Zip
        } else {
            $package = Get-Package
        }

        Stop-Cyclone
        $kept = Copy-Missing $LegacyRuntime (Join-Path $Root 'runtime')
        if ($kept) { Say "Kept your pairing from Cyclone One." }
        Remove-DesktopWindow

        Say "Installing into $Root..."
        $unpacked = Join-Path $Temp 'package'
        New-Item -ItemType Directory -Force -Path $unpacked | Out-Null
        Expand-Archive -LiteralPath $package -DestinationPath $unpacked -Force
        if (-not (Test-Path (Join-Path $unpacked 'CyclonePCRuntime.exe'))) { Fail "the package has no CyclonePCRuntime.exe." }
        New-Item -ItemType Directory -Force -Path $Root | Out-Null
        # Files from the package replace old ones; the owner's own files (runtime, keys, tunnel config) are not in it.
        Copy-Item -Path (Join-Path $unpacked '*') -Destination $Root -Recurse -Force
        if ($Zip) { Remove-Item -Force $Zip, "$Zip.sha256" -ErrorAction SilentlyContinue }

        $runtime = Join-Path $Root 'CyclonePCRuntime.exe'
        & $runtime install-cli | ForEach-Object { Say $_ }
        if ($LASTEXITCODE -ne 0) { Fail "the cyclone command could not be set up (install-cli exit $LASTEXITCODE)." }
        $env:Path = "$env:Path;$(Join-Path $Root 'bin')"

        Write-Host ""
        Write-Host "  +- Cyclone is installed -------------------------------+" -ForegroundColor Cyan
        Write-Host "  | Start   open a terminal and type  cyclone             |" -ForegroundColor Cyan
        Write-Host "  | Stop    close that window, or press Ctrl+C            |" -ForegroundColor Cyan
        Write-Host "  | Update  type  cyclone update                          |" -ForegroundColor Cyan
        Write-Host "  +-------------------------------------------------------+" -ForegroundColor Cyan
        Write-Host ""
        return $true
    } catch {
        $message = "$($_.Exception.Message)" -replace '^CYCLONE: ', ''
        Write-Host ""
        Write-Host "  Cyclone was not installed: $message" -ForegroundColor Red
        return $false
    } finally {
        Remove-Item -Recurse -Force $Temp -ErrorAction SilentlyContinue
    }
}

if ($Log) { try { Start-Transcript -Path $Log -Append | Out-Null } catch { } }
$ok = Install-Cyclone
if ($Log) { try { Stop-Transcript | Out-Null } catch { } }
if ($ok -and ($Relaunch -or -not $NoStart)) {
    Say "Starting Cyclone..."
    & (Join-Path $Root 'CyclonePCRuntime.exe') terminal --no-update
}
# Run as a file (cyclone's update), report the result; under `irm | iex`, just return to the owner's prompt.
if ($PSCommandPath) { exit ([int](-not $ok)) }
