/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.core.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端跨会话持久层：写穿透 + 重开加载 + 完整性校验丢弃损坏 + 预算淘汰。
 */
class ChunkCacheStoreTest {

    private static final long BIG = 64L * 1024 * 1024;

    /** 落盘是异步的：在 JUnit 删除 @TempDir 前先等后台 I/O 落定，避免“并发写 vs 删目录”的清理竞态。 */
    @AfterEach
    void flushPersistence() {
        ChunkCacheStore.awaitPersistence();
    }

    @Test
    void putThenSnapshotAndReopenLoads(@TempDir Path dir) {
        byte[] a = frame(1, 2000);
        byte[] b = frame(2, 3000);
        Hash128 ka = Hashing.content128(a);
        Hash128 kb = Hashing.content128(b);

        ChunkCacheStore store = ChunkCacheStore.openAt(dir, BIG);
        store.put(ka, a);
        store.put(kb, b);
        Map<Hash128, byte[]> warm = store.snapshotWarm();
        assertEquals(2, warm.size());
        assertArrayEquals(a, warm.get(ka));
        assertArrayEquals(b, warm.get(kb));

        // 重开（模拟重连）：从磁盘加载、完整性校验通过。落盘是异步的，先等其落定再重开。
        ChunkCacheStore.awaitPersistence();
        ChunkCacheStore reopened = ChunkCacheStore.openAt(dir, BIG);
        Map<Hash128, byte[]> warm2 = reopened.snapshotWarm();
        assertEquals(2, warm2.size());
        assertArrayEquals(a, warm2.get(ka));
        assertArrayEquals(b, warm2.get(kb));
    }

    @Test
    void putIsIdempotent(@TempDir Path dir) {
        byte[] a = frame(1, 1000);
        Hash128 ka = Hashing.content128(a);
        ChunkCacheStore store = ChunkCacheStore.openAt(dir, BIG);
        store.put(ka, a);
        long bytes1 = store.bytes();
        store.put(ka, a);
        assertEquals(bytes1, store.bytes());
        assertEquals(1, store.size());
    }

    @Test
    void corruptFileDroppedOnLoad(@TempDir Path dir) throws IOException {
        byte[] a = frame(1, 1500);
        Hash128 ka = Hashing.content128(a);
        // 用 a 的哈希命名，却写入 b 的内容 → content128 不符 → 加载时丢弃。
        byte[] b = frame(99, 1500);
        Files.write(dir.resolve(ka.toHex() + ".chunk"), b);

        ChunkCacheStore store = ChunkCacheStore.openAt(dir, BIG);
        assertEquals(0, store.size(), "tampered entry must be dropped (never served as WARM_REF)");
        assertFalse(Files.exists(dir.resolve(ka.toHex() + ".chunk")), "corrupt file should be deleted");
    }

    @Test
    void badFilenameDroppedOnLoad(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("not-a-hash.chunk"), frame(1, 100));
        ChunkCacheStore store = ChunkCacheStore.openAt(dir, BIG);
        assertEquals(0, store.size());
    }

    @Test
    void evictsToBudget(@TempDir Path dir) {
        byte[] a = frame(1, 4000);
        byte[] b = frame(2, 4000);
        Hash128 ka = Hashing.content128(a);
        Hash128 kb = Hashing.content128(b);
        long tight = a.length + 100; // 只容得下一个
        ChunkCacheStore store = ChunkCacheStore.openAt(dir, tight);
        store.put(ka, a);
        store.put(kb, b);
        assertEquals(1, store.size());
        assertTrue(store.snapshotWarm().containsKey(kb), "newest entry survives eviction");
        assertFalse(store.snapshotWarm().containsKey(ka), "oldest entry evicted");
    }

    @Test
    void touchRefreshesRecencySoHotEntrySurvivesEviction(@TempDir Path dir) {
        byte[] a = frame(1, 4000);
        byte[] b = frame(2, 4000);
        byte[] c = frame(3, 4000);
        Hash128 ka = Hashing.content128(a);
        Hash128 kb = Hashing.content128(b);
        Hash128 kc = Hashing.content128(c);
        long twoFit = 2L * (4000 + 64) + 64; // 容得下 2 个、容不下 3 个
        ChunkCacheStore store = ChunkCacheStore.openAt(dir, twoFit);
        store.put(ka, a);
        store.put(kb, b); // 此刻插入序：a(最旧) → b
        store.touch(ka);  // a 升为最近 → 序：b(最旧) → a
        store.put(kc, c); // 超预算 → 逐出最旧 = b（而非 a）
        assertEquals(2, store.size());
        assertTrue(store.snapshotWarm().containsKey(ka), "touched (hot) entry must survive");
        assertTrue(store.snapshotWarm().containsKey(kc), "newest entry survives");
        assertFalse(store.snapshotWarm().containsKey(kb), "untouched older entry is evicted");
    }

    // ---- helpers ----

    private static byte[] frame(int seed, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (seed * 131 + i * 7);
        }
        return b;
    }
}
