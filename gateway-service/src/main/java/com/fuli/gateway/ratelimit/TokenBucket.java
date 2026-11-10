package com.fuli.gateway.ratelimit;

import java.util.function.LongSupplier;

/**
 * 令牌桶限流器（纯函数、线程安全、可单测）。
 *
 * <p>规则：桶以 {@code replenishRatePerSec} 的速率补充令牌，容量上限 {@code burstCapacity}；
 * 每次 {@link #tryAcquire()} 消耗 1 枚令牌，不足则拒绝。与 RedisRateLimiter 的语义一致，
 * 但这里是单实例内存实现——网关目前单点部署，无需分布式一致；多实例时再换 RedisRateLimiter。
 *
 * <p>时间源通过 {@link LongSupplier} 注入（纳秒单调钟），测试可替换为可控时钟；
 * 生产用 {@link System#nanoTime()}，不随墙钟回拨而失效。
 */
public class TokenBucket {

    private final double replenishRatePerSec;
    private final double burstCapacity;
    private final LongSupplier nanoTime;

    private double tokens;
    private long lastRefillNanos;
    private volatile long lastAccessNanos;

    public TokenBucket(double replenishRatePerSec, double burstCapacity) {
        this(replenishRatePerSec, burstCapacity, System::nanoTime);
    }

    TokenBucket(double replenishRatePerSec, double burstCapacity, LongSupplier nanoTime) {
        if (replenishRatePerSec <= 0) {
            throw new IllegalArgumentException("replenishRatePerSec 必须 > 0");
        }
        if (burstCapacity < 1) {
            throw new IllegalArgumentException("burstCapacity 必须 >= 1");
        }
        this.replenishRatePerSec = replenishRatePerSec;
        this.burstCapacity = burstCapacity;
        this.nanoTime = nanoTime;
        this.tokens = burstCapacity;
        long now = nanoTime.getAsLong();
        this.lastRefillNanos = now;
        this.lastAccessNanos = now;
    }

    /**
     * 尝试消耗 1 枚令牌。
     *
     * @return true 表示通过（消耗成功），false 表示限流（拒绝本次请求）
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** 距上次访问的时长（纳秒），供清理闲置桶。 */
    public long idleNanos(long now) {
        return now - lastAccessNanos;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(burstCapacity, tokens + elapsedSeconds * replenishRatePerSec);
            lastRefillNanos = now;
        }
        lastAccessNanos = now;
    }
}
