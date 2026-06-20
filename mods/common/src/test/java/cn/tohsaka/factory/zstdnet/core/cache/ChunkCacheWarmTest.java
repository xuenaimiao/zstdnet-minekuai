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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2 跨会话 WARM_REF + 自适应旁路：往返字节一致、版本门控、fail-closed、旁路下仍逐字节还原。
 */
class ChunkCacheWarmTest {

    private static final int FULL_CHUNK_ID = 0x24; // 1.20.1
    private static final CacheablePacketTable TABLE = CacheablePacketTable.forProtocol(763);
    private static final long BUDGET = 1L << 20;

    @Test
    void warmRefRoundTripsAndShrinks() throws IOException {
        byte[] chunk = frame(FULL_CHUNK_ID, body(8000, 5));
        Hash128 h = Hashing.content128(chunk);
        Set<Hash128> warm = Set.of(h);
        Map<Hash128, byte[]> warmMap = Map.of(h, chunk);
        byte[] in = concat(chunk, chunk); // 客户端已持有 → 两次都 WARM_REF（首次就不传整块）

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos =
            new CacheTransformingOutputStream(encoded, TABLE, BUDGET, warm, ChunkCacheFormat.VERSION_MANIFEST, 0);
        try (cos) {
            cos.write(in, 0, in.length);
            cos.flush();
        }
        assertEquals(2, cos.warmRefBlocks, "both occurrences should be WARM_REF");
        assertEquals(0, cos.fullBlocks, "warm chunk never needs FULL");

        byte[] decoded = decodeWarm(encoded.toByteArray(), BUDGET, warmMap);
        assertArrayEquals(in, decoded);
        assertTrue(encoded.size() < chunk.length, "two WARM_REF tokens are tiny vs one chunk");
    }

    @Test
    void warmRefToMissingPersistedBaselineFailsClosed() throws IOException {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        ChunkCacheCodec.writePreamble(bad, ChunkCacheFormat.VERSION_MANIFEST);
        ChunkCacheCodec.writeWarmRef(bad, new Hash128(0xDEADL, 0xBEEFL));
        // 客户端 warm 快照里没有这个 hash → fail-closed。
        assertThrows(IOException.class, () -> decodeWarm(bad.toByteArray(), BUDGET, Map.of()));
    }

    @Test
    void warmDisabledBelowV2() throws IOException {
        byte[] chunk = frame(FULL_CHUNK_ID, body(2000, 3));
        Hash128 h = Hashing.content128(chunk);
        Set<Hash128> warm = Set.of(h);
        byte[] in = concat(chunk, chunk);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos =
            new CacheTransformingOutputStream(encoded, TABLE, BUDGET, warm, ChunkCacheFormat.VERSION_REF, 0);
        try (cos) {
            cos.write(in, 0, in.length);
            cos.flush();
        }
        assertEquals(0, cos.warmRefBlocks, "v1 must not emit WARM_REF even if warm set has it");
        assertEquals(1, cos.fullBlocks);
        assertEquals(1, cos.refBlocks); // 退化为会话内 REF
        assertArrayEquals(in, decodeWarm(encoded.toByteArray(), BUDGET, Map.of()));
    }

    @Test
    void adaptiveBypassStaysByteIdenticalAndEngages() throws IOException {
        int window = 4;
        ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
        for (int i = 0; i < 30; i++) {
            inBuf.writeBytes(frame(FULL_CHUNK_ID, body(2000, 100 + i))); // 30 个互异区块（全新）
        }
        byte[] in = inBuf.toByteArray();

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos =
            new CacheTransformingOutputStream(encoded, TABLE, BUDGET, Set.of(), ChunkCacheFormat.VERSION_MANIFEST, window);
        try (cos) {
            cos.write(in, 0, in.length);
            cos.flush();
        }
        assertArrayEquals(in, decodeWarm(encoded.toByteArray(), BUDGET, Map.of()), "bypass must stay byte-identical");
        assertTrue(cos.bypassedFrames > 0, "bypass should engage on all-new terrain");
        assertTrue(cos.fullBlocks < 30, "bypass should avoid FULL'ing most never-repeated chunks; full=" + cos.fullBlocks);
    }

    @Test
    void bypassDisabledByDefaultFullsEveryNewChunk() throws IOException {
        ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            inBuf.writeBytes(frame(FULL_CHUNK_ID, body(1000, 200 + i)));
        }
        byte[] in = inBuf.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos =
            new CacheTransformingOutputStream(encoded, TABLE, BUDGET, Set.of(), ChunkCacheFormat.VERSION_MANIFEST, 0);
        try (cos) {
            cos.write(in, 0, in.length);
            cos.flush();
        }
        assertEquals(0, cos.bypassedFrames);
        assertEquals(10, cos.fullBlocks);
        assertArrayEquals(in, decodeWarm(encoded.toByteArray(), BUDGET, Map.of()));
    }

    // ---- helpers ----

    private static byte[] decodeWarm(byte[] wire, long budget, Map<Hash128, byte[]> warm) throws IOException {
        CacheUntransformingInputStream cis =
            new CacheUntransformingInputStream(new ByteArrayInputStream(wire), budget, warm, null);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] rb = new byte[64];
        int n;
        while ((n = cis.read(rb, 0, rb.length)) >= 0) {
            decoded.write(rb, 0, n);
        }
        return decoded.toByteArray();
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
