param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence/cross-abi-lifecycle')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-09-cross-abi-clear-delete-reinstall-transaction'
$hostPackage = 'com.warden.controlledsandbox.debug'
$companionPackage = 'com.warden.controlledsandbox.companion32.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture32'
$activity = 'com.warden.controlledsandbox.fixture.MainActivity'
$guestProcessPattern = "$( [regex]::Escape($guestPackage) )(?:\:\S+)?(?:\s|$)"
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
    return Read-CommandResult $Device ((Get-Date).AddSeconds(120))
}

function Read-GuestProcesses([object]$Device) {
    return @(& adb -s $Device.Serial shell ps -A 2>$null |
        Select-String $guestProcessPattern | ForEach-Object { $_.ToString().Trim() })
}

function Assert-NoGuestProcess([object]$Device, [string]$Phase) {
    $processes = @(Read-GuestProcesses $Device)
    if ($processes.Count -gt 0) {
        throw "CROSS_ABI_GUEST_PROCESS_SURVIVED:${Phase}:$($processes -join '|')"
    }
}

function Read-CompanionFiles([object]$Device) {
    $workspace = "files/companion-runtime/$guestPackage/u0"
    & adb @('-s', $Device.Serial, 'shell', 'run-as', $companionPackage,
        'test', '-d', $workspace) 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { return '' }
    $output = & adb @('-s', $Device.Serial, 'shell', 'run-as', $companionPackage, 'find',
        $workspace, '-type', 'f', '-print') 2>$null
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
        throw 'COMPANION_WORKSPACE_INSPECTION_FAILED'
    }
    return ($output | Out-String).Trim()
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

    $stop = Invoke-DebugCommand $device 'stop'
    if ($stop.status -ne 'PASS' -or $stop.operation.status -ne 'STOPPED') {
        throw "INITIAL_STOP_FAILED:$($stop | ConvertTo-Json -Compress)"
    }
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    $launch = Invoke-DebugCommand $device 'import-launch' $true
    if ($launch.status -ne 'PASS' -or $launch.operation.status -ne 'LAUNCH_PASS') {
        throw "LAUNCH_FAILED:$($launch | ConvertTo-Json -Compress)"
    }
    $markerRoot = "files/instances/u0/$guestPackage"
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'mkdir', '-p',
        $markerRoot) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'touch',
        "$markerRoot/t57-cross-abi-marker.txt") | Out-Null
    $before = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage,
        'find', $markerRoot, '-mindepth', '1', '-maxdepth', '2', '-print') | Out-String).Trim()
    if ($before -notmatch 't57-cross-abi-marker.txt') { throw 'CROSS_ABI_MARKER_CREATE_FAILED' }
    $runningBeforeClear = @(Read-GuestProcesses $device)
    if ($runningBeforeClear.Count -eq 0) { throw 'CROSS_ABI_GUEST_NOT_RUNNING_BEFORE_CLEAR' }

    $clear = Invoke-DebugCommand $device 'clear'
    if ($clear.status -ne 'PASS' -or $clear.operation.status -ne 'CLEARED') {
        throw "CLEAR_FAILED:$($clear | ConvertTo-Json -Compress)"
    }
    $afterClear = (Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage,
        'find', $markerRoot, '-mindepth', '1', '-maxdepth', '2', '-print') | Out-String).Trim()
    if ($afterClear) { throw "CLEAR_LEFT_HOST_DATA:$afterClear" }
    Assert-NoGuestProcess $device 'clear'
    $companionFilesAfterClear = Read-CompanionFiles $device
    if ($companionFilesAfterClear) {
        throw "CLEAR_LEFT_COMPANION_FILES:$companionFilesAfterClear"
    }

    $delete = Invoke-DebugCommand $device 'delete'
    if ($delete.status -ne 'PASS' -or $delete.operation.status -ne 'DELETED') {
        throw "DELETE_FAILED:$($delete | ConvertTo-Json -Compress)"
    }
    & adb -s $device.Serial shell run-as $hostPackage test -e $markerRoot 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'DELETE_LEFT_HOST_INSTANCE_ROOT' }
    Assert-NoGuestProcess $device 'delete'
    $companionFilesAfterDelete = Read-CompanionFiles $device
    if ($companionFilesAfterDelete) {
        throw "DELETE_LEFT_COMPANION_FILES:$companionFilesAfterDelete"
    }

    $reinstall = Invoke-DebugCommand $device 'import-launch' $true
    if ($reinstall.status -ne 'PASS' -or $reinstall.operation.status -ne 'LAUNCH_PASS') {
        throw "REINSTALL_LAUNCH_FAILED:$($reinstall | ConvertTo-Json -Compress)"
    }
    $log = (Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-d', '-v', 'threadtime') |
        Out-String)
    if ($log -notmatch 'CS_FIXTURE: NATIVE_LOAD JNI_LOADED') {
        throw 'REINSTALL_NATIVE_LOAD_MARKER_MISSING'
    }
    if ($log -match 'LAUNCH_GATE_FAILED|FATAL EXCEPTION|ANR in|STALE_GENERATION') {
        throw 'CROSS_ABI_LIFECYCLE_FATAL_MARKER_PRESENT'
    }

    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $log | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    [pscustomobject]@{
        case = $caseName; serial = $device.Serial; api = $device.API; status = 'PASS'
        before = $before; afterClear = $afterClear; clear = $clear; delete = $delete
        reinstall = $reinstall; companionFilesAfterClear = $companionFilesAfterClear
        companionFilesAfterDelete = $companionFilesAfterDelete
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-result.json")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
