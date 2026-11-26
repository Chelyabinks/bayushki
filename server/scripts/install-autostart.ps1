# Регистрирует две задачи в Планировщике Windows: сервер и туннель.
# Стартуют при включении ПК (даже без входа в систему) и перезапускаются при сбое.
# Запускать в PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА:
#   powershell -ExecutionPolicy Bypass -File .\scripts\install-autostart.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$user = "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
$trigger = New-ScheduledTaskTrigger -AtStartup
foreach ($name in 'server', 'tunnel') {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$here\run-$name.ps1`""
    Register-ScheduledTask -TaskName "SpyChallenge-$name" -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName "SpyChallenge-$name"
    Write-Host "Задача SpyChallenge-$name зарегистрирована и запущена"
}
