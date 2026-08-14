param([string]$InstanceName = '', [string]$Serial = '',
      [string]$OutputDirectory = 'build/t57-rd-evidence', [string]$TestCommand = '')
. "$PSScriptRoot/t57_rd_common.ps1"
exit (Invoke-T57RdCase -CaseName 'RD-04-service-framework-ownership' -InstanceName $InstanceName -Serial $Serial -OutputDirectory $OutputDirectory -TestCommand $TestCommand)
