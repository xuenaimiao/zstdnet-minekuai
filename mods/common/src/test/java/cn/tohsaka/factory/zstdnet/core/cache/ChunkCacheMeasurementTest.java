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

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * chunk_cache=measure 埋点的统计正确性：会话内重复（窗口内/外）、跨会话重复、按 id 全区块包，
 * 以及对损坏输入的 fail-safe（绝不抛异常、绝不拖累转发）。
 */
class ChunkCacheMeasurementTest {

    private static final int PROTO = 763;       // 1.20.1
    private static final int FULL_CHUNK_ID = 0x24;

    @Test
    void inSessionRepeatWithinWindow() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false); // 1 MiB 窗口
        ChunkCacheMeasurement.Collector c = m.newCollector(PROTO);
        byte[] a = frame(FULL_CHUNK_ID, body(300, 1));
        c.accept(a, 0, a.length);
        c.accept(a, 0, a.length);

        assertEquals(2, c.totalFrames);
        assertEquals(a.length, c.inSessionRepeatBytes);
        assertEquals(a.length, c.repeatWithinWindowBytes);
        assertEquals(0, c.repeatBeyondWindowBytes);
        assertEquals(2L * a.length, c.cacheableBytes);
        assertEquals(a.length, c.cacheableRepeatBytes);
        assertEquals(0, c.crossSessionRepeatBytes);
    }

    @Test
    void inSessionRepeatBeyondWindow() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(100L, false); // 极小窗口
        ChunkCacheMeasurement.Collector c = m.newCollector(PROTO);
        byte[] a = frame(FULL_CHUNK_ID, body(300, 1));
        byte[] b = frame(FULL_CHUNK_ID, body(300, 2)); // 不同内容，把 a 推到窗口外
        c.accept(a, 0, a.length);
        c.accept(b, 0, b.length);
        c.accept(a, 0, a.length);

        assertEquals(a.length, c.inSessionRepeatBytes);
        assertEquals(0, c.repeatWithinWindowBytes);
        assertEquals(a.length, c.repeatBeyondWindowBytes);
    }

    @Test
    void crossSessionRepeatAcrossCollectors() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false);
        byte[] a = frame(FULL_CHUNK_ID, body(300, 7));

        ChunkCacheMeasurement.Collector c1 = m.newCollector(PROTO);
        c1.accept(a, 0, a.length);
        c1.finish();
        assertEquals(0, c1.crossSessionRepeatBytes); // 首见，非跨会话

        ChunkCacheMeasurement.Collector c2 = m.newCollector(PROTO);
        c2.accept(a, 0, a.length);
        assertEquals(a.length, c2.crossSessionRepeatBytes); // 上条连接见过 → 跨会话重复
        assertEquals(0, c2.inSessionRepeatBytes);
    }

    @Test
    void nonCacheableLargeFrameStillTracksRepeatButNotById() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false);
        ChunkCacheMeasurement.Collector c = m.newCollector(PROTO);
        byte[] x = frame(0x50, body(300, 3)); // 非全区块 id
        c.accept(x, 0, x.length);
        c.accept(x, 0, x.length);

        assertEquals(x.length, c.inSessionRepeatBytes);
        assertEquals(0, c.cacheableBytes);
        assertEquals(0, c.cacheableRepeatBytes);
    }

    @Test
    void splitBoundariesProduceSameTally() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false);
        ChunkCacheMeasurement.Collector c = m.newCollector(PROTO);
        byte[] a = frame(FULL_CHUNK_ID, body(300, 9));
        byte[] stream = concat(a, a);
        for (byte value : stream) {
            c.accept(new byte[]{value}, 0, 1); // 逐字节喂入
        }
        assertEquals(2, c.totalFrames);
        assertEquals(a.length, c.inSessionRepeatBytes);
    }

    @Test
    void malformedInputNeverThrows() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false);
        ChunkCacheMeasurement.Collector c = m.newCollector(PROTO);
        byte[] garbage = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        assertDoesNotThrow(() -> c.accept(garbage, 0, garbage.length));
        // 进入 broken 后后续输入安静忽略，仍不抛
        byte[] a = frame(FULL_CHUNK_ID, body(300, 1));
        assertDoesNotThrow(() -> c.accept(a, 0, a.length));
        assertDoesNotThrow(c::finish);
    }

    @Test
    void unknownProtocolStillCountsBySize() {
        ChunkCacheMeasurement m = new ChunkCacheMeasurement(1L << 20, false);
        ChunkCacheMeasurement.Collector c = m.newCollector(404); // 无表
        byte[] a = frame(FULL_CHUNK_ID, body(300, 1));
        c.accept(a, 0, a.length);
        c.accept(a, 0, a.length);
        // 无表 → 不按 id 归类，但按大小重复仍统计
        assertEquals(0, c.cacheableBytes);
        assertEquals(a.length, c.inSessionRepeatBytes);
    }

    // ---- helpers ----

    private static byte[] frame(int packetId, byte[] payloadBody) {
        byte[] dl = VarIntCodec.encode(0);
        byte[] pid = VarIntCodec.encode(packetId);
        byte[] payload = concat(dl, pid, payloadBody);
        byte[] len = VarIntCodec.encode(payload.length);
        return concat(len, payload);
    }

    private static byte[] body(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return b;
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }
}
