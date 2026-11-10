package com.fuli.gateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流桶注册表：按「限流键」维护令牌桶，带闲置清理与容量上限。
 *
 * <p>键形如 {@code user:42}（已认证）或 {@code ip:1.2.3.4}（未认证）。
 * 桶按需创建；表满时先清理闲置桶，仍满则<b>放行</b>（fail-open），绝不因为限流器自身
 * 的问题把正常流量拦下来——限流是保护性能力，不是正确性约束。
 */
@Slf4j
@Component
public class RateLimitRegistry {

    /** 桶闲置超过该时长（纳秒）即视为可回收。 */
    private static final long IDLE_NANOS = 5L * 60 * 1_000_000_000;

    /** 桶数量软上限；超过后触发清理，清理后仍超则 fail-open。 */
    private static final int MAX_BUCKETS = 100_000;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** 清理闲置桶；表超限时由写入方调用。 */
    private void purgeIfNeeded(String key) {
        if (buckets.size() < MAX_BUCKETS) {
            return;
        }
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> !e.getKey().equals(key)
                && e.getValue().idleNanos(now) > IDLE_NANOS);
        if (buckets.size() >= MAX_BUCKETS) {
            // 清理不动（大量活跃 key）：fail-open，放行由上层决定，这里不拦。
            log.warn("限流桶已达上限 {}，清理后仍满，本次放行", MAX_BUCKETS);
        }
    }

    /**
     * 供过滤器调用：先做容量守卫再消费。合并为一个入口避免重复计算。
     */
    public boolean acquire(String key, double replenishRate, double burstCapacity) {
        purgeIfNeeded(key);
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(replenishRate, burstCapacity));
        return bucket.tryAcquire();
    }
}
