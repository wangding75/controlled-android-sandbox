param([switch]$Online, [switch]$VerifyTwice)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    if (git status --short --untracked-files=all) { throw 'Reproducible Android build requires a clean Git worktree' }
    & python scripts\check-build-environment.py --android
    if ($LASTEXITCODE -ne 0) { throw 'Build environment check failed' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-wrapper-bootstrap.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper check failed' }
    $env:SOURCE_DATE_EPOCH = (git log -1 --format=%ct)
    $env:TZ = 'UTC'
    $env:GRADLE_USER_HOME = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $Root '.gradle-reproducible' }
    $GradleArgs = @('--no-daemon','--no-build-cache','--no-parallel','--stacktrace')
    if (-not $Online) { $GradleArgs += '--offline' }
    $Tasks = @('clean','check',':fixture-basic:assembleRelease',':app:assembleRelease',':sandbox-companion32:assembleRelease')
    $Commit = (git rev-parse --short=12 HEAD)
    $Out = Join-Path $Root "build\reproducible\$Commit"
    Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $Out -Force | Out-Null
    function Invoke-DeterministicBuild([string]$Label) {
        & .\gradlew.bat @GradleArgs @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $Label" }
        $Target = Join-Path $Out $Label
        New-Item -ItemType Directory -Path $Target -Force | Out-Null
        Get-ChildItem app\build\outputs\apk, fixture-basic\build\outputs\apk, sandbox-companion32\build\outputs\apk -Recurse -Filter *.apk |
            Sort-Object FullName | ForEach-Object { Copy-Item $_.FullName (Join-Path $Target $_.Name) }
        Get-ChildItem $Target -Filter *.apk | Sort-Object Name | ForEach-Object {
            "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant(), $_.Name
        } | Set-Content -Encoding ascii (Join-Path $Target 'SHA256SUMS.txt')
    }
    Invoke-DeterministicBuild 'first'
    if ($VerifyTwice) {
        Invoke-DeterministicBuild 'second'
        $First = Get-ChildItem (Join-Path $Out 'first') -Filter *.apk | Sort-Object Name
        foreach ($Item in $First) {
            $Other = Join-Path (Join-Path $Out 'second') $Item.Name
            if ((Get-FileHash $Item.FullName).Hash -ne (Get-FileHash $Other).Hash) { throw "Non-reproducible APK: $($Item.Name)" }
        }
    }
    Write-Host "PASS reproducible Android build: $Out"
} finally { Pop-Location }
