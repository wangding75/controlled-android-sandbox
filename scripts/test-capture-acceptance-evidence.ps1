$ErrorActionPreference = "Stop"

$RepoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
Set-Location $RepoRoot

$TempRepoDir = Join-Path $env:TEMP ("evidence-git-test-" + (Get-Random))
if (Test-Path $TempRepoDir) { Remove-Item -Recurse -Force $TempRepoDir }

try {
    # 1. Initialize temporary git repository
    New-Item -ItemType Directory -Force -Path $TempRepoDir | Out-Null
    Set-Location $TempRepoDir

    git init -b main | Out-Null
    git config user.email "test@example.com"
    git config user.name "Test User"

    $testFile = Join-Path $TempRepoDir "file.txt"
    Set-Content -Path $testFile -Value "Initial Content"
    git add file.txt
    git commit -m "initial commit for evidence test" | Out-Null

    # Create origin remote baseline locally
    git remote add origin $TempRepoDir
    git update-ref refs/remotes/origin/main (git rev-parse HEAD)

    $EvidenceDir = Join-Path $TempRepoDir "evidence"

    # ----------------------------------------------------
    # 8.1 Test Clean worktree & exact commitMessage match
    # ----------------------------------------------------
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") -RepositoryPath $TempRepoDir -OutputDirectory $EvidenceDir
    $manifest1 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifest1.worktreeClean -ne $true) {
        throw "Self-test 8.1 Clean failed! Expected worktreeClean=true, got $($manifest1.worktreeClean)"
    }

    $expectedHead1 = (git rev-parse HEAD).Trim()
    if ($manifest1.headCommit -ne $expectedHead1) {
        throw "Self-test 8.1 headCommit mismatch! Expected $expectedHead1, got $($manifest1.headCommit)"
    }

    $expectedMsg1 = (git show -s --format=%s HEAD).Trim()
    if ($manifest1.commitMessage -ne $expectedMsg1) {
        throw "Self-test 8.1 commitMessage mismatch! Expected '$expectedMsg1', got '$($manifest1.commitMessage)'"
    }

    # ----------------------------------------------------
    # 8.2 Test Dirty worktree
    # ----------------------------------------------------
    Add-Content -Path $testFile -Value "Dirty Modified Content"
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") -RepositoryPath $TempRepoDir -OutputDirectory $EvidenceDir
    $manifest2 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifest2.worktreeClean -ne $false) {
        throw "Self-test 8.2 Dirty failed! Expected worktreeClean=false, got $($manifest2.worktreeClean)"
    }

    # Revert dirty modification for bundle creation
    git checkout -- file.txt

    # ----------------------------------------------------
    # 8.3 Test Valid Bundle
    # ----------------------------------------------------
    $ValidBundle = Join-Path $TempRepoDir "valid.bundle"
    git bundle create $ValidBundle --all | Out-Null

    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") -RepositoryPath $TempRepoDir -OutputDirectory $EvidenceDir -GitBundlePath $ValidBundle
    $manifest3 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifest3.bundleVerifyPassed -ne $true -or $manifest3.bundleVerifyExitCode -ne 0) {
        throw "Self-test 8.3 Valid Bundle failed! Expected bundleVerifyPassed=true, got exitCode=$($manifest3.bundleVerifyExitCode)"
    }

    # ----------------------------------------------------
    # 8.4 Test Invalid Bundle
    # ----------------------------------------------------
    $InvalidBundle = Join-Path $TempRepoDir "invalid.bundle"
    Set-Content -Path $InvalidBundle -Value "CORRUPTED INVALID BUNDLE HEADER DATA"

    $invalidFailedAsExpected = $false
    try {
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") -RepositoryPath $TempRepoDir -OutputDirectory $EvidenceDir -GitBundlePath $InvalidBundle
    } catch {
        $invalidFailedAsExpected = $true
    }

    if (-not $invalidFailedAsExpected) {
        throw "Self-test 8.4 Invalid Bundle failed! Expected capture script to fail non-zero on corrupt bundle"
    }

    $manifest4 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json
    if ($manifest4.bundleVerifyPassed -ne $false -or $manifest4.bundleVerifyExitCode -eq 0) {
        throw "Self-test 8.4 Invalid Bundle manifest record failed! Expected bundleVerifyPassed=false"
    }

    Write-Output "PASS test-capture-acceptance-evidence (all clean/dirty/valid/invalid reverse tests passed)"
}
finally {
    Set-Location $RepoRoot
    if (Test-Path $TempRepoDir) {
        Remove-Item -Recurse -Force $TempRepoDir -ErrorAction SilentlyContinue
    }
}
