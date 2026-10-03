#Requires -Version 5.1
<#
.SYNOPSIS
  Local smoke: health, 401 without token, initialize + filtered tools/list, blocked phone_act.
#>
[CmdletBinding()]
param(
  [string]$Base = "http://127.0.0.1:8787",
  [switch]$Json
)

$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$Root = if ($env:CYCLONE_MCP_TUNNEL_ROOT) { $env:CYCLONE_MCP_TUNNEL_ROOT } else { Split-Path -Parent $PSScriptRoot }
$EnvFile = Join-Path $Root "config\gateway.env"
if (-not (Test-Path $EnvFile)) {
  if ($Json) {
    @{ ok = $false; message = "missing gateway.env - start the tunnel first"; checks = @() } | ConvertTo-Json -Compress -Depth 6
    exit 1
  }
  throw "missing $EnvFile - run start-tunnel.ps1 first"
}
$tok = ((Get-Content $EnvFile | Where-Object { $_ -match '^GATEWAY_BEARER_TOKEN=' } | Select-Object -First 1) -split '=', 2)[1]
$fail = 0
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check($ok, $name, $detail) {
  $script:checks.Add([pscustomobject]@{ name = [string]$name; ok = [bool]$ok; detail = [string]$detail }) | Out-Null
  if ($ok) {
    if (-not $Json) { Write-Host "PASS $name" }
  } else {
    if (-not $Json) { Write-Host "FAIL $name" }
    $script:fail++
  }
}

try {
  $h = Invoke-WebRequest -Uri "$Base/health" -UseBasicParsing -TimeoutSec 5
  Add-Check ($h.StatusCode -eq 200) "GET /health 200" "status=$($h.StatusCode)"
  Add-Check ($h.Content -match '"ok":true') "health ok" "body has ok:true"
  Add-Check ($h.Content -notmatch [regex]::Escape($tok)) "health does not leak token" "token absent from body"
} catch {
  Add-Check $false "GET /health 200" $_.Exception.Message
}

try {
  Invoke-WebRequest -Uri "$Base/mcp" -Method POST -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' -ContentType "application/json" -UseBasicParsing -TimeoutSec 5 | Out-Null
  Add-Check $false "POST /mcp without auth -> 401" "request succeeded; anonymous access is not allowed"
} catch {
  $code = 0
  if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  Add-Check ($code -eq 401) "POST /mcp without auth -> 401" "got $code"
}

$init = @{
  jsonrpc = "2.0"
  id = 1
  method = "initialize"
  params = @{
    protocolVersion = "2025-03-26"
    capabilities = @{}
    clientInfo = @{ name = "one-settings-smoke"; version = "1.1.0" }
  }
} | ConvertTo-Json -Compress -Depth 6

$session = $null
try {
  $headers = @{ Authorization = "Bearer $tok"; Accept = "application/json, text/event-stream" }
  $r = Invoke-WebRequest -Uri "$Base/mcp" -Method POST -Headers $headers -Body $init -ContentType "application/json" -UseBasicParsing -TimeoutSec 25
  Add-Check ($r.StatusCode -eq 200) "initialize 200 with bearer" "status=$($r.StatusCode)"
  Add-Check ([bool]$r.Headers["Mcp-Session-Id"]) "Mcp-Session-Id present" "header present"
  $session = $r.Headers["Mcp-Session-Id"]
  Add-Check ($r.Content -match 'cyclone-phone') "initialize names cyclone-phone" "serverInfo matched"
} catch {
  Add-Check $false "initialize 200 with bearer" $_.Exception.Message
}

$headers2 = @{
  Authorization = "Bearer $tok"
  Accept = "application/json, text/event-stream"
}
if ($session) { $headers2["Mcp-Session-Id"] = $session }

$list = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
try {
  $r = Invoke-WebRequest -Uri "$Base/mcp" -Method POST -Headers $headers2 -Body $list -ContentType "application/json" -UseBasicParsing -TimeoutSec 25
  $listed = $r.Content | ConvertFrom-Json
  $names = @($listed.result.tools | ForEach-Object { $_.name })
  Add-Check ($names -contains "phone_status") "tools/list includes phone_status" "tools present"
  $mode = ((Get-Content $EnvFile | Where-Object { $_ -match '^GATEWAY_MODE=' } | Select-Object -First 1) -split '=', 2)[1]
  if ($mode -eq "readonly") {
    Add-Check (-not ($names -contains "phone_act")) "tools/list hides phone_act in readonly" "phone_act filtered"
  }
} catch {
  Add-Check $false "tools/list" $_.Exception.Message
}

$act = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"phone_act","arguments":{}}}'
try {
  $r = Invoke-WebRequest -Uri "$Base/mcp" -Method POST -Headers $headers2 -Body $act -ContentType "application/json" -UseBasicParsing -TimeoutSec 15
  $mode = ((Get-Content $EnvFile | Where-Object { $_ -match '^GATEWAY_MODE=' } | Select-Object -First 1) -split '=', 2)[1]
  if ($mode -eq "readonly") {
    Add-Check ($r.Content -match 'blocked by gateway readonly allowlist') "phone_act blocked by allowlist" "readonly allowlist"
  } else {
    Add-Check $true "phone_act reachable in full mode" "GATEWAY_MODE=full; mutating tools are exposed to the bearer holder"
  }
} catch {
  Add-Check $false "phone_act block" $_.Exception.Message
}

$passed = ($fail -eq 0)
$message = if ($passed) { "SMOKE PASSED" } else { "SMOKE FAILED ($fail)" }
if ($Json) {
  @{
    ok      = $passed
    message = $message
    checks  = $checks
  } | ConvertTo-Json -Compress -Depth 6
} else {
  Write-Host $message
}
if (-not $passed) { exit 1 }
exit 0
