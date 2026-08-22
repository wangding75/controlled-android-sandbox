param([string]$InstanceName = '',
      [string]$OutputDirectory = 'verification/catch-up/C1-GATE')

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/t57_rd_common.ps1"

if ([string]::IsNullOrWhiteSpace($InstanceName)) {
    $InstanceName = 'RD' + [char]0x6d4b + [char]0x8bd5
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = (Join-Path $root $OutputDirectory)
$fullRegression = Join-Path $output 'full-regression'
$transcript = Join-Path $output 'c1-gate-run.txt'
$providerReceipt = Join-Path $output 'c1-gate-provider-rd-summary.json'
$startedAt = (Get-Date).ToUniversalTime().ToString('o')
$exitCode = 1
$transcriptStarted = $false

New-Item -ItemType Directory -Force -Path $output | Out-Null
New-Item -ItemType Directory -Force -Path $fullRegression | Out-Null

try {
    Start-Transcript -Path $transcript -Force | Out-Null
    $transcriptStarted = $true
    $device = Resolve-T57RdDevice -InstanceName $InstanceName
    Write-Output "C1_GATE_DEVICE instance=$InstanceName serial=$($device.Serial) api=$($device.API) boot=$($device.BootId)"

    & python (Join-Path $root 'tools/capability/run_c1_t04_rd.py') `
        --instance $InstanceName --loops 50 --pressure-seconds 1800 --receipt $providerReceipt
    if ($LASTEXITCODE -ne 0) { throw "C1_GATE_PROVIDER_PRESSURE_FAILED:$LASTEXITCODE" }

    $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $device.Serial
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 't57_rd_full_regression.ps1') `
        -InstanceName $InstanceName -Serial $device.Serial -OutputDirectory $fullRegression
    if ($LASTEXITCODE -ne 0) { throw "C1_GATE_FULL_REGRESSION_FAILED:$LASTEXITCODE" }

    $finalDevice = Write-T57RdEvidence -Device $device -CaseName 'C1-GATE-final-device' -OutputDirectory $output
    [pscustomobject]@{
        schema_version = 1
        task = 'C1-GATE'
        status = 'CAMPAIGNS_PASS'
        started_at = $startedAt
        finished_at = (Get-Date).ToUniversalTime().ToString('o')
        instance_name = $InstanceName
        device = $finalDevice
        provider_receipt = $providerReceipt
        full_regression_directory = $fullRegression
        transcript = $transcript
        git_head = (git rev-parse HEAD).Trim()
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $output 'c1-gate-run.json')
    Write-Output "RESULT: PASS task=C1-GATE instance=$InstanceName serial=$($device.Serial)"
    $exitCode = 0
} catch {
    $failureReason = $_.Exception.Message
    [pscustomobject]@{
        schema_version = 1
        task = 'C1-GATE'
        status = 'BLOCKED'
        started_at = $startedAt
        finished_at = (Get-Date).ToUniversalTime().ToString('o')
        instance_name = $InstanceName
        reason = $failureReason
        transcript = $transcript
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $output 'c1-gate-run.json')
    Write-Output "RESULT: BLOCKED task=C1-GATE reason=$failureReason"
    Write-Error $failureReason
    $exitCode = 1
} finally {
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
}

exit $exitCode
