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

import cn.tohsaka.factory.zstdnet.core.cache.patch.DeltaCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;

/**
 * CRC 区块引用缓存的底层编解码原语（无状态；缓存状态由调用方的 {@link LruByteCache} 持有）。
 * 写原语供编码端（{@link CacheTransformingOutputStream}）使用；{@link #decodeBlock} 供解码端
 * （{@link CacheUntransformingInputStream}）与单测使用。契约见 {@link ChunkCacheFormat}。
 */
public final class ChunkCacheCodec {
    /** {@link #decodeBlock} 返回值：当前字节不足以解出一个完整 block，需更多上游字节。 */
    public static final int NEED_MORE = -1;

    private ChunkCacheCodec() {
    }

    // ---- 写原语（编码端） ----

    public static void writePreamble(OutputStream out) throws IOException {
        writePreamble(out, ChunkCacheFormat.VERSION_REF);
    }

    /** 写流首 PREAMBLE，版本为协商出的生效版本（v2 时客户端据此知道 WARM_REF 可能出现）。 */
    public static void writePreamble(OutputStream out, int version) throws IOException {
        out.write(ChunkCacheFormat.PREAMBLE_MAGIC);
        out.write(version);
    }

    public static void writePassthrough(OutputStream out, byte[] buf, int off, int len) throws IOException {
        out.write(ChunkCacheFormat.BLOCK_PASSTHROUGH);
        writeVarInt(out, len);
        out.write(buf, off, len);
    }

    public static void writeFull(OutputStream out, long hash, byte[] frame) throws IOException {
        out.write(ChunkCacheFormat.BLOCK_FULL);
        writeLong8(out, hash);
        writeVarInt(out, frame.length);
        out.write(frame);
    }

    public static void writeRef(OutputStream out, long hash) throws IOException {
        out.write(ChunkCacheFormat.BLOCK_REF);
        writeLong8(out, hash);
    }

    /** 写跨会话 WARM_REF（16 字节 hash128）。客户端将从磁盘缓存重放对应字节。 */
    public static void writeWarmRef(OutputStream out, Hash128 hash) throws IOException {
        out.write(ChunkCacheFormat.BLOCK_WARM_REF);
        byte[] tmp = new byte[ChunkCacheFormat.HASH128_BYTES];
        hash.writeBytes(tmp, 0);
        out.write(tmp);
    }

    /**
     * 写 PATCH（v3）：{@code baseHash(8) newHash(8) deltaLen(varint) delta}。客户端用会话内基线 {@code baseHash}
     * 的字节 + {@code delta} 重建出新帧，并校验其 {@code content128().hi()==newHash}。
     */
    public static void writePatch(OutputStream out, long baseHash, long newHash, byte[] delta) throws IOException {
        out.write(ChunkCacheFormat.BLOCK_PATCH);
        writeLong8(out, baseHash);
        writeLong8(out, newHash);
        writeVarInt(out, delta.length);
        out.write(delta);
    }

    /**
     * 提取全区块包的区块坐标 key（{@code x<<32 | (z & 0xFFFFFFFF)}）到 {@code out[0]}；字节不足时返回 {@code false}。
     * <b>仅编码端 PATCH 用</b>：跳过 dataLength(play 阶段==0) + packetId varint 后，紧跟的是大端 {@code int x}、{@code int z}
     * （{@code ClientboundLevelChunkWithLightPacket} 在 1.18.2~1.21.x 一致以 {@code writeInt} 写 x/z）。
     * 非全区块包（未来扩表的其它可缓存包）解析失败 → {@code false} → 不参与 PATCH（仅影响压缩、不影响正确性）。
     */
    public static boolean readChunkPosKey(byte[] buf, int payloadStart, int frameEnd, long[] out) {
        VarIntRead dl = VarIntCodec.read(buf, payloadStart, frameEnd);
        if (dl == null || dl.value() != 0) {
            return false;
        }
        VarIntRead pid = VarIntCodec.read(buf, dl.next(), frameEnd);
        if (pid == null) {
            return false;
        }
        int p = pid.next();
        if (frameEnd - p < 8) {
            return false;
        }
        long x = readInt(buf, p);
        long z = readInt(buf, p + 4);
        out[0] = (x << 32) | (z & 0xFFFFFFFFL);
        return true;
    }

    /**
     * 判断一帧是否为可缓存包：仅编码端用。读 dataLength（play 阶段恒为 0）+ packetId，查表。
     * 表为 null / 非 play 帧 / 非可缓存 id 一律返回 false（→ 走 PASSTHROUGH）。
     */
    public static boolean isCacheableFrame(byte[] buf, int payloadStart, int frameEnd, CacheablePacketTable table) {
        if (table == null) {
            return false;
        }
        VarIntRead dl = VarIntCodec.read(buf, payloadStart, frameEnd);
        if (dl == null || dl.value() != 0) {
            return false;
        }
        VarIntRead pid = VarIntCodec.read(buf, dl.next(), frameEnd);
        if (pid == null) {
            return false;
        }
        return table.isCacheable(pid.value());
    }

    // ---- 解码（解码端 + 单测） ----

    /**
     * 解出 {@code buf[start, end)} 起点处的一个 block，把还原出的原始帧字节写入 {@code sink}，并对 FULL/REF
     * 维护 {@code cache}。返回消费的字节数；不足一个完整 block 时返回 {@link #NEED_MORE}（调用方需补字节）。
     *
     * @param warm  跨会话 warm 快照（hash128 → 帧字节），供 {@link ChunkCacheFormat#BLOCK_WARM_REF} 重放；
     *              {@code null} 表示本连接无持久化缓存（遇 WARM_REF 即 fail-closed）。
     * @param store 跨会话持久层；非 {@code null} 时把每个 FULL 帧落盘（写穿透），供下次会话 WARM_REF。
     * @throws IOException 非法 block 类型 / 长度越界 / REF 指向缺失基线 / WARM_REF 指向缺失持久条目
     *                     （fail-closed，绝不产出错误字节）
     */
    public static int decodeBlock(byte[] buf, int start, int end, LruByteCache cache,
                                  Map<Hash128, byte[]> warm, ChunkCacheStore store, ByteArrayOutputStream sink)
        throws IOException {
        if (start >= end) {
            return NEED_MORE;
        }
        int type = buf[start] & 0xFF;
        int pos = start + 1;
        switch (type) {
            case ChunkCacheFormat.BLOCK_PASSTHROUGH: {
                VarIntRead lenRead = readLen(buf, pos, end, ChunkCacheFormat.MAX_BLOCK_PAYLOAD);
                if (lenRead == null) {
                    return NEED_MORE;
                }
                int len = lenRead.value();
                int dataStart = lenRead.next();
                int dataEnd = dataStart + len;
                if (dataEnd > end) {
                    return NEED_MORE;
                }
                sink.write(buf, dataStart, len);
                return dataEnd - start;
            }
            case ChunkCacheFormat.BLOCK_FULL: {
                if (end - pos < ChunkCacheFormat.HASH_BYTES) {
                    return NEED_MORE;
                }
                long hash = readLong8(buf, pos);
                int afterHash = pos + ChunkCacheFormat.HASH_BYTES;
                VarIntRead lenRead = readLen(buf, afterHash, end, ChunkCacheFormat.MAX_FRAME_LENGTH);
                if (lenRead == null) {
                    return NEED_MORE;
                }
                int len = lenRead.value();
                int dataStart = lenRead.next();
                int dataEnd = dataStart + len;
                if (dataEnd > end) {
                    return NEED_MORE;
                }
                byte[] frame = Arrays.copyOfRange(buf, dataStart, dataEnd);
                cache.put(hash, frame);
                if (store != null) {
                    // 写穿透：以 128 位内容哈希落盘，供下次会话 WARM_REF（与编码端的 WARM_REF 键一致）。
                    store.put(Hashing.content128(frame), frame);
                }
                sink.write(frame, 0, frame.length);
                return dataEnd - start;
            }
            case ChunkCacheFormat.BLOCK_REF: {
                if (end - pos < ChunkCacheFormat.HASH_BYTES) {
                    return NEED_MORE;
                }
                long hash = readLong8(buf, pos);
                byte[] frame = cache.get(hash);
                if (frame == null) {
                    throw new IOException("zstdnet chunk-cache: REF to missing baseline (cache desync)");
                }
                sink.write(frame, 0, frame.length);
                return pos + ChunkCacheFormat.HASH_BYTES - start;
            }
            case ChunkCacheFormat.BLOCK_WARM_REF: {
                if (end - pos < ChunkCacheFormat.HASH128_BYTES) {
                    return NEED_MORE;
                }
                Hash128 hash = Hash128.fromBytes(buf, pos);
                byte[] frame = warm == null ? null : warm.get(hash);
                if (frame == null) {
                    throw new IOException("zstdnet chunk-cache: WARM_REF to missing persisted baseline " + hash);
                }
                if (store != null) {
                    store.touch(hash); // 刷新跨会话近用度（D4-LRU），仅影响缓存有效性、不影响正确性
                }
                sink.write(frame, 0, frame.length);
                return pos + ChunkCacheFormat.HASH128_BYTES - start;
            }
            case ChunkCacheFormat.BLOCK_PATCH: {
                // baseHash(8) newHash(8) deltaLen(varint) delta
                if (end - pos < ChunkCacheFormat.HASH_BYTES * 2) {
                    return NEED_MORE;
                }
                long baseHash = readLong8(buf, pos);
                long newHash = readLong8(buf, pos + ChunkCacheFormat.HASH_BYTES);
                int afterHashes = pos + ChunkCacheFormat.HASH_BYTES * 2;
                VarIntRead lenRead = readLen(buf, afterHashes, end, ChunkCacheFormat.MAX_BLOCK_PAYLOAD);
                if (lenRead == null) {
                    return NEED_MORE;
                }
                int len = lenRead.value();
                int dataStart = lenRead.next();
                int dataEnd = dataStart + len;
                if (dataEnd > end) {
                    return NEED_MORE;
                }
                // 基线只取会话内 LRU（与编码端对称用 peek，不触碰 LRU → lock-step 不变）。
                byte[] base = cache.peek(baseHash);
                if (base == null) {
                    throw new IOException("zstdnet chunk-cache: PATCH base missing (cache desync)");
                }
                byte[] delta = Arrays.copyOfRange(buf, dataStart, dataEnd);
                byte[] result = DeltaCodec.apply(base, delta);
                // 校验门：结构无关地重建后，须与编码端的新帧逐位一致（content128.hi==newHash）。
                Hash128 actual = Hashing.content128(result);
                if (actual.hi() != newHash) {
                    throw new IOException("zstdnet chunk-cache: PATCH result hash mismatch (corruption)");
                }
                cache.put(newHash, result); // 与 FULL 同样进会话内 LRU（编码端亦 put）→ lock-step
                if (store != null) {
                    store.put(actual, result); // 写穿透：重建结果也可供下次会话 WARM_REF
                }
                sink.write(result, 0, result.length);
                return dataEnd - start;
            }
            default:
                throw new IOException("zstdnet chunk-cache: unknown block type " + type);
        }
    }

    // ---- 内部 ----

    /**
     * 读一个长度 varint：返回 null 表示<b>字节不足</b>（NEED_MORE）。若可读字节已 ≥5 仍解不出，或值越界，
     * 视为流损坏 → 抛 IOException（fail-closed）。
     */
    private static VarIntRead readLen(byte[] buf, int pos, int end, int maxValue) throws IOException {
        VarIntRead read = VarIntCodec.read(buf, pos, end);
        if (read == null) {
            if (end - pos >= 5) {
                throw new IOException("zstdnet chunk-cache: malformed length varint");
            }
            return null;
        }
        int value = read.value();
        if (value < 0 || value > maxValue) {
            throw new IOException("zstdnet chunk-cache: block length out of range: " + value);
        }
        return read;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        out.write(VarIntCodec.encode(value));
    }

    private static void writeLong8(OutputStream out, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }

    private static long readLong8(byte[] buf, int off) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buf[off + i] & 0xFFL);
        }
        return value;
    }

    /** 读大端 4 字节有符号 int（区块坐标 x/z）。 */
    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
            | ((buf[off + 1] & 0xFF) << 16)
            | ((buf[off + 2] & 0xFF) << 8)
            | (buf[off + 3] & 0xFF);
    }
}
