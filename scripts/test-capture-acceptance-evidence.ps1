$ErrorActionPreference = "Stop"

$RepoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
Set-Location $RepoRoot

$TempRepoDir = Join-Path $env:TEMP ("evidence-git-test-" + (Get-Random))
$BareRemote = Join-Path $env:TEMP ("evidence-remote-" + (Get-Random))
$EvidenceDir = $null
$RunDir = $null
$ArtifactDir = $null

if (Test-Path $TempRepoDir) { Remove-Item -Recurse -Force $TempRepoDir }
if (Test-Path $BareRemote) { Remove-Item -Recurse -Force $BareRemote }

try {
    # 1. Initialize temporary git repository with a real bare origin
    New-Item -ItemType Directory -Force -Path $TempRepoDir | Out-Null
    Set-Location $TempRepoDir

    git init -b main | Out-Null
    git config user.email "test@example.com"
    git config user.name "Test User"

    $testFile = Join-Path $TempRepoDir "file.txt"
    Set-Content -Path $testFile -Value "Initial Content"
    git add file.txt
    git commit -m "initial commit for evidence test" | Out-Null

    # Bare remote + push so refs/remotes/origin/main exists and matches HEAD
    git init --bare $BareRemote | Out-Null
    git remote add origin $BareRemote
    git push -u origin main | Out-Null
    git fetch origin | Out-Null

    $EvidenceDir = Join-Path $env:TEMP ("evidence-out-" + (Get-Random))
    $RunDir = Join-Path $env:TEMP ("run-out-" + (Get-Random))

    # Create dummy artifacts outside repo
    $ArtifactDir = Join-Path $env:TEMP ("artifact-out-" + (Get-Random))
    New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null
    $ZipFile = Join-Path $ArtifactDir "test-source.zip"
    Set-Content -Path $ZipFile -Value "Dummy Source Zip Data"

    $ApkFile = Join-Path $ArtifactDir "test-app-debug.apk"
    Set-Content -Path $ApkFile -Value "Dummy APK Data"

    $ValidBundle = Join-Path $ArtifactDir "valid.bundle"
    git bundle create $ValidBundle --all | Out-Null

    function Reset-RunResultsDir {
        param([string]$TargetRunDir)
        if (Test-Path $TargetRunDir) { Remove-Item -Recurse -Force $TargetRunDir }
        New-Item -ItemType Directory -Force -Path $TargetRunDir | Out-Null
        $names = @("static-android-compile", "strict-online-assemble", "strict-offline-assemble", "strict-directional-compile")
        foreach ($n in $names) {
            $obj = [PSCustomObject]@{
                name = $n
                command = "test-cmd $n"
                startedAtUtc = "2026-08-07T12:00:00Z"
                finishedAtUtc = "2026-08-07T12:01:00Z"
                exitCode = 0
                stdoutPath = (Join-Path $TargetRunDir "$n.stdout.log")
                stderrPath = (Join-Path $TargetRunDir "$n.stderr.log")
            }
            Set-Content -Path (Join-Path $TargetRunDir "$n.result.json") -Value ($obj | ConvertTo-Json -Depth 5)
        }
    }

    function Invoke-CaptureFinal {
        param(
            [string]$OutDir = $EvidenceDir,
            [string]$Runs = $RunDir,
            [string]$Zip = $ZipFile,
            [string]$Bundle = $ValidBundle,
            [string[]]$Apks = @($ApkFile)
        )
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $OutDir `
            -RunResultsDirectory $Runs `
            -SourceZipPath $Zip `
            -GitBundlePath $Bundle `
            -ApkPaths $Apks `
            -StrictFinalVerification
    }

    # ----------------------------------------------------
    # 8.1 Clean worktree & exact commitMessage match (Final)
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    Invoke-CaptureFinal

    $manifest1 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifest1.worktreeClean -ne $true) {
        $st = git status --porcelain=v1
        throw "Self-test Clean failed! Expected worktreeClean=true, got git status: '$st'"
    }

    $expectedMsg1 = (git show -s --format=%s HEAD).Trim()
    if ($manifest1.commitMessage -ne $expectedMsg1) {
        throw "Self-test commitMessage mismatch! Expected '$expectedMsg1', got '$($manifest1.commitMessage)'"
    }

    if ($manifest1.headMatchesOriginMain -ne $true) {
        throw "Self-test Clean failed! Expected headMatchesOriginMain=true"
    }

    # ----------------------------------------------------
    # 8.2 Non-Final: dirty worktree may still be recorded
    # ----------------------------------------------------
    Add-Content -Path $testFile -Value "Dirty Modified Content"
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
        -RepositoryPath $TempRepoDir `
        -OutputDirectory $EvidenceDir `
        -GitBundlePath $ValidBundle `
        -StrictFinalVerification:$false

    $manifest2 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json
    if ($manifest2.worktreeClean -ne $false) {
        throw "Self-test Dirty (non-final) failed! Expected worktreeClean=false"
    }

    # ----------------------------------------------------
    # T07-A Final dirty worktree MUST fail-closed
    # ----------------------------------------------------
    # worktree still dirty from 8.2
    Reset-RunResultsDir -TargetRunDir $RunDir
    $threwDirty = $false
    $errDirty = ""
    try {
        Invoke-CaptureFinal
    } catch {
        $threwDirty = $true
        $errDirty = $_.Exception.Message
        if ($_.Exception.InnerException) {
            $errDirty = "$errDirty $($_.Exception.InnerException.Message)"
        }
        $errDirty = "$errDirty $_"
    }
    if (-not $threwDirty) {
        throw "T07-A failed! Final capture succeeded on dirty worktree (must reject)"
    }
    if ($errDirty -notmatch "FINAL_EVIDENCE_WORKTREE_NOT_CLEAN") {
        throw "T07-A failed! Expected FINAL_EVIDENCE_WORKTREE_NOT_CLEAN, got: $errDirty"
    }

    git checkout -- file.txt
    if (-not [string]::IsNullOrWhiteSpace((git status --porcelain=v1))) {
        throw "Failed to restore clean worktree after dirty gate test"
    }

    # ----------------------------------------------------
    # T07-B Final HEAD != origin/main (unpushed) MUST fail-closed
    # ----------------------------------------------------
    Set-Content -Path $testFile -Value "Unpushed Content"
    git add file.txt
    git commit -m "local unpushed commit" | Out-Null
    # worktree clean, but HEAD has not been pushed
    if (-not [string]::IsNullOrWhiteSpace((git status --porcelain=v1))) {
        throw "T07-B setup failed: worktree not clean after unpushed commit"
    }
    $headNow = (git rev-parse HEAD).Trim()
    $originNow = (git rev-parse refs/remotes/origin/main).Trim()
    if ($headNow -eq $originNow) {
        throw "T07-B setup failed: HEAD still equals origin/main (need unpushed divergence)"
    }

    # Refresh bundle to include new commit so only HEAD gate fails (not bundle)
    git bundle create $ValidBundle --all | Out-Null
    Reset-RunResultsDir -TargetRunDir $RunDir

    $threwHead = $false
    $errHead = ""
    try {
        Invoke-CaptureFinal
    } catch {
        $threwHead = $true
        $errHead = $_.Exception.Message
        if ($_.Exception.InnerException) {
            $errHead = "$errHead $($_.Exception.InnerException.Message)"
        }
        $errHead = "$errHead $_"
    }
    if (-not $threwHead) {
        throw "T07-B failed! Final capture succeeded when HEAD != origin/main (must reject)"
    }
    if ($errHead -notmatch "FINAL_EVIDENCE_HEAD_NOT_ORIGIN_MAIN") {
        throw "T07-B failed! Expected FINAL_EVIDENCE_HEAD_NOT_ORIGIN_MAIN, got: $errHead"
    }

    # Restore synced HEAD for remaining tests
    git reset --hard origin/main | Out-Null
    git bundle create $ValidBundle --all | Out-Null

    # ----------------------------------------------------
    # 9.A Missing required run
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    Remove-Item (Join-Path $RunDir "static-android-compile.result.json")

    $threwA = $false
    try {
        Invoke-CaptureFinal
    } catch {
        $threwA = $true
    }
    if (-not $threwA) { throw "Reverse test A failed! Expected failure when required run is missing" }

    # ----------------------------------------------------
    # 9.B Required run exit code != 0
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    $objB = Get-Content (Join-Path $RunDir "strict-online-assemble.result.json") -Raw | ConvertFrom-Json
    $objB.exitCode = 1
    Set-Content -Path (Join-Path $RunDir "strict-online-assemble.result.json") -Value ($objB | ConvertTo-Json -Depth 5)

    $threwB = $false
    try {
        Invoke-CaptureFinal
    } catch {
        $threwB = $true
    }
    if (-not $threwB) { throw "Reverse test B failed! Expected failure when required run has exitCode != 0" }

    # ----------------------------------------------------
    # 9.C Duplicate required run
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    Copy-Item (Join-Path $RunDir "static-android-compile.result.json") (Join-Path $RunDir "static-android-compile-dup.result.json")

    $threwC = $false
    try {
        Invoke-CaptureFinal
    } catch {
        $threwC = $true
    }
    if (-not $threwC) { throw "Reverse test C failed! Expected failure when duplicate required run name exists" }

    # ----------------------------------------------------
    # 9.D Missing Source ZIP
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    $threwD = $false
    try {
        Invoke-CaptureFinal -Zip (Join-Path $TempRepoDir "non-existent-source.zip")
    } catch {
        $threwD = $true
    }
    if (-not $threwD) { throw "Reverse test D failed! Expected failure when Source ZIP does not exist" }

    # ----------------------------------------------------
    # 9.E Missing Git Bundle
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    $threwE = $false
    try {
        Invoke-CaptureFinal -Bundle ""
    } catch {
        $threwE = $true
    }
    if (-not $threwE) { throw "Reverse test E failed! Expected failure when Git Bundle is missing in strict final mode" }

    # ----------------------------------------------------
    # 9.F Missing APK
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    $threwF = $false
    try {
        Invoke-CaptureFinal -Apks @((Join-Path $TempRepoDir "non-existent.apk"))
    } catch {
        $threwF = $true
    }
    if (-not $threwF) { throw "Reverse test F failed! Expected failure when APK file does not exist" }

    # ----------------------------------------------------
    # 9.G / T07 positive: clean + synced Final gate success
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    if (-not [string]::IsNullOrWhiteSpace((git status --porcelain=v1))) {
        throw "Positive final gate setup failed: worktree not clean"
    }
    $headPos = (git rev-parse HEAD).Trim()
    $originPos = (git rev-parse refs/remotes/origin/main).Trim()
    if ($headPos -ne $originPos) {
        throw "Positive final gate setup failed: HEAD != origin/main"
    }

    Invoke-CaptureFinal

    $manifestG = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifestG.worktreeClean -ne $true) {
        throw "Positive final gate failed! Expected worktreeClean=true"
    }
    if ($manifestG.headMatchesOriginMain -ne $true) {
        throw "Positive final gate failed! Expected headMatchesOriginMain=true"
    }
    if ($manifestG.headCommit -ne $manifestG.originMainCommit) {
        throw "Positive final gate failed! Expected headCommit == originMainCommit (full SHA)"
    }
    if ($manifestG.bundleVerifyPassed -ne $true) {
        throw "Positive final gate failed! Expected bundleVerifyPassed=true"
    }
    if ($manifestG.runs.Count -lt 4) {
        throw "Positive final gate failed! Expected runs.Count >= 4, got $($manifestG.runs.Count)"
    }
    foreach ($rn in @("static-android-compile", "strict-online-assemble", "strict-offline-assemble", "strict-directional-compile")) {
        $m = @($manifestG.runs | Where-Object { $_.name -eq $rn })
        if ($m.Count -ne 1 -or [int]$m[0].exitCode -ne 0) {
            throw "Positive final gate failed! Required run '$rn' missing or exitCode != 0"
        }
    }
    if ($manifestG.artifacts.Count -lt 3) {
        throw "Positive final gate failed! Expected artifacts to contain ZIP, Bundle, and APK, got $($manifestG.artifacts.Count)"
    }

    Write-Output "PASS test-capture-acceptance-evidence (dirty final reject, unpushed HEAD reject, clean+synced final success, reverse A-F)"
}
finally {
    Set-Location $RepoRoot
    if (Test-Path $TempRepoDir) { Remove-Item -Recurse -Force $TempRepoDir -ErrorAction SilentlyContinue }
    if (Test-Path $BareRemote) { Remove-Item -Recurse -Force $BareRemote -ErrorAction SilentlyContinue }
    if ($EvidenceDir -and (Test-Path $EvidenceDir)) { Remove-Item -Recurse -Force $EvidenceDir -ErrorAction SilentlyContinue }
    if ($RunDir -and (Test-Path $RunDir)) { Remove-Item -Recurse -Force $RunDir -ErrorAction SilentlyContinue }
    if ($ArtifactDir -and (Test-Path $ArtifactDir)) { Remove-Item -Recurse -Force $ArtifactDir -ErrorAction SilentlyContinue }
}
