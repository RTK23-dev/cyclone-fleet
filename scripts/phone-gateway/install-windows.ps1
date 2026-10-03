param(
    [string]$VenvPath = "",
    [switch]$SkipUiAutomator2
)

$ErrorActionPreference = "Stop"

function Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Note($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }

$scriptDir = $PSScriptRoot
$gatewayWheel = Get-ChildItem -Path $scriptDir -Filter "cyclone_device_gateway-*.whl" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$mcpWheel = Get-ChildItem -Path $scriptDir -Filter "cyclone_phone_mcp-*.whl" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$bundleMode = $null -ne $gatewayWheel -and $null -ne $mcpWheel

if ($bundleMode) {
    $baseDir = $scriptDir
    Note "Detected extracted Cyclone V3.1 PC bundle"
} else {
    $baseDir = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
    if (-not (Test-Path (Join-Path $baseDir "apps\device-gateway\pyproject.toml"))) {
        Fail "Could not find bundled wheels or a Cyclone repository checkout."
    }
    Note "Detected Cyclone repository checkout"
}

if (-not $VenvPath) {
    $VenvPath = Join-Path $baseDir ".venv-gateway"
} elseif (-not [System.IO.Path]::IsPathRooted($VenvPath)) {
    $VenvPath = Join-Path $baseDir $VenvPath
}

if (-not (Test-Path $VenvPath)) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        Note "Creating Python virtual environment with py -3"
        & py -3 -m venv $VenvPath
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        Note "Creating Python virtual environment with python"
        & python -m venv $VenvPath
    } else {
        Fail "Python 3.11+ was not found. Install Python for Windows first."
    }
}

$venvPython = Join-Path $VenvPath "Scripts\python.exe"
$gatewayExe = Join-Path $VenvPath "Scripts\cyclone-device-gateway.exe"
if (-not (Test-Path $venvPython)) {
    Fail "Virtual environment Python was not created at $venvPython"
}

& $venvPython -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 4)"
if ($LASTEXITCODE -ne 0) {
    Fail "Cyclone Device Gateway requires Python 3.11 or newer."
}
Pass "Python 3.11+ virtual environment ready"

& $venvPython -m pip install --upgrade pip
if ($bundleMode) {
    Note "Installing Cyclone Device Gateway and Codex MCP wheels"
    & $venvPython -m pip install $gatewayWheel.FullName $mcpWheel.FullName
} else {
    Note "Installing Cyclone Device Gateway and Codex MCP from this checkout"
    & $venvPython -m pip install -e (Join-Path $baseDir "apps\device-gateway")
    & $venvPython -m pip install -e (Join-Path $baseDir "tools\codex-phone-mcp")
}
if ($LASTEXITCODE -ne 0) {
    Fail "Python package installation failed."
}

if (-not $SkipUiAutomator2) {
    Note "Installing optional UiAutomator2 independent witness provider"
    & $venvPython -m pip install "uiautomator2>=3.2,<4"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[WARN] UiAutomator2 installation failed. Cyclone will fall back to ADB uiautomator dump." -ForegroundColor Yellow
    }
}

if (-not (Test-Path $gatewayExe)) {
    Fail "cyclone-device-gateway console entry point was not installed."
}
Pass "Cyclone PC Device Gateway installed"

function New-StrongToken {
    $bytes = New-Object byte[] 32
    # GetBytes works on both .NET Framework (Windows PowerShell 5.1) and modern PowerShell.
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

$RuntimeRoot = Join-Path $env:LOCALAPPDATA "Cyclone\bridge-v31"
New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
$TokenFile = Join-Path $RuntimeRoot "pc-token.clixml"
$McpRunner = Join-Path $RuntimeRoot "run-cyclone-phone-mcp.ps1"
if (-not (Test-Path $TokenFile)) {
    $Token = New-StrongToken
    ConvertTo-SecureString $Token -AsPlainText -Force | Export-Clixml -Path $TokenFile
    Remove-Variable Token
    Note "Generated a new PC Gateway token protected with Windows CurrentUser encryption."
} else {
    Note "Existing encrypted PC Gateway token retained."
}

$McpExe = Join-Path $VenvPath "Scripts\cyclone-phone-mcp.exe"
$EscapedRunner = $McpRunner.Replace('\', '\\')
$EscapedMcp = $McpExe.Replace("'", "''")
$RunnerContent = @"
`$ErrorActionPreference = "Stop"
`$RuntimeRoot = Split-Path -Parent `$MyInvocation.MyCommand.Path
`$Secure = Import-Clixml (Join-Path `$RuntimeRoot "pc-token.clixml")
`$Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(`$Secure)
try { `$env:CYCLONE_DEVICE_GATEWAY_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(`$Ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(`$Ptr) }
`$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
& '$EscapedMcp'
exit `$LASTEXITCODE
"@
Set-Content -Path $McpRunner -Value $RunnerContent -Encoding UTF8

$configPath = Join-Path $baseDir "codex-config.generated.toml"
$config = @"
# Generated by Cyclone V3.1 install-windows.ps1.
# The PC gateway token is decrypted at launch from the user's Windows vault and never lives in this file.
[mcp_servers.cyclone-phone]
command = "powershell.exe"
args = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "$EscapedRunner"]
enabled = true
required = true
default_tools_approval_mode = "writes"
tool_timeout_sec = 60
"@
Set-Content -Path $configPath -Value $config -Encoding UTF8
Pass "Generated Codex MCP config: $configPath"

$escapedGateway = $gatewayExe.Replace("'", "''")
$startPath = Join-Path $baseDir "start-gateway.ps1"
$startScript = @"
`$ErrorActionPreference = "Stop"
`$RuntimeRoot = Join-Path `$env:LOCALAPPDATA "Cyclone\bridge-v31"
`$Secure = Import-Clixml (Join-Path `$RuntimeRoot "pc-token.clixml")
`$Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(`$Secure)
try { `$env:CYCLONE_DEVICE_GATEWAY_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(`$Ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(`$Ptr) }
if (-not `$env:CYCLONE_ANDROID_BRIDGE_TOKEN) { throw "Set CYCLONE_ANDROID_BRIDGE_TOKEN to the Session token shown by Cyclone PC Gateway on the phone." }
if (-not `$env:CYCLONE_DEVICE_GATEWAY_URL) { `$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765" }
& '$escapedGateway'
"@
Set-Content -Path $startPath -Value $startScript -Encoding UTF8
Pass "Generated gateway launcher: $startPath"

Write-Host ""
Write-Host "Cyclone V3.1 PC components are installed." -ForegroundColor Green
Write-Host "Before starting, set:" -ForegroundColor Yellow
Write-Host '  $env:CYCLONE_ANDROID_BRIDGE_TOKEN = "<Session token shown inside the Cyclone app>" (session-only)'
Write-Host '  $env:CYCLONE_DEVICE_SERIAL = "<Pixel 8 adb serial>"'
Write-Host '  $env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"'
Write-Host "Then run: & '$startPath'" -ForegroundColor Cyan
Write-Host "Copy/merge '$configPath' into the trusted Codex project's .codex/config.toml (it needs no token env vars)." -ForegroundColor Cyan
