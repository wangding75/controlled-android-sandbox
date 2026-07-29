param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$Serial = '',
    [string]$ArtifactDirectory = '',
    [string]$EvidenceDirectory = '',
    [int]$StabilitySeconds = 1200,
    [switch]$Diagnostic,
    [switch]$SkipBuild,
    [switch]$OnlineBuild,
    [switch]$KeepEmulator,
    [switch]$Headless
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ArgsList = @(
    (Join-Path $Root 'scripts\m5_device_lab.py'),
    '--root', $Root,
    '--android-sdk', $AndroidSdk,
    '--stability-seconds', $StabilitySeconds.ToString()
)
if (-not [string]::IsNullOrWhiteSpace($Serial)) { $ArgsList += @('--serial', $Serial) }
if (-not [string]::IsNullOrWhiteSpace($ArtifactDirectory)) { $ArgsList += @('--artifact-dir', $ArtifactDirectory) }
if (-not [string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $ArgsList += @('--evidence-dir', $EvidenceDirectory) }
if ($Diagnostic) { $ArgsList += '--diagnostic' }
if ($SkipBuild) { $ArgsList += '--skip-build' }
if ($OnlineBuild) { $ArgsList += '--online-build' }
if ($KeepEmulator) { $ArgsList += '--keep-emulator' }
if ($Headless) { $ArgsList += '--headless' }
& python @ArgsList
if ($LASTEXITCODE -ne 0) { throw "M5 device lab failed with exit code $LASTEXITCODE" }
