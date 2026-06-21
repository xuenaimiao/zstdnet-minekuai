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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheablePacketTableTest {

    @Test
    void knownProtocolsExposeFullChunkId() {
        assertTrue(CacheablePacketTable.forProtocol(758).isFullChunk(0x22)); // 1.18.2
        assertTrue(CacheablePacketTable.forProtocol(760).isFullChunk(0x21)); // 1.19.2
        assertTrue(CacheablePacketTable.forProtocol(763).isFullChunk(0x24)); // 1.20.1
        assertTrue(CacheablePacketTable.forProtocol(767).isFullChunk(0x27)); // 1.21.1
        assertTrue(CacheablePacketTable.forProtocol(775).isFullChunk(0x2D)); // 26.1.2（非混淆，注册序 45）
    }

    @Test
    void cacheableMatchesFullChunk() {
        CacheablePacketTable t = CacheablePacketTable.forProtocol(763);
        assertNotNull(t);
        assertTrue(t.isCacheable(0x24));
        assertFalse(t.isCacheable(0x25));
        assertFalse(t.isEmpty());
    }

    @Test
    void unknownProtocolReturnsNull() {
        assertNull(CacheablePacketTable.forProtocol(404));
        assertNull(CacheablePacketTable.forProtocol(759)); // 1.19 暂未覆盖
    }

    @Test
    void syntheticTableForTests() {
        CacheablePacketTable t = CacheablePacketTable.ofFullChunkIds(0x40, 0x41);
        assertTrue(t.isFullChunk(0x40));
        assertTrue(t.isCacheable(0x41));
        assertFalse(t.isFullChunk(0x42));
    }
}
