# 历史交易练习接口契约

已确认规则见 [设计](2026-10-24-repairs-and-blind-replay-design.md)。前后端统一使用以下契约，不调用正式资金、持仓、交易或画线接口。

## 接口

所有接口位于 `/trade/practice`，网关路径 `/api/trade/practice`；认证来自 Bearer 用户身份。返回既有 `Result<T>` `{code,message,data}`。

- `POST /`：`{stockCode,startDate,endDate,initialCash}` 创建（日期 ISO yyyy-MM-dd，允许后端兼容 yyyyMMdd；资金默认100000）。返回 PracticeView。
- `GET /`：返回 Summary[]（id, stockCode, startDate, endDate, currentDate, status, version, equity, returnRate, createdAt），只列当前用户。
- `GET /{id}`：返回 PracticeView，恢复进度。
- `POST /{id}/orders`：`{expectedVersion,side:'BUY'|'SELL',quantity}`，创建/替换一笔待成交单。返回新 PracticeView。
- `POST /{id}/cancel-order`：`{expectedVersion}` 撤单。返回新 PracticeView。
- `POST /{id}/advance`：`{expectedVersion}`，撮合待成交单并揭晓下一根，最后一根自动结束。返回新 PracticeView。
- `POST /{id}/finish`：`{expectedVersion}`，提前结束，撤销未成交单并冻结指标。返回新 PracticeView。
- `PUT /{id}/annotations`：`{expectedVersion,annotations:Annotation[]}` 替换本次标注，最多200条，只允许已可见日期；结束后只读。

跨用户返回404（避免暴露存在性），缺少认证401，参数无效400，过时版本或结束后修改409。任何修改均校验 expectedVersion：只能是0至Long.MAX_VALUE范围内的JSON整数 token；缺失、null、负数、小数（包括1.0）、指数、溢出、字符串、布尔、对象与数组均返回400，不改变持久化状态。此校验仅作用于练习请求，不改变全局JSON转换行为。服务端事务/条件更新确保重复请求不多推进、不重复成交。失败不得改动资金/进度。所有 id 为字符串。

## PracticeView

```
{
 id,stockCode,startDate,endDate,currentDate,status:'ACTIVE'|'FINISHED',version,
 createdAt,initialCash,cash,quantity,availableQuantity,equity,returnRate,
 benchmarkEquity,benchmarkReturnRate,excessReturnRate,maxDrawdown,fees,
 backgroundCount,revealedSteps,totalSteps,
 bars:Bar[],
 reviewBars?:Bar[], // 仅 FINISHED 可含整个所选区间+背景；bars仍截止实际结束日
 pendingOrder:Order|null,orders:Order[],fills:Fill[],equityCurve:EquityPoint[],
 annotations:Annotation[]
}
```

所有金额为JSON number；收益率、回撤为小数比例（0.1表示10%）。列表不含行情快照。ACTIVE不允许包含任何未来OHLC/未来指标/快照字段。

- `Bar={date:'yyyy-MM-dd',open,high,low,close,volume}`，按日期升序，volume单位手。
- `Order={id,side,quantity,submittedDate,status:'PENDING'|'FILLED'|'REJECTED'|'CANCELLED',reason?:string,executedDate?:string}`。下单回执绝不能含下一根价格。
- `Fill={id,orderId,side,date,price,quantity,amount,commission,tax,fee}`。
- `EquityPoint={date,equity,benchmarkEquity}`，仅起点及已揭晓收盘日，实际结束后冻结。
- `Annotation={id,type:'LINE'|'BUY'|'SELL',points:{date,price}[],text?:string}`，LINE恰2点，其余恰1点；价格正数，日期不能晚于currentDate。

## 训练规则

首版A股日线，只读取data_db已同步的未复权行情，创建后冻结快照；不使用自动数据源回退、quote/WS、最新价或正式交易。规范代码带.SH/.SZ/.BJ；只接受合法A股代码，缺失行情提示同步。无记录日期跳过并提示无法区分停牌与缺失；无效OHLC/重复日期/非正价格/无效volume/preClose拒绝；相邻已存日线的preClose相对上一根close断层>0.5%拒绝（创建报错不得透露具体未来价或方向）。

起点取不晚于startDate的最后已存行情，提供含该根在内最多120根背景。后续到endDate至少1根；允许非交易日日期输入并显示实际currentDate，不补造K线。输入起点不得早于1990-01-01，终点不晚于今日，区间最长5年；背景查询最多向前3年、总记录不超过3000条，避免全库快照。初始资金100至1000000000元、精确到分，单次委托最多10000000股。结束自动取消待成交单。最后持仓只估值，不强制卖。

初始资金默认100000，可改。佣金=每笔amount*0.0003、最低5；卖出税=amount*0.0005；HALF_UP到分；total fees为两项之和。100股整数倍，固定股数；next bar open全额成交，资金/可卖股数不足拒绝，volume<=0或high==low拒绝（当根未成交后不追单）；T+1，新买股在下一根才能卖。一次只允许一笔待成交单，可替换撤销。固定训练费率，不模拟历史费率、分红、滑点、排队和涨跌停限制。

基准使用相同初始现金，在起点之后首个volume>0且high>low的行情开盘买最大可负担整手（含买入佣金），买不起则保持现金；持有到实际结束日。逐日收盘权益=现金+股数*close，初始权益也进入回撤峰值。收益=(权益-初始现金)/初始现金；超额为两者收益差。提前结束后reviewBars揭晓未来，账户/基准/回撤均不再改变。

## 隔离与存储

快照只保存于服务端，创建时冻结全部背景和区间行情；GET只返回已揭晓前缀。每笔练习持久化全部账户/订单/成交/标注及快照，刷新/重启/重同步不改变已保存价格。不得向正式账户发事件或调用现金API。前端独立页面及纯图表，只使用本接口数据，无普通图表缓存回退。工具：画线/手动买点/手动卖点互斥，切换保留已有标注，Esc退出。指标仅使用可见bars（可复用纯MA/指标计算）。
