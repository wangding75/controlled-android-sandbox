param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-11-framework-job-work-item-probe'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'
$activity = 'com.warden.controlledsandbox.fixture.FixtureJobWorkItemScheduleActivity'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Invoke-AdbChecked([string[]]$Arguments) {
    $output = & adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB_COMMAND_FAILED:$($Arguments -join ' ')" }
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
    foreach ($apk in @($hostApk, $companionApk, $guestApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "APK_MISSING:$apk" }
    }

    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $hostApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $companionApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $guestApk) | Out-Null

    # A previous recovery hold-prepare can still emit a launch-gate line into a new case.
    # Isolate the process/session before clearing logcat so the result represents this run.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop', $hostPackage) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop', $guestPackage) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null
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

    $log = ''
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $log = (& adb -s $device.Serial logcat -d -v threadtime | Out-String)
        if ($log -match 'JOB_WORK_ITEM_FAILED') { throw 'JOB_WORK_ITEM_FAILURE_MARKER_PRESENT' }
        $enqueued = $log -match 'JOB_WORK_ENQUEUE_RESULT[^\r\n]*result=1'
        $completed = $log -match 'JOB_WORK_ITEMS_DRAINED[^\r\n]*count=2'
        $finished = $log -match 'CS_JOB_BRIDGE: JOB_FINISHED_RECEIVED|CS_JOB_BRIDGE: JOB_AUTO_FINISHED_RECEIVED'
        if ($enqueued -and $completed -and $finished) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    if ($log -notmatch 'JOB_WORK_ENQUEUE_RESULT[^\r\n]*result=1') {
        throw 'JOB_WORK_ENQUEUE_RESULT_MISSING'
    }
    $completedFirst = $log -match 'JOB_WORK_ITEM_COMPLETED[^\r\n]*index=1'
    $completedSecond = $log -match 'JOB_WORK_ITEM_COMPLETED[^\r\n]*index=2'
    if (-not $completedFirst -or -not $completedSecond) {
        throw 'JOB_WORK_ITEM_COMPLETION_MISSING'
    }
    if ($log -notmatch 'JOB_WORK_ITEMS_DRAINED[^\r\n]*count=2') {
        throw 'JOB_WORK_ITEM_DRAIN_MISSING'
    }
    if ($log -notmatch 'CS_JOB_BRIDGE: JOB_FINISHED_RECEIVED|CS_JOB_BRIDGE: JOB_AUTO_FINISHED_RECEIVED') {
        throw 'JOB_FINISHED_RECEIPT_MISSING'
    }
    if ($log -match 'FATAL EXCEPTION|ANR in|LAUNCH_GATE_FAILED|NO_GUEST_SERVICE_MATCH') {
        throw 'JOB_WORK_ITEM_FATAL_MARKER_PRESENT'
    }

    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $log | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
