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
    & (Join-Path $PSScriptRoot $case) -InstanceName $InstanceName -Serial $Serial -OutputDirectory $OutputDirectory
    $caseExit = $LASTEXITCODE
    Write-Output "CASE_END $case exit=$caseExit"
    if ($caseExit -ne 0) { $failed = $true }
}
if ($failed) {
    Write-Output 'RESULT: BLOCKED suite=t57-rd-full-regression'
    exit 1
}
Write-Output 'RESULT: PASS suite=t57-rd-full-regression'
exit 0
