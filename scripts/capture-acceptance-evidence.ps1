[CmdletBinding()]
param(
    [string]$RepositoryPath = $PSScriptRoot + "\..",
    [string]$OutputDirectory = "evidence",
    [string[]]$ApkPaths = @(),
    [string]$SourceZipPath = "",
    [string]$GitBundlePath = "",
    [string]$RunResultsDirectory = "",
    [switch]$StrictFinalVerification
)

$ErrorActionPreference = "Stop"

$RepoFull = (Get-Item $RepositoryPath).FullName
$OriginalLocation = Get-Location
Set-Location $RepoFull

try {
    # Auto-enable StrictFinalVerification if delivery inputs or run results directory are passed
    if (-not $PSBoundParameters.ContainsKey('StrictFinalVerification')) {
        if (-not [string]::IsNullOrWhiteSpace($RunResultsDirectory) -or
            -not [string]::IsNullOrWhiteSpace($GitBundlePath) -or
            -not [string]::IsNullOrWhiteSpace($SourceZipPath) -or
            ($ApkPaths -and $ApkPaths.Count -gt 0)) {
            $StrictFinalVerification = [switch]::Present
        }
    }

    if (-not (Test-Path $OutputDirectory)) {
        New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    }
    $OutFull = (Get-Item $OutputDirectory).FullName

    # Directly capture Git fields
    $headCommit = (git rev-parse HEAD).Trim()
    $headTree = (git rev-parse "HEAD^{tree}").Trim()
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $parentRaw = (git rev-parse "HEAD^" 2>$null)
    $ErrorActionPreference = $oldEap
    $parentCommit = if ($null -ne $parentRaw -and -not [string]::IsNullOrWhiteSpace($parentRaw)) { ($parentRaw -join "").Trim() } else { "N/A" }

    $commitMessage = (git show -s --format=%s HEAD).Trim()
    $originMainCommit = (git rev-parse refs/remotes/origin/main).Trim()
    $branch = (git branch --show-current).Trim()
    $statusRaw = (git status --porcelain=v1)
    $statusText = if ($null -ne $statusRaw) { ($statusRaw -join "`n").Trim() } else { "" }
    $worktreeClean = [string]::IsNullOrWhiteSpace($statusText)

    $trackedFiles = git ls-files
    $trackedFileCount = if ($null -eq $trackedFiles) { 0 } elseif ($trackedFiles -is [array]) { $trackedFiles.Count } else { 1 }
    $log1 = (git log -1 --format=fuller) -join "`n"

    # Write raw text outputs
    Set-Content -Path (Join-Path $OutFull "git-head.txt") -Value $headCommit -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-tree.txt") -Value $headTree -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-parent.txt") -Value $parentCommit -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-commit-message.txt") -Value $commitMessage -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-origin-main.txt") -Value $originMainCommit -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-branch.txt") -Value $branch -NoNewline
    Set-Content -Path (Join-Path $OutFull "git-status.txt") -Value $statusText
    Set-Content -Path (Join-Path $OutFull "git-log-1.txt") -Value $log1
    Set-Content -Path (Join-Path $OutFull "tracked-file-count.txt") -Value ($trackedFileCount.ToString()) -NoNewline

    # Process Bundle verify
    $bundleVerifyExitCode = -1
    $bundleVerifyPassed = $false
    if (-not [string]::IsNullOrWhiteSpace($GitBundlePath)) {
        if (-not (Test-Path $GitBundlePath)) {
            Set-Content -Path (Join-Path $OutFull "bundle-verify.txt") -Value "FILE_NOT_FOUND: $GitBundlePath"
            $bundleVerifyExitCode = -1
            $bundleVerifyPassed = $false
            if ($StrictFinalVerification) {
                throw "Git bundle file specified but does not exist: $GitBundlePath"
            }
        } else {
            $oldEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            $bundleVerifyOut = (git bundle verify "$GitBundlePath" 2>&1) -join "`n"
            $bundleVerifyExitCode = $LASTEXITCODE
            $ErrorActionPreference = $oldEap
            Set-Content -Path (Join-Path $OutFull "bundle-verify.txt") -Value $bundleVerifyOut
            $bundleVerifyPassed = ($bundleVerifyExitCode -eq 0)
        }
    } else {
        Set-Content -Path (Join-Path $OutFull "bundle-verify.txt") -Value "NOT_PROVIDED"
        $bundleVerifyExitCode = -1
        $bundleVerifyPassed = $false
        if ($StrictFinalVerification) {
            throw "Git bundle path was not provided in strict final verification mode"
        }
    }

    # Process runs from RunResultsDirectory
    $runsList = @()
    $requiredRunNames = @(
        "static-android-compile",
        "strict-online-assemble",
        "strict-offline-assemble",
        "strict-directional-compile"
    )

    if (-not [string]::IsNullOrWhiteSpace($RunResultsDirectory)) {
        if (-not (Test-Path $RunResultsDirectory)) {
            if ($StrictFinalVerification) {
                throw "RunResultsDirectory specified but does not exist: $RunResultsDirectory"
            }
        } else {
            $resultFiles = Get-ChildItem -Path $RunResultsDirectory -Filter "*.result.json" | Sort-Object Name
            foreach ($rf in $resultFiles) {
                $runObj = Get-Content $rf.FullName -Raw | ConvertFrom-Json
                $runsList += $runObj
            }
        }
    }

    if ($StrictFinalVerification) {
        if ([string]::IsNullOrWhiteSpace($RunResultsDirectory) -or -not (Test-Path $RunResultsDirectory) -or (Get-ChildItem -Path $RunResultsDirectory -Filter "*.result.json").Count -eq 0) {
            throw "RunResultsDirectory is empty or missing in strict final verification mode"
        }

        # Check required runs
        foreach ($reqName in $requiredRunNames) {
            $matches = @($runsList | Where-Object { $_.name -eq $reqName })
            if ($matches.Count -eq 0) {
                throw "Required run '$reqName' is missing from run results"
            }
            if ($matches.Count -gt 1) {
                throw "Duplicate run entry found for required run '$reqName' (count: $($matches.Count))"
            }
            if ([int]$matches[0].exitCode -ne 0) {
                throw "Required run '$reqName' failed with exitCode $($matches[0].exitCode)"
            }
        }
    }

    # Process artifacts (APKs, Source Zip, Git Bundle)
    $artifactList = @()
    $artifactHashLines = @()

    if ($StrictFinalVerification) {
        if ([string]::IsNullOrWhiteSpace($SourceZipPath)) {
            throw "SourceZipPath is missing in strict final verification mode"
        }
        if (-not (Test-Path $SourceZipPath)) {
            throw "SourceZipPath does not exist: $SourceZipPath"
        }

        if ($null -eq $ApkPaths -or $ApkPaths.Count -eq 0) {
            throw "ApkPaths list is empty in strict final verification mode"
        }
        foreach ($apk in $ApkPaths) {
            if ([string]::IsNullOrWhiteSpace($apk) -or -not (Test-Path $apk)) {
                throw "APK artifact file does not exist: $apk"
            }
        }
    }

    $allPaths = @()
    if ($ApkPaths) { $allPaths += $ApkPaths }
    if (-not [string]::IsNullOrWhiteSpace($SourceZipPath)) { $allPaths += $SourceZipPath }
    if (-not [string]::IsNullOrWhiteSpace($GitBundlePath)) { $allPaths += $GitBundlePath }

    foreach ($p in $allPaths) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        if (-not (Test-Path $p)) {
            throw "Artifact file specified does not exist: $p"
        }
        $item = Get-Item $p
        $hash = (Get-FileHash -Path $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $size = $item.Length
        $artifactList += [PSCustomObject]@{
            path = $item.FullName
            sizeBytes = $size
            sha256 = $hash
        }
        $artifactHashLines += ("{0}  {1}  {2} bytes" -f $hash, $item.FullName, $size)
    }

    Set-Content -Path (Join-Path $OutFull "artifact-hashes.txt") -Value ($artifactHashLines -join "`n")

    # Construct JSON manifest
    $manifest = [PSCustomObject]@{
        schemaVersion = "1.0"
        capturedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        repository = $RepoFull
        branch = $branch
        headCommit = $headCommit
        headTree = $headTree
        parentCommit = $parentCommit
        commitMessage = $commitMessage
        originMainCommit = $originMainCommit
        worktreeClean = $worktreeClean
        trackedFileCount = $trackedFileCount
        bundleVerifyExitCode = $bundleVerifyExitCode
        bundleVerifyPassed = $bundleVerifyPassed
        runs = $runsList
        artifacts = $artifactList
    }

    $json = $manifest | ConvertTo-Json -Depth 10
    Set-Content -Path (Join-Path $OutFull "evidence-manifest.json") -Value $json

    # Fail closed if bundle verify failed
    if (-not $bundleVerifyPassed) {
        throw "Git bundle verification failed or was not passed (exit code: $bundleVerifyExitCode)"
    }

    Write-Output "Captured acceptance evidence successfully to $OutFull"
}
finally {
    Set-Location $OriginalLocation
}
