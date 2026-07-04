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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ZNCM manifest 编解码：往返、空表、自纠错（魔数不匹配→null）、fail-closed（损坏→IOException）。
 */
class ChunkManifestTest {

    @Test
    void roundTrip() throws IOException {
        List<Hash128> in = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            in.add(new Hash128(0x1111_0000L + i, 0xABCD_0000L - i));
        }
        byte[] frame = ChunkManifest.encode(in);
        List<Hash128> out = ChunkManifest.parse(frame);
        assertEquals(in, out);
    }

    @Test
    void emptyManifestRoundTrips() throws IOException {
        byte[] frame = ChunkManifest.encode(Collections.emptyList());
        List<Hash128> out = ChunkManifest.parse(frame);
        assertEquals(Collections.emptyList(), out);
    }

    @Test
    void nonManifestPayloadReturnsNull() throws IOException {
        // 形如 login-start 包载荷（不以 ZNCM 起头）→ 自纠错：返回 null，调用方按普通包转发。
        byte[] loginStart = {0x00, 0x05, 'h', 'e', 'l', 'l', 'o'};
        assertNull(ChunkManifest.parse(loginStart));
        assertNull(ChunkManifest.parse(new byte[]{0x00}));
        assertNull(ChunkManifest.parse(new byte[0]));
    }

    @Test
    void badVersionFailsClosed() {
        byte[] frame = ChunkManifest.encode(Arrays.asList(new Hash128(1, 2)));
        frame[ChunkCacheFormat.MANIFEST_MAGIC.length] = (byte) 0x7F; // 篡改版本
        assertThrows(IOException.class, () -> ChunkManifest.parse(frame));
    }

    @Test
    void truncatedEntriesFailClosed() {
        byte[] frame = ChunkManifest.encode(Arrays.asList(new Hash128(1, 2), new Hash128(3, 4)));
        byte[] cut = Arrays.copyOf(frame, frame.length - 5); // 砍掉部分条目字节
        assertThrows(IOException.class, () -> ChunkManifest.parse(cut));
    }

    @Test
    void declaredCountMismatchFailsClosed() {
        byte[] frame = ChunkManifest.encode(Arrays.asList(new Hash128(1, 2)));
        // 追加垃圾字节使长度与声明计数不符。
        byte[] extended = Arrays.copyOf(frame, frame.length + 4);
        assertThrows(IOException.class, () -> ChunkManifest.parse(extended));
    }
}
