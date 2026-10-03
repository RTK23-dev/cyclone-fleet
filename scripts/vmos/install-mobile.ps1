param(
  [Parameter(Mandatory=$true)][string]$MobileApk,
  [Parameter(Mandatory=$true)][string]$VmosSerial,
  [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$oneRoot = Join-Path $env:LOCALAPPDATA "Cyclone One"
$bundledAdb = Join-Path $oneRoot "android-platform-tools\adb.exe"
if ([string]::IsNullOrWhiteSpace($Adb)) { $Adb = $bundledAdb }
if (-not (Test-Path $Adb)) { throw "Cyclone One bundled adb.exe was not found at '$Adb'. Install/repair Cyclone One 1.5.1." }
$Adb = (Resolve-Path $Adb).Path
$adbOutput = (& $Adb version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $adbOutput -notmatch '(?m)^Version\s+(\d+\.\d+\.\d+)') {
  throw "Could not verify Android Platform-Tools from '$Adb'."
}
$adbVersion = [version]$Matches[1]
if ($adbVersion -lt [version]"37.0.1") { throw "Android Platform-Tools 37.0.1 or newer is required; found $adbVersion." }

if (-not (Test-Path $MobileApk)) { throw "Cyclone Mobile APK not found: $MobileApk" }
if ([IO.Path]::GetExtension($MobileApk).ToLowerInvariant() -ne ".apk") { throw "Cyclone Mobile artifact must be an .apk file." }

$state = (& $Adb -s $VmosSerial get-state 2>$null | Select-Object -First 1).Trim()
if ($state -ne "device") { throw "VMOS ADB serial '$VmosSerial' is not connected in device state." }

$sdk = [int]((& $Adb -s $VmosSerial shell getprop ro.build.version.sdk).Trim())
if ($sdk -lt 33) { throw "Cyclone Mobile requires Android API 33 / Android 13 or newer. VMOS reports API $sdk." }

Write-Host "Installing Cyclone Mobile on VMOS device $VmosSerial with Platform-Tools $adbVersion..."
$install = & $Adb -s $VmosSerial install -r $MobileApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($install -join "`n") -notmatch "Success") {
  throw "APK install failed: $($install -join ' ')"
}

Write-Host "Launching com.cyclone.mobile/.MainActivity..."
$launch = & $Adb -s $VmosSerial shell am start -W -n com.cyclone.mobile/.MainActivity 2>&1
if ($LASTEXITCODE -ne 0 -or ($launch -join "`n") -match "Error:") {
  throw "Cyclone Mobile launch failed: $($launch -join ' ')"
}

Start-Sleep -Milliseconds 700
$packagePath = (& $Adb -s $VmosSerial shell pm path com.cyclone.mobile 2>$null | Select-Object -First 1).Trim()
$pid = (& $Adb -s $VmosSerial shell pidof com.cyclone.mobile 2>$null | Select-Object -First 1).Trim()
if (-not $packagePath.StartsWith("package:")) { throw "Cyclone Mobile package verification failed after install." }
if ([string]::IsNullOrWhiteSpace($pid)) { throw "Cyclone Mobile process is not running after launch." }

[pscustomobject]@{
  Installed = $true
  Launched = $true
  Serial = $VmosSerial
  AdbPath = $Adb
  AdbVersion = $adbVersion.ToString()
  AndroidSdk = $sdk
  Package = "com.cyclone.mobile"
  Launcher = ".MainActivity"
  Pid = $pid
  PackagePath = $packagePath
  NextStep = "Pair Cyclone One with this Cyclone Mobile instance."
} | ConvertTo-Json -Depth 3
