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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Hash128 的字节/十六进制对称编解码与作 Map 键的语义。
 */
class Hash128Test {

    @Test
    void bytesRoundTripBigEndian() {
        Random r = new Random(7L);
        for (int i = 0; i < 1000; i++) {
            Hash128 h = new Hash128(r.nextLong(), r.nextLong());
            byte[] b = h.toBytes();
            assertEquals(16, b.length);
            assertEquals(h, Hash128.fromBytes(b, 0));
        }
    }

    @Test
    void bytesRoundTripWithOffset() {
        Hash128 h = new Hash128(0x0123456789ABCDEFL, 0xFEDCBA9876543210L);
        byte[] buf = new byte[20];
        h.writeBytes(buf, 3);
        assertEquals(h, Hash128.fromBytes(buf, 3));
        // 大端：hi 高字节在前。
        assertEquals((byte) 0x01, buf[3]);
        assertEquals((byte) 0xFE, buf[11]);
    }

    @Test
    void hexRoundTrip() {
        Hash128 h = new Hash128(0xDEADBEEFCAFEBABEL, 0x0L);
        String hex = h.toHex();
        assertEquals(32, hex.length());
        assertEquals(h, Hash128.fromHex(hex));
    }

    @Test
    void fromHexRejectsBadInput() {
        assertNull(Hash128.fromHex(null));
        assertNull(Hash128.fromHex("short"));
        assertNull(Hash128.fromHex("zz3779b97f4a7c15zz3779b97f4a7c15"));
    }

    @Test
    void usableAsMapKey() {
        Map<Hash128, byte[]> map = new HashMap<>();
        Hash128 k = new Hash128(1L, 2L);
        byte[] v = {9, 8, 7};
        map.put(k, v);
        assertArrayEquals(v, map.get(new Hash128(1L, 2L)));
    }
}
