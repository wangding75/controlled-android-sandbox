param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence/lifecycle')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-06-clear-delete-reinstall-transaction'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'

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

function Invoke-DebugCommand([object]$Device, [string]$Command, [bool]$TrustNativeGuest = $false) {
    Invoke-AdbChecked @('-s', $Device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    $arguments = @('-s', $Device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', $Command, '--es', 'package', $guestPackage, '--ei', 'user', '0')
    if ($TrustNativeGuest) { $arguments += @('--ez', 'trustNativeGuest', 'true') }
    Invoke-AdbChecked $arguments | Out-Null
    return Read-CommandResult $Device ((Get-Date).AddSeconds(60))
}

try {
    $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $Serial
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    # Recovery deliberately keeps a hold-prepare client alive while the replacement Guest is
    # prepared.  A lifecycle transaction is a new test session, so cancel any previous host
    # command before clearing logcat; otherwise its expected late launch failure is attributed
    # to clear/delete/reinstall and makes the result depend on probe ordering.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop', $hostPackage) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop', $guestPackage) | Out-Null
    # A prior cross-ABI case owns a separate Companion process.  Stop that independent virtual
    # world before this case so its remote package/system-service connection cannot contaminate
    # the ordinary-ABI lifecycle evidence.
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop',
        'com.warden.controlledsandbox.companion32.debug') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'force-stop',
        'com.warden.controlledsandbox.fixture32') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null
    $instanceRoot = "files/instances/u0/$guestPackage"
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'mkdir', '-p',
        $instanceRoot) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'touch',
        "$instanceRoot/t57-marker.txt") | Out-Null
    $before = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage,
        'find', $instanceRoot, '-mindepth', '1', '-maxdepth', '2', '-print') | Out-String).Trim()
    if ($before -notmatch 't57-marker.txt') { throw 'LIFECYCLE_MARKER_CREATE_FAILED' }

    $clear = Invoke-DebugCommand $device 'clear'
    if ($clear.status -ne 'PASS' -or $clear.operation.status -ne 'CLEARED') {
        throw "CLEAR_FAILED:$($clear | ConvertTo-Json -Compress)"
    }
    $afterClear = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage,
        'find', $instanceRoot, '-mindepth', '1', '-maxdepth', '2', '-print') | Out-String).Trim()
    if ($afterClear) { throw "CLEAR_LEFT_DATA:$afterClear" }
    $guestAfterClear = (& adb -s $device.Serial shell ps -A 2>$null |
        Select-String 'com\.warden\.controlledsandbox\.debug:guest' | Out-String).Trim()
    if ($guestAfterClear) { throw "CLEAR_LEFT_GUEST_PROCESS:$guestAfterClear" }

    $delete = Invoke-DebugCommand $device 'delete'
    if ($delete.status -ne 'PASS' -or $delete.operation.status -ne 'DELETED') {
        throw "DELETE_FAILED:$($delete | ConvertTo-Json -Compress)"
    }
    & adb -s $device.Serial shell run-as $hostPackage test -e $instanceRoot 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'DELETE_LEFT_INSTANCE_ROOT' }

    $reinstall = Invoke-DebugCommand $device 'import-launch' $true
    if ($reinstall.status -ne 'PASS' -or $reinstall.operation.status -ne 'LAUNCH_PASS') {
        throw "REINSTALL_LAUNCH_FAILED:$($reinstall | ConvertTo-Json -Compress)"
    }
    $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') | Out-String)
    if ($log -match 'LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in') {
        throw 'LIFECYCLE_FATAL_MARKER_PRESENT'
    }
    [pscustomobject]@{
        case = $caseName; serial = $device.Serial; api = $device.API; status = 'PASS'
        before = $before; afterClear = $afterClear
        clear = $clear; delete = $delete; reinstall = $reinstall
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
