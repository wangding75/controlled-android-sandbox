Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-T57AdbProperty([string]$Serial, [string]$Name) {
    $value = & adb -s $Serial shell getprop $Name 2>$null
    if ($LASTEXITCODE -ne 0) { return '' }
    return (($value | Out-String).Trim())
}

function Get-T57MuMuIndexForSerial([string]$Serial) {
    if ($Serial -notmatch ':(\d+)$') { return '' }
    $port = [int]$matches[1]
    $connections = @(Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue)
    $ownerPids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($ownerPid in $ownerPids) {
        $vmProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerPid" -ErrorAction SilentlyContinue
        if ($vmProcess -and $vmProcess.Name -eq 'MuMuVMMHeadless.exe' -and
            $vmProcess.CommandLine -match '--comment\s+MuMuPlayer-12\.0-(\d+)') {
            return $matches[1]
        }
    }
    return ''
}

function Get-T57MuMuWindowTitle([string]$Index) {
    if ([string]::IsNullOrWhiteSpace($Index)) { return '' }
    $players = @(Get-CimInstance Win32_Process -Filter "Name = 'MuMuPlayer.exe'" -ErrorAction SilentlyContinue)
    foreach ($player in $players) {
        if ($player.CommandLine -notmatch '(?:^|\s)-v\s+' + [regex]::Escape($Index) + '(?:\s|$)') { continue }
        $process = Get-Process -Id $player.ProcessId -ErrorAction SilentlyContinue
        if ($process -and !([string]::IsNullOrWhiteSpace($process.MainWindowTitle))) {
            return [string]$process.MainWindowTitle
        }
    }
    $configPath = "C:\Program Files\Netease\MuMu Player 12\vms\MuMuPlayer-12.0-$Index\configs\extra_config.json"
    if (Test-Path $configPath) {
        try {
            $json = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($json.playerName) { return [string]$json.playerName }
        } catch { }
    }
    return ''
}

function Resolve-T57RdDevice {
    param([string]$InstanceName = '', [string]$Serial = '')
    if ([string]::IsNullOrWhiteSpace($InstanceName)) { $InstanceName = 'RD' + [char]0x6d4b + [char]0x8bd5 }
    $rows = @(& adb devices -l 2>$null | Select-Object -Skip 1)
    if ($LASTEXITCODE -ne 0) { throw 'ADB_NOT_AVAILABLE' }
    $candidates = @()
    foreach ($row in $rows) {
        $text = ([string]$row).Trim()
        if (!$text -or $text -match '^\* daemon') { continue }
        $parts = $text -split '\s+'
        if ($parts.Count -lt 2 -or $parts[1] -ne 'device') { continue }
        $candidateSerial = $parts[0]
        if ($Serial -and $candidateSerial -ne $Serial) { continue }
        $model = Get-T57AdbProperty $candidateSerial 'ro.product.model'
        $device = Get-T57AdbProperty $candidateSerial 'ro.product.device'
        $api = Get-T57AdbProperty $candidateSerial 'ro.build.version.sdk'
        $android = Get-T57AdbProperty $candidateSerial 'ro.build.version.release'
        $bootId = ((& adb -s $candidateSerial shell cat /proc/sys/kernel/random/boot_id 2>$null) | Out-String).Trim()
        $avd = Get-T57AdbProperty $candidateSerial 'ro.boot.qemu.avd_name'
        $mumu = Get-T57AdbProperty $candidateSerial 'ro.mumu.instance.name'
        $mumuIndex = Get-T57MuMuIndexForSerial $candidateSerial
        $windowTitle = Get-T57MuMuWindowTitle $mumuIndex
        $identity = "$avd $mumu $windowTitle $model $device $($parts -join ' ')"
        $candidates += [pscustomobject]@{
            InstanceName = $InstanceName; Serial = $candidateSerial; Model = $model
            Device = $device; API = $api; Android = $android; BootId = $bootId
            MuMuIndex = $mumuIndex; DesktopWindowTitle = $windowTitle
            InstanceMatch = $identity.Contains($InstanceName)
            State = 'device'
        }
    }
    $matches = @($candidates | Where-Object { $_.InstanceMatch })
    if ($matches.Count -ne 1) {
        $summary = ($candidates | ConvertTo-Json -Compress)
        throw "RD_INSTANCE_RESOLUTION_FAILED: expected one '$InstanceName' match; candidates=$summary"
    }
    $selected = $matches[0]
    $state = ((& adb -s $selected.Serial get-state 2>$null) | Out-String).Trim()
    $bootCompleted = Get-T57AdbProperty $selected.Serial 'sys.boot_completed'
    if ($state -ne 'device') { throw "RD_DEVICE_STATE_FAILED:$state" }
    if ($bootCompleted -ne '1') { throw "RD_BOOT_NOT_COMPLETED:$bootCompleted" }
    if ($selected.API -ne '32') { throw "RD_API_MISMATCH:$($selected.API)" }
    if ([string]::IsNullOrWhiteSpace($selected.BootId)) { throw 'RD_BOOT_ID_MISSING' }
    return $selected
}

function Write-T57RdEvidence([object]$Device, [string]$CaseName, [string]$OutputDirectory) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $record = [pscustomobject]@{
        case = $CaseName; instanceName = $Device.InstanceName; serial = $Device.Serial
        model = $Device.Model; device = $Device.Device; api = $Device.API
        android = $Device.Android; bootId = $Device.BootId
        capturedAt = (Get-Date).ToUniversalTime().ToString('o')
        gitHead = (git rev-parse HEAD); gitTree = (git status --porcelain)
    }
    $record | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$CaseName-device.json")
    & adb -s $Device.Serial logcat -d -v threadtime -s CS_RUNTIME:V CS_DIAGNOSTICS:V CS_FIXTURE:V CS_COMMAND:V '*:S' |
        Set-Content -Encoding UTF8 (Join-Path $OutputDirectory "$CaseName-logcat.txt")
    return $record
}

function Invoke-T57RdCase {
    param([string]$CaseName, [string]$InstanceName = '', [string]$Serial = '',
          [string]$OutputDirectory = 'build/t57-rd-evidence', [string]$TestCommand = '')
    try {
        $device = Resolve-T57RdDevice -InstanceName $InstanceName -Serial $Serial
        $null = Write-T57RdEvidence -Device $device -CaseName $CaseName -OutputDirectory $OutputDirectory
        if ([string]::IsNullOrWhiteSpace($TestCommand)) {
            Write-Output "RESULT: DEVICE_REGRESSION_PENDING case=$CaseName reason=fixture command not supplied"
            return 2
        }
        Invoke-Expression $TestCommand
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "CASE_COMMAND_FAILED:$LASTEXITCODE" }
        Write-Output "RESULT: PASS case=$CaseName"
        return 0
    } catch {
        Write-Error $_
        Write-Output "RESULT: BLOCKED case=$CaseName reason=$($_.Exception.Message)"
        return 1
    }
}
