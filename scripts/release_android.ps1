# Build and verify signed release APKs, then write SHA-256 checksums.
#
#   scripts\release_android.ps1
#
# Requires a clean working tree and a keystore configured in apps/android/local.properties
# (monet.release.storeFile, monet.release.keyAlias, monet.release.storePassword, monet.release.keyPassword).
# Output: apps/android/app/build/outputs/apk/release/*.apk plus SHA256SUMS.txt.
$ErrorActionPreference = "Stop"

$repo = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $repo "apps\android"
$releaseDir = Join-Path $androidDir "app\build\outputs\apk\release"

$dirty = git -C $repo status --porcelain
if ($dirty) {
    throw "Working tree is not clean; commit first."
}

Push-Location $androidDir
try {
    & ".\gradlew.bat" clean assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$apksigner = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" }
if (-not $apksigner -or -not (Test-Path $apksigner)) {
    throw "apksigner.bat not found under $env:ANDROID_HOME\build-tools"
}

$apks = Get-ChildItem $releaseDir -Filter "*.apk"
if (-not $apks) {
    throw "No APKs found in $releaseDir"
}

foreach ($apk in $apks) {
    & $apksigner verify --print-certs $apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Unsigned or invalid APK: $($apk.Name)"
    }
}

$sums = foreach ($apk in $apks) {
    $hash = (Get-FileHash -Algorithm SHA256 $apk.FullName).Hash.ToLowerInvariant()
    "$hash  $($apk.Name)"
}
Set-Content -Path (Join-Path $releaseDir "SHA256SUMS.txt") -Value $sums -Encoding ASCII

foreach ($apk in $apks) {
    $mb = [math]::Round($apk.Length / 1MB, 2)
    Write-Output "$($apk.Name) ($mb MB)"
}

Push-Location $androidDir
try {
    $versionLine = & ".\gradlew.bat" -q :app:printVersion
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle printVersion failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
$versionName = ($versionLine | Select-Object -First 1).Split(" ")[1]

Write-Output "Release OK: $versionName"
