$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-device-test-apks.ps1 -Online
    if ($LASTEXITCODE -ne 0) { throw "M5 debug APK build failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
