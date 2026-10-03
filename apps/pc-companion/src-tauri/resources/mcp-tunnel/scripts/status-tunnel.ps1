#Requires -Version 5.1
[CmdletBinding()]
param(
  [switch]$Json
)

$ErrorActionPreference = "Continue"
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$Root = if ($env:CYCLONE_MCP_TUNNEL_ROOT) { $env:CYCLONE_MCP_TUNNEL_ROOT } else { Split-Path -Parent $PSScriptRoot }
$Runtime = Join-Path $Root "artifacts\runtime"
$EnvFile = Join-Path $Root "config\gateway.env"
$Node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $Node) { $Node = "C:\Program Files\nodejs\node.exe" }
$Cloudflared = "C:\Program Files (x86)\cloudflared\cloudflared.exe"

function Test-PidAlive([string]$PidPath) {
  if (-not (Test-Path $PidPath)) { return $false }
  $id = (Get-Content $PidPath -ErrorAction SilentlyContinue | Select-Object -First 1)
  if (-not $id) { return $false }
  try {
    Get-Process -Id ([int]$id) -ErrorAction Stop | Out-Null
    return $true
  } catch {
    return $false
  }
}

function Get-PidValue([string]$PidPath) {
  if (-not (Test-Path $PidPath)) { return $null }
  $id = (Get-Content $PidPath -ErrorAction SilentlyContinue | Select-Object -First 1)
  if (-not $id) { return $null }
  return [int]$id
}

function Get-EnvValue([string]$Key) {
  if (-not (Test-Path $EnvFile)) { return $null }
  $line = Get-Content $EnvFile -ErrorAction SilentlyContinue | Where-Object { $_ -match ("^" + [regex]::Escape($Key) + "=") } | Select-Object -First 1
  if (-not $line) { return $null }
  return ($line -split '=', 2)[1].Trim()
}

function Resolve-McpBinary {
  $candidates = @(
    $env:MCP_COMMAND,
    (Get-EnvValue "MCP_COMMAND"),
    (Join-Path $env:LOCALAPPDATA "Cyclone One\CycloneAgentMCP.exe"),
    (Join-Path $env:LOCALAPPDATA "Cyclone PC Companion\CycloneAgentMCP.exe")
  ) | Where-Object { $_ }
  foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { return $c }
  }
  if ($candidates.Count -gt 0) { return $candidates[0] }
  return $null
}

$gwAlive = Test-PidAlive (Join-Path $Runtime "gateway.pid")
$cfAlive = Test-PidAlive (Join-Path $Runtime "cloudflared.pid")
$healthOk = $false
$healthDetail = $null
try {
  $h = Invoke-WebRequest -Uri "http://127.0.0.1:8787/health" -UseBasicParsing -TimeoutSec 3
  $healthOk = ($h.StatusCode -eq 200 -and $h.Content -match '"ok":true')
  $healthDetail = $h.Content
} catch {
  $healthDetail = $_.Exception.Message
}

$public = $null
$urlFile = Join-Path $Runtime "public-url.txt"
if (Test-Path $urlFile) {
  $public = (Get-Content $urlFile | Select-Object -First 1)
  if ($public) { $public = $public.Trim().TrimEnd("/") }
}
if (-not $public) {
  $fromEnv = Get-EnvValue "PUBLIC_URL"
  if ($fromEnv) { $public = $fromEnv.Trim().TrimEnd("/") }
}

$mode = Get-EnvValue "GATEWAY_MODE"
if (-not $mode) { $mode = "readonly" }
$mode = $mode.ToLowerInvariant()
$token = Get-EnvValue "GATEWAY_BEARER_TOKEN"
$tokenLast4 = $null
if ($token -and $token.Length -ge 4) { $tokenLast4 = $token.Substring($token.Length - 4) }

$mcp = Resolve-McpBinary
$mcpOk = [bool]($mcp -and (Test-Path $mcp))
$nodeOk = [bool](Test-Path $Node)
$cfOk = [bool](Test-Path $Cloudflared)

$hasPublic = [bool]$public
$state = "stopped"
if ($gwAlive -and $healthOk -and $cfAlive -and $hasPublic) {
  $state = "running"
} elseif ($gwAlive -or $cfAlive -or $healthOk -or $hasPublic) {
  $state = "degraded"
}

$message = switch ($state) {
  "running"  { "Tunnel is running. ChatGPT / Grok chat can use the public MCP URL with the bearer token." }
  "degraded" { "Tunnel is partially up. Health, cloudflared, or the public URL needs attention. Quick tunnels also change hostname on restart." }
  default    { "Tunnel is stopped. Start it from Settings to get a public MCP URL. Local Grok Build / Cursor stdio is unchanged." }
}

$mcpUrl = if ($public) { "$public/mcp" } else { $null }
$healthUrl = if ($public) { "$public/health" } else { $null }

if ($Json) {
  @{
    ok                 = $true
    state              = $state
    mode               = $mode
    tokenLast4         = $tokenLast4
    publicUrl          = $public
    mcpUrl             = $mcpUrl
    healthUrl          = $healthUrl
    localMcpUrl        = "http://127.0.0.1:8787/mcp"
    localHealthUrl     = "http://127.0.0.1:8787/health"
    gatewayAlive       = $gwAlive
    cloudflaredAlive   = $cfAlive
    healthOk           = $healthOk
    mcpBinaryOk        = $mcpOk
    mcpBinary          = $mcp
    nodeOk             = $nodeOk
    cloudflaredOk      = $cfOk
    gatewayPid         = (Get-PidValue (Join-Path $Runtime "gateway.pid"))
    cloudflaredPid     = (Get-PidValue (Join-Path $Runtime "cloudflared.pid"))
    installPath        = $Root
    grokStdioUntouched = $true
    message            = $message
  } | ConvertTo-Json -Compress -Depth 6
} else {
  Write-Host ("gateway    : {0}" -f $(if ($gwAlive) { "running" } else { "not running" }))
  Write-Host ("cloudflared: {0}" -f $(if ($cfAlive) { "running" } else { "not running" }))
  Write-Host ("health     : {0}" -f $(if ($healthOk) { "200" } else { "down ($healthDetail)" }))
  Write-Host ("public     : {0}" -f $(if ($mcpUrl) { $mcpUrl } else { "(none)" }))
  Write-Host ("config     : GATEWAY_MODE=$mode tokenLast4=$tokenLast4")
  Write-Host "local grok : ~/.grok/config.toml cyclone-phone still uses stdio (untouched)"
}
