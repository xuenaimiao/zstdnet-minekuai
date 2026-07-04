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

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * content64/content128 的关键不变量：hi 半逐位等于 content64（编码端只算一次即得两种宽度的键），及确定性。
 */
class HashingTest {

    @Test
    void hi128EqualsContent64ForManyInputs() {
        Random r = new Random(20260620L);
        for (int t = 0; t < 500; t++) {
            byte[] data = new byte[r.nextInt(4096)];
            r.nextBytes(data);
            assertEquals(Hashing.content64(data), Hashing.content128(data).hi(),
                "content128.hi must equal content64 (so the 8-byte in-session token matches)");
        }
    }

    @Test
    void content128IsDeterministicAndRangeConsistent() {
        byte[] data = "the quick brown fox jumps over the lazy dog".getBytes();
        Hash128 whole = Hashing.content128(data);
        Hash128 again = Hashing.content128(data, 0, data.length);
        assertEquals(whole, again);
        // 不同输入应得不同 128 位（极大概率）。
        byte[] other = "the quick brown fox jumps over the lazy dox".getBytes();
        assertNotEquals(whole, Hashing.content128(other));
    }

    @Test
    void hiAndLoAreIndependentEnough() {
        // lo 半不应恒等于 hi 半（否则 128 位退化为 64 位）。
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7);
        }
        Hash128 h = Hashing.content128(data);
        assertNotEquals(h.hi(), h.lo());
    }
}
