[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
& .\gradlew.bat --no-daemon --dependency-verification=strict resolveAndLockAll --write-locks
if ($LASTEXITCODE -ne 0) { throw "Gradle lock generation failed: $LASTEXITCODE" }
python tools/gradle_lock_state.py verify
if ($LASTEXITCODE -ne 0) { throw "Gradle lock verification failed: $LASTEXITCODE" }
Write-Host 'PASS Gradle lock state generated and verified.'
