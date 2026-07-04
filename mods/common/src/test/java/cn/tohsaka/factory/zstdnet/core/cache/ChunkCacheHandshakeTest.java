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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CRC 能力握手标记 zstdnet-ccache= 的解析/剥离：与 FML、real-ip、xform 等其它 \0 字段任意顺序共存，
 * 剥离后其余字段原样保留。
 */
class ChunkCacheHandshakeTest {

    @Test
    void parsesVersion() {
        assertEquals(0, ChunkCacheHandshake.parseVersion("play.example.com"));
        assertEquals(0, ChunkCacheHandshake.parseVersion(null));
        assertEquals(1, ChunkCacheHandshake.parseVersion("play.example.com\0zstdnet-ccache=1"));
        assertEquals(1, ChunkCacheHandshake.parseVersion("h\0zstdnet-xform=3\0zstdnet-ccache=1\0FML2\0"));
        assertEquals(0, ChunkCacheHandshake.parseVersion("h\0zstdnet-ccache=abc"));
    }

    @Test
    void stripsKeepingOtherFields() {
        assertEquals("play.example.com", ChunkCacheHandshake.strip("play.example.com"));
        assertEquals("play.example.com", ChunkCacheHandshake.strip("play.example.com\0zstdnet-ccache=1"));
        // 与 xform / real-ip 共存：只剥 ccache，其余原样
        assertEquals("h\0zstdnet-xform=3", ChunkCacheHandshake.strip("h\0zstdnet-xform=3\0zstdnet-ccache=1"));
        assertEquals("h\0zstdnet-real-ip=YWJj", ChunkCacheHandshake.strip("h\0zstdnet-ccache=2\0zstdnet-real-ip=YWJj"));
        assertEquals(null, ChunkCacheHandshake.strip(null));
    }

    @Test
    void advertiseRoundTrip() {
        String host = "play.example.com";
        for (int v = 1; v <= 3; v++) {
            String advertised = host + ChunkCacheHandshake.advertiseSuffix(v);
            assertEquals(v, ChunkCacheHandshake.parseVersion(advertised));
            assertEquals(host, ChunkCacheHandshake.strip(advertised));
        }
        // 与 xform 标记叠加：两者独立解析、互不剥除对方
        String both = host + "\0zstdnet-xform=3" + ChunkCacheHandshake.advertiseSuffix(1);
        assertEquals(1, ChunkCacheHandshake.parseVersion(both));
        assertEquals(host + "\0zstdnet-xform=3", ChunkCacheHandshake.strip(both));
    }
}
