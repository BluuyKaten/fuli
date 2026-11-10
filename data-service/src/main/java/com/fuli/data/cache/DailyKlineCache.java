package com.fuli.data.cache;

import com.alibaba.fastjson2.JSON;
import com.fuli.data.entity.Bar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 行情 K 线两级缓存：L1 进程内 + L2 Redis。见 docs/adr/0004-kline-two-level-cache.md。
 *
 * <h3>key 为什么带 period 与 adjust</h3>
 * 阶段 0 起一张 {@code bar} 表覆盖全市场全周期，且复权是查询参数而不是存储维度。
 * 不带 period 会让日线和周线互相污染；不带 adjust 会让前复权与原始价互相污染。
 * 前缀 {@code kline:v1:} 是版本位：结构变更时改版本号即可整体失效，不必写清理脚本。
 *
 * <h3>为什么 key 里不带 limit / 日期区间</h3>
 * 缓存的是「该标的该周期最近 {@value #CACHE_SIZE} 根」这一份固定大小的数据，请求 620 根就从里面切
 * 最后 620 根。如果 key 里带上 limit，`620` / `500` / `100` 会各占一个 key，命中率被切碎，
 * 而它们的数据是同一份，只是切片长度不同。
 *
 * <h3>为什么 TTL 是 10/30 分钟而不是「到收盘」</h3>
 * 原先 ADR-0004 写的是「TTL 到当日 15:30」，但同步任务在收盘后才写库。那样一来，
 * 15:30 之后刷新的缓存会一直存活到**次日** 15:30，用户整晚看到的都是同步前的旧数据。
 * 改用较短的固定 TTL，并把 {@link #evict} 接到同步任务提交后：同步补完数据立刻可见。
 *
 * <h3>降级</h3>
 * Redis 任何异常都按「未命中」处理并继续查库。缓存绝不能成为故障点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyKlineCache {

    /** 缓存内容的固定长度。请求窗口不超过它就一定命中，超过则直接查库。 */
    public static final int CACHE_SIZE = 800;

    /** L1 TTL：覆盖首屏的重复请求（切股票再切回来、切周期） */
    private static final long L1_TTL_MS = 10 * 60 * 1000L;

    /** L2 TTL：略长于 L1，让多实例之间也能共享 */
    private static final Duration L2_TTL = Duration.ofMinutes(30);

    /** key 前缀，含版本位。结构变更时改这里即可整体失效。 */
    private static final String KEY_PREFIX = "kline:v1:";

    /** adjust 只有两个取值，失效时直删两键，无需 SCAN。 */
    private static final List<String> ADJUSTS = List.of("none", "forward");

    /** L1 条目上限，防止标的不断切换导致 map 无限增长 */
    private static final int L1_MAX = 500;

    private record Entry(List<Bar> bars, long expireAt) {}

    private final Map<String, Entry> l1 = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;

    /**
     * 取最近 {@code want} 根。未命中时调用 {@code loader} 加载
     * {@link #CACHE_SIZE} 根并回填两级缓存。
     *
     * @param adjust none / forward；不同口径的数据不能互相复用
     * @param want   需要的根数；超过 {@link #CACHE_SIZE} 时直接走 loader，不占用缓存
     */
    public List<Bar> getRecent(String symbol, String period, String adjust, int want,
                               Supplier<List<Bar>> loader) {
        if (want > CACHE_SIZE) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        String key = key(symbol, period, adjust);

        Entry l1Hit = l1.get(key);
        if (l1Hit != null && l1Hit.expireAt() > now && l1Hit.bars().size() >= want) {
            return tail(l1Hit.bars(), want);
        }

        List<Bar> fromRedis = readRedis(key);
        if (fromRedis != null && fromRedis.size() >= want) {
            putL1(key, fromRedis, now);
            return tail(fromRedis, want);
        }

        List<Bar> fresh = loader.get();
        if (fresh == null || fresh.isEmpty()) {
            return fresh == null ? new ArrayList<>() : fresh;
        }
        putL1(key, fresh, now);
        writeRedis(key, fresh);
        return tail(fresh, want);
    }

    /**
     * 同步任务写库并提交后调用：让新数据立刻可见，而不必等 TTL 自然过期。
     *
     * <p>两个 adjust 都要失效——同步只写原始价，但 forward 是原始价的派生视图，
     * 原始价变了它也必须重建。
     */
    public void evict(String symbol, String period) {
        evictAll(List.of(symbol), period);
    }

    /**
     * 批量失效，供同步任务在一天的全市场写完后一次调用。
     *
     * <p>逐标的、逐 key 发 DEL，一天 5,500 只 × 2 个 adjust 就是 11,000 次往返；
     * 这里 L1 扫一遍、L2 一次 {@code DEL key...}，把往返压到常数级。
     */
    public void evictAll(Collection<String> symbols, String period) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        String marker = ":" + period + ":";
        l1.keySet().removeIf(k -> k.startsWith(KEY_PREFIX)
                && k.contains(marker)
                && symbols.contains(symbolBetween(k, marker)));

        List<String> keys = new ArrayList<>(symbols.size() * ADJUSTS.size());
        for (String symbol : symbols) {
            for (String adjust : ADJUSTS) {
                keys.add(key(symbol, period, adjust));
            }
        }
        try {
            redis.delete(keys);
        } catch (Exception e) {
            log.warn("[K线缓存] 批量失效失败（{} 只，周期 {}），将由 TTL 兜底: {}",
                    symbols.size(), period, e.getMessage());
        }
    }

    private static String key(String symbol, String period, String adjust) {
        return KEY_PREFIX + period + ":" + symbol + ":" + adjust;
    }

    /** 从 {@code kline:v1:1d:600519.SH:none} 里取出 {@code 600519.SH}。 */
    private static String symbolBetween(String key, String marker) {
        int from = key.indexOf(marker) + marker.length();
        int to = key.lastIndexOf(':');
        return (from <= to) ? key.substring(from, to) : "";
    }

    private static List<Bar> tail(List<Bar> bars, int n) {
        if (bars.size() <= n) {
            return bars;
        }
        // 复制而不是返回 subList 视图：视图会持有整份 800 根的引用，
        // 生命周期不可控，反序列化到 JSON 时也容易踩到并发修改。
        return new ArrayList<>(bars.subList(bars.size() - n, bars.size()));
    }

    private void putL1(String key, List<Bar> bars, long now) {
        if (l1.size() >= L1_MAX) {
            l1.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
            if (l1.size() >= L1_MAX) {
                l1.clear();
                log.info("[K线缓存] L1 达到上限 {}，已整体清空", L1_MAX);
            }
        }
        l1.put(key, new Entry(bars, now + L1_TTL_MS));
    }

    private List<Bar> readRedis(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return JSON.parseArray(json, Bar.class);
        } catch (Exception e) {
            // 反序列化失败（旧格式/字段变更）与连接失败都按未命中处理
            log.warn("[K线缓存] 读取 L2 失败，降级查库: {}", e.getMessage());
            return null;
        }
    }

    private void writeRedis(String key, List<Bar> bars) {
        try {
            redis.opsForValue().set(key, JSON.toJSONString(bars), L2_TTL);
        } catch (Exception e) {
            log.warn("[K线缓存] 写入 L2 失败，不影响本次结果: {}", e.getMessage());
        }
    }
}
