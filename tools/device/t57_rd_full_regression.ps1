param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')
. "$PSScriptRoot/t57_rd_common.ps1"
$cases = @('t57_rd_activity_real_path.ps1','t57_rd_pending_intent_real_path.ps1',
    't57_rd_service_real_path.ps1','t57_rd_provider_real_path.ps1',
    't57_rd_framework_transport_probe.ps1','t57_rd_clear_lifecycle.ps1')
$failed = $false
foreach ($case in $cases) {
    & (Join-Path $PSScriptRoot $case) -InstanceName $InstanceName -Serial $Serial -OutputDirectory $OutputDirectory
    if ($LASTEXITCODE -eq 1) { $failed = $true }
}
if ($failed) { Write-Output 'RESULT: BLOCKED suite=t57-rd-full-regression'; exit 1 }
Write-Output 'RESULT: DEVICE_REGRESSION_PENDING suite=t57-rd-full-regression reason=fixture commands not supplied'; exit 2
