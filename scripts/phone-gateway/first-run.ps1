param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$ConfigureForward,
    [switch]$SkipMcpCheck
)

$ErrorActionPreference = "Stop"
$Package = "com.cyclone.mobile"
$GatewayUrl = if ($env:CYCLONE_DEVICE_GATEWAY_URL) { $env:CYCLONE_DEVICE_GATEWAY_URL } else { "http://127.0.0.1:8765" }
$HttpToken = $env:CYCLONE_DEVICE_GATEWAY_TOKEN
$AndroidBridgeToken = $env:CYCLONE_ANDROID_BRIDGE_TOKEN

function Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Note($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb was not found. Install Android Platform Tools and add adb.exe to PATH."
}
Pass "adb is available"

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Fail "python was not found in this PowerShell session. Activate the Cyclone gateway virtual environment first."
}
Pass "Python is available"

$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S+\s+\S+" }
$authorized = @($deviceLines | Where-Object { $_ -match "\sdevice$" })
$unauthorized = @($deviceLines | Where-Object { $_ -match "\sunauthorized$" })
if ($unauthorized.Count -gt 0) {
    Fail "An Android device is unauthorized. Unlock the Pixel 8 and accept the USB debugging prompt."
}

if (-not $Serial) {
    if ($authorized.Count -ne 1) {
        Fail "Expected exactly one authorized Android device. Pass -Serial <adb-serial> when multiple devices are connected."
    }
    $Serial = (($authorized[0] -split "\s+")[0])
}
if (-not ($authorized | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    Fail "ADB serial '$Serial' is not an authorized connected device."
}
$env:CYCLONE_DEVICE_SERIAL = $Serial
Pass "ADB device selected: $Serial"

$model = (adb -s $Serial shell getprop ro.product.model).Trim()
if ($model -ne "Pixel 8") {
    Fail "Selected device reports '$model', not the first-test target Pixel 8."
}
Pass "Pixel 8 confirmed"

$pkg = (adb -s $Serial shell pm path $Package 2>$null)
if (-not $pkg) {
    Fail "Cyclone package $Package is not installed on the Pixel 8."
}
Pass "Cyclone is installed"

$rootResult = (adb -s $Serial shell su -c id 2>$null | Out-String).Trim()
if ($rootResult -match "uid=0") {
    Pass "Root is available for optional getevent/dumpsys/logcat telemetry"
} else {
    Warn "Root is not available. Agentic Accessibility control can still work, but root telemetry/input tracing will be unavailable."
}

if (-not $AndroidBridgeToken) {
    Fail "CYCLONE_ANDROID_BRIDGE_TOKEN is not set. In the Cyclone app tap the PC Gateway button, enable it, copy the Session token, then set `$env:CYCLONE_ANDROID_BRIDGE_TOKEN='<token>'."
}
Pass "Android bridge session token is configured locally"

if (-not $HttpToken) {
    Fail "CYCLONE_DEVICE_GATEWAY_TOKEN is not set. Choose a separate strong local token for the PC HTTP gateway."
}
Pass "PC HTTP bearer token is configured locally"

if ($HttpToken -eq $AndroidBridgeToken) {
    Warn "The PC HTTP token and Android session token are identical. V3.1 supports this, but separate tokens are recommended."
}

$forward = adb -s $Serial forward --list | Where-Object { $_ -match "tcp:8766\s+localabstract:cyclone_gateway" }
if (-not $forward -and $ConfigureForward) {
    Note "Creating local ADB forward tcp:8766 -> localabstract:cyclone_gateway"
    adb -s $Serial forward tcp:8766 localabstract:cyclone_gateway | Out-Null
    $forward = adb -s $Serial forward --list | Where-Object { $_ -match "tcp:8766\s+localabstract:cyclone_gateway" }
}
if (-not $forward) {
    Fail "Cyclone Android bridge forwarding is not configured. Re-run with -ConfigureForward after enabling PC Gateway inside Cyclone."
}
Pass "Cyclone Android bridge ADB forward is configured"

$headers = @{ Authorization = "Bearer $HttpToken" }
try {
    $status = Invoke-RestMethod -Uri "$GatewayUrl/v1/device/status" -Headers $headers -Method Get -TimeoutSec 10
} catch {
    Fail "PC Device Gateway is not reachable/authenticated at $GatewayUrl. Start the Cyclone PC Device Gateway with both token variables set. $($_.Exception.Message)"
}
Pass "PC Device Gateway is reachable"

if (-not $status.cyclone_bridge_reachable) {
    $bridgeError = $status.cyclone_bridge.error
    Fail "PC Gateway cannot authenticate/reach the Android localabstract bridge. Confirm CYCLONE_ANDROID_BRIDGE_TOKEN exactly matches the token displayed on the phone. $bridgeError"
}
Pass "PC -> ADB forward -> Android Cyclone bridge is authenticated"

$bridge = $status.cyclone_bridge
if (-not $bridge.gatewayEnabled) {
    Fail "Android bridge reports gatewayEnabled=false. Enable 'PC Gateway (USB debugging)' inside Cyclone."
}
if (-not $bridge.accessibilityConnected) {
    Fail "Cyclone Accessibility does not report connected. Enable Cyclone in Android Accessibility settings."
}
Pass "Cyclone Gateway + Accessibility report ready"

if ($bridge.appVersion -and $bridge.appVersion -notmatch "3\.1") {
    Warn "Installed Cyclone reports '$($bridge.appVersion)'. This preflight is designed for the V3.1 integrated-gateway APK."
} elseif ($bridge.appVersion) {
    Pass "Cyclone V3.1 app version confirmed"
}

$mcpOutput = python -m cyclone_phone_mcp --self-test 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Fail "Cyclone Phone MCP self-test could not talk to Device Gateway. Activate/install the V3.1 MCP package first. $mcpOutput"
}
Write-Host $mcpOutput.Trim()
Pass "Cyclone Phone MCP self-test passed"

$mcpDevices = python -m cyclone_phone_mcp --self-test devices 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Warn "Device auto-detection self-test reported: $($mcpDevices.Trim())"
} else {
    Write-Host $mcpDevices.Trim()
    Pass "Cyclone Phone MCP auto-detects the connected phone"
}

if (-not $SkipMcpCheck) {
    if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
        Fail "codex CLI is not installed/on PATH. Install/open Codex, then register the MCP server as documented in CODEX_PHONE_FIRST_RUN.md."
    }
    $mcpList = codex mcp list 2>&1 | Out-String
    if ($mcpList -notmatch "cyclone-phone") {
        Fail "Codex does not list an MCP server named 'cyclone-phone'. Register it using the generated Codex config or CODEX_PHONE_FIRST_RUN.md."
    }
    Pass "Codex lists cyclone-phone MCP"
}

Write-Host ""
Write-Host "Cyclone V3.1 + Full Gateway is ready for the safe Pixel 8 acceptance run." -ForegroundColor Green
Write-Host "Next: python -m cyclone_phone_mcp.acceptance --live --execute" -ForegroundColor Yellow
