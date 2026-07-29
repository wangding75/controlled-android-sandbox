param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$CacheDirectory = '',
    [string]$CommandLineToolsArchive = '',
    [switch]$Online,
    [switch]$AcceptLicenses,
    [switch]$SkipSdkPackages
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if ([string]::IsNullOrWhiteSpace($CacheDirectory)) {
    $CacheDirectory = Join-Path $Root '.toolchain-cache'
}
$AndroidSdk = [IO.Path]::GetFullPath($AndroidSdk)
$CacheDirectory = [IO.Path]::GetFullPath($CacheDirectory)
$Lock = Get-Content (Join-Path $Root 'build-environment.lock.json') -Raw | ConvertFrom-Json
$Cli = $Lock.deviceLab.commandLineTools.windows
$ExpectedArchive = Join-Path $CacheDirectory $Cli.filename

function Assert-Java17 {
    $text = (& java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $text -notmatch 'version "17(?:\.|\")') {
        throw "M5 device lab requires JDK 17 on PATH/JAVA_HOME. Observed: $($text.Trim())"
    }
}

function Assert-Sha256([string]$Path, [string]$Expected) {
    if (-not (Test-Path $Path -PathType Leaf)) { throw "Missing archive: $Path" }
    $actual = (Get-FileHash $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, found $actual"
    }
}

Assert-Java17
New-Item $AndroidSdk -ItemType Directory -Force | Out-Null
New-Item $CacheDirectory -ItemType Directory -Force | Out-Null
$SdkManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    if ([string]::IsNullOrWhiteSpace($CommandLineToolsArchive)) {
        $CommandLineToolsArchive = $ExpectedArchive
    }
    if (-not (Test-Path $CommandLineToolsArchive)) {
        if (-not $Online) {
            throw "Command-line tools archive is unavailable. Place $($Cli.filename) in $CacheDirectory or pass -Online."
        }
        Invoke-WebRequest -Uri $Cli.url -OutFile $CommandLineToolsArchive
    }
    Assert-Sha256 $CommandLineToolsArchive $Cli.sha256
    $Temp = Join-Path $env:TEMP ('controlled-sandbox-cli-' + [guid]::NewGuid())
    New-Item $Temp -ItemType Directory -Force | Out-Null
    try {
        Expand-Archive -Path $CommandLineToolsArchive -DestinationPath $Temp -Force
        $Source = Join-Path $Temp 'cmdline-tools'
        if (-not (Test-Path (Join-Path $Source 'bin\sdkmanager.bat'))) {
            throw "Unexpected Android command-line tools archive layout: $CommandLineToolsArchive"
        }
        $Latest = Join-Path $AndroidSdk 'cmdline-tools\latest'
        Remove-Item $Latest -Recurse -Force -ErrorAction SilentlyContinue
        New-Item $Latest -ItemType Directory -Force | Out-Null
        Get-ChildItem $Source -Force | ForEach-Object { Move-Item $_.FullName $Latest -Force }
    } finally {
        Remove-Item $Temp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
if (-not (Test-Path $SdkManager)) { throw "sdkmanager installation failed: $SdkManager" }

$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_HOME = $AndroidSdk
if (-not $SkipSdkPackages) {
    $Packages = @($Lock.toolchain.android.sdkPackages) + @($Lock.deviceLab.sdkPackages) | Select-Object -Unique
    if ($AcceptLicenses) {
        $licenseInput = (1..200 | ForEach-Object { 'y' }) -join [Environment]::NewLine
        $licenseInput | & $SdkManager --sdk_root=$AndroidSdk --licenses | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "sdkmanager license acceptance failed: $LASTEXITCODE" }
    }
    & $SdkManager --sdk_root=$AndroidSdk @Packages
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager package installation failed: $LASTEXITCODE" }
}

$sdkEscaped = $AndroidSdk.Replace('\', '\\').Replace(':', '\:')
"sdk.dir=$sdkEscaped" | Set-Content (Join-Path $Root 'local.properties') -Encoding ascii
& python (Join-Path $Root 'scripts\check-build-environment.py') --android
if ($LASTEXITCODE -ne 0) { throw 'Locked Android build environment validation failed' }

$Evidence = Join-Path $Root 'artifacts\m5-device-lab-toolchain'
New-Item $Evidence -ItemType Directory -Force | Out-Null
$javaVersion = (& java -version 2>&1 | Out-String).Trim()
$record = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    androidSdk = $AndroidSdk
    commandLineToolsVersion = $Lock.deviceLab.commandLineTools.version
    commandLineToolsSha256 = $Cli.sha256
    javaVersion = $javaVersion
    sdkPackages = @($Lock.toolchain.android.sdkPackages) + @($Lock.deviceLab.sdkPackages) | Select-Object -Unique
}
$record | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Evidence 'toolchain.json') -Encoding utf8
Write-Host "PASS M5 device-lab toolchain bootstrap: $AndroidSdk"
