# 大智慧人气自动采集

## 在复利系统中使用

1. 运行本机 `trade-service` Java 服务，打开复利系统的“量化排行”页面。
2. 在大智慧中登录并打开含“人气(wyz)”的行情列表。
3. 点击“从大智慧采集”，即可生成每日快照和历史总表，无需手动粘贴。
4. 在“采集与通知设置”中开启定时采集并保存。Java 服务会在北京时间 15:10 执行；开启启动补跑后，15:10 后启动服务会补采当天尚未成功的数据。

程序路径留空时识别当前 Windows 会话中的大智慧主窗口；有多个安装版本时填写实际运行的 `dzh2.exe` 完整路径。本机现场验证使用 `F:\dzh365(64)\dzh2.exe`，与旧的 `Z:\dzh2` 目录不同。

当前实现需要大智慧已登录并打开目标行情列表，Windows 用户已登录且桌面未锁定。无法确认目标窗口、复制结果或人气列时会返回具体错误，不写入旧剪贴板。

## 获取方式

Java 服务调用随服务打包的 `capture-dazhihui.ps1`。助手读取所选 `dzh2.exe` 自身菜单资源，查找“复制到剪切板”命令，再调用该原生命令，等价于人工点击“常用 → 复制到剪切板”。没有使用全选快捷键、鼠标坐标或固定分辨率。

复制前记录剪贴板序列号，复制后核对新序列、拥有者进程、代码/名称/人气表头及有效数据行。校验成功才原子写入 Java 创建的私有临时文件。Java 完成读取后清理本次临时文件。

## 排行口径

- 唯一排序字段是“人气{wyz}”，兼容半角和全角括号、空白、大小写变体。
- 先去重，再按人气值从高到低重算连续名次；人气相同时按代码升序。大智慧原序号不作为人气排名。
- 负数正常参与排序。空白、横线等未计算值明确计数并跳过；非空但非数字的值拒绝导入；全部未计算时保留旧快照。
- 保留最新价、涨幅及全部原始指标；重复表头追加 `(2)` 等编号，防止字段被覆盖。
- 每日快照保存全部有效候选；总表的出现次数只统计每天人气前 20。旧的涨幅等其它指标快照不混入人气总表。
- 相同用户相同交易日覆盖；源表头有明确日期时必须与导入日期一致。

## 定时及失败重试

自动设置按登录用户保存在 `quant_automation_config` 中。默认关闭，保存开启后由 Java 服务直接调度，不需要额外注册 Windows 每日采集任务。成功日期会持久保存，重启服务不重复采集同日数据。失败后间隔至少五分钟，每天最多自动尝试三次；页面仍可手动重试。

默认跳过周末。交易所节假日需要在 Java 服务环境变量 `QUANT_RANKING_HOLIDAYS` 中配置逗号分隔的日期，例如 `2026-01-01,2026-01-02`；当前未接入在线交易日历。

旧的 Windows 计划任务只是兼容入口。若已使用 Java 定时采集，删除旧任务以避免重复触发：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\quant-ranking\install-task.ps1 -Unregister
```

## 数据库升级

已有数据库按顺序执行新增表脚本；不要重新执行带删表语句的初始化脚本。

```sql
source sql/migrations/V17__quant_ranking.sql;
source sql/migrations/V18__quant_automation.sql;
```

如果 V17 已执行，只执行 V18。本次开发已在本机 `trade_db` 创建自动采集配置表。

## 通知

企业微信机器人在页面中配置。采集成功后可推送当天人气前 10、历史前 20 和数据日期。未配置机器人时采集照常保存，并提示“通知未启用”；发送失败单独记录，不把通知失败伪装成发送成功。

## 验证

```powershell
powershell.exe -NoProfile -NonInteractive -STA -ExecutionPolicy Bypass -File .\scripts\quant-ranking\test-capture-dazhihui.ps1
.\mvnw -pl trade-service test
npm --prefix frontend test
npm --prefix frontend run build
```

现场验证需本机大智慧已打开目标表格；以下测试只写隔离内存库，不向外发消息：

```powershell
$env:FULI_DZH_LIVE = 'true'
.\mvnw -pl trade-service -Dtest=DazhihuiLiveCaptureTest test
Remove-Item Env:FULI_DZH_LIVE
```

手工触发 Java 接口的兼容脚本是 `run-collector.ps1`，需要当前登录用户的令牌环境变量 `FULI_QUANT_ACCESS_TOKEN`；页面和 Java 定时任务无需这个变量。文件导出备用入口仅在显式传入 `-UseExportFile` 时启用，仍须包含人气列。

## 文件读取调查的边界

已从当前安装的 `listplan/listsself/A股行情.xml` 确认显示名“人气(wyz)”对应 `OBV1.1` 日周期输出。现阶段尚未完成该输出数值缓存的文件解码；活动 SST 有共享锁，不能据此断言没有文件缓存。当前交付采用已通过现场验证的大智慧原生复制命令。
