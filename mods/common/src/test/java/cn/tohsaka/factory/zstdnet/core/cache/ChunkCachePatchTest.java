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
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3 结构无关 PATCH 的端到端契约：同坐标小改动发增量、版本门控、不划算/异坐标退回 FULL、链预算重锚、
 * 损坏/缺基线 fail-closed、与 REF/WARM/FULL 混流仍 lock-step 逐字节还原。
 */
class ChunkCachePatchTest {

    private static final int CHUNK_ID = 0x24; // 1.20.1 full-chunk
    private static final int PROTO = 763;
    private static final CacheablePacketTable TABLE = CacheablePacketTable.forProtocol(PROTO);
    private static final long BUDGET = 1L << 20;
    private static final int V3 = ChunkCacheFormat.VERSION_PATCH;

    @Test
    void smallEditAtSamePositionEmitsPatchAndRoundTrips() throws IOException {
        byte[] base = chunkFrame(5, 7, data(8000, 1));
        byte[] mod = chunkFrame(5, 7, smallEdit(data(8000, 1)));
        byte[] in = concat(base, mod);

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);

        assertEquals(1, e.cos.fullBlocks, "base chunk is FULL");
        assertEquals(1, e.cos.patchBlocks, "modified chunk at same position is PATCH");
        assertEquals(0, e.cos.refBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
        assertTrue(wire.length < base.length + mod.length / 2,
            "PATCH must be far smaller than re-sending the whole modified chunk; wire=" + wire.length);
    }

    @Test
    void patchDisabledBelowV3FallsBackToFull() throws IOException {
        byte[] base = chunkFrame(5, 7, data(8000, 2));
        byte[] mod = chunkFrame(5, 7, smallEdit(data(8000, 2)));
        byte[] in = concat(base, mod);

        // v2 协商：不应发 PATCH（旧客户端解不了 0x05）。
        Enc e = new Enc(BUDGET, Set.of(), ChunkCacheFormat.VERSION_MANIFEST, 0, true);
        byte[] wire = e.run(in);

        assertEquals(0, e.cos.patchBlocks, "v2 must never emit PATCH");
        assertEquals(2, e.cos.fullBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    @Test
    void patchEnabledFlagFalseFallsBackToFull() throws IOException {
        byte[] base = chunkFrame(5, 7, data(8000, 3));
        byte[] mod = chunkFrame(5, 7, smallEdit(data(8000, 3)));
        byte[] in = concat(base, mod);

        // 生效版本 v3 但 patchEnabled=false（对应 mode=ref）。
        Enc e = new Enc(BUDGET, Set.of(), V3, 0, false);
        byte[] wire = e.run(in);

        assertEquals(0, e.cos.patchBlocks);
        assertEquals(2, e.cos.fullBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    @Test
    void completelyDifferentChunkAtSamePositionStaysFull() throws IOException {
        byte[] base = chunkFrame(5, 7, data(8000, 4));
        byte[] mod = chunkFrame(5, 7, data(8000, 999)); // 全然不同 → 增量 ≈ 全帧 → 不划算
        byte[] in = concat(base, mod);

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);

        assertEquals(0, e.cos.patchBlocks, "unrelated chunk must not PATCH (ratio guard)");
        assertEquals(2, e.cos.fullBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    @Test
    void differentPositionsDoNotPatch() throws IOException {
        byte[] a = chunkFrame(1, 1, data(8000, 5));
        byte[] b = chunkFrame(2, 2, smallEdit(data(8000, 5))); // 内容相似，但坐标不同 → 不跨坐标 PATCH
        byte[] in = concat(a, b);

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);

        assertEquals(0, e.cos.patchBlocks);
        assertEquals(2, e.cos.fullBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    @Test
    void patchToMissingBaseFailsClosed() throws IOException {
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        ChunkCacheCodec.writePreamble(bad, V3);
        // 基线 0x1234 不在客户端会话内缓存 → 解码端 peek miss → fail-closed（delta 是什么不影响）。
        ChunkCacheCodec.writePatch(bad, 0x1234L, 0x5678L, new byte[]{0x02, 0x01, 0x41});
        assertThrows(IOException.class, () -> decode(bad.toByteArray(), BUDGET, Map.of()));
    }

    @Test
    void corruptedPatchDeltaFailsClosed() throws IOException {
        byte[] base = chunkFrame(5, 7, data(8000, 6));
        byte[] mod = chunkFrame(5, 7, smallEdit(data(8000, 6)));
        byte[] in = concat(base, mod);

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);
        assertEquals(1, e.cos.patchBlocks);

        // 翻转 PATCH 区（流尾）的一个字节 → 重建结果与 newHash 不符 → fail-closed。
        byte[] corrupt = wire.clone();
        corrupt[corrupt.length - 1] ^= 0xFF;
        assertThrows(IOException.class, () -> decode(corrupt, BUDGET, Map.of()));
    }

    @Test
    void patchChainReanchorsAfterBudget() throws IOException {
        int n = ChunkCacheFormat.DEFAULT_PATCH_CHAIN_BUDGET + 6; // 70
        ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
        byte[] cur = data(8000, 7);
        inBuf.writeBytes(chunkFrame(9, 9, cur));
        for (int k = 1; k < n; k++) {
            cur = nudge(cur, k); // 与上一帧仅差一小段
            inBuf.writeBytes(chunkFrame(9, 9, cur));
        }
        byte[] in = inBuf.toByteArray();

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);

        // 1 个起始 FULL + 64 个 PATCH 后强制重锚 FULL，再继续 PATCH。
        assertEquals(2, e.cos.fullBlocks, "chain budget should force exactly one re-anchor FULL");
        assertEquals(n - 2, e.cos.patchBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    @Test
    void mixedRefWarmPatchFullStayByteIdentical() throws IOException {
        byte[] dataA = data(8000, 8);
        byte[] a = chunkFrame(1, 1, dataA);
        byte[] aAgain = chunkFrame(1, 1, dataA);           // REF（精确重复）
        byte[] aEdit = chunkFrame(1, 1, smallEdit(dataA));  // PATCH（同坐标小改）
        byte[] w = chunkFrame(2, 2, data(8000, 88));        // WARM_REF（manifest 声明持有）
        byte[] b = chunkFrame(3, 3, data(8000, 888));       // FULL（新坐标）
        byte[] in = concat(a, aAgain, aEdit, w, b);

        Hash128 wh = Hashing.content128(w);
        Set<Hash128> warm = Set.of(wh);
        Map<Hash128, byte[]> warmMap = Map.of(wh, w);

        Enc e = new Enc(BUDGET, warm, V3, 0, true);
        byte[] wire = e.run(in);

        assertEquals(2, e.cos.fullBlocks, "a + b");
        assertEquals(1, e.cos.refBlocks, "aAgain");
        assertEquals(1, e.cos.patchBlocks, "aEdit");
        assertEquals(1, e.cos.warmRefBlocks, "w");
        assertArrayEquals(in, decode(wire, BUDGET, warmMap));
    }

    @Test
    void chainedPatchResultIsValidBaseForNextPatch() throws IOException {
        // 链式 PATCH：第二个 PATCH 的基线是第一个 PATCH 的“重建结果”，验证 PATCH 结果确实进了会话内 LRU。
        byte[] f0 = chunkFrame(4, 4, data(8000, 9));
        byte[] f1 = chunkFrame(4, 4, smallEditAt(data(8000, 9), 1000));
        byte[] f2 = chunkFrame(4, 4, smallEditAt(smallEditAt(data(8000, 9), 1000), 2000));
        byte[] in = concat(f0, f1, f2);

        Enc e = new Enc(BUDGET, Set.of(), V3, 0, true);
        byte[] wire = e.run(in);

        assertEquals(1, e.cos.fullBlocks);
        assertEquals(2, e.cos.patchBlocks);
        assertArrayEquals(in, decode(wire, BUDGET, Map.of()));
    }

    // ---- helpers ----

    /** 绑定一个 ByteArrayOutputStream 的编码器，便于在测试里既拿计数又拿线上字节。 */
    private static final class Enc {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final CacheTransformingOutputStream cos;

        Enc(long budget, Set<Hash128> warm, int version, int bypassWindow, boolean patch) {
            cos = new CacheTransformingOutputStream(out, TABLE, budget, warm, version, bypassWindow, patch, PROTO);
        }

        byte[] run(byte[] in) throws IOException {
            cos.write(in, 0, in.length);
            cos.flush();
            cos.close();
            return out.toByteArray();
        }
    }

    private static byte[] decode(byte[] wire, long budget, Map<Hash128, byte[]> warm) throws IOException {
        CacheUntransformingInputStream cis =
            new CacheUntransformingInputStream(new ByteArrayInputStream(wire), budget, warm, null);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] rb = new byte[57]; // 奇数读块，覆盖任意读边界
        int n;
        while ((n = cis.read(rb, 0, rb.length)) >= 0) {
            decoded.write(rb, 0, n);
        }
        return decoded.toByteArray();
    }

    private static byte[] chunkFrame(int x, int z, byte[] chunkData) {
        byte[] body = concat(int32(x), int32(z), chunkData);
        byte[] dl = VarIntCodec.encode(0);
        byte[] pid = VarIntCodec.encode(CHUNK_ID);
        byte[] payload = concat(dl, pid, body);
        byte[] len = VarIntCodec.encode(payload.length);
        return concat(len, payload);
    }

    /** 确定性（同 seed 恒等）但跨 seed 互不相关的伪随机字节——避免线性生成器导致不同 seed 间出现可整段 COPY 的位移等价。 */
    private static byte[] data(int size, int seed) {
        byte[] b = new byte[size];
        new Random(0x9E3779B97F4A7C15L * (seed + 1)).nextBytes(b);
        return b;
    }

    /** 翻转中段 16 字节（模拟“放置一个方块”）。 */
    private static byte[] smallEdit(byte[] src) {
        return smallEditAt(src, src.length / 2);
    }

    private static byte[] smallEditAt(byte[] src, int at) {
        byte[] b = src.clone();
        for (int i = at; i < Math.min(at + 16, b.length); i++) {
            b[i] ^= 0xFF;
        }
        return b;
    }

    /** 与上一帧仅差一小段（随 k 滚动位置），保证链上每对相邻帧的增量都很小。 */
    private static byte[] nudge(byte[] src, int k) {
        byte[] b = src.clone();
        int at = (k * 37) % (b.length - 16);
        for (int i = at; i < at + 16; i++) {
            b[i] ^= (byte) (k + 1);
        }
        return b;
    }

    private static byte[] int32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }
}
