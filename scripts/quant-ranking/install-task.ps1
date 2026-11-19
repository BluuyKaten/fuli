param(
    [string]$TaskName = '复利量化排行采集',
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$DzhRoot = 'Z:\dzh2',
    [string]$ExportDir = 'Z:\dzh2\quant-export',
    [string]$ProjectRoot = '',
    [switch]$NoLogonTrigger,
    [switch]$Unregister
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
else {
    $ProjectRoot = (Resolve-Path $ProjectRoot).Path
}

$runScript = (Resolve-Path (Join-Path $PSScriptRoot 'run-collector.ps1')).Path
$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

if ($Unregister) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "已删除当前用户的计划任务：$TaskName"
    exit 0
}

# 每个交易日 15:10 执行；登录触发用于电脑关机后的顺延恢复。
$dailyAt = (Get-Date).Date.AddHours(15).AddMinutes(10)
$triggers = @(
    (New-ScheduledTaskTrigger `
        -Weekly `
        -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday `
        -At $dailyAt)
)
if (-not $NoLogonTrigger) {
    $triggers += New-ScheduledTaskTrigger -AtLogOn -User $currentUser
}

$arguments = @(
    '-NoLogo',
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', "`"$runScript`"",
    '-BaseUrl', "`"$BaseUrl`"",
    '-DzhRoot', "`"$DzhRoot`"",
    '-ExportDir', "`"$ExportDir`"",
    '-ProjectRoot', "`"$ProjectRoot`"",
    '-StartService',
    '-OnlyAfterSchedule'
) -join ' '

$action = New-ScheduledTaskAction `
    -Execute 'PowerShell.exe' `
    -Argument $arguments `
    -WorkingDirectory $ProjectRoot

$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30)

# 交互式当前用户任务不需要管理员权限，也不会把凭证写入任务定义。
$principal = New-ScheduledTaskPrincipal `
    -UserId $currentUser `
    -LogonType InteractiveToken `
    -RunLevel Limited

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $triggers `
    -Settings $settings `
    -Principal $principal `
    -Description "复利量化排行：交易日 15:10 调用本机 Java 服务采集大智慧排行；默认目录 $DzhRoot。" `
    -Force | Out-Null

Write-Output "已注册当前用户计划任务：$TaskName"
Write-Output '执行时间：周一至周五 15:10；登录触发会在 15:10 后尝试补跑。'
Write-Output "服务地址：$BaseUrl；大智慧目录：$DzhRoot"
