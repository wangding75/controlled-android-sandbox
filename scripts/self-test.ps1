$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Build = Join-Path $Root 'build\self-test'
Remove-Item $Build -Recurse -Force -ErrorAction SilentlyContinue
New-Item (Join-Path $Build 'domain') -ItemType Directory -Force | Out-Null
New-Item (Join-Path $Build 'wrapper') -ItemType Directory -Force | Out-Null
$Domain = @(
  Get-ChildItem (Join-Path $Root 'sandbox-domain\src\main\java') -Recurse -Filter *.java
  Get-ChildItem (Join-Path $Root 'sandbox-domain\src\testHarness\java') -Recurse -Filter *.java
) | ForEach-Object FullName
& javac --release 17 -encoding UTF-8 -d (Join-Path $Build 'domain') $Domain
& java -cp (Join-Path $Build 'domain') com.warden.controlledsandbox.domain.SelfTest
& javac --release 17 -encoding UTF-8 -d (Join-Path $Build 'wrapper') (Join-Path $Root 'tools\wrapper-src\org\gradle\wrapper\GradleWrapperMain.java')
& jar --create --file (Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar') -C (Join-Path $Build 'wrapper') .
Get-ChildItem $Root -Recurse -Filter *.xml |
  Where-Object { $_.FullName -notmatch '\\(?:build|\.gradle[^\\]*)\\' } |
  ForEach-Object { [xml](Get-Content $_.FullName -Raw) | Out-Null }
Write-Host 'PASS repository self-test'
