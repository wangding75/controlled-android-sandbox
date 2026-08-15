param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-06-framework-transport-probe'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'
$peerGuestPackage = 'com.warden.controlledsandbox.fixture32'
$activity = 'com.warden.controlledsandbox.fixture.FrameworkProbeActivity'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Invoke-AdbChecked([string[]]$Arguments) {
    $output = & adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB_COMMAND_FAILED:$($Arguments -join ' ')"
    }
    return $output
}

function Read-CommandResult([object]$Device, [datetime]$Deadline) {
    while ((Get-Date) -lt $Deadline) {
        & adb -s $Device.Serial shell run-as $hostPackage test -f files/debug-command-result.json 2>$null
        if ($LASTEXITCODE -eq 0) {
            $content = (& adb -s $Device.Serial shell run-as $hostPackage cat files/debug-command-result.json 2>$null |
                Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and $content.StartsWith('{')) {
                return ($content | ConvertFrom-Json)
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'DEBUG_COMMAND_RESULT_TIMEOUT'
}

try {
    $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $Serial
    $hostApk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $companionApk = Join-Path $repoRoot 'sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk'
    $guestApk = Join-Path $repoRoot 'fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk'
    $peerGuestApk = Join-Path $repoRoot 'fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk'
    if (-not (Test-Path -LiteralPath $hostApk)) { throw "HOST_APK_MISSING:$hostApk" }
    if (-not (Test-Path -LiteralPath $companionApk)) { throw "COMPANION_APK_MISSING:$companionApk" }
    if (-not (Test-Path -LiteralPath $guestApk)) { throw "GUEST_APK_MISSING:$guestApk" }
    if (-not (Test-Path -LiteralPath $peerGuestApk)) { throw "PEER_GUEST_APK_MISSING:$peerGuestApk" }

    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $hostApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $companionApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $peerGuestApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $guestApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'import-prepare', '--es', 'package', $peerGuestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true') | Out-Null
    $peerPrepare = Read-CommandResult $device ((Get-Date).AddSeconds(45))
    if ($peerPrepare.status -ne 'PASS') {
        throw "PEER_IMPORT_PREPARE_FAILED:$($peerPrepare | ConvertTo-Json -Compress)"
    }

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'import-prepare', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true') | Out-Null
    $prepare = Read-CommandResult $device ((Get-Date).AddSeconds(45))
    if ($prepare.status -ne 'PASS') { throw "IMPORT_PREPARE_FAILED:$($prepare | ConvertTo-Json -Compress)" }

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'launch-component', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--es', 'component', $activity) | Out-Null
    $launch = Read-CommandResult $device ((Get-Date).AddSeconds(60))
    if ($launch.status -ne 'PASS' -or $launch.operation.status -ne 'LAUNCH_PASS') {
        throw "LAUNCH_COMMAND_FAILED:$($launch | ConvertTo-Json -Compress)"
    }

    $requiredMarkers = @(
        'FRAMEWORK_PROBE_PROVIDER_BULK_PASS',
        'FRAMEWORK_PROBE_PROVIDER_BATCH_PASS',
        'FRAMEWORK_PROBE_PENDING_INTENT_PASS',
        'FRAMEWORK_PROBE_SERVICE_BIND_PASS',
        'FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS',
        'FRAMEWORK_PROBE_REMOTE_ROUTE_REQUESTED',
        'FRAMEWORK_PROBE_REMOTE_STOP_PASS',
        'FRAMEWORK_PROBE_PASS',
        'VIRTUAL_PENDING_INTENT_DELIVERY status=LAUNCH_PASS'
    )
    $log = ''
    $markerDeadline = (Get-Date).AddSeconds(15)
    do {
        $log = (& adb -s $device.Serial logcat -d -v threadtime | Out-String)
        $missing = @($requiredMarkers | Where-Object {
            $log -notmatch [regex]::Escape($_)
        })
        if ($missing.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $markerDeadline)
    foreach ($marker in $missing) {
        throw "PROBE_MARKER_MISSING:$marker"
    }
    if ($log -notmatch 'DETAIL_CREATE .*process=com\.warden\.controlledsandbox\.fixture:remote') {
        throw 'REMOTE_ACTIVITY_PROCESS_MARKER_MISSING'
    }
    if ($log -notmatch 'SERVICE_CREATE .*RemoteFixtureService process=com\.warden\.controlledsandbox\.fixture:remote') {
        throw 'REMOTE_SERVICE_PROCESS_MARKER_MISSING'
    }
    if ($log -notmatch 'GUEST_PREPARED .*processName=com\.warden\.controlledsandbox\.fixture:remote .*slot=') {
        throw 'REMOTE_SESSION_PREPARE_MARKER_MISSING'
    }
    if ($log -match 'NO_GUEST_SERVICE_MATCH|LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in') {
        throw 'PROBE_FATAL_MARKER_PRESENT'
    }

    $record = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $log | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
