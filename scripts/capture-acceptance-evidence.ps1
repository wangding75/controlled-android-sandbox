[CmdletBinding()]
param(
    [string]$RepositoryPath = $PSScriptRoot + "\..",
    [string]$OutputDirectory = "evidence",
    [string[]]$ApkPaths = @(),
    [string]$SourceZipPath = "",
    [string]$GitBundlePath = "",
    [string]$RunResultsDirectory = ""
)

$ErrorActionPreference = "Stop"

$RepoFull = (Get-Item $RepositoryPath).FullName
$OriginalLocation = Get-Location
Set-Location $RepoFull

try {
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
    if (-not [string]::IsNullOrWhiteSpace($GitBundlePath) -and (Test-Path $GitBundlePath)) {
        $oldEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $bundleVerifyOut = (git bundle verify "$GitBundlePath" 2>&1) -join "`n"
        $bundleVerifyExitCode = $LASTEXITCODE
        $ErrorActionPreference = $oldEap
        Set-Content -Path (Join-Path $OutFull "bundle-verify.txt") -Value $bundleVerifyOut
        if ($bundleVerifyExitCode -eq 0) {
            $bundleVerifyPassed = $true
        } else {
            $bundleVerifyPassed = $false
        }
    } else {
        Set-Content -Path (Join-Path $OutFull "bundle-verify.txt") -Value "N/A"
        $bundleVerifyExitCode = 0
        $bundleVerifyPassed = $true
    }

    # Process runs from RunResultsDirectory
    $runsList = @()
    if (-not [string]::IsNullOrWhiteSpace($RunResultsDirectory) -and (Test-Path $RunResultsDirectory)) {
        $resultFiles = Get-ChildItem -Path $RunResultsDirectory -Filter "*.result.json" | Sort-Object Name
        foreach ($rf in $resultFiles) {
            $runObj = Get-Content $rf.FullName -Raw | ConvertFrom-Json
            $runsList += $runObj
        }
    }

    # Process artifacts (APKs, Source Zip, Git Bundle)
    $artifactList = @()
    $artifactHashLines = @()

    $allPaths = @()
    if ($ApkPaths) { $allPaths += $ApkPaths }
    if (-not [string]::IsNullOrWhiteSpace($SourceZipPath)) { $allPaths += $SourceZipPath }
    if (-not [string]::IsNullOrWhiteSpace($GitBundlePath)) { $allPaths += $GitBundlePath }

    foreach ($p in $allPaths) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        if (Test-Path $p) {
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
        throw "Git bundle verification failed with exit code $bundleVerifyExitCode"
    }

    Write-Output "Captured acceptance evidence successfully to $OutFull"
}
finally {
    Set-Location $OriginalLocation
}
