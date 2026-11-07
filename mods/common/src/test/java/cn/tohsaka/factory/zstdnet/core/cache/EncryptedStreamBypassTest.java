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

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;
import cn.tohsaka.factory.zstdnet.core.transform.TransformingOutputStream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：下行流在登录握手后变成 AES 密文（原版 online-mode，或 AutoModpack 5.x 的 HOLEPUNCH 在同端口上自己做一遍
 * 「加密请求/响应」）时，CRC / 实体变换层不得把密文当成「永远收不齐的大帧」憋在缓冲里。
 *
 * <p>现象：AutoModpack 客户端经 ZstdNet 本地代理连上服务端后，服务端 AMP 已接管连接并开始等客户端的 TLS ClientHello，
 * 但服务端→客户端那几个密文小包被帧缓冲扣着没发，双方互等直到 10 秒超时（服务端
 * {@code SslHandshakeTimeoutException}，客户端 {@code Couldn't kickstart handshaking}）。
 */
class EncryptedStreamBypassTest {

    private static final CacheablePacketTable TABLE = CacheablePacketTable.forProtocol(767);
    private static final long BUDGET = 1L << 20;

    /** 登录态 Encryption Request：{@code [长度][packetId=0x01][…]}，无 dataLength（发生在压缩协商之前）。 */
    private static byte[] encryptionRequest() {
        return rawFrame(0x01, body(120, 3));
    }

    /**
     * 模拟 AES 密文：开头两字节恰好解成 varint 999，而后面只有 40 字节——按 MC 帧格式永远凑不齐一帧。
     * 取固定字节而非随机数，保证测试确定性。
     */
    private static byte[] cipherLikeBytes() {
        byte[] head = {(byte) 0xE7, 0x07};
        return concat(head, body(40, 9));
    }

    @Test
    void cacheLayer_encryptionRequestFirst_neverHoldsCiphertext() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos = new CacheTransformingOutputStream(wire, TABLE, BUDGET);

        byte[] encReq = encryptionRequest();
        cos.write(encReq, 0, encReq.length);
        cos.flush();
        // 首帧是加密请求：整条流此后是密文，CRC 层自我关闭——原样透传，连前导都不写。
        assertArrayEquals(encReq, wire.toByteArray());

        byte[] cipher = cipherLikeBytes();
        cos.write(cipher, 0, cipher.length);
        cos.flush();
        // 关键断言：flush 之后密文已经到了底层流，而不是扣在帧缓冲里等一个永远不会齐的「大帧」。
        assertArrayEquals(concat(encReq, cipher), wire.toByteArray());

        cos.close();
        assertArrayEquals(concat(encReq, cipher), wire.toByteArray());
    }

    @Test
    void cacheLayer_encryptionRequestFirst_clientDecodesByteExact() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos = new CacheTransformingOutputStream(wire, TABLE, BUDGET);
        byte[] in = concat(encryptionRequest(), cipherLikeBytes(), body(300, 5));
        for (int i = 0; i < in.length; i += 7) {
            cos.write(in, i, Math.min(7, in.length - i));
            cos.flush();
        }
        cos.close();

        // 客户端解码端：流首没有 ZNCR 前导 → 原样透传，逐字节一致（含 1 字节一读的极端边界）。
        CacheUntransformingInputStream cis = new CacheUntransformingInputStream(new ByteArrayInputStream(wire.toByteArray()), BUDGET);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int b;
        while ((b = cis.read()) >= 0) {
            decoded.write(b);
        }
        assertArrayEquals(in, decoded.toByteArray());
    }

    @Test
    void cacheLayer_decisionWaitsForEnoughBytes_thenBypasses() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos = new CacheTransformingOutputStream(wire, TABLE, BUDGET);
        byte[] encReq = encryptionRequest();

        // 首帧只到了 1 个字节（长度前缀），还不知道 packetId：先不决定，也不输出任何东西。
        cos.write(encReq, 0, 1);
        cos.flush();
        assertEquals(0, wire.size());

        // 其余到齐后判定为加密请求 → 透传。
        cos.write(encReq, 1, encReq.length - 1);
        cos.flush();
        assertArrayEquals(encReq, wire.toByteArray());
    }

    @Test
    void cacheLayer_firstBytesNotAFrame_bypasses() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos = new CacheTransformingOutputStream(wire, TABLE, BUDGET);

        // 5 个连续的 0xFF 不可能是合法 VarInt：这不是 MC 帧流，别切帧。
        byte[] junk = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1, 2, 3};
        cos.write(junk, 0, junk.length);
        cos.flush();
        assertArrayEquals(junk, wire.toByteArray());
    }

    @Test
    void cacheLayer_ordinaryLoginStillUsesCache() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        CacheTransformingOutputStream cos = new CacheTransformingOutputStream(wire, TABLE, BUDGET);

        // 离线服的首帧是 Set Compression(0x03)：正常切帧，写前导，行为与历史一致。
        byte[] setCompression = rawFrame(0x03, VarIntCodec.encode(1048576));
        cos.write(setCompression, 0, setCompression.length);
        cos.flush();
        assertTrue(startsWithCrcPreamble(wire.toByteArray()), "ordinary login must still go through CRC");

        // 不足一帧的尾巴照旧留待后续，关闭时作为 PASSTHROUGH 吐出——普通游戏流的既有语义不变。
        int before = wire.size();
        byte[] partial = concat(VarIntCodec.encode(500), body(10, 1));
        cos.write(partial, 0, partial.length);
        cos.flush();
        assertEquals(before, wire.size());
        cos.close();
        assertTrue(wire.size() > before);

        CacheUntransformingInputStream cis = new CacheUntransformingInputStream(new ByteArrayInputStream(wire.toByteArray()), BUDGET);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int b;
        while ((b = cis.read()) >= 0) {
            decoded.write(b);
        }
        assertArrayEquals(concat(setCompression, partial), decoded.toByteArray());
    }

    @Test
    void transformLayer_encryptionRequestFirst_neverHoldsCiphertext() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        TransformingOutputStream tos = new TransformingOutputStream(wire, TransformFormat.VERSION_LAYER_A);

        byte[] encReq = encryptionRequest();
        tos.write(encReq, 0, encReq.length);
        tos.flush();
        assertArrayEquals(encReq, wire.toByteArray());

        byte[] cipher = cipherLikeBytes();
        tos.write(cipher, 0, cipher.length);
        tos.flush();
        assertArrayEquals(concat(encReq, cipher), wire.toByteArray());
        tos.close();
        assertFalse(wire.size() == 0);
        assertArrayEquals(concat(encReq, cipher), wire.toByteArray());
    }

    // ---- helpers ----

    private static boolean startsWithCrcPreamble(byte[] wire) {
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

    /** 压缩协商之前的帧：{@code [VarInt 长度][VarInt packetId][body]}，没有 dataLength。 */
    private static byte[] rawFrame(int packetId, byte[] body) {
        byte[] payload = concat(VarIntCodec.encode(packetId), body);
        return concat(VarIntCodec.encode(payload.length), payload);
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
            out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }
}
