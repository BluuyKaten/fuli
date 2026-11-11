package com.fuli.trade.practice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PracticeSnapshotCacheTest {
    private static PracticeState.Snapshot snapshot() {
        return new PracticeState.Snapshot(List.of(), 0);
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedAtCapacity() {
        var cache = new PracticeSnapshotCache(2);
        cache.put("a", snapshot());
        cache.put("b", snapshot());
        assertNotNull(cache.get("a"));          // a 变成最近使用
        cache.put("c", snapshot());
        assertEquals(2, cache.size());
        assertNotNull(cache.get("a"));
        assertNull(cache.get("b"), "容量满时应淘汰最久未用的会话");
        assertNotNull(cache.get("c"));
    }

    @Test
    void invalidateForcesTheNextReadToGoBackToTheDatabase() {
        var cache = new PracticeSnapshotCache();
        cache.put("a", snapshot());
        cache.invalidate("a");
        assertNull(cache.get("a"));
        assertEquals(1, cache.misses());
        assertEquals(0, cache.hits());
    }

    @Test
    void countersSeparateHitsFromMisses() {
        var cache = new PracticeSnapshotCache();
        assertNull(cache.get("a"));
        cache.put("a", snapshot());
        assertSame(cache.get("a"), cache.get("a"));
        assertEquals(1, cache.misses());
        assertEquals(2, cache.hits());
        assertNull(cache.get(null));
        assertEquals(1, cache.misses(), "null key 不应计入未命中");
    }
}
