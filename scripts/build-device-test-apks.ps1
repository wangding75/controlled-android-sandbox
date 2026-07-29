param([switch]$Online, [switch]$NoClean)
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
    $GradleArgs = @('--no-daemon','--no-build-cache','--no-parallel','--stacktrace')
    if (-not $Online) { $GradleArgs += '--offline' }
    $Lock = Get-Content build-environment.lock.json -Raw | ConvertFrom-Json
    $Tasks = @()
    if (-not $NoClean) { $Tasks += 'clean' }
    $Tasks += 'check'
    $Tasks += @($Lock.deviceTestBuild.artifacts | ForEach-Object { $_.gradleTask })
    & .\gradlew.bat @GradleArgs @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    $Commit = (git rev-parse --short=12 HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $Commit) { $Commit = 'source-archive' } else { $Commit = $Commit.Trim() }
    $Out = Join-Path $Root "artifacts\m5-device-test-build\$Commit"
    Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
    & python scripts\verify-device-test-artifacts.py --android-tools --output $Out
    if ($LASTEXITCODE -ne 0) { throw 'Device-test APK verification failed' }
    Write-Host "PASS M5 device-test APK build: $Out"
} finally { Pop-Location }
