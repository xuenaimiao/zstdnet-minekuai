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
