param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')

$ErrorActionPreference = 'Stop'
$cases = @(
    't57_rd_framework_activity_result_probe.ps1',
    't57_rd_framework_transport_probe.ps1',
    't57_rd_framework_job_work_item_probe.ps1',
    't57_rd_framework_foreground_service_probe.ps1',
    't57_rd_recovery_probe.ps1',
    't57_rd_isolated_service_probe.ps1',
    't57_rd_lifecycle_probe.ps1',
    't57_rd_cross_abi_recovery_probe.ps1',
    't57_rd_cross_abi_lifecycle_probe.ps1'
)
$failed = $false
foreach ($case in $cases) {
    # Each probe is an independent lifecycle transaction.  MuMu's ActivityTaskManager may keep
    # a finishing Stub task's starting-window callback queued after the previous probe returns;
    # without this boundary the next probe can trip an OEM system_server NPE before CAS receives
    # its launch transaction.  This does not weaken any probe: recovery, clear/delete and
    # cross-ABI cases exercise their own in-case process death and teardown semantics.
    if ($Serial) {
        foreach ($package in @(
                'com.warden.controlledsandbox.debug',
                'com.warden.controlledsandbox.companion32.debug',
                'com.warden.controlledsandbox.fixture',
                'com.warden.controlledsandbox.fixture32')) {
            & adb -s $Serial shell am force-stop $package 2>$null | Out-Null
        }
        Start-Sleep -Milliseconds 750
    }
    Write-Output "CASE_BEGIN $case"
    $caseOutput = @(& (Join-Path $PSScriptRoot $case) -InstanceName $InstanceName -Serial $Serial `
        -OutputDirectory $OutputDirectory 2>&1)
    $caseExit = $LASTEXITCODE
    $caseOutput | ForEach-Object { Write-Output $_ }
    $caseText = ($caseOutput | Out-String)
    if ($caseExit -ne 0 -and $Serial -and $caseText -match 'daemon still not running') {
        # ADB can briefly lose its host daemon after a long device campaign. Retry only this
        # transport-level symptom once after proving the resolved device is back in `device`
        # state; runtime/probe assertions remain fail-closed and are never retried here.
        Write-Output "CASE_RETRY $case reason=ADB_DAEMON_TRANSIENT"
        & adb start-server 2>$null | Out-Null
        $adbReady = $false
        for ($attempt = 0; $attempt -lt 10; $attempt++) {
            $state = (& adb -s $Serial get-state 2>$null | Out-String).Trim()
            if ($state -eq 'device') { $adbReady = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if ($adbReady) {
            $caseOutput = @(& (Join-Path $PSScriptRoot $case) -InstanceName $InstanceName -Serial $Serial `
                -OutputDirectory $OutputDirectory 2>&1)
            $caseExit = $LASTEXITCODE
            $caseOutput | ForEach-Object { Write-Output $_ }
        } else {
            Write-Output "CASE_RETRY_BLOCKED $case reason=ADB_DEVICE_NOT_READY"
        }
    }
    Write-Output "CASE_END $case exit=$caseExit"
    if ($caseExit -ne 0) { $failed = $true }
}
if ($failed) {
    Write-Output 'RESULT: BLOCKED suite=t57-rd-full-regression'
    exit 1
}
Write-Output 'RESULT: PASS suite=t57-rd-full-regression'
exit 0
