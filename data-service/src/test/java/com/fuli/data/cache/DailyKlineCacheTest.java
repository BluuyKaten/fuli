package com.fuli.data.cache;

import com.fuli.data.entity.Bar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两级缓存的 key 口径与失效边界。本机不一定有 Redis，所以全部用 mock 固定行为：
 *
 * <p>1. <b>key 必须带 period 与 adjust</b>——阶段 0 起一张 {@code bar} 覆盖全周期，
 * 复权又是查询参数，任一项漏在 key 外都会让日线与周线、原始价与前复权互相污染；
 * <p>2. <b>evict 只清该标的该周期</b>，不能把别的周期/别的标的顺手带走；
 * <p>3. <b>Redis 挂了不能变成故障点</b>：读写异常一律降级查库。
 */
class DailyKlineCacheTest {

    private StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;

    private DailyKlineCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.delete(org.mockito.ArgumentMatchers.<Collection<String>>any()))
                .thenReturn(2L);
        cache = new DailyKlineCache(redis);
    }

    private static Bar bar(long ts, String close) {
        Bar b = new Bar();
        b.setInstrumentCode("600519.SH");
        b.setPeriod("1d");
        b.setTs(ts);
        b.setClosePrice(new BigDecimal(close));
        return b;
    }

    private static List<Bar> barsOf(int n) {
        List<Bar> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(bar(1_600_000_000L + i * 86_400L, String.valueOf(100 + i)));
        }
        return list;
    }

    @Test
    @DisplayName("key 带 period 与 adjust：日线/周线、原始价/前复权互不污染")
    void keySeparatesPeriodAndAdjust() {
        when(ops.get(anyString())).thenReturn(null);

        AtomicInteger loads = new AtomicInteger();
        cache.getRecent("600519.SH", "1d", "none", 10, () -> {
            loads.incrementAndGet();
            return barsOf(10);
        });
        cache.getRecent("600519.SH", "1w", "none", 10, () -> {
            loads.incrementAndGet();
            return barsOf(10);
        });
        cache.getRecent("600519.SH", "1d", "forward", 10, () -> {
            loads.incrementAndGet();
            return barsOf(10);
        });

        // 三个不同口径各回源一次，共 3 次；若 key 漏了 period/adjust 会命中同一个键
        assertEquals(3, loads.get());

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ops, times(3)).set(written.capture(), anyString(), any(Duration.class));
        assertTrue(written.getAllValues().contains("kline:v1:1d:600519.SH:none"));
        assertTrue(written.getAllValues().contains("kline:v1:1w:600519.SH:none"));
        assertTrue(written.getAllValues().contains("kline:v1:1d:600519.SH:forward"));
    }

    @Test
    @DisplayName("L1 命中后不再回源，也不重复写 Redis")
    void l1HitSkipsLoader() {
        when(ops.get(anyString())).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();
        List<Bar> source = barsOf(20);

        List<Bar> first = cache.getRecent("600519.SH", "1d", "none", 5,
                () -> { loads.incrementAndGet(); return source; });
        List<Bar> second = cache.getRecent("600519.SH", "1d", "none", 5,
                () -> { loads.incrementAndGet(); return source; });

        assertEquals(1, loads.get());
        assertEquals(5, first.size());
        assertEquals(5, second.size());
        // 取的是尾部（最近 5 根）
        assertEquals(new BigDecimal("119"), second.get(4).getClosePrice());
        verify(ops, times(1)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("evictAll：只清该标的该周期的两个 adjust，L1 一并失效")
    void evictAllClearsOnlyTargetKeys() {
        when(ops.get(anyString())).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();
        // 预置：目标 1d、同标的 1w、别的标的 1d
        cache.getRecent("600519.SH", "1d", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        cache.getRecent("600519.SH", "1w", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        cache.getRecent("000001.SZ", "1d", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        assertEquals(3, loads.get());

        cache.evictAll(List.of("600519.SH"), "1d");

        // L2：两条 DEL 必须精确落在 none/forward 两个键上
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(redis).delete((Collection<String>) keys.capture());
        assertEquals(List.of("kline:v1:1d:600519.SH:none", "kline:v1:1d:600519.SH:forward"),
                new ArrayList<>(keys.getValue()));

        // L1：目标标的的 1d 失效 → 回源；同标的 1w 与别的标的 1d 仍然命中
        cache.getRecent("600519.SH", "1d", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        assertEquals(4, loads.get(), "目标 key 应已失效并回源");

        cache.getRecent("600519.SH", "1w", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        cache.getRecent("000001.SZ", "1d", "none", 3, () -> { loads.incrementAndGet(); return barsOf(10); });
        assertEquals(4, loads.get(), "别的周期/别的标的不该被带走");
    }

    @Test
    @DisplayName("Redis 不可用时降级查库，不抛异常")
    void redisDownDegradesToLoader() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(redis.delete(org.mockito.ArgumentMatchers.<Collection<String>>any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        AtomicInteger loads = new AtomicInteger();
        List<Bar> result = cache.getRecent("600519.SH", "1d", "none", 3,
                () -> { loads.incrementAndGet(); return barsOf(10); });

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(1, loads.get());
        // 失效失败也不能冒泡到同步任务（evictAll 内部 catch 掉，这里只要求不抛）
        cache.evictAll(List.of("600519.SH"), "1d");
    }

    @Test
    @DisplayName("请求根数超过缓存长度时不占用缓存，直接回源")
    void oversizedRequestBypassesCache() {
        AtomicInteger loads = new AtomicInteger();
        cache.getRecent("600519.SH", "1d", "none", DailyKlineCache.CACHE_SIZE + 1,
                () -> { loads.incrementAndGet(); return barsOf(10); });

        assertEquals(1, loads.get());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
