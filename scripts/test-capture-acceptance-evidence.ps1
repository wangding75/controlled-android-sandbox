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

    # Helper script to create 4 valid run-result JSON files

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

    # ----------------------------------------------------
    # 8.1 Test Clean worktree & exact commitMessage match
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
        -RepositoryPath $TempRepoDir `
        -OutputDirectory $EvidenceDir `
        -RunResultsDirectory $RunDir `
        -SourceZipPath $ZipFile `
        -GitBundlePath $ValidBundle `
        -ApkPaths @($ApkFile)

    $manifest1 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifest1.worktreeClean -ne $true) {
        $st = git status --porcelain=v1
        throw "Self-test Clean failed! Expected worktreeClean=true, got git status: '$st'"
    }

    $expectedMsg1 = (git show -s --format=%s HEAD).Trim()
    if ($manifest1.commitMessage -ne $expectedMsg1) {
        throw "Self-test commitMessage mismatch! Expected '$expectedMsg1', got '$($manifest1.commitMessage)'"
    }

    # ----------------------------------------------------
    # 8.2 Test Dirty worktree
    # ----------------------------------------------------
    Add-Content -Path $testFile -Value "Dirty Modified Content"
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
        -RepositoryPath $TempRepoDir `
        -OutputDirectory $EvidenceDir `
        -RunResultsDirectory $RunDir `
        -SourceZipPath $ZipFile `
        -GitBundlePath $ValidBundle `
        -ApkPaths @($ApkFile)

    $manifest2 = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json
    if ($manifest2.worktreeClean -ne $false) {
        throw "Self-test Dirty failed! Expected worktreeClean=false"
    }

    git checkout -- file.txt

    # ----------------------------------------------------
    # 9.A Missing required run
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    Remove-Item (Join-Path $RunDir "static-android-compile.result.json")

    $threwA = $false
    try {
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath $ZipFile `
            -GitBundlePath $ValidBundle `
            -ApkPaths @($ApkFile) `
            -StrictFinalVerification
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
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath $ZipFile `
            -GitBundlePath $ValidBundle `
            -ApkPaths @($ApkFile) `
            -StrictFinalVerification
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
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath $ZipFile `
            -GitBundlePath $ValidBundle `
            -ApkPaths @($ApkFile) `
            -StrictFinalVerification
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
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath (Join-Path $TempRepoDir "non-existent-source.zip") `
            -GitBundlePath $ValidBundle `
            -ApkPaths @($ApkFile) `
            -StrictFinalVerification
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
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath $ZipFile `
            -GitBundlePath "" `
            -ApkPaths @($ApkFile) `
            -StrictFinalVerification
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
        & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
            -RepositoryPath $TempRepoDir `
            -OutputDirectory $EvidenceDir `
            -RunResultsDirectory $RunDir `
            -SourceZipPath $ZipFile `
            -GitBundlePath $ValidBundle `
            -ApkPaths @((Join-Path $TempRepoDir "non-existent.apk")) `
            -StrictFinalVerification
    } catch {
        $threwF = $true
    }
    if (-not $threwF) { throw "Reverse test F failed! Expected failure when APK file does not exist" }

    # ----------------------------------------------------
    # 9.G Complete Valid Evidence
    # ----------------------------------------------------
    Reset-RunResultsDir -TargetRunDir $RunDir
    & (Join-Path $PSScriptRoot "capture-acceptance-evidence.ps1") `
        -RepositoryPath $TempRepoDir `
        -OutputDirectory $EvidenceDir `
        -RunResultsDirectory $RunDir `
        -SourceZipPath $ZipFile `
        -GitBundlePath $ValidBundle `
        -ApkPaths @($ApkFile) `
        -StrictFinalVerification

    $manifestG = Get-Content (Join-Path $EvidenceDir "evidence-manifest.json") -Raw | ConvertFrom-Json

    if ($manifestG.bundleVerifyPassed -ne $true) {
        throw "Reverse test G failed! Expected bundleVerifyPassed=true"
    }
    if ($manifestG.runs.Count -lt 4) {
        throw "Reverse test G failed! Expected runs.Count >= 4, got $($manifestG.runs.Count)"
    }
    if ($manifestG.artifacts.Count -lt 3) {
        throw "Reverse test G failed! Expected artifacts to contain ZIP, Bundle, and APK, got $($manifestG.artifacts.Count)"
    }

    Write-Output "PASS test-capture-acceptance-evidence (all reverse tests A-G passed)"
}
finally {
    Set-Location $RepoRoot
    if (Test-Path $TempRepoDir) { Remove-Item -Recurse -Force $TempRepoDir -ErrorAction SilentlyContinue }
    if (Test-Path $EvidenceDir) { Remove-Item -Recurse -Force $EvidenceDir -ErrorAction SilentlyContinue }
    if (Test-Path $RunDir) { Remove-Item -Recurse -Force $RunDir -ErrorAction SilentlyContinue }
    if (Test-Path $ArtifactDir) { Remove-Item -Recurse -Force $ArtifactDir -ErrorAction SilentlyContinue }
}
