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

    # This probe is also invoked standalone.  Do not inherit a finishing Stub task or a
    # queued starting-window callback from a previous case: on OEM API32 builds that stale
    # callback can crash system_server while the next Activity is being paused.  The full
    # regression suite has the same boundary between cases; keeping it here makes the direct
    # transport gate an independent lifecycle transaction as well.
    foreach ($package in @($hostPackage, 'com.warden.controlledsandbox.companion32.debug',
            $guestPackage, $peerGuestPackage)) {
        Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop', $package) | Out-Null
    }
    Start-Sleep -Milliseconds 750

    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $hostApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $companionApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $peerGuestApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $guestApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W',
        '-f', '0x10008000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'import-prepare', '--es', 'package', $peerGuestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true') | Out-Null
    $peerPrepare = Read-CommandResult $device ((Get-Date).AddSeconds(45))
    if ($peerPrepare.status -ne 'PASS') {
        throw "PEER_IMPORT_PREPARE_FAILED:$($peerPrepare | ConvertTo-Json -Compress)"
    }

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W',
        '-f', '0x10008000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'import-prepare', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true') | Out-Null
    $prepare = Read-CommandResult $device ((Get-Date).AddSeconds(45))
    if ($prepare.status -ne 'PASS') { throw "IMPORT_PREPARE_FAILED:$($prepare | ConvertTo-Json -Compress)" }

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W',
        '-f', '0x10008000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'launch-component', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--es', 'component', $activity) | Out-Null
    $launch = Read-CommandResult $device ((Get-Date).AddSeconds(60))
    if ($launch.status -ne 'PASS' -or $launch.operation.status -ne 'LAUNCH_PASS') {
        throw "LAUNCH_COMMAND_FAILED:$($launch | ConvertTo-Json -Compress)"
    }

    # Exercise a real host ActivityThread ReceiverDispatcher lease. The Guest probe registered
    # DynamicFixtureReceiver during onCreate; this broadcast originates outside the Guest Broker
    # and must still arrive with Guest Context identity.
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'broadcast',
            '-a', 'com.warden.controlledsandbox.fixture.DYNAMIC_PING',
            '--es', 'frameworkDynamicReceiverValue', 'dynamic-framework-probe') | Out-Null
        Start-Sleep -Milliseconds 500
    }

    $requiredMarkers = @(
        'FRAMEWORK_PROBE_PROVIDER_BULK_PASS',
        'FRAMEWORK_PROBE_PROVIDER_BATCH_PASS',
        'FRAMEWORK_PROBE_PENDING_INTENT_PASS',
        'FRAMEWORK_PROBE_PENDING_INTENT_BINDER_PASS',
        'FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS',
        'FRAMEWORK_PROBE_NOTIFICATION_READBACK_PASS',
        'FRAMEWORK_PROBE_JOB_READBACK_PASS',
        'FRAMEWORK_PROBE_ALARM_CLOCK_READBACK_PASS',
        'FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_REQUESTED',
        'FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_PASS',
        'FRAMEWORK_PROBE_ORDERED_RECEIVER_DELIVERED',
        'FRAMEWORK_PROBE_ORDERED_RECEIVER_FRAMEWORK_PASS',
        'FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_DELIVERED',
        'FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FINISHED',
        'FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FRAMEWORK_PASS',
        'GUEST_RECEIVER_FRAMEWORK_DELIVERED',
        'FRAMEWORK_PROBE_CROSS_RECEIVER_FRAMEWORK_REQUESTED',
        'FRAMEWORK_PROBE_CROSS_RECEIVER_FRAMEWORK_PASS',
        'FRAMEWORK_PROBE_DYNAMIC_RECEIVER_FRAMEWORK_PASS',
        'GUEST_RECEIVER_FRAMEWORK_REGISTERED',
        'GUEST_RECEIVER_FRAMEWORK_DYNAMIC_DELIVERED',
        'FRAMEWORK_PROBE_SERVICE_BIND_PASS',
        'FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS',
        'FRAMEWORK_PROBE_PACKAGE_IDENTITY_PASS',
        'FRAMEWORK_PROBE_COMPONENT_METADATA_PASS',
        'FRAMEWORK_PROBE_PACKAGE_CONTEXT_PASS',
        'FRAMEWORK_PROBE_CROSS_PROVIDER_PASS',
        'FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_DELIVERED',
        'FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_PASS',
        'FRAMEWORK_PROBE_ACTIVITY_CONTRACT_PASS',
        'GUEST_ACTIVITY_PERSISTABLE_CREATE',
        'FRAMEWORK_PROBE_TASK_REUSE_LIFECYCLE',
        'FRAMEWORK_PROBE_TASK_REUSE_COUNTS',
        'FRAMEWORK_PROBE_CROSS_ACTIVITY_PASS',
        'FRAMEWORK_PROBE_CROSS_SERVICE_BIND_PASS',
        'FRAMEWORK_PROBE_CROSS_PENDING_INTENT_PASS',
        'FRAMEWORK_PROBE_CROSS_PENDING_INTENT_RECEIVED',
        'FRAMEWORK_PROBE_REMOTE_ROUTE_REQUESTED',
        'FRAMEWORK_PROBE_REMOTE_STOP_PASS',
        'FRAMEWORK_PROBE_CROSS_STOP_PASS',
        'FRAMEWORK_PROBE_PASS',
        'VIRTUAL_PENDING_INTENT_DELIVERY status=BROADCAST_DELIVERED'
    )
    $log = ''
    # Cross-package/32-bit provider work may still be draining through the Companion before
    # the remote-process Activity/Service route is delivered.  Fifteen seconds made a healthy
    # API32 RD run fail while the same markers arrived a few seconds later; keep the probe
    # bounded, but allow the real cross-process transaction to settle.
    $markerDeadline = (Get-Date).AddSeconds(45)
    do {
        $log = (& adb -s $device.Serial logcat -d -v threadtime -s CS_RUNTIME:V CS_DIAGNOSTICS:V CS_FIXTURE:V CS_COMMAND:V '*:S' | Out-String)
        $missing = @($requiredMarkers | Where-Object {
            $log -notmatch [regex]::Escape($_)
        })
        if ($missing.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $markerDeadline)
    foreach ($marker in $missing) {
        throw "PROBE_MARKER_MISSING:$marker"
    }
    if ($log -match 'FRAMEWORK_PROBE_TASK_REUSE_FAIL') {
        throw 'PROBE_TASK_REUSE_FAILED'
    }
    if ($log -notmatch 'DETAIL_CREATE .*process=com\.warden\.controlledsandbox\.fixture:remote') {
        throw 'REMOTE_ACTIVITY_PROCESS_MARKER_MISSING'
    }
    if ($log -notmatch 'SERVICE_CREATE .*RemoteFixtureService process=com\.warden\.controlledsandbox\.fixture:remote') {
        throw 'REMOTE_SERVICE_PROCESS_MARKER_MISSING'
    }
    if ($log -notmatch 'APPLICATION_CREATE .*package=com\.warden\.controlledsandbox\.fixture32') {
        throw 'CROSS_PACKAGE_APPLICATION_MARKER_MISSING'
    }
    if ($log -notmatch 'SERVICE_CREATE .*FixtureService process=com\.warden\.controlledsandbox\.fixture32') {
        throw 'CROSS_PACKAGE_SERVICE_PROCESS_MARKER_MISSING'
    }
    if ($log -notmatch 'CS_CROSS_PACKAGE_ROUTE: caller=com\.warden\.controlledsandbox\.fixture target=com\.warden\.controlledsandbox\.fixture32') {
        throw 'CROSS_PACKAGE_BROKER_ROUTE_MARKER_MISSING'
    }
    if ($log -notmatch 'CS_CROSS_ABI_ROUTE: caller=com\.warden\.controlledsandbox\.fixture target=com\.warden\.controlledsandbox\.fixture32 operation=PREPARE_PROVIDER abi=x86 provider=true delegated=true') {
        throw 'CROSS_ABI_PROVIDER_PREPARE_ROUTE_MARKER_MISSING'
    }
    if ($log -notmatch 'CS_CROSS_ABI_ROUTE: caller=com\.warden\.controlledsandbox\.fixture target=com\.warden\.controlledsandbox\.fixture32 operation=PROVIDER_QUERY abi=x86 provider=true delegated=true') {
        throw 'CROSS_ABI_PROVIDER_QUERY_ROUTE_MARKER_MISSING'
    }
    if ($log -notmatch 'CS_CROSS_ABI_ROUTE: caller=com\.warden\.controlledsandbox\.fixture target=com\.warden\.controlledsandbox\.fixture32 operation=PROVIDER_OBSERVER_REGISTER abi=x86 provider=true delegated=true') {
        throw 'CROSS_ABI_PROVIDER_OBSERVER_ROUTE_MARKER_MISSING'
    }
    if ($log -match 'CS_FIXTURE: NATIVE_LOAD JNI_UNAVAILABLE|CS_FIXTURE: NATIVE_PROBE JNI_UNAVAILABLE|unexpected e_machine') {
        throw 'CROSS_ABI_NATIVE_DOWNGRADE_MARKER_PRESENT'
    }
    $providerPrepare = [regex]::Match($log,
        'GUEST_PROVIDER_PREPARE .*package=com\.warden\.controlledsandbox\.fixture32 .*physicalPid=(\d+)')
    if (-not $providerPrepare.Success) {
        throw 'CROSS_ABI_PROVIDER_PREPARE_PID_MISSING'
    }
    $providerPid = $providerPrepare.Groups[1].Value
    if ($log -notmatch "(?m)^.*\s$providerPid\s+\S+\s+I\s+CS_FIXTURE:\s+NATIVE_LOAD JNI_LOADED") {
        throw "CROSS_ABI_PROVIDER_NATIVE_LOAD_MARKER_MISSING:pid=$providerPid"
    }
    if ($log -notmatch 'GUEST_PREPARED .*processName=com\.warden\.controlledsandbox\.fixture:remote .*slot=') {
        throw 'REMOTE_SESSION_PREPARE_MARKER_MISSING'
    }
    if ($log -match 'NO_GUEST_SERVICE_MATCH|LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in') {
        throw 'PROBE_FATAL_MARKER_PRESENT'
    }
    if ($log -match 'VIRTUAL_PENDING_INTENT_DELIVERY status=FAILED') {
        throw 'PENDING_INTENT_DELIVERY_FAILURE_MARKER_PRESENT'
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
