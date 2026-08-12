param(
    [Parameter(Mandatory = $true)]
    [string] $BackupRoot,
    [Parameter(Mandatory = $true)]
    [string] $Destination
)

$ErrorActionPreference = 'Stop'
$backup = (Resolve-Path -LiteralPath $BackupRoot).Path
$target = [System.IO.Path]::GetFullPath($Destination)
$zip = Join-Path $backup 'T54-source.zip'
$bundle = Join-Path $backup 'T54-source.bundle'
$manifest = Join-Path $backup 'T54-manifest.txt'

if (-not (Test-Path -LiteralPath $zip -PathType Leaf)) { throw "Missing $zip" }
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "Missing $manifest" }
if (Test-Path -LiteralPath $target) {
    $children = @(Get-ChildItem -LiteralPath $target -Force)
    if ($children.Count -gt 0) { throw "Destination must be empty: $target" }
} else {
    New-Item -ItemType Directory -Path $target | Out-Null
}

$expected = (Get-Content -LiteralPath $manifest | Where-Object { $_ -like 'T54-source.zip SHA256=*' })
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash
if ($expected -and $expected -ne "T54-source.zip SHA256=$actual") {
    throw 'Source ZIP SHA-256 does not match T54-manifest.txt'
}

Expand-Archive -LiteralPath $zip -DestinationPath $target -Force
if (Test-Path -LiteralPath $bundle -PathType Leaf) {
    git bundle verify $bundle
    if ($LASTEXITCODE -ne 0) { throw 'Git bundle verification failed' }
}
Write-Output "Restored T54 source to $target"
