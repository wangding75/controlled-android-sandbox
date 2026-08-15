param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence/recovery')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-07-process-death-generation-recovery'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'
$activity = 'com.warden.controlledsandbox.fixture.FrameworkProbeActivity'
$guestProcessPattern = "$( [regex]::Escape($guestPackage) )(?:\:guest\d+)?(?:\s|$)"

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
        Start-Sleep -Milliseconds 300
    }
    throw 'DEBUG_COMMAND_RESULT_TIMEOUT'
}

function Invoke-DebugLaunch([object]$Device) {
    Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'am', 'start', '-W', '-f', '0x18000000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'launch-component', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--es', 'component', $activity) | Out-Null
    return Read-CommandResult $Device ((Get-Date).AddSeconds(75))
}

function Stop-ExistingGuestProcesses([object]$Device) {
    $lines = @( & adb -s $Device.Serial shell ps -A 2>$null |
        Select-String $guestProcessPattern )
    foreach ($line in $lines) {
        $candidate = $line.ToString().Trim()
        $guestPid = $null
        if ($candidate -match "^\S+\s+(\d+)\s+.*$guestProcessPattern") {
            $guestPid = $matches[1]
        } elseif ($candidate -match "\s(\d+)\s+.*$guestProcessPattern") {
            $guestPid = $matches[1]
        }
        if ($guestPid) {
            Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'run-as', $hostPackage,
                'kill', '-9', $guestPid) | Out-Null
        }
    }
    Start-Sleep -Seconds 1
}

try {
    $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $Serial
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null
    Stop-ExistingGuestProcesses $device
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '-f', '0x18000000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'hold-prepare', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true',
        '--el', 'holdMs', '30000') | Out-Null

    # Keep the client/broker binding alive while the harness finds and kills the
    # concrete guest process.  A normal import-prepare exits immediately and its
    # finally block releases the slot, which would test orderly shutdown instead
    # of crash recovery.
    $guestProcessDeadline = (Get-Date).AddSeconds(75)
    $process = ''
    $guestPid = $null
    while ((Get-Date) -lt $guestProcessDeadline) {
        $processLines = @( & adb -s $device.Serial shell ps -A 2>$null |
            Select-String $guestProcessPattern )
        foreach ($line in $processLines) {
            $candidate = $line.ToString().Trim()
            if ($candidate -match "^\S+\s+(\d+)\s+.*$guestProcessPattern") {
                $guestPid = $matches[1]
                $process = $candidate
                break
            }
            if ($candidate -match "\s(\d+)\s+.*$guestProcessPattern") {
                $guestPid = $matches[1]
                $process = $candidate
                break
            }
        }
        if ($guestPid) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $guestPid) { throw "GUEST_PROCESS_NOT_FOUND:$process" }

    $readyDeadline = (Get-Date).AddSeconds(30)
    $firstGeneration = $null
    $log = ''
    do {
        $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') | Out-String)
        if ($log -match 'GUEST_PREPARED .*generation=(\d+)') {
            $firstGeneration = [long]$matches[1]
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $readyDeadline)
    if ($null -eq $firstGeneration) { throw 'GUEST_READY_MARKER_MISSING' }

    # MuMu's adb shell UID cannot signal an application-owned process.  run-as keeps
    # the injection in the host application's UID while still delivering SIGKILL
    # to the concrete guest slot.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage,
        'kill', '-9', $guestPid) | Out-Null
    $deathDeadline = (Get-Date).AddSeconds(20)
    $log = ''
    do {
        $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') | Out-String)
        if ($log -match 'GUEST_PROCESS_DISCONNECTED|Process .*:guest\d+ .* has died') { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deathDeadline)
    if ($log -notmatch 'GUEST_PROCESS_DISCONNECTED') { throw 'DISCONNECT_MARKER_MISSING' }

    # Launch the replacement while hold-prepare still owns the original client
    # binding.  This forces RuntimeBrokerService to take the RECOVERING path rather
    # than allowing the old client finally block to perform a normal STOPPED teardown.
    $first = [pscustomobject]@{
        status = 'PASS'
        operation = [pscustomobject]@{
            status = 'PREPARED'; generation = $firstGeneration; processSlot = -1
        }
    }

    $second = Invoke-DebugLaunch $device
    if ($second.status -ne 'PASS' -or $second.operation.status -ne 'LAUNCH_PASS') {
        throw "RECOVERY_LAUNCH_FAILED:$($second | ConvertTo-Json -Compress)"
    }
    $secondGeneration = [long]$second.operation.generation
    if ($secondGeneration -le $firstGeneration) {
        throw "GENERATION_NOT_ADVANCED:first=$firstGeneration second=$secondGeneration"
    }
    $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') | Out-String)
    if ($log -match 'LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in') {
        throw 'RECOVERY_FATAL_MARKER_PRESENT'
    }
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    [pscustomobject]@{
        case = $caseName; serial = $device.Serial; api = $device.API; status = 'PASS'
        pidKilled = [int]$guestPid; firstGeneration = $firstGeneration; secondGeneration = $secondGeneration
        first = $first; second = $second
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
