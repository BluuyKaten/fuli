[CmdletBinding()]
param([string]$ExecutablePath = '')

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$scriptPath = Join-Path $PSScriptRoot 'capture-dazhihui.ps1'
$resourcePath = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'trade-service/src/main/resources/quant/capture-dazhihui.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw '缺少桌面采集脚本。' }

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw ('采集脚本语法错误：' + $parseErrors[0].Message) }
$sourceBytes = [System.IO.File]::ReadAllBytes($scriptPath)
if ($sourceBytes.Length -lt 3 -or $sourceBytes[0] -ne 239 -or $sourceBytes[1] -ne 187 -or $sourceBytes[2] -ne 191) { throw '采集脚本必须使用 UTF-8 BOM，兼容 Windows PowerShell 5.1。' }
if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) { throw '缺少随服务打包的采集脚本。' }
if ([Convert]::ToBase64String($sourceBytes) -cne [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($resourcePath))) { throw '两份采集脚本内容不一致。' }

# 只加载纯校验函数和原生资源读取函数，不运行桌面采集入口。
foreach ($name in @('Assert-DazhihuiRankingTsv', 'Save-DazhihuiRankingTsv', 'Initialize-DazhihuiNative')) {
    $definition = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if ($null -eq $definition) { throw ('缺少必要函数：' + $name) }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$checked = 0
foreach ($metric in @('人气{wyz}', '人气[wyz]', '人气(wyz)', '人气（WYZ）', '人气｛wyz｝', '人气［wyz］', '人气【wyz】')) {
    $sample = (@('代码', '名称', $metric) -join "`t") + "`r`n'603706`t测试股票`t12.5`r`n"
    $result = Assert-DazhihuiRankingTsv -Text $sample
    if ($result.RowCount -ne 1 -or $result.Text -cne $sample) { throw '有效原生表格未原样通过校验。' }
    $checked++
}

$invalid = @(
    "代码`t名称`t人气`r`n603706`t测试股票`t12.5",
    "代码`t名称`t人气[wyz]`r`n",
    "代码`t名称`t人气[wyz]`r`n不是代码`t测试股票`t12.5",
    "代码`t名称`t人气[wyz]`r`n603706`t`t12.5",
    "代码`t名称`t人气[wyz]`r`n603706`t测试股票`t无数据",
    "代码`t名称`t人气[wyz]`t人气(wyz)`r`n603706`t测试股票`t12.5`t13.5"
)
foreach ($sample in $invalid) {
    $rejected = $false
    try { $null = Assert-DazhihuiRankingTsv -Text $sample } catch { $rejected = $true }
    if (-not $rejected) { throw '无效或含歧义的表格被错误接受。' }
    $checked++
}

# 采集入口的窗口恢复必须放在 finally，且必须先选择行情列表再投递复制命令。
$mainTry = @($ast.EndBlock.Statements | Where-Object { $_ -is [System.Management.Automation.Language.TryStatementAst] })
if ($mainTry.Count -ne 1 -or $null -eq $mainTry[0].Finally) { throw '采集入口缺少窗口恢复 finally。' }
$restoreCalls = @($mainTry[0].Finally.FindAll({ param($node) $node -is [System.Management.Automation.Language.InvokeMemberExpressionAst] -and $node.Member.Value -eq 'RestoreRankingWindow' }, $true))
if ($restoreCalls.Count -ne 1) { throw '采集入口 finally 未唯一调用条件窗口恢复。' }
$checked++
$windowCalls = @($mainTry[0].Body.FindAll({ param($node) $node -is [System.Management.Automation.Language.InvokeMemberExpressionAst] -and $node.Member.Value -in @('FindOpenedRankingWindow', 'ActivateRankingWindow', 'SendCopyCommand') }, $true) | Sort-Object { $_.Extent.StartOffset })
if ($windowCalls.Count -ne 3 -or $windowCalls[0].Member.Value -ne 'FindOpenedRankingWindow' -or $windowCalls[1].Member.Value -ne 'ActivateRankingWindow' -or $windowCalls[2].Member.Value -ne 'SendCopyCommand') { throw '行情窗口发现、激活与复制命令顺序错误。' }
$checked++

# 临时文件已经存在时必须覆盖成功，防止把空备份路径误当成非法路径。
$probeOutput = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($probeOutput, '旧结果')
    Save-DazhihuiRankingTsv -Path $probeOutput -Text '新结果'
    if ([System.IO.File]::ReadAllText($probeOutput) -cne '新结果') { throw '已存在的采集输出文件未成功替换。' }
    $checked++
    [System.IO.File]::Delete($probeOutput)
    Save-DazhihuiRankingTsv -Path $probeOutput -Text '首次结果'
    if ([System.IO.File]::ReadAllText($probeOutput) -cne '首次结果') { throw '首次采集输出文件未成功创建。' }
    $checked++
} finally {
    if ([System.IO.File]::Exists($probeOutput)) { [System.IO.File]::Delete($probeOutput) }
}

Initialize-DazhihuiNative
$selection = New-Object QuantRankingCapture.MdiSelection
$selection.Client = [IntPtr]10
$selection.Previous = [IntPtr]20
$selection.Target = [IntPtr]30
$selection.Activated = $true
if (-not [QuantRankingCapture.Native]::ShouldRestoreRankingWindow($selection, [IntPtr]30)) { throw '脚本激活的行情列表未允许恢复原图表。' }
$checked++
if ([QuantRankingCapture.Native]::ShouldRestoreRankingWindow($selection, [IntPtr]40)) { throw '用户切换后的新窗口会被脚本覆盖。' }
$checked++
$selection.Previous = [IntPtr]30
if ([QuantRankingCapture.Native]::ShouldRestoreRankingWindow($selection, [IntPtr]30)) { throw '原本已处于行情列表时不应重复恢复。' }
$checked++
$selection.Previous = [IntPtr]20
$selection.Activated = $false
if ([QuantRankingCapture.Native]::ShouldRestoreRankingWindow($selection, [IntPtr]30)) { throw '未由脚本激活的窗口不应执行恢复。' }
$checked++

$postingError = ''
try { [QuantRankingCapture.Native]::SendCopyCommand([IntPtr](-2), [uint32]57634) }
catch { $postingError = $_.Exception.GetBaseException().Message }
if ($postingError -notmatch 'Windows 错误 \d+') { throw '无效窗口的异步复制命令没有返回明确的 Windows 投递错误。' }
$checked++

if (-not [string]::IsNullOrWhiteSpace($ExecutablePath)) {
    $command = [QuantRankingCapture.Native]::ResolveCopyCommand([System.IO.Path]::GetFullPath($ExecutablePath))
    if ($command -ne 57634) { throw ('本机菜单资源命令与独立 PE 调查结果不一致：' + $command) }
    $checked++
    Write-Output ('只读菜单资源验证通过，命令编号：' + $command)
}
Write-Output ('桌面采集静态验证通过，共 ' + $checked + ' 项数据及资源检查；未操作大智慧界面。')
