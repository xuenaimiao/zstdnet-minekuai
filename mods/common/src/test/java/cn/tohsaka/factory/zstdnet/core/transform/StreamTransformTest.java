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

package cn.tohsaka.factory.zstdnet.core.transform;

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdStreams;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer A 变换的核心保证：往返逐字节精确（含空/单/多/大帧）、与 ZSTD 组合往返、任意分块边界、
 * MAGIC 自检 passthrough、损坏 fail-closed。
 */
class StreamTransformTest {

    private static final int[] WRITE_CHUNKS = {1, 2, 3, 7, 64, 16 * 1024, 1 << 20};
    private static final int[] READ_CHUNKS = {1, 2, 5, 64, 16 * 1024};

    @Test
    void layerARoundTripVariousSizesAndChunkings() throws Exception {
        byte[] raw = sampleFrames();
        for (int writeChunk : WRITE_CHUNKS) {
            byte[] transformed = transform(raw, writeChunk);
            // 变换确实发生：以 PREAMBLE 开头，且与原文不同。
            assertTrue(startsWithPreamble(transformed), "transformed stream should begin with PREAMBLE");
            for (int upstreamChunk : READ_CHUNKS) {
                for (int readBuf : READ_CHUNKS) {
                    byte[] restored = untransform(transformed, upstreamChunk, readBuf);
                    assertArrayEquals(raw, restored,
                        "round-trip mismatch writeChunk=" + writeChunk + " upstream=" + upstreamChunk + " readBuf=" + readBuf);
                }
            }
        }
    }

    @Test
    void emptyAndDegenerateFrames() throws Exception {
        // 空流
        assertArrayEquals(new byte[0], untransform(transform(new byte[0], 8), 8, 8));
        // 单个空帧（仅一个长度前缀 0）
        byte[] oneEmpty = buildFrames(Arrays.asList(new byte[0]));
        assertArrayEquals(oneEmpty, untransform(transform(oneEmpty, 1), 1, 1));
        // 多个空帧
        byte[] manyEmpty = buildFrames(Arrays.asList(new byte[0], new byte[0], new byte[0]));
        assertArrayEquals(manyEmpty, untransform(transform(manyEmpty, 2), 3, 1));
    }

    @Test
    void fullPipelineWithZstdRoundTrip() throws Exception {
        byte[] raw = sampleFrames();
        for (int writeChunk : new int[]{1, 7, 16 * 1024}) {
            byte[] compressed = transformThenCompress(raw, writeChunk);
            byte[] restored = decompressThenUntransform(compressed, 7);
            assertArrayEquals(raw, restored, "zstd pipeline mismatch writeChunk=" + writeChunk);
        }
    }

    @Test
    void passthroughIsByteIdenticalWhenNotTransformed() throws Exception {
        // 不以 PREAMBLE 开头的流应原样透传（对端未启用/未升级变换时仍正常）。
        byte[] rawMcFrames = sampleFrames();
        assertTrue(rawMcFrames[0] != TransformFormat.PREAMBLE_MAGIC[0], "fixture must not start with magic");
        for (int upstreamChunk : READ_CHUNKS) {
            for (int readBuf : READ_CHUNKS) {
                assertArrayEquals(rawMcFrames, untransform(rawMcFrames, upstreamChunk, readBuf));
            }
        }
        // 极短流（< PREAMBLE 长度）也按原样透传。
        byte[] tiny = {1, 2, 3};
        assertArrayEquals(tiny, untransform(tiny, 1, 1));
        // 前缀部分像魔数但随后不符，也应原样透传。
        byte[] nearMiss = {0x5A, 0x4E, 0x54, 0x00, 9, 9, 9};
        assertArrayEquals(nearMiss, untransform(nearMiss, 2, 3));
    }

    @Test
    void corruptBlockFailsClosed() {
        // PREAMBLE + 非法 block 类型 → 进入变换模式后 fail-closed 抛异常。
        byte[] bad = new byte[TransformFormat.PREAMBLE_LENGTH + 1];
        System.arraycopy(TransformFormat.PREAMBLE_MAGIC, 0, bad, 0, TransformFormat.PREAMBLE_MAGIC.length);
        bad[TransformFormat.PREAMBLE_MAGIC.length] = (byte) TransformFormat.VERSION_LAYER_A;
        bad[TransformFormat.PREAMBLE_LENGTH] = (byte) 0x55; // 未知块类型
        assertThrows(IOException.class, () -> untransform(bad, 4, 4));
    }

    // ---- helpers ----

    /** 一批多样化的 MC 帧：空帧、小帧、多字节长度前缀的中帧、跨多次读的大帧。 */
    private static byte[] sampleFrames() {
        Random rnd = new Random(20260615L);
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(new byte[0]);                 // 空
        payloads.add(payload(rnd, 1));
        payloads.add(payload(rnd, 5));
        payloads.add(payload(rnd, 12));            // 类实体移动小帧
        payloads.add(payload(rnd, 300));           // 长度需 2 字节 varint
        payloads.add(payload(rnd, 1));
        payloads.add(payload(rnd, 70_000));        // 大帧，跨多次 16KB 读
        payloads.add(payload(rnd, 7));
        // 故意保证首字节不等于 PREAMBLE 第一个字节（用一个长度 != 0x5A 的帧打头由 buildFrames 保证）。
        return buildFrames(payloads);
    }

    private static byte[] payload(Random rnd, int len) {
        byte[] p = new byte[len];
        rnd.nextBytes(p);
        return p;
    }

    private static byte[] buildFrames(List<byte[]> payloads) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : payloads) {
            byte[] lenPrefix = VarIntCodec.encode(p.length);
            out.write(lenPrefix, 0, lenPrefix.length);
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static boolean startsWithPreamble(byte[] data) {
        if (data.length < TransformFormat.PREAMBLE_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < TransformFormat.PREAMBLE_MAGIC.length; i++) {
            if (data[i] != TransformFormat.PREAMBLE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] transform(byte[] raw, int writeChunk) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (TransformingOutputStream t = new TransformingOutputStream(sink, TransformFormat.VERSION_LAYER_A)) {
            for (int i = 0; i < raw.length; i += writeChunk) {
                int n = Math.min(writeChunk, raw.length - i);
                t.write(raw, i, n);
                t.flush(); // 每块后 flush：压力测试不足一帧的尾部保留
            }
        }
        return sink.toByteArray();
    }

    private static byte[] untransform(byte[] transformed, int upstreamChunk, int readBuf) throws IOException {
        InputStream src = new ChunkLimitedInputStream(new ByteArrayInputStream(transformed), upstreamChunk);
        try (UntransformingInputStream u = new UntransformingInputStream(src)) {
            return readAll(u, readBuf);
        }
    }

    private static byte[] transformThenCompress(byte[] raw, int writeChunk) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        OutputStream zstd = ZstdStreams.newCompressor(wire, 9, CompressionOptions.none(), false);
        try (TransformingOutputStream t = new TransformingOutputStream(zstd, TransformFormat.VERSION_LAYER_A)) {
            for (int i = 0; i < raw.length; i += writeChunk) {
                int n = Math.min(writeChunk, raw.length - i);
                t.write(raw, i, n);
                t.flush();
            }
        } // 关闭 t 会链式关闭 zstd，落定 ZSTD 帧
        return wire.toByteArray();
    }

    private static byte[] decompressThenUntransform(byte[] compressed, int readBuf) throws IOException {
        InputStream zin = ZstdStreams.newDecompressor(new ByteArrayInputStream(compressed), CompressionOptions.none(), null);
        try (UntransformingInputStream u = new UntransformingInputStream(zin)) {
            return readAll(u, readBuf);
        }
    }

    private static byte[] readAll(InputStream in, int bufSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[Math.max(1, bufSize)];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** 限制每次 read 返回的字节数，模拟分片的上游（ZSTD 解压块/TCP 分段）。 */
    private static final class ChunkLimitedInputStream extends InputStream {
        private final InputStream in;
        private final int max;

        ChunkLimitedInputStream(InputStream in, int max) {
            this.in = in;
            this.max = Math.max(1, max);
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, Math.min(len, max));
        }
    }
}
