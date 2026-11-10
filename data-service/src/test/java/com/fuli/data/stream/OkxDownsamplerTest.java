package com.fuli.data.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P8：高频深度流的降采样与背压计数。 */
class OkxDownsamplerTest {

    @Test
    void keepsAtMostOneFramePerInterval() {
        OkxDownsampler d = new OkxDownsampler(1000L);
        assertTrue(d.shouldKeep(10_000L));   // 首帧保留
        assertFalse(d.shouldKeep(10_010L));  // 10ms 后的原始帧丢弃
        assertFalse(d.shouldKeep(10_100L));
        assertFalse(d.shouldKeep(10_990L));
        assertTrue(d.shouldKeep(11_000L));   // 满 1s 保留
        assertEquals(3, d.droppedCount());
    }

    @Test
    void exactIntervalBoundaryIsKept() {
        OkxDownsampler d = new OkxDownsampler(100L);
        assertTrue(d.shouldKeep(100L));
        assertTrue(d.shouldKeep(200L));
        assertEquals(0, d.droppedCount());
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new OkxDownsampler(0));
        assertEquals(1000L, new OkxDownsampler(1000L).intervalMs());
    }
}
