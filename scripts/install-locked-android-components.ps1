param([switch]$AcceptLicenses)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $null }
if (-not $SdkRoot) { throw 'ANDROID_SDK_ROOT or ANDROID_HOME is required' }
$Candidates = @(
    (Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'),
    (Join-Path $SdkRoot 'cmdline-tools\bin\sdkmanager.bat'),
    (Get-Command sdkmanager.bat -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
) | Where-Object { $_ -and (Test-Path $_) }
if (-not $Candidates) { throw 'Android command-line tools are required; sdkmanager.bat was not found' }
$SdkManager = $Candidates[0]
$Lock = Get-Content (Join-Path $Root 'build-environment.lock.json') -Raw | ConvertFrom-Json
$Packages = @($Lock.toolchain.android.sdkPackages)
if ($AcceptLicenses) {
    1..100 | ForEach-Object { 'y' } | & $SdkManager --sdk_root=$SdkRoot --licenses | Out-Null
}
& $SdkManager --sdk_root=$SdkRoot @Packages
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
& python (Join-Path $Root 'scripts\check-build-environment.py') --android
if ($LASTEXITCODE -ne 0) { throw 'Locked Android environment validation failed' }
Write-Host "PASS installed locked Android components under $SdkRoot"
