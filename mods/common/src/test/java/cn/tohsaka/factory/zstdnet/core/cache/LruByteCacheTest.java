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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LRU 字节缓存：预算淘汰、以及 peek（不触碰）vs get（触碰）的关键区别——这是“服务端 REF 决策不打乱 LRU、
 * 两端同步淘汰”的基础。
 */
class LruByteCacheTest {

    @Test
    void peekDoesNotTouchSoLruEvictsEldest() {
        LruByteCache c = new LruByteCache(400); // 约容 2 条（每条 100+64 开销）
        c.put(1, val(100));
        c.put(2, val(100)); // 顺序：2(MRU), 1(LRU)
        c.peek(1);          // peek 不应把 1 提到 MRU
        c.put(3, val(100)); // 触发淘汰最久未用 → 应淘汰 1
        assertNull(c.get(1), "peek 不该触碰，1 应被淘汰");
        assertNotNull(c.get(2));
        assertNotNull(c.get(3));
    }

    @Test
    void getTouchesSoEldestChanges() {
        LruByteCache c = new LruByteCache(400);
        c.put(1, val(100));
        c.put(2, val(100)); // 2(MRU), 1(LRU)
        c.get(1);           // get 把 1 提到 MRU → 1(MRU), 2(LRU)
        c.put(3, val(100)); // 淘汰最久未用 → 应淘汰 2
        assertNull(c.get(2), "get 触碰后，2 变成最久未用应被淘汰");
        assertNotNull(c.get(1));
        assertNotNull(c.get(3));
    }

    @Test
    void budgetIsEnforced() {
        LruByteCache c = new LruByteCache(2_000);
        for (int i = 0; i < 100; i++) {
            c.put(i, val(500));
        }
        assertTrue(c.bytes() <= 2_000, "字节数不得超预算: " + c.bytes());
        assertTrue(c.size() <= 4, "条目数应被预算限制: " + c.size());
    }

    @Test
    void updateSameKeyAdjustsBytes() {
        LruByteCache c = new LruByteCache(10_000);
        c.put(1, val(100));
        long after1 = c.bytes();
        c.put(1, val(300)); // 覆盖更大值
        assertTrue(c.bytes() == after1 + 200, "覆盖应按差值调整字节数");
        assertNotNull(c.get(1));
    }

    private static byte[] val(int n) {
        return new byte[n];
    }
}
