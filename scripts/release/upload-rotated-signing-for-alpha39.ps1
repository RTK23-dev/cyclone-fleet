param(
    [string]$Repo = "premiumcentraal-boop/Cyclone",
    [string]$Environment = "mobile-release-approval",
    [string]$Branch = "claude/cyclone-v5-handoff-review-9qrs40",
    # The signer of Cyclone 5.0.0-alpha.34.dev1, which phones that installed alpha.34 now require.
    [string]$ExpectedSignerSha256 = "e78c6e0b32d66da05839243c65e9987ba54722d402543f00408b57b1585dbf60",
    # The historical development signer; the lineage must still prove it so older installs can update.
    [string]$HistoricalSignerSha256 = "cc2a7a5d8e5e3686d8f7afe0a0e6e76ca42a1e6e6306e26f0eba84e4e69f3965"
)

# Reuse-only companion to rotate-android-signing-and-release.ps1, for the alpha.39 re-sign.
#
# Alpha.34 was signed on this PC with the rotated key in $HOME\.cyclone-signing. This script NEVER creates a key: it
# reads that existing key, lineage and DPAPI password backup, refuses unless the key is exactly alpha.34's signer and
# the lineage links it to the historical key, writes the five protected environment secrets through stdin, and
# dispatches the workflow that re-signs the verified alpha.39 CI build and replaces the APK on the alpha.39 release.
# Nothing touches the phone.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "Required command '$Name' was not found in PATH." }
    return $command.Source
}

function Resolve-ApkSigner {
    $direct = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($direct) { return $direct.Source }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
        Where-Object { $_ -and (Test-Path $_) }) {
        $buildTools = Join-Path $sdk "build-tools"
        if (-not (Test-Path $buildTools)) { continue }
        $found = Get-ChildItem $buildTools -Directory | Sort-Object Name -Descending | ForEach-Object {
            $bat = Join-Path $_.FullName "apksigner.bat"
            if (Test-Path $bat) { $bat }
        } | Select-Object -First 1
        if ($found) { return $found }
    }
    throw "apksigner was not found. Install Android SDK Build Tools 35.0.0, then rerun this script."
}

function Set-ProtectedSecret([string]$Name, [string]$Value) {
    # Exact bytes on stdin: no trailing newline, and nothing in process arguments.
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $gh
    $start.Arguments = "secret set $Name --repo $Repo --env $Environment"
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Failed to set protected secret $Name. $($process.StandardError.ReadToEnd())" }
    }
    finally { $process.Dispose() }
}

$gh = Require-Command "gh"
$keytool = Require-Command "keytool"
$apksigner = Resolve-ApkSigner

& $gh auth status
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run: gh auth login" }

$secureDir = Join-Path $HOME ".cyclone-signing"
$keystore = Join-Path $secureDir "cyclone-android-rotated-2026.p12"
$lineage = Join-Path $secureDir "cyclone-signing-lineage.bin"
$passwordBackup = Join-Path $secureDir "cyclone-android-rotated-2026-password.dpapi.txt"
foreach ($path in @($keystore, $lineage, $passwordBackup)) {
    if (-not (Test-Path $path)) {
        throw "Missing $path. This script only reuses the key that signed alpha.34 and never creates one. Restore it from your offline backup (or run this on the PC and Windows account that signed alpha.34)."
    }
}

$protected = Get-Content -Raw -Path $passwordBackup | ConvertTo-SecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protected)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$alias = "cyclone-rotated-2026"
$env:CYCLONE_ROTATED_STORE_PASSWORD = $password
try {
    $info = (& $keytool -list -v -alias $alias -keystore $keystore -storepass:env CYCLONE_ROTATED_STORE_PASSWORD) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "The saved keystore or password is invalid." }
    $match = [regex]::Match($info, 'SHA256:\s*([0-9a-fA-F:]+)')
    if (-not $match.Success) { throw "Cannot read the keystore certificate fingerprint." }
    $digest = $match.Groups[1].Value.Replace(':','').ToLowerInvariant()
    if ($digest -ne $ExpectedSignerSha256) {
        throw "This key ($digest) is not the key that signed alpha.34 ($ExpectedSignerSha256). Refusing: an APK signed with it would not install."
    }
    $lineageInfo = ((& $apksigner lineage --in $lineage --print-certs -v) -join "`n").Replace(':','').Replace(' ','').ToLowerInvariant()
    if ($LASTEXITCODE -ne 0) { throw "The saved signing lineage is invalid." }
    if (-not $lineageInfo.Contains($ExpectedSignerSha256) -or -not $lineageInfo.Contains($HistoricalSignerSha256)) {
        throw "The saved lineage does not link the historical key to the alpha.34 key."
    }
    Write-Host "Verified: key $digest is alpha.34's signer, and the lineage links it to $HistoricalSignerSha256."

    Write-Host "Writing the five signing secrets to the '$Environment' environment through stdin..."
    Set-ProtectedSecret "CYCLONE_ANDROID_KEYSTORE_B64" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))
    Set-ProtectedSecret "CYCLONE_ANDROID_STORE_PASSWORD" $password
    Set-ProtectedSecret "CYCLONE_ANDROID_KEY_ALIAS" $alias
    Set-ProtectedSecret "CYCLONE_ANDROID_KEY_PASSWORD" $password
    Set-ProtectedSecret "CYCLONE_ANDROID_SIGNING_LINEAGE_B64" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage)))

    Write-Host "Dispatching the alpha.39 re-sign..."
    & $gh workflow run v5-alpha39-rotated-resign.yml --repo $Repo --ref $Branch
    if ($LASTEXITCODE -ne 0) {
        # A workflow that lives only on a feature branch may not be dispatchable; re-running its first run works too
        # (the run reads the new secrets when it starts).
        Write-Host "Dispatch was refused; re-running the existing alpha.39 re-sign run instead."
        & $gh run rerun 36236959250 --repo $Repo
        if ($LASTEXITCODE -ne 0) { throw "Could not start the alpha.39 re-sign. Re-run 'Re-sign V5 alpha.39 with the rotated release key' in GitHub Actions." }
    }
    Write-Host "Done. Watch 'Re-sign V5 alpha.39 with the rotated release key' in GitHub Actions; when it passes, the alpha.39 release has the re-signed APK."
}
finally {
    Remove-Item Env:CYCLONE_ROTATED_STORE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
