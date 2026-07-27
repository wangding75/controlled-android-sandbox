$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("controlled-wrapper-check-" + [Guid]::NewGuid())
try {
    $Classes = Join-Path $Temp 'classes'
    New-Item -ItemType Directory -Path $Classes -Force | Out-Null
    & javac --release 17 -d $Classes (Join-Path $Root 'tools\wrapper-src\org\gradle\wrapper\GradleWrapperMain.java')
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper source compilation failed' }
    $Lock = Get-Content (Join-Path $Root 'build-environment.lock.json') -Raw | ConvertFrom-Json
    $Expected = $Lock.toolchain.gradle.compatibilityWrapperJarSha256.ToLowerInvariant()
    $Actual = (Get-FileHash -Algorithm SHA256 (Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar')).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) { throw "Compatibility wrapper JAR checksum mismatch: $Actual != $Expected" }
    Write-Host 'PASS wrapper source compile and compatibility JAR checksum'
} finally {
    Remove-Item $Temp -Recurse -Force -ErrorAction SilentlyContinue
}
