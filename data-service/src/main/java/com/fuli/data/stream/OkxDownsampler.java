package com.fuli.data.stream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P8：高频深度流的时间降采样器。
 *
 * <p>OKX 的 books-l2-tbt 是 10ms 一帧，books5 是 100ms 一帧。原始流只在
 * {@code OkxWsFrameBuffer} 内存里，这里按固定时间间隔**只放一帧过去**落稀疏快照，
 * 其余丢弃并计数。这样 {@code crypto_book_snapshot} 不会变成每秒上百行的写入放大器。
 *
 * <p>不是滑动窗口、不做聚合——实时盘口只有最新一帧有意义，取间隔内最后一帧即可。
 */
public class OkxDownsampler {

    private final long intervalMs;

    private long lastKeptTs = Long.MIN_VALUE;

    private final AtomicLong dropped = new AtomicLong();

    public OkxDownsampler(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("降采样间隔必须为正数");
        }
        this.intervalMs = intervalMs;
    }

    public synchronized boolean shouldKeep(long tsMs) {
        if (lastKeptTs == Long.MIN_VALUE || tsMs - lastKeptTs >= intervalMs) {
            lastKeptTs = tsMs;
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long intervalMs() {
        return intervalMs;
    }
}
