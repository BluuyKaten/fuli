package com.fuli.data.okx.ws;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 帧的有界环形缓冲（实施计划 P3 的背压设施）。
 *
 * <p>OKX 的 books-l2-tbt 是 10ms 一帧，任何「先攒着慢慢写库」的想法都会把堆或 MySQL 打垮。
 * 这里的策略是<b>满了就丢最旧帧</b>：实时数据本来就是越新越有价值，
 * 背压必须是可观测的（{@link #droppedCount()}）而不是静默 OOM。
 *
 * <p><b>P3 约束</b>：缓冲只在内存里，消费方按 {@link #drain} 批量取走做降采样；
 * 10ms 原始流不落 MySQL（ADR-0009 硬约束 5）。
 *
 * @param <T> 帧类型，通常是原始 JSON 文本或解码后的轻量对象
 */
public class OkxWsFrameBuffer<T> {

    private final int capacity;

    private final Deque<T> frames;

    private final AtomicLong dropped = new AtomicLong();

    public OkxWsFrameBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("缓冲容量必须为正数：" + capacity);
        }
        this.capacity = capacity;
        this.frames = new ArrayDeque<>(capacity);
    }

    /** 入队；返回 true 表示未触发淘汰，false 表示丢掉了一帧最旧数据。 */
    public synchronized boolean offer(T frame) {
        Objects.requireNonNull(frame, "frame");
        boolean evicted = frames.size() >= capacity;
        if (evicted) {
            frames.pollFirst();
            dropped.incrementAndGet();
        }
        frames.addLast(frame);
        return !evicted;
    }

    /** 按 FIFO 取走最多 {@code max} 帧；消费后缓冲腾出空间。 */
    public synchronized List<T> drain(int max) {
        List<T> out = new ArrayList<>(Math.min(max, frames.size()));
        while (out.size() < max && !frames.isEmpty()) {
            out.add(frames.pollFirst());
        }
        return out;
    }

    public synchronized int size() {
        return frames.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public synchronized void clear() {
        frames.clear();
    }
}
