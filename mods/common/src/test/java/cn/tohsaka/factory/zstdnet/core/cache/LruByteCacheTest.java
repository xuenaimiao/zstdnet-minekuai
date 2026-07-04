/*
 * Copyright (c) 2026 wish (original author, MIT — https://github.com/wish131400/zstdnet)
 * Copyright (c) 2026 xuenai · 麦块联机 / MineKuai (https://minekuai.com)
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is a derivative work of the MIT-licensed ZstdNet by wish. wish's
 * original portions remain under the MIT License (see the LICENSE file); that
 * upstream grant is preserved and not revoked.
 *
 * This project as a whole — and all modifications and additions by xuenai — is
 * licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0
 * International License (CC BY-NC-SA 4.0). You may share and adapt it for
 * NON-COMMERCIAL purposes only, must give appropriate credit and retain the
 * copyright notices above, and must distribute your contributions under this
 * same license (share-alike, source included).
 *
 * You should have received a copy of the license along with ZstdNet.
 * If not, see <https://creativecommons.org/licenses/by-nc-sa/4.0/>.
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
