[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$Name,

    [Parameter(Mandatory=$true)]
    [string]$FilePath,

    [string[]]$ArgumentList = @(),

    [string]$WorkingDirectory = (Get-Location).Path,

    [string]$OutputDirectory = "run-results"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $OutputDirectory)) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
}
$OutFull = (Get-Item $OutputDirectory).FullName

$stdoutFile = Join-Path $OutFull "$Name.stdout.log"
$stderrFile = Join-Path $OutFull "$Name.stderr.log"
$resultFile = Join-Path $OutFull "$Name.result.json"

$startedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

$process = Start-Process `
    -FilePath $FilePath `
    -ArgumentList $ArgumentList `
    -WorkingDirectory $WorkingDirectory `
    -RedirectStandardOutput $stdoutFile `
    -RedirectStandardError $stderrFile `
    -Wait `
    -PassThru

$finishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$exitCode = $process.ExitCode

$cmdString = "$FilePath " + ($ArgumentList -join " ")

$resultObj = [PSCustomObject]@{
    name = $Name
    command = $cmdString.Trim()
    startedAtUtc = $startedAt
    finishedAtUtc = $finishedAt
    exitCode = $exitCode
    stdoutPath = (Get-Item $stdoutFile).FullName
    stderrPath = (Get-Item $stderrFile).FullName
}

$json = $resultObj | ConvertTo-Json -Depth 5
Set-Content -Path $resultFile -Value $json

if ($exitCode -ne 0) {
    throw "Step '$Name' failed with exit code $exitCode. Stderr at $stderrFile"
}

Write-Output "Step '$Name' finished successfully with exit code 0"
