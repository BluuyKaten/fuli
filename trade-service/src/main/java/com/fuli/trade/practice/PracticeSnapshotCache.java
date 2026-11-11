package com.fuli.trade.practice;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 练习快照的进程内缓存。
 *
 * <p>为什么需要它：{@code practice_session.snapshot_json} 存的是整段练习区间的冻结 K 线（几十 KB），
 * 创建后永不变化。原先 {@code load()} 每次都把它和 {@code state_json} 一起读出来再反序列化，
 * 于是「下单 / 撤单 / 揭晓下一日 / 存画线」这些只改 {@code state_json} 的操作，每次都要付一遍
 * 全量 bars 的解析成本。拆表（快照独立表）也能消除这次读取，但要新表 + 迁移 + 双写期 + 回滚脚本；
 * 这里只加一层缓存，收益相同、风险低一个数量级。
 *
 * <p><b>不可变性是这个缓存的前提。</b>快照在会话创建时冻结，之后没有任何代码路径会重写它
 * （{@code PracticeService} 的 UPDATE 语句只写 {@code state_json} 与摘要列）。如果将来加「续期 /
 * 延长练习区间 / 重算快照」这类功能，必须在同一个事务里调用 {@link #invalidate(String)}，
 * 否则读者会继续拿到旧 bars——这类错误查起来很贵：图表照常画、只是画的是上一段区间。
 *
 * <p>多实例部署时每个实例各有一份缓存：命中率低一些，正确性不受影响。
 */
@Component
public class PracticeSnapshotCache {
    /** 200 个会话 × 几十 KB，量级在个位数 MB；个人复盘工具的活跃会话远达不到这个数。 */
    static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    /** accessOrder=true + removeEldestEntry：超额时淘汰最久未用的那条，不需要额外的过期线程。 */
    private final Map<String, PracticeState.Snapshot> entries;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public PracticeSnapshotCache() {
        this(DEFAULT_CAPACITY);
    }

    public PracticeSnapshotCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("缓存容量必须大于 0");
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PracticeState.Snapshot> eldest) {
                return size() > PracticeSnapshotCache.this.capacity;
            }
        };
    }

    /** 命中返回快照，未命中返回 {@code null}（调用方负责回源并 {@link #put}）。 */
    public PracticeState.Snapshot get(String sessionId) {
        if (sessionId == null) return null;
        synchronized (entries) {
            PracticeState.Snapshot cached = entries.get(sessionId);
            if (cached == null) {
                misses.increment();
            } else {
                hits.increment();
            }
            return cached;
        }
    }

    public void put(String sessionId, PracticeState.Snapshot snapshot) {
        if (sessionId == null || snapshot == null) return;
        synchronized (entries) {
            entries.put(sessionId, snapshot);
        }
    }

    /** 快照被改写（续期、重算）时必须调用；也用于测试隔离。 */
    public void invalidate(String sessionId) {
        if (sessionId == null) return;
        synchronized (entries) {
            entries.remove(sessionId);
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    int capacity() {
        return capacity;
    }
}
