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

function Stop-PidFile([string]$PidPath, [string]$Name) {
  if (-not (Test-Path $PidPath)) { return }
  $old = (Get-Content $PidPath -ErrorAction SilentlyContinue | Select-Object -First 1)
  if ($old) {
    try {
      $proc = Get-Process -Id ([int]$old) -ErrorAction Stop
      if (-not $Json) { Write-Host "stopping $Name pid=$old ($($proc.ProcessName))" }
      Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
      Start-Sleep -Milliseconds 300
    } catch {
      if (-not $Json) { Write-Host "$Name pid=$old already gone" }
    }
  }
  Remove-Item $PidPath -Force -ErrorAction SilentlyContinue
}

Stop-PidFile (Join-Path $Runtime "cloudflared.pid") "cloudflared"
Stop-PidFile (Join-Path $Runtime "gateway.pid") "gateway"

Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object {
    $_.CommandLine -and (
      $_.CommandLine -like "*gateway\server.js*" -or
      $_.CommandLine -like "*gateway/server.js*" -or
      ($_.Name -eq "cloudflared.exe" -and $_.CommandLine -like "*127.0.0.1:8787*")
    )
  } |
  ForEach-Object {
    if (-not $Json) { Write-Host "stopping leftover $($_.Name) pid=$($_.ProcessId)" }
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }

if ($Json) {
  @{
    ok                 = $true
    state              = "stopped"
    mode               = "readonly"
    tokenLast4         = $null
    publicUrl          = $null
    mcpUrl             = $null
    healthUrl          = $null
    localMcpUrl        = "http://127.0.0.1:8787/mcp"
    localHealthUrl     = "http://127.0.0.1:8787/health"
    gatewayAlive       = $false
    cloudflaredAlive   = $false
    healthOk           = $false
    installPath        = $Root
    grokStdioUntouched = $true
    message            = "Tunnel stopped. Local Grok Build / Cursor stdio MCP is unchanged."
  } | ConvertTo-Json -Compress -Depth 6
} else {
  Write-Host "tunnel stopped"
}
