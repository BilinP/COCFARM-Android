# Builds COCFARM for Android, signed with the release key, as release\COCFARM-android.apk (every
# phone) and COCFARM-android-arm64.apk / COCFARM-android-x86_64.apk (one processor each, half the size).
#
#   .\release.ps1                  build it and check its signature
#   .\release.ps1 -Upload v0.6.2   also attach it to that GitHub release (it must exist)
#
# The key: "release.keystore=" in local.properties names its keystore.properties (see
# app\build.gradle.kts). Phones update COCFARM from the release's app for their processor (versions
# before 17 Nov 2026: COCFARM-android.apk), and only
# when the app's version (farmbot\__init__.py) is newer than theirs and the key is the same.
param([string]$Upload = "")

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw "The build failed." }

New-Item -ItemType Directory -Force release | Out-Null
$apks = @{ "universal" = "release\COCFARM-android.apk"; "arm64" = "release\COCFARM-android-arm64.apk";
           "x86" = "release\COCFARM-android-x86_64.apk" }
foreach ($flavor in $apks.Keys) {
    Copy-Item "app\build\outputs\apk\$flavor\release\app-$flavor-release.apk" $apks[$flavor] -Force
}

# Signed with the release key (not the debug one)?
# (local.properties escapes it: "C\:\\Users\\...")
$sdk = (Get-Content local.properties | Where-Object { $_ -like "sdk.dir=*" }) -replace "^sdk.dir=", "" -replace '\\(.)', '$1'
$tools = Get-ChildItem (Join-Path $sdk "build-tools") | Sort-Object Name | Select-Object -Last 1
$version = ((Get-Content ..\farmbot\__init__.py) -match '__version__' -replace '.*"(.*)".*', '$1')
foreach ($apk in $apks.Values) {
    $certs = & (Join-Path $tools.FullName "apksigner.bat") verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw "$apk's signature doesn't check out." }
    if (-not ($certs -match "CN=COCFARM")) { throw "$apk isn't signed with COCFARM's release key:`n$certs" }
    $size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    Write-Host "COCFARM $version for Android: $apk ($size MB)"
    Write-Host "SHA-256 $hash"
}

if ($Upload) {
    gh release upload $Upload @($apks.Values) --clobber
    if ($LASTEXITCODE -ne 0) { throw "Couldn't attach it to the release $Upload." }
    Write-Host "Attached to $Upload."
}
