# Idempotent, portable toolchain setup. Everything lands in D:\Monet\.tools (gitignored).
#   powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1 [-WithEmulator]
param([switch]$WithEmulator)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root  = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root '.tools'
$bin   = Join-Path $tools 'bin'
$dl    = Join-Path $tools 'downloads'
New-Item -ItemType Directory -Force $tools, $bin, $dl | Out-Null

function Get-File($url, $out) {
  if (-not (Test-Path $out)) {
    Write-Host "  downloading $url"
    curl.exe -fsSL -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
  }
}

# --- JDK 21 (Gradle/AGP need <= 23; the system JDK 25 is too new) -----------------
$jdk = Join-Path $tools 'jdk-21'
if (-not (Test-Path "$jdk\bin\java.exe")) {
  Write-Host 'JDK 21'
  $zip = Join-Path $dl 'jdk21.zip'
  Get-File 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $zip
  $tmp = Join-Path $dl 'jdk21'
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  New-Item -ItemType Directory $tmp | Out-Null
  tar -xf $zip -C $tmp
  Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $jdk
}
$env:JAVA_HOME = $jdk

# --- uv (Python toolchain manager) --------------------------------------------------
if (-not (Test-Path "$bin\uv.exe")) {
  Write-Host 'uv'
  $zip = Join-Path $dl 'uv.zip'
  Get-File 'https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip' $zip
  tar -xf $zip -C $bin
}
$env:UV_PYTHON_INSTALL_DIR = Join-Path $tools 'python'
$env:UV_CACHE_DIR = Join-Path $tools 'uv-cache'
& "$bin\uv.exe" python install 3.12 | Out-Null

# --- GitHub CLI ---------------------------------------------------------------------
if (-not (Test-Path "$bin\gh.exe")) {
  Write-Host 'gh'
  $rel = Invoke-RestMethod 'https://api.github.com/repos/cli/cli/releases/latest'
  $asset = $rel.assets | Where-Object { $_.name -like '*windows_amd64.zip' } | Select-Object -First 1
  $zip = Join-Path $dl 'gh.zip'
  Get-File $asset.browser_download_url $zip
  $tmp = Join-Path $dl 'gh'
  New-Item -ItemType Directory -Force $tmp | Out-Null
  tar -xf $zip -C $tmp
  Copy-Item (Get-ChildItem $tmp -Recurse -Filter gh.exe | Select-Object -First 1).FullName $bin
}

# --- Android SDK ----------------------------------------------------------------------
$sdk = Join-Path $tools 'android-sdk'
$sdkm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkm)) {
  Write-Host 'Android cmdline-tools'
  $zip = Join-Path $dl 'cmdline-tools.zip'
  Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
  $tmp = Join-Path $dl 'cmdline-tools'
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  New-Item -ItemType Directory $tmp | Out-Null
  tar -xf $zip -C $tmp
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Move-Item "$tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
}
$env:ANDROID_HOME = $sdk
$pkgs = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
if ($WithEmulator) { $pkgs += @('emulator', 'system-images;android-35;google_apis;x86_64') }
Write-Host "Android packages: $($pkgs -join ', ')"
# Piping "y" into sdkmanager.bat is unreliable from PowerShell, so write the accepted-license
# hashes directly (the same files `sdkmanager --licenses` produces).
$licenses = @{
  'android-sdk-license'           = '24333f8a63b6825ea9c5514f83c2829b004d1fee'
  'android-sdk-preview-license'   = '84831b9409646a918e30573bab4c9c91346d8abd'
  'android-sdk-arm-dbt-license'   = '859f317696f67ef3d7f30a50a5560e7834b43903'
  'android-googletv-license'      = '601085b94cd77f0b54ff86406957099ebe79c4d6'
  'android-googlexr-license'      = 'ceff83576aac4f7f37cb98fe189e9fb3c49d3b81'
  'google-gdk-license'            = '33b6a2b64607f11b759f320ef9dff4ae5c47d97a'
  'microxr-sysimage-license'      = '3a7848b1d7366ca5cfa0be477627644ca73f47e3'
  'mips-android-sysimage-license' = 'e9acab5b5fbb560a72cfaecce8946896ff6aab9d'
}
New-Item -ItemType Directory -Force "$sdk\licenses" | Out-Null
foreach ($k in $licenses.Keys) { [IO.File]::WriteAllText("$sdk\licenses\$k", "`n" + $licenses[$k]) }
& $sdkm --sdk_root=$sdk @pkgs
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }
foreach ($exe in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll') {
  Copy-Item "$sdk\platform-tools\$exe" $bin -Force
}

if ($WithEmulator -and -not (Test-Path (Join-Path $tools 'avd\monet.avd'))) {
  $env:ANDROID_AVD_HOME = Join-Path $tools 'avd'
  New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
  'no' | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n monet -k 'system-images;android-35;google_apis;x86_64' -d pixel_7 | Out-Null
}

Write-Host "`nDone. Tools in $tools"
& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1
& "$bin\uv.exe" --version
& "$bin\gh.exe" --version | Select-Object -First 1
& "$bin\adb.exe" version | Select-Object -First 1
