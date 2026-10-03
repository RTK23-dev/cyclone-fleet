[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$LocalAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($LocalAppData)) {
    if ($DryRun) {
        $LocalAppData = Join-Path ([IO.Path]::GetTempPath()) "Cyclone-DryRun"
    } else {
        throw "LOCALAPPDATA is unavailable. Cyclone bridge setup must run on Windows."
    }
}
$RuntimeRoot = Join-Path $LocalAppData "Cyclone\bridge-v31"
$Venv = Join-Path $RuntimeRoot "venv"
$TokenFile = Join-Path $RuntimeRoot "pc-token.clixml"
$McpRunner = Join-Path $RuntimeRoot "run-cyclone-phone-mcp.ps1"
$CodexSnippet = Join-Path $RuntimeRoot "codex-mcp.generated.toml"

function Write-Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Invoke-OrShow([string]$Exe, [string[]]$Args) {
    if ($DryRun) {
        Write-Host ("DRY RUN: {0} {1}" -f $Exe, ($Args -join " "))
        return
    }
    & $Exe @Args
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with code $LASTEXITCODE" }
}
function New-StrongToken {
    $bytes = New-Object byte[] 32
    # RandomNumberGenerator.Fill is .NET Core only; Windows PowerShell 5.1 still ships the
    # .NET Framework API where GetBytes is the portable call.
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

Write-Step "Checking Python 3.11+"
$Python = Get-Command py -ErrorAction SilentlyContinue
if ($Python) {
    $PythonExe = "py"
    $PythonPrefix = @("-3")
} else {
    $Python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $Python) { throw "Python 3.11+ is required. Install it from python.org, then rerun this script." }
    $PythonExe = $Python.Source
    $PythonPrefix = @()
}
$VersionText = & $PythonExe @PythonPrefix -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
$Parts = $VersionText.Trim().Split('.')
if ([int]$Parts[0] -lt 3 -or ([int]$Parts[0] -eq 3 -and [int]$Parts[1] -lt 11)) {
    throw "Python 3.11+ is required; detected $VersionText"
}
Write-Host "Python $VersionText READY"

Write-Step "Checking Android Platform Tools"
$Adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $Adb) {
    $Winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($Winget -and -not $DryRun) {
        Write-Host "ADB is missing. Attempting Android Platform Tools install with winget..."
        & winget install --id Google.PlatformTools -e --accept-source-agreements --accept-package-agreements
        $Adb = Get-Command adb -ErrorAction SilentlyContinue
    }
    if (-not $Adb) {
        Write-Host "ADB MISSING. Install official Android Platform Tools:" -ForegroundColor Yellow
        Write-Host "https://developer.android.com/tools/releases/platform-tools"
        if (-not $DryRun) { throw "ADB is required before setup can continue." }
    }
} else {
    Write-Host "ADB READY: $($Adb.Source)"
}

Write-Step "Preparing user-local bridge environment"
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null }
if (-not (Test-Path $Venv)) {
    Invoke-OrShow $PythonExe ($PythonPrefix + @("-m", "venv", $Venv))
}
$VenvPython = Join-Path $Venv "Scripts\python.exe"
if ($DryRun) { $VenvPython = Join-Path $Venv "Scripts\python.exe" }
Invoke-OrShow $VenvPython @("-m", "pip", "install", "--upgrade", "pip")
Invoke-OrShow $VenvPython @("-m", "pip", "install", "-e", (Join-Path $RepoRoot "apps\device-gateway[test]"), "-e", (Join-Path $RepoRoot "tools\codex-phone-mcp"))

Write-Step "Creating separate PC Gateway credential"
if (-not $DryRun -and -not (Test-Path $TokenFile)) {
    $Token = New-StrongToken
    ConvertTo-SecureString $Token -AsPlainText -Force | Export-Clixml -Path $TokenFile
    Remove-Variable Token
    Write-Host "PC Gateway token stored with Windows CurrentUser encryption."
} elseif ($DryRun) {
    Write-Host "DRY RUN: would generate a 256-bit PC token and DPAPI-protect it at $TokenFile"
} else {
    Write-Host "Existing encrypted PC Gateway token retained."
}

Write-Step "Generating token-free Codex MCP launcher"
$RunnerContent = @'
$ErrorActionPreference = "Stop"
$RuntimeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Secure = Import-Clixml (Join-Path $RuntimeRoot "pc-token.clixml")
$Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
try { $env:CYCLONE_DEVICE_GATEWAY_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Ptr) }
$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
& (Join-Path $RuntimeRoot "venv\Scripts\cyclone-phone-mcp.exe")
exit $LASTEXITCODE
'@
$EscapedRunner = $McpRunner.Replace('\','\\')
$SnippetContent = @"
[mcp_servers.cyclone-phone]
command = "powershell.exe"
args = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "$EscapedRunner"]
"@
if ($DryRun) {
    Write-Host "DRY RUN: would write $McpRunner and $CodexSnippet (no tokens in either file)"
} else {
    Set-Content -Path $McpRunner -Value $RunnerContent -Encoding UTF8
    Set-Content -Path $CodexSnippet -Value $SnippetContent -Encoding UTF8
    Write-Host "Codex MCP snippet: $CodexSnippet"
}

Write-Step "Verifying the generated launcher can decrypt the token"
if (-not $DryRun -and (Test-Path $McpRunner) -and (Test-Path $TokenFile)) {
    $Secure = Import-Clixml $TokenFile
    $Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        $Plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Ptr)
        if ([string]::IsNullOrWhiteSpace($Plain)) { throw "Decrypted PC Gateway token is empty." }
        Write-Host "Launcher token decrypt verified (token value not printed)."
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Ptr)
        Remove-Variable Plain -ErrorAction SilentlyContinue
    }
} elseif ($DryRun) {
    Write-Host "DRY RUN: would verify the launcher decrypts the stored token"
}

if ($Adb) {
    Write-Step "Checking USB phone authorization"
    $Devices = & adb devices -l
    $Devices | ForEach-Object { Write-Host $_ }
    if ($Devices -match "unauthorized") {
        Write-Host "PHONE UNAUTHORIZED: unlock the phone and accept the USB debugging prompt, then rerun setup." -ForegroundColor Yellow
    }
    if ($DeviceSerial) { $env:CYCLONE_DEVICE_SERIAL = $DeviceSerial }
    if (-not $DryRun -and $Devices -match "\sdevice(\s|$)") {
        $ForwardArgs = @()
        if ($DeviceSerial) { $ForwardArgs += @("-s", $DeviceSerial) }
        $ForwardArgs += @("forward", "tcp:8766", "localabstract:cyclone_gateway")
        & adb @ForwardArgs
        if ($LASTEXITCODE -ne 0) { throw "Could not create ADB forward." }
        Write-Host "ADB Forward READY: tcp:8766 -> localabstract:cyclone_gateway"
    }
}

Write-Step "Android session token"
Write-Host "One phone action is still required:"
Write-Host "Cyclone -> AI -> Full PC + Codex Gateway -> Enable -> Copy connection code"
Write-Host "Do NOT save that Android token in the repo. start-cyclone-bridge.ps1 prompts for it and keeps it session-only."

Write-Step "Bridge doctor"
if (-not $DryRun -and (Test-Path $TokenFile)) {
    $Secure = Import-Clixml $TokenFile
    $Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { $env:CYCLONE_DEVICE_GATEWAY_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Ptr) }
    $env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
    & (Join-Path $Venv "Scripts\cyclone-device-gateway.exe") doctor
    if ($LASTEXITCODE -ne 0) { Write-Host "Doctor is expected to be DEGRADED until the Android token is supplied and the PC Gateway is started." -ForegroundColor Yellow }
} else {
    Write-Host "DRY RUN: would run cyclone-device-gateway doctor"
}

Write-Host "`nSETUP COMPLETE"
Write-Host "Next: .\scripts\phone-gateway\start-cyclone-bridge.ps1"
