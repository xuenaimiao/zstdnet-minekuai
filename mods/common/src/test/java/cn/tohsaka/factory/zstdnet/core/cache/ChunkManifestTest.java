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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
        byte[] frame = ChunkManifest.encode(List.of());
        List<Hash128> out = ChunkManifest.parse(frame);
        assertEquals(List.of(), out);
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
        byte[] frame = ChunkManifest.encode(List.of(new Hash128(1, 2)));
        frame[ChunkCacheFormat.MANIFEST_MAGIC.length] = (byte) 0x7F; // 篡改版本
        assertThrows(IOException.class, () -> ChunkManifest.parse(frame));
    }

    @Test
    void truncatedEntriesFailClosed() {
        byte[] frame = ChunkManifest.encode(List.of(new Hash128(1, 2), new Hash128(3, 4)));
        byte[] cut = Arrays.copyOf(frame, frame.length - 5); // 砍掉部分条目字节
        assertThrows(IOException.class, () -> ChunkManifest.parse(cut));
    }

    @Test
    void declaredCountMismatchFailsClosed() {
        byte[] frame = ChunkManifest.encode(List.of(new Hash128(1, 2)));
        // 追加垃圾字节使长度与声明计数不符。
        byte[] extended = Arrays.copyOf(frame, frame.length + 4);
        assertThrows(IOException.class, () -> ChunkManifest.parse(extended));
    }
}
