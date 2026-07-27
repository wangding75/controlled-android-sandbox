param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$Serial = '',
    [string]$AvdName = 'ControlledSandbox_API35',
    [string]$SystemImage = 'system-images;android-35;google_apis;x86_64',
    [int]$StabilityMinutes = 20,
    [switch]$SkipSdkInstall,
    [switch]$SkipBuild,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$SdkManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$AvdManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\avdmanager.bat'
$Adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$Emulator = Join-Path $AndroidSdk 'emulator\emulator.exe'
$HostPackage = 'com.warden.controlledsandbox.debug'
$FixturePackage = 'com.warden.controlledsandbox.fixture'
$CommandActivity = "$HostPackage/com.warden.controlledsandbox.DebugCommandActivity"
$Evidence = Join-Path $Root ('artifacts\m3-emulator-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item $Evidence -ItemType Directory -Force | Out-Null
$EmulatorProcess = $null
$StartedAt = Get-Date

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
    $all = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) { $all += @('-s', $Serial) }
    $all += $Args
    & $Adb @all
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): $($Args -join ' ')" }
}

function Wait-Boot {
    Invoke-Adb wait-for-device | Out-Null
    $deadline = (Get-Date).AddMinutes(10)
    do {
        Start-Sleep -Seconds 3
        $boot = (Invoke-Adb shell getprop sys.boot_completed | Out-String).Trim()
        if ((Get-Date) -gt $deadline) { throw 'Android Emulator boot timeout' }
    } until ($boot -eq '1')
}

function Invoke-GuestCommand {
    param([string]$Command, [int]$VirtualUserId = 0)
    Invoke-Adb shell run-as $HostPackage rm -f files/debug-command-result.json 2>$null | Out-Null
    Invoke-Adb shell am start -W -n $CommandActivity --es command $Command --es package $FixturePackage --ei user $VirtualUserId |
        Out-File (Join-Path $Evidence ("command-$Command-u$VirtualUserId-start.txt")) -Encoding utf8
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 750
        $adbPrefix = @()
        if (-not [string]::IsNullOrWhiteSpace($Serial)) { $adbPrefix += @('-s', $Serial) }
        $json = (& $Adb @adbPrefix shell run-as $HostPackage cat files/debug-command-result.json 2>$null | Out-String).Trim()
        if ((Get-Date) -gt $deadline) { throw "Command result timeout: $Command" }
    } until ($json.StartsWith('{'))
    $json | Out-File (Join-Path $Evidence ("command-$Command-u$VirtualUserId-result.json")) -Encoding utf8
    $result = $json | ConvertFrom-Json
    if ($result.status -ne 'PASS') { throw "Guest command failed: $Command - $($result.errorType): $($result.errorMessage)" }
    return $result
}

try {
    foreach ($tool in @($SdkManager, $AvdManager)) {
        if (-not (Test-Path $tool)) { throw "Missing Android command-line tool: $tool" }
    }
    if (-not $SkipSdkInstall) {
        & $SdkManager --install 'platform-tools' 'emulator' 'platforms;android-36' 'build-tools;35.0.0' $SystemImage
        if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed: $LASTEXITCODE" }
    }
    foreach ($tool in @($Adb, $Emulator)) { if (-not (Test-Path $tool)) { throw "Missing Android tool: $tool" } }

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        $online = & $Adb devices | Select-String "`tdevice$" | Select-Object -First 1
        if ($online) {
            $Serial = (($online -split "`t")[0]).Trim()
        } else {
            $existing = & $Emulator -list-avds
            if ($existing -notcontains $AvdName) {
                'no' | & $AvdManager create avd --force --name $AvdName --package $SystemImage --device 'pixel_6'
                if ($LASTEXITCODE -ne 0) { throw "avdmanager failed: $LASTEXITCODE" }
            }
            $EmulatorProcess = Start-Process $Emulator -ArgumentList @("@$AvdName", '-no-snapshot', '-no-audio', '-gpu', 'swiftshader_indirect', '-no-boot-anim') -PassThru
        }
    }
    Wait-Boot

    if (-not $SkipBuild) {
        Push-Location $Root
        try {
            & .\gradlew.bat clean check :fixture-basic:assembleDebug :app:assembleDebug
            if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
        } finally { Pop-Location }
    }

    $FixtureApk = Join-Path $Root 'fixture-basic\build\outputs\apk\debug\fixture-basic-debug.apk'
    $HostApk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    foreach ($apk in @($FixtureApk, $HostApk)) { if (-not (Test-Path $apk)) { throw "Missing APK: $apk" } }
    Invoke-Adb install -r $FixtureApk | Out-File (Join-Path $Evidence 'install-fixture.txt') -Encoding utf8
    Invoke-Adb install -r $HostApk | Out-File (Join-Path $Evidence 'install-host.txt') -Encoding utf8
    Invoke-Adb logcat -c | Out-Null

    $device = [ordered]@{
        serial = $Serial
        sdk = (Invoke-Adb shell getprop ro.build.version.sdk | Out-String).Trim()
        release = (Invoke-Adb shell getprop ro.build.version.release | Out-String).Trim()
        abi = (Invoke-Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
        fingerprint = (Invoke-Adb shell getprop ro.build.fingerprint | Out-String).Trim()
    }
    $device | ConvertTo-Json | Out-File (Join-Path $Evidence 'device.json') -Encoding utf8

    $prepare = Invoke-GuestCommand 'import-prepare' 0
    $components = Invoke-GuestCommand 'component-suite' 0
    $launch = Invoke-GuestCommand 'launch' 0
    $clonePrepare = Invoke-GuestCommand 'prepare' 1
    $cloneLaunch = Invoke-GuestCommand 'launch' 1
    Start-Sleep -Seconds 5

    $deadline = (Get-Date).AddMinutes([Math]::Max(0, $StabilityMinutes))
    $iteration = 0
    while ((Get-Date) -lt $deadline) {
        $iteration++
        Invoke-Adb shell input keyevent 3 | Out-Null
        Start-Sleep -Seconds 2
        Invoke-GuestCommand 'launch' ($iteration % 2) | Out-Null
        Start-Sleep -Seconds 8
        if (($iteration % 3) -eq 0) { Invoke-GuestCommand 'component-suite' ($iteration % 2) | Out-Null }
    }

    Invoke-Adb shell ps -A | Select-String 'controlledsandbox' | Out-File (Join-Path $Evidence 'processes.txt') -Encoding utf8
    Invoke-Adb shell dumpsys activity activities | Out-File (Join-Path $Evidence 'activities.txt') -Encoding utf8
    Invoke-Adb shell dumpsys activity services $HostPackage | Out-File (Join-Path $Evidence 'services.txt') -Encoding utf8
    Invoke-Adb shell dumpsys meminfo $HostPackage | Out-File (Join-Path $Evidence 'meminfo.txt') -Encoding utf8
    Invoke-Adb shell run-as $HostPackage find files/instances -maxdepth 4 -type d | Out-File (Join-Path $Evidence 'instance-directories.txt') -Encoding utf8
    $diagnosticFiles = @(Invoke-Adb shell run-as $HostPackage find files/runtime-diagnostics -maxdepth 1 -type f -print |
        ForEach-Object { $_.ToString().Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $diagnosticFiles | Out-File (Join-Path $Evidence 'diagnostic-files.txt') -Encoding utf8
    $diagnosticContent = Join-Path $Evidence 'runtime-diagnostics.jsonl.txt'
    foreach ($diagnosticFile in $diagnosticFiles) {
        "=== $diagnosticFile ===" | Out-File $diagnosticContent -Encoding utf8 -Append
        Invoke-Adb shell run-as $HostPackage cat $diagnosticFile | Out-File $diagnosticContent -Encoding utf8 -Append
    }
    Invoke-Adb logcat -d -v threadtime | Out-File (Join-Path $Evidence 'logcat.txt') -Encoding utf8

    $log = Get-Content (Join-Path $Evidence 'logcat.txt') -Raw
    $guestActivityCreateCount = [regex]::Matches($log, 'CS_RUNTIME.*GUEST_ACTIVITY_CREATE.*status=ACTIVITY_CREATED').Count
    $activityCreated = $guestActivityCreateCount -ge 2
    $fixtureCreated = $log -match 'CS_FIXTURE.*ACTIVITY_CREATE'
    $nativeProbe = $log -match 'CS_FIXTURE.*NATIVE_PROBE JNI_OK'
    $fatal = $log -match '(FATAL EXCEPTION|ANR in com\.warden\.controlledsandbox|Fatal signal.*controlledsandbox)'
    $processText = Get-Content (Join-Path $Evidence 'processes.txt') -Raw
    $guestProcessMatches = [regex]::Matches($processText, ':guest[0-7]')
    $guestProcessCount = ($guestProcessMatches | ForEach-Object { $_.Value } | Sort-Object -Unique).Count
    $guestProcess = $guestProcessCount -ge 2
    $instanceText = Get-Content (Join-Path $Evidence 'instance-directories.txt') -Raw
    $fixturePattern = [regex]::Escape($FixturePackage)
    $instanceDataIsolated = ($instanceText -match ("instances/u0/.+" + $fixturePattern)) -and ($instanceText -match ("instances/u1/.+" + $fixturePattern))
    $diagnosticsCollected = $diagnosticFiles.Count -gt 0 -and (Test-Path $diagnosticContent) -and ((Get-Item $diagnosticContent).Length -gt 0)
    $componentSuitePassed = $components.status -eq 'PASS'
    $durationSeconds = [int]((Get-Date) - $StartedAt).TotalSeconds
    $gatePass = $activityCreated -and $fixtureCreated -and $nativeProbe -and $guestProcess -and $instanceDataIsolated -and $diagnosticsCollected -and $componentSuitePassed -and (-not $fatal) -and ($StabilityMinutes -lt 20 -or $durationSeconds -ge 1200)
    $gate = [ordered]@{
        status = $(if ($gatePass) { 'PASS' } else { 'FAIL' })
        activityCreated = $activityCreated
        guestActivityCreateCount = $guestActivityCreateCount
        fixtureActivityCreated = $fixtureCreated
        nativeFixtureProbe = $nativeProbe
        guestProcessObserved = $guestProcess
        guestProcessCount = $guestProcessCount
        multiInstanceDataRoots = $instanceDataIsolated
        componentSuitePassed = $componentSuitePassed
        diagnosticsCollected = $diagnosticsCollected
        diagnosticFileCount = $diagnosticFiles.Count
        fatalCrashOrAnr = $fatal
        durationSeconds = $durationSeconds
        requestedStabilityMinutes = $StabilityMinutes
        completedAt = (Get-Date).ToString('o')
    }
    $gate | ConvertTo-Json | Out-File (Join-Path $Evidence 'm3-gate.json') -Encoding utf8
    if (-not $gatePass) { throw "M3 emulator gate failed. Evidence: $Evidence" }
    Write-Host "PASS M3 emulator gate. Evidence: $Evidence"
} finally {
    if ($EmulatorProcess -and -not $KeepEmulator -and -not $EmulatorProcess.HasExited) {
        try { Invoke-Adb emu kill | Out-Null } catch { }
    }
}
