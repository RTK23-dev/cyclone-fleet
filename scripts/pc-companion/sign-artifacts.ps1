param([Parameter(Mandatory=$true)][string]$ArtifactDirectory)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:CYCLONE_WINDOWS_SIGN_PFX_BASE64) -or [string]::IsNullOrWhiteSpace($env:CYCLONE_WINDOWS_SIGN_PFX_PASSWORD)) {
  Write-Host 'Signing credentials not configured; leaving artifacts unsigned.'
  exit 0
}
$cert = Join-Path $env:RUNNER_TEMP 'cyclone-signing.pfx'
try {
  [IO.File]::WriteAllBytes($cert, [Convert]::FromBase64String($env:CYCLONE_WINDOWS_SIGN_PFX_BASE64))
  Get-ChildItem -Path $ArtifactDirectory -File | Where-Object { $_.Extension -in '.exe','.msi' } | ForEach-Object {
    & signtool sign /fd SHA256 /f $cert /p $env:CYCLONE_WINDOWS_SIGN_PFX_PASSWORD $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for $($_.Name)" }
  }
} finally {
  Remove-Item -Force -ErrorAction SilentlyContinue $cert
}
