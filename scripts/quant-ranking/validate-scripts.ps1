param(
    [string]$ScriptDirectory = $PSScriptRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$targets = @(
    (Join-Path $ScriptDirectory 'run-collector.ps1'),
    (Join-Path $ScriptDirectory 'install-task.ps1')
)
$failures = [System.Collections.Generic.List[string]]::new()
$allText = [System.Text.StringBuilder]::new()

foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        $failures.Add("缺少脚本：$target")
        continue
    }

    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        (Resolve-Path $target), [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -gt 0) {
        $failures.Add("PowerShell 语法错误：$target")
    }

    [void]$allText.AppendLine((Get-Content -Raw -Encoding utf8 $target))
}

$text = $allText.ToString()
$required = [ordered]@{
    '大智慧目录' = $text.Contains('Z:\dzh2')
    '固定导出目录' = $text.Contains('quant-export')
    '采集接口' = $text.Contains('/trade/quant-ranking/collect')
    '交易日时间' = $text.Contains('15:10')
    '中文文案或注释' = [regex]::IsMatch($text, '[\u4e00-\u9fff]')
    '用户级计划任务' = $text.Contains('Register-ScheduledTask')
}
foreach ($item in $required.GetEnumerator()) {
    if (-not $item.Value) {
        $failures.Add("缺少静态约束：$($item.Key)")
    }
}

if ([regex]::IsMatch($text, '(?i)https?://[^\s"'']*webhook')) {
    $failures.Add('脚本中不得写入 webhook 地址')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output '量化排行脚本静态校验通过。'
