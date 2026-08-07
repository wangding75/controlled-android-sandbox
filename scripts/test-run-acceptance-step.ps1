$ErrorActionPreference = "Stop"

$RepoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
Set-Location $RepoRoot

$TempDir = Join-Path $env:TEMP ("step-test-" + (Get-Random))
if (Test-Path $TempDir) { Remove-Item -Recurse -Force $TempDir }
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

try {
    # 1. Test success step
    & (Join-Path $PSScriptRoot "run-acceptance-step.ps1") `
        -Name "test-success" `
        -FilePath "cmd.exe" `
        -ArgumentList "/c", "exit", "0" `
        -OutputDirectory $TempDir `
        -WorkingDirectory $RepoRoot

    $successJson = Join-Path $TempDir "test-success.result.json"
    if (-not (Test-Path $successJson)) { throw "Success result JSON not created" }
    $objSuccess = Get-Content $successJson -Raw | ConvertFrom-Json
    if ($objSuccess.exitCode -ne 0) { throw "Expected exitCode 0" }

    # 2. Test failure step (must throw error)
    $failedThrew = $false
    try {
        & (Join-Path $PSScriptRoot "run-acceptance-step.ps1") `
            -Name "test-failure" `
            -FilePath "cmd.exe" `
            -ArgumentList "/c", "exit", "1" `
            -OutputDirectory $TempDir `
            -WorkingDirectory $RepoRoot
    } catch {
        $failedThrew = $true
    }

    if (-not $failedThrew) { throw "Expected wrapper to throw on non-zero exit code" }

    $failureJson = Join-Path $TempDir "test-failure.result.json"
    if (-not (Test-Path $failureJson)) { throw "Failure result JSON not created" }
    $objFailure = Get-Content $failureJson -Raw | ConvertFrom-Json
    if ($objFailure.exitCode -ne 1) { throw "Expected exitCode 1 in result JSON" }

    Write-Output "PASS test-run-acceptance-step"
}
finally {
    if (Test-Path $TempDir) {
        Remove-Item -Recurse -Force $TempDir -ErrorAction SilentlyContinue
    }
}
