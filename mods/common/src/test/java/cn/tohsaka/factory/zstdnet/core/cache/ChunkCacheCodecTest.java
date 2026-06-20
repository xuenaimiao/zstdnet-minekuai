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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRC 编解码往返字节一致性（PASSTHROUGH/FULL/REF），含任意读写边界、淘汰下不 miss、与 fail-closed。
 */
class ChunkCacheCodecTest {

    private static final int FULL_CHUNK_ID = 0x24; // 1.20.1
    private static final CacheablePacketTable TABLE = CacheablePacketTable.forProtocol(763);
    private static final long BUDGET = 1L << 20;

    @Test
    void passthroughOnlyRoundTrips() throws IOException {
        byte[] in = concat(frame(0x50, body(40, 1)), frame(0x51, body(60, 2)), frame(0x50, body(10, 3)));
        assertArrayEquals(in, roundTrip(in, TABLE, BUDGET, 16, 16));
    }

    @Test
    void repeatedChunkUsesRefAndStaysIdentical() throws IOException {
        byte[] chunk = frame(FULL_CHUNK_ID, body(4096, 7));
        byte[] in = concat(chunk, chunk, chunk, chunk); // 同一区块重发 4 次
        byte[] wire = encode(in, TABLE, BUDGET, 1 << 16);
        byte[] decoded = decode(wire, BUDGET, 64);
        assertArrayEquals(in, decoded);
        // 4 个相同大区块：首个 FULL + 3 个 8 字节 REF → wire 远小于原始
        assertTrue(wire.length < in.length / 2, "REF should shrink wire; wire=" + wire.length + " in=" + in.length);
        assertTrue(startsWithPreamble(wire));
    }

    @Test
    void mixedStreamRoundTrips() throws IOException {
        byte[] chunkA = frame(FULL_CHUNK_ID, body(2048, 1));
        byte[] chunkB = frame(FULL_CHUNK_ID, body(2048, 2));
        byte[] ent = frame(0x2B, body(20, 9));
        byte[] in = concat(ent, chunkA, ent, chunkB, chunkA, ent, chunkB);
        assertArrayEquals(in, roundTrip(in, TABLE, BUDGET, 7, 5)); // 奇数边界
    }

    @Test
    void byteAtATimeBoundariesRoundTrip() throws IOException {
        byte[] chunk = frame(FULL_CHUNK_ID, body(1500, 5));
        byte[] in = concat(frame(0x50, body(33, 4)), chunk, chunk, frame(0x50, body(7, 6)));
        assertArrayEquals(in, roundTrip(in, TABLE, BUDGET, 1, 1)); // 逐字节写 + 逐字节读
    }

    @Test
    void evictionNeverCausesRefMiss() throws IOException {
        // 预算只够 1 个区块：第二个区块进来会把第一个挤掉；再发第一个 → 服务端必须重发 FULL（不 REF）。
        byte[] chunkA = frame(FULL_CHUNK_ID, body(4000, 1));
        byte[] chunkB = frame(FULL_CHUNK_ID, body(4000, 2));
        long tightBudget = chunkA.length + 200; // 只容得下一个
        byte[] in = concat(chunkA, chunkB, chunkA, chunkB);
        assertArrayEquals(in, roundTrip(in, TABLE, tightBudget, 1 << 16, 256));
    }

    @Test
    void nonCrcStreamPassesThroughVerbatim() throws IOException {
        // 流首不是 ZNCR（例如服务端没启用 CRC / 走了实体变换）→ 客户端逆向流原样透传。
        byte[] raw = concat(frame(0x50, body(100, 1)), frame(0x24, body(100, 2)));
        byte[] out = decode(raw, BUDGET, 8);
        assertArrayEquals(raw, out);
    }

    @Test
    void corruptBlockTypeFailsClosed() throws IOException {
        byte[] wire = encode(frame(FULL_CHUNK_ID, body(500, 1)), TABLE, BUDGET, 1 << 16);
        wire[ChunkCacheFormat.PREAMBLE_LENGTH] = (byte) 0x7F; // 把第一个 block 类型改成非法
        assertThrows(IOException.class, () -> decode(wire, BUDGET, 64));
    }

    @Test
    void truncatedStreamFailsClosed() throws IOException {
        byte[] wire = encode(frame(FULL_CHUNK_ID, body(500, 1)), TABLE, BUDGET, 1 << 16);
        byte[] cut = new byte[wire.length - 3]; // 砍掉末尾 → 半个 block
        System.arraycopy(wire, 0, cut, 0, cut.length);
        assertThrows(IOException.class, () -> decode(cut, BUDGET, 64));
    }

    @Test
    void refToMissingBaselineFailsClosed() throws IOException {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        ChunkCacheCodec.writePreamble(bad);
        ChunkCacheCodec.writeRef(bad, 0x0123456789ABCDEFL); // 从未 FULL 过的哈希
        assertThrows(IOException.class, () -> decode(bad.toByteArray(), BUDGET, 64));
    }

    // ---- helpers ----

    private static byte[] roundTrip(byte[] in, CacheablePacketTable table, long budget, int writeChunk, int readChunk)
        throws IOException {
        return decode(encode(in, table, budget, writeChunk), budget, readChunk);
    }

    private static byte[] encode(byte[] in, CacheablePacketTable table, long budget, int writeChunk) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CacheTransformingOutputStream cos = new CacheTransformingOutputStream(encoded, table, budget)) {
            for (int i = 0; i < in.length; i += writeChunk) {
                int len = Math.min(writeChunk, in.length - i);
                cos.write(in, i, len);
                cos.flush();
            }
        }
        return encoded.toByteArray();
    }

    private static byte[] decode(byte[] wire, long budget, int readChunk) throws IOException {
        CacheUntransformingInputStream cis = new CacheUntransformingInputStream(new ByteArrayInputStream(wire), budget);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] rb = new byte[readChunk];
        int n;
        while ((n = cis.read(rb, 0, readChunk)) >= 0) {
            decoded.write(rb, 0, n);
        }
        return decoded.toByteArray();
    }

    private static boolean startsWithPreamble(byte[] wire) {
        if (wire.length < ChunkCacheFormat.PREAMBLE_LENGTH) {
            return false;
        }
        for (int i = 0; i < ChunkCacheFormat.PREAMBLE_MAGIC.length; i++) {
            if (wire[i] != ChunkCacheFormat.PREAMBLE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

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
