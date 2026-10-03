[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$RuntimeRoot = Join-Path $env:LOCALAPPDATA "Cyclone\bridge-v31"
$Venv = Join-Path $RuntimeRoot "venv"
$TokenFile = Join-Path $RuntimeRoot "pc-token.clixml"
$GatewayExe = Join-Path $Venv "Scripts\cyclone-device-gateway.exe"
$Stdout = Join-Path $RuntimeRoot "gateway.stdout.log"
$Stderr = Join-Path $RuntimeRoot "gateway.stderr.log"
$PidFile = Join-Path $RuntimeRoot "gateway.pid"

if (-not (Test-Path $GatewayExe)) { throw "Cyclone bridge environment is missing. Run setup-cyclone-bridge.ps1 first." }
if (-not (Test-Path $TokenFile)) { throw "Encrypted PC Gateway token is missing. Run setup-cyclone-bridge.ps1 first." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "ADB is missing. Run setup-cyclone-bridge.ps1 first." }

function Get-PlainSecureString([Security.SecureString]$Secure) {
    $Ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Ptr) }
}

if ($DeviceSerial) { $env:CYCLONE_DEVICE_SERIAL = $DeviceSerial }
$AdbArgs = @()
if ($DeviceSerial) { $AdbArgs += @("-s", $DeviceSerial) }
$Devices = & adb devices -l
if ($Devices -match "unauthorized") {
    throw "Phone is UNAUTHORIZED. Unlock it, accept the USB debugging prompt, then run this command again."
}
$AuthorizedLines = @($Devices | Select-String -Pattern "\sdevice(\s|$)")
if (-not $DeviceSerial -and $AuthorizedLines.Count -gt 1) {
    throw "Multiple authorized phones are connected. Rerun with -DeviceSerial <serial>."
}
if ($AuthorizedLines.Count -eq 0) { throw "No authorized ADB phone is connected." }

& adb @AdbArgs forward tcp:8766 localabstract:cyclone_gateway
if ($LASTEXITCODE -ne 0) { throw "Could not create the Cyclone ADB forward." }

$PcToken = Get-PlainSecureString (Import-Clixml $TokenFile)
$AndroidToken = ($env:CYCLONE_ANDROID_BRIDGE_TOKEN | ForEach-Object { $_.Trim() })
if (-not $AndroidToken) {
    Write-Host "On the phone: Cyclone -> AI -> Full PC + Codex Gateway -> Enable -> Copy session token" -ForegroundColor Cyan
    $AndroidToken = Get-PlainSecureString (Read-Host "Paste the Android session token (input hidden)" -AsSecureString)
}
if (-not $AndroidToken) { throw "Android session token is required." }

$PreviousPcToken = $env:CYCLONE_DEVICE_GATEWAY_TOKEN
$PreviousAndroidToken = $env:CYCLONE_ANDROID_BRIDGE_TOKEN
$PreviousUrl = $env:CYCLONE_DEVICE_GATEWAY_URL
try {
    $env:CYCLONE_DEVICE_GATEWAY_TOKEN = $PcToken
    $env:CYCLONE_ANDROID_BRIDGE_TOKEN = $AndroidToken
    $env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"

    if (Test-Path $PidFile) {
        $OldPid = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($OldPid -and (Get-Process -Id $OldPid -ErrorAction SilentlyContinue)) {
            Write-Host "Cyclone PC Gateway is already running (PID $OldPid)."
        } else {
            Remove-Item $PidFile -ErrorAction SilentlyContinue
        }
    }

    if (-not (Test-Path $PidFile)) {
        $Process = Start-Process -FilePath $GatewayExe -ArgumentList @("serve") -WindowStyle Hidden -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru
        Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII
        Write-Host "Cyclone PC Gateway started (PID $($Process.Id))."
    }

    Start-Sleep -Milliseconds 900
    & $GatewayExe doctor
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Bridge doctor reports attention is needed. Check $Stderr and the phone Gateway control center." -ForegroundColor Yellow
    }

    Write-Host "PC Gateway: http://127.0.0.1:8765"
    Write-Host "Android token remains session-only and was not written to disk."
    if (-not $NoBrowser) {
        try { Start-Process "http://127.0.0.1:8765/pc" | Out-Null } catch { }
    }
} finally {
    $env:CYCLONE_DEVICE_GATEWAY_TOKEN = $PreviousPcToken
    $env:CYCLONE_ANDROID_BRIDGE_TOKEN = $PreviousAndroidToken
    $env:CYCLONE_DEVICE_GATEWAY_URL = $PreviousUrl
    Remove-Variable PcToken, AndroidToken -ErrorAction SilentlyContinue
}
