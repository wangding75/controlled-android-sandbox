param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence/cross-abi-recovery')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-08-cross-abi-process-death-generation-recovery'
$hostPackage = 'com.warden.controlledsandbox.debug'
$companionPackage = 'com.warden.controlledsandbox.companion32.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture32'
$activity = 'com.warden.controlledsandbox.fixture.MainActivity'
$guestProcessPattern = "$( [regex]::Escape($guestPackage) )(?:\:\S+)?(?:\s|$)"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$finalLog = ''
$fatalLines = @()

function Invoke-AdbChecked([string[]]$Arguments) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $output = & adb @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -eq 0) { return $output }
        $text = ($output | Out-String)
        if ($text -match '(?i)daemon.*not running|cannot connect|device offline') {
            & adb start-server 2>$null | Out-Null
            Start-Sleep -Seconds $attempt
            continue
        }
        throw "ADB_COMMAND_FAILED:$($Arguments -join ' ') exit=$exitCode output=$text"
    }
    throw "ADB_COMMAND_FAILED_AFTER_RETRY:$($Arguments -join ' ')"
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

function Invoke-DebugCommand([object]$Device, [string]$Command, [hashtable]$Extras = @{}) {
    Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    $arguments = @('-s', $Device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', $Command, '--es', 'package', $guestPackage, '--ei', 'user', '0')
    foreach ($key in $Extras.Keys) {
        $value = $Extras[$key]
        if ($value -is [bool]) {
            $arguments += @('--ez', $key, ($(if ($value) { 'true' } else { 'false' })))
        } elseif ($value -is [int]) {
            $arguments += @('--ei', $key, [string]$value)
        } else {
            $arguments += @('--es', $key, [string]$value)
        }
    }
    Invoke-AdbChecked $arguments | Out-Null
    return Read-CommandResult $Device ((Get-Date).AddSeconds(120))
}

function Read-Log([object]$Device) {
    return (Invoke-AdbChecked @('-s', $Device.Serial, 'logcat', '-d', '-v', 'threadtime',
        '-s', 'CS_RUNTIME:V', 'CS_FIXTURE:V', 'CS_COMMAND:V', 'CS_NATIVE_BIND:V',
        'AndroidRuntime:E', 'ActivityManager:E', 'InputMethodManagerService:E', '*:S') |
        Out-String)
}

function Wait-GuestPrepared([object]$Device, [datetime]$Deadline, [long]$MinimumGeneration = 0) {
    do {
        $log = Read-Log $Device
        $matches = [regex]::Matches($log,
            "GUEST_PREPARED .*package=$([regex]::Escape($guestPackage)) .*physicalPid=(\d+) .*generation=(\d+)")
        for ($index = $matches.Count - 1; $index -ge 0; $index--) {
            $match = $matches[$index]
            $generation = [long]$match.Groups[2].Value
            if ($generation -gt $MinimumGeneration) {
                return [pscustomobject]@{
                    log = $log; pid = [int]$match.Groups[1].Value; generation = $generation
                }
            }
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $Deadline)
    throw "GUEST_PREPARED_MARKER_TIMEOUT:minGeneration=$MinimumGeneration"
}

function Wait-LogMarker([object]$Device, [string]$Pattern, [datetime]$Deadline) {
    do {
        $log = Read-Log $Device
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $Deadline)
    throw "LOG_MARKER_TIMEOUT:$Pattern"
}

try {
    $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $Serial
    $hostApk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $companionApk = Join-Path $repoRoot 'sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk'
    $guestApk = Join-Path $repoRoot 'fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk'
    foreach ($apk in @($hostApk, $companionApk, $guestApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "APK_MISSING:$apk" }
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $hostApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $companionApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $guestApk) | Out-Null
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    $prepare = Invoke-DebugCommand $device 'import-prepare' @{
        trustNativeGuest = $true
    }
    if ($prepare.status -ne 'PASS' -or $prepare.operation.status -notin @('PREPARED', 'ALREADY_PREPARED')) {
        throw "IMPORT_PREPARE_FAILED:$($prepare | ConvertTo-Json -Compress)"
    }
    # The import command itself creates a Guest process.  Clear its evidence before the held
    # session so the PID/generation selected below can only belong to the process whose Binder
    # lease is about to be killed.
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    # Keep the Host -> Companion Runtime Broker binding alive while the harness kills the
    # concrete x86 Guest process.  The target's physical PID is deliberately taken from the
    # Guest's own prepared marker, not from a package-wide force-stop, so the test proves the
    # Binder-death path of the sandbox slot rather than an orderly package stop.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '-f', '0x18000000',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'hold-prepare', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true',
        '--es', 'launchComponent', $activity, '--el', 'holdMs', '45000') | Out-Null

    $first = Wait-GuestPrepared $device ((Get-Date).AddSeconds(90))
    $firstLog = $first.log
    if ($firstLog -notmatch "(?m)^.*\s$($first.pid)\s+\S+\s+I\s+CS_FIXTURE:\s+NATIVE_LOAD JNI_LOADED") {
        throw "CROSS_ABI_NATIVE_LOAD_MARKER_MISSING:firstPid=$($first.pid)"
    }
    $ps = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'ps', '-A') | Out-String)
    if ($ps -notmatch "(?m)^.*\s$($first.pid)\s+.*$guestProcessPattern") {
        throw "CROSS_ABI_GUEST_PID_NOT_FOUND:pid=$($first.pid)"
    }

    # The Companion owns the UID of the x86 Guest process.  Using its debuggable run-as shell
    # keeps the kill scoped to the installed test Companion and avoids a host-side privileged
    # signal that would bypass the same-UID lifecycle boundary used in production.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $companionPackage,
        'kill', '-9', [string]$first.pid) | Out-Null
    $deathLog = Wait-LogMarker $device 'GUEST_PROCESS_DISCONNECTED' ((Get-Date).AddSeconds(30))

    $second = Invoke-DebugCommand $device 'prepare' @{
        trustNativeGuest = $true
    }
    if ($second.status -ne 'PASS' -or $second.operation.status -notin @('PREPARED', 'ALREADY_PREPARED')) {
        throw "RECOVERY_PREPARE_FAILED:$($second | ConvertTo-Json -Compress)"
    }
    $secondGeneration = [long]$second.operation.generation
    if ($secondGeneration -le $first.generation) {
        throw "GENERATION_NOT_ADVANCED:first=$($first.generation) second=$secondGeneration"
    }
    $secondPrepared = Wait-GuestPrepared $device ((Get-Date).AddSeconds(60)) $first.generation
    if ($secondPrepared.pid -eq $first.pid) {
        throw "CROSS_ABI_PID_NOT_REPLACED:pid=$($first.pid)"
    }
    if ($secondPrepared.log -notmatch "(?m)^.*\s$($secondPrepared.pid)\s+\S+\s+I\s+CS_FIXTURE:\s+NATIVE_LOAD JNI_LOADED") {
        throw "CROSS_ABI_NATIVE_LOAD_MARKER_MISSING:secondPid=$($secondPrepared.pid)"
    }
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $finalLog = Read-Log $device
    $finalLog | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    $fatalLines = @($finalLog -split "`r?`n" | Where-Object {
        $_ -match 'FRAMEWORK_SERVICE_EVENT_REJECTED|STALE_GENERATION|LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in'
    })
    if ($fatalLines.Count -gt 0) {
        throw "CROSS_ABI_RECOVERY_FATAL_MARKER_PRESENT: $($fatalLines | Select-Object -Last 5 | Out-String)"
    }

    [pscustomobject]@{
        case = $caseName; serial = $device.Serial; api = $device.API; status = 'PASS'
        killedPid = $first.pid; recoveredPid = $secondPrepared.pid
        firstGeneration = $first.generation; secondGeneration = $secondGeneration
        prepare = $prepare; second = $second; deathMarker = 'GUEST_PROCESS_DISCONNECTED'
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    if ($finalLog) {
        $finalLog | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    }
    [pscustomobject]@{
        case = $caseName; serial = if ($device) { $device.Serial } else { '' }; api = if ($device) { $device.API } else { '' }
        status = 'BLOCKED'; error = $_.Exception.Message; markerLines = $fatalLines
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
