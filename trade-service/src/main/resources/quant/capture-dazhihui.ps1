[CmdletBinding()]
param(
    [string]$ExecutablePath = '',
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$OutputPath,
    [ValidateRange(1, 300)][int]$TimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Assert-DazhihuiRankingTsv {
    param([Parameter(Mandatory = $true)][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { throw '本次复制没有返回文本表格。' }
    $lines = @($Text -split "`r`n|`n|`r" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -lt 2) { throw '本次复制的表格没有数据行。' }
    $headers = @($lines[0].TrimStart([char]0xFEFF) -split "`t" | ForEach-Object {
        (($_.Trim().Trim([char]34) -replace '\s', '').Replace('（', '(').Replace('）', ')').Replace('｛', '{').Replace('｝', '}').Replace('［', '[').Replace('］', ']').Replace('【', '[').Replace('】', ']'))
    })
    $codeIndexes = @()
    $nameIndexes = @()
    $metricIndexes = @()
    for ($index = 0; $index -lt $headers.Count; $index++) {
        if ($headers[$index] -match '^(代码|股票代码|证券代码)$') { $codeIndexes += $index }
        if ($headers[$index] -match '^(名称|股票名称|证券名称)$') { $nameIndexes += $index }
        if ($headers[$index] -match '^人气(?:\{wyz\}|\[wyz\]|\(wyz\))$') { $metricIndexes += $index }
    }
    if ($codeIndexes.Count -ne 1 -or $nameIndexes.Count -ne 1 -or $metricIndexes.Count -ne 1) {
        throw '请在大智慧当前列表显示唯一的代码、名称和人气[wyz]列后重试。'
    }
    $lastRequiredIndex = [Math]::Max($codeIndexes[0], [Math]::Max($nameIndexes[0], $metricIndexes[0]))
    $validRows = 0
    foreach ($line in $lines[1..($lines.Count - 1)]) {
        $fields = @($line -split "`t")
        if ($fields.Count -le $lastRequiredIndex) { continue }
        $code = $fields[$codeIndexes[0]].Trim().Trim([char[]]@([char]39, [char]34)).Trim()
        $name = $fields[$nameIndexes[0]].Trim().Trim([char]34)
        $metric = ($fields[$metricIndexes[0]].Trim().Trim([char]34) -replace ',', '')
        $number = [decimal]0
        if ($code -match '^(?:(?:SH|SZ|BJ)\.?)?\d{6}(?:\.(?:SH|SZ|BJ))?$' -and
            -not [string]::IsNullOrWhiteSpace($name) -and
            [decimal]::TryParse($metric, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
            $validRows++
        }
    }
    if ($validRows -lt 1) { throw '本次复制的表格中没有同时包含有效代码、名称和人气数值的数据行。' }
    return [pscustomobject]@{ Text = $Text; RowCount = $validRows }
}

function Save-DazhihuiRankingTsv {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Text)

    $destination = [System.IO.Path]::GetFullPath($Path)
    $directory = [System.IO.Path]::GetDirectoryName($destination)
    $temporary = Join-Path $directory ('.' + [System.IO.Path]::GetFileName($destination) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllText($temporary, $Text, (New-Object System.Text.UTF8Encoding($false)))
        # Windows PowerShell 会把普通 $null 绑定为空字符串，必须显式传入空字符串引用。
        if ([System.IO.File]::Exists($destination)) { [System.IO.File]::Replace($temporary, $destination, [NullString]::Value) }
        else { [System.IO.File]::Move($temporary, $destination) }
    } finally {
        if ([System.IO.File]::Exists($temporary)) { [System.IO.File]::Delete($temporary) }
    }
}

function Initialize-DazhihuiNative {
    if ('QuantRankingCapture.Native' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace QuantRankingCapture {
    public sealed class MdiSelection {
        public IntPtr Client;
        public IntPtr Previous;
        public IntPtr Target;
        public bool Activated;
    }

    public static class Native {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct MenuItemInfo {
            public uint cbSize, fMask, fType, fState, wID;
            public IntPtr hSubMenu, hbmpChecked, hbmpUnchecked, dwItemData, dwTypeData;
            public uint cch;
            public IntPtr hbmpItem;
        }
        private delegate bool ResourceNameCallback(IntPtr module, IntPtr type, IntPtr name, IntPtr parameter);
        private delegate bool ChildWindowCallback(IntPtr window, IntPtr parameter);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool FreeLibrary(IntPtr module);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool EnumResourceNamesW(IntPtr module, IntPtr type, ResourceNameCallback callback, IntPtr parameter);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadMenuW(IntPtr module, IntPtr name);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern int GetMenuItemCount(IntPtr menu);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool GetMenuItemInfoW(IntPtr menu, uint item, bool byPosition, ref MenuItemInfo info);
        [DllImport("user32.dll")]
        private static extern bool DestroyMenu(IntPtr menu);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool GetUserObjectInformationW(IntPtr handle, int index, StringBuilder buffer, uint length, out uint required);
        [DllImport("user32.dll")]
        private static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("user32.dll")]
        public static extern bool IsWindow(IntPtr window);
        [DllImport("user32.dll")]
        public static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")]
        public static extern IntPtr GetWindow(IntPtr window, uint command);
        [DllImport("user32.dll")]
        private static extern bool EnumChildWindows(IntPtr parent, ChildWindowCallback callback, IntPtr parameter);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassNameW(IntPtr window, StringBuilder name, int capacity);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowTextW(IntPtr window, StringBuilder text, int capacity);
        [DllImport("user32.dll")]
        private static extern IntPtr GetParent(IntPtr window);
        [DllImport("user32.dll")]
        public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
        [DllImport("user32.dll")]
        public static extern uint GetClipboardSequenceNumber();
        [DllImport("user32.dll")]
        private static extern IntPtr GetClipboardOwner();
        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SendMessageTimeoutW(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool PostMessageW(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam);

        // 只按数据文件加载资源，不运行大智慧的入口或初始化代码。
        public static uint ResolveCopyCommand(string executablePath) {
            IntPtr module = LoadLibraryExW(executablePath, IntPtr.Zero, 0x00000002);
            if (module == IntPtr.Zero) throw new InvalidOperationException("无法只读加载大智慧菜单资源。");
            HashSet<uint> commands = new HashSet<uint>();
            Exception failure = null;
            ResourceNameCallback callback = delegate(IntPtr currentModule, IntPtr type, IntPtr name, IntPtr parameter) {
                IntPtr menu = IntPtr.Zero;
                try {
                    menu = LoadMenuW(currentModule, name);
                    if (menu == IntPtr.Zero) throw new InvalidOperationException("无法读取大智慧菜单模板。");
                    ReadMenu(menu, commands, 0);
                    return true;
                } catch (Exception error) {
                    failure = error;
                    return false;
                } finally {
                    if (menu != IntPtr.Zero) DestroyMenu(menu);
                }
            };
            try {
                bool enumerated = EnumResourceNamesW(module, new IntPtr(4), callback, IntPtr.Zero);
                GC.KeepAlive(callback);
                if (failure != null) throw failure;
                if (!enumerated || commands.Count != 1) throw new InvalidOperationException("无法从大智慧菜单资源唯一确认“复制到剪切板”命令。");
                foreach (uint command in commands) return command;
                throw new InvalidOperationException("大智慧菜单资源没有复制命令。");
            } finally { FreeLibrary(module); }
        }

        private static void ReadMenu(IntPtr menu, HashSet<uint> commands, int depth) {
            if (depth > 32) throw new InvalidOperationException("大智慧菜单资源嵌套异常。");
            int count = GetMenuItemCount(menu);
            if (count < 0) throw new InvalidOperationException("无法读取大智慧菜单项目。");
            for (uint index = 0; index < count; index++) {
                MenuItemInfo info = new MenuItemInfo();
                info.cbSize = (uint)Marshal.SizeOf(typeof(MenuItemInfo));
                info.fMask = 0x00000146;
                if (!GetMenuItemInfoW(menu, index, true, ref info)) throw new InvalidOperationException("无法读取大智慧菜单文字。");
                IntPtr text = Marshal.AllocHGlobal(checked(((int)info.cch + 1) * 2));
                try {
                    Marshal.WriteInt16(text, 0);
                    info.dwTypeData = text;
                    info.cch++;
                    if (!GetMenuItemInfoW(menu, index, true, ref info)) throw new InvalidOperationException("无法确认大智慧菜单文字。");
                    string label = Marshal.PtrToStringUni(text) ?? "";
                    label = Regex.Replace(label, @"\(&.\)|（&.）|&|\t.*", "").Trim();
                    if ((label == "复制到剪切板" || label == "复制到剪贴板") && info.hSubMenu == IntPtr.Zero && info.wID > 0 && info.wID <= 65535) commands.Add(info.wID);
                    if (info.hSubMenu != IntPtr.Zero) ReadMenu(info.hSubMenu, commands, depth + 1);
                } finally { Marshal.FreeHGlobal(text); }
            }
        }

        public static bool IsInteractiveDesktop() {
            if (!Environment.UserInteractive) return false;
            IntPtr desktop = OpenInputDesktop(0, false, 0x0001);
            if (desktop == IntPtr.Zero) return false;
            try {
                StringBuilder name = new StringBuilder(256);
                uint required;
                return GetUserObjectInformationW(desktop, 2, name, 512, out required) && String.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
            } finally { CloseDesktop(desktop); }
        }

        public static uint ClipboardOwnerProcessId() {
            IntPtr owner = GetClipboardOwner();
            uint processId = 0;
            if (owner != IntPtr.Zero) GetWindowThreadProcessId(owner, out processId);
            return processId;
        }

        // 只选择已经打开且标题完全匹配的行情子窗口，不创建页面或猜测导航命令。
        public static MdiSelection FindOpenedRankingWindow(IntPtr mainWindow) {
            if (!IsWindow(mainWindow)) return null;
            uint mainProcess;
            GetWindowThreadProcessId(mainWindow, out mainProcess);
            List<IntPtr> children = new List<IntPtr>();
            ChildWindowCallback callback = delegate(IntPtr window, IntPtr parameter) { children.Add(window); return true; };
            EnumChildWindows(mainWindow, callback, IntPtr.Zero);
            GC.KeepAlive(callback);
            List<MdiSelection> candidates = new List<MdiSelection>();
            foreach (IntPtr client in children) {
                StringBuilder className = new StringBuilder(256);
                if (GetClassNameW(client, className, className.Capacity) == 0 || !String.Equals(className.ToString(), "MDIClient", StringComparison.OrdinalIgnoreCase)) continue;
                uint clientProcess;
                GetWindowThreadProcessId(client, out clientProcess);
                if (clientProcess != mainProcess) continue;
                foreach (IntPtr child in children) {
                    if (GetParent(child) != client) continue;
                    uint childProcess;
                    GetWindowThreadProcessId(child, out childProcess);
                    if (childProcess != mainProcess) continue;
                    StringBuilder title = new StringBuilder(256);
                    if (GetWindowTextW(child, title, title.Capacity) == 0 || !String.Equals(title.ToString(), "沪深京A股", StringComparison.Ordinal)) continue;
                    candidates.Add(new MdiSelection { Client = client, Target = child });
                }
            }
            if (candidates.Count != 1) return null;
            MdiSelection selection = candidates[0];
            selection.Previous = GetActiveMdiWindow(selection.Client);
            return selection;
        }

        private static IntPtr GetActiveMdiWindow(IntPtr client) {
            UIntPtr result;
            if (SendMessageTimeoutW(client, 0x0229, UIntPtr.Zero, IntPtr.Zero, 0x22, 1000, out result) == IntPtr.Zero) throw new InvalidOperationException("无法读取大智慧当前行情子窗口。");
            return IntPtr.Size == 8 ? new IntPtr(unchecked((long)result.ToUInt64())) : new IntPtr(unchecked((int)result.ToUInt32()));
        }

        private static void ActivateMdiWindow(IntPtr client, IntPtr child) {
            UIntPtr result;
            UIntPtr childParameter = IntPtr.Size == 8 ? new UIntPtr(unchecked((ulong)child.ToInt64())) : new UIntPtr(unchecked((uint)child.ToInt32()));
            if (SendMessageTimeoutW(client, 0x0222, childParameter, IntPtr.Zero, 0x22, 1000, out result) == IntPtr.Zero) throw new InvalidOperationException("大智慧行情子窗口切换没有响应。");
        }

        public static void ActivateRankingWindow(MdiSelection selection) {
            if (selection == null || selection.Previous == selection.Target) return;
            if (!IsWindow(selection.Client) || !IsWindow(selection.Target) || GetParent(selection.Target) != selection.Client) throw new InvalidOperationException("已打开的沪深京A股行情窗口已变化，请重试。");
            StringBuilder title = new StringBuilder(256);
            GetWindowTextW(selection.Target, title, title.Capacity);
            if (!String.Equals(title.ToString(), "沪深京A股", StringComparison.Ordinal)) throw new InvalidOperationException("目标行情窗口标题已变化，请重试。");
            if (GetActiveMdiWindow(selection.Client) != selection.Previous) throw new InvalidOperationException("用户已切换大智慧窗口，本次采集已取消，请重试。");
            selection.Activated = true;
            ActivateMdiWindow(selection.Client, selection.Target);
            if (GetActiveMdiWindow(selection.Client) != selection.Target) throw new InvalidOperationException("未能激活已打开的沪深京A股行情列表。");
        }

        public static bool ShouldRestoreRankingWindow(MdiSelection selection, IntPtr activeChild) {
            return selection != null && selection.Activated && selection.Client != IntPtr.Zero && selection.Previous != IntPtr.Zero && selection.Target != IntPtr.Zero && selection.Previous != selection.Target && activeChild == selection.Target;
        }

        // 用户已切换到其他子窗口时不再恢复，保留用户自己的后续操作。
        public static bool RestoreRankingWindow(MdiSelection selection) {
            if (selection == null || !selection.Activated || !IsWindow(selection.Client) || !IsInteractiveDesktop()) return false;
            if (!ShouldRestoreRankingWindow(selection, GetActiveMdiWindow(selection.Client))) return false;
            if (!IsWindow(selection.Previous) || GetParent(selection.Previous) != selection.Client) return false;
            ActivateMdiWindow(selection.Client, selection.Previous);
            if (GetActiveMdiWindow(selection.Client) != selection.Previous) throw new InvalidOperationException("未能恢复采集前的大智慧图表窗口。");
            return true;
        }

        public static void CheckResponsive(IntPtr window, uint timeout) {
            UIntPtr result;
            if (SendMessageTimeoutW(window, 0, UIntPtr.Zero, IntPtr.Zero, 0x22, timeout, out result) == IntPtr.Zero) throw new InvalidOperationException("大智慧主窗口没有响应，请恢复窗口后重试。");
        }

        public static void SendCopyCommand(IntPtr window, uint command) {
            // 复制完整行情表可能超过数秒，投递后由剪贴板序列和表格校验确认完成。
            if (!PostMessageW(window, 0x0111, new UIntPtr(command), IntPtr.Zero)) {
                int error = Marshal.GetLastWin32Error();
                throw new InvalidOperationException("大智慧复制命令投递失败（Windows 错误 " + error + "）。");
            }
        }
    }
}
'@
}

$mdiSelection = $null
$captureExitCode = 0
try {
    # Windows 剪贴板需要 STA；用同一脚本重启 Windows PowerShell，参数作为数组传递。
    if ([System.Threading.Thread]::CurrentThread.GetApartmentState() -ne [System.Threading.ApartmentState]::STA) {
        $windowsPowerShell = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell/v1.0/powershell.exe'
        $restartArguments = @('-NoProfile', '-NonInteractive', '-STA', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-OutputPath', $OutputPath, '-TimeoutSeconds', $TimeoutSeconds)
        if (-not [string]::IsNullOrWhiteSpace($ExecutablePath)) { $restartArguments += @('-ExecutablePath', $ExecutablePath) }
        & $windowsPowerShell @restartArguments
        exit $LASTEXITCODE
    }
    if ([string]::IsNullOrWhiteSpace($OutputPath)) { throw '必须指定采集输出文件。' }
    $fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    $outputDirectory = [System.IO.Path]::GetDirectoryName($fullOutputPath)
    if (-not [System.IO.Directory]::Exists($outputDirectory)) { throw '采集输出文件的父目录不存在。' }
    if ([System.IO.Directory]::Exists($fullOutputPath)) { throw '采集输出路径必须是文件。' }
    $requestedExecutable = $null
    if (-not [string]::IsNullOrWhiteSpace($ExecutablePath)) {
        $requestedExecutable = (Get-Item -LiteralPath $ExecutablePath -ErrorAction Stop).FullName
        if (-not [string]::Equals([System.IO.Path]::GetFileName($requestedExecutable), 'dzh2.exe', [StringComparison]::OrdinalIgnoreCase)) { throw '采集目标必须是大智慧 dzh2.exe。' }
    }

    Initialize-DazhihuiNative
    Add-Type -AssemblyName System.Windows.Forms
    if (-not [QuantRankingCapture.Native]::IsInteractiveDesktop()) { throw '当前桌面已锁定或不在交互会话中，请解锁后重试。' }
    $currentSession = [System.Diagnostics.Process]::GetCurrentProcess().SessionId
    $targets = @()
    foreach ($process in @(Get-Process -Name dzh2 -ErrorAction SilentlyContinue)) {
        try {
            $process.Refresh()
            if ($process.SessionId -ne $currentSession) { continue }
            $window = $process.MainWindowHandle
            $actualExecutable = $process.MainModule.FileName
            if ($window -eq [IntPtr]::Zero -or -not [QuantRankingCapture.Native]::IsWindowVisible($window)) { continue }
            if ([QuantRankingCapture.Native]::GetWindow($window, 4) -ne [IntPtr]::Zero) { continue }
            if (-not [string]::Equals([System.IO.Path]::GetFileName($actualExecutable), 'dzh2.exe', [StringComparison]::OrdinalIgnoreCase)) { continue }
            if ($null -ne $requestedExecutable -and -not [string]::Equals($actualExecutable, $requestedExecutable, [StringComparison]::OrdinalIgnoreCase)) { continue }
            $ownerProcess = [uint32]0
            $null = [QuantRankingCapture.Native]::GetWindowThreadProcessId($window, [ref]$ownerProcess)
            if ($ownerProcess -ne $process.Id) { continue }
            $targets += [pscustomobject]@{ ProcessId = $process.Id; Window = $window; Executable = $actualExecutable }
        } catch {
            # 无法核实所属路径或窗口的进程不作为采集目标。
            continue
        }
    }
    if ($targets.Count -eq 0) { throw '未找到当前用户会话中符合路径的大智慧主窗口，请先打开目标行情列表。' }
    if ($targets.Count -ne 1) { throw '存在多个大智慧主窗口，无法唯一确定采集目标；请指定可唯一匹配的可执行文件路径。' }
    $target = $targets[0]
    $command = [QuantRankingCapture.Native]::ResolveCopyCommand($target.Executable)
    [QuantRankingCapture.Native]::CheckResponsive($target.Window, 1000)

    # 自动选择的前提是已经打开标题为“沪深京A股”的 MDI 行情列表。
    # 未找到唯一匹配时检查当前窗口，仍须通过后续目标字段校验。
    $mdiSelection = [QuantRankingCapture.Native]::FindOpenedRankingWindow($target.Window)
    [QuantRankingCapture.Native]::ActivateRankingWindow($mdiSelection)

    # 仅接受本次原生复制命令之后、由目标进程拥有且读取期间稳定的新剪贴板内容。
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $previousSequence = [QuantRankingCapture.Native]::GetClipboardSequenceNumber()
    [QuantRankingCapture.Native]::SendCopyCommand($target.Window, $command)
    $capture = $null
    $capturedSequence = [uint32]0
    $lastReason = '未收到本次复制产生的新表格。'
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (-not [QuantRankingCapture.Native]::IsWindow($target.Window)) { throw '大智慧主窗口已退出。' }
        if (-not [QuantRankingCapture.Native]::IsInteractiveDesktop()) { throw '采集期间桌面已锁定，请解锁后重试。' }
        $sequence = [QuantRankingCapture.Native]::GetClipboardSequenceNumber()
        if ($sequence -ne $previousSequence) {
            if ([QuantRankingCapture.Native]::ClipboardOwnerProcessId() -eq $target.ProcessId) {
                try {
                    $text = [System.Windows.Forms.Clipboard]::GetText([System.Windows.Forms.TextDataFormat]::UnicodeText)
                    if ($sequence -eq [QuantRankingCapture.Native]::GetClipboardSequenceNumber()) {
                        $capture = Assert-DazhihuiRankingTsv -Text $text
                        $capturedSequence = $sequence
                        break
                    }
                } catch {
                    $lastReason = '剪贴板尚未返回可读取且符合目标列要求的表格。'
                    if ($_.Exception -is [System.Management.Automation.RuntimeException]) { $lastReason = $_.Exception.Message }
                }
            } else { $lastReason = '剪贴板变化未能确认为当前大智慧产生。' }
        }
        Start-Sleep -Milliseconds 100
    }
    if ($null -eq $capture) { throw ('等待大智慧新表格超时：' + $lastReason) }
    if ($capturedSequence -ne [QuantRankingCapture.Native]::GetClipboardSequenceNumber()) { throw '复制完成后剪贴板再次变化，本次结果未保存，请重试。' }
    [QuantRankingCapture.Native]::CheckResponsive($target.Window, 1000)

    # 校验通过后才写文件，使用同目录临时文件替换，失败时不产生半份表格。
    Save-DazhihuiRankingTsv -Path $fullOutputPath -Text $capture.Text
} catch {
    [Console]::Error.WriteLine('大智慧采集失败（第 ' + $_.InvocationInfo.ScriptLineNumber + ' 行）：' + $_.Exception.GetBaseException().Message)
    $captureExitCode = 1
} finally {
    if ($null -ne $mdiSelection) {
        try { $null = [QuantRankingCapture.Native]::RestoreRankingWindow($mdiSelection) }
        catch {
            [Console]::Error.WriteLine('大智慧窗口恢复失败：' + $_.Exception.GetBaseException().Message)
            $captureExitCode = 1
        }
    }
}
if ($captureExitCode -eq 0) { Write-Output ('大智慧表格采集完成，有效数据行：' + $capture.RowCount) }
exit $captureExitCode
