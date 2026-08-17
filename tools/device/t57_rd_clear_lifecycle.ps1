param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence')

# Keep one authoritative implementation of the destructive lifecycle transaction.  The former
# wrapper called Invoke-T57RdCase without a fixture command, which only emitted a pending status
# and could be mistaken for an executed clear/delete test by the surrounding suite.
& (Join-Path $PSScriptRoot 't57_rd_lifecycle_probe.ps1') `
    -InstanceName $InstanceName -Serial $Serial -OutputDirectory $OutputDirectory
exit $LASTEXITCODE
