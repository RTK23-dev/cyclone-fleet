#Requires -Version 5.1
<#
.SYNOPSIS
  Replace the gateway bearer token and restart the gateway (tunnel process kept if possible).
#>
[CmdletBinding()]
param(
  [switch]$NoRestart,
  [switch]$Json
)

$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$Root = if ($env:CYCLONE_MCP_TUNNEL_ROOT) { $env:CYCLONE_MCP_TUNNEL_ROOT } else { Split-Path -Parent $PSScriptRoot }
$EnvFile = Join-Path $Root "config\gateway.env"
$Runtime = Join-Path $Root "artifacts\runtime"

if (-not (Test-Path $EnvFile)) {
  if ($Json) {
    @{ ok = $false; error = "missing gateway.env - start the tunnel once first"; grokStdioUntouched = $true } | ConvertTo-Json -Compress
    exit 1
  }
  throw "missing $EnvFile - start the tunnel once first"
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$tok = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")

$raw = @(Get-Content $EnvFile)
$replaced = $false
$next = foreach ($line in $raw) {
  if ($line -match '^GATEWAY_BEARER_TOKEN=') {
    $replaced = $true
    "GATEWAY_BEARER_TOKEN=$tok"
  } else {
    $line
  }
}
if (-not $replaced) { $next += "GATEWAY_BEARER_TOKEN=$tok" }
$next | Set-Content $EnvFile -Encoding ascii

$user = "$env:USERDOMAIN\$env:USERNAME"
icacls $EnvFile /inheritance:r /grant:r "${user}:(R,W)" | Out-Null

$last4 = $tok.Substring($tok.Length - 4)
if (-not $Json) {
  Write-Host ("new token last4={0} written to {1}" -f $last4, $EnvFile)
  Write-Host "update ChatGPT / Grok connector Authorization: Bearer <new token>"
  Write-Host "the previous token is now invalid"
}

$restarted = $false
if (-not $NoRestart) {
  $gwPidFile = Join-Path $Runtime "gateway.pid"
  $wasRunning = Test-Path $gwPidFile
  $stop = Join-Path $PSScriptRoot "stop-tunnel.ps1"
  $start = Join-Path $PSScriptRoot "start-tunnel.ps1"
  if ($Json) {
    & $stop -Json | Out-Null
  } else {
    & $stop
  }
  if ($wasRunning) {
    $restarted = $true
    if ($Json) {
      $started = & $start -Json
      Write-Output $started
      exit $LASTEXITCODE
    }
    & $start
    exit $LASTEXITCODE
  }
}

if ($Json) {
  @{
    ok                 = $true
    state              = "stopped"
    tokenLast4         = $last4
    rotated            = $true
    restarted          = $restarted
    grokStdioUntouched = $true
    message            = "Bearer rotated (last4 $last4). Previous token is invalid. Update ChatGPT / Grok connectors."
  } | ConvertTo-Json -Compress -Depth 6
}
