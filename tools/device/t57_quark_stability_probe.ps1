param(
    [string]$Serial = "127.0.0.1:16416",
    [int]$DurationSeconds = 300,
    [int]$IntervalSeconds = 10,
    [string]$PackageName = "com.quark.browser",
    [string]$HostPackageName = "com.warden.controlledsandbox.debug",
    [int]$ProcessSlot = 28,
    [string]$OutputDirectory = "build/t57-rd-evidence/quark-5min-abi-gated"
)

$ErrorActionPreference = "Stop"
$root = (Get-Location).Path
$output = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null
$evidence = Join-Path $output "monitor.log"
if (Test-Path -LiteralPath $evidence) {
    Remove-Item -LiteralPath $evidence -Force
}

function Write-Evidence([string]$Text) {
    $Text | Tee-Object -FilePath $evidence -Append
}

$started = Get-Date
$hostUidLine = @(adb -s $Serial shell cmd package list packages -U 2>$null |
    Select-String ("package:" + [regex]::Escape($HostPackageName) + " uid:"))
if ($hostUidLine.Count -ne 1 -or ([string]$hostUidLine[0] -notmatch 'uid:(\d+)')) {
    throw "HOST_UID_RESOLUTION_FAILED:$HostPackageName"
}
$hostUid = [int]$matches[1]
Write-Evidence ("START {0:o} serial={1} duration={2}s package={3} slot={4}" -f `
        $started, $Serial, $DurationSeconds, $PackageName, $ProcessSlot)
Write-Evidence ("HOST_UID {0} hostPackage={1}" -f $hostUid, $HostPackageName)
# The direct APK may remain installed for the control run. It must not make the sandbox
# appear alive, and old logcat history must not make a new run fail.
adb -s $Serial shell am force-stop $PackageName 2>$null | Out-Null
adb -s $Serial logcat -c 2>$null | Out-Null
$pass = $true
$ticks = [Math]::Max(1, [Math]::Ceiling($DurationSeconds / [double]$IntervalSeconds))

for ($tick = 0; $tick -lt $ticks; $tick++) {
    $now = Get-Date
    $processes = @(adb -s $Serial shell ps -A -o UID,PID,PPID,NAME 2>$null |
        Select-String ("^\s*" + $hostUid + "\s+\d+\s+(?!1\s+)\d+\s+" +
            [regex]::Escape($PackageName) + "(?:\s|$)"))
    $errors = @(adb -s $Serial logcat -d -v brief 2>$null | Select-String `
        -Pattern "FATAL EXCEPTION|Fatal signal|ANR in|GUEST_PROCESS_DISCONNECTED|PROCESS DIED|LAUNCH_GATE_FAILED|GUEST_PROCESS_ABORTED|NATIVE_FATAL_SIGNAL")
    $alive = $processes.Count -gt 0
    if (-not $alive -or $errors.Count -gt 0) {
        $pass = $false
    }
    Write-Evidence ("TICK {0}/{1} time={2:o} alive={3} processCount={4} errors={5}" -f `
            ($tick + 1), $ticks, $now, $alive, $processes.Count, $errors.Count)
    foreach ($process in $processes) {
        Write-Evidence ("  PROC {0}" -f $process)
    }
    foreach ($issue in $errors) {
        Write-Evidence ("  ERROR {0}" -f $issue)
    }
    if ($tick + 1 -lt $ticks) {
        Start-Sleep -Seconds $IntervalSeconds
    }
}

$ended = Get-Date
Write-Evidence ("END {0:o} pass={1} elapsedSeconds={2}" -f `
        $ended, $pass, [int](($ended - $started).TotalSeconds))
if (-not $pass) {
    exit 2
}
