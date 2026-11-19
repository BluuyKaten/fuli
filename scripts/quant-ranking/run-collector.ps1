param(
    # 直连 trade-service；如通过网关调用，可传入网关地址和对应路径。
    [string]$BaseUrl = 'http://127.0.0.1:8082',
    [string]$EndpointPath = '/trade/quant-ranking/collect',
    [string]$HealthPath = '/actuator/health',
    # 大智慧安装根目录，首版采集器通过原生导出/复制获取结果。
    [string]$DzhRoot = 'Z:\dzh2',
    # 固定结果导出目录，建议让大智慧把排行文件保存到这里。
    [string]$ExportDir = 'Z:\dzh2\quant-export',
    [string]$ProjectRoot = '',
    [int]$WaitSeconds = 90,
    [int]$RequestTimeoutSeconds = 60,
    # 服务未就绪时使用仓库已有的 Maven Wrapper 启动 trade-service local。
    [switch]$StartService,
    # 登录触发时使用：交易日 15:10 前只等待下一次定时触发。
    [switch]$OnlyAfterSchedule,
    # 只有明确传入时才读取固定导出文件；默认直接复制大智慧当前表格。
    [switch]$UseExportFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Join-EndpointUri {
    param(
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$Path
    )

    return ($Base.TrimEnd('/') + '/' + $Path.TrimStart('/'))
}

function Test-LocalServiceHealth {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $health = Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec 5
        if ($null -eq $health) {
            return $false
        }

        $statusProperty = $health.PSObject.Properties['status']
        if ($null -eq $statusProperty) {
            return $true
        }

        return ([string]$statusProperty.Value).ToUpperInvariant() -eq 'UP'
    }
    catch {
        return $false
    }
}

function Start-ExistingTradeService {
    param([Parameter(Mandatory = $true)][string]$Root)

    $mvnw = Join-Path $Root 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mvnw -PathType Leaf)) {
        throw "未找到 $mvnw，无法使用现有 Maven Wrapper 启动 trade-service。"
    }

    # 这里仅启动仓库已有 Java 服务，不安装 JDK、Maven 或其他运行时。
    $process = Start-Process `
        -FilePath $mvnw `
        -ArgumentList @('-pl', 'trade-service', 'spring-boot:run', '-Dspring-boot.run.profiles=local') `
        -WorkingDirectory $Root `
        -WindowStyle Hidden `
        -PassThru

    Write-Output "已请求启动现有 trade-service，进程号 $($process.Id)。"
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
else {
    $ProjectRoot = (Resolve-Path $ProjectRoot).Path
}

$now = Get-Date
if ($now.DayOfWeek -in @([System.DayOfWeek]::Saturday, [System.DayOfWeek]::Sunday)) {
    Write-Output '今天是周末，跳过量化排行采集。'
    exit 0
}

if ($OnlyAfterSchedule -and $now.TimeOfDay -lt ([TimeSpan]::Parse('15:10'))) {
    Write-Output '当前时间早于交易日 15:10，登录触发仅等待每日定时任务。'
    exit 0
}

# 新启动的 Java 进程继承此变量，供 local 配置的导出目录读取。
$env:DZH_EXPORT_DIR = $ExportDir

$healthUri = Join-EndpointUri -Base $BaseUrl -Path $HealthPath
$collectUri = Join-EndpointUri -Base $BaseUrl -Path $EndpointPath

if (-not (Test-LocalServiceHealth -Uri $healthUri)) {
    if (-not $StartService) {
        throw "本地 Java 服务未就绪：$healthUri。请先启动 trade-service，或传入 -StartService。"
    }

    Start-ExistingTradeService -Root $ProjectRoot
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while (-not (Test-LocalServiceHealth -Uri $healthUri)) {
    if ((Get-Date) -ge $deadline) {
        throw "等待本地 Java 服务健康检查超时：$healthUri。"
    }

    Start-Sleep -Seconds 2
}

# 令牌只从当前用户环境变量读取，脚本和计划任务中不保存凭证。
$accessToken = [Environment]::GetEnvironmentVariable('FULI_QUANT_ACCESS_TOKEN')
$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($accessToken)) {
    $headers['Authorization'] = "Bearer $accessToken"
}

# Java 服务负责运行内置本机助手，脚本不再控制窗口或依赖陈旧剪贴板。
if ($UseExportFile) {
    $EndpointPath = '/trade/quant-ranking/collect-file'
    $collectUri = Join-EndpointUri -Base $BaseUrl -Path $EndpointPath
}
$payload = '{}' 
try {
    $response = Invoke-RestMethod `
        -Uri $collectUri `
        -Method Post `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body $payload `
        -TimeoutSec $RequestTimeoutSeconds

    if ($response.code -ne 200 -or $response.data.status -ne 'SUCCESS') {
        throw ('采集未完成：' + $response.data.message)
    }
    Write-Output '量化排行采集已完成。' 
    if ($null -ne $response) {
        $response | ConvertTo-Json -Depth 8
    }
    exit 0
}
catch {
    $detail = $_.Exception.Message
    $responseProperty = $_.Exception.PSObject.Properties['Response']
    if ($null -ne $responseProperty -and $null -ne $responseProperty.Value) {
        $statusProperty = $responseProperty.Value.PSObject.Properties['StatusCode']
        if ($null -ne $statusProperty) {
            $detail = "$detail（HTTP $([int]$statusProperty.Value)）"
        }
    }
    throw "量化排行采集请求失败：$detail"
}

