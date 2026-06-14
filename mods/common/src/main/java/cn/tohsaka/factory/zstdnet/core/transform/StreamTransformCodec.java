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

package cn.tohsaka.factory.zstdnet.core.transform;

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 变换 block 的纯编解码（无 IO、无状态）。Layer A：把一批完整 MC 帧拆成
 * L_SECTION（长度前缀逐字节原样）+ P_SECTION（载荷拼接）。逆向严格还原原始字节流。
 *
 * <p>{@link #decodeBlock} 为增量友好：缓冲不足一个完整 block 时返回 {@link #NEED_MORE}，
 * 调用方补字节后重试；遇到非法类型/越界/损坏 varint 抛 {@link IOException}（fail-closed）。
 */
public final class StreamTransformCodec {
    /** {@link #decodeBlock} 的返回值：当前缓冲不足以解出一个完整 block。 */
    public static final int NEED_MORE = -1;

    private StreamTransformCodec() {
    }

    // ---- 编码 ----

    /** 写出一个 Layer A block（不含流首 PREAMBLE，由调用方负责一次性写前导）。 */
    public static void encodeLayerABlock(PacketFramer.Batch batch, OutputStream sink) throws IOException {
        ByteArrayOutputStream lSection = new ByteArrayOutputStream();
        ByteArrayOutputStream pSection = new ByteArrayOutputStream();

        int cursor = batch.start;
        for (int i = 0; i < batch.count; i++) {
            VarIntRead prefix = VarIntCodec.read(batch.buffer, cursor, batch.end);
            // batch 已由 PacketFramer 保证是完整帧，这里必非 null。
            int prefixStart = cursor;
            int prefixEnd = prefix.next();
            int payloadLen = prefix.value();
            lSection.write(batch.buffer, prefixStart, prefixEnd - prefixStart);
            pSection.write(batch.buffer, prefixEnd, payloadLen);
            cursor = prefixEnd + payloadLen;
        }

        byte[] lBytes = lSection.toByteArray();
        sink.write(TransformFormat.BLOCK_LAYER_A);
        sink.write(VarIntCodec.encode(batch.count));
        sink.write(VarIntCodec.encode(lBytes.length));
        sink.write(lBytes);
        pSection.writeTo(sink);
    }

    /** 写出一个原样尾块（流尾不足一帧的剩余字节，逆向原样还原）。 */
    public static void encodeRawTailBlock(byte[] tail, OutputStream sink) throws IOException {
        sink.write(TransformFormat.BLOCK_RAW_TAIL);
        sink.write(VarIntCodec.encode(tail.length));
        if (tail.length > 0) {
            sink.write(tail);
        }
    }

    // ---- 解码 ----

    /**
     * 尝试从 {@code buf[off, end)} 解出一个完整 block，把还原出的原始字节写入 {@code out}。
     *
     * @return 已消费的字节数（&gt;0）；或 {@link #NEED_MORE} 表示缓冲不足、需补更多字节
     * @throws IOException 非法块类型 / 损坏 varint / 越界（流损坏或错位，fail-closed）
     */
    public static int decodeBlock(byte[] buf, int off, int end, ByteArrayOutputStream out) throws IOException {
        if (off >= end) {
            return NEED_MORE;
        }
        int type = buf[off] & 0xFF;
        int p = off + 1;

        if (type == TransformFormat.BLOCK_LAYER_A) {
            return decodeLayerA(buf, off, p, end, out);
        }
        if (type == TransformFormat.BLOCK_RAW_TAIL) {
            return decodeRawTail(buf, off, p, end, out);
        }
        throw new IOException("zstdnet transform: unknown block type 0x" + Integer.toHexString(type));
    }

    private static int decodeLayerA(byte[] buf, int blockStart, int p, int end, ByteArrayOutputStream out) throws IOException {
        VarIntRead frameCount = readVarintOrNeedMore(buf, p, end);
        if (frameCount == null) {
            return NEED_MORE;
        }
        int count = frameCount.value();
        p = frameCount.next();
        if (count < 0) {
            throw new IOException("zstdnet transform: negative frame count");
        }

        VarIntRead lLen = readVarintOrNeedMore(buf, p, end);
        if (lLen == null) {
            return NEED_MORE;
        }
        int lSectionLen = lLen.value();
        p = lLen.next();
        if (lSectionLen < 0 || lSectionLen > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: L section length out of range: " + lSectionLen);
        }
        if (p + lSectionLen > end) {
            return NEED_MORE; // L 段未到齐
        }
        int lStart = p;
        int lEnd = p + lSectionLen;

        // 第一遍：解析 L 段得到各帧载荷长度，校验恰好消费完且帧数吻合，累计 P 段长度。
        long pSum = 0;
        int lp = lStart;
        for (int i = 0; i < count; i++) {
            VarIntRead r = VarIntCodec.read(buf, lp, lEnd);
            if (r == null) {
                throw new IOException("zstdnet transform: corrupt L section");
            }
            int payloadLen = r.value();
            if (payloadLen < 0 || payloadLen > TransformFormat.MAX_FRAME_LENGTH) {
                throw new IOException("zstdnet transform: payload length out of range: " + payloadLen);
            }
            pSum += payloadLen;
            lp = r.next();
            if (pSum > TransformFormat.MAX_BLOCK_PAYLOAD) {
                throw new IOException("zstdnet transform: block payload too large");
            }
        }
        if (lp != lEnd) {
            throw new IOException("zstdnet transform: L section frame count mismatch");
        }

        long pEnd = (long) lEnd + pSum;
        if (pEnd > end) {
            return NEED_MORE; // P 段未到齐
        }

        // 第二遍：逐帧还原 长度前缀(原样) + 载荷。
        lp = lStart;
        int payOff = lEnd;
        for (int i = 0; i < count; i++) {
            VarIntRead r = VarIntCodec.read(buf, lp, lEnd);
            int prefixStart = lp;
            int prefixEnd = r.next();
            int payloadLen = r.value();
            out.write(buf, prefixStart, prefixEnd - prefixStart);
            out.write(buf, payOff, payloadLen);
            lp = prefixEnd;
            payOff += payloadLen;
        }
        return (int) pEnd - blockStart;
    }

    private static int decodeRawTail(byte[] buf, int blockStart, int p, int end, ByteArrayOutputStream out) throws IOException {
        VarIntRead tailLen = readVarintOrNeedMore(buf, p, end);
        if (tailLen == null) {
            return NEED_MORE;
        }
        int len = tailLen.value();
        p = tailLen.next();
        if (len < 0 || len > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: raw tail length out of range: " + len);
        }
        if (p + len > end) {
            return NEED_MORE;
        }
        out.write(buf, p, len);
        return (p + len) - blockStart;
    }

    /**
     * 读 varint：成功返回；字节不足返回 {@code null}（需补更多）；
     * 已有 ≥5 字节仍解不出则视为损坏抛异常（合法 varint 至多 5 字节）。
     */
    private static VarIntRead readVarintOrNeedMore(byte[] buf, int off, int end) throws IOException {
        VarIntRead r = VarIntCodec.read(buf, off, end);
        if (r != null) {
            return r;
        }
        if (end - off >= 5) {
            throw new IOException("zstdnet transform: corrupt varint");
        }
        return null;
    }
}
