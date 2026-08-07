$ErrorActionPreference = "Stop"

$RepoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
Set-Location $RepoRoot

$TempEvidenceDir = Join-Path $env:TEMP ("evidence-test-" + (Get-Random))
if (Test-Path $TempEvidenceDir) { Remove-Item -Recurse -Force $TempEvidenceDir }

$DummyFile = Join-Path $TempEvidenceDir "dummy-artifact.bin"
New-Item -ItemType Directory -Force -Path $TempEvidenceDir | Out-Null
Set-Content -Path $DummyFile -Value "Test Artifact Content for Evidence Script"

try {
    # 1. Run capture script
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") -RepositoryPath $RepoRoot -OutputDirectory $TempEvidenceDir -SourceZipPath $DummyFile

    $ManifestFile = Join-Path $TempEvidenceDir "evidence-manifest.json"
    if (-not (Test-Path $ManifestFile)) { throw "Manifest file not created" }

    $manifestObj = Get-Content $ManifestFile -Raw | ConvertFrom-Json

    # Check 1: headCommit exact match
    $expectedHead = (git rev-parse HEAD).Trim()
    if ($manifestObj.headCommit -ne $expectedHead) {
        throw "headCommit mismatch! Expected: $expectedHead, Actual: $($manifestObj.headCommit)"
    }

    # Check 2: headTree exact match
    $expectedTree = (git rev-parse "HEAD^{tree}").Trim()
    if ($manifestObj.headTree -ne $expectedTree) {
        throw "headTree mismatch! Expected: $expectedTree, Actual: $($manifestObj.headTree)"
    }

    # Check 3: originMainCommit exact match
    $expectedOriginMain = (git rev-parse refs/remotes/origin/main).Trim()
    if ($manifestObj.originMainCommit -ne $expectedOriginMain) {
        throw "originMainCommit mismatch! Expected: $expectedOriginMain, Actual: $($manifestObj.originMainCommit)"
    }

    # Check 4: trackedFileCount exact match
    $expectedTrackedCount = (git ls-files).Count
    if ([int]$manifestObj.trackedFileCount -ne [int]$expectedTrackedCount) {
        throw "trackedFileCount mismatch! Expected: $expectedTrackedCount, Actual: $($manifestObj.trackedFileCount)"
    }

    # Check 5: artifact sha256 exact match
    $expectedDummyHash = (Get-FileHash -Path $DummyFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $dummyArtifactInManifest = $manifestObj.artifacts | Where-Object { $_.path -eq (Get-Item $DummyFile).FullName }
    if ($null -eq $dummyArtifactInManifest) { throw "Dummy artifact not found in manifest" }
    if ($dummyArtifactInManifest.sha256 -ne $expectedDummyHash) {
        throw "Artifact SHA256 mismatch! Expected: $expectedDummyHash, Actual: $($dummyArtifactInManifest.sha256)"
    }

    # Check 6 & 7: worktreeClean boolean exact match
    $statusRaw = (git status --porcelain=v1)
    $currentStatus = if ($null -ne $statusRaw) { ($statusRaw -join "`n").Trim() } else { "" }
    $expectedClean = [string]::IsNullOrWhiteSpace($currentStatus)
    if ($manifestObj.worktreeClean -ne $expectedClean) {
        throw "worktreeClean mismatch! Expected: $expectedClean, Actual: $($manifestObj.worktreeClean)"
    }

    Write-Output "PASS test-capture-acceptance-evidence"
}
finally {
    if (Test-Path $TempEvidenceDir) {
        Remove-Item -Recurse -Force $TempEvidenceDir -ErrorAction SilentlyContinue
    }
}
