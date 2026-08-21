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

function Ensure-Adb([string]$Serial) {
    & adb start-server 2>$null | Out-Null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & adb -s $Serial get-state 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds $attempt
        & adb start-server 2>$null | Out-Null
    }
    throw "ADB_DEVICE_NOT_READY:$Serial"
}

function Clear-TestPackages([string]$Serial) {
    Ensure-Adb $Serial
    foreach ($package in @('com.warden.controlledsandbox.debug', 'com.warden.controlledsandbox.companion32.debug', 'com.warden.controlledsandbox.fixture32')) {
        & adb -s $Serial shell pm clear $package 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "ADB_PM_CLEAR_FAILED:$package" }
    }
}

function Is-EnvironmentFailure([string]$Text) {
    return $Text -match 'daemon.*not running|device offline|cannot connect|ADB_NOT_AVAILABLE|ADB_COMMAND_FAILED|ADB_DEVICE_NOT_READY'
}

for ($i = 1; $i -le $Loops; $i++) {
    $iterationDir = Join-Path $runRoot ('loop-{0:D2}' -f $i)
    New-Item -ItemType Directory -Force -Path $iterationDir | Out-Null
    $output = ''
    $status = 'FAIL'
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Clear-TestPackages $resolved.resolvedSerial
            Start-Sleep -Milliseconds 500
            $oldErrorAction = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $probe `
                -InstanceName $InstanceName -OutputDirectory $iterationDir 2>&1 | Out-String
            $probeExit = $LASTEXITCODE
            $ErrorActionPreference = $oldErrorAction
        } catch {
            $ErrorActionPreference = 'Continue'
            $output = ($_ | Out-String)
            $probeExit = 1
        }
        $resultFile = Join-Path $iterationDir 'RD-08-cross-abi-process-death-generation-recovery-result.json'
        $status = if (Test-Path $resultFile) { (Get-Content -Raw $resultFile | ConvertFrom-Json).status } else { 'FAIL' }
        if ($status -eq 'PASS') { break }
        if (-not (Is-EnvironmentFailure $output) -or $attempt -eq 3) { break }
        Start-Sleep -Seconds $attempt
    }
    $resultFile = Join-Path $iterationDir 'RD-08-cross-abi-process-death-generation-recovery-result.json'
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
