param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')

. "$PSScriptRoot/t57_rd_common.ps1"

$caseName = 'RD-09-framework-activity-result-transport'
$hostPackage = 'com.warden.controlledsandbox.debug'
$guestPackage = 'com.warden.controlledsandbox.fixture'
$component = 'com.warden.controlledsandbox.fixture.FrameworkActivityResultParentActivity'
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
    foreach ($path in @($hostApk, $companionApk, $guestApk)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "APK_MISSING:$path" }
    }

    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $hostApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $companionApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'install', '-r', $guestApk) | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'logcat', '-c') | Out-Null

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'import-prepare', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--ez', 'trustNativeGuest', 'true') | Out-Null
    $prepare = Read-CommandResult $device ((Get-Date).AddSeconds(45))
    if ($prepare.status -ne 'PASS') {
        throw "IMPORT_PREPARE_FAILED:$($prepare | ConvertTo-Json -Compress)"
    }

    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'run-as', $hostPackage, 'rm', '-f',
        'files/debug-command-result.json') | Out-Null
    Invoke-AdbChecked @('-s', $device.Serial, 'shell', 'am', 'start', '-W', '--activity-clear-top',
        '-n', "$hostPackage/com.warden.controlledsandbox.DebugCommandActivity",
        '--es', 'command', 'launch-component', '--es', 'package', $guestPackage,
        '--ei', 'user', '0', '--es', 'component', $component) | Out-Null
    $launch = Read-CommandResult $device ((Get-Date).AddSeconds(60))
    if ($launch.status -ne 'PASS' -or $launch.operation.status -ne 'LAUNCH_PASS') {
        throw "LAUNCH_COMMAND_FAILED:$($launch | ConvertTo-Json -Compress)"
    }

    $log = ''
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $log = (& adb -s $device.Serial logcat -d -v threadtime | Out-String)
        $missing = @('FRAMEWORK_PROBE_ACTIVITY_RESULT_PARENT_CREATE',
            'FRAMEWORK_PROBE_ACTIVITY_RESULT_START',
            'FRAMEWORK_PROBE_ACTIVITY_RESULT_CHILD_FINISH',
            'GUEST_ACTIVITY_FINISH_RESULT',
            'FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS' | Where-Object {
                $log -notmatch [regex]::Escape($_)
            })
        if ($missing.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($missing.Count -ne 0) { throw "PROBE_MARKER_MISSING:$($missing -join ',')" }
    if ($log -match 'FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL|FATAL EXCEPTION|AndroidRuntime|LAUNCH_GATE_FAILED|GUEST_PREPARE_FAILED') {
        throw 'PROBE_FATAL_MARKER_PRESENT'
    }

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $null = Write-T57RdEvidence -Device $device -CaseName $caseName -OutputDirectory $OutputDirectory
    $log | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$caseName-full-logcat.txt")
    Write-Output "RESULT: PASS case=$caseName serial=$($device.Serial) api=$($device.API)"
    exit 0
} catch {
    Write-Error $_
    Write-Output "RESULT: BLOCKED case=$caseName reason=$($_.Exception.Message)"
    exit 1
}
