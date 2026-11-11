# ADR-0015：练习快照读放大 —— 进程内缓存，不拆表

- 状态：已接受（2026-11-11）
- 关联：ADR-0004（K 线两级缓存）、ADR-0005（不分库分表）、ADR-0007（bar 统一存储）
- 落地：`trade-service/.../practice/PracticeSnapshotCache.java`、`.../PracticeService.java`

## 背景

待办里挂着一条「`practice_session` 的 bars 拆表（快照独立表，减少整行重写）」。核实后，这个描述与实际代码对不上：

- `PracticeService.mutate()` 的 UPDATE 只写 `state_json / status / current_day / equity / version / updated_at`，
  **不碰 `snapshot_json`**。MySQL 的 UPDATE 也不会「整行重写大字段」，「整行重写」的担忧本身不成立。
- 真正的浪费在**读**：`load()` 每次 `SELECT state_json,snapshot_json,version`，把整段练习区间的冻结
  bars（几十 KB）读出来再 `JSON.parseObject` 成 `PracticeState.Snapshot`。而快照在会话创建时就冻结，
  **之后永不变化**。于是「下单 / 撤单 / 揭晓下一日 / 存画线」每一次点击，都重复付一遍全量 bars 的
  读取与反序列化成本，而它们只改了 `state_json`。

## 决策

**加一层进程内快照缓存，不拆表。**

1. `load()` 的 SQL 改为 `SELECT state_json,version`（`FOR UPDATE` 保留，行锁语义不变）。
2. 快照单独走 `PracticeSnapshotCache`：`LinkedHashMap(accessOrder=true)` + `removeEldestEntry`
   做 LRU，容量 200（200 × 几十 KB ≈ 个位数 MB）。**没用 `ConcurrentHashMap`**：它给不了
   「按最近使用淘汰」，要自己维护时间戳再全表扫描；而这里的竞争只有同一用户点按钮的并发度，
   锁住一个 200 条的 map 完全不是瓶颈。
3. 未命中时单独 `SELECT snapshot_json FROM practice_session WHERE id=?`，读到即入缓存。
   这次读取发生在「已确认会话存在且属于该用户、且持有行锁」之后，因此不存在被并发改写的窗口。
4. `create()` 插入成功后直接把内存里那份快照放进缓存——前端创建完立刻 GET 详情，那一次不必回源。
5. **不用 `@Cacheable`**：这里要消除的是「反序列化」而不是「方法调用」，Spring Cache 的代理在
   `@Transactional` 内部的 `this.load()` 调用上根本不生效（自调用绕过代理），配了也是白配。

## 为什么不拆表

拆表的完整成本：新表 DDL + 存量数据迁移 + 双写期 + 回滚脚本 + 改所有读写路径 + 后续任何
「导出练习」类需求都要多一次 JOIN。它换来的收益只有「少读一个 LONGTEXT」——而缓存已经拿到了，
且改动面只有两个文件。

判断依据：**快照不可变**。可变的、需要跨实例强一致的数据（例如持仓、资金）不能这样缓存；
不可变的冻结历史数据可以。这也是为什么这条决策不能推广到 `state_json`。

## 约束（最重要的一条）

快照的不可变性是本方案的前提。**如果将来加「续期 / 延长练习区间 / 重算快照」这类功能，
必须在同一个事务里调用 `PracticeSnapshotCache.invalidate(sessionId)`**，否则读者会继续拿到旧 bars。
这类错误很贵：图表照常画、不报错，只是画的是上一段区间。约束已写在缓存类的 Javadoc 上。

## 后果

- 读放大消除：同一会话的第二次及以后的任何操作都不再读取 `snapshot_json`（
  `PracticePersistenceTest.repeatedReadsAndMutationsStopReloadingTheImmutableSnapshotColumn` 用记录 SQL 的方式钉住）。
- 多实例部署时各实例各有一份缓存：命中率下降，正确性不受影响（快照只来自数据库，且不可变）。
- 内存占用随活跃会话数线性增长，上限 200 条由 LRU 兜底；淘汰后下次访问回源一次即可。
- `hits()/misses()` 计数暴露给测试与排障，不对外提供端点（个人项目不需要额外的观测面）。
