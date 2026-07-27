$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    & python scripts\check-build-environment.py --android
    if ($LASTEXITCODE -ne 0) { throw 'Build environment check failed' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-wrapper-bootstrap.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper check failed' }
    $env:TZ = 'UTC'
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $Root '.gradle-reproducible' }
    & .\gradlew.bat --no-daemon --no-build-cache --no-parallel --refresh-dependencies help
    if ($LASTEXITCODE -ne 0) { throw 'Dependency bootstrap failed' }
    & .\gradlew.bat --no-daemon --no-build-cache --no-parallel :fixture-basic:assembleRelease :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Android cache bootstrap build failed' }
    Write-Host "PASS populated locked Gradle cache at $env:GRADLE_USER_HOME"
} finally { Pop-Location }
