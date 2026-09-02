$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    & python scripts\check-build-environment.py --android
    if ($LASTEXITCODE -ne 0) { throw 'Build environment check failed' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-wrapper-bootstrap.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper check failed' }
    & .\gradlew.bat --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
