package com.fuli.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    /** 可控时钟：测试里手动推进纳秒。 */
    private static final class FakeClock {
        final AtomicLong now = new AtomicLong(0);
        void advanceSeconds(double seconds) {
            now.addAndGet((long) (seconds * 1_000_000_000L));
        }
    }

    @Test
    void initialBurstAllowsUpToCapacityThenRejects() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(10.0, 5.0, clock.now::get);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "第 " + (i + 1) + " 次应通过");
        }
        assertFalse(bucket.tryAcquire(), "突发耗尽后应拒绝");
    }

    @Test
    void refillsAtConfiguredRate() {
        FakeClock clock = new FakeClock();
        // 1 枚/秒、容量 3
        TokenBucket bucket = new TokenBucket(1.0, 3.0, clock.now::get);
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "容量已空");

        clock.advanceSeconds(2.0); // 补 2 枚
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "补的 2 枚用完后应拒绝");
    }

    @Test
    void neverExceedsBurstCapacityEvenAfterLongIdle() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(1.0, 3.0, clock.now::get);
        clock.advanceSeconds(1000.0); // 长时间闲置，补充量远超容量

        for (int i = 0; i < 3; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire(), "令牌不应超过突发容量");
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10.0, 0.0));
    }

    @Test
    void tracksIdleTimeForCleanup() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(1.0, 3.0, clock.now::get);
        clock.advanceSeconds(30.0);
        assertTrue(bucket.idleNanos(clock.now.get()) >= 30L * 1_000_000_000L);
    }
}
