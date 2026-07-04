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
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer B（B1 移动 SoA + B2 实体定向）的核心保证：
 * <ul>
 *   <li><b>正确性</b>：往返逐字节精确——且<b>不依赖 packet 表正确</b>（误分类、长度不符均仍精确可逆）；
 *   <li><b>收益</b>：去交错后，匀速/相似实体场景下经 ZSTD 连续帧压缩显著小于 Layer A。
 * </ul>
 * 解码端永不使用表，故所有往返测试只构造编码端合成表。
 */
class StreamTransformLayerBTest {

    // 合成测试协议（与生产 760/763/767 无关，仅供编码端分类）。
    private static final int PID_MOVE_POS = 0x2B;        // [2,2,2,1]
    private static final int PID_MOVE_POS_ROT = 0x2C;    // [2,2,2,1,1,1]
    private static final int PID_METADATA = 0x52;        // B2 变长
    private static final int PID_EQUIPMENT = 0x55;       // B2 变长
    private static final int PID_CHUNK = 0x24;           // 非实体包 → RAW

    private static EntityPacketTable testTable() {
        return EntityPacketTable.builder()
            .move(PID_MOVE_POS, EntityPacketTable.LAYOUT_MOVE_POS)
            .move(PID_MOVE_POS_ROT, EntityPacketTable.LAYOUT_MOVE_POS_ROT)
            .entityLeading(PID_METADATA)
            .entityLeading(PID_EQUIPMENT)
            .build();
    }

    private static final int[] WRITE_CHUNKS = {1, 3, 7, 64, 16 * 1024};
    private static final int[] READ_CHUNKS = {1, 5, 64, 16 * 1024};

    @Test
    void layerBRoundTripExactMixedClasses() throws Exception {
        byte[] raw = mixedScene(new Random(1L));
        for (int version : new int[]{TransformFormat.VERSION_B1, TransformFormat.VERSION_B2}) {
            for (int writeChunk : WRITE_CHUNKS) {
                byte[] transformed = transformB(raw, writeChunk, version, testTable());
                assertTrue(startsWithPreamble(transformed), "should begin with PREAMBLE (version " + version + ")");
                for (int upstreamChunk : READ_CHUNKS) {
                    for (int readBuf : READ_CHUNKS) {
                        byte[] restored = untransform(transformed, upstreamChunk, readBuf);
                        assertArrayEquals(raw, restored,
                            "round-trip mismatch v=" + version + " w=" + writeChunk + " up=" + upstreamChunk + " rb=" + readBuf);
                    }
                }
            }
        }
    }

    @Test
    void fullPipelineLayerBWithZstd() throws Exception {
        byte[] raw = mixedScene(new Random(2L));
        for (int version : new int[]{TransformFormat.VERSION_B1, TransformFormat.VERSION_B2}) {
            for (int writeChunk : new int[]{1, 7, 16 * 1024}) {
                byte[] compressed = transformThenCompress(raw, writeChunk, version, testTable());
                byte[] restored = decompressThenUntransform(compressed, 7);
                assertArrayEquals(raw, restored, "zstd pipeline mismatch v=" + version + " w=" + writeChunk);
            }
        }
    }

    @Test
    void misclassificationIsStillByteExact() throws Exception {
        // 把一个"非实体包" id 强行登记为实体定向（B2）和移动（B1），即使语义不对也必须字节精确还原。
        EntityPacketTable wrong = EntityPacketTable.builder()
            .entityLeading(PID_CHUNK)                              // 误把 chunk 当 B2
            .move(0x77, EntityPacketTable.LAYOUT_MOVE_POS)        // 任意 id 强行 B1（长度多半不符 → 回退 RAW）
            .build();
        Random rnd = new Random(3L);
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            frames.add(playFrame(PID_CHUNK, 1000 + i, randomBytes(rnd, 20 + (i % 17))));
            frames.add(playFrame(0x77, 5, randomBytes(rnd, 7)));   // 恰好匹配 LAYOUT_MOVE_POS 长度 → 走 B1
            frames.add(playFrame(0x77, 5, randomBytes(rnd, 9)));   // 长度不符 → 回退 RAW
        }
        byte[] raw = concat(frames);
        for (int version : new int[]{TransformFormat.VERSION_B1, TransformFormat.VERSION_B2}) {
            for (int writeChunk : WRITE_CHUNKS) {
                byte[] restored = untransform(transformB(raw, writeChunk, version, wrong), 5, 64);
                assertArrayEquals(raw, restored, "misclassified round-trip mismatch v=" + version + " w=" + writeChunk);
            }
        }
    }

    @Test
    void b1ImprovesCompressionForContraption() throws Exception {
        // 机械动力契约体：大量方块作为刚体一起动 → 同一 tick 内所有实体 delta 相同；delta 逐 tick 漂移。
        // entityId 列稳定且混宽、onGround 稳定。去交错后同一 tick 的 delta 列成为可压连续段。
        byte[] raw = contraptionScene(64, 40, new Random(4L));
        long zstdRaw = compressedSize(raw);
        long zstdA = compressedSize(transform(raw, 16 * 1024, TransformFormat.VERSION_LAYER_A, null));
        long zstdB1 = compressedSize(transform(raw, 16 * 1024, TransformFormat.VERSION_B1, movingTable()));
        System.out.println("[B1] raw-zstd=" + zstdRaw + " A=" + zstdA + " B1=" + zstdB1
            + " (B1/A=" + (zstdB1 * 100 / zstdA) + "%)");
        assertTrue(zstdB1 < zstdA * 0.85, "B1 should be >=15% smaller than Layer A: B1=" + zstdB1 + " A=" + zstdA);
    }

    @Test
    void b2ImprovesCompressionForSimilarMobs() throws Exception {
        // 大量相似生物的元数据：entityId 递增（混宽），载荷近似相同。
        byte[] raw = similarMobsScene(80, 10, new Random(5L));
        long zstdA = compressedSize(transform(raw, 16 * 1024, TransformFormat.VERSION_LAYER_A, null));
        long zstdB2 = compressedSize(transform(raw, 16 * 1024, TransformFormat.VERSION_B2, similarMobsTable()));
        System.out.println("[B2] A=" + zstdA + " B2=" + zstdB2);
        assertTrue(zstdB2 < zstdA * 0.8, "B2 should be >=20% smaller than Layer A: B2=" + zstdB2 + " A=" + zstdA);
    }

    // ---- 场景构造 ----

    private static EntityPacketTable movingTable() {
        return EntityPacketTable.builder()
            .move(PID_MOVE_POS, EntityPacketTable.LAYOUT_MOVE_POS)
            .move(PID_MOVE_POS_ROT, EntityPacketTable.LAYOUT_MOVE_POS_ROT)
            .build();
    }

    private static EntityPacketTable similarMobsTable() {
        return EntityPacketTable.builder().entityLeading(PID_METADATA).build();
    }

    /** 混合：若干 move(B1)、metadata/equipment(B2)、chunk(RAW)、空帧、bundle 分隔(0x00 空载荷)。 */
    private static byte[] mixedScene(Random rnd) {
        List<byte[]> frames = new ArrayList<>();
        for (int tick = 0; tick < 8; tick++) {
            for (int e = 0; e < 12; e++) {
                int eid = 100 + e * 13;  // 跨 1/2 字节 varint 边界
                frames.add(playFrame(PID_MOVE_POS, eid, randomBytes(rnd, 7)));
                if (e % 3 == 0) {
                    frames.add(playFrame(PID_MOVE_POS_ROT, eid, randomBytes(rnd, 9)));
                }
                if (e % 4 == 0) {
                    frames.add(playFrame(PID_METADATA, eid, randomBytes(rnd, 5 + (e % 11))));
                }
                if (e % 5 == 0) {
                    frames.add(playFrame(PID_EQUIPMENT, eid, randomBytes(rnd, 6)));
                }
            }
            frames.add(playFrame(PID_CHUNK, 0, randomBytes(rnd, 200)));   // 非实体大包
            frames.add(new byte[]{0});                                    // 空载荷帧（如 bundle 分隔）
        }
        return concat(frames);
    }

    private static byte[] contraptionScene(int entities, int ticks, Random rnd) {
        int[] base = new int[entities];
        for (int e = 0; e < entities; e++) {
            base[e] = 100 + e * 7;   // 100..~540：混 1/2 字节 varint
        }
        List<byte[]> frames = new ArrayList<>();
        short dx = 0, dy = 0, dz = 0;
        for (int t = 0; t < ticks; t++) {
            // 整体 delta 逐 tick 小幅漂移（刚体），同一 tick 内所有实体共享。
            dx += (short) (rnd.nextInt(7) - 3);
            dy += (short) (rnd.nextInt(3) - 1);
            dz += (short) (rnd.nextInt(7) - 3);
            for (int e = 0; e < entities; e++) {
                byte[] rest = new byte[7];          // dx,dy,dz(short) + onGround
                putShort(rest, 0, dx);
                putShort(rest, 2, dy);
                putShort(rest, 4, dz);
                rest[6] = 0;                          // onGround 恒为 0（稳定列）
                frames.add(playFrame(PID_MOVE_POS, base[e], rest));
            }
        }
        return concat(frames);
    }

    private static byte[] similarMobsScene(int mobs, int ticks, Random rnd) {
        // 同种怪近似元数据：一份模板，少量字节随 mob 微扰；entityId 递增混宽。
        byte[] template = randomBytes(rnd, 24);
        List<byte[]> frames = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            for (int m = 0; m < mobs; m++) {
                byte[] meta = template.clone();
                meta[3] = (byte) (m & 0x0F);          // 仅个别字节不同
                meta[10] = (byte) (t & 0x07);
                frames.add(playFrame(PID_METADATA, 200 + m, meta));
            }
        }
        return concat(frames);
    }

    // ---- 帧构造 ----

    /** 构造一个开启原版压缩(threshold 极高)时的 play 帧：[packetLength][dataLength=0][packetId][entityId][rest]。 */
    private static byte[] playFrame(int packetId, int entityId, byte[] rest) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] dataLen = VarIntCodec.encode(0);              // dataLength=0（未压缩）
        payload.write(dataLen, 0, dataLen.length);
        byte[] pid = VarIntCodec.encode(packetId);
        payload.write(pid, 0, pid.length);
        byte[] eid = VarIntCodec.encode(entityId);
        payload.write(eid, 0, eid.length);
        payload.write(rest, 0, rest.length);
        byte[] p = payload.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        byte[] frameLen = VarIntCodec.encode(p.length);
        frame.write(frameLen, 0, frameLen.length);
        frame.write(p, 0, p.length);
        return frame.toByteArray();
    }

    private static byte[] randomBytes(Random rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        return b;
    }

    private static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
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

    // ---- 变换/压缩管线 ----

    private static byte[] transformB(byte[] raw, int writeChunk, int version, EntityPacketTable table) throws IOException {
        return transform(raw, writeChunk, version, table);
    }

    private static byte[] transform(byte[] raw, int writeChunk, int version, EntityPacketTable table) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (TransformingOutputStream t = new TransformingOutputStream(sink, version, table)) {
            for (int i = 0; i < raw.length; i += writeChunk) {
                int n = Math.min(writeChunk, raw.length - i);
                t.write(raw, i, n);
                t.flush();
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

    private static byte[] transformThenCompress(byte[] raw, int writeChunk, int version, EntityPacketTable table) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        OutputStream zstd = ZstdStreams.newCompressor(wire, 9, CompressionOptions.none(), false);
        try (TransformingOutputStream t = new TransformingOutputStream(zstd, version, table)) {
            for (int i = 0; i < raw.length; i += writeChunk) {
                int n = Math.min(writeChunk, raw.length - i);
                t.write(raw, i, n);
                t.flush();
            }
        }
        return wire.toByteArray();
    }

    private static byte[] decompressThenUntransform(byte[] compressed, int readBuf) throws IOException {
        InputStream zin = ZstdStreams.newDecompressor(new ByteArrayInputStream(compressed), CompressionOptions.none(), null);
        try (UntransformingInputStream u = new UntransformingInputStream(zin)) {
            return readAll(u, readBuf);
        }
    }

    /** 把已变换字节经 ZSTD 连续帧压缩后的字节数（模拟下行 flush-per-tick 的逐 block 压缩）。 */
    private static long compressedSize(byte[] data) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (OutputStream zstd = ZstdStreams.newCompressor(wire, 9, CompressionOptions.none(), false)) {
            zstd.write(data);
        }
        return wire.size();
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
