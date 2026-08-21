param(
    [string]$InstanceName = '',
    [int]$Loops = 50,
    [string]$OutputDirectory = 'verification/catch-up/C1-T07'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$InstanceName = if ($InstanceName) { $InstanceName } else { 'RD' + ([char]0x6d4b) + ([char]0x8bd5) }
$probe = Join-Path $PSScriptRoot 't57_rd_cross_abi_recovery_probe.ps1'
$resolved = & python (Join-Path $repoRoot 'scripts/mumu_instance.py') --instance-name $InstanceName | ConvertFrom-Json
if (-not $resolved.resolvedSerial) { throw 'RD_ENVIRONMENT_RESOLUTION_BLOCKED' }
if ($Loops -lt 50) { throw 'C1_T07_REQUIRES_AT_LEAST_50_LOOPS' }
$runRoot = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$started = (Get-Date).ToUniversalTime().ToString('o')
$results = @()
for ($i = 1; $i -le $Loops; $i++) {
    $iterationDir = Join-Path $runRoot ('loop-{0:D2}' -f $i)
    New-Item -ItemType Directory -Force -Path $iterationDir | Out-Null
    foreach ($package in @('com.warden.controlledsandbox.debug', 'com.warden.controlledsandbox.companion32.debug', 'com.warden.controlledsandbox.fixture32')) {
        & adb -s $resolved.resolvedSerial shell pm clear $package 2>$null | Out-Null
    }
    Start-Sleep -Milliseconds 500
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $probe `
        -InstanceName $InstanceName -OutputDirectory $iterationDir 2>&1 | Out-String
    $resultFile = Join-Path $iterationDir 'RD-08-cross-abi-process-death-generation-recovery-result.json'
    $status = if (Test-Path $resultFile) { (Get-Content -Raw $resultFile | ConvertFrom-Json).status } else { 'FAIL' }
    $results += [pscustomobject]@{ loop = $i; status = $status; outputTail = $output.Trim().Substring([Math]::Max(0, $output.Trim().Length - 1000)) }
    if ($status -ne 'PASS') { throw "C1_T07_LOOP_FAILED:$i" }
}
$ended = (Get-Date).ToUniversalTime().ToString('o')
[pscustomobject]@{
    task = 'C1-T07'; status = 'PASS'; loops = $Loops; instanceName = $InstanceName
    serial = $resolved.resolvedSerial; api = $resolved.api; abiList = $resolved.abiList
    startedUtc = $started; endedUtc = $ended; results = $results
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'c1-t07-process-abi-recovery-summary.json')
Write-Output "RESULT: PASS task=C1-T07 loops=$Loops serial=$($resolved.resolvedSerial)"
