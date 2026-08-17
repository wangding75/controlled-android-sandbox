param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence/isolated-service')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-08-isolated-service-framework-transport'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'
$component = 'com.warden.controlledsandbox.fixture.IsolatedFixtureService'
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
        Start-Sleep -Milliseconds 300
    }
    throw 'DEBUG_COMMAND_RESULT_TIMEOUT'
}

function Clear-CommandResult([object]$Device) {
    Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'run-as', $hostPackage,
        'rm', '-f', 'files/debug-command-result.json') | Out-Null
}

function Invoke-DebugCommand([object]$Device, [string]$Command,
                             [hashtable]$Extras, [int]$TimeoutSeconds = 75) {
    Clear-CommandResult $Device
    $arguments = @('-s', $Device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', $Command)
    foreach ($entry in $Extras.GetEnumerator()) {
        if ($entry.Value -is [bool]) {
            $arguments += @('--ez', [string]$entry.Key, ([string]$entry.Value).ToLowerInvariant())
        } elseif ($entry.Value -is [int]) {
            $arguments += @('--ei', [string]$entry.Key, [string]$entry.Value)
        } else {
            $arguments += @('--es', [string]$entry.Key, [string]$entry.Value)
        }
    }
    Invoke-AdbChecked $arguments | Out-Null
    return Read-CommandResult $Device ((Get-Date).AddSeconds($TimeoutSeconds))
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
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    $prepare = Invoke-DebugCommand $device 'import-prepare' @{
        package = $guestPackage; user = 0; trustNativeGuest = $true
    }
    if ($prepare.status -ne 'PASS') {
        throw "IMPORT_PREPARE_FAILED:$($prepare | ConvertTo-Json -Compress)"
    }

    $start = Invoke-DebugCommand $device 'isolated-service' @{
        package = $guestPackage; user = 0; component = $component
        serviceOperation = 'start'; trustNativeGuest = $true
    }
    if ($start.status -ne 'PASS' -or $start.operation.status -notin @('SERVICE_STARTED', 'SERVICE_RECOVERED')) {
        throw "ISOLATED_START_FAILED:$($start | ConvertTo-Json -Compress)"
    }
    if (-not $start.operation.isolatedProcess) { throw 'ISOLATED_RESULT_FLAG_MISSING' }
    if ([int]$start.operation.isolatedPlatformPid -le 0) { throw 'ISOLATED_PID_MISSING' }
    if ([int]$start.operation.isolatedPlatformUid -le 0) { throw 'ISOLATED_UID_MISSING' }
    if ($start.operation.processName -notmatch ':isolated_') {
        throw "ISOLATED_PROCESS_NAME_MISSING:$($start.operation.processName)"
    }

    $hostUidText = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'id', '-u') |
        Out-String).Trim()
    $hostUid = 0
    if (-not [int]::TryParse($hostUidText, [ref]$hostUid)) {
        throw "HOST_UID_UNREADABLE:$hostUidText"
    }
    if ([int]$start.operation.isolatedPlatformUid -eq $hostUid) {
        throw "ISOLATED_UID_EQUALS_HOST_UID:$hostUid"
    }

    $processTable = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'ps', '-A') | Out-String)
    if ($processTable -notmatch [regex]::Escape([string]$start.operation.isolatedPlatformPid)) {
        throw "ISOLATED_PID_NOT_VISIBLE_IN_PS:$($start.operation.isolatedPlatformPid)"
    }

    # Use the public route to stop the same session.  This verifies that cleanup also remains on
    # the dedicated channel and does not silently fall back to an ordinary Guest slot.
    $stop = Invoke-DebugCommand $device 'isolated-service' @{
        package = $guestPackage; user = 0; component = $component
        serviceOperation = 'stop'; trustNativeGuest = $true
    }
    if ($stop.status -ne 'PASS' -or $stop.operation.status -notin @('SERVICE_STOPPED', 'SERVICE_NOT_RUNNING', 'SERVICE_STOP_REQUESTED')) {
        throw "ISOLATED_STOP_FAILED:$($stop | ConvertTo-Json -Compress)"
    }

    $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') | Out-String)
    if ($log -match 'FATAL EXCEPTION|ANR in|ISOLATED_UID_EQUALS_HOST_UID|ISOLATED_PROCESS_UID_INVALID') {
        throw 'ISOLATED_FATAL_MARKER_PRESENT'
    }
    $record = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $log | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    [pscustomobject]@{
        case = $caseName; serial = $device.Serial; api = $device.API; status = 'PASS'
        component = $component; hostUid = $hostUid; prepare = $prepare; start = $start; stop = $stop
    } | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API) isolatedUid=$($start.operation.isolatedPlatformUid) isolatedPid=$($start.operation.isolatedPlatformPid)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
