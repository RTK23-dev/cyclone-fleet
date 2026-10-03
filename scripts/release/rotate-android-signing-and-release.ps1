param(
    [string]$Repo = "premiumcentraal-boop/Cyclone",
    [string]$Environment = "mobile-release-approval",
    [string]$Version = "",
    [string]$BuildRunId = "",
    [string]$ExpectedSourceSha = "",
    [string]$PixelSerial = "",
    [string]$LegacyRef = "origin/release/cyclone-mobile-v3.9.0"
)

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

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { $_ -and (Test-Path $_) } |
        Select-Object -Unique

    foreach ($sdk in $sdkRoots) {
        $buildTools = Join-Path $sdk "build-tools"
        if (-not (Test-Path $buildTools)) { continue }
        $candidate = Get-ChildItem $buildTools -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                $bat = Join-Path $_.FullName "apksigner.bat"
                $exe = Join-Path $_.FullName "apksigner"
                if (Test-Path $bat) { $bat }
                elseif (Test-Path $exe) { $exe }
            } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    throw "apksigner was not found. Install Android SDK Build Tools 35.0.0, then rerun this script."
}

function Extract-QuotedSetting([string]$Text, [string]$Name) {
    $match = [regex]::Match($Text, [regex]::Escape($Name) + '\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw "Could not recover legacy $Name from Git history." }
    return $match.Groups[1].Value
}

function New-RandomPassword {
    $bytes = New-Object byte[] 36
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','A').Replace('/','B'))
}

function Set-ProtectedSecret([string]$Name, [string]$Value) {
    # PowerShell's pipeline appends a newline, and --body exposes credentials
    # in process arguments. Send exact bytes directly to gh's standard input.
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
        if ($process.ExitCode -ne 0) {
            throw "Failed to set protected secret $Name. $($process.StandardError.ReadToEnd())"
        }
    }
    finally { $process.Dispose() }
}

function Get-ApkSignerDigest([string]$ApkPath) {
    $verify = (& $apksigner verify --min-sdk-version 33 --print-certs $ApkPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot verify APK signature: $ApkPath" }
    $match = [regex]::Match($verify, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $match.Success) { throw "Cannot read signer fingerprint from $ApkPath" }
    return $match.Groups[1].Value.ToLowerInvariant()
}

$git = Require-Command "git"
$gh = Require-Command "gh"
$keytool = Require-Command "keytool"
$apksigner = Resolve-ApkSigner

Write-Host "Checking GitHub authentication..."
& $gh auth status
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run: gh auth login" }

if ($Repo -ne "premiumcentraal-boop/Cyclone" -or $Environment -ne "mobile-release-approval") {
    throw "V5 signing requires the Cyclone repository and protected mobile-release-approval environment."
}
if ($BuildRunId -notmatch '^[0-9]+$' -or $Version -notmatch '^5\.0\.0-alpha\.2\.dev[0-9]+$' -or
    $ExpectedSourceSha -notmatch '^[0-9a-f]{40}$') {
    throw "Use a reviewed V5 alpha.2 development version, numeric successful CI run, and exact 40-character source SHA."
}
$ReleaseBranch = "release/cyclone-mobile-v$Version"
$ArtifactName = "Cyclone-Android-$Version"
$runJson = & $gh api "repos/$Repo/actions/runs/$BuildRunId"
if ($LASTEXITCODE -ne 0) { throw "Cannot read source CI run $BuildRunId." }
$sourceRun = ($runJson -join "`n") | ConvertFrom-Json
if ($sourceRun.name -ne "Cyclone Mobile CI" -or $sourceRun.conclusion -ne "success" -or
    $sourceRun.event -ne "push" -or $sourceRun.head_branch -ne $ReleaseBranch -or
    $sourceRun.head_repository.full_name -ne $Repo -or
    $sourceRun.head_sha -ne $ExpectedSourceSha) {
    throw "The CI run does not match the reviewed release branch, source SHA, and successful push build."
}
$artifactJson = & $gh api "repos/$Repo/actions/runs/$BuildRunId/artifacts"
if ($LASTEXITCODE -ne 0) { throw "Cannot read artifacts of source CI run $BuildRunId." }
$artifacts = ($artifactJson -join "`n") | ConvertFrom-Json
if (-not @($artifacts.artifacts | Where-Object { $_.name -eq $ArtifactName -and -not $_.expired }).Count) {
    throw "The exact unsigned Mobile artifact is missing or expired. Obtain a new reviewed CI run."
}
Write-Host "Verified exact CI run $BuildRunId, source $($sourceRun.head_sha), and artifact $ArtifactName."

Write-Host "Fetching legacy signing source without checking it out..."
& $git fetch origin "release/cyclone-mobile-v3.9.0" --quiet
if ($LASTEXITCODE -ne 0) { throw "Could not fetch release/cyclone-mobile-v3.9.0." }

$legacyGradle = (& $git show "$LegacyRef`:apps/mobile/app/build.gradle.kts") -join "`n"
if ($LASTEXITCODE -ne 0 -or -not $legacyGradle) { throw "Could not read the legacy Android signing configuration." }
$legacyB64 = ((& $git show "$LegacyRef`:apps/mobile/release.keystore.b64") -join "").Trim()
if ($LASTEXITCODE -ne 0 -or -not $legacyB64) { throw "Could not read the legacy release keystore from Git history." }

$releaseBlock = [regex]::Match($legacyGradle, 'create\("ciRelease"\)\s*\{(?<body>.*?)\r?\n\s*\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $releaseBlock.Success) { throw "Could not locate the legacy ciRelease signing block." }
$legacyBody = $releaseBlock.Groups['body'].Value
$oldStorePassword = Extract-QuotedSetting $legacyBody "storePassword"
$oldAlias = Extract-QuotedSetting $legacyBody "keyAlias"
$oldKeyPassword = Extract-QuotedSetting $legacyBody "keyPassword"

$secureDir = Join-Path $HOME ".cyclone-signing"
New-Item -ItemType Directory -Force -Path $secureDir | Out-Null
$oldKeystore = Join-Path $env:TEMP ("cyclone-old-release-" + [guid]::NewGuid().ToString('N') + '.p12')
$newKeystore = Join-Path $secureDir "cyclone-android-rotated-2026.p12"
$lineage = Join-Path $secureDir "cyclone-signing-lineage.bin"
$passwordBackup = Join-Path $secureDir "cyclone-android-rotated-2026-password.dpapi.txt"

$existing = @($newKeystore, $lineage, $passwordBackup) | Where-Object { Test-Path $_ }
if ($existing.Count -ne 0 -and $existing.Count -ne 3) {
    throw "Incomplete rotation state in $secureDir. Preserve it and recover from a secure backup; no key will be overwritten."
}

$env:CYCLONE_OLD_STORE_PASSWORD = $oldStorePassword
$env:CYCLONE_OLD_KEY_PASSWORD = $oldKeyPassword
$priorDir = $null
try {
    [IO.File]::WriteAllBytes($oldKeystore, [Convert]::FromBase64String($legacyB64))
    # The current published app is the update target. A lineage from some
    # other historical key would sign successfully but fail to upgrade it.
    $priorDir = Join-Path $env:TEMP ("cyclone-v5-prior-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $priorDir | Out-Null
    & $gh release download v4.8.0 --repo $Repo --pattern 'Cyclone-4.8.0.apk' --dir $priorDir
    if ($LASTEXITCODE -ne 0) { throw "The published 4.8.0 APK is unavailable for signer comparison." }
    $publishedDigest = Get-ApkSignerDigest (Join-Path $priorDir 'Cyclone-4.8.0.apk')
    $oldKeyInfo = (& $keytool -list -v -alias $oldAlias -keystore $oldKeystore `
        -storepass:env CYCLONE_OLD_STORE_PASSWORD) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot verify the historical key certificate." }
    $oldDigestMatch = [regex]::Match($oldKeyInfo, 'SHA256:\s*([0-9a-fA-F:]+)')
    if (-not $oldDigestMatch.Success -or
        $oldDigestMatch.Groups[1].Value.Replace(':','').ToLowerInvariant() -ne $publishedDigest) {
        throw "Historical signing key does not match published Cyclone 4.8.0. Refusing rotation."
    }
    Write-Host "Published 4.8.0 signer matches lineage source: $publishedDigest"

    if ($PixelSerial) {
        $adb = Require-Command "adb"
        $basePath = @(& $adb -s $PixelSerial shell pm path com.cyclone.mobile) |
            Where-Object { $_ -match '^package:.*/base\.apk\s*$' } | Select-Object -First 1
        if ($LASTEXITCODE -ne 0 -or -not $basePath) { throw "Pixel has no readable Cyclone base.apk." }
        $phoneApk = Join-Path $priorDir 'installed-Cyclone-base.apk'
        $installedBase = ($basePath -replace '^package:', '').Trim()
        & $adb -s $PixelSerial pull $installedBase $phoneApk | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot read the installed Pixel APK for signer comparison." }
        if ((Get-ApkSignerDigest $phoneApk) -ne $publishedDigest) {
            throw "Installed Pixel signer differs from published Cyclone 4.8.0. Stop before updating."
        }
        Write-Host "Installed Pixel signer also matches $publishedDigest. No phone app data was changed."
    }

    $newAlias = "cyclone-rotated-2026"
    if ($existing.Count -eq 3) {
        Write-Host "Resuming with the existing protected V5 key and lineage; no new key will be generated."
        $protectedPassword = Get-Content -Raw -Path $passwordBackup | ConvertTo-SecureString
        $passwordBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protectedPassword)
        try {
            $newStorePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordBstr)
        }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordBstr) }
    }
    else {
        $newStorePassword = New-RandomPassword
    }
    $newKeyPassword = $newStorePassword
    $env:CYCLONE_NEW_STORE_PASSWORD = $newStorePassword
    $env:CYCLONE_NEW_KEY_PASSWORD = $newKeyPassword
    if ($existing.Count -eq 0) {
        Write-Host "Generating a new private Android signing key..."
        & $keytool -genkeypair `
            -keystore $newKeystore `
            -storetype PKCS12 `
            -storepass:env CYCLONE_NEW_STORE_PASSWORD `
            -keypass:env CYCLONE_NEW_KEY_PASSWORD `
            -alias $newAlias `
            -keyalg RSA `
            -keysize 4096 `
            -validity 10000 `
            -dname "CN=Cyclone Mobile, OU=Release, O=Cyclone, C=NL"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed to generate the rotated signing key." }
        # Windows DPAPI encrypts the local password backup to this user account.
        $newStorePassword | ConvertTo-SecureString -AsPlainText -Force |
            ConvertFrom-SecureString | Set-Content -Path $passwordBackup -NoNewline

        Write-Host "Creating Android proof-of-rotation lineage..."
        & $apksigner rotate `
            --out $lineage `
            --old-signer `
            --ks $oldKeystore `
            --ks-key-alias $oldAlias `
            --ks-pass env:CYCLONE_OLD_STORE_PASSWORD `
            --key-pass env:CYCLONE_OLD_KEY_PASSWORD `
            --new-signer `
            --ks $newKeystore `
            --ks-key-alias $newAlias `
            --ks-pass env:CYCLONE_NEW_STORE_PASSWORD `
            --key-pass env:CYCLONE_NEW_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw "apksigner rotate failed." }
    }

    $newKeyInfo = (& $keytool -list -v -alias $newAlias -keystore $newKeystore `
        -storepass:env CYCLONE_NEW_STORE_PASSWORD) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "The saved rotated keystore or password is invalid." }
    $newDigestMatch = [regex]::Match($newKeyInfo, 'SHA256:\s*([0-9a-fA-F:]+)')
    if (-not $newDigestMatch.Success) { throw "Cannot read rotated signing certificate fingerprint." }
    $newDigest = $newDigestMatch.Groups[1].Value.Replace(':','').ToLowerInvariant()
    $lineageInfo = (& $apksigner lineage --in $lineage --print-certs -v) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "The saved signing lineage is invalid." }
    $flatLineage = $lineageInfo.Replace(':','').Replace(' ','').ToLowerInvariant()
    if (-not $flatLineage.Contains($publishedDigest) -or -not $flatLineage.Contains($newDigest)) {
        throw "Saved lineage does not include both the published 4.8.0 signer and the rotated key."
    }
    Write-Host "Verified existing lineage from $publishedDigest to $newDigest."

    $newKeystoreB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($newKeystore))
    $lineageB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage))

    Write-Host "Writing rotated signing credentials to GitHub environment '$Environment' through stdin..."
    Set-ProtectedSecret "CYCLONE_ANDROID_KEYSTORE_B64" $newKeystoreB64
    Set-ProtectedSecret "CYCLONE_ANDROID_STORE_PASSWORD" $newStorePassword
    Set-ProtectedSecret "CYCLONE_ANDROID_KEY_ALIAS" $newAlias
    Set-ProtectedSecret "CYCLONE_ANDROID_KEY_PASSWORD" $newKeyPassword
    Set-ProtectedSecret "CYCLONE_ANDROID_SIGNING_LINEAGE_B64" $lineageB64

    Write-Host "Protected secrets configured. Dispatching V5 signing for exact source CI run $BuildRunId..."
    & $gh workflow run mobile-release.yml --repo $Repo --ref $ReleaseBranch `
        -f "build_run_id=$BuildRunId" -f "artifact_name=$ArtifactName"
    if ($LASTEXITCODE -ne 0) { throw "Could not dispatch V5 protected signing workflow." }
    Write-Host "Inspect the Cyclone Mobile Release Signing run before using its signed artifact."
    Write-Host "Back up the new keystore, lineage, and DPAPI password backup offline. Never commit them."
}
finally {
    Remove-Item $oldKeystore -Force -ErrorAction SilentlyContinue
    if ($priorDir) { Remove-Item $priorDir -Recurse -Force -ErrorAction SilentlyContinue }
    Remove-Item Env:CYCLONE_OLD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_OLD_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_NEW_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
}
