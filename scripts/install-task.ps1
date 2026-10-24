# Registers a Windows scheduled task that runs check.sh every 2 hours, hidden, so a Fire OS update
# that undoes the setup gets fixed on its own. Run it again to change the options; remove the task
# with: Unregister-ScheduledTask -TaskName "Fire TV - verification" -Confirm:$false
#
#   powershell -ExecutionPolicy Bypass -File scripts\install-task.ps1 -Ip 192.168.1.42 [-SetupArgs "--keep alexa"]
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$SetupArgs = ""
)

$repo = Split-Path -Parent $PSScriptRoot
$bash = @("$env:ProgramFiles\Git\bin\bash.exe", "${env:ProgramFiles(x86)}\Git\bin\bash.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bash) { throw "Git Bash introuvable : installe Git for Windows." }

$check = ($repo -replace '\\', '/') + '/check.sh'
# conhost --headless runs bash without opening a console window.
$action = New-ScheduledTaskAction -Execute "conhost.exe" `
    -Argument "--headless `"$bash`" `"$check`" $Ip $SetupArgs" -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).Date.AddHours(8) `
    -RepetitionInterval (New-TimeSpan -Hours 2)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries `
    -AllowStartIfOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 15)

Register-ScheduledTask -TaskName "Fire TV - verification" -Action $action -Trigger $trigger `
    -Settings $settings -Description "Vérifie que le Fire TV est toujours configuré (firetv-home)." -Force | Out-Null
Write-Host "Tâche « Fire TV - verification » créée : toutes les 2 heures. Journal : $repo\logs\check.log"
