param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$AvdName = 'ControlledSandbox_API30',
    [string]$SystemImage = 'system-images;android-30;google_apis;x86_64',
    [switch]$SkipSdkInstall
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$SdkManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$AvdManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\avdmanager.bat'
$Adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$Emulator = Join-Path $AndroidSdk 'emulator\emulator.exe'

foreach ($Tool in @($SdkManager, $AvdManager)) {
    if (-not (Test-Path $Tool)) { throw "Missing Android command-line tool: $Tool" }
}

if (-not $SkipSdkInstall) {
    & $SdkManager --install 'platform-tools' 'emulator' 'platforms;android-36' 'build-tools;35.0.0' $SystemImage
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed: $LASTEXITCODE" }
}
foreach ($Tool in @($Adb, $Emulator)) {
    if (-not (Test-Path $Tool)) { throw "Missing Android tool after installation: $Tool" }
}

$Existing = & $Emulator -list-avds
if ($Existing -notcontains $AvdName) {
    'no' | & $AvdManager create avd --force --name $AvdName --package $SystemImage --device 'pixel_6'
    if ($LASTEXITCODE -ne 0) { throw "avdmanager failed: $LASTEXITCODE" }
}

$Evidence = Join-Path $Root ('artifacts\emulator-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item $Evidence -ItemType Directory -Force | Out-Null
$EmulatorProcess = Start-Process $Emulator -ArgumentList @("@$AvdName", '-no-snapshot', '-no-audio', '-gpu', 'swiftshader_indirect') -PassThru
try {
    & $Adb wait-for-device
    $Deadline = (Get-Date).AddMinutes(8)
    do {
        Start-Sleep -Seconds 3
        $Boot = (& $Adb shell getprop sys.boot_completed 2>$null).Trim()
        if ((Get-Date) -gt $Deadline) { throw 'Android Emulator boot timeout' }
    } until ($Boot -eq '1')

    Push-Location $Root
    try {
        & .\gradlew.bat clean check :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    } finally { Pop-Location }

    $Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    & $Adb install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw "APK installation failed: $LASTEXITCODE" }
    & $Adb logcat -c
    & $Adb shell am start -W -n 'com.warden.controlledsandbox.debug/com.warden.controlledsandbox.MainActivity' |
        Tee-Object (Join-Path $Evidence 'activity-start.txt')
    Start-Sleep -Seconds 20
    & $Adb shell pidof com.warden.controlledsandbox.debug | Out-File (Join-Path $Evidence 'host-pid.txt') -Encoding utf8
    & $Adb shell ps -A | Select-String 'controlledsandbox' | Out-File (Join-Path $Evidence 'processes.txt') -Encoding utf8
    & $Adb logcat -d -v threadtime | Out-File (Join-Path $Evidence 'logcat.txt') -Encoding utf8
    & $Adb shell dumpsys activity processes | Out-File (Join-Path $Evidence 'activity-processes.txt') -Encoding utf8
    Write-Host "PASS emulator smoke test. Evidence: $Evidence"
} finally {
    if (-not $EmulatorProcess.HasExited) {
        & $Adb emu kill 2>$null
    }
}
