[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BundlePath,
    [Parameter(Mandatory = $true)]
    [string]$Destination,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedTree
)

$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
if (Test-Path -LiteralPath $Destination) {
    throw "Destination already exists; restore is intentionally non-destructive: $Destination"
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null
git -C $Destination init -b feature/sx-migration | Out-Null
git -C $Destination remote add bundle $bundle
git -C $Destination fetch bundle 'refs/heads/feature/sx-migration:refs/remotes/bundle/feature/sx-migration'
git -C $Destination checkout -b feature/sx-migration $ExpectedCommit

$actualCommit = (git -C $Destination rev-parse HEAD).Trim()
$actualTree = (git -C $Destination rev-parse 'HEAD^{tree}').Trim()
if ($actualCommit -ne $ExpectedCommit) {
    throw "Restored commit mismatch: expected $ExpectedCommit, got $actualCommit"
}
if ($actualTree -ne $ExpectedTree) {
    throw "Restored tree mismatch: expected $ExpectedTree, got $actualTree"
}

Write-Output "RESTORE PASS commit=$actualCommit tree=$actualTree destination=$Destination"
