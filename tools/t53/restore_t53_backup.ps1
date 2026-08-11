param(
    [Parameter(Mandatory = $true)]
    [string] $BundlePath,
    [Parameter(Mandatory = $true)]
    [string] $RestorePath,
    [Parameter(Mandatory = $true)]
    [string] $ExpectedCommit,
    [Parameter(Mandatory = $true)]
    [string] $ExpectedTree
)

$ErrorActionPreference = 'Stop'

$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
$restore = [System.IO.Path]::GetFullPath($RestorePath)
if (Test-Path -LiteralPath $restore) {
    $existing = @(Get-ChildItem -LiteralPath $restore -Force)
    if ($existing.Count -ne 0) {
        throw "Restore target must be absent or empty: $restore"
    }
} else {
    New-Item -ItemType Directory -Path $restore | Out-Null
}

& git clone --quiet $bundle $restore
if ($LASTEXITCODE -ne 0) { throw "git clone failed" }

$actualCommit = (& git -C $restore rev-parse HEAD).Trim()
$actualTree = (& git -C $restore rev-parse 'HEAD^{tree}').Trim()
$statusLines = & git -C $restore status --porcelain=v1
$status = if ($null -eq $statusLines) { '' } else { ($statusLines | Out-String).Trim() }
if ($actualCommit -ne $ExpectedCommit) {
    throw "Restored HEAD mismatch: expected $ExpectedCommit, found $actualCommit"
}
if ($actualTree -ne $ExpectedTree) {
    throw "Restored tree mismatch: expected $ExpectedTree, found $actualTree"
}
if (-not [string]::IsNullOrWhiteSpace($status)) {
    throw "Restored worktree is not clean: $status"
}

Write-Output "RESTORE_PASS"
Write-Output "HEAD=$actualCommit"
Write-Output "TREE=$actualTree"
Write-Output "WORKTREE=CLEAN"
